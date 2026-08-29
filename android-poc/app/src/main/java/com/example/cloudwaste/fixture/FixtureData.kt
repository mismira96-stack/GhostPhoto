package com.example.cloudwaste.fixture

data class MediaTile(
    val id: String,
    val label: String,
    val kind: Kind,
    val color: Int,
    var selected: Boolean = false,
) {
    enum class Kind { CANDIDATE, DECOY }
}

object FixtureData {
    /** Same crown set as PC fixtures/candidates.json — order interleaved with decoys. */
    val tiles: List<MediaTile> = listOf(
        MediaTile("media-d01", "장식용 풍경", MediaTile.Kind.DECOY, 0xFF94A3B8.toInt()),
        MediaTile("media-c01", "해변 석양", MediaTile.Kind.CANDIDATE, 0xFFD97706.toInt()),
        MediaTile("media-d02", "회의 화이트보드", MediaTile.Kind.DECOY, 0xFF64748B.toInt()),
        MediaTile("media-c02", "고양이", MediaTile.Kind.CANDIDATE, 0xFFE11D48.toInt()),
        MediaTile("media-d03", "영수증 스캔", MediaTile.Kind.DECOY, 0xFF92400E.toInt()),
        MediaTile("media-d04", "스크린샷_01", MediaTile.Kind.DECOY, 0xFF64748B.toInt()),
        MediaTile("media-c03", "산 정상", MediaTile.Kind.CANDIDATE, 0xFF0284C7.toInt()),
        MediaTile("media-d05", "앱 아이콘 모음", MediaTile.Kind.DECOY, 0xFF4338CA.toInt()),
        MediaTile("media-c04", "커피잔", MediaTile.Kind.CANDIDATE, 0xFF65A30D.toInt()),
        MediaTile("media-d06", "문서 표지", MediaTile.Kind.DECOY, 0xFF0F766E.toInt()),
        MediaTile("media-d07", "밈_캡처", MediaTile.Kind.DECOY, 0xFF0891B2.toInt()),
        MediaTile("media-c05", "야경", MediaTile.Kind.CANDIDATE, 0xFF7C3AED.toInt()),
        MediaTile("media-d08", "QR 코드", MediaTile.Kind.DECOY, 0xFFEA580C.toInt()),
        MediaTile("media-c06", "꽃밭", MediaTile.Kind.CANDIDATE, 0xFFDB2777.toInt()),
        MediaTile("media-d09", "지도 스냅", MediaTile.Kind.DECOY, 0xFF16A34A.toInt()),
        MediaTile("media-d10", "UI 목업", MediaTile.Kind.DECOY, 0xFF2563EB.toInt()),
        MediaTile("media-c07", "도서관", MediaTile.Kind.CANDIDATE, 0xFF0F766E.toInt()),
        MediaTile("media-c08", "자전거", MediaTile.Kind.CANDIDATE, 0xFFEA580C.toInt()),
        MediaTile("media-d11", "메모 사진", MediaTile.Kind.DECOY, 0xFF64748B.toInt()),
        MediaTile("media-c09", "눈 내린 거리", MediaTile.Kind.CANDIDATE, 0xFF0284C7.toInt()),
        MediaTile("media-d12", "광고 배너", MediaTile.Kind.DECOY, 0xFFE11D48.toInt()),
        MediaTile("media-c10", "강아지", MediaTile.Kind.CANDIDATE, 0xFFD97706.toInt()),
    )

    val candidateIds: List<String> = tiles.filter { it.kind == MediaTile.Kind.CANDIDATE }.map { it.id }
    val decoyIds: List<String> = tiles.filter { it.kind == MediaTile.Kind.DECOY }.map { it.id }
}
