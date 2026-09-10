package com.ghostphoto.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.ghostphoto.app.data.SharedPrefsSnapshotStorage
import com.ghostphoto.app.matcher.CloudCandidate
import com.ghostphoto.app.matcher.CloudCandidateParser
import com.ghostphoto.app.matcher.LiveGroundTruthCoordinator
import com.ghostphoto.app.matcher.LocalMediaRecord
import com.ghostphoto.app.matcher.MediaLifecycleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P2-A: Google Photos 전용 100% Read-Only 접근성 수집기 (Collector).
 *
 * 엄격한 원칙:
 * 1. Zero-Delete / Zero-Mutation: 휴지통, 삭제, delete, trash 관련 노드 조작 일체 금지.
 * 2. 오직 UI 탐색 및 메타데이터 수집(Swipe / Parse)만 수행.
 * 3. 수집된 CloudCandidate 리스트를 누적하여 독립 평가기(GroundTruthEvaluator)에 전달.
 */
class GhostAccessibilityService : AccessibilityService() {

    enum class WorkflowMode {
        COLLECT_ONLY,
        FIND_AND_SELECT,
        SAFE_PIPELINE
    }

    enum class SelectionSubState {
        IDLE,
        COLLECTING_METADATA,
        NAVIGATING_TO_GRID,
        SCROLLING_GRID,
        SELECTING_ITEMS,
        DONE,
        ABORTED
    }

    companion object {
        private const val TAG = "GhostAccessCollector"

        var instance: GhostAccessibilityService? = null
            private set

        var isCollecting: Boolean = false
            private set

        var workflowMode: WorkflowMode = WorkflowMode.COLLECT_ONLY
            private set

        var selectionSubState: SelectionSubState = SelectionSubState.IDLE
            private set

        val observedCandidates = mutableListOf<CloudCandidate>()
        val targetMissingRecords = mutableListOf<LocalMediaRecord>()
        val matchedCandidateIds = mutableSetOf<String>()

        private var lastActionTime = 0L
        private var lastSwipedTime = 0L
        private var consecutiveSamePhotoSwipes = 0
        private var swipeUpAttempts = 0
        private var gridScrollAttempts = 0

        var lastPipelineMetrics: PipelineMetrics? = null
            private set

        private var currentPipelineJob: Job? = null

        var onStatusChanged: ((String) -> Unit)? = null
        var onCandidateObserved: ((CloudCandidate, totalObserved: Int) -> Unit)? = null

        fun startSafePipeline(limit: Int = 20, month: String = "2026-09") {
            val service = instance ?: run {
                Log.e(TAG, "Cannot start pipeline: GhostAccessibilityService instance is null")
                return
            }
            val storage = SharedPrefsSnapshotStorage(service)
            val snapshot = storage.loadSnapshot() ?: emptyList()
            val missing = snapshot.filter { it.state == MediaLifecycleState.MISSING_FROM_LOCAL_SCAN }
            val targetRecords = if (month.isNotBlank()) {
                val sdf = SimpleDateFormat("yyyy-MM", Locale.KOREA)
                missing.filter { sdf.format(Date(it.takenAtMillis)) == month }
            } else {
                missing
            }.take(limit)

            Log.i(TAG, "startSafePipeline loaded ${targetRecords.size} targets for month '$month' (limit=$limit)")
            startSafePipeline(targetRecords, month)
        }

        fun startSafePipeline(targets: List<LocalMediaRecord>, unifiedQuery: String = "2026-09") {
            val service = instance ?: run {
                Log.e(TAG, "Cannot start pipeline: GhostAccessibilityService instance is null")
                return
            }
            currentPipelineJob?.cancel()

            workflowMode = WorkflowMode.SAFE_PIPELINE
            isCollecting = true
            service.postStatus("안전 E2E 파이프라인 시작 (타겟 ${targets.size}건, Zero-Delete)")

            // 1. Foreground Service 시작하여 Samsung Freecess 방지
            val nm = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel("pipeline_fgs", "Safe Pipeline", NotificationManager.IMPORTANCE_LOW)
                nm.createNotificationChannel(channel)
            }
            val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(service, "pipeline_fgs")
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(service)
            }.apply {
                setContentTitle("GhostPhoto E2E Benchmark")
                setContentText("구글포토 소실 사진 검증/선택 진행 중 (Zero-Delete)")
                setSmallIcon(android.R.drawable.sym_def_app_icon)
            }.build()

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    service.startForeground(1001, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    service.startForeground(1001, notif)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to startForeground: ${e.message}")
            }

            // 2. Partial WakeLock 획득
            val pm = service.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ghostphoto:pipeline_wakelock").apply {
                setReferenceCounted(false)
                acquire(10 * 60 * 1000L)
            }

            currentPipelineJob = service.serviceScope.launch {
                try {
                    val engine = SafePipelineEngine(service) { status ->
                        service.postStatus(status)
                    }
                    val metrics = engine.executePipeline(targets, unifiedQuery)
                    lastPipelineMetrics = metrics
                    service.postStatus("파이프라인 정상 완료: ${metrics.finalSelectedCount}건 선택됨 (Zero-Delete)")
                } catch (e: Exception) {
                    Log.e(TAG, "SafePipelineEngine execution failed", e)
                    service.postStatus("파이프라인 오류: ${e.message}")
                } finally {
                    try {
                        if (wakeLock.isHeld) wakeLock.release()
                    } catch (e: Exception) { }
                    try {
                        service.stopForeground(true)
                    } catch (e: Exception) { }
                    isCollecting = false
                    workflowMode = WorkflowMode.COLLECT_ONLY
                }
            }
        }

        fun startCollecting() {
            observedCandidates.clear()
            targetMissingRecords.clear()
            matchedCandidateIds.clear()
            consecutiveSamePhotoSwipes = 0
            swipeUpAttempts = 0
            workflowMode = WorkflowMode.COLLECT_ONLY
            selectionSubState = SelectionSubState.COLLECTING_METADATA
            isCollecting = true
            instance?.postStatus("수집 모드 활성화 (100% Read-Only 탐색 대기 중)")
        }

        fun startFindAndSelect(targets: List<LocalMediaRecord>) {
            targetMissingRecords.clear()
            targetMissingRecords.addAll(targets)
            matchedCandidateIds.clear()
            consecutiveSamePhotoSwipes = 0
            swipeUpAttempts = 0
            gridScrollAttempts = 0
            workflowMode = WorkflowMode.FIND_AND_SELECT
            isCollecting = true

            // 타겟 3건의 identity(일시 및 서명)를 등록하고 그리드 선택 모드로 진입
            selectionSubState = SelectionSubState.SCROLLING_GRID
            instance?.postStatus("소실 ${targets.size}건 그리드 탐색 & 자동 선택 모드 활성화 (Zero-Delete)")
        }

        fun stopCollecting() {
            currentPipelineJob?.cancel()
            isCollecting = false
            workflowMode = WorkflowMode.COLLECT_ONLY
            selectionSubState = SelectionSubState.IDLE
            consecutiveSamePhotoSwipes = 0
            swipeUpAttempts = 0
            instance?.postStatus("작업 종료 (총 ${observedCandidates.size}개 후보 수집됨)")
        }

        fun clearObserved() {
            observedCandidates.clear()
            targetMissingRecords.clear()
            matchedCandidateIds.clear()
            consecutiveSamePhotoSwipes = 0
            swipeUpAttempts = 0
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.ghostphoto.app.START_PIPELINE" -> {
                    val limit = intent.getIntExtra("limit", 20)
                    val month = intent.getStringExtra("month") ?: "2026-09"
                    Log.i(TAG, "Broadcast received: START_PIPELINE (limit=$limit, month=$month)")
                    startSafePipeline(limit, month)
                }
                "com.ghostphoto.app.START_COLLECT" -> {
                    startCollecting()
                    Log.i(TAG, "Broadcast received: START_COLLECT")
                }
                "com.ghostphoto.app.STOP_COLLECT" -> {
                    stopCollecting()
                    Log.i(TAG, "Broadcast received: STOP_COLLECT")
                }
                "com.ghostphoto.app.PRINT_REPORT" -> {
                    val coordinator = LiveGroundTruthCoordinator()
                    val (metrics, _) = coordinator.evaluateLiveCandidates(observedCandidates)
                    Log.i(TAG, "=== LIVE REPORT ===\n" + metrics.toSummaryReport())
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "GhostAccessibilityService connected (Read-Only Mode)")
        postStatus("접근성 수집기 연결됨 (Read-Only 준비 완료)")

        val filter = IntentFilter().apply {
            addAction("com.ghostphoto.app.START_PIPELINE")
            addAction("com.ghostphoto.app.START_COLLECT")
            addAction("com.ghostphoto.app.STOP_COLLECT")
            addAction("com.ghostphoto.app.PRINT_REPORT")
        }
        ContextCompat.registerReceiver(this, commandReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(commandReceiver)
        } catch (e: Exception) {
            // ignore
        }
        serviceScope.cancel()
        instance = null
        isCollecting = false
        Log.i(TAG, "GhostAccessibilityService destroyed")
    }

    override fun onInterrupt() {
        Log.w(TAG, "GhostAccessibilityService interrupted")
        isCollecting = false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isCollecting || event == null) return
        if (event.packageName != "com.google.android.apps.photos") return
        if (workflowMode == WorkflowMode.SAFE_PIPELINE) return // SafePipelineEngine drives state asynchronously

        val now = System.currentTimeMillis()
        if (now - lastActionTime < 450) return // 스로틀링

        val rootNode = rootInActiveWindow ?: return
        try {
            if (workflowMode == WorkflowMode.FIND_AND_SELECT) {
                processFindAndSelectFlow(rootNode)
            } else {
                processPhotosWindow(rootNode)
            }
        } finally {
            // Android O+ 에서 안전하게 관리
        }
    }

    private fun processFindAndSelectFlow(root: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()

        // 뷰어 화면 여부 확인
        val hasPhotoControls = root.findAccessibilityNodeInfosByViewId("com.google.android.apps.photos:id/photos_pager_photo_bar").isNotEmpty()
        val hasPhotoContainer = root.findAccessibilityNodeInfosByViewId("com.google.android.apps.photos:id/photo_container").isNotEmpty()
        val hasDetails = root.findAccessibilityNodeInfosByViewId("com.google.android.apps.photos:id/details_container").isNotEmpty()
        val hasGrid = root.findAccessibilityNodeInfosByViewId(GooglePhotosGridSelector.ID_RECYCLER_VIEW).isNotEmpty()

        when (selectionSubState) {
            SelectionSubState.COLLECTING_METADATA -> {
                if (hasPhotoControls || hasPhotoContainer) {
                    val texts = mutableListOf<String>()
                    val descs = mutableListOf<String>()
                    collectTextsAndDescs(root, texts, descs)
                    val rawCandidate = CloudCandidateParser.parseFromTexts(texts, descs)
                    val candidate = if (rawCandidate.filename == null && hasDetails) {
                        val fallbackName = texts.find { it.startsWith("제목 없음") || it.startsWith("Untitled") || it.contains("...") }
                            ?: "${rawCandidate.dateStr ?: "cloud"}_${rawCandidate.width ?: 0}x${rawCandidate.height ?: 0}"
                        rawCandidate.copy(filename = fallbackName)
                    } else {
                        rawCandidate
                    }

                    if (candidate.filename != null) {
                        swipeUpAttempts = 0
                        val alreadyObserved = observedCandidates.any { it.filename == candidate.filename }
                        if (!alreadyObserved) {
                            observedCandidates.add(candidate)
                            postCandidateObserved(candidate, observedCandidates.size)

                            // GhostMatcher로 타겟 대조
                            val matcher = com.ghostphoto.app.matcher.GhostMatcher()
                            for (target in targetMissingRecords) {
                                val match = matcher.match(target, listOf(candidate), isFullTraversalCompleted = false)
                                if (match.verdict == com.ghostphoto.app.matcher.MatchVerdict.CONFIDENT_MATCH) {
                                    matchedCandidateIds.add(candidate.candidateId)
                                    postStatus("타겟 매칭 [${matchedCandidateIds.size}/${targetMissingRecords.size}]: ${candidate.filename}")
                                }
                            }
                        }

                        // 타겟 전수 확인 시 그리드로 복귀
                        if (matchedCandidateIds.size >= targetMissingRecords.size && targetMissingRecords.isNotEmpty()) {
                            postStatus("✅ 소실 3건 전수 재식별 완료! 그리드로 복귀하여 선택합니다.")
                            selectionSubState = SelectionSubState.NAVIGATING_TO_GRID
                            lastActionTime = now
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            return
                        }

                        // 다음 사진 스와이프
                        if (now - lastSwipedTime > 1200) {
                            lastSwipedTime = now
                            lastActionTime = now
                            postStatus("다음 사진으로 스와이프...")
                            swipeNextPhoto(isDetailsOpen = hasDetails)
                        }
                    } else if (!hasDetails) {
                        if (now - lastSwipedTime > 900 && swipeUpAttempts < 3) {
                            swipeUpAttempts++
                            lastSwipedTime = now
                            lastActionTime = now
                            postStatus("세부정보 노출 시도 (위로 스와이프 $swipeUpAttempts/3)")
                            swipeUpForDetails()
                        }
                    }
                } else if (hasGrid && matchedCandidateIds.size >= targetMissingRecords.size && targetMissingRecords.isNotEmpty()) {
                    selectionSubState = SelectionSubState.SCROLLING_GRID
                    postStatus("그리드 화면 도달 -> 타겟 3건 선택 탐색 시작")
                }
            }

            SelectionSubState.NAVIGATING_TO_GRID -> {
                if (hasGrid && !hasPhotoControls) {
                    selectionSubState = SelectionSubState.SCROLLING_GRID
                    postStatus("그리드 복귀 완료. 타겟 사진 위치 확인 중...")
                    lastActionTime = now + 400
                } else if (hasPhotoControls || hasPhotoContainer) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    lastActionTime = now + 400
                }
            }

            SelectionSubState.SCROLLING_GRID,
            SelectionSubState.SELECTING_ITEMS -> {
                if (!hasGrid) return

                val matchingNodes = GooglePhotosGridSelector.findTargetPhotoNodes(root, targetMissingRecords)
                val dm = resources.displayMetrics
                val allVisible = matchingNodes.size >= targetMissingRecords.size && matchingNodes.take(targetMissingRecords.size).all {
                    GooglePhotosGridSelector.isNodeVisibleInViewport(GooglePhotosGridSelector.getNodeBounds(it), dm.heightPixels)
                }

                if (!allVisible && gridScrollAttempts < 4) {
                    gridScrollAttempts++
                    lastActionTime = now
                    postStatus("타겟 사진 노출을 위해 스크롤 ($gridScrollAttempts/4)...")
                    dispatchScrollUp()
                    return
                }

                // 타겟 3개 노드가 화면에 안정적으로 잡힌 상태
                val targetNodes = matchingNodes.take(targetMissingRecords.size)
                if (targetNodes.isEmpty()) return

                val currentCount = GooglePhotosGridSelector.getSelectionCount(root)
                when {
                    currentCount == 0 -> {
                        // 1번째 사진 롱클릭 (선택 모드 진입)
                        selectionSubState = SelectionSubState.SELECTING_ITEMS
                        val bounds = GooglePhotosGridSelector.getNodeBounds(targetNodes[0])
                        postStatus("1번째 사진 길게 누름 (선택 진입): ${targetNodes[0].contentDescription}")
                        lastActionTime = now
                        dispatchLongClick(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                        targetNodes[0].performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                    }
                    currentCount in 1 until targetMissingRecords.size -> {
                        // 미선택된 다음 타겟 사진 탭
                        val nextNode = targetNodes.firstOrNull { !it.isChecked }
                        if (nextNode != null) {
                            val bounds = GooglePhotosGridSelector.getNodeBounds(nextNode)
                            postStatus("${currentCount + 1}번째 사진 선택 탭: ${nextNode.contentDescription}")
                            lastActionTime = now
                            dispatchTap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                            nextNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }
                    }
                    currentCount == targetMissingRecords.size -> {
                        // 정확히 기대 수량 선택 완료! (Zero-Delete 유지, 검증 성공)
                        selectionSubState = SelectionSubState.DONE
                        isCollecting = false
                        postStatus("🎉 [검증 성공] Google Photos에서 소실 ${targetMissingRecords.size}건 정확히 선택 완료! (Zero-Delete 유지, 안전 종료)")
                    }
                    currentCount > targetMissingRecords.size -> {
                        // 안전 중단 (오선택 방지)
                        selectionSubState = SelectionSubState.ABORTED
                        isCollecting = false
                        postStatus("⚠️ [안전 중단] 선택 개수 초과 (${currentCount}개 선택됨). 즉시 중단합니다.")
                    }
                }
            }

            SelectionSubState.DONE,
            SelectionSubState.ABORTED,
            SelectionSubState.IDLE -> {
                // 종료 상태
            }
        }
    }

    private fun checkAndAdvanceSelection() {
        if (workflowMode != WorkflowMode.FIND_AND_SELECT || !isCollecting) return
        val root = rootInActiveWindow ?: return
        lastActionTime = 0L
        processFindAndSelectFlow(root)
    }

    private fun dispatchLongClick(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 750)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                mainHandler.postDelayed({
                    checkAndAdvanceSelection()
                }, 600)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "dispatchLongClick cancelled")
            }
        }, null)
    }

    private fun dispatchTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                mainHandler.postDelayed({
                    checkAndAdvanceSelection()
                }, 500)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "dispatchTap cancelled")
            }
        }, null)
    }

    private fun dispatchScrollUp() {
        val dm = resources.displayMetrics
        val centerX = dm.widthPixels / 2f
        val startY = dm.heightPixels * 0.70f
        val endY = dm.heightPixels * 0.35f

        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 350)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                mainHandler.postDelayed({
                    checkAndAdvanceSelection()
                }, 500)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "dispatchScrollUp cancelled")
            }
        }, null)
    }

    private fun processPhotosWindow(root: AccessibilityNodeInfo) {
        val texts = mutableListOf<String>()
        val descs = mutableListOf<String>()
        collectTextsAndDescs(root, texts, descs)

        // 뷰어 화면인지 확인 (사진 컨트롤 바 존재 여부 또는 사진 보기 영역)
        val hasPhotoControls = root.findAccessibilityNodeInfosByViewId("com.google.android.apps.photos:id/photos_pager_photo_bar").isNotEmpty()
        val hasPhotoContainer = root.findAccessibilityNodeInfosByViewId("com.google.android.apps.photos:id/photo_container").isNotEmpty()
        val hasDetails = root.findAccessibilityNodeInfosByViewId("com.google.android.apps.photos:id/details_container").isNotEmpty()

        if (hasPhotoControls || hasPhotoContainer) {
            val rawCandidate = CloudCandidateParser.parseFromTexts(texts, descs)
            val candidate = if (rawCandidate.filename == null && hasDetails) {
                // 세부정보는 열려있으나 파일명이 비표준(제목 없음 등)인 경우
                val fallbackName = texts.find { it.startsWith("제목 없음") || it.startsWith("Untitled") || it.contains("...") }
                    ?: "${rawCandidate.dateStr ?: "cloud"}_${rawCandidate.width ?: 0}x${rawCandidate.height ?: 0}"
                rawCandidate.copy(filename = fallbackName)
            } else {
                rawCandidate
            }

            if (candidate.filename != null) {
                swipeUpAttempts = 0
                val now = System.currentTimeMillis()
                // 이미 수집된 후보인지 확인 (파일명 기준 중복 억제)
                val alreadyObserved = observedCandidates.any { it.filename == candidate.filename }
                if (!alreadyObserved) {
                    consecutiveSamePhotoSwipes = 0
                    observedCandidates.add(candidate)
                    postCandidateObserved(candidate, observedCandidates.size)
                    postStatus("후보 수집 [${observedCandidates.size}]: ${candidate.filename} (${candidate.width}x${candidate.height}, ${candidate.displayedSizeText})")
                    lastActionTime = now
                }

                // 다음 사진으로 넘기기 (Swipe Left)
                if (now - lastSwipedTime > 1200) {
                    if (alreadyObserved) {
                        consecutiveSamePhotoSwipes++
                        if (consecutiveSamePhotoSwipes >= 3) {
                            postStatus("마지막 사진 도달: 탐색 완료 (총 ${observedCandidates.size}개 수집)")
                            stopCollecting()
                            return
                        }
                    }

                    lastSwipedTime = now
                    lastActionTime = now
                    postStatus("다음 사진으로 스와이프...")
                    swipeNextPhoto(isDetailsOpen = hasDetails)
                }
            } else if (!hasDetails) {
                // 세부정보 패널이 닫혀 있어 파일명이 안 보임 -> 위로 스와이프하여 상세정보 열기
                val now = System.currentTimeMillis()
                if (now - lastSwipedTime > 900 && swipeUpAttempts < 3) {
                    swipeUpAttempts++
                    lastSwipedTime = now
                    lastActionTime = now
                    postStatus("세부정보 노출 시도 (위로 스와이프 $swipeUpAttempts/3)")
                    swipeUpForDetails()
                }
            }
        }
    }

    private fun swipeUpForDetails() {
        val dm = resources.displayMetrics
        val centerX = dm.widthPixels / 2f
        val startY = dm.heightPixels * 0.75f
        val endY = dm.heightPixels * 0.25f

        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 400))
            .build()

        dispatchGesture(gesture, null, null)
    }

    private fun swipeNextPhoto(isDetailsOpen: Boolean = false) {
        val dm = resources.displayMetrics
        val startX = dm.widthPixels * 0.85f
        val endX = dm.widthPixels * 0.15f
        // 세부정보 패널이 열려있으면 상단 사진 영역(25% 지점)을 스와이프
        val centerY = if (isDetailsOpen) dm.heightPixels * 0.25f else dm.heightPixels * 0.5f

        val path = Path().apply {
            moveTo(startX, centerY)
            lineTo(endX, centerY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()

        dispatchGesture(gesture, null, null)
    }

    private fun postStatus(msg: String) {
        Log.i(TAG, msg)
        mainHandler.post {
            onStatusChanged?.invoke(msg)
        }
    }

    private fun postCandidateObserved(candidate: CloudCandidate, count: Int) {
        mainHandler.post {
            onCandidateObserved?.invoke(candidate, count)
        }
    }

    private fun collectTextsAndDescs(
        node: AccessibilityNodeInfo,
        texts: MutableList<String>,
        descs: MutableList<String>
    ) {
        val text = node.text?.toString()
        if (!text.isNullOrBlank()) texts.add(text)

        val desc = node.contentDescription?.toString()
        if (!desc.isNullOrBlank()) descs.add(desc)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTextsAndDescs(child, texts, descs)
        }
    }
}
