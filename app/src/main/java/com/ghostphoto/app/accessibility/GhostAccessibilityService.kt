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
import androidx.core.content.ContextCompat
import com.ghostphoto.app.matcher.CloudCandidate
import com.ghostphoto.app.matcher.CloudCandidateParser
import com.ghostphoto.app.matcher.LiveGroundTruthCoordinator

/**
 * P2-A: Google Photos 전용 100% Read-Only 접근성 수집기 (Collector).
 *
 * 엄격한 원칙:
 * 1. Zero-Delete / Zero-Mutation: 휴지통, 삭제, delete, trash 관련 노드 조작 일체 금지.
 * 2. 오직 UI 탐색 및 메타데이터 수집(Swipe / Parse)만 수행.
 * 3. 수집된 CloudCandidate 리스트를 누적하여 독립 평가기(GroundTruthEvaluator)에 전달.
 */
class GhostAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "GhostAccessCollector"

        var instance: GhostAccessibilityService? = null
            private set

        var isCollecting: Boolean = false
            private set

        val observedCandidates = mutableListOf<CloudCandidate>()
        private var lastActionTime = 0L
        private var lastSwipedTime = 0L
        private var consecutiveSamePhotoSwipes = 0
        private var swipeUpAttempts = 0

        var onStatusChanged: ((String) -> Unit)? = null
        var onCandidateObserved: ((CloudCandidate, totalObserved: Int) -> Unit)? = null

        fun startCollecting() {
            observedCandidates.clear()
            consecutiveSamePhotoSwipes = 0
            swipeUpAttempts = 0
            isCollecting = true
            instance?.postStatus("수집 모드 활성화 (100% Read-Only 탐색 대기 중)")
        }

        fun stopCollecting() {
            isCollecting = false
            consecutiveSamePhotoSwipes = 0
            swipeUpAttempts = 0
            instance?.postStatus("수집 모드 종료 (총 ${observedCandidates.size}개 후보 수집됨)")
        }

        fun clearObserved() {
            observedCandidates.clear()
            consecutiveSamePhotoSwipes = 0
            swipeUpAttempts = 0
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
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

        val now = System.currentTimeMillis()
        if (now - lastActionTime < 400) return // 스로틀링

        val rootNode = rootInActiveWindow ?: return
        try {
            processPhotosWindow(rootNode)
        } finally {
            // Android O+ 에서 안전하게 관리
        }
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
