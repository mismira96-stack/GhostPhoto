package com.ghostphoto.app.matcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class GhostMatcherTest {

    private val zoneId = ZoneId.of("Asia/Seoul")
    private lateinit var matcherBinaryMiB: GhostMatcher
    private lateinit var matcherDecimalMB: GhostMatcher

    @Before
    fun setUp() {
        matcherBinaryMiB = GhostMatcher(
            zoneId = zoneId,
            sizePolicy = StandardSizeCompatibilityPolicy(standard = ByteUnitStandard.BINARY_MIB)
        )
        matcherDecimalMB = GhostMatcher(
            zoneId = zoneId,
            sizePolicy = StandardSizeCompatibilityPolicy(standard = ByteUnitStandard.DECIMAL_MB)
        )
    }

    private fun createMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zoneId)
            .toInstant()
            .toEpochMilli()
    }

    @Test
    fun realFixture_exactPredicatesMatch_confidentMatch_binaryPolicy() {
        // photo_details_dump.xml 실측치 (adv_live_timeline.png: 2,936,012 bytes, 1248x1972, 2.8MB 표시)
        // 2,936,012 / (1024*1024) = 2.80002 MiB -> 2.8MB 반올림 범위 [2.74, 2.86] MiB 내 일치
        val takenMillis = createMillis(2026, 8, 29, 23, 59)
        val local = LocalMediaRecord(
            id = 1001L,
            displayName = "adv_live_timeline.png",
            takenAtMillis = takenMillis,
            width = 1248,
            height = 1972,
            sizeBytes = 2936012L
        )

        val candidate = CloudCandidate(
            candidateId = "cloud_node_01",
            dateStr = "2026-08-29",
            timeMinuteStr = "오후 11:59",
            contentDesc = "사진 - 2026. 8. 29. 오후 11:59",
            filename = "adv_live_timeline.png",
            width = 1248,
            height = 1972,
            displayedSizeText = "백업됨 • 2.8MB"
        )

        val result = matcherBinaryMiB.match(local, listOf(candidate), isFullTraversalCompleted = true)

        assertEquals(MatchVerdict.CONFIDENT_MATCH, result.verdict)
        assertNotNull(result.matchedCandidate)
        assertEquals("cloud_node_01", result.matchedCandidate?.candidateId)
    }

    @Test
    fun decimalMbPolicy_supportsConfiguredStandard() {
        // 2,800,000 bytes = 2.80 decimal MB -> [2.74, 2.86] decimal MB 범위 내 일치
        val takenMillis = createMillis(2026, 8, 29, 23, 59)
        val local = LocalMediaRecord(
            id = 1002L,
            displayName = "decimal_photo.jpg",
            takenAtMillis = takenMillis,
            width = 1000,
            height = 1000,
            sizeBytes = 2800000L
        )

        val candidate = CloudCandidate(
            candidateId = "cloud_node_decimal",
            dateStr = "2026-08-29",
            filename = "decimal_photo.jpg",
            width = 1000,
            height = 1000,
            displayedSizeText = "2.8MB"
        )

        val result = matcherDecimalMB.match(local, listOf(candidate), isFullTraversalCompleted = true)

        assertEquals(MatchVerdict.CONFIDENT_MATCH, result.verdict)
        assertNotNull(result.matchedCandidate)
    }

    @Test
    fun competingPlausibleCandidates_verdictAmbiguous_zeroFalsePositive() {
        val takenMillis = createMillis(2026, 8, 29, 19, 1)
        val local = LocalMediaRecord(
            id = 1003L,
            displayName = "20260829_190149.jpg",
            takenAtMillis = takenMillis,
            width = 4080,
            height = 3060,
            sizeBytes = 8074035L
        )

        val cand1 = CloudCandidate(
            candidateId = "burst_01",
            dateStr = "2026-08-29",
            filename = "20260829_190149.jpg",
            width = 4080,
            height = 3060,
            displayedSizeText = "백업됨 • 7.7MB"
        )

        val cand2 = CloudCandidate(
            candidateId = "burst_02",
            dateStr = "2026-08-29",
            filename = "20260829_190149.jpg",
            width = 4080,
            height = 3060,
            displayedSizeText = "백업됨 • 7.7MB"
        )

        val result = matcherBinaryMiB.match(local, listOf(cand1, cand2), isFullTraversalCompleted = true)

        assertEquals(MatchVerdict.AMBIGUOUS, result.verdict)
        assertNull("Competing candidates must yield null matchedCandidate to guarantee zero false positives", result.matchedCandidate)
    }

    @Test
    fun missingFilename_inconclusiveEvidence_verdictAmbiguous() {
        val takenMillis = createMillis(2026, 8, 29, 19, 1)
        val local = LocalMediaRecord(
            id = 1004L,
            displayName = "20260829_190149.jpg",
            takenAtMillis = takenMillis,
            width = 4080,
            height = 3060,
            sizeBytes = 8074035L
        )

        val candidateNoFilename = CloudCandidate(
            candidateId = "no_filename_cand",
            dateStr = "2026-08-29",
            width = 4080,
            height = 3060,
            displayedSizeText = "7.7MB",
            filename = null
        )

        val result = matcherBinaryMiB.match(local, listOf(candidateNoFilename), isFullTraversalCompleted = true)

        assertEquals(MatchVerdict.AMBIGUOUS, result.verdict)
    }

    @Test
    fun incompatibleByteSize_rejected() {
        val takenMillis = createMillis(2026, 8, 29, 10, 30)
        val local = LocalMediaRecord(
            id = 1005L,
            displayName = "photo.jpg",
            takenAtMillis = takenMillis,
            width = 1920,
            height = 1080,
            sizeBytes = 5000000L
        )

        val candidateDifferentSize = CloudCandidate(
            candidateId = "cand_diff_size",
            dateStr = "2026-08-29",
            filename = "photo.jpg",
            width = 1920,
            height = 1080,
            displayedSizeText = "2.1MB"
        )

        val result = matcherBinaryMiB.match(local, listOf(candidateDifferentSize), isFullTraversalCompleted = true)

        assertEquals(MatchVerdict.NOT_FOUND, result.verdict)
    }

    @Test
    fun traversalIncomplete_remainsAmbiguous() {
        val local = LocalMediaRecord(
            id = 1006L,
            displayName = "missing_target.jpg",
            takenAtMillis = createMillis(2026, 8, 29, 12, 0),
            width = 1000,
            height = 1000,
            sizeBytes = 1000000L
        )

        val result = matcherBinaryMiB.match(local, emptyList(), isFullTraversalCompleted = false)
        assertEquals(MatchVerdict.AMBIGUOUS, result.verdict)
    }

    @Test
    fun traversalComplete_zeroCandidates_notFound() {
        val local = LocalMediaRecord(
            id = 1007L,
            displayName = "missing_target.jpg",
            takenAtMillis = createMillis(2026, 8, 29, 12, 0),
            width = 1000,
            height = 1000,
            sizeBytes = 1000000L
        )

        val result = matcherBinaryMiB.match(local, emptyList(), isFullTraversalCompleted = true)
        assertEquals(MatchVerdict.NOT_FOUND, result.verdict)
    }
}
