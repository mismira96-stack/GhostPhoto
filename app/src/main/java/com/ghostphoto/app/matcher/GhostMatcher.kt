package com.ghostphoto.app.matcher

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * GhostPhoto 매칭 엔진.
 * Product Safety Acceptance Criterion: WRONG_MATCH = 0 (오선택 0건 절대 보장)
 *
 * 단순 점수 threshold 중심이 아닌, 명확한 술어(Predicate) 기반의 증거 검증:
 * 1. date/time: Candidate Locator / Search Gate
 * 2. filename: 정규화 파일명 완전 일치 (Exact match)
 * 3. resolution: 해상도 완전 일치 (Exact match, 가로세로 회전 포용)
 * 4. size: 주입된 SizeCompatibilityPolicy를 통한 표시 반올림 호환 바이트 범위 대조
 * 5. Competing Candidate 부재: 유일하게 검증된 후보일 때만 CONFIDENT_MATCH
 * 6. NOT_FOUND: Full Traversal 완료 후에만 판정 가능 (미완료 시 AMBIGUOUS 유지)
 */
class GhostMatcher(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val sizePolicy: SizeCompatibilityPolicy = StandardSizeCompatibilityPolicy()
) {

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault()).withZone(zoneId)

    /**
     * 로컬 미디어 1건에 대해 Google Photos 후보군을 대조하여 엄격한 매칭 결과를 판정합니다.
     */
    fun match(
        local: LocalMediaRecord,
        candidates: List<CloudCandidate>,
        isFullTraversalCompleted: Boolean = true
    ): MatchResult {
        if (candidates.isEmpty()) {
            return if (isFullTraversalCompleted) {
                MatchResult(
                    localRecord = local,
                    verdict = MatchVerdict.NOT_FOUND,
                    matchReasons = listOf("해당 일자 전체 순회(Full Traversal) 완료 및 후보 0건 (NOT_FOUND)")
                )
            } else {
                MatchResult(
                    localRecord = local,
                    verdict = MatchVerdict.AMBIGUOUS,
                    matchReasons = listOf("후보가 비어 있으나 순회가 완료되지 않아 NOT_FOUND 확정 불가 (AMBIGUOUS)")
                )
            }
        }

        val localDateStr = dateFormatter.format(Instant.ofEpochMilli(local.takenAtMillis))
        val evidences = candidates.map { evaluateCandidate(local, localDateStr, it) }

        val plausibleList = evidences.filter { it.isPlausible }

        // 1. 단일 Plausible 후보 존재 (경쟁 후보 없음) -> CONFIDENT_MATCH
        if (plausibleList.size == 1) {
            val winner = plausibleList.first()
            return MatchResult(
                localRecord = local,
                verdict = MatchVerdict.CONFIDENT_MATCH,
                matchedCandidate = winner.candidate,
                auxiliaryScore = winner.auxiliaryScore,
                matchReasons = listOf(
                    "모든 핵심 술어 충족 (파일명 완전일치, 해상도 완전일치, 반올림 호환 용량)",
                    "단독 고유 후보 확인 (경쟁 후보 0건)"
                ) + winner.notes,
                candidateEvidences = evidences
            )
        }

        // 2. 복수의 Plausible 후보 경쟁 -> AMBIGUOUS (오선택 방지)
        if (plausibleList.size > 1) {
            return MatchResult(
                localRecord = local,
                verdict = MatchVerdict.AMBIGUOUS,
                matchedCandidate = null,
                auxiliaryScore = plausibleList.maxOf { it.auxiliaryScore },
                matchReasons = listOf(
                    "복수의 후보(${plausibleList.size}건)가 핵심 술어를 동시 충족하여 특정 불가",
                    "WRONG_MATCH 방지를 위해 자동 선택 배제 (AMBIGUOUS)"
                ),
                candidateEvidences = evidences
            )
        }

        // 3. Plausible 후보가 0건인 경우
        val dateMatchedCount = evidences.count { it.dateMatches }
        if (dateMatchedCount > 0) {
            val hasIncompleteEvidence = evidences.any {
                it.dateMatches && (it.candidate.filename == null || it.candidate.width == null || it.candidate.displayedSizeText == null)
            }

            if (hasIncompleteEvidence) {
                return MatchResult(
                    localRecord = local,
                    verdict = MatchVerdict.AMBIGUOUS,
                    matchedCandidate = null,
                    auxiliaryScore = evidences.maxOfOrNull { it.auxiliaryScore } ?: 0.0,
                    matchReasons = listOf(
                        "동일 일자 후보가 존재하나 세부 메타데이터(파일명/해상도/용량) 증거 불충분으로 미확정 (AMBIGUOUS)"
                    ),
                    candidateEvidences = evidences
                )
            }
        }

        return if (isFullTraversalCompleted) {
            MatchResult(
                localRecord = local,
                verdict = MatchVerdict.NOT_FOUND,
                matchedCandidate = null,
                auxiliaryScore = evidences.maxOfOrNull { it.auxiliaryScore } ?: 0.0,
                matchReasons = listOf(
                    "Full Traversal 완료: 관측된 후보군(${candidates.size}건) 중 일치 후보 전무 (NOT_FOUND)"
                ),
                candidateEvidences = evidences
            )
        } else {
            MatchResult(
                localRecord = local,
                verdict = MatchVerdict.AMBIGUOUS,
                matchedCandidate = null,
                auxiliaryScore = evidences.maxOfOrNull { it.auxiliaryScore } ?: 0.0,
                matchReasons = listOf(
                    "현재 화면에서 일치 후보가 없으나 순회가 미완료 상태 (AMBIGUOUS)"
                ),
                candidateEvidences = evidences
            )
        }
    }

    private fun evaluateCandidate(
        local: LocalMediaRecord,
        localDateStr: String,
        candidate: CloudCandidate
    ): CandidateEvidence {
        val notes = mutableListOf<String>()

        // 1. Date Gating
        val dateMatches = matchDate(localDateStr, candidate)
        if (dateMatches) {
            notes.add("날짜 일치: $localDateStr")
        } else {
            notes.add("날짜 불일치 (Local: $localDateStr)")
        }

        // 2. Filename Exact Match
        var filenameExactMatch = false
        if (!candidate.filename.isNullOrBlank()) {
            val localNorm = normalizeFilename(local.displayName)
            val cloudNorm = normalizeFilename(candidate.filename)
            filenameExactMatch = localNorm.equals(cloudNorm, ignoreCase = true)
            if (filenameExactMatch) {
                notes.add("파일명 완전 일치: ${candidate.filename}")
            } else {
                notes.add("파일명 불일치: ${local.displayName} vs ${candidate.filename}")
            }
        } else {
            notes.add("파일명 미확인 (클라우드 세부정보 미수집)")
        }

        // 3. Resolution Exact Match
        var resolutionExactMatch = false
        if (candidate.width != null && candidate.height != null && candidate.width > 0 && candidate.height > 0) {
            val exact = (local.width == candidate.width && local.height == candidate.height)
            val rotated = (local.width == candidate.height && local.height == candidate.width)
            resolutionExactMatch = exact || rotated
            if (resolutionExactMatch) {
                notes.add("해상도 완전 일치: ${candidate.width}x${candidate.height}")
            } else {
                notes.add("해상도 불일치: local=${local.width}x${local.height}, cloud=${candidate.width}x${candidate.height}")
            }
        } else {
            notes.add("해상도 미확인")
        }

        // 4. Size Rounding Compatibility (via SizeCompatibilityPolicy)
        val sizeCompatible = sizePolicy.isCompatible(local.sizeBytes, candidate.displayedSizeText)
        if (candidate.displayedSizeText != null) {
            if (sizeCompatible) {
                notes.add("용량 호환: local=${local.sizeBytes}B, cloud=${candidate.displayedSizeText}")
            } else {
                notes.add("용량 불일치: local=${local.sizeBytes}B, cloud=${candidate.displayedSizeText}")
            }
        } else {
            notes.add("용량 미확인")
        }

        var auxScore = 0.0
        if (dateMatches) auxScore += 25.0
        if (filenameExactMatch) auxScore += 35.0
        if (resolutionExactMatch) auxScore += 25.0
        if (sizeCompatible) auxScore += 15.0

        return CandidateEvidence(
            candidate = candidate,
            dateMatches = dateMatches,
            filenameExactMatch = filenameExactMatch,
            resolutionExactMatch = resolutionExactMatch,
            sizeRoundingCompatible = sizeCompatible,
            auxiliaryScore = auxScore,
            notes = notes
        )
    }

    private fun matchDate(localDateStr: String, candidate: CloudCandidate): Boolean {
        if (!candidate.dateStr.isNullOrBlank()) {
            val cleanCandidate = candidate.dateStr.replace(Regex("""[^0-9]"""), "")
            val cleanLocal = localDateStr.replace(Regex("""[^0-9]"""), "")
            if (cleanCandidate.isNotEmpty() && cleanCandidate == cleanLocal) {
                return true
            }
        }

        if (!candidate.contentDesc.isNullOrBlank()) {
            val parts = localDateStr.split("-")
            if (parts.size == 3) {
                val year = parts[0]
                val month = parts[1].toInt().toString()
                val day = parts[2].toInt().toString()
                val hasDate = candidate.contentDesc.contains(year) &&
                        (candidate.contentDesc.contains("$month.") || candidate.contentDesc.contains("${month}월")) &&
                        (candidate.contentDesc.contains("$day.") || candidate.contentDesc.contains("${day}일"))
                if (hasDate) return true
            }
        }

        return false
    }

    private fun normalizeFilename(name: String): String {
        return name.trim().replace("\\", "/").substringAfterLast('/')
    }
}
