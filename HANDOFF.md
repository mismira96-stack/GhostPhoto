# FindGhostPhoto (Ghost Photo) — Handoff Document

## 1. Project Context & Current State
- **Project Name**: `FindGhostPhoto` (독립 킬러 프로덕트)
- **Project Goal**: 기기 로컬에서는 삭제되었지만 Google Photos 클라우드에 잔존하는 유령 사진을 탐색 및 안전 선택하여 사용자 확인 후 정리를 돕는 서비스.
- **Product Safety Acceptance Criterion**: `WRONG_MATCH = 0` (오선택 0건 절대 보장, 삭제 자동화 제외)
- **Physical Test Device**: `SM-F971N` (Galaxy Z Fold, serial: `R5KL503VHQR`)
- **Key PoC Test**: `SelectTenTest.kt` (Crown Test PASS: 18.126s, 회귀 테스트 자산으로 안전 보존)
- **Feasibility Verdict**: `Promising / technically plausible`

---

## 2. Preserved Real-Cloud E2E Fixture
실기기 실험에서 생성된 테스트 미디어는 Google Photos에 실제 백업된 뒤 기기 로컬 원본이 삭제된 상태로 안전하게 보존되어 있다:
```text
LOCAL = deleted
GOOGLE PHOTOS = still exists
```
이 28개 픽스처는 향후 E2E 검증을 위해 클라우드 삭제, 선택, 추가 생성 없이 원본 그대로 유지한다.

---

## 3. Key Architecture & 2026-09-05 Implementation State

1. **Date Search Traversal Completeness (2026-08-30 실기기 검증 PASS)**:
   - `2026-08-29` 날짜 검색 화면에서 3회 스크롤 + 바닥 확인을 통해 **63개 고유 후보 전수 순회 완료 (`PASS`)**.
   - 뷰포트 오버랩 7개 타일 중복 억제(Deduplication) 확인.
   - **`NOT_FOUND` 상태 정의**: Full Traversal + End Detection(바닥 도달)이 완료된 이후에만 판정 가능.
   - *주의: 본 결과는 이번 날짜/기기/UI 조건에서 관측 가능한 결과를 끝까지 traversal한 observed result이며, 1800ms settle delay 역시 이번 테스트 환경에서의 안정적 관측값임.*

2. **MatchEngine Core (`com.ghostphoto.app.matcher`)**:
   - 단순 점수 임계치가 아닌 **엄격한 다차원 술어(Predicate) 기반 판정**:
     - `date/time`: 검색 진입 및 후보 게이트
     - `filename`: 정규화 파일명 완전 일치 (`exact match`)
     - `resolution`: 해상도 완전 일치 (`exact match`, 가로세로 회전 포용)
     - `size`: 표시 반올림 규칙 호환 바이트 범위 대조 (`SizeCompatibilityPolicy` 추상화로 `BINARY_MIB` / `DECIMAL_MB` 교체 가능)
     - `경쟁 후보 부재`: 복수 Plausible 후보 존재 시 또는 증거 불충분 시 무조건 `AMBIGUOUS`로 안전 격리.
     - `NOT_FOUND`: Full Traversal 완료 확인 후에만 판정.

3. **Local History & Data Layer (`com.ghostphoto.app.data`)**:
   - 확장 가능한 `MediaLifecycleState` (`ACTIVE`, `MISSING_FROM_LOCAL_SCAN`, 미래 호환용 `USER_DELETED_FROM_APP`).
   - First scan: baseline snapshot 생성 (고스트 후보 0건 생성).
   - Subsequent scan: previous snapshot vs current MediaStore 대조로 소실 미디어 도출.
   - Missing -> Active Recovery: 이전에 소실되었던 미디어가 다시 나타나면 `ACTIVE`로 자동 복구.
   - Invalid scan abort guard: 권한 거부, 쿼리 에러 시 스냅샷 델타 커밋을 중단하여 대량 허위 소실 발생 방지.

4. **Minimal UI Shell (`com.ghostphoto.app.ui`)**:
   - 기능 검증용 최소 버튼, 상태 텍스트, 후보 리스트 RecyclerView 구현.

5. **검증 현황**:
   - 단위 테스트 11/11 PASS (`GhostMatcherTest` 7건 + `GhostScanRepositoryTest` 4건).
     *(주의: realFixture 테스트는 실제 Google Photos E2E가 아니라, 기 수집된 실측 metadata를 이용한 unit-test fixture임)*
   - `assembleDebug` BUILD SUCCESSFUL (`app-debug.apk` 정상 생성).

---

## 4. Next Steps for Next Developer (TODO 요약)

- **P0**:
  - `SharedPrefsSnapshotStorage` 영속화가 실제 앱 프로세스 재시작(cold restart) 후에도 완벽히 유지되는지 실기기/에뮬레이터 상에서 확인 (필요 시 Room DB 등으로 고도화).
- **P1**:
  - Google Photos `CloudCandidate` 수집기(UIAutomator/Accessibility Collector)와 `MatchEngine` 사이의 Adapter/Interface 구축.
  - 실제 Google Photos의 표시 크기(MB vs MiB, 반올림 표기 규칙) 정책 실기기 정밀 검증.
- **P2**:
  - 보존된 28개 Real-Cloud Ground-Truth Fixture를 이용한 실제 E2E 탐색/대조 실행 및 `CONFIDENT_MATCH` / `AMBIGUOUS` / `NOT_FOUND` / `WRONG_MATCH` 지표 측정.
- **Future Optional**:
  - PhotoPlace 미디어 히스토리 연동 (Core 로드맵에서는 제외, 추후 선택적 연동으로만 고려).
