package com.ghostphoto.app.matcher

/**
 * Ground Truth 평가 요약 지표
 */
data class EvaluationMetrics(
    val totalPreservedFixtures: Int,
    val evaluableCount: Int,
    val insufficientCount: Int,
    val confidentMatchCount: Int,
    val ambiguousCount: Int,
    val notFoundCount: Int,
    val wrongMatchCount: Int
) {
    val isWrongMatchZero: Boolean get() = wrongMatchCount == 0

    fun toSummaryReport(): String {
        return buildString {
            appendLine("=== GROUND TRUTH EVALUATION REPORT ===")
            appendLine("TOTAL_PRESERVED_FIXTURES   = $totalPreservedFixtures")
            appendLine("EVALUABLE_GROUND_TRUTH     = $evaluableCount")
            appendLine("INSUFFICIENT_GROUND_TRUTH  = $insufficientCount")
            appendLine("---------------------------------------")
            appendLine("CONFIDENT_MATCH            = $confidentMatchCount")
            appendLine("AMBIGUOUS                  = $ambiguousCount")
            appendLine("NOT_FOUND                  = $notFoundCount")
            appendLine("WRONG_MATCH                = $wrongMatchCount (Zero Tolerance Check: ${if (isWrongMatchZero) "PASS ✅" else "FAIL ❌"})")
            appendLine("=======================================")
        }
    }
}

/**
 * 개별 Ground Truth 평가 항목
 */
data class GroundTruthEvaluationItem(
    val expectedRecord: LocalMediaRecord,
    val matchResult: MatchResult,
    val isWrongMatch: Boolean,
    val isConfirmedMatch: Boolean
)

/**
 * GhostMatcher 자체의 판정에 의존하지 않고,
 * Ground Truth의 실제 정답 Identity와 매칭 결과의 MatchedCandidate Identity를
 * 독립적으로 대조하여 진짜 WRONG_MATCH를 산출하는 평가기.
 */
object GroundTruthEvaluator {

    fun evaluate(
        expectedRecords: List<LocalMediaRecord>,
        matchResults: List<MatchResult>,
        totalPreservedFixtures: Int = 28
    ): Pair<EvaluationMetrics, List<GroundTruthEvaluationItem>> {
        val evaluableCount = expectedRecords.size
        val insufficientCount = (totalPreservedFixtures - evaluableCount).coerceAtLeast(0)

        val resultMap = matchResults.associateBy { it.localRecord.displayName }
        var confidentCount = 0
        var ambiguousCount = 0
        var notFoundCount = 0
        var wrongMatchCount = 0

        val items = mutableListOf<GroundTruthEvaluationItem>()

        for (expected in expectedRecords) {
            val res = resultMap[expected.displayName]
            if (res == null) {
                notFoundCount++
                items.add(
                    GroundTruthEvaluationItem(
                        expectedRecord = expected,
                        matchResult = MatchResult(localRecord = expected, verdict = MatchVerdict.NOT_FOUND, matchReasons = listOf("결과 매핑 부재")),
                        isWrongMatch = false,
                        isConfirmedMatch = false
                    )
                )
                continue
            }

            when (res.verdict) {
                MatchVerdict.CONFIDENT_MATCH -> {
                    val matchedName = res.matchedCandidate?.filename
                    // 외부 독립 검증: 매칭된 후보의 파일명이 실제 expected 파일명과 완전히 일치하는가?
                    if (matchedName != null && matchedName == expected.displayName) {
                        confidentCount++
                        items.add(
                            GroundTruthEvaluationItem(
                                expectedRecord = expected,
                                matchResult = res,
                                isWrongMatch = false,
                                isConfirmedMatch = true
                            )
                        )
                    } else {
                        // Matcher는 CONFIDENT라고 판정했으나 실제 정답과 다른 후보를 매칭함 -> 진짜 WRONG_MATCH!
                        wrongMatchCount++
                        items.add(
                            GroundTruthEvaluationItem(
                                expectedRecord = expected,
                                matchResult = res,
                                isWrongMatch = true,
                                isConfirmedMatch = false
                            )
                        )
                    }
                }
                MatchVerdict.AMBIGUOUS -> {
                    ambiguousCount++
                    items.add(
                        GroundTruthEvaluationItem(
                            expectedRecord = expected,
                            matchResult = res,
                            isWrongMatch = false,
                            isConfirmedMatch = false
                        )
                    )
                }
                MatchVerdict.NOT_FOUND -> {
                    notFoundCount++
                    items.add(
                        GroundTruthEvaluationItem(
                            expectedRecord = expected,
                            matchResult = res,
                            isWrongMatch = false,
                            isConfirmedMatch = false
                        )
                    )
                }
                MatchVerdict.WRONG_MATCH -> {
                    wrongMatchCount++
                    items.add(
                        GroundTruthEvaluationItem(
                            expectedRecord = expected,
                            matchResult = res,
                            isWrongMatch = true,
                            isConfirmedMatch = false
                        )
                    )
                }
            }
        }

        val metrics = EvaluationMetrics(
            totalPreservedFixtures = totalPreservedFixtures,
            evaluableCount = evaluableCount,
            insufficientCount = insufficientCount,
            confidentMatchCount = confidentCount,
            ambiguousCount = ambiguousCount,
            notFoundCount = notFoundCount,
            wrongMatchCount = wrongMatchCount
        )

        return metrics to items
    }
}
