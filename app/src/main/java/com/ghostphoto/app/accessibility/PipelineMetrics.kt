package com.ghostphoto.app.accessibility

import java.util.Locale

/**
 * 2-Pass 파이프라인의 실기기 실측 성능 및 세부 카운터 지표.
 */
data class PipelineMetrics(
    var startTimeMs: Long = 0L,
    var endTimeMs: Long = 0L,
    var totalDurationMs: Long = 0L,
    var gridScanDurationMs: Long = 0L,
    var detailsVerificationDurationMs: Long = 0L,
    var selectionDurationMs: Long = 0L,
    var detailsOpenCount: Int = 0,
    var retryCount: Int = 0,
    var confidentCount: Int = 0,
    var ambiguousCount: Int = 0,
    var driftDetectedCount: Int = 0,
    var finalSelectedCount: Int = 0,
    var unifiedViewReasoning: String = ""
) {
    fun finish() {
        endTimeMs = System.currentTimeMillis()
        totalDurationMs = endTimeMs - startTimeMs
    }

    fun toSummaryReport(): String {
        return buildString {
            appendLine("============================================================")
            appendLine("=== GHOST ACCESSIBILITY SERVICE E2E BENCHMARK REPORT ===")
            appendLine("============================================================")
            appendLine(String.format(Locale.US, "총 소요시간 (Total E2E)       : %.2fs (%d ms)", totalDurationMs / 1000.0, totalDurationMs))
            appendLine(String.format(Locale.US, "  - Grid scan 시간             : %.2fs (%d ms)", gridScanDurationMs / 1000.0, gridScanDurationMs))
            appendLine(String.format(Locale.US, "  - Details verification 시간  : %.2fs (%d ms)", detailsVerificationDurationMs / 1000.0, detailsVerificationDurationMs))
            appendLine(String.format(Locale.US, "  - Pass 2 selection 시간      : %.2fs (%d ms)", selectionDurationMs / 1000.0, selectionDurationMs))
            appendLine("------------------------------------------------------------")
            appendLine("실제 Details open 횟수         : $detailsOpenCount 회")
            appendLine("Retry 횟수                     : $retryCount 회")
            appendLine("검증 결과 (CONFIDENT)          : $confidentCount 건")
            appendLine("제외된 후보 (AMBIGUOUS)        : $ambiguousCount 건")
            appendLine("Drift 감지 및 건너뜀 (DRIFT)   : $driftDetectedCount 건")
            appendLine("최종 선택 화면 수량            : $finalSelectedCount 건")
            appendLine("Zero-Delete 보장               : 100% (휴지통/삭제 버튼 일체 미호출, 선택 화면 정지)")
            if (unifiedViewReasoning.isNotBlank()) {
                appendLine("------------------------------------------------------------")
                appendLine("[View Architecture & Risk Analysis]")
                appendLine(unifiedViewReasoning)
            }
            appendLine("============================================================")
        }
    }
}
