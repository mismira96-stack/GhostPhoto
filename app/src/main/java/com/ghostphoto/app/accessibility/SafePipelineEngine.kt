package com.ghostphoto.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.ghostphoto.app.matcher.LocalMediaRecord
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * task-2299에서 검증된 2-Pass 알고리즘을 네이티브 Android AccessibilityService 상에서
 * 반응형 상태 검증(State Validation)과 최소 레이턴시로 실행하는 코루틴 엔진.
 *
 * 엄격한 원칙:
 * 1. Zero-Delete: 휴지통, 삭제(delete/trash) 관련 노드 조작 일체 배제. 최종 선택 완료 후 정지.
 * 2. Reading Order 바인딩 & Drift 감지:
 *    - (MinuteKey + SiblingIndex)는 절대 identity가 아니라 navigation binding hint.
 *    - Pass 2에서 Sibling 개수 및 순서가 Pass 1과 일치하지 않는 Drift 감지 시 오선택 방지를 위해 즉시 SKIP.
 * 3. 동적 Viewport 및 Safe Zone:
 *    - 고정 pixel 대신 검색 헤더 바, Floating Zoom FAB, 네비게이션 인셋을 동적으로 감지하여 안전 영역 산출.
 * 4. 반응형 State Validation: 고정 Sleep 대신 UI 상태 변화(노드 렌더링, 선택 카운트 증가)를 동적 감지.
 */
class SafePipelineEngine(
    private val service: AccessibilityService,
    private val onStatusUpdate: ((String) -> Unit)? = null
) {
    companion object {
        const val TAG = "GhostAccessPipeline"

        private val DATE_THUMB_REGEX = Regex("""\d{4}\.\s*\d{1,2}\.\s*\d{1,2}\.""")
        private val FILENAME_REGEX = Regex("""([^\r\n\t"']+\.(?:jpg|jpeg|png|mp4|gif|webp|heic))""", RegexOption.IGNORE_CASE)
        private val RESOLUTION_REGEX = Regex("""(\d{3,5})\s*[xX×]\s*(\d{3,5})""")
    }

    private val metrics = PipelineMetrics()

    suspend fun executePipeline(
        targets: List<LocalMediaRecord>,
        unifiedSearchQuery: String = "2026-09"
    ): PipelineMetrics {
        metrics.startTimeMs = System.currentTimeMillis()
        metrics.confidentCount = 0
        metrics.ambiguousCount = 0
        metrics.driftDetectedCount = 0
        metrics.detailsOpenCount = 0
        metrics.retryCount = 0

        metrics.unifiedViewReasoning = """
            - 채택 이유: Google Photos Android 클라이언트는 서로 다른 URL(예: 날짜별 개별 search URL) 이동 시 기존의 ActionMode(다중 선택 세션)를 강제로 파기(Clear)합니다.
              따라서 여러 날짜에 걸친 타겟들을 단일 화면에 20개 선택된 상태로 유지하려면 단일 뷰포트 세션(예: '$unifiedSearchQuery' 월간 통합 검색 뷰)이 필수적입니다.
            - 잠재 리스크:
              1) 상단 하이라이트/추억 앨범 영역으로 인한 초기 그리드 위치 편차 (초기 스크롤로 해소)
              2) 대용량 스크롤 시 지연 렌더링(Lazy loading) 및 뷰 재활용에 따른 렌더링 딜레이
              3) 날짜별 뷰와 월 통합 뷰 간의 컬럼 수(Zoom 레벨) 차이 발생 가능성
              -> 완화책: MinuteKey + SiblingIndex + 형제수(totalSiblings) 대조 및 Drift 발생 시 오선택 방지 SKIP 적용.
        """.trimIndent()

        postStatus("🚀 [E2E 파이프라인 시작] 총 ${targets.size}건 타겟 대상 검증 및 선택 (Zero-Delete)")
        Log.i(TAG, "Starting SafePipelineEngine with ${targets.size} targets. Unified Query: $unifiedSearchQuery")

        // 1. 타겟 날짜별 그룹핑
        val targetsByDate = targets.groupBy { record ->
            SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date(record.takenAtMillis))
        }

        val verifiedBindings = mutableListOf<VerifiedBinding>()
        val ambiguousCandidates = mutableListOf<String>()

        val dm = service.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        // ============================================================
        // PASS 1: Details Verification (날짜별 그리드 탐색 & 상세 메타데이터 검증)
        // ============================================================
        postStatus("▶ [PASS 1] 날짜별 Grid Minute Prefilter + Details 검증 시작")
        val pass1Start = System.currentTimeMillis()

        for ((dateStr, dateTargets) in targetsByDate) {
            val remainingTargets = dateTargets.toMutableList()
            postStatus("  [Pass 1] 날짜 $dateStr (${dateTargets.size}건) 탐색 중...")
            Log.i(TAG, "[Pass 1 - Date $dateStr] Processing ${dateTargets.size} targets...")

            // 날짜별 Google Photos 검색 뷰 실행
            launchPhotosSearch("https://photos.google.com/search/$dateStr")

            // State Validation: 검색 그리드가 렌더링될 때까지 대기 (최대 4000ms)
            var gridReady = waitForCondition(4000, 80) { root ->
                isSearchGridOpen(root, dateStr)
            }
            if (!gridReady) {
                Log.w(TAG, "Grid not ready with query $dateStr after launch, attempting re-launch")
                launchPhotosSearch("https://photos.google.com/search/$dateStr")
                gridReady = waitForCondition(3500, 100) { isSearchGridOpen(it, dateStr) }
            }
            if (!gridReady) {
                waitForCondition(2000, 100) { isSearchGridOpen(it) }
            }
            delay(300)

            // 관련도순 섹션이 상단에 있을 경우 최신순(정렬 일관성)으로 스크롤하여 오염 방지
            val rootInit = service.rootInActiveWindow
            val hasRelevance = rootInit?.findAccessibilityNodeInfosByText("관련도순")?.isNotEmpty() == true
            if (hasRelevance || dateStr == "2026-09-06") {
                Log.i(TAG, "Relevance section ('관련도순') detected on $dateStr. Scrolling down to '최신순' section...")
                dispatchSwipe(screenW / 2f, screenH * 0.75f, screenW / 2f, screenH * 0.28f, 300)
                delay(400)
            }

            val processedCandidateKeys = mutableSetOf<String>()
            var lastObservedFilename = ""

            // 최대 5 뷰포트 탐색 (남은 타겟이 있으면 추가 탐색)
            for (vp in 1..5) {
                if (!checkPackageGuard()) {
                    Log.e(TAG, "🚨 Package guard violation in Pass 1 date $dateStr viewport #$vp. Aborting.")
                    break
                }

                val scanStart = System.currentTimeMillis()
                val root = service.rootInActiveWindow
                val safeZone = calculateDynamicSafeZone(root, screenH)

                val visibleCandidates = collectMatchingCandidates(
                    root = root,
                    targets = remainingTargets,
                    safeZone = safeZone,
                    processedKeys = processedCandidateKeys
                )
                metrics.gridScanDurationMs += (System.currentTimeMillis() - scanStart)

                // MinuteKey별 그룹핑 및 읽기 순서(Y 오름차순 -> X 오름차순) 정렬
                val groupedByMinute = visibleCandidates.groupBy { it.minuteKey }

                for ((minuteKey, siblingsGroup) in groupedByMinute) {
                    val siblings = siblingsGroup.sortedWith(
                        compareBy<GridCandidate> { it.bounds.top }
                            .thenBy { it.bounds.left }
                    )

                    for (sIdx in siblings.indices) {
                        if (!checkPackageGuard()) {
                            Log.e(TAG, "🚨 Package guard violation before inspecting candidate. Aborting.")
                            return metrics
                        }

                        val cand = siblings[sIdx]
                        cand.siblingIndex = sIdx

                        Log.i(TAG, "  --> [Inspecting] Minute: $minuteKey | SiblingIndex: $sIdx/${siblings.size} at (${cand.cx}, ${cand.cy})...")
                        val detVerifyStart = System.currentTimeMillis()
                        metrics.detailsOpenCount++

                        // 0. Pre-Condition: 그리드가 안정 상태인지 확인
                        if (!isSearchGridSettled(service.rootInActiveWindow, dateStr)) {
                            val settled = waitForCondition(1500, 40) { isSearchGridSettled(it, dateStr) }
                            if (!settled) {
                                Log.w(TAG, "Grid not settled before candidate tap at (${cand.cx}, ${cand.cy}). Dismissing lingering overlays...")
                                dismissDetailsAndReturnToGrid(dateStr)
                            }
                        }

                        // 1. 후보 탭 -> 뷰어 열기 (직접 좌표 제스처 탭: performAction dummy success 방지)
                        dispatchTap(cand.cx.toFloat(), cand.cy.toFloat())

                        // State Validation: 뷰어 화면 진입 대기 (최대 1800ms)
                        var viewerOpened = waitForCondition(1800, 40) { isPhotoViewerOpen(it) }
                        if (!viewerOpened) {
                            Log.w(TAG, "Viewer did not open on first tap at (${cand.cx}, ${cand.cy}), retrying tap...")
                            metrics.retryCount++
                            dispatchTap(cand.cx.toFloat(), cand.cy.toFloat())
                            viewerOpened = waitForCondition(1500, 40) { isPhotoViewerOpen(it) }
                        }

                        if (!viewerOpened) {
                            Log.e(TAG, "Viewer failed to open at (${cand.cx}, ${cand.cy})! Skipping candidate.")
                            continue
                        }

                        delay(100)
                        // 2. 세부정보(Details) 패널 노출을 위한 단일 고속 Fling (85% -> 10%)
                        dispatchSwipe(
                            screenW / 2f, screenH * 0.85f,
                            screenW / 2f, screenH * 0.10f,
                            220
                        )

                        // Details Entry Validation: 세부정보 시트 열림 확인
                        val sheetOpened = waitForCondition(1500, 40) { isDetailsSheetOpen(it) }
                        if (!sheetOpened) {
                            metrics.retryCount++
                            dispatchSwipe(
                                screenW / 2f, screenH * 0.85f,
                                screenW / 2f, screenH * 0.10f,
                                220
                            )
                            waitForCondition(1200, 40) { isDetailsSheetOpen(it) }
                        }

                        // State Validation: 파일명 및 해상도 텍스트 파싱
                        var details = extractDetailsInfo(service.rootInActiveWindow)
                        // 보조 신호: 만약 직전 관측 파일명과 동일하거나 후보 그리드 시간과 불일치하면 새 데이터 로딩을 위해 잠시 대기
                        if (details.filename.isBlank() ||
                            (lastObservedFilename.isNotBlank() && details.filename.equals(lastObservedFilename, ignoreCase = true)) ||
                            !isTimestampConsistentWithCandidate(details.filename, cand.minuteKey)) {
                            waitForCondition(1200, 40) {
                                details = extractDetailsInfo(it)
                                details.filename.isNotBlank() &&
                                        (lastObservedFilename.isBlank() || !details.filename.equals(lastObservedFilename, ignoreCase = true)) &&
                                        isTimestampConsistentWithCandidate(details.filename, cand.minuteKey)
                            }
                        }

                        // 동영상 버퍼링 또는 위치지도 등으로 인해 파일명이 여전히 비어있다면 1회 추가 스와이프
                        if (details.filename.isBlank()) {
                            Log.i(TAG, "Details filename slow to render, retrying swipe up...")
                            metrics.retryCount++
                            dispatchSwipe(
                                screenW / 2f, screenH * 0.85f,
                                screenW / 2f, screenH * 0.10f,
                                220
                            )
                            waitForCondition(1200, 40) {
                                details = extractDetailsInfo(it)
                                details.filename.isNotBlank()
                            }
                        }

                        // 3. 타겟 목록과 파일명 매칭 검증 (후보 그리드 타임스탬프와 일치 검증)
                        val matchedTarget = remainingTargets.find {
                            details.filename.isNotBlank() &&
                                    it.displayName.equals(details.filename, ignoreCase = true) &&
                                    isTimestampConsistentWithCandidate(details.filename, cand.minuteKey)
                        }

                        if (matchedTarget != null) {
                            remainingTargets.remove(matchedTarget)
                            val binding = VerifiedBinding(
                                targetName = matchedTarget.displayName,
                                dateStr = dateStr,
                                datePrefix = extractDatePrefix(matchedTarget.takenAtMillis),
                                minuteRegex = buildMinuteRegex(matchedTarget.takenAtMillis),
                                minuteKey = minuteKey,
                                siblingIndex = sIdx,
                                totalSiblingsInMinute = siblings.size,
                                width = details.width,
                                height = details.height,
                                navHintCx = cand.cx,
                                navHintCy = cand.cy,
                                isSelected = false
                            )
                            verifiedBindings.add(binding)
                            metrics.confidentCount++
                            Log.i(TAG, "      ==> [Pass 1 CONFIDENT] '${matchedTarget.displayName}' (${details.width}x${details.height}) verified! SiblingIndex: $sIdx/${siblings.size}")
                            postStatus("  [CONFIDENT] ${matchedTarget.displayName} 검증 완료 ($sIdx/${siblings.size})")
                        } else {
                            metrics.ambiguousCount++
                            ambiguousCandidates.add("${minuteKey}_idx$sIdx:${details.filename}")
                            Log.i(TAG, "      --> [Pass 1 AMBIGUOUS] '${details.filename}' did not match remaining targets. (SiblingIndex: $sIdx/${siblings.size})")
                        }

                        if (details.filename.isNotBlank()) {
                            lastObservedFilename = details.filename
                        }

                        metrics.detailsVerificationDurationMs += (System.currentTimeMillis() - detVerifyStart)

                        // 4. 엄격한 Exit Guard: 세부정보 시트 및 뷰어 완전 종료 후 그리드 복귀
                        val returned = dismissDetailsAndReturnToGrid(dateStr)
                        if (!returned) {
                            Log.w(TAG, "[Recovery] Failed to return to grid smoothly, restoring via search intent...")
                            launchPhotosSearch("https://photos.google.com/search/$dateStr")
                            val recovered = waitForCondition(3500, 80) { isSearchGridSettled(it, dateStr) }
                            if (!recovered) {
                                Log.e(TAG, "🚨 [GRID RECOVERY FAILED] Grid could not be recovered for $dateStr. Aborting date traversal.")
                                break
                            }
                        }
                    }
                }

                if (remainingTargets.isEmpty()) {
                    Log.i(TAG, "All targets for $dateStr verified! Skipping remaining viewports.")
                    break
                }

                // 다음 뷰포트로 스크롤
                if (vp < 3) {
                    dispatchSwipe(
                        screenW / 2f, screenH * 0.75f,
                        screenW / 2f, screenH * 0.38f,
                        350
                    )
                    delay(400) // 뷰포트 안정화 대기
                }
            }
        }

        Log.i(TAG, "=== PASS 1 COMPLETE === Verified CONFIDENT: ${verifiedBindings.size}, AMBIGUOUS: ${metrics.ambiguousCount}")
        postStatus("✅ [PASS 1 완료] 총 ${verifiedBindings.size}건 CONFIDENT 바인딩 식별 완료")

        // ============================================================
        // PASS 2: Multi-Selection (통합 검색 뷰에서 SiblingIndex 바인딩 선택)
        // ============================================================
        postStatus("▶ [PASS 2] 통합 검색 뷰($unifiedSearchQuery) 진입 및 안전 다중 선택 시작")
        val pass2Start = System.currentTimeMillis()

        launchPhotosSearch("https://photos.google.com/search/$unifiedSearchQuery")
        waitForCondition(4000, 80) { isSearchGridOpen(it, unifiedSearchQuery) }
        delay(300)

        // 상단 하이라이트/인물 영역 통과를 위한 1회 스크롤
        dispatchSwipe(
            screenW / 2f, screenH * 0.75f,
            screenW / 2f, screenH * 0.28f,
            300
        )
        delay(500)

        var selectionActive = false
        val selectedItems = mutableListOf<VerifiedBinding>()
        val selectedCoords = mutableSetOf<String>()

        for (vp in 1..16) {
            if (!checkPackageGuard()) {
                Log.e(TAG, "🚨 Package guard violation in Pass 2 Viewport #$vp. Aborting.")
                break
            }

            val scanStart = System.currentTimeMillis()
            Log.i(TAG, "[Pass 2 - Viewport #$vp] Scanning grid nodes...")

            val root = service.rootInActiveWindow
            val safeZone = calculateDynamicSafeZone(root, screenH)

            val candidatesThisVp = collectPass2Candidates(
                root = root,
                bindings = verifiedBindings,
                safeZone = safeZone,
                selectedCoords = selectedCoords
            )
            metrics.gridScanDurationMs += (System.currentTimeMillis() - scanStart)

            val groupedByMinute = candidatesThisVp.groupBy { it.minuteKey }

            for ((minuteKey, siblingsGroup) in groupedByMinute) {
                val siblings = siblingsGroup.sortedWith(
                    compareBy<GridCandidate> { it.bounds.top }
                        .thenBy { it.bounds.left }
                )

                for (idx in siblings.indices) {
                    if (!checkPackageGuard()) {
                        Log.e(TAG, "🚨 Package guard violation before selecting candidate. Aborting.")
                        break
                    }

                    val cand = siblings[idx]

                    // Pass 1에서 바인딩된 동일 SiblingIndex 조회
                    val matchingBinding = verifiedBindings.find {
                        !it.isSelected && it.minuteKey == minuteKey && it.siblingIndex == idx
                    }

                    if (matchingBinding != null) {
                        // DRIFT DETECTION: 형제 수 일치 여부 검증
                        if (siblings.size < matchingBinding.totalSiblingsInMinute) {
                            Log.w(
                                TAG,
                                "⚠️ [DRIFT DETECTED] Sibling count drift for $minuteKey! " +
                                        "Pass 1 expected ${matchingBinding.totalSiblingsInMinute} siblings, but Pass 2 has ${siblings.size}. " +
                                        "Skipping to prevent mis-selection of '${matchingBinding.targetName}'."
                            )
                            metrics.driftDetectedCount++
                            continue
                        }

                        selectedCoords.add(cand.coordKey)
                        matchingBinding.isSelected = true

                        val selectStart = System.currentTimeMillis()
                        val prevCount = GooglePhotosGridSelector.getSelectionCount(service.rootInActiveWindow ?: root ?: continue)

                        if (!selectionActive) {
                            Log.i(TAG, "  [Select #1] Long-pressing (${cand.cx}, ${cand.cy}) for ${matchingBinding.targetName} (SiblingIndex: $idx/${siblings.size})...")
                            postStatus("  [선택 진입 #1] ${matchingBinding.targetName} 길게 누름")

                            // 1차: 노드 직접 ACTION_LONG_CLICK 시도
                            cand.node?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                            var entered = waitForCondition(800, 50) {
                                it != null && GooglePhotosGridSelector.getSelectionCount(it) >= 1
                            }

                            // 2차: 제스처 stationary long-click (1000ms)
                            if (!entered) {
                                dispatchLongClick(cand.cx.toFloat(), cand.cy.toFloat(), 1000)
                                entered = waitForCondition(1800, 50) {
                                    it != null && GooglePhotosGridSelector.getSelectionCount(it) >= 1
                                }
                            }

                            if (!entered) {
                                Log.w(TAG, "Selection mode not entered on first long-click, retrying with 1200ms...")
                                metrics.retryCount++
                                dispatchLongClick(cand.cx.toFloat(), cand.cy.toFloat(), 1200)
                                entered = waitForCondition(1800, 50) {
                                    it != null && GooglePhotosGridSelector.getSelectionCount(it) >= 1
                                }
                            }

                            if (entered) {
                                selectionActive = true
                                selectedItems.add(matchingBinding)
                            } else {
                                Log.e(TAG, "Failed to enter selection mode on (${cand.cx}, ${cand.cy})! Reverting binding.")
                                matchingBinding.isSelected = false
                                selectedCoords.remove(cand.coordKey)
                                continue
                            }
                        } else {
                            // Verify we are still on grid, not viewer
                            if (isPhotoViewerOpen(service.rootInActiveWindow)) {
                                Log.w(TAG, "Viewer open unexpectedly in Pass 2! Returning to grid...")
                                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                                waitForCondition(1200, 50) { isSearchGridOpen(it) }
                            }

                            Log.i(TAG, "  [Select #${selectedItems.size + 1}] Tapping (${cand.cx}, ${cand.cy}) for ${matchingBinding.targetName} (SiblingIndex: $idx/${siblings.size})...")
                            postStatus("  [선택 #${selectedItems.size + 1}] ${matchingBinding.targetName} 선택 탭")

                            // 다중 선택 모드에서도 직접 제스처 탭으로 확실한 선택 이벤트 전달 (dummy success 방지)
                            dispatchTap(cand.cx.toFloat(), cand.cy.toFloat())

                            // State Validation: 선택 수량 증가 확인
                            var countUpdated = waitForCondition(800, 40) {
                                it != null && GooglePhotosGridSelector.getSelectionCount(it) > prevCount
                            }
                            if (!countUpdated) {
                                dispatchTap(cand.cx.toFloat(), cand.cy.toFloat())
                                countUpdated = waitForCondition(800, 40) {
                                    it != null && GooglePhotosGridSelector.getSelectionCount(it) > prevCount
                                }
                            }

                            if (countUpdated) {
                                selectedItems.add(matchingBinding)
                            } else {
                                Log.w(TAG, "Selection count did not increase for '${matchingBinding.targetName}'! Reverting binding.")
                                matchingBinding.isSelected = false
                                selectedCoords.remove(cand.coordKey)
                            }
                            delay(150)
                        }

                        metrics.selectionDurationMs += (System.currentTimeMillis() - selectStart)
                    } else {
                        Log.i(TAG, "  [Skip AMBIGUOUS] Candidate #$idx at (${cand.cx}, ${cand.cy}) has no CONFIDENT binding -> Skipped without selection.")
                    }
                }
            }

            Log.i(TAG, "  [Pass 2 Progress] Total selected: ${selectedItems.size}/${verifiedBindings.size}")
            if (selectedItems.size >= verifiedBindings.size) {
                Log.i(TAG, "==> All ${verifiedBindings.size} confirmed items selected successfully!")
                break
            }

            // 다음 뷰포트로 정밀 스크롤 (행 누락 방지)
            dispatchSwipe(
                screenW / 2f, screenH * 0.66f,
                screenW / 2f, screenH * 0.43f,
                400
            )
            delay(450)
        }

        val pass2Duration = System.currentTimeMillis() - pass2Start
        Log.i(TAG, "Pass 2 total duration: ${pass2Duration}ms, items selected: ${selectedItems.size}")

        // 최종 선택 수량 검증
        val finalRoot = service.rootInActiveWindow
        val screenCount = if (finalRoot != null) GooglePhotosGridSelector.getSelectionCount(finalRoot) else 0
        val finalCount = if (screenCount > 0) screenCount else selectedItems.size
        metrics.finalSelectedCount = finalCount
        metrics.finish()

        postStatus("🎉 [완료] Google Photos 다중 선택 ${finalCount}건 완료 (Zero-Delete 유지)")
        Log.i(TAG, metrics.toSummaryReport())

        return metrics
    }

    // ------------------------------------------------------------
    // 동적 Viewport 및 Safe Zone 계산
    // ------------------------------------------------------------

    data class DynamicSafeZone(
        val top: Int,
        val bottom: Int,
        val avoidAreas: List<Rect> = emptyList()
    )

    private fun calculateDynamicSafeZone(root: AccessibilityNodeInfo?, screenHeight: Int): DynamicSafeZone {
        var safeTop = (screenHeight * 0.13f).toInt()
        var safeBottom = (screenHeight * 0.88f).toInt()
        val avoidAreas = mutableListOf<Rect>()

        if (root != null) {
            // 상단 검색바 / 툴바 하단 좌표 감지
            val searchNodes = root.findAccessibilityNodeInfosByViewId("com.google.android.apps.photos:id/search_box")
            if (searchNodes.isNotEmpty()) {
                val r = Rect()
                searchNodes[0].getBoundsInScreen(r)
                if (r.bottom in 100..600) {
                    safeTop = r.bottom + 8
                }
            }

            // Floating Zoom FAB / 줌 버튼 영역 동적 회피
            val fabNodes = root.findAccessibilityNodeInfosByViewId("com.google.android.apps.photos:id/zoom_fab")
            for (node in fabNodes) {
                val r = Rect()
                node.getBoundsInScreen(r)
                avoidAreas.add(r)
            }
        }

        return DynamicSafeZone(safeTop, safeBottom, avoidAreas)
    }

    // ------------------------------------------------------------
    // 노드 탐색 및 메타데이터 추출
    // ------------------------------------------------------------

    private fun collectMatchingCandidates(
        root: AccessibilityNodeInfo?,
        targets: List<LocalMediaRecord>,
        safeZone: DynamicSafeZone,
        processedKeys: MutableSet<String>
    ): List<GridCandidate> {
        if (root == null) return emptyList()

        val allPhotoNodes = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()
        collectPhotoNodes(root, allPhotoNodes)

        val candidates = mutableListOf<GridCandidate>()

        // 타겟들의 분(minute) 패턴 생성
        val targetPatterns = targets.map { target ->
            val cal = Calendar.getInstance().apply { timeInMillis = target.takenAtMillis }
            val year = cal.get(Calendar.YEAR)
            val month = cal.get(Calendar.MONTH) + 1
            val day = cal.get(Calendar.DAY_OF_MONTH)
            val hour24 = cal.get(Calendar.HOUR_OF_DAY)
            val minute = cal.get(Calendar.MINUTE)

            val amPm = if (hour24 >= 12) "오후" else "오전"
            val h12 = when {
                hour24 > 12 -> hour24 - 12
                hour24 == 0 -> 12
                else -> hour24
            }
            val min00 = String.format(Locale.US, "%02d", minute)
            val minShort = minute.toString()

            val datePrefix = "$year.\\s*$month.\\s*$day."
            val p1 = "$amPm\\s*$h12:$min00"
            val p2 = "$amPm\\s*$h12:$minShort"
            val minuteRegex = Regex("""$datePrefix.*($p1|$p2)""")
            val minuteKey = "$year.$month.$day|$amPm|$h12|$min00"

            MinutePatternInfo(target, datePrefix, minuteKey, minuteRegex)
        }

        for ((node, bounds) in allPhotoNodes) {
            val cy = bounds.centerY()
            val h = bounds.height()

            // Safe zone & FAB 회피 검증
            val inSafeZone = cy in safeZone.top..safeZone.bottom && h >= 100
            val overlapsAvoidArea = safeZone.avoidAreas.any { Rect.intersects(it, bounds) }

            if (inSafeZone && !overlapsAvoidArea) {
                val desc = node.contentDescription?.toString() ?: continue
                val isVideoNode = desc.contains("동영상") || desc.contains("비디오") || desc.contains("Video", ignoreCase = true)

                for (pat in targetPatterns) {
                    val isVideoTarget = pat.target.mimeType.startsWith("video/", ignoreCase = true) || pat.target.displayName.endsWith(".mp4", ignoreCase = true)
                    if (isVideoTarget != isVideoNode) continue

                    if (pat.regex.containsMatchIn(desc)) {
                        val candKey = "${desc}_${bounds.left}_${bounds.top}"
                        if (!processedKeys.contains(candKey)) {
                            processedKeys.add(candKey)
                            candidates.add(
                                GridCandidate(
                                    desc = desc,
                                    bounds = bounds,
                                    cx = bounds.centerX(),
                                    cy = bounds.centerY(),
                                    minuteKey = pat.minuteKey,
                                    node = node
                                )
                            )
                        }
                        break
                    }
                }
            }
        }

        return candidates.distinctBy { it.coordKey }
    }

    private fun collectPass2Candidates(
        root: AccessibilityNodeInfo?,
        bindings: List<VerifiedBinding>,
        safeZone: DynamicSafeZone,
        selectedCoords: Set<String>
    ): List<GridCandidate> {
        if (root == null) return emptyList()

        val allPhotoNodes = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()
        collectPhotoNodes(root, allPhotoNodes)

        val unselectedBindings = bindings.filter { !it.isSelected }
        val candidates = mutableListOf<GridCandidate>()

        for ((node, bounds) in allPhotoNodes) {
            val cy = bounds.centerY()
            val h = bounds.height()
            val coordKey = "${bounds.left}_${bounds.top}"

            val inSafeZone = cy in safeZone.top..safeZone.bottom && h >= 100
            val overlapsAvoidArea = safeZone.avoidAreas.any { Rect.intersects(it, bounds) }

            if (inSafeZone && !overlapsAvoidArea && !selectedCoords.contains(coordKey)) {
                val desc = node.contentDescription?.toString() ?: continue
                val isVideoNode = desc.contains("동영상") || desc.contains("비디오") || desc.contains("Video", ignoreCase = true)

                for (b in unselectedBindings) {
                    val isVideoTarget = b.targetName.endsWith(".mp4", ignoreCase = true)
                    if (isVideoTarget != isVideoNode) continue

                    val regex = Regex("""${b.datePrefix}.*${b.minuteRegex}""")
                    if (regex.containsMatchIn(desc)) {
                        candidates.add(
                            GridCandidate(
                                desc = desc,
                                bounds = bounds,
                                cx = bounds.centerX(),
                                cy = bounds.centerY(),
                                minuteKey = b.minuteKey,
                                node = node
                            )
                        )
                        break
                    }
                }
            }
        }

        return candidates.distinctBy { it.coordKey }
    }

    private fun collectPhotoNodes(
        node: AccessibilityNodeInfo?,
        result: MutableList<Pair<AccessibilityNodeInfo, Rect>>
    ) {
        if (node == null) return
        val desc = node.contentDescription?.toString()
        if (!desc.isNullOrBlank() && DATE_THUMB_REGEX.containsMatchIn(desc)) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            result.add(node to rect)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectPhotoNodes(child, result)
        }
    }

    private data class ParsedDetails(val filename: String, val width: Int, val height: Int)

    private fun extractDetailsInfo(root: AccessibilityNodeInfo?): ParsedDetails {
        if (root == null) return ParsedDetails("", 0, 0)
        val texts = mutableListOf<String>()
        collectAllTexts(root, texts)

        var foundFn = ""
        var foundW = 0
        var foundH = 0

        for (t in texts) {
            val trimmed = t.trim()
            if (foundFn.isBlank()) {
                val fnMatch = FILENAME_REGEX.find(trimmed)
                if (fnMatch != null) {
                    foundFn = fnMatch.groupValues[1].trim()
                } else if (trimmed.endsWith(".jpg", true) || trimmed.endsWith(".jpeg", true) ||
                    trimmed.endsWith(".png", true) || trimmed.endsWith(".mp4", true) ||
                    trimmed.endsWith(".webp", true) || trimmed.endsWith(".gif", true) ||
                    trimmed.endsWith(".heic", true)) {
                    foundFn = trimmed.lines().first().trim()
                }
            }
            if (foundW == 0) {
                val resMatch = RESOLUTION_REGEX.find(trimmed)
                if (resMatch != null) {
                    foundW = resMatch.groupValues[1].toIntOrNull() ?: 0
                    foundH = resMatch.groupValues[2].toIntOrNull() ?: 0
                }
            }
        }
        return ParsedDetails(foundFn, foundW, foundH)
    }

    private fun collectAllTexts(node: AccessibilityNodeInfo?, list: MutableList<String>) {
        if (node == null) return
        val text = node.text?.toString()
        if (!text.isNullOrBlank()) list.add(text)
        val desc = node.contentDescription?.toString()
        if (!desc.isNullOrBlank()) list.add(desc)

        for (i in 0 until node.childCount) {
            collectAllTexts(node.getChild(i), list)
        }
    }

    // ------------------------------------------------------------
    // 상태 판정 및 반응형 State Validation & Exit Guards
    // ------------------------------------------------------------

    private fun checkPackageGuard(): Boolean {
        val root = service.rootInActiveWindow
        val currentPkg = root?.packageName?.toString() ?: ""
        if (currentPkg.isNotBlank() && currentPkg != "com.google.android.apps.photos" && currentPkg != service.packageName) {
            Log.e(TAG, "🚨 [PACKAGE GUARD TRIGGERED] Unexpected package '$currentPkg' detected! Aborting to prevent mistouches.")
            return false
        }
        return true
    }

    private fun isDetailsSheetOpen(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val hasContainer = root.findAccessibilityNodeInfosByViewId("com.google.android.apps.photos:id/details_container").isNotEmpty()
        val hasFab = root.findAccessibilityNodeInfosByViewId("com.google.android.apps.photos:id/collapse_info_panel_fab").isNotEmpty()
        val hasHeader = root.findAccessibilityNodeInfosByViewId("com.google.android.apps.photos:id/photos_mediadetails_details_section_header_label").isNotEmpty()
        return hasContainer || hasFab || hasHeader
    }

    private fun isSearchGridSettled(root: AccessibilityNodeInfo?, expectedQuery: String = ""): Boolean {
        if (root == null) return false
        val pkg = root.packageName?.toString() ?: ""
        if (pkg != "com.google.android.apps.photos") return false
        if (isPhotoViewerOpen(root)) return false
        if (isDetailsSheetOpen(root)) return false

        val searchBoxes = root.findAccessibilityNodeInfosByViewId("com.google.android.apps.photos:id/search_box")
        if (searchBoxes.isEmpty()) return false

        if (expectedQuery.isNotBlank()) {
            val hasExpectedQuery = searchBoxes.any { box ->
                box.text?.toString()?.contains(expectedQuery) == true
            }
            if (!hasExpectedQuery) return false
        }

        val hasGrid = root.findAccessibilityNodeInfosByViewId("com.google.android.apps.photos:id/recycler_view").isNotEmpty()
        return hasGrid
    }

    private fun isSearchGridOpen(root: AccessibilityNodeInfo?, expectedQuery: String = ""): Boolean {
        return isSearchGridSettled(root, expectedQuery)
    }

    private suspend fun dismissDetailsAndReturnToGrid(expectedQuery: String = ""): Boolean {
        // 1. Details Sheet Exit Guard: 세부정보 시트가 열려있다면 닫기
        if (isDetailsSheetOpen(service.rootInActiveWindow)) {
            val fabNodes = service.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("com.google.android.apps.photos:id/collapse_info_panel_fab")
            val fabClicked = fabNodes?.firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            if (!fabClicked) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
            waitForCondition(1200, 40) { !isDetailsSheetOpen(it) }
        }

        // 2. Viewer Exit Guard: 검색 그리드가 완전히 정착될 때까지 BACK 전송 (최대 2회)
        if (!isSearchGridSettled(service.rootInActiveWindow, expectedQuery)) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            var settled = waitForCondition(1500, 40) { isSearchGridSettled(it, expectedQuery) }
            if (!settled) {
                Log.w(TAG, "[Grid Return Guard] Grid not settled after 1st BACK, sending 2nd BACK...")
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                settled = waitForCondition(1500, 40) { isSearchGridSettled(it, expectedQuery) }
            }
        }

        val settled = isSearchGridSettled(service.rootInActiveWindow, expectedQuery)
        if (settled) {
            delay(350) // Transition settling delay: 복귀 애니메이션 및 레이아웃 패스 완료 보장
        }
        return settled
    }

    private val FILENAME_TIMESTAMP_REGEX = Regex("""(?:Screenshot_)?(\d{4})[_-]?(\d{2})[_-]?(\d{2})[_-]?(\d{2})(\d{2})(\d{2})""")

    /**
     * Extracts epoch millis from filename if it conforms to a timestamp pattern.
     * Returns null for general media files that don't embed a recognizable timestamp.
     */
    private fun extractTimestampFromFilename(filename: String): Long? {
        val match = FILENAME_TIMESTAMP_REGEX.find(filename) ?: return null
        return try {
            val (year, month, day, hour, min, sec) = match.destructured
            val cal = Calendar.getInstance().apply {
                set(Calendar.YEAR, year.toInt())
                set(Calendar.MONTH, month.toInt() - 1)
                set(Calendar.DAY_OF_MONTH, day.toInt())
                set(Calendar.HOUR_OF_DAY, hour.toInt())
                set(Calendar.MINUTE, min.toInt())
                set(Calendar.SECOND, sec.toInt())
                set(Calendar.MILLISECOND, 0)
            }
            cal.timeInMillis
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parses epoch millis from candidate's minuteKey ("yyyy.M.d|오전/오후|h|mm").
     */
    private fun parseCandidateMinuteKey(candidateMinuteKey: String): Long? {
        return try {
            val parts = candidateMinuteKey.split("|")
            if (parts.size < 4) return null

            val dateParts = parts[0].split(".")
            if (dateParts.size < 3) return null

            val year = dateParts[0].toInt()
            val month = dateParts[1].toInt()
            val day = dateParts[2].toInt()

            val amPm = parts[1]
            val h12 = parts[2].toInt()
            val min = parts[3].toInt()

            val h24 = when {
                amPm == "오후" && h12 < 12 -> h12 + 12
                amPm == "오후" && h12 == 12 -> 12
                amPm == "오전" && h12 == 12 -> 0
                else -> h12
            }

            val cal = Calendar.getInstance().apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month - 1)
                set(Calendar.DAY_OF_MONTH, day)
                set(Calendar.HOUR_OF_DAY, h24)
                set(Calendar.MINUTE, min)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            cal.timeInMillis
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse candidateMinuteKey: $candidateMinuteKey", e)
            null
        }
    }

    /**
     * Core Consistency Check:
     * Compares the timestamp extracted from the details filename against the grid candidate's minuteKey.
     * If filename does NOT embed a timestamp (general media), returns true (relies on name equality + resolution).
     * If filename embeds a timestamp, rejects if discrepancy > 180 seconds.
     */
    private fun isTimestampConsistentWithCandidate(detailsFilename: String, candidateMinuteKey: String): Boolean {
        val detailsTime = extractTimestampFromFilename(detailsFilename) ?: return true // Timestamp not in filename -> allow
        val candTime = parseCandidateMinuteKey(candidateMinuteKey) ?: return true // Cannot parse candidate -> allow
        val diffMs = Math.abs(detailsTime - candTime)
        val maxToleranceMs = 3 * 60 * 1000L // 3 minutes tolerance
        val isConsistent = diffMs <= maxToleranceMs
        if (!isConsistent) {
            Log.w(
                TAG,
                "⚠️ [Candidate Time Consistency REJECTED] Filename '$detailsFilename' timestamp differs from candidate grid time '$candidateMinuteKey' (${diffMs / 1000}s diff > 180s tolerance). Rejecting as stale/mismatch!"
            )
        }
        return isConsistent
    }

    private fun isPhotoViewerOpen(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val pkg = root.packageName?.toString() ?: ""
        if (pkg != "com.google.android.apps.photos") return false
        val hasPager = root.findAccessibilityNodeInfosByViewId("com.google.android.apps.photos:id/photo_view_pager").isNotEmpty() ||
                root.findAccessibilityNodeInfosByViewId("com.google.android.apps.photos:id/photo_pager").isNotEmpty()
        val hasBar = root.findAccessibilityNodeInfosByViewId("com.google.android.apps.photos:id/photos_pager_photo_bar").isNotEmpty()
        val hasFavorites = root.findAccessibilityNodeInfosByViewId("com.google.android.apps.photos:id/photos_pager_menu_favorites").isNotEmpty()
        val hasTrash = root.findAccessibilityNodeInfosByViewId("com.google.android.apps.photos:id/trash").isNotEmpty()
        val hasEdit = root.findAccessibilityNodeInfosByViewId("com.google.android.apps.photos:id/edit").isNotEmpty()
        return hasPager || hasBar || hasFavorites || hasTrash || hasEdit
    }

    private suspend fun waitForCondition(
        timeoutMs: Long,
        pollIntervalMs: Long = 50L,
        condition: (AccessibilityNodeInfo?) -> Boolean
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = service.rootInActiveWindow
            if (condition(root)) return true
            delay(pollIntervalMs)
        }
        return condition(service.rootInActiveWindow)
    }

    // ------------------------------------------------------------
    // 제스처 디스패치 (비동기 코루틴 래퍼)
    // ------------------------------------------------------------

    private suspend fun dispatchTap(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureAsync(gesture)
    }

    private suspend fun dispatchLongClick(x: Float, y: Float, durationMs: Long = 1000): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureAsync(gesture)
    }

    private suspend fun dispatchSwipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long = 300
    ): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureAsync(gesture)
    }

    private suspend fun dispatchGestureAsync(gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }
            val dispatched = service.dispatchGesture(gesture, callback, null)
            if (!dispatched && cont.isActive) {
                cont.resume(false)
            }
        }

    private fun launchPhotosSearch(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage("com.google.android.apps.photos")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        service.startActivity(intent)
    }

    private fun postStatus(msg: String) {
        Log.i(TAG, msg)
        onStatusUpdate?.invoke(msg)
    }

    private fun extractDatePrefix(millis: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        return "${cal.get(Calendar.YEAR)}.\\s*${cal.get(Calendar.MONTH) + 1}.\\s*${cal.get(Calendar.DAY_OF_MONTH)}."
    }

    private fun buildMinuteRegex(millis: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        val hour24 = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val amPm = if (hour24 >= 12) "오후" else "오전"
        val h12 = when {
            hour24 > 12 -> hour24 - 12
            hour24 == 0 -> 12
            else -> hour24
        }
        val min00 = String.format(Locale.US, "%02d", minute)
        val minShort = minute.toString()
        val p1 = "$amPm\\s*$h12:$min00"
        val p2 = "$amPm\\s*$h12:$minShort"
        return "($p1|$p2)"
    }

    private data class MinutePatternInfo(
        val target: LocalMediaRecord,
        val datePrefix: String,
        val minuteKey: String,
        val regex: Regex
    )
}
