package com.ghostphoto.app.matcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

class CloudCandidateParserTest {

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
    fun parse_photoDetailsDump_extractsAllMetadata() {
        val file = findWorkspaceFile("experiment/photo_details_dump.xml")
        val candidate = CloudCandidateParser.parse(file)

        assertEquals("2026-08-29", candidate.dateStr)
        assertEquals("오후 11:59", candidate.timeMinuteStr)
        assertEquals("adv_live_timeline.png", candidate.filename)
        assertEquals(1248, candidate.width)
        assertEquals(1972, candidate.height)
        assertEquals("백업됨 • 2.8MB", candidate.displayedSizeText)
    }

    @Test
    fun parse_collisionCand0Info_extractsAvailableMetadata() {
        val file = findWorkspaceFile("experiment/collision/cand_0_info.xml")
        val candidate = CloudCandidateParser.parse(file)

        assertEquals("2026-08-29", candidate.dateStr)
        assertEquals("오후 7:01", candidate.timeMinuteStr)
        assertEquals("백업됨 • 7.7MB", candidate.displayedSizeText)
        assertEquals("samsung Galaxy Z Fold8", candidate.device)
        assertEquals("서울특별시", candidate.location)
    }
}
