# 집 Codex 인수인계 — 잊힌 용량 / cloud-waste-map

> 회사 PC에서 **확인된 사실만** 정리. 집(iMac / 개인 기기)에서 이어서 진행용.  
> 작성 기준일: 2026-08-24  
> 저장소: `C:\work\cloud-waste-map` (utopia와 **분리된 단독 프로젝트**)

---

## 1. 한 줄 요약

**로컬 가짜 그리드에서 “지정 10장만 선택하고 멈춘다”** 는 PC(Playwright)와 Android(UIAutomator) 둘 다 **회사 PC에서 PASS**.  
실 Google 계정·실 Photos·삭제 자동화는 **아직 안 함 / 회사에서는 하지 말 것**.

---

## 2. 프로젝트 위치·이력

| 항목 | 내용 |
|------|------|
| 최종 경로 | `C:\work\cloud-waste-map` |
| 이전 위치 | `C:\work\PLM\utopia\scratch\google-photos-waste-poc` (사고로 utopia 안에 생김) |
| 조치 | 단독 프로젝트로 이동 + git init. utopia 쪽 **내용은 비움**. 빈 폴더가 잠금으로 남을 수 있음 → utopia 창 닫은 뒤 `rmdir`로 정리 가능 |
| Cursor 채팅 | `잊힌 용량 PoC` |
| 커밋 | 회사에서는 **커밋하지 않음** (요청 시에만) |
| Google 계정 | **연동하지 않음** |

캔버스(참고, Cursor 쪽):  
`C:\Users\mismira.seo\.cursor\projects\c-work-PLM-utopia\canvases\cloud-waste-map.canvas.tsx`  
→ 필요하면 새 프로젝트 Cursor workspace로 복사해도 됨.

GUI 시안(이 저장소):

- `docs/gui-mock-forgotten-storage-phone.png`
- `docs/gui-mock-forgotten-storage-desktop.png`

---

## 3. 왕관 테스트가 증명하는 것 (공통)

**질문:** 지정한 10개 미디어 ID만 찾아 선택하고, 디코이는 건드리지 않고, Delete는 누르지 않은 채 멈출 수 있는가?

| 포함 | 제외 (선 밖) |
|------|----------------|
| 후보 ID로 타일 찾기 | 용량 계산 / Takeout |
| 정확히 10개 선택 | AI 분류 |
| 디코이 미선택 검증 | 실계정 로그인 |
| Delete UI는 있어도 **미클릭** | 삭제 자동화 |
| | PLM / utopia / routines 연동 |

후보 ID (PC·Android 동일):

`media-c01` … `media-c10`  
(라벨: 해변 석양, 고양이, 산 정상, 커피잔, 야경, 꽃밭, 도서관, 자전거, 눈 내린 거리, 강아지)

디코이: `media-d01` … (그리드에 의도적으로 **끼워 넣음**. 앞에서부터 10장 고르면 실패해야 함)

---

## 4. 확인된 실행 결과

### 4.1 PC (Playwright + HTML 픽스처) — PASS

```powershell
cd C:\work\cloud-waste-map
npm install
npm run poc
```

- 출력: `PASS: selected exactly the 10 candidates; no decoys.`
- UI: `선택됨 10개`
- Delete 미클릭
- 대상: 로컬 `fixtures/photos-grid.html` 만 (실사이트 아님)

주요 파일:

- `fixtures/photos-grid.html`
- `fixtures/candidates.json`
- `poc/select-ten.mjs`
- `package.json` (`postinstall` → Playwright Chromium)

### 4.2 Android (픽스처 앱 + UIAutomator) — PASS

```powershell
cd C:\work\cloud-waste-map\android-poc
.\check-feasibility.ps1
# 또는
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat connectedDebugAndroidTest
```

회사 PC에서 확인된 툴체인:

- SDK: `%LOCALAPPDATA%\Android\Sdk`
- AVD: `Medium_Phone_API_36.1` (첫 기동 시 이름 주의 — `Medium_Phone` 아님)
- Java: Android Studio JBR 21
- `connectedDebugAndroidTest` → **SelectTenTest PASS** (BUILD SUCCESSFUL)

주요 파일:

- `android-poc/app/.../MainActivity.kt` — 가짜 그리드
- `android-poc/app/.../FixtureData.kt` — PC와 같은 후보/디코이
- `android-poc/app/.../SelectTenTest.kt` — 왕관 테스트
- `android-poc/check-feasibility.ps1` — 툴체인 + 테스트 원클릭
- `android-poc/README.md`

참고: RecyclerView는 스크롤/클릭 불안정 → **ScrollView + GridLayout**으로 바꿔 PASS.

---

## 5. 하드 가드 (집 Codex도 유지)

1. **회사 PC에서 개인 Google 로그인 / photos.google.com / 실 Photos 앱 자동화 금지**
2. **삭제 버튼·휴지통·영구삭제 자동화 금지** — 항상 사람 확인 후 수동
3. 이 저장소 스크립트를 실 Photos URL/패키지에 **그대로 붙이지 말 것**
4. utopia / PLM 대시보드 / routines에 **넣지 말 것**
5. 사용자가 “살짝 연동”을 회사에서도 제안했으나 → **거절·보류**. 계정 연동은 **집·개인 기기만**

---

## 6. 집에서 이어서 할 일 (제안 순서)

### Phase A — 환경 이전 ( mechan ical )

1. `cloud-waste-map` 폴더를 USB/git/zip 등으로 집으로 복사
2. Mac에서 PC PoC: `npm install` → `npm run poc` → PASS 재확인
3. (선택) Android Studio + 에뮬/실기로 `android-poc` 왕관 테스트 재확인

### Phase B — 실 Photos “선택만” 실험 (고수준, 새 코드)

목표: **본인이 로그인한 상태**에서, 화면에 보이는 정리 후보 N장(예: 10)을  
**찾아 선택까지** 같은 패턴으로 실험. 삭제는 사람이.

체크리스트:

- [ ] 개인 Mac/폰에서 Google Photos **본인 로그인** (에이전트에게 비번/쿠키 주지 말 것)
- [ ] 후보 목록을 화면에서 사람이 먼저 확인
- [ ] 자동화 범위 = 탐색 + 선택 + 검증(개수/오선택) 까지만
- [ ] Delete / 휴지통 / “영구 삭제” 셀렉터는 코드에 **넣지 않거나** dead-guard
- [ ] UI 변경에 깨지기 쉬움 → 셀렉터·접근성 트리 조사부터
- [ ] Android 실 Photos 앱 vs 웹 Photos 중 하나부터 (동시 말고)

### Phase C — 제품화(나중)

- “잊힌 용량” UX: 후보 제시 → 선택 확인 → **사람 승인 후**만 삭제
- 용량 추정, Takeout, AI 분류는 왕관 통과 후에만

---

## 7. GUI 시안 의도 (집 작업 참고)

시안 이미지: `docs/gui-mock-*.png`

의도된 UX 원칙:

- 브랜드 **잊힌 용량**이 첫 화면에서 크게
- 한 문장: 지정한 사진만 찾아 선택
- 선택 카운트 명확 (`선택됨 N개`)
- Delete는 보여도 **비활성/경고** — “사람이 확인 후”
- 대시보드형 통계 나열·배지 남발 금지
- 회사 PoC 픽스처(`fixtures/photos-grid.html`)가 이미 이 흐름의 **동작 프로토타입**

---

## 8. Codex에게 부탁할 첫 프롬프트 예시

```text
cloud-waste-map 저장소와 HANDOFF-HOME.md를 읽어줘.
회사에서는 PC/Android 픽스처 왕관 테스트가 PASS야.
집에서는 실 Google Photos에 "선택만" 패턴을 실험하고 싶어.
삭제 자동화·계정 비밀번호 요청·회사 환경 연동은 금지.
먼저 웹 Photos vs Android Photos 중 어디가 안전한지 비교하고,
선택-only 실험 설계(셀렉터 조사 방법, 가드, 성공 기준)만 제안해줘.
코드는 설계 합의 후에.
```

---

## 9. 알려진 잡음 / 블로커

| 이슈 | 상태 |
|------|------|
| utopia `scratch/google-photos-waste-poc` 빈 폴더 잠금 | 내용물 없음. Cursor/프로세스 잠금 시 rmdir 실패할 수 있음 |
| MCP `create_project` git init 실패 (Windows `/bin/sh`) | 수동 `git init`으로 해결됨 |
| Android 에뮬 첫 기동 AVD 이름 오타 | `Medium_Phone_API_36.1`로 수정됨 |
| 회사 네트워크 Playwright Chromium | 당시 install 성공. 집에서 재설치 필요할 수 있음 |

---

## 10. 디렉터리 맵

```
cloud-waste-map/
  README.md                 # PC PoC + Android 링크
  HANDOFF-HOME.md           # ← 이 문서
  docs/
    gui-mock-forgotten-storage-phone.png
    gui-mock-forgotten-storage-desktop.png
  fixtures/
    photos-grid.html
    candidates.json
  poc/
    select-ten.mjs
  package.json
  android-poc/
    README.md
    check-feasibility.ps1
    app/...
```

**끝.** 집에서는 “가능여부 재현 → 실 Photos 선택-only 설계” 순으로.
