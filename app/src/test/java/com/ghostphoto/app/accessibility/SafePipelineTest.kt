package com.ghostphoto.app.accessibility

import android.graphics.Rect
import com.ghostphoto.app.matcher.LocalMediaRecord
import com.ghostphoto.app.matcher.MediaLifecycleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class SafePipelineTest {

    @Test
    fun testMinutePatternMatching() {
        // 2026-09-06 19:29:15 KST
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 6, 19, 29, 15)
        }
        val target = LocalMediaRecord(
            id = 1L,
            displayName = "20260906_192915.jpg",
            takenAtMillis = cal.timeInMillis,
            width = 3000,
            height = 4000,
            sizeBytes = 1024L,
            mimeType = "image/jpeg",
            state = MediaLifecycleState.MISSING_FROM_LOCAL_SCAN
        )

        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val hour24 = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val amPm = if (hour24 >= 12) "오후" else "오전"
        val h12 = if (hour24 > 12) hour24 - 12 else hour24
        val min00 = String.format("%02d", minute)
        val minShort = minute.toString()

        val datePrefix = "$year.\\s*$month.\\s*$day."
        val p1 = "$amPm\\s*$h12:$min00"
        val p2 = "$amPm\\s*$h12:$minShort"
        val regex = Regex("""$datePrefix.*($p1|$p2)""")

        val testDescPhoto = "2026. 9. 6. 오후 7:29에 촬영한 사진"
        val testDescVideo = "2026. 9. 6. 오후 7:29에 촬영한 동영상"
        val testDescDiffMin = "2026. 9. 6. 오후 7:30에 촬영한 사진"

        assertTrue("Photo desc should match", regex.containsMatchIn(testDescPhoto))
        assertTrue("Video desc should match", regex.containsMatchIn(testDescVideo))
        assertFalse("Different minute should not match", regex.containsMatchIn(testDescDiffMin))
    }

    @Test
    fun testReadingOrderSortingAndSiblingIndex() {
        // Simulate two siblings in the same minute: 23:51
        // candA at [100, 500][400, 800] -> top=500, left=100
        // candB at [500, 500][800, 800] -> top=500, left=500
        // candC at [100, 900][400, 1200] -> top=900, left=100
        val candB = GridCandidate(
            desc = "2026. 9. 7. 오후 11:51에 촬영한 사진",
            bounds = Rect(500, 500, 800, 800),
            cx = 650, cy = 650,
            minuteKey = "2026.9.7|오후|11|51"
        )
        val candA = GridCandidate(
            desc = "2026. 9. 7. 오후 11:51에 촬영한 사진",
            bounds = Rect(100, 500, 400, 800),
            cx = 250, cy = 650,
            minuteKey = "2026.9.7|오후|11|51"
        )
        val candC = GridCandidate(
            desc = "2026. 9. 7. 오후 11:51에 촬영한 사진",
            bounds = Rect(100, 900, 400, 1200),
            cx = 250, cy = 1050,
            minuteKey = "2026.9.7|오후|11|51"
        )

        val candidates = listOf(candC, candB, candA)
        val sorted = candidates.sortedWith(
            compareBy<GridCandidate> { it.bounds.top }
                .thenBy { it.bounds.left }
        )

        assertEquals("First in reading order should be candA", candA.cx, sorted[0].cx)
        assertEquals("Second in reading order should be candB", candB.cx, sorted[1].cx)
        assertEquals("Third in reading order should be candC", candC.cx, sorted[2].cx)

        sorted.forEachIndexed { idx, c -> c.siblingIndex = idx }
        assertEquals(0, sorted[0].siblingIndex)
        assertEquals(1, sorted[1].siblingIndex)
        assertEquals(2, sorted[2].siblingIndex)
    }

    @Test
    fun testPass2BindingAndAmbiguousSkip() {
        // Suppose Pass 1 verified candB as CONFIDENT (bound to siblingIndex = 1)
        // and candA was AMBIGUOUS (not bound)
        val bindingB = VerifiedBinding(
            targetName = "target_b.jpg",
            dateStr = "2026-09-07",
            datePrefix = "2026.\\s*9.\\s*7.",
            minuteRegex = "(오후\\s*11:51)",
            minuteKey = "2026.9.7|오후|11|51",
            siblingIndex = 1,
            totalSiblingsInMinute = 2,
            width = 1920,
            height = 1080
        )

        val verifiedBindings = listOf(bindingB)

        // In Pass 2, we find candA (idx=0) and candB (idx=1)
        val pass2Siblings = listOf(
            GridCandidate("descA", Rect(100, 500, 400, 800), 250, 650, "2026.9.7|오후|11|51", siblingIndex = 0),
            GridCandidate("descB", Rect(500, 500, 800, 800), 650, 650, "2026.9.7|오후|11|51", siblingIndex = 1)
        )

        // Sibling 0: should NOT match bindingB (it's ambiguous)
        val matchFor0 = verifiedBindings.find { !it.isSelected && it.siblingIndex == pass2Siblings[0].siblingIndex }
        assertNull("Ambiguous candidate must not be bound or selected", matchFor0)

        // Sibling 1: should match bindingB
        val matchFor1 = verifiedBindings.find { !it.isSelected && it.siblingIndex == pass2Siblings[1].siblingIndex }
        assertNotNull("Confident candidate must match binding", matchFor1)
        assertEquals("target_b.jpg", matchFor1?.targetName)
    }

    @Test
    fun testDriftDetectionPreventsMisSelection() {
        // Pass 1 had 2 siblings for this minute
        val binding = VerifiedBinding(
            targetName = "target_sibling1.jpg",
            dateStr = "2026-09-07",
            datePrefix = "2026.\\s*9.\\s*7.",
            minuteRegex = "(오후\\s*11:51)",
            minuteKey = "2026.9.7|오후|11|51",
            siblingIndex = 1,
            totalSiblingsInMinute = 2
        )

        // Pass 2 encountered a layout drift: only 1 sibling is rendered
        val pass2SiblingsDrift = listOf(
            GridCandidate("descA", Rect(100, 500, 400, 800), 250, 650, "2026.9.7|오후|11|51", siblingIndex = 0)
        )

        val hasDrift = pass2SiblingsDrift.size < binding.totalSiblingsInMinute
        assertTrue("Layout drift should be detected when sibling count doesn't match", hasDrift)
    }
}
