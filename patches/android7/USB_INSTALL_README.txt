================================================================
  A40i USB 설치 방법
================================================================

1. USB 메모리에 LibroPrintDriver 폴더를 만들고 아래 파일을 복사:

   USB:/LibroPrintDriver/
     ├── install.sh
     ├── app-a40-debug.apk
     ├── chrome113.apk
     ├── configure_print.sh
     ├── printdriver.rc
     └── config_webview_packages_patched.bin

2. USB를 A40i 기기에 연결

3. PC에서 adb shell로 실행:

   adb shell sh /storage/udisk/LibroPrintDriver/install.sh

   또는 기기에 터미널 앱이 있으면 직접:

   sh /storage/udisk/LibroPrintDriver/install.sh

   * USB 경로가 다른 경우 (udiskh, udisk3 등):
   adb shell sh /storage/udiskh/LibroPrintDriver/install.sh

4. 자동으로 재부팅됩니다. 약 30초 후 설치 완료.

================================================================
  확인사항
================================================================

- 웹 페이지가 정상 표시되는지 확인
- 관리자 > 상태 탭: PrintSpooler "설치됨", 드라이버 "활성화"
- 다른 스마트폰에서 인쇄 테스트

================================================================
  파일 다운로드
================================================================

GitHub 릴리즈에서 다운로드:
https://github.com/betona1/jp1000printer/releases/latest

- app-a40-debug.apk  : 키오스크 앱
- chrome113.apk      : Chrome 113 (WebView 업그레이드)

나머지 파일은 Git 저장소의 patches/android7/ 에 있습니다.
