# FindGhostPhoto — Work Log

## 📅 2026-08-30: Date Search Viewport Traversal Completeness Verification

### 1. 작업 개요
- **목적**: Google Photos 날짜 검색(`2026-08-29`) 결과 화면에서 스크롤을 통한 전체 후보군 순회(Traversal Completeness) 및 바닥 도달 감지(End Detection) 가능성 검증.
- **모드**: 100% Read-Only Navigation (선택/삭제/편집/추가 미디어 생성 일절 없음).
- **테스트 환경**: `SM-F971N` (Galaxy Z Fold, Android 실기기).

### 2. 관측 및 검증 결과
- **Traversal 판정**: `PASS` (이번 테스트 조건 기준)
- **Observed Unique Candidates**: **63개** (해당 일자의 상단 스크린캡처 fixture 픽스처군 포함 오전 9:42 ~ 오후 11:59 관측 노드 전수 수집)
- **Duplicates Suppressed**: **7개** (뷰포트 간 스크롤 오버랩으로 중복 노출된 타일 정확히 억제)
- **Scroll Count**: **3회 스크롤 + 1회 바닥 확인**
- **End Detection Method**: **`Viewport Signature Stability`** (스크롤 후 뷰포트 노드 시그니처가 직전 스텝과 동일함을 감지하여 안전 종료)
- **Settle Delay**: **`1800ms`** (이번 테스트 환경에서 스크롤 후 UIAutomator 덤프 시 노드 렌더링 안정성을 확보한 관측값)

### 3. 상태 정의 갱신
- **`NOT_FOUND`**: 단순 뷰포트 미노출이 아니라, **Full Traversal + End Detection이 완료된 이후에만 판정 가능**.

### 4. 주의사항 및 한계
- 본 결과는 "Google Photos 전체를 어떤 환경에서든 100% 탐색 가능"하다는 일반화가 아니며, **이번 날짜/기기/UI 조건에서 관측 가능한 결과를 끝까지 traversal할 수 있음을 확인**한 것임.

---

## 📅 2026-08-29 ~ 2026-08-30: Initial Feasibility & Ground-Truth Fixture

- Google Photos 실기기 접근성 트리 탐색 및 날짜 검색 점프 검증.
- Multi-select 모드 및 `checked` 상태 검증.
- 단순 분 단위 일치(`unique minute == exact identity`) 및 로컬 1:1 가정 폐기.
- Info/Details 시트의 다차원 메타데이터(`filename`, `width x height`, `size`) 확인.
- Real-Cloud Ghost Photo Ground-Truth Fixture 28개 보존 및 로컬 원본 정리.
