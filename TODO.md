# Ghost Photo (FindGhostPhoto) — Project TODO & Roadmap

> **Current Status**: `Promising / technically plausible`  
> **Product Safety Acceptance Criterion**: `WRONG_MATCH = 0` (Zero False Positives)

---

## 🧪 Preserved Real-Cloud Ground Truth Fixture (28 Items)

실기기 실험에서 생성된 테스트 미디어는 Google Photos에 실제 백업된 뒤 기기 로컬 원본이 삭제된 상태로 안전하게 보존되어 있다.
```text
LOCAL = deleted
GOOGLE PHOTOS = still exists
```
- **Ground Truth 28개 픽스처**: 클라우드 삭제 금지, 선택 금지, 새 미디어 생성 금지 상태로 보존.

---

## 📌 Priority Tasks (우선순위 로드맵)

### P0 (Critical - Immediate Safety & False Binding Fix)
- [ ] **Image-Only Target Filtering**: `GhostAccessibilityService.startSafePipeline`에서 `it.mimeType.startsWith("image/")` 필터 추가 (대용량 카메라 동영상 타겟 진입 및 영상 재생 원천 차단).
- [ ] **In-Place (Same-View) Date-Session 2-Pass 전환**:
  - 기존의 "날짜 뷰(검증) -> 월 뷰(선택)" 방식 폐기 (뷰 이동 시 발생하는 Grid Drift 및 동일 분 중복 미디어 충돌 오선택 원천 방지).
  - 동일 날짜 검색 뷰(`photos.google.com/search/YYYY-MM-DD`) 내에서 Pass 1(검증)과 Pass 2(다중 선택)를 모두 완료하는 단일 세션 구조로 전환.
  - 날짜별 단위로 선택 완료 후 사용자 확인/다음 날짜 진행 구조 확립.

### P1 (Robustness & Traversal Complete)
- [x] **Native Android Accessibility Service E2E 파이프라인 구현** (`GhostAccessibilityService`, `SafePipelineEngine`, `GooglePhotosGridSelector`)
- [x] **Samsung Freecess 프로세스 동결 방지** (Notification FGS 연동 완료)
- [x] **Dummy Click Success & Candidate Timestamp Consistency Guard 구현**
- [ ] 날짜별 그리드 상단 추천 배너("관련도순") 스크롤 바이패스 일관성 강화 (2026-09-05 인덱스 밀림 해소)
- [ ] Settle Delay 및 Dynamic Bounds 계산 안정화

### P2 (Real-Cloud E2E Verification & Scaling)
- [x] SM-F971N 실기기 Top 20 벤치마크 4회 실행 및 계측 완료
- [x] 1-Pass Verify-and-Select 실기기 Spike 실행 및 Google Photos UI 제약(ActionMode 중 Details 진입 불가, ActionMode 해제 시 선택 초기화) 증명 완료
- [ ] 보존된 Ground-Truth Fixture 대상 In-Place 2-Pass 파이프라인 100% 정합성 검증 (`WRONG_MATCH = 0`, `Zero-Delete`)

### Future Optional Integration
- [ ] PhotoPlace 로컬 미디어 소실 히스토리(Disappearance History) 최소 스키마 연동 검토
- [ ] 대량 candidate 처리 성능 측정 및 최적화
- [ ] Google Photos UI 변경 및 다국어(Locale) 대응

---

## 🚫 지금 하지 않을 것 (Explicitly Out of Scope)
- Google Photos 삭제 / 휴지통 / 영구 삭제 자동화 (절대 수행하지 않음, `Zero-Delete` 유지)
- 1-Pass Verify-and-Select 강제 구현 (Google Photos 클라이언트 UI 제약으로 불가능함이 입증됨)
- Google Photos 전체 라이브러리 API 접근을 전제로 한 비현실적 설계
- AI 기반 trash 자동 판정
- 불필요한 대시보드 확장 및 복잡한 UI 기능 구현
