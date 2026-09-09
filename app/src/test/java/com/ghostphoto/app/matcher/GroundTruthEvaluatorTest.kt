package com.ghostphoto.app.matcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundTruthEvaluatorTest {

    @Test
    fun evaluate_calculatesMetricsAccurately_withZeroWrongMatch() {
        val evaluable = RealCloudGroundTruthFixtures.getEvaluableGroundTruth()
        assertEquals(2, evaluable.size)

        val target1 = evaluable.first { it.displayName == "adv_live_timeline.png" }
        val target2 = evaluable.first { it.displayName == "pass-screen.png" }

        val cand1 = CloudCandidate(
            candidateId = "c1",
            filename = "adv_live_timeline.png",
            dateStr = "2026-08-29",
            width = 1248,
            height = 1972,
            displayedSizeText = "2.8MB"
        )
        val cand2 = CloudCandidate(
            candidateId = "c2",
            filename = "pass-screen.png",
            dateStr = "2026-08-29",
            width = 1248,
            height = 1972,
            displayedSizeText = "662kB"
        )

        val results = listOf(
            MatchResult(
                localRecord = target1,
                verdict = MatchVerdict.CONFIDENT_MATCH,
                matchedCandidate = cand1,
                matchReasons = listOf("모든 조건 일치")
            ),
            MatchResult(
                localRecord = target2,
                verdict = MatchVerdict.CONFIDENT_MATCH,
                matchedCandidate = cand2,
                matchReasons = listOf("모든 조건 일치")
            )
        )

        val (metrics, items) = GroundTruthEvaluator.evaluate(
            expectedRecords = evaluable,
            matchResults = results,
            totalPreservedFixtures = 28
        )

        assertEquals(28, metrics.totalPreservedFixtures)
        assertEquals(2, metrics.evaluableCount)
        assertEquals(26, metrics.insufficientCount)
        assertEquals(2, metrics.confidentMatchCount)
        assertEquals(0, metrics.ambiguousCount)
        assertEquals(0, metrics.notFoundCount)
        assertEquals(0, metrics.wrongMatchCount)
        assertTrue(metrics.isWrongMatchZero)

        println(metrics.toSummaryReport())
    }

    @Test
    fun evaluate_detectsTrueWrongMatch_whenCandidateIdentityDiffersFromExpected() {
        val evaluable = RealCloudGroundTruthFixtures.getEvaluableGroundTruth()
        val target1 = evaluable.first { it.displayName == "adv_live_timeline.png" }

        // Matcher는 어떤 이유로 오선택했으나 실제 파일명이 다른 경우 (Adversarial False Positive)
        val impostorCand = CloudCandidate(
            candidateId = "impostor",
            filename = "completely_different_file.png",
            dateStr = "2026-08-29",
            width = 1248,
            height = 1972,
            displayedSizeText = "2.8MB"
        )

        val wrongMatchResult = listOf(
            MatchResult(
                localRecord = target1,
                verdict = MatchVerdict.CONFIDENT_MATCH,
                matchedCandidate = impostorCand,
                matchReasons = listOf("모호한 증거로 오선택")
            )
        )

        val (metrics, items) = GroundTruthEvaluator.evaluate(
            expectedRecords = listOf(target1),
            matchResults = wrongMatchResult,
            totalPreservedFixtures = 28
        )

        assertEquals("독립 검증기에서 WRONG_MATCH 1건 적발되어야 함", 1, metrics.wrongMatchCount)
        assertEquals(0, metrics.confidentMatchCount)
        assertFalse("WRONG_MATCH가 발생했으므로 실패 판정", metrics.isWrongMatchZero)

        val wrongItem = items.first()
        assertTrue(wrongItem.isWrongMatch)
        assertFalse(wrongItem.isConfirmedMatch)
    }
}
