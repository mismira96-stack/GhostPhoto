# Ghost Photo — Handoff Document

## 1. Project Context & Current State
- **Project Goal**: PhotoPlace의 로컬 삭제 이력을 바탕으로 Google Photos 클라우드에 잔존하는 고스트 사진을 탐색 및 안전 선택하여 사용자 확인 후 정리를 돕는 서비스.
- **Physical Test Device**: `SM-F971N` (Galaxy Z Fold, serial: `R5KL503VHQR`)
- **Key PoC Test**: `SelectTenTest.kt` (Crown Test PASS: 18.126s)
- **Feasibility Verdict**: `Promising / technically plausible` (삭제 자동화 제외, False Positive = 0)

## 2. Key Architecture Findings
1. **Grid-Level Matching 폐기**:
   - Google Photos 메인/검색 그리드는 분 단위까지만 `content-desc`에 노출하므로, 연사 또는 부재 중 임포스터가 있을 경우 오선택 위험 존재.
   - 따라서 `unique minute == exact identity` 규칙은 공식 폐기됨.
2. **Info/Details Metadata 결합**:
   - Google Photos 뷰어에서 위로 스와이프하여 열리는 Info Sheet에는 `filename`, `width x height`, `size`, `device`, `location`이 노출됨.
   - 이를 로컬 MediaStore/PhotoPlace 메타데이터와 다차원 대조하여 고유성이 입증된 경우에만 `CONFIDENT_MATCH`로 처리.
3. **Safety First**:
   - 모호한 경우 `AMBIGUOUS`로 남기며, 자동 삭제는 구현하지 않음.

## 3. Next Steps for Next Developer
1. `FEASIBILITY_REPORT.md` 및 `TODO.md` 참고.
2. P0 작업: 기존 dump 기반 `filename`/`resolution` 추출 안정성 분석 및 매칭 룰 정립.
3. P1 작업: 날짜 검색 결과의 전체 스크롤 순회(Candidate traversal completeness) 구현.
