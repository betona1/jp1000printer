================================================================
  LibroPrintDriver USB 설치 방법
================================================================

이 폴더의 파일들을 USB에 복사하여 새 기기에 설치할 수 있습니다.
Android Studio나 개발 환경이 필요 없습니다.

================================================================
  사전 준비
================================================================

- Git Bash 설치 (Windows): https://git-scm.com
- USB 케이블로 기기 연결
- 기기에서 USB 디버깅 활성화

================================================================
  JY-P1000 (Android 11) 설치
================================================================

필요 파일:
  - adb.exe (+ AdbWinApi.dll, AdbWinUsbApi.dll)
  - setup_jyp1000.sh
  - app-standard-release.apk 또는 app-standard-debug.apk

설치 방법:

  1. Git Bash 실행 후 이 폴더로 이동:
     cd /c/Users/.../LibroPrintDriver

  2. 스크립트 실행:
     bash setup_jyp1000.sh

  3. 기기가 여러 대 연결된 경우:
     adb devices -l              # transport_id 확인
     bash setup_jyp1000.sh -t 1  # transport_id 지정

  4. 설치 후 자동 재부팅:
     bash setup_jyp1000.sh -r

설치 과정 (자동, 4단계):
  [1/4] 기기 연결 확인
  [2/4] 앱 설치
  [3/4] 인쇄 드라이버 활성화 + WRITE_SECURE_SETTINGS 권한 부여
  [4/4] 설치 확인

================================================================
  A40i (Android 7) 설치
================================================================

필요 파일:
  - adb.exe (+ AdbWinApi.dll, AdbWinUsbApi.dll)
  - setup_a40i.sh
  - app-a40-release.apk 또는 app-a40-debug.apk
  - chrome113.apk
  - PrintSpooler_patched.apk
  - configure_print.sh, printdriver.rc
  - patch_framework_res.py, config_webview_packages_patched.bin
  - Python 3 설치 필요 (framework-res 패치용)

설치 방법:

  1. Git Bash 실행 후 이 폴더로 이동
  2. 스크립트 실행:
     bash setup_a40i.sh

  3. 기기가 여러 대 연결된 경우:
     bash setup_a40i.sh -t 4     # transport_id 지정

  4. 설치 후 자동 재부팅:
     bash setup_a40i.sh -r

설치 과정 (자동, 8단계):
  [1/8] 기기 연결 확인
  [2/8] 앱 설치
  [3/8] Chrome 113 설치
  [4/8] 시스템 파티션 마운트 (root + remount)
  [5/8] PrintSpooler 패치
  [6/8] 부트 스크립트 설치
  [7/8] WebView 패치 (framework-res.apk)
  [8/8] 설정 적용
  → 재부팅

================================================================
  트러블슈팅
================================================================

[문제] "adb를 찾을 수 없습니다"
  → adb.exe를 이 폴더에 넣으세요 (AdbWinApi.dll, AdbWinUsbApi.dll 포함)

[문제] 기기에 연결할 수 없음
  → USB 케이블 확인
  → 기기에서 "USB 디버깅 허용" 팝업 승인
  → adb devices -l 로 기기 목록 확인

[문제] 기기가 여러 대 연결됨
  → adb devices -l 로 transport_id 확인
  → bash setup_xxx.sh -t <ID>

[문제] adb server conflict (error: cannot connect to daemon)
  → Git Bash: taskkill //F //IM adb.exe
  → CMD: taskkill /F /IM adb.exe
  → 잠시 대기 후 다시 실행

[문제] 재부팅 후 인쇄 드라이버 비활성화 (Android 11)
  → WRITE_SECURE_SETTINGS 권한이 부여되었는지 확인:
    adb shell dumpsys package com.betona.printdriver | grep WRITE_SECURE
  → granted=true가 아니면:
    adb shell pm grant com.betona.printdriver android.permission.WRITE_SECURE_SETTINGS

[문제] 재부팅 후 인쇄 드라이버 비활성화 (A40i)
  → 부트 스크립트가 설치되었는지 확인:
    adb shell ls /system/bin/configure_print.sh
    adb shell ls /system/etc/init/printdriver.rc
  → 없으면 setup_a40i.sh 재실행

[문제] PrintSpooler "중단됨" (A40i)
  → PrintSpooler_patched.apk가 설치되었는지 확인
  → setup_a40i.sh가 자동으로 패치함

[문제] 웹 페이지 안 나옴 (A40i)
  → WiFi가 연결되어 있는지 확인
  → Chrome 113이 설치되었는지 확인
  → framework-res.apk가 패치되었는지 확인

================================================================
  파일 다운로드
================================================================

GitHub 릴리즈에서 다운로드:
https://github.com/betona1/jp1000printer/releases/latest

- app-a40-release.apk    : A40i용 키오스크 앱
- app-standard-debug.apk : JY-P1000용 키오스크 앱
- chrome113.apk           : Chrome 113 (A40i WebView 업그레이드용)

나머지 파일은 Git 저장소의 patches/android7/ 에 있습니다.
