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

### Ground Truth
- 이번 cleanup에서 로컬 삭제된 테스트 미디어: **28개**
- 파일명 목록:
  1. `screen.png`
  2. `app-screen.png`
  3. `app-scroll.png`
  4. `dump-screen.png`
  5. `pass-screen.png`
  6. `photos-screen.png`
  7. `photos-selected.png`
  8. `photo-detail.png`
  9. `info-sheet.png`
  10. `real-info.png`
  11. `exif-info.png`
  12. `test1-screen.png`
  13. `test1_timeline.png`
  14. `test2_detail.png`
  15. `test3_search_tab.png`
  16. `test3_search_result.png`
  17. `t1_timeline.png`
  18. `t2_detail_info.png`
  19. `t3_search_result.png`
  20. `t4_main_timeline.png`
  21. `t5_selection_check.png`
  22. `t3_search_20260828.png`
  23. `t4_page1.png`
  24. `t4_page2.png`
  25. `adv_timeline.png`
  26. `adv_live_timeline.png`
  27. `photo_details_dump.png`
  28. `cleanup_selected_state.png`
- Google Photos에는 백업된 상태 유지.
- 현재 cloud copy는 삭제하지 않으며, 사용자가 명시적으로 cleanup을 결정하기 전까지 테스트 fixture로 유지한다.

### Future E2E Test
향후 identity matching + candidate traversal이 구현된 뒤 이 fixture를 이용해 실제 end-to-end 검증을 수행한다.

**검증 흐름:**
```text
PhotoPlace/local history
  └──► local deletion candidate
        └──► Google Photos date search
              └──► candidate traversal
                    └──► Info metadata verification
                          └──► CONFIDENT_MATCH / AMBIGUOUS / NOT_FOUND
                                └──► CONFIDENT_MATCH만 selection
                                      └──► selection count verification
                                            └──► user manual delete
```

### Metrics & Acceptance Criteria
반드시 다음 지표를 기록한다:
- Ground truth cloud items: **28**
- `CONFIDENT_MATCH` count
- `AMBIGUOUS` count
- `NOT_FOUND` count
- `WRONG_MATCH` count
- `Recall` = correctly found / 28
- `False Positive` count

> **가장 중요한 acceptance criterion**:  
> `WRONG_MATCH = 0`  
> (Recall이 다소 낮은 것은 허용하나, 확신할 수 없는 항목은 절대 선택하지 않는다.)

### Important Rules
- 이 28개 fixture를 새로운 테스트를 위해 복제하거나 추가 테스트 미디어를 생성하지 않는다.
- Google Photos에서 자동 삭제하지 않는다. 최종 cleanup은 사용자가 직접 수행한다.
- 현재는 TODO/HANDOFF 기록만 하고 E2E 테스트는 아직 실행하지 않는다.

---

## 📌 Priority Tasks (우선순위 로드맵)

### P0 (Critical - Identity Matching Rules)
- [ ] 기존 collision dump에서 Info Sheet의 `filename` / `width` / `height` 노출 및 추출 일관성 확인
- [ ] 다차원 메타데이터 기반 Matching Identity Rule & Score 상세 정의

### P1 (Discovery & Traversal)
- [ ] 날짜 검색 결과 candidate 전체 traversal(스크롤 순회) 안정성 검증
- [ ] 마지막 candidate 도달 판정 및 `NOT_FOUND` 확정 조건 정의
- [ ] 화면 스크롤 시 중복 처리 방지(Duplicate Traversal Prevention) 메커니즘 설계

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
