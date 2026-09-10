package com.ghostphoto.app.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.ghostphoto.app.matcher.LocalMediaRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Google Photos Search Grid 화면에서 타겟 사진 식별 및 선택 상태 검증 유틸리티.
 *
 * 엄격한 원칙:
 * 1. Zero-Delete: 휴지통, 삭제 관련 노드 조작 일체 배제.
 * 2. Exact Count: 선택된 항목 개수가 기대치와 정확히 일치할 때만 SUCCESS 판정.
 * 3. Re-identification: 타겟의 촬영일시(yyyy. M. d. a h:mm) 패턴을 기반으로 그리드 노드 탐색.
 */
object GooglePhotosGridSelector {

    const val ID_ACTION_BAR_TITLE = "com.google.android.apps.photos:id/action_bar_title"
    const val ID_ACTION_MODE_CLOSE = "com.google.android.apps.photos:id/action_mode_close_button"
    const val ID_SELECTION_COUNT = "com.google.android.apps.photos:id/floating_selection_count"
    const val ID_RECYCLER_VIEW = "com.google.android.apps.photos:id/recycler_view"

    /**
     * LocalMediaRecord의 촬영 시각을 Google Photos content-desc 포맷으로 변환.
     * 예: "2026. 9. 6. 오후 7:29"
     */
    fun getExpectedDescPattern(record: LocalMediaRecord): String {
        val sdf = SimpleDateFormat("yyyy. M. d. a h:mm", Locale.KOREA)
        return sdf.format(Date(record.takenAtMillis))
    }

    /**
     * contentDescription이 타겟 레코드의 날짜/시간과 일치하는지 확인.
     */
    fun matchesTarget(contentDesc: String?, record: LocalMediaRecord): Boolean {
        if (contentDesc.isNullOrBlank()) return false
        val pattern = getExpectedDescPattern(record)
        return contentDesc.contains(pattern)
    }

    /**
     * 그리드 내에서 타겟 레코드 목록과 매칭되는 AccessibilityNodeInfo 노드들을 수집.
     */
    fun findTargetPhotoNodes(
        root: AccessibilityNodeInfo,
        targets: List<LocalMediaRecord>
    ): List<AccessibilityNodeInfo> {
        val allPhotoNodes = mutableListOf<AccessibilityNodeInfo>()
        collectPhotoNodes(root, allPhotoNodes)

        val matchedNodes = mutableListOf<AccessibilityNodeInfo>()
        val remainingTargets = targets.toMutableList()

        for (node in allPhotoNodes) {
            val desc = node.contentDescription?.toString() ?: continue
            val matchedTarget = remainingTargets.find { matchesTarget(desc, it) }
            if (matchedTarget != null) {
                matchedNodes.add(node)
                remainingTargets.remove(matchedTarget)
                if (remainingTargets.isEmpty()) break
            }
        }

        return matchedNodes
    }

    /**
     * 현재 선택된 항목 수 확인.
     * 선택 모드가 아니거나 선택된 항목이 없으면 0 반환.
     */
    fun getSelectionCount(root: AccessibilityNodeInfo?): Int {
        if (root == null) return 0

        // 1. action_bar_title에서 선택 숫자 직접 추출 (예: "1", "2", "3", "18")
        val titleNodes = root.findAccessibilityNodeInfosByViewId(ID_ACTION_BAR_TITLE)
        for (node in titleNodes) {
            val txt = node.text?.toString()?.trim()
            if (!txt.isNullOrEmpty() && txt.all { it.isDigit() }) {
                val num = txt.toIntOrNull()
                if (num != null && num > 0) return num
            }
        }

        // 2. action_mode_close_button 존재 시 상단 텍스트 노드 탐색
        val hasActionMode = root.findAccessibilityNodeInfosByViewId(ID_ACTION_MODE_CLOSE).isNotEmpty()
        if (hasActionMode) {
            val allTexts = mutableListOf<String>()
            collectAllTexts(root, allTexts)
            for (txt in allTexts) {
                val trimmed = txt.trim()
                if (trimmed.isNotEmpty() && trimmed.all { it.isDigit() }) {
                    val num = trimmed.toIntOrNull()
                    if (num != null && num > 0) return num
                }
            }
        }

        // 3. 그리드 내 checkable/checked 노드 중 isChecked=true 인 노드 개수 직접 합산
        val allPhotoNodes = mutableListOf<AccessibilityNodeInfo>()
        collectPhotoNodes(root, allPhotoNodes)
        val checkedCount = allPhotoNodes.count { it.isChecked }
        if (checkedCount > 0) return checkedCount

        // 4. floating_selection_count 확인 (일부 대체 레이아웃 대응)
        val countNodes = root.findAccessibilityNodeInfosByViewId(ID_SELECTION_COUNT)
        if (!countNodes.isNullOrEmpty()) {
            val node = countNodes.first()
            val text = node.text?.toString()?.trim()
            if (text != null && !text.startsWith("+") && text.any { it.isDigit() }) {
                val num = text.filter { it.isDigit() }.toIntOrNull()
                if (num != null) return num
            }
        }

        return 0
    }

    private fun collectAllTexts(node: AccessibilityNodeInfo?, list: MutableList<String>) {
        if (node == null) return
        val text = node.text?.toString()
        if (!text.isNullOrBlank()) list.add(text)
        val desc = node.contentDescription?.toString()
        if (!desc.isNullOrBlank()) list.add(desc)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllTexts(child, list)
        }
    }

    /**
     * 선택 개수가 기대값과 일치하는지 검증.
     */
    fun verifySelectionCount(root: AccessibilityNodeInfo, expectedCount: Int): Boolean {
        val count = getSelectionCount(root)
        return count == expectedCount
    }

    /**
     * 노드의 화면 내 사각형 영역 추출
     */
    fun getNodeBounds(node: AccessibilityNodeInfo): Rect {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return rect
    }

    /**
     * 노드가 현재 화면의 유효 뷰포트 내에 완전히 표시되는지 확인.
     */
    fun isNodeVisibleInViewport(bounds: Rect, screenHeight: Int): Boolean {
        // 상단 툴바(약 280px) 및 하단 네비게이션/바(약 1800px) 사이
        return bounds.top >= 280 && bounds.bottom <= (screenHeight - 150) && bounds.height() > 50
    }

    private fun collectPhotoNodes(node: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
        val desc = node.contentDescription?.toString()
        val className = node.className?.toString()
        if (desc != null && desc.contains("촬영한 사진") && (className == "android.widget.ImageView" || node.isClickable)) {
            list.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectPhotoNodes(child, list)
        }
    }
}
