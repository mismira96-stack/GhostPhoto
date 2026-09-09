package com.ghostphoto.app.accessibility

import com.ghostphoto.app.matcher.CloudCandidateParser
import com.ghostphoto.app.matcher.GhostMatcher
import com.ghostphoto.app.matcher.LocalMediaRecord
import com.ghostphoto.app.matcher.MatchVerdict
import com.ghostphoto.app.matcher.MediaLifecycleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AccessibilityParserTest {

    @Test
    fun parseFromTexts_parsesExtractedAccessibilityNodeStringsAccurately() {
        val texts = listOf(
            "Screenshot_20260906_192955_NAVER.jpg",
            "2026년 9월 6일 (일) • 오후 7:29",
            "1248 x 1736",
            "백업됨 • 539kB",
            "samsung SM-F971N"
        )
        val descs = listOf(
            "2026. 9. 6. 오후 7:29에 촬영한 사진",
            "휴지통"
        )

        val candidate = CloudCandidateParser.parseFromTexts(texts, descs, "acc_candidate_1")

        assertEquals("Screenshot_20260906_192955_NAVER.jpg", candidate.filename)
        assertEquals("2026-09-06", candidate.dateStr)
        assertEquals("오후 7:29", candidate.timeMinuteStr)
        assertEquals(1248, candidate.width)
        assertEquals(1736, candidate.height)
        assertEquals("백업됨 • 539kB", candidate.displayedSizeText)

        val zoneId = ZoneId.systemDefault()
        val epoch20260906 = LocalDate.of(2026, 9, 6).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val target = LocalMediaRecord(
            id = 101L,
            displayName = "Screenshot_20260906_192955_NAVER.jpg",
            takenAtMillis = epoch20260906 + (19 * 3600 + 29 * 60 + 55) * 1000L,
            width = 1248,
            height = 1736,
            sizeBytes = 552_000L,
            state = MediaLifecycleState.MISSING_FROM_LOCAL_SCAN
        )

        val matcher = GhostMatcher()
        val result = matcher.match(target, listOf(candidate), isFullTraversalCompleted = false)
        assertEquals(MatchVerdict.CONFIDENT_MATCH, result.verdict)
    }
}
