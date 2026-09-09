package com.ghostphoto.app.matcher

/**
 * P2-A: Live Ground Truth 매칭 코디네이터.
 *
 * 1. 실기기 Google Photos에서 접근성 수집기(GhostAccessibilityService)가 읽어온
 *    라이브 CloudCandidate 목록을 수신.
 * 2. 엄격하게 메타데이터가 보존된 Evaluable Ground-Truth(N건)와 GhostPipelineCoordinator 대조 실행.
 * 3. 독립 GroundTruthEvaluator를 통해 WRONG_MATCH = 0 여부 및 최종 지표 리포트 생성.
 */
class LiveGroundTruthCoordinator(
    private val pipeline: GhostPipelineCoordinator = GhostPipelineCoordinator()
) {

    fun evaluateLiveCandidates(
        observedCandidates: List<CloudCandidate>,
        isFullTraversalCompleted: Boolean = true
    ): Pair<EvaluationMetrics, List<GroundTruthEvaluationItem>> {
        val evaluableRecords = RealCloudGroundTruthFixtures.getEvaluableGroundTruth()

        // 1. GhostMatcher 파이프라인 대조
        val pipelineReport = pipeline.runMatching(
            missingTargets = evaluableRecords,
            observedCandidates = observedCandidates,
            isFullTraversalCompleted = isFullTraversalCompleted
        )

        // 2. 독립 GroundTruthEvaluator를 통한 WRONG_MATCH 및 최종 지표 산출
        return GroundTruthEvaluator.evaluate(
            expectedRecords = evaluableRecords,
            matchResults = pipelineReport.allResults,
            totalPreservedFixtures = RealCloudGroundTruthFixtures.TOTAL_PRESERVED_FIXTURES
        )
    }
}
