package com.ghostphoto.app.matcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * P1: 저장된 실제 Google Photos 접근성 XML 덤프를 이용한 Replay 파이프라인 통합 테스트.
 * (실제 덤프에 존재하는 메타데이터만을 재현 입력으로 사용하여 Parser ↔ Matcher 연결 검증)
 */
class CloudFixtureReplayIntegrationTest {

    private val zoneId = ZoneId.systemDefault()
    private val testDateEpochMillis = LocalDate.of(2026, 8, 29)
        .atTime(19, 1)
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()

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
    fun replay_photoDetailsDump_producesConfidentMatch() {
        // 실제 experiment/photo_details_dump.xml 에 존재하는 메타데이터:
        // 파일명: adv_live_timeline.png, 해상도: 1248x1972, 용량: 백업됨 • 2.8MB, 일자: 2026-08-29
        val xmlFile = findWorkspaceFile("experiment/photo_details_dump.xml")

        val targetLocal = LocalMediaRecord(
            id = 101L,
            displayName = "adv_live_timeline.png",
            takenAtMillis = testDateEpochMillis,
            width = 1248,
            height = 1972,
            sizeBytes = 2_936_012L, // 2.80 MiB -> 2.8 MB 반올림 범위 내 완벽 일치
            state = MediaLifecycleState.MISSING_FROM_LOCAL_SCAN
        )

        val coordinator = GhostPipelineCoordinator()
        val report = coordinator.runMatchingFromXmlFiles(
            missingTargets = listOf(targetLocal),
            xmlFiles = listOf(xmlFile),
            isFullTraversalCompleted = true
        )

        assertEquals("WRONG_MATCH = 0 원칙 준수", 0, report.wrongCount)
        assertEquals("정확히 일치하므로 CONFIDENT_MATCH 1건", 1, report.confidentCount)
        assertEquals(0, report.ambiguousCount)
        assertEquals(0, report.notFoundCount)
        assertTrue(report.isWrongMatchZero)

        val winner = report.confidentMatches.first()
        assertEquals("adv_live_timeline.png", winner.matchedCandidate?.filename)
        assertEquals(1248, winner.matchedCandidate?.width)
        assertEquals(1972, winner.matchedCandidate?.height)
    }

    @Test
    fun replay_incompleteMetadataDump_safelyProducesAmbiguous() {
        // cand_0_info.xml: 일자(2026-08-29)와 용량(7.7MB)만 있고 파일명 노드가 없는 불완전한 상세정보 덤프
        val xmlFile = findWorkspaceFile("experiment/collision/cand_0_info.xml")

        val targetLocal = LocalMediaRecord(
            id = 102L,
            displayName = "20260829_190140.jpg",
            takenAtMillis = testDateEpochMillis,
            width = 4000,
            height = 3000,
            sizeBytes = 7_685_006L,
            state = MediaLifecycleState.MISSING_FROM_LOCAL_SCAN
        )

        val coordinator = GhostPipelineCoordinator()
        val report = coordinator.runMatchingFromXmlFiles(
            missingTargets = listOf(targetLocal),
            xmlFiles = listOf(xmlFile),
            isFullTraversalCompleted = true
        )

        // 파일명이 없으므로 추측하지 않고 안전하게 AMBIGUOUS로 남겨야 함 (오선택 절대 방지)
        assertEquals("오선택 0건", 0, report.wrongCount)
        assertEquals("증거 불충분으로 CONFIDENT_MATCH는 0건이어야 함", 0, report.confidentCount)
        assertEquals("안전 배제(AMBIGUOUS) 1건", 1, report.ambiguousCount)
    }

    @Test
    fun replay_unrelatedTarget_safelyProducesNotFound() {
        val xmlFile = findWorkspaceFile("experiment/photo_details_dump.xml")

        val unrelatedTarget = LocalMediaRecord(
            id = 103L,
            displayName = "completely_different_photo.jpg",
            takenAtMillis = testDateEpochMillis,
            width = 4080,
            height = 3060,
            sizeBytes = 5_000_000L,
            state = MediaLifecycleState.MISSING_FROM_LOCAL_SCAN
        )

        val coordinator = GhostPipelineCoordinator()
        val report = coordinator.runMatchingFromXmlFiles(
            missingTargets = listOf(unrelatedTarget),
            xmlFiles = listOf(xmlFile),
            isFullTraversalCompleted = true
        )

        assertEquals("오선택 0건", 0, report.wrongCount)
        assertEquals(0, report.confidentCount)
        assertEquals(0, report.ambiguousCount)
        assertEquals("일치하는 후보가 없으므로 NOT_FOUND 1건", 1, report.notFoundCount)
    }

    @Test
    fun replay_adversarialDecoy_neverProducesWrongMatch() {
        val target = LocalMediaRecord(
            id = 104L,
            displayName = "my_target_screenshot.png",
            takenAtMillis = testDateEpochMillis,
            width = 1248,
            height = 1972,
            sizeBytes = 2_000_000L,
            state = MediaLifecycleState.MISSING_FROM_LOCAL_SCAN
        )

        // 동일 날짜/동일 분이지만 다른 속성을 가진 위장 후보
        val decoyCandidate = CloudCandidate(
            candidateId = "decoy_cand_1",
            dateStr = "2026-08-29",
            timeMinuteStr = "오후 7:01",
            filename = "some_other_file.jpg",
            width = 4000,
            height = 3000,
            displayedSizeText = "10.5MB"
        )

        val coordinator = GhostPipelineCoordinator()
        val report = coordinator.runMatching(
            missingTargets = listOf(target),
            observedCandidates = listOf(decoyCandidate),
            isFullTraversalCompleted = true
        )

        assertEquals("Decoy에 속아 WRONG_MATCH가 발생하면 안 됨", 0, report.wrongCount)
        assertEquals(0, report.confidentCount)
        assertEquals(1, report.notFoundCount)
        assertTrue(report.toSummaryString().contains("PASS (WRONG_MATCH = 0 원칙 준수 ✅)"))
    }
}
