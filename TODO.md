# Ghost Photo (FindGhostPhoto) — Project TODO & Roadmap

> **Current Status**: `Promising / technically plausible`  
> **Key Metric**: `WRONG_MATCH = 0` (Zero False Positives)

---

## 🧪 Preserved Real-Cloud E2E Fixture

이번 실기기 실험에서 생성된 테스트 미디어는 Google Photos에 실제 백업된 뒤 기기 로컬 원본이 삭제된 상태다.  
따라서 현재 이 미디어들은 FindGhostPhoto가 찾고자 하는 실제 조건:

```text
LOCAL = deleted
GOOGLE PHOTOS = still exists
```

을 만족하는 **Real-Cloud Ghost Photo Ground-Truth Fixture**로 보존한다.

### Ground Truth (28 Items)
- `screen.png`, `app-screen.png`, `app-scroll.png`, `dump-screen.png`, `pass-screen.png`
- `photos-screen.png`, `photos-selected.png`, `photo-detail.png`, `info-sheet.png`, `real-info.png`, `exif-info.png`
- `test1-screen.png`, `test1_timeline.png`, `test2_detail.png`, `test3_search_tab.png`, `test3_search_result.png`
- `t1_timeline.png`, `t2_detail_info.png`, `t3_search_result.png`, `t4_main_timeline.png`, `t5_selection_check.png`
- `t3_search_20260828.png`, `t4_page1.png`, `t4_page2.png`
- `adv_timeline.png`, `adv_live_timeline.png`, `photo_details_dump.png`, `cleanup_selected_state.png`
- Google Photos에는 백업된 상태 유지.
- 현재 cloud copy는 삭제하지 않으며, 사용자가 명시적으로 cleanup을 결정하기 전까지 테스트 fixture로 유지한다.

---

## 🎯 NEXT: Real-Cloud 28 Fixture E2E Matching 검증
- **목표**: 28개 Ground-Truth fixture를 이용해 실제 E2E 탐색 및 매칭 수행.
- **분류**: `CONFIDENT_MATCH` / `AMBIGUOUS` / `NOT_FOUND`
- **Acceptance Criterion**: `WRONG_MATCH = 0` (Zero False Positives).
- **Rule**: 확신할 수 없는 항목은 선택하지 않으며 Recall 다소 낮음 허용.

---

## 📌 Priority Tasks (우선순위 로드맵)

### P0 (Critical - Identity Matching Rules)
- [ ] 기존 collision dump에서 Info Sheet의 `filename` / `width` / `height` 노출 및 추출 일관성 확인
- [ ] 다차원 메타데이터 기반 Matching Identity Rule & Score 상세 정의

### P1 (Discovery & Traversal - ✅ 1차 검증 완료)
- [x] 날짜 검색 결과 candidate 전체 traversal(스크롤 순회) 안정성 검증 (`2026-08-30 PASS: 63 candidates, 3 scrolls, End detected`)
- [x] 마지막 candidate 도달 판정 및 `NOT_FOUND` 확정 조건 정의 (`Full Traversal + End Detection 완료 후 판정`)
- [x] 화면 스크롤 시 중복 처리 방지(Duplicate Traversal Prevention) 메커니즘 설계 (`Overlap Deduplication 7 suppressed`)

### P2 (Edge Cases & Safety)
- [ ] Metadata collision adversarial test (동일 분, 동일 해상도, 유사 용량 케이스)
- [ ] Matching confidence score/rule 임계치 설계
- [ ] 모호한 후보(`AMBIGUOUS`) 처리를 위한 사용자 UX 설계

### P3 (PhotoPlace Integration)
- [ ] PhotoPlace 로컬 미디어 소실 히스토리(Disappearance History) 최소 스키마 검토
- [ ] FindGhostPhoto - PhotoPlace 간 Handoff 데이터 포맷 정의

### Later (Future Roadmap)
- [ ] 대량 candidate 처리 성능 측정 및 최적화
- [ ] Google Photos UI 변경 및 다국어(Locale) 대응
- [ ] UIAutomator / Accessibility 테스트 자동화 파이프라인
- [ ] 실제 엔드유저 UX 및 화면 구현

---

## 🚫 지금 하지 않을 것 (Explicitly Out of Scope)
- Google Photos 삭제 / 휴지통 / 영구 삭제 자동화 (절대 수행하지 않음)
- Google Photos 전체 라이브러리 API 접근을 전제로 한 비현실적 설계
- PhotoPlace 기존 scan/index 아키텍처 대규모 변경
- AI 기반 trash 자동 판정
- 불필요한 대시보드 확장 및 복잡한 UI 기능 구현
