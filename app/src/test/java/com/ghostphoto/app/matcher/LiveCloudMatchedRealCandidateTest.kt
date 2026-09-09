package com.ghostphoto.app.matcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * 실기기 Google Photos에서 방금 실시간 덤프한 2026-09-06 소실 사진 3건의
 * CloudCandidateParser ➔ GhostPipelineCoordinator 실시간 대조 검증 테스트.
 */
class LiveCloudMatchedRealCandidateTest {

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
    fun match_liveGooglePhotosDumps_withRealDeletedTargets_achievesConfidentMatch() {
        val zoneId = ZoneId.systemDefault()
        val epoch20260906 = LocalDate.of(2026, 9, 6)
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()

        // 1. 실기기에서 파싱한 실제 Google Photos XML 덤프 3개
        val xml1 = findWorkspaceFile("experiment/detail_tile1_dump.xml")
        val xml2 = findWorkspaceFile("experiment/detail_tile2_dump.xml")
        val xml3 = findWorkspaceFile("experiment/detail_tile3_dump.xml")

        val cand1 = CloudCandidateParser.parse(xml1, "cloud_naver_1")
        val cand2 = CloudCandidateParser.parse(xml2, "cloud_naver_2")
        val cand3 = CloudCandidateParser.parse(xml3, "cloud_naver_3")

        assertEquals("Screenshot_20260906_192955_NAVER.jpg", cand1.filename)
        assertEquals("Screenshot_20260906_192910_NAVER.jpg", cand2.filename)
        assertEquals("Screenshot_20260906_192858_NAVER.jpg", cand3.filename)

        assertEquals("2026-09-06", cand1.dateStr)
        assertEquals(1248, cand1.width)
        assertEquals(1736, cand1.height)

        // 2. 기기에서 실제 소실 감지되었던 타겟 3건
        val target1 = LocalMediaRecord(
            id = 201L,
            displayName = "Screenshot_20260906_192955_NAVER.jpg",
            takenAtMillis = epoch20260906 + (19 * 3600 + 29 * 60 + 55) * 1000L,
            width = 1248,
            height = 1736,
            sizeBytes = 552_000L, // 539kB 표시 호환
            state = MediaLifecycleState.MISSING_FROM_LOCAL_SCAN
        )
        val target2 = LocalMediaRecord(
            id = 202L,
            displayName = "Screenshot_20260906_192910_NAVER.jpg",
            takenAtMillis = epoch20260906 + (19 * 3600 + 29 * 60 + 10) * 1000L,
            width = 1248,
            height = 1736,
            sizeBytes = 646_000L, // 631kB 표시 호환
            state = MediaLifecycleState.MISSING_FROM_LOCAL_SCAN
        )
        val target3 = LocalMediaRecord(
            id = 203L,
            displayName = "Screenshot_20260906_192858_NAVER.jpg",
            takenAtMillis = epoch20260906 + (19 * 3600 + 28 * 60 + 58) * 1000L,
            width = 1248,
            height = 1736,
            sizeBytes = 624_640L, // 610kB 표시 호환 (610 * 1024)
            state = MediaLifecycleState.MISSING_FROM_LOCAL_SCAN
        )

        // 3. 파이프라인 코디네이터 실행
        val coordinator = GhostPipelineCoordinator()
        val report = coordinator.runMatching(
            missingTargets = listOf(target1, target2, target3),
            observedCandidates = listOf(cand1, cand2, cand3),
            isFullTraversalCompleted = true
        )

        val policy = StandardSizeCompatibilityPolicy()
        println("cand3: filename=${cand3.filename}, size=${cand3.displayedSizeText}, range=${policy.getCompatibleByteRange(cand3.displayedSizeText)}, isComp=${policy.isCompatible(target3.sizeBytes, cand3.displayedSizeText)}")
        println("AMBIGUOUS: ${report.ambiguousMatches.map { it.localRecord.displayName to it.matchReasons }}")
        println("NOT_FOUND: ${report.notFoundMatches.map { it.localRecord.displayName to it.matchReasons }}")

        // 4. 검증: WRONG_MATCH == 0 및 CONFIDENT_MATCH == 3
        assertEquals("WRONG_MATCH는 무조건 0건이어야 함", 0, report.wrongCount)
        assertEquals("소실 타겟 3건 전수가 CONFIDENT_MATCH로 잡혀야 함", 3, report.confidentCount)
        assertEquals(0, report.ambiguousCount)
        assertEquals(0, report.notFoundCount)
        assertTrue(report.isWrongMatchZero)

        val match1 = report.confidentMatches.first { it.localRecord.displayName == "Screenshot_20260906_192955_NAVER.jpg" }
        assertEquals("cloud_naver_1", match1.matchedCandidate?.candidateId)

        val match2 = report.confidentMatches.first { it.localRecord.displayName == "Screenshot_20260906_192910_NAVER.jpg" }
        assertEquals("cloud_naver_2", match2.matchedCandidate?.candidateId)

        val match3 = report.confidentMatches.first { it.localRecord.displayName == "Screenshot_20260906_192858_NAVER.jpg" }
        assertEquals("cloud_naver_3", match3.matchedCandidate?.candidateId)
    }
}
