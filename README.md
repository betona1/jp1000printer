# LibroPrintDriver

JY-P1000 키오스크 내장 3인치 감열 프린터용 Android PrintService 드라이버.

## 장치 정보

| 항목 | 내용 |
|------|------|
| 기기 | JY-P1000 (JV COMPANY) |
| SoC | RK3568 |
| OS | Android 11 |
| 프린터 경로 | `/dev/printer` |
| 인쇄 폭 | 576px (72 bytes/row), 75mm |
| 해상도 | 203 DPI |
| 밝기 | 1~8 (기본값 4) |
| 커터 | ESC/POS GS V 0 자동 절단 |

## 설치 및 설정

### 1. APK 설치

```bash
# 디버그 빌드
./gradlew assembleDebug

# 기기에 설치
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 2. PrintService 활성화

앱 설치 후 반드시 인쇄 서비스를 수동으로 활성화해야 합니다.

1. **설정** > **연결된 기기** > **인쇄** (또는 앱 내 **"인쇄 서비스 설정 열기"** 버튼)
2. **LibroPrintDriver** 항목을 찾아서 **ON** 으로 전환
3. 활성화되면 "JY-P1000 감열 프린터" 가 프린터 목록에 나타남

### 3. 인쇄 테스트

- 앱 메인 화면에서 **연결 테스트** 버튼으로 `/dev/printer` 연결 확인
- **이미지 인쇄 테스트**, **라벨 인쇄 테스트** 등으로 직접 인쇄 확인
- Chrome, PDF 뷰어 등에서 **인쇄** > **JY-P1000 감열 프린터** 선택하여 PrintService 인쇄 확인

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
├── LibroPrintService.kt    # Android PrintService (PDF→인쇄)
├── LibroDiscoverySession.kt # 프린터 검색 (75mm 용지 설정)
├── DevicePrinter.kt         # 프린터 I/O 싱글톤 (jyndklib 래핑)
├── EscPosCommands.kt        # ESC/POS 명령어 빌더
├── BitmapConverter.kt       # Bitmap→흑백 변환, 크롭, 트림
└── MainActivity.kt          # 설정 UI + 테스트 버튼

jyndklib/                    # 네이티브 라이브러리 (JY-P1000 전용)
└── jyNativeClass            # JNI: open, close, printString, rawData, feed 등
```

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
| compileSdk | 34 |
| targetSdk | 26 |
| minSdk | 24 |
| NDK (jyndklib) | 22.1.7171670 |

## 알려진 제한사항

- `jyPrinterClose()` 호출 시 네이티브 스택 오버플로우로 앱 크래시 발생 → 호출하지 않음
- `jyPrinterRawData()` 사용 후 `jyPrinterClose()` 호출 시 커트가 작동하지 않음 → 사용하지 않음
- `FileOutputStream`으로 `/dev/printer` 직접 쓰기 불가 (EINVAL) → 반드시 jyndklib 사용
- 프린터 fd는 프로세스 종료 시 OS에서 자동 해제
