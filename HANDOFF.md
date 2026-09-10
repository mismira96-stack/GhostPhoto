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
   - 단위 테스트 13/13 PASS (`GhostMatcherTest`, `GhostScanRepositoryTest`, `SafePipelineTest`, `GooglePhotosGridSelectorTest`).
   - `assembleDebug` BUILD SUCCESSFUL (`app-debug.apk` 정상 생성).
   - 실기기 SM-F971N E2E 벤치마크 4회 완료 (Zero-Delete 100% 준수).

---

## 4. Native Accessibility Service State & Critical Findings (2026-09-10)

1. **Native E2E 파이프라인 구현 완료 (`com.ghostphoto.app.accessibility`)**:
   - `GhostAccessibilityService`, `SafePipelineEngine`, `GooglePhotosGridSelector`: 무ADB 온디바이스 자동화 파이프라인 구축 완료.
   - `Samsung Freecess 방지`: `Foreground Service` (Notification FGS) 연동.
   - `Candidate Timestamp Consistency Guard`: Details 파일명과 그리드 시간의 오차를 검증하여 불일치 시 `AMBIGUOUS`로 안전 격리.

2. **최근 벤치마크 Incident & 근본 원인**:
   - **대용량 동영상 선택**: `LocalMediaScanner`가 동영상(`video/mp4`)도 스캔하여 타겟 DB에 진입함. -> `mimeType.startsWith("image/")` 필터 필요.
   - **개인 사진 오선택**: 날짜 뷰(`2026-09-05`)에서 확인한 Minute/Index를 월간 뷰(`2026-09`)에서 재사용하면서 Grid Drift 및 동일 분(14:00) 중복 미디어 충돌 발생.

3. **1-Pass Verify-and-Select Spike 결과 (절대 UI 제약 증명)**:
   - Google Photos는 ActionMode(다중 선택) 중 Details 진입이 불가능하며, ActionMode를 해제하면 선택이 0으로 리셋됨.
   - 따라서 **"1-Pass로 선택을 유지하며 다음 후보를 계속 검증하는 구조"는 클라이언트 제약상 불가능함**.

4. **Next Agent / Developer Action Items**:
   - **[P0-1] Image-Only Filter**: `GhostAccessibilityService.startSafePipeline`에 `missing.filter { it.mimeType.startsWith("image/") }` 적용.
   - **[P0-2] In-Place (Same-View) Date-Session 2-Pass 구현**:
     - 월간 뷰로 이동하지 않고, **동일 날짜 검색 화면 내에서** Pass 1(검증)과 Pass 2(선택)를 모두 완료하는 구조로 전환.
     - 날짜별 단위 일괄 선택 후 사용자 확인/다음 날짜 처리.
   - **[P0-3] Zero-Delete 유지**: 휴지통/삭제 버튼은 절대 누르지 않고, 선택 완료 화면에서 정지하여 사용자의 검수를 받는다.
