package com.ghostphoto.app.matcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LiveGroundTruthCoordinatorIntegrationTest {

    private fun findWorkspaceFile(relativePath: String): File {
        val possiblePaths = listOf(
            File(relativePath),
            File("../$relativePath"),
            File("c:/Users/mismi/Documents/Codex/GhostPhoto/$relativePath")
        )
        return possiblePaths.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Cannot find file: $relativePath in $possiblePaths")
    }

    @Test
    fun evaluateLiveCandidates_withRealPreservedXmlDumps_producesExactMetrics() {
        val xml1 = findWorkspaceFile("experiment/photo_details_dump.xml") // adv_live_timeline.png
        val xml2 = findWorkspaceFile("experiment/test2_detail.xml")       // pass-screen.png

        val cand1 = CloudCandidateParser.parse(xml1, "cloud_cand_1")
        val cand2 = CloudCandidateParser.parse(xml2, "cloud_cand_2")

        val coordinator = LiveGroundTruthCoordinator()
        val (metrics, items) = coordinator.evaluateLiveCandidates(
            observedCandidates = listOf(cand1, cand2),
            isFullTraversalCompleted = true
        )

        println(metrics.toSummaryReport())

        // 1. 전체 보존 fixture 및 completeness inventory 지표 검증
        assertEquals("TOTAL_PRESERVED_FIXTURES = 28", 28, metrics.totalPreservedFixtures)
        assertEquals("EVALUABLE_GROUND_TRUTH = 2 (완전 실측 메타데이터 보유)", 2, metrics.evaluableCount)
        assertEquals("INSUFFICIENT_GROUND_TRUTH = 26 (세부 실측 미보유, 추정 배제)", 26, metrics.insufficientCount)

        // 2. Evaluable 2건에 대한 정밀도 검증
        assertEquals("CONFIDENT_MATCH = 2", 2, metrics.confidentMatchCount)
        assertEquals("AMBIGUOUS = 0", 0, metrics.ambiguousCount)
        assertEquals("NOT_FOUND = 0", 0, metrics.notFoundCount)
        assertEquals("WRONG_MATCH = 0 (독립 평가기 검증)", 0, metrics.wrongMatchCount)
        assertTrue(metrics.isWrongMatchZero)

        // 3. 개별 아이템 정답 확인
        val item1 = items.first { it.expectedRecord.displayName == "adv_live_timeline.png" }
        assertTrue(item1.isConfirmedMatch)
        assertEquals("adv_live_timeline.png", item1.matchResult.matchedCandidate?.filename)

        val item2 = items.first { it.expectedRecord.displayName == "pass-screen.png" }
        assertTrue(item2.isConfirmedMatch)
        assertEquals("pass-screen.png", item2.matchResult.matchedCandidate?.filename)
    }
}
