package com.ghostphoto.app.matcher

import java.time.LocalDate
import java.time.ZoneId

/**
 * 28개 Real-Cloud Ground-Truth Fixture 정의 및 완성도 인벤토리
 */
object RealCloudGroundTruthFixtures {

    const val TOTAL_PRESERVED_FIXTURES = 28

    val all28PreservedFilenames = listOf(
        // PoC screen dumps (5)
        "screen.png", "app-screen.png", "app-scroll.png", "dump-screen.png", "pass-screen.png",
        // UI / Details (6)
        "photos-screen.png", "photos-selected.png", "photo-detail.png", "info-sheet.png", "real-info.png", "exif-info.png",
        // Test 1-3 (5)
        "test1-screen.png", "test1_timeline.png", "test2_detail.png", "test3_search_tab.png", "test3_search_result.png",
        // All-in-One experiments (8)
        "t1_timeline.png", "t2_detail_info.png", "t3_search_result.png", "t4_main_timeline.png", "t5_selection_check.png",
        "t3_search_20260828.png", "t4_page1.png", "t4_page2.png",
        // Adversarial / Info (4)
        "adv_timeline.png", "adv_live_timeline.png", "photo_details_dump.png", "cleanup_selected_state.png"
    )

    /**
     * 실제로 실측 세부정보(XML 덤프)가 확보되어
     * filename, resolution, size, timestamp가 완전한 검증 가능 Ground-Truth 목록.
     * (추정치/임의값으로 채우지 않고 실측된 메타데이터만 포함)
     */
    fun getEvaluableGroundTruth(zoneId: ZoneId = ZoneId.systemDefault()): List<LocalMediaRecord> {
        val epoch20260829 = LocalDate.of(2026, 8, 29)
            .atTime(19, 1)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()

        return listOf(
            LocalMediaRecord(
                id = 1001L,
                displayName = "adv_live_timeline.png",
                takenAtMillis = epoch20260829,
                width = 1248,
                height = 1972,
                sizeBytes = 2_936_012L, // 실측치: 2.80 MiB -> 2.8 MB 반올림 호환
                state = MediaLifecycleState.MISSING_FROM_LOCAL_SCAN
            ),
            LocalMediaRecord(
                id = 1002L,
                displayName = "pass-screen.png",
                takenAtMillis = epoch20260829,
                width = 1248,
                height = 1972,
                sizeBytes = 677_888L,   // 실측치: 662 kB 반올림 호환 (662 * 1024 = 677,888)
                state = MediaLifecycleState.MISSING_FROM_LOCAL_SCAN
            )
        )
    }

    val insufficientGroundTruthFilenames: List<String>
        get() {
            val evaluableNames = getEvaluableGroundTruth().map { it.displayName }.toSet()
            return all28PreservedFilenames.filter { !evaluableNames.contains(it) }
        }

    val evaluableCount: Int get() = getEvaluableGroundTruth().size
    val insufficientCount: Int get() = all28PreservedFilenames.size - evaluableCount
}
