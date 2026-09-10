package com.ghostphoto.app.accessibility

import com.ghostphoto.app.matcher.LocalMediaRecord
import com.ghostphoto.app.matcher.MediaLifecycleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class GooglePhotosGridSelectorTest {

    @Test
    fun testExpectedDescPatternAndMatching() {
        val zoneId = ZoneId.systemDefault()
        val epoch20260906 = LocalDate.of(2026, 9, 6)
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()

        val target1 = LocalMediaRecord(
            id = 201L,
            displayName = "Screenshot_20260906_192955_NAVER.jpg",
            takenAtMillis = epoch20260906 + (19 * 3600 + 29 * 60 + 55) * 1000L,
            width = 1248,
            height = 1736,
            sizeBytes = 552_000L,
            state = MediaLifecycleState.MISSING_FROM_LOCAL_SCAN
        )

        val pattern = GooglePhotosGridSelector.getExpectedDescPattern(target1)
        println("Generated pattern for 19:29: $pattern")

        val realGooglePhotosDesc = "2026. 9. 6. 오후 7:29에 촬영한 사진"
        assertTrue(GooglePhotosGridSelector.matchesTarget(realGooglePhotosDesc, target1))

        val nonMatchingDesc = "2026. 9. 6. 오후 7:38에 촬영한 사진"
        assertFalse(GooglePhotosGridSelector.matchesTarget(nonMatchingDesc, target1))
    }
}
