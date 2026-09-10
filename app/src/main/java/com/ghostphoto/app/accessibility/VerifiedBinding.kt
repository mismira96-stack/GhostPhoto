package com.ghostphoto.app.accessibility

import android.graphics.Rect

/**
 * Pass 1에서 Details 패널을 통해 파일명 및 해상도가 완벽히 검증된 타겟의 그리드 바인딩 정보.
 *
 * 절대 좌표($cx, $cy)에 identity를 의존하지 않고,
 * (dateStr, minuteKey, siblingIndex)의 자연스러운 읽기 순서(Reading Order)를 바인딩 힌트로 사용합니다.
 * Pass 2 실행 시 grid ordering/state 일치 여부를 검증하며, drift 감지 시 오선택 방지를 위해 SKIP 처리합니다.
 */
data class VerifiedBinding(
    val targetName: String,
    val dateStr: String,
    val datePrefix: String,
    val minuteRegex: String,
    val minuteKey: String,
    val siblingIndex: Int,
    val totalSiblingsInMinute: Int = 1,
    val width: Int = 0,
    val height: Int = 0,
    val navHintCx: Int = 0,
    val navHintCy: Int = 0,
    var isSelected: Boolean = false
)

/**
 * 그리드 뷰포트에서 추출된 후보 사진/동영상 노드 메타데이터
 */
data class GridCandidate(
    val desc: String,
    val bounds: Rect,
    val cx: Int,
    val cy: Int,
    val minuteKey: String,
    var siblingIndex: Int = 0,
    val coordKey: String = "${bounds.left}_${bounds.top}",
    val node: android.view.accessibility.AccessibilityNodeInfo? = null
)
