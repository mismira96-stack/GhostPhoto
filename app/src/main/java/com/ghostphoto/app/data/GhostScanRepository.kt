package com.ghostphoto.app.data

import android.content.Context
import android.content.SharedPreferences
import com.ghostphoto.app.matcher.LocalMediaRecord
import com.ghostphoto.app.matcher.MediaLifecycleState
import com.ghostphoto.app.scanner.LocalMediaScanner
import com.ghostphoto.app.scanner.ScanFetchResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 스냅샷 영속화 인터페이스 (단위 테스트를 위해 분리)
 */
interface SnapshotStorage {
    fun loadSnapshot(): List<LocalMediaRecord>?
    fun saveSnapshot(records: List<LocalMediaRecord>)
    fun clear()
}

/**
 * JSON 직렬화 및 역직렬화 전담 유틸리티
 */
object SnapshotJsonSerializer {
    fun serialize(records: List<LocalMediaRecord>): String {
        val array = JSONArray()
        for (item in records) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("displayName", item.displayName)
                put("takenAtMillis", item.takenAtMillis)
                put("width", item.width)
                put("height", item.height)
                put("sizeBytes", item.sizeBytes)
                put("mimeType", item.mimeType)
                put("relativePath", item.relativePath)
                put("state", item.state.name)
                put("firstSeenAtMillis", item.firstSeenAtMillis)
                put("lastSeenAtMillis", item.lastSeenAtMillis)
                if (item.missingDetectedAtMillis != null) {
                    put("missingDetectedAtMillis", item.missingDetectedAtMillis)
                }
            }
            array.put(obj)
        }
        return array.toString()
    }

    fun deserialize(json: String): List<LocalMediaRecord> {
        val list = mutableListOf<LocalMediaRecord>()
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(
                LocalMediaRecord(
                    id = obj.getLong("id"),
                    displayName = obj.getString("displayName"),
                    takenAtMillis = obj.getLong("takenAtMillis"),
                    width = obj.getInt("width"),
                    height = obj.getInt("height"),
                    sizeBytes = obj.getLong("sizeBytes"),
                    mimeType = obj.optString("mimeType", "image/jpeg"),
                    relativePath = if (obj.has("relativePath")) obj.getString("relativePath") else null,
                    state = MediaLifecycleState.valueOf(obj.optString("state", MediaLifecycleState.ACTIVE.name)),
                    firstSeenAtMillis = obj.optLong("firstSeenAtMillis", System.currentTimeMillis()),
                    lastSeenAtMillis = obj.optLong("lastSeenAtMillis", System.currentTimeMillis()),
                    missingDetectedAtMillis = if (obj.has("missingDetectedAtMillis")) obj.getLong("missingDetectedAtMillis") else null
                )
            )
        }
        return list
    }
}

/**
 * 파일 기반 영속 스냅샷 저장소 구현체 (Cold Restart 및 대용량 데이터 대응)
 */
class FileSnapshotStorage(private val file: File) : SnapshotStorage {
    override fun loadSnapshot(): List<LocalMediaRecord>? {
        if (!file.exists()) return null
        return try {
            SnapshotJsonSerializer.deserialize(file.readText())
        } catch (e: Exception) {
            null
        }
    }

    override fun saveSnapshot(records: List<LocalMediaRecord>) {
        file.parentFile?.mkdirs()
        file.writeText(SnapshotJsonSerializer.serialize(records))
    }

    override fun clear() {
        if (file.exists()) file.delete()
    }
}

/**
 * Android SharedPreferences 기반 스냅샷 저장소 구현체
 */
class SharedPrefsSnapshotStorage(context: Context) : SnapshotStorage {
    private val prefs: SharedPreferences = context.getSharedPreferences("ghost_photo_history", Context.MODE_PRIVATE)

    override fun loadSnapshot(): List<LocalMediaRecord>? {
        val json = prefs.getString("snapshot_data", null) ?: return null
        return try {
            SnapshotJsonSerializer.deserialize(json)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override fun saveSnapshot(records: List<LocalMediaRecord>) {
        val json = SnapshotJsonSerializer.serialize(records)
        prefs.edit().putString("snapshot_data", json).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }
}

/**
 * 인메모리 스냅샷 저장소 구현체 (단위 테스트 전용)
 */
class InMemorySnapshotStorage : SnapshotStorage {
    private var data: List<LocalMediaRecord>? = null

    override fun loadSnapshot(): List<LocalMediaRecord>? = data
    override fun saveSnapshot(records: List<LocalMediaRecord>) {
        data = records.toList()
    }
    override fun clear() {
        data = null
    }
}

/**
 * 스캔 실행 결과
 */
sealed class ScanExecutionResult {
    data class Success(
        val isBaselineScan: Boolean,
        val totalLocalCount: Int,
        val ghostCandidates: List<LocalMediaRecord>,
        val recoveredCount: Int = 0
    ) : ScanExecutionResult()

    data class Aborted(
        val reason: String,
        val cause: Throwable? = null
    ) : ScanExecutionResult()
}

/**
 * 로컬 미디어 히스토리 및 델타 감지 레포지토리.
 *
 * 안전 규칙:
 * 1. First scan: baseline snapshot 생성 (0 ghost candidates).
 * 2. Subsequent scan: previous snapshot vs current MediaStore -> disappeared media detection.
 * 3. Recovery: 이전에 MISSING이었던 아이템이 다시 발견되면 ACTIVE로 복구.
 * 4. Scan Validity: 쿼리 실패 / 권한 에러 시 스냅샷을 오염시키지 않고 즉시 Abort (대량 False Missing 방지).
 */
class GhostScanRepository(
    private val storage: SnapshotStorage,
    private val mediaFetcher: () -> ScanFetchResult
) {

    constructor(context: Context) : this(
        storage = SharedPrefsSnapshotStorage(context),
        mediaFetcher = { LocalMediaScanner(context).scanAllLocalMedia() }
    )

    fun performScan(): ScanExecutionResult {
        // 1. Scan Validity 확인: 쿼리 실패나 권한 문제 시 정상 빈 스캔으로 오인하지 않고 즉시 중단
        val fetchResult = mediaFetcher()
        if (fetchResult is ScanFetchResult.Failure) {
            return ScanExecutionResult.Aborted(
                reason = "스캔 실패로 스냅샷 델타 커밋 중단: ${fetchResult.reason}",
                cause = fetchResult.cause
            )
        }

        val currentMedia = (fetchResult as ScanFetchResult.Success).mediaList
        val previousSnapshot = storage.loadSnapshot()

        // 2. 최초 스캔 (First scan) -> Baseline snapshot 생성, Ghost 후보 0건 보장
        if (previousSnapshot == null) {
            val baseline = currentMedia.map { it.copy(state = MediaLifecycleState.ACTIVE) }
            storage.saveSnapshot(baseline)
            return ScanExecutionResult.Success(
                isBaselineScan = true,
                totalLocalCount = currentMedia.size,
                ghostCandidates = emptyList(),
                recoveredCount = 0
            )
        }

        // 3. 후속 스캔 (Subsequent scan) -> 델타 감지 및 복구(Recovery) 처리
        val currentMap = currentMedia.associateBy { it.id }
        val now = System.currentTimeMillis()

        val ghostCandidates = mutableListOf<LocalMediaRecord>()
        val updatedSnapshot = mutableListOf<LocalMediaRecord>()
        var recoveredCount = 0

        for (prev in previousSnapshot) {
            val matchingCurrent = currentMap[prev.id]
            if (matchingCurrent != null) {
                // 기기에 다시 나타남 / 계속 존재함
                if (prev.state == MediaLifecycleState.MISSING_FROM_LOCAL_SCAN) {
                    // 이전 소실 상태에서 복구 (Recovery)
                    recoveredCount++
                    updatedSnapshot.add(
                        prev.copy(
                            state = MediaLifecycleState.ACTIVE,
                            lastSeenAtMillis = now,
                            missingDetectedAtMillis = null
                        )
                    )
                } else {
                    updatedSnapshot.add(
                        prev.copy(
                            state = MediaLifecycleState.ACTIVE,
                            lastSeenAtMillis = now
                        )
                    )
                }
            } else {
                // 현재 기기 MediaStore에 없음
                if (prev.state == MediaLifecycleState.USER_DELETED_FROM_APP) {
                    // 미래 호환 상태 보존
                    updatedSnapshot.add(prev)
                } else {
                    val missingRecord = prev.copy(
                        state = MediaLifecycleState.MISSING_FROM_LOCAL_SCAN,
                        missingDetectedAtMillis = prev.missingDetectedAtMillis ?: now
                    )
                    updatedSnapshot.add(missingRecord)
                    ghostCandidates.add(missingRecord)
                }
            }
        }

        // 새로 발견된 미디어 추가
        val prevIds = previousSnapshot.map { it.id }.toSet()
        for (curr in currentMedia) {
            if (!prevIds.contains(curr.id)) {
                updatedSnapshot.add(
                    curr.copy(
                        state = MediaLifecycleState.ACTIVE,
                        firstSeenAtMillis = now,
                        lastSeenAtMillis = now
                    )
                )
            }
        }

        // 4. 유효 스캔 확인 후 스냅샷 커밋
        storage.saveSnapshot(updatedSnapshot)

        return ScanExecutionResult.Success(
            isBaselineScan = false,
            totalLocalCount = currentMedia.size,
            ghostCandidates = ghostCandidates,
            recoveredCount = recoveredCount
        )
    }
}
