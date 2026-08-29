# Ghost Photo 👻

> **"기기에서는 삭제되었지만 Google Photos 클라우드에 남아 있는 사진을 안전하게 찾아 정리하도록 돕는 도구"**

---

## 🎯 프로젝트 핵심 원칙
1. **FALSE POSITIVE = 0 (오선택 0건 절대 보장)**
   - 잘못된 사진을 선택하는 것은 영구적인 데이터 손실 위험을 초래하므로, 조금이라도 모호하면 `AMBIGUOUS`로 분류하여 건드리지 않습니다.
2. **삭제는 100% 사용자 책임**
   - Ghost Photo는 클라우드 사진을 **발견 → 검증 → 다중 선택**까지만 수행하며, 실제 삭제/휴지통 이동은 사용자가 직접 눈으로 확인하고 수행합니다.

---

## 📊 현재 검증 현황 (Feasibility Status)
- **판정**: `Promising / technically plausible`
- **검증 완료 항목**:
  - Google Photos 날짜(`YYYY-MM-DD`) 검색 기반 후보 필터링
  - Multi-select 모드 진입 및 `checked` 상태 검증
  - Info/Details 시트의 다차원 접근성 메타데이터(`filename`, `width x height`, `size`) 추출
  - 동일 분 연사 충돌 시 단순 분단위 매칭 폐기 및 다차원 대조를 통한 False Positive 방지
- **상세 보고서**: [FEASIBILITY_REPORT.md](FEASIBILITY_REPORT.md)
- **작업 계획**: [TODO.md](TODO.md)

---

## 📂 저장소 구조
```text
GhostPhoto/
├── android-poc/        # UIAutomator 기반 실기기 테스트 및 PoC 코드
├── experiment/         # Feasibility & Adversarial 테스트 스크립트 및 덤프
├── FEASIBILITY_REPORT.md # 실기기 검증 결과 및 아키텍처 보고서
├── TODO.md             # 프로젝트 우선순위 로드맵
├── HANDOFF.md          # 인수인계 및 개발 컨텍스트 문서
└── README.md
```
