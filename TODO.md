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

### P0 (Critical - Persistence Integrity)
- [ ] Snapshot persistence가 실제 앱 재시작(cold restart) 후에도 유지되는지 확인
  - 현재 `SharedPrefsSnapshotStorage` 구현의 실제 기기/에뮬레이터 동작 검증
  - 필요 시 Room DB 등 견고한 로컬 영속 스토리지로 마이그레이션

### P1 (Interface & Size Policy)
- [ ] Google Photos `CloudCandidate` 수집기(UIAutomator/Accessibility Collector)와 `MatchEngine` 사이 Adapter/Interface 설계 및 연동
- [ ] 실제 Google Photos의 표시 크기(MB vs MiB, 반올림 표기 규칙) 정책 실기기 정밀 검증 및 기본 Policy 확정

### P2 (Real-Cloud E2E Verification)
- [ ] 보존된 28개 Real-Cloud Ground-Truth Fixture를 이용한 실제 E2E 탐색 및 매칭 실행
- [ ] `CONFIDENT_MATCH` / `AMBIGUOUS` / `NOT_FOUND` / `WRONG_MATCH` 지표 실측 (`WRONG_MATCH = 0` 달성 검증)

### Future Optional Integration
- [ ] (Optional) PhotoPlace 로컬 미디어 소실 히스토리(Disappearance History) 최소 스키마 연동 검토 (Core Roadmap 외)
- [ ] 대량 candidate 처리 성능 측정 및 최적화
- [ ] Google Photos UI 변경 및 다국어(Locale) 대응

---

## 🚫 지금 하지 않을 것 (Explicitly Out of Scope)
- Google Photos 삭제 / 휴지통 / 영구 삭제 자동화 (절대 수행하지 않음)
- Google Photos 전체 라이브러리 API 접근을 전제로 한 비현실적 설계
- AI 기반 trash 자동 판정
- 불필요한 대시보드 확장 및 복잡한 UI 기능 구현
