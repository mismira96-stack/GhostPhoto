package com.ghostphoto.app.matcher

/**
 * 기기 내 미디어의 수명주기 및 감지 상태.
 * 확장 가능한 상태 머신으로 설계.
 * (USER_DELETED_FROM_APP은 미래 호환성을 위한 정의이며, 현재 프로덕션 플로우에서는 생성하지 않음)
 */
enum class MediaLifecycleState {
    ACTIVE,                     // 기기 MediaStore에 현재 정상 존재
    MISSING_FROM_LOCAL_SCAN,    // 이전 스냅샷에는 있었으나 현재 스캔에서 사라짐 (Ghost 후보)
    USER_DELETED_FROM_APP       // (미래 호환용) 앱 내 명시적 동작 또는 수동 정리 완료
}

/**
 * 기기 로컬의 MediaStore 또는 이전 스냅샷에 기록된 미디어 레코드.
 */
data class LocalMediaRecord(
    val id: Long,
    val displayName: String,
    val takenAtMillis: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val mimeType: String = "image/jpeg",
    val relativePath: String? = null,
    val state: MediaLifecycleState = MediaLifecycleState.ACTIVE,
    val firstSeenAtMillis: Long = System.currentTimeMillis(),
    val lastSeenAtMillis: Long = System.currentTimeMillis(),
    val missingDetectedAtMillis: Long? = null
)

/**
 * Google Photos UI(그리드 및 Info Sheet)에서 관측/추출된 클라우드 후보 메타데이터.
 */
data class CloudCandidate(
    val candidateId: String,
    val dateStr: String? = null,              // 예: "2026-08-29" 또는 "2026. 8. 29."
    val timeMinuteStr: String? = null,        // 예: "19:01" 또는 "오후 7:01"
    val contentDesc: String? = null,          // 예: "사진 - 2026. 8. 29. 오후 7:01"
    val filename: String? = null,             // 예: "adv_live_timeline.png"
    val width: Int? = null,                   // 예: 1248
    val height: Int? = null,                  // 예: 1972
    val displayedSizeText: String? = null,    // 예: "백업됨 • 2.8MB" 또는 "2.8MB"
    val device: String? = null,
    val location: String? = null,
    val isPhoto: Boolean = true
)

/**
 * 매칭 결과 판정 열거형.
 * - CONFIDENT_MATCH: 핵심 3요소(파일명, 해상도, 호환 용량) 완전 일치 + 단독 후보 (안전 선택 가능)
 * - AMBIGUOUS: 증거 불충분 또는 복수 Plausible 후보 경쟁 (절대 선택 금지, 스킵)
 * - NOT_FOUND: Full Traversal 완료 후 기준 충족 후보 전무
 */
enum class MatchVerdict {
    CONFIDENT_MATCH,
    AMBIGUOUS,
    NOT_FOUND
}

/**
 * 각 후보에 대한 술어 검증 결과.
 */
data class CandidateEvidence(
    val candidate: CloudCandidate,
    val dateMatches: Boolean,
    val filenameExactMatch: Boolean,
    val resolutionExactMatch: Boolean,
    val sizeRoundingCompatible: Boolean,
    val auxiliaryScore: Double,
    val notes: List<String>
) {
    /**
     * 핵심 증거를 모두 충족하여 타겟과 일치할 가능성이 있는 그럴듯한(Plausible) 후보인가?
     */
    val isPlausible: Boolean
        get() = dateMatches && filenameExactMatch && resolutionExactMatch && sizeRoundingCompatible
}

/**
 * 최종 매칭 평가 결과.
 */
data class MatchResult(
    val localRecord: LocalMediaRecord,
    val verdict: MatchVerdict,
    val matchedCandidate: CloudCandidate? = null,
    val auxiliaryScore: Double = 0.0,
    val matchReasons: List<String> = emptyList(),
    val candidateEvidences: List<CandidateEvidence> = emptyList()
)
