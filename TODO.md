# Ghost Photo — Project TODO & Roadmap

> **Current Status**: `Promising / technically plausible`  
> **Key Metric**: `WRONG_MATCH = 0` (Zero False Positives)

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
- [ ] Ghost Photo - PhotoPlace 간 Handoff 데이터 포맷 정의

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
