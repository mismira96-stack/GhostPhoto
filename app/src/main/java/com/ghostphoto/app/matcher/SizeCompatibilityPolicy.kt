package com.ghostphoto.app.matcher

import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * 바이트 단위 계산 표준
 */
enum class ByteUnitStandard {
    BINARY_MIB,   // 1 MB = 1024 * 1024 bytes (1,048,576 B)
    DECIMAL_MB    // 1 MB = 1000 * 1000 bytes (1,000,000 B)
}

/**
 * Google Photos 표시 용량과 로컬 실제 바이트 크기 간의 호환성 검증 정책 인터페이스.
 * (MiB/MB 표준 및 반올림 오차 정책을 교체 가능하도록 추상화)
 */
interface SizeCompatibilityPolicy {
    fun isCompatible(localSizeBytes: Long, displayedSizeText: String?): Boolean
    fun getCompatibleByteRange(displayedSizeText: String?): LongRange?
}

/**
 * 표준 반올림 기반 용량 호환성 정책 구현체.
 */
class StandardSizeCompatibilityPolicy(
    val standard: ByteUnitStandard = ByteUnitStandard.BINARY_MIB,
    val extraSafetyMarginRatio: Double = 0.01
) : SizeCompatibilityPolicy {

    override fun getCompatibleByteRange(displayedSizeText: String?): LongRange? {
        if (displayedSizeText == null) return null
        val regex = Regex("""([0-9]+(?:\.[0-9]+)?)\s*(MB|KB|GB)""", RegexOption.IGNORE_CASE)
        val match = regex.find(displayedSizeText) ?: return null

        val valueStr = match.groupValues[1]
        val unit = match.groupValues[2].uppercase(Locale.US)
        val nominal = valueStr.toDoubleOrNull() ?: return null

        val decimalPlaces = if (valueStr.contains('.')) valueStr.substringAfter('.').length else 0
        // 반올림 허용오차 (예: 소수 1자리면 ±0.05)
        val halfUnit = 0.5 * (10.0.pow(-decimalPlaces))

        val multiplier = when (unit) {
            "KB" -> if (standard == ByteUnitStandard.BINARY_MIB) 1024.0 else 1000.0
            "MB" -> if (standard == ByteUnitStandard.BINARY_MIB) 1024.0 * 1024.0 else 1000.0 * 1000.0
            "GB" -> if (standard == ByteUnitStandard.BINARY_MIB) 1024.0 * 1024.0 * 1024.0 else 1000.0 * 1000.0 * 1000.0
            else -> if (standard == ByteUnitStandard.BINARY_MIB) 1024.0 * 1024.0 else 1000.0 * 1000.0
        }

        val minBytes = ((nominal - halfUnit - extraSafetyMarginRatio).coerceAtLeast(0.0) * multiplier).roundToLong()
        val maxBytes = ((nominal + halfUnit + extraSafetyMarginRatio) * multiplier).roundToLong()
        return minBytes..maxBytes
    }

    override fun isCompatible(localSizeBytes: Long, displayedSizeText: String?): Boolean {
        val range = getCompatibleByteRange(displayedSizeText) ?: return false
        return localSizeBytes in range
    }
}
