#!/bin/bash
# ============================================================================
# A40i (Android 7) 자동 설치 스크립트
# ============================================================================
# 새 A40i 기기에 LibroPrintDriver 전체 환경을 한 번에 설치합니다.
#
# 사전 요구사항:
#   - ADB가 PATH에 있거나 ANDROID_HOME이 설정되어 있을 것
#   - A40i 기기가 USB로 연결되어 있을 것
#   - 이 스크립트가 patches/android7/ 디렉토리에서 실행될 것
#
# 사용법:
#   cd patches/android7
#   bash setup_a40i.sh                    # 기기가 1대만 연결된 경우
#   bash setup_a40i.sh -s 20080411        # 시리얼 지정
#   bash setup_a40i.sh -t 4              # transport_id 지정
# ============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# ── ADB 경로 설정 ──────────────────────────────────────────────────────────

if command -v adb &>/dev/null; then
    ADB=adb
elif [ -f "$ANDROID_HOME/platform-tools/adb" ]; then
    ADB="$ANDROID_HOME/platform-tools/adb"
elif [ -f "$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" ]; then
    ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
else
    echo "ERROR: adb를 찾을 수 없습니다. PATH에 추가하거나 ANDROID_HOME을 설정하세요."
    exit 1
fi

# ── 인자 파싱 ───────────────────────────────────────────────────────────────

ADB_TARGET=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        -s) ADB_TARGET="-s $2"; shift 2 ;;
        -t) ADB_TARGET="-t $2"; shift 2 ;;
        *)  echo "Usage: $0 [-s serial | -t transport_id]"; exit 1 ;;
    esac
done

adb_cmd() {
    $ADB $ADB_TARGET "$@"
}

# ── 필수 파일 확인 ──────────────────────────────────────────────────────────

echo "============================================"
echo "  A40i (Android 7) 자동 설치 스크립트"
echo "============================================"
echo ""

REQUIRED_FILES=(
    "configure_print.sh"
    "printdriver.rc"
    "config_webview_packages_patched.bin"
    "patch_framework_res.py"
    "chrome113.apk"
)

# app APK: 빌드된 것 또는 릴리즈 APK 사용
APP_APK=""
if [ -f "../../app/build/outputs/apk/a40/debug/app-a40-debug.apk" ]; then
    APP_APK="../../app/build/outputs/apk/a40/debug/app-a40-debug.apk"
elif [ -f "app-a40-release.apk" ]; then
    APP_APK="app-a40-release.apk"
else
    echo "WARNING: app-a40 APK를 찾을 수 없습니다."
    echo "  먼저 빌드하세요: ./gradlew :app:assembleA40Debug"
    echo "  또는 app-a40-release.apk를 이 디렉토리에 복사하세요."
fi

MISSING=0
for f in "${REQUIRED_FILES[@]}"; do
    if [ ! -f "$f" ]; then
        echo "ERROR: 필수 파일 없음: $f"
        MISSING=1
    fi
done
if [ $MISSING -eq 1 ]; then
    echo "필수 파일이 누락되었습니다. patches/android7/ 디렉토리를 확인하세요."
    exit 1
fi

# ── 기기 연결 확인 ──────────────────────────────────────────────────────────

echo "[1/7] 기기 연결 확인..."
DEVICE_INFO=$(adb_cmd shell getprop ro.build.display.id 2>/dev/null || true)
if [ -z "$DEVICE_INFO" ]; then
    echo "ERROR: 기기에 연결할 수 없습니다. USB 연결과 adb 인가를 확인하세요."
    exit 1
fi
DEVICE_MODEL=$(adb_cmd shell getprop ro.product.model 2>/dev/null)
DEVICE_SDK=$(adb_cmd shell getprop ro.build.version.sdk 2>/dev/null)
CHROME_VER=$(adb_cmd shell dumpsys package com.android.chrome 2>/dev/null | grep versionName | head -1 | tr -d ' ' || echo "없음")
WEBVIEW_VER=$(adb_cmd shell dumpsys package com.android.webview 2>/dev/null | grep versionName | head -1 | tr -d ' ' || echo "없음")

echo "  기기: $DEVICE_MODEL"
echo "  Android SDK: $DEVICE_SDK"
echo "  Chrome: $CHROME_VER"
echo "  WebView: $WEBVIEW_VER"
echo ""

if [ "$DEVICE_SDK" != "25" ] && [ "$DEVICE_SDK" != "24" ]; then
    echo "WARNING: 이 스크립트는 Android 7 (API 24-25) 전용입니다. (현재: API $DEVICE_SDK)"
    read -p "계속하시겠습니까? (y/N) " -n 1 -r
    echo
    [[ $REPLY =~ ^[Yy]$ ]] || exit 0
fi

# ── Step 1: 앱 설치 ─────────────────────────────────────────────────────────

echo "[2/7] 앱 설치..."
if [ -n "$APP_APK" ]; then
    adb_cmd install -r "$APP_APK" 2>&1 | tail -1
    echo "  앱 설치 완료: $APP_APK"
else
    echo "  SKIP: 앱 APK 없음 (수동 설치 필요)"
fi

# ── Step 2: Chrome 113 설치 ──────────────────────────────────────────────────

echo "[3/7] Chrome 113 설치..."
CURRENT_CHROME=$(adb_cmd shell dumpsys package com.android.chrome 2>/dev/null | grep "versionName=" | head -1 | sed 's/.*versionName=//' || echo "")
if [[ "$CURRENT_CHROME" == "113."* ]]; then
    echo "  SKIP: Chrome 113 이미 설치됨 ($CURRENT_CHROME)"
else
    # 기존 Chrome 제거 (서명 불일치 방지)
    adb_cmd shell pm uninstall com.android.chrome 2>/dev/null || true
    adb_cmd install -r chrome113.apk 2>&1 | tail -1
    echo "  Chrome 113 설치 완료"
fi

# ── Step 3: Root + Remount ───────────────────────────────────────────────────

echo "[4/7] 시스템 파티션 마운트..."
adb_cmd root 2>&1 | tail -1
sleep 1
# transport_id가 바뀔 수 있으므로 재연결 대기
adb_cmd wait-for-device 2>/dev/null || sleep 2
adb_cmd remount 2>&1 | tail -1

# ── Step 4: 부트 스크립트 설치 ───────────────────────────────────────────────

echo "[5/7] 부트 스크립트 설치..."
adb_cmd push configure_print.sh //data/local/tmp/configure_print.sh 2>&1 | tail -1
adb_cmd push printdriver.rc //data/local/tmp/printdriver.rc 2>&1 | tail -1
adb_cmd shell "cp /data/local/tmp/configure_print.sh /system/bin/configure_print.sh" 2>&1
adb_cmd shell "chmod 755 /system/bin/configure_print.sh" 2>&1
adb_cmd shell "mkdir -p /system/etc/init" 2>&1
adb_cmd shell "cp /data/local/tmp/printdriver.rc /system/etc/init/printdriver.rc" 2>&1
adb_cmd shell "chmod 644 /system/etc/init/printdriver.rc" 2>&1
echo "  부트 스크립트 설치 완료"

# ── Step 5: framework-res.apk 패치 ──────────────────────────────────────────

echo "[6/7] WebView 패치 (framework-res.apk)..."

# 현재 설치된 framework-res 가져오기
adb_cmd shell "cat /system/framework/framework-res.apk" > /tmp/framework-res-device.apk 2>/dev/null \
    || adb_cmd pull //system/framework/framework-res.apk /tmp/framework-res-device.apk 2>/dev/null

# 이미 패치됐는지 확인
CONFIG_SIZE=$(python3 -c "
import zipfile
z = zipfile.ZipFile('/tmp/framework-res-device.apk', 'r')
info = z.getinfo('res/xml/config_webview_packages.xml')
print(info.file_size)
z.close()
" 2>/dev/null || echo "0")

if [ "$CONFIG_SIZE" = "540" ]; then
    echo "  SKIP: framework-res.apk 이미 패치됨"
else
    # 백업
    adb_cmd shell "cp /system/framework/framework-res.apk /system/framework/framework-res.apk.bak" 2>&1

    # 패치
    python3 patch_framework_res.py /tmp/framework-res-device.apk config_webview_packages_patched.bin 2>&1 | grep -E "REPLACED|Original|Patched|Output"

    # 푸시
    PATCHED_APK="/tmp/framework-res-device-patched.apk"
    adb_cmd push "$PATCHED_APK" //data/local/tmp/framework-res-patched.apk 2>&1 | tail -1
    adb_cmd shell "cp /data/local/tmp/framework-res-patched.apk /system/framework/framework-res.apk" 2>&1
    adb_cmd shell "chmod 644 /system/framework/framework-res.apk" 2>&1
    echo "  framework-res.apk 패치 완료"
fi

# ── Step 6: 설정 적용 ────────────────────────────────────────────────────────

echo "[7/7] 설정 적용..."
adb_cmd shell "settings put global webview_provider com.android.chrome" 2>&1
adb_cmd shell "settings put secure enabled_print_services com.android.printdriver/com.betona.printdriver.LibroPrintService" 2>&1
adb_cmd shell "settings put secure disabled_print_services ''" 2>&1
adb_cmd shell "pm grant com.android.printspooler android.permission.ACCESS_COARSE_LOCATION" 2>/dev/null || true
adb_cmd shell "pm grant com.android.printspooler android.permission.ACCESS_FINE_LOCATION" 2>/dev/null || true
echo "  설정 적용 완료"

# ── 완료 ──────────────────────────────────────────────────────────────────────

echo ""
echo "============================================"
echo "  설치 완료!"
echo "============================================"
echo ""
echo "재부팅이 필요합니다. 지금 재부팅하시겠습니까?"
read -p "(y/N) " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    adb_cmd reboot
    echo "재부팅 중... 약 30초 후 기기가 시작됩니다."
    echo ""
    echo "부팅 후 확인사항:"
    echo "  1. 웹 페이지가 정상 표시되는지 확인"
    echo "  2. 관리자 → 상태 탭에서 PrintSpooler/드라이버 상태 확인"
    echo "  3. 다른 핸드폰에서 인쇄 테스트"
else
    echo "수동으로 재부팅하세요: adb reboot"
fi
