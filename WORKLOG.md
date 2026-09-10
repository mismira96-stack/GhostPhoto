# FindGhostPhoto — Work Log

## 📅 2026-09-10: 1-Pass Verify-and-Select Spike, UI Constraint Proof, & Target Filtering

### 1. 작업 개요
- **배경**: 실기기 벤치마크 중 발생한 비디오 선택 및 개인 사진 오선택 현상에 대한 사용자 제기 의문 검증:
  1) 왜 동영상이 타겟으로 잡히고 선택되는가?
  2) 1-Pass "확인 즉시 선택(Verify-and-Select)" 방식으로 다중 선택을 유지하며 다음 후보를 계속 검증하는 것이 Google Photos UI 구조상 가능한가?
- **방식**: SM-F971N 실기기 단계별 Spike 실행 및 UI 계층(XML) 덤프 분석.

### 2. 주요 발견 및 증명 (Key Findings)
1. **동영상 선택 원인 (타겟 DB 및 필터링 부재)**:
   - `LocalMediaScanner`가 `MediaStore.Images`와 `MediaStore.Video`를 모두 스캔하여 Snapshot을 생성함.
   - 사용자가 로컬에서 삭제했던 대용량 카메라 영상(`20260906_173122.mp4` 6.7GB, `20260906_165539.mp4` 7.7GB)이 `MISSING_FROM_LOCAL_SCAN` 타겟으로 등록됨.
   - `startSafePipeline`에서 `mimeType` 필터링이 없어 타겟 4번/5번으로 지정되었고, 구글포토에서 실제로 해당 영상 파일명을 확인하여 정상 매칭으로 판정 후 선택함.
   - **해결책**: 타겟 로딩 시 `it.mimeType.startsWith("image/")` 필터 1줄 추가로 원천 차단 가능. 비디오 재생으로 인한 CPU 과열 및 UI 왜곡 제거 효과 확인.
2. **Google Photos 1-Pass 다중 선택의 절대적 UI 제약 (Spike 결과)**:
   - **제약 ① ActionMode 중 Details 진입 불가**: 사진 1을 확인하고 롱프레스로 선택 모드(`action_bar_title` = "1")를 띄운 뒤, 다음 후보(사진 2)를 탭하면 **뷰어나 Details가 열리지 않고 사진 2가 Details 확인 없이 맹목적으로 선택(2)**됨.
   - **제약 ② 선택 모드 해제 시 기존 선택 100% 증발**: 다음 사진의 Details를 보기 위해 Back 키로 빠져나오는 즉시 **이전 선택 상태가 0으로 리셋**됨 (`spike_exit_action.xml` 확인).
   - **제약 ③ ActionMode 메뉴 부재**: ActionMode 오버플로우 메뉴에는 상세정보(Details) 항목이 없음.
   - **결론**: "선택 상태를 누적 유지하면서 다음 사진의 Details를 계속 검증하는 1-Pass 구조"는 Google Photos 클라이언트 구조상 **100% 불가능**.

### 3. 차기 아키텍처 결론 (Next Architecture)
- **"동일 화면 내(In-Place) 날짜별 2-Pass" 채택**:
  - 기존 실패 원인은 2-Pass 자체가 아니라, 날짜 뷰(`2026-09-06`)에서 확인하고 월 전체 뷰(`2026-09`)로 **화면을 완전히 전환**했기 때문에 그리드가 틀어진 것임.
  - 화면을 전환하지 않고 **동일한 날짜 검색 화면 내에서** Pass 1(검증) + Pass 2(선택)를 완료하면 그리드 순서가 보존되어 오선택이 0%로 차단됨.
  - 날짜별 단위로 일괄 선택 및 확인을 진행하는 방식으로 설계 확정.

---

## 📅 2026-09-09 ~ 2026-09-10: Native GhostAccessibilityService E2E Benchmark

### 1. 작업 개요
- **목적**: Python/ADB 스크립트에 의존하던 구글포토 자동화를 Android Native `GhostAccessibilityService` 기반의 온디바이스 독립 서비스로 전환 및 실기기 E2E 벤치마크 검증.
- **테스트 환경**: Samsung Galaxy Z Fold (SM-F971N, Android 12, 시리얼 `R5KL503VHQR`), 2026-09 누락 타겟 Top 20건.
- **원칙**: `Zero-Delete` 절대 보장 (휴지통/삭제 기능 일체 미호출, 선택 화면에서 정지).

### 2. 주요 구현 내용
1. **`GhostAccessibilityService` Native E2E 파이프라인**:
   - `SafePipelineEngine`: Pass 1 날짜별 Grid Minute Prefilter + Details 검증 -> Pass 2 안전 다중 선택.
   - `GooglePhotosGridSelector`: Grid accessibility 노드 파싱, MinuteKey 및 SiblingIndex 바인딩.
   - `Samsung Freecess 방지`: `Foreground Service` (Notification FGS) 연동으로 장시간 벤치마크 중 프로세스 동결 방지.
2. **False Binding 방지 안전 가드 구현**:
   - `Dummy Click Success 방지`: `ACTION_CLICK` 반환값 맹신 금지, 실제 Viewer 오픈 여부 Entry 검증 및 실패 시 retry tap 수행.
   - `Candidate Timestamp Consistency Guard (`isTimestampConsistentWithCandidate`)`: Details 파일명에서 추출한 timestamp와 클릭한 Grid 타일의 시간 차이가 180초 이상 나면 Stale 데이터로 판정하여 즉시 `AMBIGUOUS`로 거부.
   - `Exit Guard & Settle Delay`: Details 닫힘 및 그리드 렌더링 안정화 대기 추가.

### 3. 벤치마크 실측 결과 (Run #4)
- **총 E2E 소요시간**: `124.42s` (Grid scan: `1.28s`, Details verification: `70.39s`, Pass 2 selection: `5.44s`)
- **검증 결과**: CONFIDENT 9건, AMBIGUOUS 15건 (타임스탬프 불일치 11건 사전 거부 성공).
- **최종 선택 화면**: 9건 선택됨, Zero-Delete 100% 준수.
- **오류 분석**:
  - `20260906_173122.mp4` 동영상 선택 (타겟 DB에 미디어 타입 구분 없이 포함됨).
  - 2026-09-05 14:00에 개인 아기 앨범 사진 오선택 발생 (날짜 뷰와 월 뷰 간 그리드 불일치 및 동일 분 중복 미디어 충돌).

---

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
