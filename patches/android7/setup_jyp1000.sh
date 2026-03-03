#!/bin/bash
# ============================================================================
# JY-P1000 (Android 11) 자동 설치 스크립트 (Portable)
# ============================================================================
# 새 JY-P1000 기기에 LibroPrintDriver를 설치합니다.
#
# 사용법:
#   bash setup_jyp1000.sh                    # 기기가 1대만 연결된 경우
#   bash setup_jyp1000.sh -s SERIAL          # 시리얼 지정
#   bash setup_jyp1000.sh -t 4              # transport_id 지정
#   bash setup_jyp1000.sh -t 4 -r           # 설치 후 자동 재부팅
# ============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# ── ADB 경로 설정 (같은 폴더의 adb.exe 우선) ────────────────────────────────

if [ -f "$SCRIPT_DIR/adb.exe" ]; then
    ADB="$SCRIPT_DIR/adb.exe"
elif [ -f "$SCRIPT_DIR/adb" ]; then
    ADB="$SCRIPT_DIR/adb"
elif command -v adb &>/dev/null; then
    ADB=adb
elif [ -f "$ANDROID_HOME/platform-tools/adb" ]; then
    ADB="$ANDROID_HOME/platform-tools/adb"
elif [ -f "$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" ]; then
    ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
else
    echo "ERROR: adb를 찾을 수 없습니다."
    echo "  이 폴더에 adb.exe를 넣거나 PATH에 추가하세요."
    exit 1
fi

# ── 인자 파싱 ───────────────────────────────────────────────────────────────

ADB_TARGET=""
AUTO_REBOOT=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        -s) ADB_TARGET="-s $2"; shift 2 ;;
        -t) ADB_TARGET="-t $2"; shift 2 ;;
        -r) AUTO_REBOOT=1; shift ;;
        *)  echo "Usage: $0 [-s serial | -t transport_id] [-r]"; exit 1 ;;
    esac
done

adb_cmd() {
    $ADB $ADB_TARGET "$@"
}

# ── 필수 파일 확인 ──────────────────────────────────────────────────────────

echo "============================================"
echo "  JY-P1000 (Android 11) 설치 스크립트"
echo "============================================"
echo ""
echo "  ADB: $ADB"
echo ""

# app APK 찾기
APP_APK=""
if [ -f "app-standard-release.apk" ]; then
    APP_APK="app-standard-release.apk"
elif [ -f "app-standard-debug.apk" ]; then
    APP_APK="app-standard-debug.apk"
else
    echo "ERROR: app-standard APK를 찾을 수 없습니다."
    echo "  app-standard-release.apk 또는 app-standard-debug.apk를 이 디렉토리에 넣으세요."
    exit 1
fi

# ── 기기 연결 확인 ──────────────────────────────────────────────────────────

echo "[1/3] 기기 연결 확인..."
DEVICE_INFO=$(adb_cmd shell getprop ro.build.display.id 2>/dev/null || true)
if [ -z "$DEVICE_INFO" ]; then
    echo "ERROR: 기기에 연결할 수 없습니다. USB 연결과 adb 인가를 확인하세요."
    exit 1
fi
DEVICE_MODEL=$(adb_cmd shell getprop ro.product.model 2>/dev/null)
DEVICE_SDK=$(adb_cmd shell getprop ro.build.version.sdk 2>/dev/null)
PRINTER_EXISTS=$(adb_cmd shell "[ -e /dev/printer ] && echo YES || echo NO" 2>/dev/null)

echo "  기기: $DEVICE_MODEL"
echo "  Android SDK: $DEVICE_SDK"
echo "  /dev/printer: $PRINTER_EXISTS"
echo ""

if [ "$DEVICE_SDK" -lt 28 ] 2>/dev/null; then
    echo "WARNING: 이 스크립트는 Android 11+ (API 30+) 전용입니다. (현재: API $DEVICE_SDK)"
    echo "  A40i (Android 7)인 경우 setup_a40i.sh를 사용하세요."
    read -p "계속하시겠습니까? (y/N) " -n 1 -r
    echo
    [[ $REPLY =~ ^[Yy]$ ]] || exit 0
fi

# ── Step 1: 앱 설치 ─────────────────────────────────────────────────────────

echo "[2/4] 앱 설치... ($APP_APK)"
adb_cmd install -r "$APP_APK" 2>&1 | tail -1
echo "  앱 설치 완료"

# ── Step 2: 인쇄 드라이버 활성화 ─────────────────────────────────────────────

echo "[3/4] 인쇄 드라이버 활성화..."
adb_cmd shell "settings put secure enabled_print_services com.betona.printdriver/com.betona.printdriver.LibroPrintService" 2>&1
adb_cmd shell "settings put secure disabled_print_services ''" 2>&1
adb_cmd shell "pm grant com.android.printspooler android.permission.ACCESS_COARSE_LOCATION" 2>/dev/null || true
adb_cmd shell "pm grant com.android.printspooler android.permission.ACCESS_FINE_LOCATION" 2>/dev/null || true
echo "  인쇄 드라이버 활성화 완료"

# ── Step 3: 확인 ────────────────────────────────────────────────────────────

echo "[4/4] 설치 확인..."
INSTALLED=$(adb_cmd shell pm list packages com.betona.printdriver 2>/dev/null | grep "com.betona.printdriver" || echo "")
if [ -n "$INSTALLED" ]; then
    echo "  앱: 설치됨 (com.betona.printdriver)"
else
    echo "  WARNING: 앱 설치 확인 실패"
fi

# PrintService 활성화 확인
PRINT_SERVICE=$(adb_cmd shell settings get secure enabled_print_services 2>/dev/null || echo "")
if echo "$PRINT_SERVICE" | grep -q "LibroPrintService"; then
    echo "  인쇄 드라이버: 활성화"
else
    echo "  인쇄 드라이버: 미활성화 (WARNING)"
fi

echo "  /dev/printer: $PRINTER_EXISTS"

# ── 완료 ──────────────────────────────────────────────────────────────────────

echo ""
echo "============================================"
echo "  설치 완료!"
echo "============================================"
echo ""

if [ "$PRINTER_EXISTS" = "NO" ]; then
    echo "WARNING: /dev/printer가 없습니다. 이 기기에 감열 프린터가 연결되어 있는지 확인하세요."
    echo ""
fi

echo "인쇄 드라이버가 활성화되었습니다."
echo ""

if [ "$AUTO_REBOOT" = "1" ]; then
    REPLY="y"
else
    echo "재부팅하시겠습니까? (필수는 아님)"
    read -p "(y/N) " -n 1 -r
    echo
fi
if [[ $REPLY =~ ^[Yy]$ ]]; then
    adb_cmd reboot
    echo "재부팅 중..."
else
    echo "앱 실행: adb shell am start -n com.betona.printdriver/.WebPrintActivity"
fi
