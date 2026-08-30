# FindGhostPhoto (Ghost Photo) — Handoff Document

## 1. Project Context & Current State
- **Project Name**: `FindGhostPhoto` (독립 킬러 프로덕트)
- **Project Goal**: 기기 로컬에서는 삭제되었지만 Google Photos 클라우드에 잔존하는 유령 사진을 탐색 및 안전 선택하여 사용자 확인 후 정리를 돕는 서비스.
- **Physical Test Device**: `SM-F971N` (Galaxy Z Fold, serial: `R5KL503VHQR`)
- **Key PoC Test**: `SelectTenTest.kt` (Crown Test PASS: 18.126s)
- **Feasibility Verdict**: `Promising / technically plausible` (삭제 자동화 제외, False Positive = 0)

---

## 2. Preserved Real-Cloud E2E Fixture

이번 실기기 실험에서 생성된 테스트 미디어는 Google Photos에 실제 백업된 뒤 기기 로컬 원본이 삭제된 상태다.  
따라서 현재 이 미디어들은 FindGhostPhoto가 찾고자 하는 실제 조건:

```text
LOCAL = deleted
GOOGLE PHOTOS = still exists
```

을 만족하는 **Real-Cloud Ghost Photo Ground-Truth Fixture (28개)**로 보존한다.

### Ground Truth (28 Items)
- `screen.png`, `app-screen.png`, `app-scroll.png`, `dump-screen.png`, `pass-screen.png`
- `photos-screen.png`, `photos-selected.png`, `photo-detail.png`, `info-sheet.png`, `real-info.png`, `exif-info.png`
- `test1-screen.png`, `test1_timeline.png`, `test2_detail.png`, `test3_search_tab.png`, `test3_search_result.png`
- `t1_timeline.png`, `t2_detail_info.png`, `t3_search_result.png`, `t4_main_timeline.png`, `t5_selection_check.png`
- `t3_search_20260828.png`, `t4_page1.png`, `t4_page2.png`
- `adv_timeline.png`, `adv_live_timeline.png`, `photo_details_dump.png`, `cleanup_selected_state.png`

---

## 3. Key Architecture & Traversal Findings

1. **Grid-Level Matching 폐기**:
   - Google Photos 메인/검색 그리드는 분 단위까지만 `content-desc`에 노출하므로, 연사 또는 부재 중 임포스터가 있을 경우 오선택 위험 존재.
   - 따라서 `unique minute == exact identity` 규칙은 공식 폐기됨.
2. **Info/Details Metadata 결합**:
   - Google Photos 뷰어에서 위로 스와이프하여 열리는 Info Sheet에는 `filename`, `width x height`, `size`, `device`, `location`이 노출됨.
   - 이를 로컬 MediaStore/PhotoPlace 메타데이터와 다차원 대조하여 고유성이 입증된 경우에만 `CONFIDENT_MATCH`로 처리.
3. **Date Search Traversal Completeness (2026-08-30 검증)**:
   - `2026-08-29` 날짜 검색 화면에서 3회 스크롤 + 바닥 확인을 통해 **63개 고유 후보 전수 순회 완료 (`PASS`)**.
   - 뷰포트 오버랩 7개 타일 중복 억제(Deduplication) 확인.
   - **`NOT_FOUND` 상태 정의**: Full Traversal + End Detection(바닥 도달)이 완료된 이후에만 판정 가능.
   - *주의: 본 결과는 이번 날짜/기기/UI 조건에서 관측 가능한 결과를 끝까지 traversal한 것이며, 1800ms settle delay 역시 이번 테스트에서 안정적으로 동작한 관측값임.*

---

## 4. Next Steps for Next Developer
1. `WORKLOG.md`, `FEASIBILITY_REPORT.md` 및 `TODO.md` 참고.
2. **NEXT Sprint**: 28개 Real-Cloud Ground-Truth fixture를 이용한 E2E matching 검증 수행 (`WRONG_MATCH = 0` acceptance criterion).
