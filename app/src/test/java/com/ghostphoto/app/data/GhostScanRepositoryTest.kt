package com.ghostphoto.app.data

import com.ghostphoto.app.matcher.LocalMediaRecord
import com.ghostphoto.app.matcher.MediaLifecycleState
import com.ghostphoto.app.scanner.ScanFetchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GhostScanRepositoryTest {

    private lateinit var storage: InMemorySnapshotStorage
    private var mockFetchResult: ScanFetchResult = ScanFetchResult.Success(emptyList())
    private lateinit var repository: GhostScanRepository

    @Before
    fun setUp() {
        storage = InMemorySnapshotStorage()
        mockFetchResult = ScanFetchResult.Success(emptyList())
        repository = GhostScanRepository(
            storage = storage,
            mediaFetcher = { mockFetchResult }
        )
    }

    @Test
    fun firstScan_createsBaselineSnapshot_generatesZeroGhostCandidates() {
        val media1 = LocalMediaRecord(101L, "photo1.jpg", 1000L, 1080, 1920, 2000000L)
        val media2 = LocalMediaRecord(102L, "photo2.jpg", 2000L, 1080, 1920, 3000000L)
        mockFetchResult = ScanFetchResult.Success(listOf(media1, media2))

        val result = repository.performScan()

        assertTrue("Result must be Success", result is ScanExecutionResult.Success)
        val success = result as ScanExecutionResult.Success
        assertTrue("First scan must be marked as baseline", success.isBaselineScan)
        assertTrue("First scan must NOT produce ghost candidates", success.ghostCandidates.isEmpty())
        assertEquals(2, success.totalLocalCount)

        val saved = storage.loadSnapshot()
        assertEquals(2, saved?.size)
        assertTrue(saved!!.all { it.state == MediaLifecycleState.ACTIVE })
    }

    @Test
    fun subsequentScan_detectsDisappearedMedia() {
        val photo1 = LocalMediaRecord(101L, "stay.jpg", 1000L, 1080, 1920, 2000000L)
        val photo2 = LocalMediaRecord(102L, "will_delete.jpg", 2000L, 1080, 1920, 3000000L)
        mockFetchResult = ScanFetchResult.Success(listOf(photo1, photo2))
        repository.performScan()

        // 2. 사용자가 기기에서 photo2 삭제
        mockFetchResult = ScanFetchResult.Success(listOf(photo1))

        val result = repository.performScan()
        assertTrue(result is ScanExecutionResult.Success)
        val success = result as ScanExecutionResult.Success

        assertFalse("Subsequent scan is not baseline", success.isBaselineScan)
        assertEquals(1, success.totalLocalCount)
        assertEquals(1, success.ghostCandidates.size)

        val ghost = success.ghostCandidates.first()
        assertEquals(102L, ghost.id)
        assertEquals("will_delete.jpg", ghost.displayName)
        assertEquals(MediaLifecycleState.MISSING_FROM_LOCAL_SCAN, ghost.state)
        assertTrue((ghost.missingDetectedAtMillis ?: 0L) > 0L)
    }

    @Test
    fun recovery_reappearingMedia_restoresToActiveState() {
        val photo1 = LocalMediaRecord(101L, "stay.jpg", 1000L, 1080, 1920, 2000000L)
        val photo2 = LocalMediaRecord(102L, "temporary_missing.jpg", 2000L, 1080, 1920, 3000000L)
        mockFetchResult = ScanFetchResult.Success(listOf(photo1, photo2))
        repository.performScan()

        // 2. 일시적으로 사라짐 (SD카드 분리 등)
        mockFetchResult = ScanFetchResult.Success(listOf(photo1))
        val scan2 = repository.performScan() as ScanExecutionResult.Success
        assertEquals(1, scan2.ghostCandidates.size)

        // 3. 미디어가 다시 기기에 복구됨 (Re-appeared)
        mockFetchResult = ScanFetchResult.Success(listOf(photo1, photo2))
        val scan3 = repository.performScan() as ScanExecutionResult.Success

        assertEquals(0, scan3.ghostCandidates.size)
        assertEquals(1, scan3.recoveredCount)

        val currentSnapshot = storage.loadSnapshot()
        val recoveredItem = currentSnapshot?.find { it.id == 102L }
        assertEquals(MediaLifecycleState.ACTIVE, recoveredItem?.state)
        assertNull(recoveredItem?.missingDetectedAtMillis)
    }

    @Test
    fun scanFailure_abortsWithoutCommittingSnapshot_preventsMassFalseMissing() {
        val photo1 = LocalMediaRecord(101L, "stay.jpg", 1000L, 1080, 1920, 2000000L)
        mockFetchResult = ScanFetchResult.Success(listOf(photo1))
        repository.performScan()

        // 2. 권한 취소나 MediaStore 쿼리 실패 발생
        mockFetchResult = ScanFetchResult.Failure("SecurityException: Permission revoked")
        val result = repository.performScan()

        assertTrue("Scan must be aborted on failure", result is ScanExecutionResult.Aborted)
        val aborted = result as ScanExecutionResult.Aborted
        assertTrue(aborted.reason.contains("SecurityException"))

        // 기존 스냅샷이 빈 스캔으로 오인되어 훼손되지 않았는지 검증
        val currentSnapshot = storage.loadSnapshot()
        assertEquals(1, currentSnapshot?.size)
        assertEquals(MediaLifecycleState.ACTIVE, currentSnapshot?.first()?.state)
    }
}
