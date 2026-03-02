#!/system/bin/sh
# ============================================================================
# A40i (Android 7) USB 설치 스크립트
# ============================================================================
# USB 메모리에 이 스크립트와 필요한 파일을 넣고 기기에서 직접 실행합니다.
#
# USB 메모리 구성:
#   /LibroPrintDriver/
#     install.sh                (이 스크립트)
#     app-a40-debug.apk         (키오스크 앱)
#     chrome113.apk             (Chrome 113 - WebView provider)
#     configure_print.sh        (부트 스크립트)
#     printdriver.rc            (init 서비스 정의)
#     config_webview_packages_patched.bin  (패치된 WebView config)
#
# 실행 방법:
#   1. USB를 기기에 연결
#   2. adb shell 또는 터미널 앱에서:
#      sh /storage/udisk/LibroPrintDriver/install.sh
#
#   USB 경로가 다른 경우:
#      sh /storage/udiskh/LibroPrintDriver/install.sh
#      sh /mnt/media_rw/udisk/LibroPrintDriver/install.sh
# ============================================================================

# USB 경로 자동 감지
SCRIPT_PATH="$(cd "$(dirname "$0")" && pwd)"
USB_DIR="$SCRIPT_PATH"

# 또는 수동으로 USB 경로 찾기
if [ ! -f "$USB_DIR/chrome113.apk" ]; then
    for dir in /storage/udisk/LibroPrintDriver \
               /storage/udiskh/LibroPrintDriver \
               /storage/udisk3/LibroPrintDriver \
               /mnt/media_rw/udisk/LibroPrintDriver \
               /mnt/media_rw/udiskh/LibroPrintDriver \
               /storage/usbdisk/LibroPrintDriver \
               /storage/usb0/LibroPrintDriver; do
        if [ -f "$dir/chrome113.apk" ]; then
            USB_DIR="$dir"
            break
        fi
    done
fi

echo "============================================"
echo "  A40i USB 설치 스크립트"
echo "============================================"
echo ""
echo "USB 경로: $USB_DIR"

# 파일 확인
MISSING=0
for f in app-a40-debug.apk chrome113.apk configure_print.sh printdriver.rc config_webview_packages_patched.bin; do
    if [ ! -f "$USB_DIR/$f" ]; then
        echo "ERROR: 파일 없음 - $f"
        MISSING=1
    fi
done
if [ "$MISSING" = "1" ]; then
    echo ""
    echo "USB에 필요한 파일이 없습니다."
    echo "LibroPrintDriver/ 폴더에 모든 파일을 복사하세요."
    exit 1
fi

echo "모든 파일 확인됨"
echo ""

# ── Step 1: 앱 설치 ──────────────────────────────────────────────────────

echo "[1/6] 앱 설치..."
pm install -r "$USB_DIR/app-a40-debug.apk"
if [ $? -eq 0 ]; then
    echo "  OK"
else
    echo "  WARNING: 앱 설치 실패 (이미 설치되어 있을 수 있음)"
fi

# ── Step 2: Chrome 113 설치 ───────────────────────────────────────────────

echo "[2/6] Chrome 113 설치..."
CHROME_VER=$(dumpsys package com.android.chrome 2>/dev/null | grep versionName | head -1)
case "$CHROME_VER" in
    *113.*)
        echo "  SKIP: Chrome 113 이미 설치됨"
        ;;
    *)
        pm uninstall com.android.chrome 2>/dev/null
        pm install -r "$USB_DIR/chrome113.apk"
        if [ $? -eq 0 ]; then
            echo "  OK"
        else
            echo "  ERROR: Chrome 설치 실패"
        fi
        ;;
esac

# ── Step 3: 시스템 마운트 ─────────────────────────────────────────────────

echo "[3/6] 시스템 파티션 마운트..."
mount -o rw,remount /system 2>/dev/null
if [ $? -eq 0 ]; then
    echo "  OK"
else
    echo "  WARNING: remount 실패 (이미 rw이거나 권한 부족)"
fi

# ── Step 4: 부트 스크립트 설치 ────────────────────────────────────────────

echo "[4/6] 부트 스크립트 설치..."
cp "$USB_DIR/configure_print.sh" /system/bin/configure_print.sh
chmod 755 /system/bin/configure_print.sh
mkdir -p /system/etc/init
cp "$USB_DIR/printdriver.rc" /system/etc/init/printdriver.rc
chmod 644 /system/etc/init/printdriver.rc
echo "  OK"

# ── Step 5: framework-res.apk 패치 ───────────────────────────────────────

echo "[5/6] WebView 패치..."

# 현재 config 크기 확인 (패치 여부 판단)
# 패치된 config는 540 bytes, 원본은 384 bytes
# 간단 체크: framework-res.apk 크기 비교 대신 직접 패치

# 백업
if [ ! -f /system/framework/framework-res.apk.bak ]; then
    cp /system/framework/framework-res.apk /system/framework/framework-res.apk.bak
    echo "  백업 생성: framework-res.apk.bak"
fi

# 바이너리 패치: config_webview_packages.xml을 교체
# Android 기기에는 Python이 없으므로 바이너리 검색/교체 방식 사용
# 원본 config (384 bytes) 패턴의 시작 부분을 찾아 교체

# 임시 디렉토리
WORK_DIR=/data/local/tmp/fwpatch
mkdir -p $WORK_DIR

# framework-res.apk를 임시로 복사
cp /system/framework/framework-res.apk $WORK_DIR/framework-res.apk

# ZIP 내부의 config 파일 교체 (Android의 toybox unzip/zip 사용)
cd $WORK_DIR
mkdir -p res/xml
cp "$USB_DIR/config_webview_packages_patched.bin" res/xml/config_webview_packages.xml

# Android 7에 zip 명령이 없을 수 있으므로 busybox 사용
if command -v busybox >/dev/null 2>&1; then
    # busybox zip으로 파일 교체
    busybox zip -j0 framework-res.apk res/xml/config_webview_packages.xml 2>/dev/null
    if [ $? -ne 0 ]; then
        # zip -j 안 되면 다른 방법
        cd $WORK_DIR
        busybox unzip -o framework-res.apk -d extracted/ 2>/dev/null
        if [ -d extracted/res/xml ]; then
            cp "$USB_DIR/config_webview_packages_patched.bin" extracted/res/xml/config_webview_packages.xml
            cd extracted
            busybox zip -r0 ../framework-res-patched.apk . 2>/dev/null
            cd $WORK_DIR
            cp framework-res-patched.apk /system/framework/framework-res.apk
            chmod 644 /system/framework/framework-res.apk
            echo "  OK (busybox unzip/zip)"
        else
            echo "  ERROR: busybox unzip 실패"
            echo "  PC에서 patch_framework_res.py를 사용하세요"
        fi
    else
        cp framework-res.apk /system/framework/framework-res.apk
        chmod 644 /system/framework/framework-res.apk
        echo "  OK (busybox zip)"
    fi
else
    echo "  WARNING: busybox 없음, framework-res 패치 스킵"
    echo "  PC에서 adb로 patch_framework_res.py를 실행하세요"
fi

# 정리
rm -rf $WORK_DIR

# ── Step 6: 설정 적용 ─────────────────────────────────────────────────────

echo "[6/6] 설정 적용..."
settings put global webview_provider com.android.chrome
settings put secure enabled_print_services com.android.printdriver/com.betona.printdriver.LibroPrintService
settings put secure disabled_print_services ""
pm grant com.android.printspooler android.permission.ACCESS_COARSE_LOCATION 2>/dev/null
pm grant com.android.printspooler android.permission.ACCESS_FINE_LOCATION 2>/dev/null
echo "  OK"

# ── 완료 ──────────────────────────────────────────────────────────────────

echo ""
echo "============================================"
echo "  설치 완료!"
echo "============================================"
echo ""
echo "재부팅합니다..."
sleep 2
reboot
