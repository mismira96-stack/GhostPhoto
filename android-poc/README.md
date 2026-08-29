# Android «지정 10개 선택» 가능여부 PoC

회사 PC용 **가짜 픽스처 앱**입니다. 실 Google Photos / 실계정 / 삭제는 범위 밖입니다.

## 이 PoC가 증명하는 것

Android UI에서도 지정한 **10개 미디어 ID**만 찾아 선택하고 멈출 수 있는가.

- 성공 = instrumented test `PASS` + 디코이 미선택 + Delete 미클릭
- 실 Photos 앱 자동화 ≠ 이 단계

## 사전 조건 (이 PC에서 확인됨)

- Android SDK: `%LOCALAPPDATA%\Android\Sdk`
- Emulator AVD: `Medium_Phone_API_36.1` (스크립트가 첫 AVD 자동 사용)
- Android Studio JBR (Java 21)

## 실행 (가능여부 체크)

```powershell
cd C:\work\cloud-waste-map\android-poc
.\check-feasibility.ps1
```

단계:

1. **TOOLCHAIN** — adb / emulator / Java 확인, AVD 기동
2. **SELECT** — 픽스처 앱 설치 + `SelectTenTest` 실행

또는 수동:

```powershell
cd C:\work\cloud-waste-map\android-poc
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat connectedDebugAndroidTest
```

## 하지 말 것

- 회사 PC에서 개인 Google 계정 / 실 Photos 앱 자동화
- 삭제 버튼 자동화
- 이 PoC를 PLM·utopia에 연동

## 파일

| 경로 | 역할 |
|------|------|
| `app/.../MainActivity.kt` | 후보+디코이 가짜 그리드 |
| `app/.../FixtureData.kt` | PC와 같은 10개 후보 ID |
| `app/.../SelectTenTest.kt` | UIAutomator 왕관 테스트 |
| `check-feasibility.ps1` | 원클릭 가능여부 체크 |
