package com.ghostphoto.app.scanner

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.ghostphoto.app.matcher.LocalMediaRecord

/**
 * 미디어 스캔 결과 래퍼. 쿼리 실패나 권한 부족 시 정상 빈 결과로 오인되지 않도록 명시적 실패 상태 분리.
 */
sealed class ScanFetchResult {
    data class Success(val mediaList: List<LocalMediaRecord>) : ScanFetchResult()
    data class Failure(val reason: String, val cause: Throwable? = null) : ScanFetchResult()
}

/**
 * 로컬 미디어 스캐너 인터페이스.
 * 플랫폼(MediaStore) 또는 모의 스캐너를 교체 가능하도록 추상화하여 PhotoPlace 등 타 모듈 재사용 지원.
 */
interface MediaScanner {
    fun scanAllLocalMedia(): ScanFetchResult
}

/**
 * Android MediaStore를 조회하여 현재 로컬 저장소에 존재하는 미디어(사진/영상) 목록을 스캔합니다.
 */
class LocalMediaScanner(private val context: Context) : MediaScanner {

    private val projection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.DATE_TAKEN,
        MediaStore.MediaColumns.WIDTH,
        MediaStore.MediaColumns.HEIGHT,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.RELATIVE_PATH
    )

    override fun scanAllLocalMedia(): ScanFetchResult {
        val results = mutableListOf<LocalMediaRecord>()

        val imgResult = scanUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/jpeg")
        if (imgResult is ScanFetchResult.Failure) {
            return imgResult
        }
        results.addAll((imgResult as ScanFetchResult.Success).mediaList)

        val vidResult = scanUri(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/mp4")
        if (vidResult is ScanFetchResult.Failure) {
            return vidResult
        }
        results.addAll((vidResult as ScanFetchResult.Success).mediaList)

        return ScanFetchResult.Success(results.sortedByDescending { it.takenAtMillis })
    }

    private fun scanUri(contentUri: Uri, defaultMime: String): ScanFetchResult {
        val list = mutableListOf<LocalMediaRecord>()
        val sortOrder = "${MediaStore.MediaColumns.DATE_TAKEN} DESC"

        try {
            val cursor = context.contentResolver.query(
                contentUri,
                projection,
                null,
                null,
                sortOrder
            ) ?: return ScanFetchResult.Failure("MediaStore Cursor가 null입니다. (권한 거부 또는 MediaStore 서비스 불가)")

            cursor.use {
                val idCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val dateCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                val widthCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
                val heightCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
                val sizeCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val mimeCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val pathCol = it.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)

                while (it.moveToNext()) {
                    val id = it.getLong(idCol)
                    val name = it.getString(nameCol) ?: "Unknown_$id"
                    val dateTaken = it.getLong(dateCol)
                    val width = it.getInt(widthCol)
                    val height = it.getInt(heightCol)
                    val size = it.getLong(sizeCol)
                    val mime = it.getString(mimeCol) ?: defaultMime
                    val relPath = if (pathCol != -1) it.getString(pathCol) else null

                    list.add(
                        LocalMediaRecord(
                            id = id,
                            displayName = name,
                            takenAtMillis = if (dateTaken > 0) dateTaken else System.currentTimeMillis(),
                            width = width,
                            height = height,
                            sizeBytes = size,
                            mimeType = mime,
                            relativePath = relPath
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            return ScanFetchResult.Failure("미디어 접근 권한이 없습니다 (SecurityException)", e)
        } catch (e: Exception) {
            return ScanFetchResult.Failure("MediaStore 쿼리 중 오류 발생: ${e.message}", e)
        }

        return ScanFetchResult.Success(list)
    }
}
