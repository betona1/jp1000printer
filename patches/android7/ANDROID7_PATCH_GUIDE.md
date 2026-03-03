# Android 7 (A40i) 설치 가이드

A40i (Allwinner A40i, Android 7.1.1) 기기에서 LibroPrintDriver를 설치하는 방법입니다.

## 대상 기기

| 항목 | 내용 |
|------|------|
| 기기명 | QUAD-CORE A40i JYA40i |
| SoC | Allwinner A40i |
| OS | Android 7.1.1 (API 25) |
| CPU | armeabi-v7a (32-bit ARM) |
| 해상도 | 1024×600 @ 160dpi (mdpi) |
| SELinux | Disabled |
| Root | adb root 지원 |

## 사전 요구사항

- ADB가 설치된 PC (Windows/Mac/Linux)
- USB로 기기 연결 (`adb devices`에서 확인)
- `adb root` + `adb remount` 가능한 상태
- Python 3 설치됨 (framework-res 패치용)

---

## 자동 설치 (권장)

**한 번에 모든 패치를 적용하는 스크립트:**

```bash
cd patches/android7
bash setup_a40i.sh                    # 기기가 1대만 연결된 경우
bash setup_a40i.sh -s 20080411        # 시리얼 지정
bash setup_a40i.sh -t 4              # transport_id 지정
```

스크립트가 수행하는 작업:
1. 앱 APK 설치 (`com.android.printdriver`)
2. Chrome 113 설치 (WebView provider용)
3. 부트 스크립트 설치 (PrintService 자동 활성화)
4. framework-res.apk 패치 (Chrome을 WebView provider로 등록)
5. 설정 적용 (PrintService, WebView provider, PrintSpooler 권한)
6. 재부팅

---

## 수동 설치 (단계별)

### 1단계: 앱 설치

```bash
# 빌드 (Android Studio 또는 Gradle)
./gradlew :app:assembleA40Debug

# 설치
adb install -r app/build/outputs/apk/a40/debug/app-a40-debug.apk
```

패키지명: `com.android.printdriver` (BackgroundManagerService 화이트리스트 우회용 a40 빌드 플레이버)

### 2단계: Chrome 113 설치

A40i 기본 Chrome은 v76으로 WebView provider 기능이 없습니다.
Chrome 113은 WebView provider로 동작하여 구 WebView v52를 대체합니다.

```bash
# 기존 Chrome 제거 (서명 불일치 방지)
adb shell pm uninstall com.android.chrome

# Chrome 113 설치
adb install -r patches/android7/chrome113.apk
```

### 3단계: 부트 스크립트 설치

Android 7에서는 `enabled_print_services`, `webview_provider` 설정이 재부팅 시 초기화됩니다.
init 스크립트로 부팅 시 자동으로 설정을 복원합니다.

```bash
adb root
adb remount

# 스크립트 설치
adb push patches/android7/configure_print.sh /data/local/tmp/
adb push patches/android7/printdriver.rc /data/local/tmp/

adb shell cp /data/local/tmp/configure_print.sh /system/bin/configure_print.sh
adb shell chmod 755 /system/bin/configure_print.sh

adb shell mkdir -p /system/etc/init
adb shell cp /data/local/tmp/printdriver.rc /system/etc/init/printdriver.rc
adb shell chmod 644 /system/etc/init/printdriver.rc
```

**configure_print.sh가 하는 일:**
- `enabled_print_services` 설정 (인쇄 드라이버 활성화)
- `disabled_print_services` 초기화 (시스템이 새 서비스를 자동 비활성화하는 것 방지)
- PrintSpooler 위치 권한 부여 (SecurityException 크래시 방지)
- `webview_provider`를 Chrome으로 설정

### 4단계: framework-res.apk 패치

Chrome을 WebView provider로 등록하기 위해 `config_webview_packages.xml`을 패치합니다.

```bash
adb root
adb remount

# 원본 백업
adb shell cp /system/framework/framework-res.apk /system/framework/framework-res.apk.bak

# PC로 가져오기
adb exec-out "cat /system/framework/framework-res.apk" > framework-res.apk

# Python으로 패치
python3 patches/android7/patch_framework_res.py framework-res.apk patches/android7/config_webview_packages_patched.bin

# 패치된 APK 설치
adb push framework-res-patched.apk /data/local/tmp/
adb shell cp /data/local/tmp/framework-res-patched.apk /system/framework/framework-res.apk
adb shell chmod 644 /system/framework/framework-res.apk
```

**패치 내용** (config_webview_packages.xml):
```xml
<!-- 패치 전: com.android.webview만 등록 -->
<webviewproviders>
    <webviewprovider description="Android WebView"
        packageName="com.android.webview" availableByDefault="true" />
</webviewproviders>

<!-- 패치 후: Chrome 추가 (우선순위 1위) -->
<webviewproviders>
    <webviewprovider description="Chrome"
        packageName="com.android.chrome" availableByDefault="true" />
    <webviewprovider description="Android WebView"
        packageName="com.android.webview" availableByDefault="true" />
</webviewproviders>
```

### 5단계: 설정 적용 + 재부팅

```bash
# 설정 적용
adb shell settings put global webview_provider com.android.chrome
adb shell settings put secure enabled_print_services com.android.printdriver/com.betona.printdriver.LibroPrintService
adb shell settings put secure disabled_print_services ""
adb shell pm grant com.android.printspooler android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant com.android.printspooler android.permission.ACCESS_FINE_LOCATION

# 재부팅
adb reboot
```

---

## 설치 확인

재부팅 후 다음을 확인합니다:

```bash
# 1. 부트 스크립트 동작 확인
adb shell logcat -d | grep PrintConfig
# → PrintConfig: Print service configured

# 2. WebView provider 확인
adb shell settings get global webview_provider
# → com.android.chrome

# 3. PrintService 활성화 확인
adb shell settings get secure enabled_print_services
# → com.android.printdriver/com.betona.printdriver.LibroPrintService

# 4. Chrome WebView 로드 확인
adb shell logcat -d | grep cr_SplitCompatApp
# → Launched version=113.0.5672.77 ... processName=com.android.chrome:webview_service
```

앱 화면에서 확인:
- 웹 페이지가 정상 표시되는지 확인
- 관리자 → 상태 탭: PrintSpooler "설치됨", 인쇄 드라이버 "활성화"
- 다른 스마트폰에서 인쇄 테스트

---

## 복구

### framework-res.apk 복구 (부팅 실패 시)

```bash
adb root
adb remount
adb shell cp /system/framework/framework-res.apk.bak /system/framework/framework-res.apk
adb shell chmod 644 /system/framework/framework-res.apk
adb reboot
```

### 부트 스크립트 제거

```bash
adb root
adb remount
adb shell rm /system/bin/configure_print.sh
adb shell rm /system/etc/init/printdriver.rc
adb reboot
```

---

## 포함된 파일

| 파일 | 설명 | 크기 |
|------|------|------|
| `setup_a40i.sh` | **자동 설치 스크립트 (한 번에 전체 설치)** | 5KB |
| `app-a40-release.apk` | A40i용 빌드 APK (com.android.printdriver) | 13MB |
| `chrome113.apk` | Chrome 113 APK (WebView provider용) | 238MB |
| `configure_print.sh` | 부팅 시 PrintService/WebView 자동 설정 스크립트 | 0.5KB |
| `printdriver.rc` | Android init 서비스 정의 파일 | 0.3KB |
| `patch_webview_config.py` | WebView config 바이너리 XML 생성 스크립트 | 7KB |
| `patch_framework_res.py` | framework-res.apk XML 교체 스크립트 | 2.5KB |
| `config_webview_packages_patched.bin` | 패치된 바이너리 XML (Chrome + WebView) | 540B |

---

## 알려진 문제 및 해결

| 문제 | 원인 | 해결 |
|------|------|------|
| 웹 페이지 안 나옴 | WebView v52 너무 오래됨 | Chrome 113 + framework-res 패치 |
| 설정 재부팅 시 초기화 | Android 7 설정 DB 초기화 | 부트 스크립트 (configure_print.sh) |
| Chrome 설치 실패 (서명 불일치) | 기본 Chrome과 서명 다름 | `pm uninstall` 후 재설치 |
| PrintSpooler 크래시 | 위치 권한 없음 (SecurityException) | `pm grant` 위치 권한 |
| PrintSpooler drawable 크래시 | mdpi에서 XML 자기참조 무한재귀 | apktool로 mdpi PNG 추가 |
| 앱 강제종료 (BackgroundManager) | 화이트리스트에 없는 패키지 | a40 빌드 플레이버 (com.android.printdriver) |
| PdfRenderer.close() 크래시 | Android 7 libpdfium 버그 | API < 26에서 close() 호출 스킵 |
| 인쇄 시 앱 중지 (2번째부터) | GC가 leaked PdfRenderer 정리 시 SIGSEGV | renderer.close() 스킵으로 해결 |

---

## 기기 스펙 비교

| 항목 | A40i (Android 7) | JY-P1000 (Android 11) |
|------|-------------------|------------------------|
| 패키지명 | com.android.printdriver | com.betona.printdriver |
| 설치 스크립트 | `setup_a40i.sh` (8단계) | `setup_jyp1000.sh` (4단계) |
| Chrome | v76 → 113 (수동 업데이트) | 최신 버전 |
| WebView | v52 → Chrome 113 (패치) | 시스템 WebView 사용 |
| Root / su | adb root + su 가능 | su 없음 |
| 재부팅 후 설정 유지 | 부트 스크립트 (printdriver.rc) | WRITE_SECURE_SETTINGS 권한 |
| framework 패치 | 필요 (WebView) | 불필요 |
| PrintSpooler 패치 | 필요 (mdpi 크래시) | 불필요 |
| 프린터 경로 | /dev/printer | /dev/printer |
