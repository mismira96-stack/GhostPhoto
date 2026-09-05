# FindGhostPhoto — Work Log

## 📅 2026-09-05: MatchEngine Core & Local History Layer Implementation

### 1. 작업 개요
- **목적**:
  - `WRONG_MATCH = 0` (Product Safety Acceptance Criterion)을 준수하는 엄격한 술어 기반(Predicate-based) `MatchEngine` 구현
  - 최초 스캔 Baseline 생성, 후속 스캔 Delta 감지, Recovery 및 Scan Validity Guard를 갖춘 `Local History / Data Layer` 구축
  - 최소 기능 검증용 `Minimal UI Shell` 구성 및 빌드/단위 테스트 검증
- **안전 가드 준수**:
  - 보존된 28개 Real-Cloud Ground-Truth 픽스처 일절 변경 없음 (Cloud delete/selection/automation/새 미디어 생성 금지)
  - 기존 `android-poc`는 회귀 테스트 자산으로 안전하게 보존 (당일 재실행 제외)

### 2. 주요 구현 내용
1. **MatchEngine Core (`com.ghostphoto.app.matcher`)**:
   - 단순 점수 threshold 중심 판정 완전 배제, 명확한 술어(Predicate) 기반의 다차원 증거 검증 체계 구현.
   - `date/time`: 후보군 검색 및 탐색 진입 게이트.
   - `filename`: 정규화 파일명 완전 일치 (`exact match`).
   - `resolution`: 해상도 완전 일치 (`exact match`, 회전 지원).
   - `size`: Google Photos 표시 반올림 규칙과 호환되는 바이트 범위 대조 (`SizeCompatibilityPolicy` 추상화로 `BINARY_MIB` / `DECIMAL_MB` 및 반올림 정책 교체 가능).
   - `경쟁 후보 부재`: 복수 Plausible 후보 존재 시 또는 세부 증거 불충분 시 무조건 `AMBIGUOUS`로 안전 격리.
   - `NOT_FOUND`: Full Traversal 완료 확인 후에만 판정 (미완료 시 `AMBIGUOUS` 유지).
2. **Local History & Data Layer (`com.ghostphoto.app.data`)**:
   - `MediaLifecycleState` 정의 (`ACTIVE`, `MISSING_FROM_LOCAL_SCAN`, 미래 호환용 `USER_DELETED_FROM_APP`).
   - 최초 스캔(First Scan): Baseline 스냅샷 생성 및 고스트 후보 0건 보장.
   - 후속 스캔(Subsequent Scan): 이전 스냅샷 대비 기기에서 사라진 미디어만 `MISSING_FROM_LOCAL_SCAN`으로 도출.
   - Re-appearing Recovery: 이전에 소실되었던 미디어가 다시 기기에 나타나면 `ACTIVE`로 자동 복구.
   - Scan Validity Guard: 권한 거부/쿼리 에러/null 커서 발생 시 스냅샷 델타 커밋을 즉시 중단(`Aborted`)하여 대량 허위 소실 발생 원천 차단.
3. **Minimal UI Shell (`com.ghostphoto.app.ui`)**:
   - 대시보드 통계/디자인 배제, 기능 검증을 위한 최소 버튼/상태 안내/후보 리스트 표시 화면 구축.

### 3. 검증 결과
- **단위 테스트 (`.\gradlew.bat testDebugUnitTest`)**: **11/11 PASS (100% 성공)**
  - `GhostMatcherTest` (7건): 실측 메타데이터 기반 unit fixture 대조, Binary/Decimal 정책, 복수 충돌 배제, 증거 불충분 배제, 용량 불일치 거부, 순회 미완료 보호, 순회 완료 NOT_FOUND.
    *(주의: realFixture 테스트는 실제 Google Photos E2E가 아니라, 기 수집된 실측 metadata를 활용한 단위 테스트 fixture임)*
  - `GhostScanRepositoryTest` (4건): 최초 baseline 생성, 소실 감지, 재출현 복구, 쿼리 실패 Abort.
- **APK 빌드 (`.\gradlew.bat assembleDebug`)**: **BUILD SUCCESSFUL** (`app-debug.apk` 정상 생성).

---

## 📅 2026-08-30: Date Search Viewport Traversal Completeness Verification

### 1. 작업 개요
- **목적**: Google Photos 날짜 검색(`2026-08-29`) 결과 화면에서 스크롤을 통한 전체 후보군 순회(Traversal Completeness) 및 바닥 도달 감지(End Detection) 가능성 검증.
- **모드**: 100% Read-Only Navigation (선택/삭제/편집/추가 미디어 생성 일절 없음).
- **테스트 환경**: `SM-F971N` (Galaxy Z Fold, Android 실기기).

### 2. 관측 및 검증 결과
- **Traversal 판정**: `PASS` *(단, 해당 날짜/기기/UI 조건에서의 관측 가능한 결과를 끝까지 traversal한 observed result임)*
- **Observed Unique Candidates**: **63개** (해당 일자의 상단 스크린캡처 fixture 픽스처군 포함 오전 9:42 ~ 오후 11:59 관측 노드 전수 수집)
- **Duplicates Suppressed**: **7개** (뷰포트 간 스크롤 오버랩으로 중복 노출된 타일 정확히 억제)
- **Scroll Count**: **3회 스크롤 + 1회 바닥 확인**
- **End Detection Method**: **`Viewport Signature Stability`** (스크롤 후 뷰포트 노드 시그니처가 직전 스텝과 동일함을 감지하여 안전 종료)
- **Settle Delay**: **`1800ms`** (이번 테스트 환경에서 스크롤 후 UIAutomator 덤프 시 노드 렌더링 안정성을 확보한 관측값)

### 3. 상태 정의 갱신
- **`NOT_FOUND`**: 단순 뷰포트 미노출이 아니라, **Full Traversal + End Detection이 완료된 이후에만 판정 가능**.

---

## 📅 2026-08-29 ~ 2026-08-30: Initial Feasibility & Ground-Truth Fixture

- Google Photos 실기기 접근성 트리 탐색 및 날짜 검색 점프 검증.
- Multi-select 모드 및 `checked` 상태 검증.
- 단순 분 단위 일치(`unique minute == exact identity`) 및 로컬 1:1 가정 폐기.
- Info/Details 시트의 다차원 메타데이터(`filename`, `width x height`, `size`) 확인.
- Real-Cloud Ghost Photo Ground-Truth Fixture 28개 보존 및 로컬 원본 정리.
