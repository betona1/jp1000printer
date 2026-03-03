# 새 기기 셋업 가이드

LibroPrintDriver를 새 기기에 설치하는 단계별 가이드입니다.

---

## 기기 타입별 요약

| 항목 | JY-P1000 (Android 11) | A40i (Android 7) |
|------|----------------------|------------------|
| SoC | RK3568 | Allwinner A40i |
| 해상도 | 1280x800 @ 320dpi | 1024x600 @ 160dpi |
| 빌드 | `standard` | `a40` |
| 패키지명 | `com.betona.printdriver` | `com.android.printdriver` |
| 시스템 패치 | 불필요 | **필수** (4가지) |
| 재부팅 후 설정 유지 | `WRITE_SECURE_SETTINGS` 권한 | 부트 스크립트 (`printdriver.rc`) |
| su (root shell) | 없음 | 있음 (`adb root`) |
| 설치 스크립트 | `setup_jyp1000.sh` | `setup_a40i.sh` |
| 셋업 난이도 | 쉬움 (APK + 권한) | 복잡 (시스템 패치 필요) |

---

## A. JY-P1000 (Android 11) — 간단 셋업

### 자동 설치 (권장)

```bash
# patches/android7/ 또는 Downloads/LibroPrintDriver/ 에서 실행
bash setup_jyp1000.sh                    # 기기가 1대만 연결된 경우
bash setup_jyp1000.sh -s <시리얼>         # 시리얼 지정
bash setup_jyp1000.sh -t <ID>            # transport_id 지정
bash setup_jyp1000.sh -r                 # 설치 후 자동 재부팅
```

스크립트가 수행하는 작업 (4단계):
1. 기기 연결 확인 (모델, SDK, /dev/printer)
2. 앱 설치 (`app-standard-release.apk` 또는 `app-standard-debug.apk`)
3. 인쇄 드라이버 활성화 + 권한 부여
   - `enabled_print_services` 설정
   - PrintSpooler 위치 권한 부여
   - **`WRITE_SECURE_SETTINGS` 권한 부여** (재부팅 후 자동 복원용)
4. 설치 확인

**필요 파일**: `app-standard-release.apk` (또는 debug), `adb.exe` (PATH 또는 같은 폴더)

### 수동 설치 (단계별)

#### 1단계: 빌드 & 설치

```bash
# PC에서 실행
cd LibroPrintDriver
./gradlew :app:assembleStandardDebug

# 기기 시리얼 확인
adb devices -l

# 설치
adb -s <시리얼> install -r app/build/outputs/apk/standard/debug/app-standard-debug.apk
```

#### 2단계: 인쇄 드라이버 활성화

```bash
# PrintService 활성화
adb shell settings put secure enabled_print_services com.betona.printdriver/com.betona.printdriver.LibroPrintService
adb shell settings put secure disabled_print_services ""

# PrintSpooler 위치 권한 (크래시 방지)
adb shell pm grant com.android.printspooler android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant com.android.printspooler android.permission.ACCESS_FINE_LOCATION

# ★ 재부팅 후 자동 복원을 위한 권한 (중요!)
adb shell pm grant com.betona.printdriver android.permission.WRITE_SECURE_SETTINGS
```

> **`WRITE_SECURE_SETTINGS` 설명**: Android 11에는 `su`가 없어서 부트 스크립트로
> 설정을 복원할 수 없습니다. 대신 이 권한을 부여하면 앱의 `BootCompletedReceiver`가
> 재부팅 후 `Settings.Secure.putString()` API로 직접 인쇄 드라이버를 재활성화합니다.
> 이 권한은 한 번 부여하면 재부팅 후에도 유지됩니다.

#### 3단계: 앱 실행 & 확인

```bash
# 앱 실행
adb shell am start -n com.betona.printdriver/.WebPrintActivity

# PrintService 상태 확인
adb shell settings get secure enabled_print_services
# 기대: com.betona.printdriver/com.betona.printdriver.LibroPrintService

# WRITE_SECURE_SETTINGS 권한 확인
adb shell dumpsys package com.betona.printdriver | grep WRITE_SECURE
# 기대: android.permission.WRITE_SECURE_SETTINGS: granted=true

# 서버 동작 확인
adb logcat -d -s WebServerService IppServer RawPrintServer
```

#### 4단계: 재부팅 테스트 (권장)

```bash
adb reboot
# 부팅 완료 후:
adb shell settings get secure enabled_print_services
# → 자동 복원되었는지 확인

adb logcat -d -s BootReceiver
# 기대: Settings API: enabled=...LibroPrintService ok=true
```

**끝!** JY-P1000은 시스템 패치가 필요 없습니다.

---

## B. A40i (Android 7) — 전체 셋업

### 사전 조건

- ADB가 설치된 PC
- USB 케이블로 기기 연결
- 기기에서 **설정 → 개발자 옵션 → USB 디버깅** 활성화

### 0단계: 기기 연결 확인

```bash
adb devices -l
# 출력 예: 36fa3f54813ab604  device product:a40_JYA40i model:QUAD-CORE_A40i_JYA40i

# Android 버전 확인
adb -s <시리얼> shell getprop ro.build.version.release
# 출력: 7.1.1

# root 접근 확인
adb -s <시리얼> root
adb -s <시리얼> remount
```

### 1단계: PrintSpooler 패치 (최초 1회)

A40i의 기본 PrintSpooler에는 mdpi 기기에서 크래시하는 버그가 있습니다.

**방법 A: 미리 패치된 PrintSpooler APK가 있는 경우**

```bash
adb root && adb remount

# 원본 백업
adb shell cp /system/app/PrintSpooler/PrintSpooler.apk /sdcard/PrintSpooler-backup.apk

# 패치된 APK 교체
adb push PrintSpooler_patched.apk /sdcard/
adb shell cp /sdcard/PrintSpooler_patched.apk /system/app/PrintSpooler/PrintSpooler.apk
adb shell chmod 644 /system/app/PrintSpooler/PrintSpooler.apk
```

> **패치된 PrintSpooler가 없는 경우**: `patches/android7/ANDROID7_PATCH_GUIDE.md`의 "패치 4" 참고

**위치 권한 부여** (PrintSpooler 크래시 방지):

```bash
adb shell pm grant com.android.printspooler android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant com.android.printspooler android.permission.ACCESS_FINE_LOCATION
```

### 2단계: WebView 업그레이드 (최초 1회)

기본 WebView (Chromium 52)은 최신 웹사이트를 렌더링하지 못합니다. Chrome 119를 WebView 프로바이더로 등록합니다.

```bash
adb root && adb remount

# 원본 백업 (필수!)
adb shell cp /system/framework/framework-res.apk /sdcard/framework-res-backup.apk

# framework-res.apk 가져오기
adb exec-out "cat /system/framework/framework-res.apk" > framework-res.apk

# 패치 적용 (PC에서)
cd patches/android7
python patch_framework_res.py ../../framework-res.apk config_webview_packages_patched.bin

# 패치된 APK 설치
adb push ../../framework-res-patched.apk /sdcard/
adb shell cp /sdcard/framework-res-patched.apk /system/framework/framework-res.apk
adb shell chmod 644 /system/framework/framework-res.apk

# WebView 프로바이더 변경
adb shell settings put global webview_provider com.android.chrome
```

### 3단계: 부트 스크립트 설치 (최초 1회)

PrintService 설정이 재부팅 시 초기화되는 문제를 해결합니다.

```bash
adb root && adb remount

# 스크립트 파일 푸시
adb push patches/android7/configure_print.sh /sdcard/
adb push patches/android7/printdriver.rc /sdcard/

# 시스템에 설치
adb shell cp /sdcard/configure_print.sh /system/bin/configure_print.sh
adb shell chmod 755 /system/bin/configure_print.sh

adb shell mkdir -p /system/etc/init
adb shell cp /sdcard/printdriver.rc /system/etc/init/printdriver.rc
adb shell chmod 644 /system/etc/init/printdriver.rc
```

### 4단계: 앱 설치

```bash
# 프로젝트 루트에서
cd LibroPrintDriver
./gradlew :app:assembleA40Debug

adb -s <시리얼> install -r app/build/outputs/apk/a40/debug/app-a40-debug.apk
```

### 5단계: 재부팅 & 확인

```bash
adb reboot

# === 부팅 후 확인 사항 ===

# 1) PrintService 활성화 확인
adb shell settings get secure enabled_print_services
# 기대 출력: com.android.printdriver/com.betona.printdriver.LibroPrintService

# 2) WebView 프로바이더 확인
adb shell settings get global webview_provider
# 기대 출력: com.android.chrome

# 3) 부트 스크립트 로그 확인
adb shell logcat -d | grep PrintConfig
# 기대 출력: PrintConfig: Print service configured

# 4) 앱 실행
adb shell am start -n com.android.printdriver/com.betona.printdriver.WebPrintActivity

# 5) 서버 동작 확인
adb logcat -d -s WebServerService IppServer RawPrintServer WebManagementServer
```

### 6단계: 인쇄 테스트

1. 앱 내 WebView에서 페이지 열기
2. 관리자 → 테스트 인쇄 실행
3. 웹 관리 접속: `http://<기기IP>:8080`

---

## C. 기존 기기에서 새 기기로 복제

이미 셋업된 A40i 기기가 있다면, 시스템 패치 파일을 복사할 수 있습니다.

```bash
# 기존 기기에서 패치된 파일 추출
adb -s <기존시리얼> pull /system/app/PrintSpooler/PrintSpooler.apk PrintSpooler_patched.apk
adb -s <기존시리얼> pull /system/framework/framework-res.apk framework-res-patched.apk
adb -s <기존시리얼> pull /system/bin/configure_print.sh configure_print.sh
adb -s <기존시리얼> pull /system/etc/init/printdriver.rc printdriver.rc

# 새 기기에 적용
adb -s <새시리얼> root && adb -s <새시리얼> remount

adb -s <새시리얼> push PrintSpooler_patched.apk /system/app/PrintSpooler/PrintSpooler.apk
adb -s <새시리얼> shell chmod 644 /system/app/PrintSpooler/PrintSpooler.apk

adb -s <새시리얼> push framework-res-patched.apk /system/framework/framework-res.apk
adb -s <새시리얼> shell chmod 644 /system/framework/framework-res.apk

adb -s <새시리얼> push configure_print.sh /system/bin/configure_print.sh
adb -s <새시리얼> shell chmod 755 /system/bin/configure_print.sh

adb -s <새시리얼> shell mkdir -p /system/etc/init
adb -s <새시리얼> push printdriver.rc /system/etc/init/printdriver.rc
adb -s <새시리얼> shell chmod 644 /system/etc/init/printdriver.rc

adb -s <새시리얼> shell settings put global webview_provider com.android.chrome
adb -s <새시리얼> shell pm grant com.android.printspooler android.permission.ACCESS_COARSE_LOCATION
adb -s <새시리얼> shell pm grant com.android.printspooler android.permission.ACCESS_FINE_LOCATION

# packages.xml 인증서 업데이트 (PrintSpooler 서명이 바뀌었으므로)
# → 기존 기기의 /data/system/packages.xml에서 PrintSpooler 관련 sigs/keys 항목을 복사

# 앱 설치
adb -s <새시리얼> install -r app/build/outputs/apk/a40/debug/app-a40-debug.apk

# 재부팅
adb -s <새시리얼> reboot
```

---

## D. 빠른 참조: 자주 쓰는 명령어

```bash
# === 빌드 ===
./gradlew :app:assembleStandardDebug    # JY-P1000용
./gradlew :app:assembleA40Debug         # A40i용
./gradlew :app:assembleDebug            # 전체 빌드

# === 설치 ===
adb -s <시리얼> install -r app/build/outputs/apk/standard/debug/app-standard-debug.apk
adb -s <시리얼> install -r app/build/outputs/apk/a40/debug/app-a40-debug.apk

# === 실행 ===
adb -s <시리얼> shell am start -n com.betona.printdriver/.WebPrintActivity         # JY-P1000
adb -s <시리얼> shell am start -n com.android.printdriver/.WebPrintActivity         # A40i (주의: com.android.printdriver)

# === 로그 ===
adb -s <시리얼> logcat -s WebPrint WebServerService IppServer RawPrintServer LibroPrintService AndroidRuntime

# === PrintService 수동 활성화 ===
adb shell settings put secure enabled_print_services com.betona.printdriver/com.betona.printdriver.LibroPrintService    # JY-P1000
adb shell settings put secure enabled_print_services com.android.printdriver/com.betona.printdriver.LibroPrintService   # A40i

# === 재부팅 후 자동 복원 권한 (JY-P1000 / Android 11) ===
adb shell pm grant com.betona.printdriver android.permission.WRITE_SECURE_SETTINGS

# === 기기 IP 확인 ===
adb -s <시리얼> shell ip addr show | grep "inet "

# === 프린터 상태 ===
adb -s <시리얼> shell dumpsys print
```

---

## E. 트러블슈팅

### PrintService가 안 보임 / 비활성화

```bash
# enabled_print_services 확인
adb shell settings get secure enabled_print_services

# 비어있으면 수동 설정
adb shell settings put secure enabled_print_services <패키지명>/com.betona.printdriver.LibroPrintService
adb shell settings put secure disabled_print_services ""
```

### 재부팅 후 인쇄 드라이버 비활성화 (Android 11)

**원인**: Android 11에는 `su`가 없어 `BootCompletedReceiver`가 실패

**해결**:
```bash
# WRITE_SECURE_SETTINGS 권한 부여 (한 번만 하면 됨)
adb shell pm grant com.betona.printdriver android.permission.WRITE_SECURE_SETTINGS

# 인쇄 드라이버 활성화
adb shell settings put secure enabled_print_services com.betona.printdriver/com.betona.printdriver.LibroPrintService

# 확인
adb shell dumpsys package com.betona.printdriver | grep WRITE_SECURE
# → granted=true 확인

# 재부팅 후 자동 복원 테스트
adb reboot
# 부팅 후:
adb logcat -d -s BootReceiver | grep "Settings API"
# → Settings API: enabled=...LibroPrintService ok=true
```

### 재부팅 후 인쇄 드라이버 비활성화 (A40i / Android 7)

**원인**: 시스템이 매 부팅 시 `enabled_print_services` 초기화

**해결**: 부트 스크립트가 설치되었는지 확인
```bash
adb root && adb remount
adb shell ls -la /system/bin/configure_print.sh
adb shell ls -la /system/etc/init/printdriver.rc
# 없으면 B섹션 3단계 참고하여 설치
```

### 앱이 백그라운드에서 죽음 (A40i)

- 패키지명이 `com.android.printdriver`인지 확인 (A40 빌드 사용)
- `com.betona.printdriver`로 설치하면 BackgroundManagerService가 강제 종료함

### PrintSpooler 크래시 (A40i)

```bash
# 로그 확인
adb logcat -d | grep -i "printspooler\|Resources\$NotFoundException"

# PrintSpooler가 패치되었는지 확인
adb shell ls -la /system/app/PrintSpooler/
```

### WebView 렌더링 오류 (A40i)

```bash
# WebView 프로바이더 확인
adb shell settings get global webview_provider
# com.android.chrome 이어야 함

# Chrome 113 설치 확인
adb shell pm list packages | grep chrome
```

### 서버 접속 안됨

```bash
# 기기 IP 확인
adb shell ip addr show

# 포트 확인 (8080=웹관리, 6631=IPP, 9100=RAW)
adb shell netstat -tlnp | grep -E "8080|6631|9100"
```

### adb 연결 안됨 / server conflict

**증상**: `adb devices`가 빈 목록이거나 `error: cannot connect to daemon`

**원인**: 여러 adb.exe가 동시 실행되면 서버 충돌

**해결**:
```bash
# Windows
taskkill /F /IM adb.exe
# 잠시 대기 후
adb devices -l

# Git Bash
taskkill //F //IM adb.exe
```

### /dev/printer가 없음

- 감열 프린터 하드웨어가 연결되어 있는지 확인
- 기기 재부팅 후 재확인
- `adb shell ls -la /dev/printer` 로 확인

---

## F. APK 출력 경로

| 빌드 | 경로 |
|------|------|
| JY-P1000 Debug | `app/build/outputs/apk/standard/debug/app-standard-debug.apk` |
| JY-P1000 Release | `app/build/outputs/apk/standard/release/app-standard-release.apk` |
| A40i Debug | `app/build/outputs/apk/a40/debug/app-a40-debug.apk` |
| A40i Release | `app/build/outputs/apk/a40/release/app-a40-release.apk` |
| 폰용 플러그인 | `printplugin/build/outputs/apk/debug/printplugin-debug.apk` |
