# LibroPrintDriver

JY-P1000 키오스크 내장 3인치 감열 프린터용 Android PrintService 드라이버.

## 장치 정보

| 항목 | JY-P1000 (standard) | A40i (a40) |
|------|------|------|
| 기기 | JY-P1000 (JV COMPANY) | QUAD-CORE A40i JYA40i |
| SoC | RK3568 | Allwinner A40i |
| OS | Android 11 | Android 7.1.1 |
| DPI | 320 (hdpi) | 160 (mdpi) |
| 패키지명 | `com.betona.printdriver` | `com.android.printdriver` |
| 프린터 경로 | `/dev/printer` | `/dev/printer` |
| 인쇄 폭 | 576px (72 bytes/row), 75mm | 576px (72 bytes/row), 75mm |
| 해상도 | 203 DPI | 203 DPI |
| 밝기 | 1~8 (기본값 4) | 1~8 (기본값 4) |
| 커터 | ESC/POS GS V 0 자동 절단 | ESC/POS GS V 0 자동 절단 |

## 빌드 플레이버

디바이스별 빌드를 위해 Product Flavor를 사용합니다.

| 플레이버 | 대상 기기 | applicationId | 비고 |
|----------|-----------|---------------|------|
| `standard` | JY-P1000 (Android 11) | `com.betona.printdriver` | 기본 |
| `a40` | A40i (Android 7) | `com.android.printdriver` | BackgroundManagerService 화이트리스트 우회 |

```bash
# JY-P1000 빌드
./gradlew assembleStandardRelease

# A40i 빌드
./gradlew assembleA40Release

# 전체 빌드
./gradlew assembleRelease
```

> **`com.android.printdriver` 패키지명 이유**: A40i 기기는 Allwinner/Softwinner의 `BackgroundManagerService`가 `com.android`, `com.google` 등의 접두사로 시작하지 않는 앱의 서비스를 강제 종료합니다. 이를 우회하기 위해 `com.android.printdriver` applicationId를 사용합니다. 상세 내용은 아래 [A40i 디바이스 이슈](#a40i-디바이스-이슈-android-7) 섹션 참조.

## 설치 및 설정

### JY-P1000 (standard)

#### 1. APK 설치

```bash
./gradlew assembleStandardDebug
adb install -r app/build/outputs/apk/standard/debug/app-standard-debug.apk
```

#### 2. PrintService 활성화

1. **설정** > **연결된 기기** > **인쇄** (또는 앱 내 **"인쇄 서비스 설정 열기"** 버튼)
2. **LibroPrintDriver** 항목을 찾아서 **ON** 으로 전환
3. 활성화되면 "JY-P1000 감열 프린터" 가 프린터 목록에 나타남

#### 3. 인쇄 테스트

- 앱 메인 화면에서 **연결 테스트** 버튼으로 `/dev/printer` 연결 확인
- **이미지 인쇄 테스트**, **라벨 인쇄 테스트** 등으로 직접 인쇄 확인
- Chrome, PDF 뷰어 등에서 **인쇄** > **JY-P1000 감열 프린터** 선택하여 PrintService 인쇄 확인

### A40i (a40)

#### 1. APK 설치

```bash
./gradlew assembleA40Release
adb install -r app/build/outputs/apk/a40/release/app-a40-release.apk
```

#### 2. PrintService 활성화 (adb 필요)

A40i에서는 설정 UI에서 인쇄 서비스가 보이지 않을 수 있습니다. adb로 직접 활성화:

```bash
adb shell settings put secure enabled_print_services com.android.printdriver/.LibroPrintService
```

#### 3. PrintSpooler 패치 (최초 1회)

A40i의 기본 PrintSpooler.apk에는 mdpi 해상도에서 크래시하는 drawable 버그가 있습니다. 패치 필요:

```bash
# 1. 패치된 PrintSpooler.apk를 기기에 푸시
adb push PrintSpooler_patched.apk /sdcard/

# 2. root 권한으로 교체
adb shell su 0 mount -o remount,rw /system
adb shell su 0 cp /sdcard/PrintSpooler_patched.apk /system/app/PrintSpooler/PrintSpooler.apk
adb shell su 0 chmod 644 /system/app/PrintSpooler/PrintSpooler.apk

# 3. 위치 권한 부여 (PrintSpooler가 요구하지만 선언하지 않음)
adb shell pm grant com.android.printspooler android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant com.android.printspooler android.permission.ACCESS_FINE_LOCATION

# 4. packages.xml 인증서 업데이트 (별도 문서 참조)

# 5. 재부팅
adb reboot
```

#### 4. 인쇄 테스트

- **Firefox** (v143, Android 7 지원 마지막 버전)에서 인쇄
- Chrome은 Android 7에서 인쇄 메뉴가 제대로 작동하지 않음

## 인쇄 방식

### 아키텍처

```
Android 앱 (Chrome, PDF 등)
    │  PrintJob (PDF)
    ▼
LibroPrintService          ← Android PrintService
    │  PDF → Bitmap → 1bpp mono
    ▼
DevicePrinter (singleton)  ← jyndklib 네이티브 I/O
    │  jyPrintString()
    ▼
/dev/printer               ← 하드웨어
```

### 인쇄 흐름

1. **PrintJob 수신**: `LibroPrintService.onPrintJobQueued()`에서 인쇄 작업 수신
2. **PDF 렌더링**: `PdfRenderer`로 각 페이지를 576px 폭 Bitmap으로 렌더링
3. **이미지 처리**:
   - `cropWhiteBorders()` - Chrome 여백 제거
   - `scaleToWidth(576)` - 인쇄 폭에 맞게 스케일링
   - `toMonochrome()` - ARGB → 1bpp 흑백 변환 (휘도 128 기준)
   - `trimTrailingWhiteRows()` - 하단 빈 줄 제거 (용지 절약)
4. **ESC/POS 래스터 전송**:
   - `GS v 0` 헤더 1회 전송 (전체 이미지 높이)
   - 이미지 데이터를 4KB 청크로 나눠서 `jyPrintString()` 전송
5. **커트**: `GS V 0` (ESC/POS full cut) 명령으로 용지 절단

### 프린터 I/O 규칙

| 메서드 | 용도 | 비고 |
|--------|------|------|
| `jyPrinterOpen()` | 프린터 열기 | 앱 시작 시 1회 호출, 싱글톤 유지 |
| `jyPrintString(data, sync)` | 명령/텍스트/이미지 전송 | 모든 데이터 전송에 사용 |
| `jyPrinterRawData(data)` | 이미지 raw 전송 | 사용하지 않음 (커트 문제 유발) |
| `jyPrinterClose()` | 프린터 닫기 | **사용 금지** - 스택 오버플로우 크래시 |

> **중요**: `DevicePrinter`는 `object` (싱글톤)으로 구현되어 프로세스 전체에서 fd를 공유합니다.
> `jyPrinterClose()`를 호출하지 않으므로 fd가 프로세스 수명 동안 유지됩니다.

### 커트 방식

```kotlin
// ESC/POS 명령으로 커트 (크래시 없음)
DevicePrinter.write(EscPosCommands.feedLines(4))  // 용지 배출
DevicePrinter.write(EscPosCommands.fullCut())      // GS V 0 전체 절단
```

`jyPrinterClose()`는 네이티브 라이브러리의 스택 오버플로우 버그로 인해 항상 SIGABRT 크래시가 발생합니다. ESC/POS `GS V 0` 명령이 정상 작동하므로 이를 사용합니다.

## 프로젝트 구조

```
app/src/main/java/com/betona/printdriver/
├── LibroPrintService.kt     # Android PrintService (PDF→인쇄)
├── LibroDiscoverySession.kt # 프린터 검색 (75mm 용지 설정)
├── DevicePrinter.kt         # 프린터 I/O 싱글톤 (jyndklib 래핑)
├── EscPosCommands.kt        # ESC/POS 명령어 빌더
├── BitmapConverter.kt       # Bitmap→흑백 변환, 크롭, 트림
├── AppPrefs.kt              # SharedPreferences 설정 관리
├── MainActivity.kt          # 관리자 설정 UI + 테스트 버튼
├── WebPrintActivity.kt      # 홈 화면 (WebView + 툴바)
├── LadderGameActivity.kt    # 사다리 게임 (Jetpack Compose)
├── LadderGenerator.kt       # 사다리 생성 알고리즘
├── LadderView.kt            # 사다리 Canvas 렌더링 + 인쇄용 Bitmap
├── BingoGameActivity.kt     # 빙고 게임 (Jetpack Compose)
├── BingoGenerator.kt        # 빙고 카드 생성 + 번호 추첨 + 빙고 판정
├── BingoCardView.kt         # 빙고 카드 Canvas 렌더링 + 인쇄용 Bitmap
└── web/
    ├── WebServerService.kt      # Foreground Service (웹/RAW/IPP 서버 호스팅)
    ├── WebManagementServer.kt   # NanoHTTPD 웹 관리 서버 (:8080)
    ├── RawPrintServer.kt        # RAW 인쇄 서버 (:9100)
    ├── IppServer.kt             # IPP 인쇄 서버 (:6631)
    ├── PrinterApi.kt            # REST API - 인쇄 관련
    ├── DeviceApi.kt             # REST API - 기기 정보/설정
    └── AuthManager.kt           # 웹 인증 관리

jyndklib/                    # 네이티브 라이브러리 (JY-P1000 전용)
└── jyNativeClass            # JNI: open, close, printString, rawData, feed 등

printplugin/                 # 스마트폰용 72mm 네트워크 인쇄 플러그인
├── LibroNetPrintService.kt  # Android PrintService (IPP 클라이언트)
├── LibroNetDiscoverySession.kt  # mDNS 프린터 자동 검색
└── IppClient.kt             # IPP Print-Job 전송
```

## 네트워크 인쇄 서버

키오스크에 내장된 3개의 인쇄 서버를 통해 PC 및 스마트폰에서 원격 인쇄가 가능합니다.

| 서버 | 포트 | 프로토콜 | 용도 |
|------|------|----------|------|
| 웹 관리 | 8080 | HTTP | 브라우저에서 인쇄 + 기기 관리 |
| RAW 인쇄 | 9100 | TCP RAW | Windows PC 네트워크 프린터 |
| IPP 인쇄 | 6631 | IPP/HTTP | 스마트폰 인쇄 플러그인 |

### PC에서 인쇄 (RAW :9100)
1. 제어판 → 장치 및 프린터 → 프린터 추가
2. "TCP/IP 주소로 프린터 추가" 선택
3. IP: `<기기IP>` / 포트: `9100` 입력

### 스마트폰에서 인쇄 (IPP :6631)
1. `printplugin` APK 설치 (72mm 용지 플러그인)
2. 같은 WiFi 네트워크 연결
3. 인쇄 시 자동으로 프린터 검색됨

### 브라우저에서 인쇄 (웹 :8080)
- `http://<기기IP>:8080` 접속
- 파일 업로드, 텍스트/이미지 인쇄, 기기 설정 가능

## 게임 기능

관리자 설정에서 "홈 화면 게임 표시" 옵션 활성화 시 홈 툴바에 게임 버튼이 표시됩니다.

### 사다리 게임
- 2~10명, 결과 직접 입력
- 사다리 생성 → 화면 표시 → 인쇄
- 참가자가 선택 후 결과 공개 (애니메이션)

### 빙고 게임
- 3x3 / 4x4 / 5x5 그리드 선택
- 1~30명 참가, 커스텀 숫자 범위 지원
- 3단계 진행: 설정 → 카드 확인/인쇄 → 번호 추첨
- 자동 추첨 + 수동 번호 선택 지원
- 빙고 라인 완성 시 색상별 줄긋기 표시
- 카드별 빙고 달성 현황 실시간 표시
- 당첨 시 자동 알림 + 당첨 카드 인쇄

> 게임에서 관리자 페이지 진입 시 항상 비밀번호 입력이 필요합니다.

## 용지 설정

LibroDiscoverySession에서 3가지 용지 크기를 제공합니다:

| 이름 | 크기 | 용도 |
|------|------|------|
| 75mm x 150mm | 2953 x 5906 mils | 기본값 (라벨, 짧은 문서) |
| 75mm x 300mm | 2953 x 11811 mils | 중간 문서 |
| 75mm x 600mm | 2953 x 23622 mils | 긴 문서 |

## 빌드 환경

| 항목 | 버전 |
|------|------|
| Gradle | 8.4 |
| Android Gradle Plugin | 8.3.0 |
| Kotlin | 1.9.0 |
| Jetpack Compose BOM | 2023.10.01 |
| Compose Compiler | 1.5.2 |
| compileSdk | 34 |
| targetSdk | 26 |
| minSdk | 24 |
| NDK (jyndklib) | 22.1.7171670 |

## A40i 디바이스 이슈 (Android 7)

A40i (Allwinner A40i, Android 7.1.1) 기기에서 PrintService를 사용하기 위해 해결해야 하는 3가지 문제:

### 1. BackgroundManagerService 화이트리스트

**문제**: Allwinner/Softwinner 커스텀 프레임워크의 `BackgroundManagerService`가 화이트리스트에 없는 앱의 백그라운드 서비스를 강제 종료합니다.

```
skipService com.betona.printdriver/.LibroPrintService because of activity not started!
```

화이트리스트는 하드코딩되어 있으며 `startsWith()`로 체크:
- `com.android`, `com.google`, `com.softwinner`, `tv.fun` 등의 접두사

**시도했지만 실패한 방법**:
- `settings put global background_manager_enabled 0` — 무시됨
- `deviceidle whitelist` — 영향 없음
- `cmd appops set RUN_IN_BACKGROUND allow` — 영향 없음
- `/system/priv-app/`으로 이동 — 영향 없음

**해결**: `com.android.printdriver` applicationId 사용 (빌드 플레이버 `a40`)

### 2. PrintSpooler drawable 크래시

**문제**: 기본 PrintSpooler.apk의 `res/drawable/ic_expand_more.xml`과 `ic_expand_less.xml`이 자기 자신을 참조하는 selector입니다.

```xml
<!-- ic_expand_more.xml — 자기참조! -->
<selector>
    <item><bitmap android:src="@drawable/ic_expand_more" ... /></item>
</selector>
```

hdpi (240+) 기기에서는 `res/drawable-hdpi-v4/`의 PNG가 우선 로딩되어 문제없지만, A40i는 mdpi (160dpi)이므로 XML → XML 무한 재귀 → `Resources$NotFoundException` 크래시.

**해결**: apktool로 PrintSpooler 디컴파일 후 `res/drawable-mdpi-v4/` 디렉터리에 hdpi PNG 복사, 재빌드 후 AOSP 테스트 키로 서명. `packages.xml`에 새 인증서 등록.

### 3. PrintSpooler 위치 권한

**문제**: PrintSpooler가 `fused` 위치 제공자에 접근하지만 `ACCESS_COARSE_LOCATION` 권한이 없어서 `SecurityException` 크래시.

```
SecurityException: "fused" location provider requires ACCESS_COARSE_LOCATION or ACCESS_FINE_LOCATION
```

**해결**: `pm grant`로 권한 수동 부여

```bash
pm grant com.android.printspooler android.permission.ACCESS_COARSE_LOCATION
pm grant com.android.printspooler android.permission.ACCESS_FINE_LOCATION
```

### 4. 브라우저 인쇄 지원

| 브라우저 | 버전 | 인쇄 지원 | 비고 |
|----------|------|-----------|------|
| Chrome | 119 (Android 7 최신) | 인쇄 메뉴 없음 | 공유→인쇄 동작 안함 |
| Firefox | 143 (Android 7 최신) | 인쇄 지원 | 메뉴 → 인쇄 |

### packages.xml 인증서 업데이트

PrintSpooler를 AOSP 테스트 키로 재서명한 경우, `/data/system/packages.xml`의 인증서 정보를 업데이트해야 합니다:

1. 새 인증서 인덱스를 `<keyset-settings>`의 `<keys>` 섹션에 추가
2. PrintSpooler 패키지 항목의 `<sigs>`에서 새 인증서 인덱스 참조
3. 재부팅 후 `enabled_print_services` 설정 재적용 필요

```bash
# 재부팅 후 매번 실행
adb shell settings put secure enabled_print_services com.android.printdriver/.LibroPrintService
```

## 알려진 제한사항

- `jyPrinterClose()` 호출 시 네이티브 스택 오버플로우로 앱 크래시 발생 → 호출하지 않음
- `jyPrinterRawData()` 사용 후 `jyPrinterClose()` 호출 시 커트가 작동하지 않음 → 사용하지 않음
- `FileOutputStream`으로 `/dev/printer` 직접 쓰기 불가 (EINVAL) → 반드시 jyndklib 사용
- 프린터 fd는 프로세스 종료 시 OS에서 자동 해제
- A40i: `enabled_print_services` 설정이 재부팅 시 초기화될 수 있음 → adb로 재설정 필요
- A40i: Chrome에서 인쇄 불가 → Firefox 143 사용
