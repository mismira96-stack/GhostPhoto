package com.ghostphoto.app.matcher

import java.time.ZoneId

/**
 * E2E 매칭 결과 종합 리포트
 */
data class PipelineE2EReport(
    val totalTargets: Int,
    val confidentMatches: List<MatchResult>,
    val ambiguousMatches: List<MatchResult>,
    val notFoundMatches: List<MatchResult>,
    val wrongMatches: List<MatchResult>
) {
    val confidentCount: Int get() = confidentMatches.size
    val ambiguousCount: Int get() = ambiguousMatches.size
    val notFoundCount: Int get() = notFoundMatches.size
    val wrongCount: Int get() = wrongMatches.size

    val allResults: List<MatchResult> get() = confidentMatches + ambiguousMatches + notFoundMatches + wrongMatches

    val isWrongMatchZero: Boolean get() = wrongCount == 0

    fun toSummaryString(): String {
        return buildString {
            append("Ground Truth: $totalTargets\n")
            append("CONFIDENT_MATCH: $confidentCount\n")
            append("AMBIGUOUS: $ambiguousCount\n")
            append("NOT_FOUND: $notFoundCount\n")
            append("WRONG_MATCH: $wrongCount (목표: 0)\n")
            append("결과: ${if (isWrongMatchZero) "PASS (WRONG_MATCH = 0 원칙 준수 ✅)" else "FAIL (오선택 발생 ❌)"}")
        }
    }
}

/**
 * 로컬 소실 레코드와 Google Photos 관측 후보군 간의 매칭을 조율하는 파이프라인 코디네이터.
 */
class GhostPipelineCoordinator(
    private val matcher: GhostMatcher = GhostMatcher()
) {

    /**
     * XML 덤프 파일 목록을 직접 읽어 CloudCandidate로 파싱 후 매칭을 조율합니다.
     */
    fun runMatchingFromXmlFiles(
        missingTargets: List<LocalMediaRecord>,
        xmlFiles: List<java.io.File>,
        isFullTraversalCompleted: Boolean = true
    ): PipelineE2EReport {
        val candidates = xmlFiles.map { CloudCandidateParser.parse(it) }
        return runMatching(missingTargets, candidates, isFullTraversalCompleted)
    }

    /**
     * 로컬 소실 레코드 목록에 대해 관측된 Cloud 후보군들을 대조하여 종합 리포트를 생성합니다.
     */
    fun runMatching(
        missingTargets: List<LocalMediaRecord>,
        observedCandidates: List<CloudCandidate>,
        isFullTraversalCompleted: Boolean = true
    ): PipelineE2EReport {
        val confidentList = mutableListOf<MatchResult>()
        val ambiguousList = mutableListOf<MatchResult>()
        val notFoundList = mutableListOf<MatchResult>()
        val wrongList = mutableListOf<MatchResult>()

        for (target in missingTargets) {
            val result = matcher.match(target, observedCandidates, isFullTraversalCompleted)
            when (result.verdict) {
                MatchVerdict.CONFIDENT_MATCH -> {
                    // Ground Truth 파일명과 실제 매칭된 파일명이 서로 다른지 검증 (오선택 감지)
                    val matchedCloudFilename = result.matchedCandidate?.filename
                    val isExpectedTarget = matchedCloudFilename != null &&
                            target.displayName.equals(matchedCloudFilename, ignoreCase = true)

                    if (isExpectedTarget) {
                        confidentList.add(result)
                    } else {
                        // 다른 후보로 오인 매칭된 치명적 결함!
                        wrongList.add(result.copy(verdict = MatchVerdict.WRONG_MATCH))
                    }
                }
                MatchVerdict.AMBIGUOUS -> ambiguousList.add(result)
                MatchVerdict.NOT_FOUND -> notFoundList.add(result)
                MatchVerdict.WRONG_MATCH -> wrongList.add(result)
            }
        }

        return PipelineE2EReport(
            totalTargets = missingTargets.size,
            confidentMatches = confidentList,
            ambiguousMatches = ambiguousList,
            notFoundMatches = notFoundList,
            wrongMatches = wrongList
        )
    }
}
