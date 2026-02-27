# Android 7 (A40i) 패치 가이드

A40i (Allwinner A40i, Android 7.1.1) 기기에서 LibroPrintDriver를 사용하기 위한 시스템 패치 안내입니다.

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

- ADB가 설치된 PC
- USB로 기기 연결 (adb devices에서 확인)
- `adb root` + `adb remount` 가능한 상태

---

## 패치 1: APK 설치

```bash
adb install -r app-a40-release.apk
```

패키지명: `com.android.printdriver` (BackgroundManagerService 화이트리스트 우회용)

---

## 패치 2: PrintService 자동 활성화 (부팅 시)

Android 7에서는 PrintService 설정이 재부팅 시 초기화됩니다. init 스크립트로 자동 구성합니다.

### 2-1. 부트 스크립트 설치

```bash
adb root
adb remount

# 스크립트 푸시
adb push configure_print.sh /sdcard/
adb push printdriver.rc /sdcard/

# 시스템 디렉터리에 설치
adb shell cp /sdcard/configure_print.sh /system/bin/configure_print.sh
adb shell chmod 755 /system/bin/configure_print.sh

adb shell mkdir -p /system/etc/init
adb shell cp /sdcard/printdriver.rc /system/etc/init/printdriver.rc
adb shell chmod 644 /system/etc/init/printdriver.rc
```

### 2-2. 확인

```bash
adb reboot

# 부팅 후 확인
adb shell logcat -d | grep PrintConfig
# 출력: PrintConfig: Print service configured

adb shell settings get secure enabled_print_services
# 출력: com.android.printdriver/com.betona.printdriver.LibroPrintService
```

### configure_print.sh 내용

```bash
#!/system/bin/sh
sleep 15
settings put secure enabled_print_services com.android.printdriver/com.betona.printdriver.LibroPrintService
settings put secure disabled_print_services ""
pm grant com.android.printspooler android.permission.ACCESS_COARSE_LOCATION
pm grant com.android.printspooler android.permission.ACCESS_FINE_LOCATION
log -t PrintConfig "Print service configured"
```

**핵심 포인트**:
- `enabled_print_services`: 반드시 **전체 컴포넌트 이름** 사용 (`패키지명/클래스명`)
- `disabled_print_services`: 반드시 빈 문자열로 설정 (시스템이 새 서비스를 disabled에 자동 추가함)
- `pm grant`: PrintSpooler가 위치 권한 없이 크래시하는 문제 해결

---

## 패치 3: WebView 업그레이드 (Chrome 119 엔진)

A40i의 기본 WebView은 Chromium 52 (2016년)로, 최신 웹사이트가 렌더링되지 않습니다.
Chrome 119가 이미 설치되어 있으므로, framework-res.apk를 패치하여 Chrome을 WebView 프로바이더로 등록합니다.

### 문제

```
기본 WebView: com.android.webview v52.0.2743.100
설치된 Chrome: com.android.chrome v119.0.6045.194
```

framework-res.apk의 `config_webview_packages.xml`에 `com.android.webview`만 등록되어 있어
Chrome을 WebView으로 사용할 수 없습니다.

### 3-1. 패치 방법 A: 미리 빌드된 바이너리 사용

```bash
adb root
adb remount

# 원본 백업 (필수!)
adb shell cp /system/framework/framework-res.apk /sdcard/framework-res-backup.apk

# PC에서 framework-res.apk 가져오기
adb exec-out "cat /system/framework/framework-res.apk" > framework-res.apk

# Python으로 패치된 XML을 APK에 삽입
python patch_framework_res.py framework-res.apk config_webview_packages_patched.bin

# 패치된 APK 푸시
adb push framework-res-patched.apk /sdcard/
adb shell cp /sdcard/framework-res-patched.apk /system/framework/framework-res.apk
adb shell chmod 644 /system/framework/framework-res.apk

# WebView 프로바이더를 Chrome으로 설정
adb shell settings put global webview_provider com.android.chrome

# 재부팅
adb reboot
```

### 3-2. 패치 방법 B: 직접 생성

Python 3이 필요합니다.

```bash
# 1. 패치된 바이너리 XML 생성
python patch_webview_config.py config_webview_packages_patched.bin

# 2. framework-res.apk에서 XML 교체 (Python 사용)
python patch_framework_res.py framework-res.apk config_webview_packages_patched.bin

# 3. 이후 방법 A의 adb push 과정 동일
```

### 3-3. 패치 검증

```bash
# 부팅 후 WebView 프로바이더 확인
adb shell settings get global webview_provider
# 출력: com.android.chrome

# WebView 로그 확인
adb shell logcat -d | grep "cr_SplitCompatApp"
# 출력: Launched version=119.0.6045.194 ... processName=com.android.chrome:webview_service
```

### 3-4. 복구 (부팅 실패 시)

만약 framework-res.apk 패치 후 부팅이 되지 않을 경우:

```bash
# ADB가 여전히 동작하는 경우
adb root
adb remount
adb shell cp /sdcard/framework-res-backup.apk /system/framework/framework-res.apk
adb shell chmod 644 /system/framework/framework-res.apk
adb reboot
```

### config_webview_packages.xml 변경 내용

**패치 전** (원본):
```xml
<webviewproviders>
    <webviewprovider description="Android WebView"
        packageName="com.android.webview" availableByDefault="true" />
</webviewproviders>
```

**패치 후**:
```xml
<webviewproviders>
    <webviewprovider description="Chrome"
        packageName="com.android.chrome" availableByDefault="true" />
    <webviewprovider description="Android WebView"
        packageName="com.android.webview" availableByDefault="true" />
</webviewproviders>
```

---

## 패치 4: PrintSpooler drawable 크래시 수정

A40i의 기본 PrintSpooler.apk에는 mdpi 기기에서 크래시하는 버그가 있습니다.

### 문제

`res/drawable/ic_expand_more.xml`과 `ic_expand_less.xml`이 자기 자신을 참조하는 selector로,
mdpi 기기에서 무한 재귀 → `Resources$NotFoundException` 크래시가 발생합니다.

### 해결

1. apktool로 PrintSpooler 디컴파일
2. `res/drawable-hdpi-v4/`의 PNG를 `res/drawable-mdpi-v4/`에 복사
3. 재빌드 후 AOSP 테스트 키로 서명
4. `/system/app/PrintSpooler/PrintSpooler.apk` 교체
5. `/data/system/packages.xml`에 새 인증서 등록
6. 재부팅

---

## 전체 설치 순서 요약

```
1. PrintSpooler 패치 설치          (패치 4 - 최초 1회)
2. APK 설치                       (패치 1)
3. 부트 스크립트 설치               (패치 2 - 최초 1회)
4. WebView 패치 (framework-res)    (패치 3 - 최초 1회)
5. 재부팅
6. 인쇄 테스트
```

---

## 포함된 파일

| 파일 | 설명 |
|------|------|
| `app-a40-release.apk` | A40i용 빌드 APK (com.android.printdriver) |
| `configure_print.sh` | 부팅 시 PrintService 자동 활성화 스크립트 |
| `printdriver.rc` | Android init 서비스 정의 파일 |
| `patch_webview_config.py` | WebView config 바이너리 XML 생성 스크립트 |
| `patch_framework_res.py` | framework-res.apk XML 교체 스크립트 |
| `config_webview_packages_patched.bin` | 패치된 바이너리 XML (Chrome + WebView) |

---

## 알려진 제한사항

- Chrome에서 직접 인쇄 불가 (인쇄 메뉴 없음) → 앱 내 WebView 브라우저 사용
- Firefox 143에서 인쇄 가능하지만 `documentInfo: null` 문제가 간헐적 발생
- framework-res.apk 패치는 기기별로 다를 수 있음 (동일 모델에서만 테스트됨)
- WebView 프로바이더 설정(`webview_provider`)은 재부팅 시 유지됨
