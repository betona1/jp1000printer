# LibroPrintDriver 기술 문서

## 목차

1. [시스템 개요](#1-시스템-개요)
2. [아키텍처](#2-아키텍처)
3. [프린터 I/O](#3-프린터-io)
4. [네트워크 인쇄 서버](#4-네트워크-인쇄-서버)
5. [웹 관리 인터페이스](#5-웹-관리-인터페이스)
6. [관리자 설정](#6-관리자-설정)
7. [게임 기능](#7-게임-기능)
8. [인쇄 플러그인 (printplugin)](#8-인쇄-플러그인-printplugin)
9. [빌드 및 배포](#9-빌드-및-배포)
10. [문제 해결](#10-문제-해결)

---

## 1. 시스템 개요

LibroPrintDriver는 학교 도서관용 키오스크에 내장된 3인치(75mm) 감열 프린터를 구동하는 Android 앱입니다.

### 지원 기기

| 항목 | JY-P1000 | A40i |
|------|----------|------|
| SoC | RK3568 | Allwinner A40i |
| OS | Android 11 | Android 7.1.1 |
| DPI | 320 (hdpi) | 160 (mdpi) |
| 패키지명 | `com.betona.printdriver` | `com.android.printdriver` |
| 빌드 플레이버 | `standard` | `a40` |

### 핵심 기능

- **Android PrintService**: 시스템 인쇄 프레임워크 통합 (Chrome, PDF 등에서 인쇄)
- **네트워크 인쇄 서버**: 웹(HTTP:8080), RAW(TCP:9100), IPP(HTTP:6631) 3종 서버
- **웹 관리**: 브라우저에서 인쇄 및 기기 설정
- **게임 기능**: 사다리 게임, 빙고 게임 (인쇄 연동)
- **스마트폰 인쇄**: 별도 printplugin APK로 72mm 용지 인쇄

---

## 2. 아키텍처

### 전체 구조

```
┌─────────────────────────────────────────────────────────────┐
│                     키오스크 (Android)                        │
│                                                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐   │
│  │ WebPrint     │  │ MainActivity │  │ Game Activities  │   │
│  │ Activity     │  │ (관리자설정) │  │ (사다리/빙고)    │   │
│  │ (홈화면)     │  │              │  │                  │   │
│  └──────────────┘  └──────────────┘  └──────────────────┘   │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │              WebServerService (Foreground)            │   │
│  │  ┌────────────┐ ┌────────────┐ ┌──────────────┐     │   │
│  │  │ WebServer  │ │ RAW Server │ │  IPP Server  │     │   │
│  │  │ :8080      │ │ :9100      │ │  :6631       │     │   │
│  │  └────────────┘ └────────────┘ └──────────────┘     │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │              LibroPrintService (PrintService)         │   │
│  └──────────────────────────────────────────────────────┘   │
│                           │                                  │
│  ┌──────────────────────────────────────────────────────┐   │
│  │              DevicePrinter (Singleton)                 │   │
│  │              jyndklib → /dev/printer                   │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
          │                    ▲                    ▲
          ▼                    │                    │
    ┌──────────┐        ┌──────────┐        ┌──────────┐
    │ 감열     │        │ Windows  │        │ 스마트폰 │
    │ 프린터   │        │ PC       │        │ (Plugin) │
    └──────────┘        └──────────┘        └──────────┘
```

### 주요 컴포넌트

| 컴포넌트 | 파일 | 역할 |
|----------|------|------|
| **WebPrintActivity** | `WebPrintActivity.kt` | 홈 화면. WebView로 학교 사이트 표시, 툴바(홈/인쇄/설정/게임) |
| **MainActivity** | `MainActivity.kt` | 관리자 설정. 시스템 상태, 설정, 테스트 인쇄 (비밀번호 보호) |
| **LibroPrintService** | `LibroPrintService.kt` | Android PrintService. PDF → Bitmap → ESC/POS 인쇄 |
| **DevicePrinter** | `DevicePrinter.kt` | 프린터 I/O 싱글톤. jyndklib 네이티브 래핑 |
| **WebServerService** | `web/WebServerService.kt` | Foreground Service. 웹/RAW/IPP 서버 호스팅 |
| **AppPrefs** | `AppPrefs.kt` | SharedPreferences 기반 설정 관리 |

---

## 3. 프린터 I/O

### 프린터 사양

| 항목 | 값 |
|------|-----|
| 경로 | `/dev/printer` |
| 인쇄 폭 | 576px (72 bytes/row) |
| 용지 폭 | 75mm (3인치) |
| 해상도 | 203 DPI |
| 밝기 | 1~8 (기본값 4) |
| 절단 | ESC/POS GS V 0 (전체/부분 절단) |

### 인쇄 흐름 (PrintService)

```
PDF (PrintJob)
    │
    ▼ PdfRenderer
Bitmap (ARGB_8888, 576px 폭)
    │
    ├─ cropWhiteBorders()      ← Chrome 여백 제거
    ├─ scaleToWidth(576)       ← 인쇄 폭 맞춤
    ├─ toMonochrome()          ← 1bpp 흑백 변환 (휘도 128)
    └─ trimTrailingWhiteRows() ← 하단 빈줄 제거
    │
    ▼ ESC/POS
GS v 0 (래스터 헤더) + 4KB 청크 데이터
    │
    ▼ jyPrintString()
/dev/printer
```

### jyndklib 네이티브 메서드

| 메서드 | 용도 | 비고 |
|--------|------|------|
| `jyPrinterOpen()` | 프린터 열기 | 싱글톤, 앱 시작 시 1회 |
| `jyPrintString(byte[], int)` | 모든 데이터 전송 | 명령/텍스트/이미지 모두 사용 |
| `jyPrinterFeed(int, int)` | 용지 이송 | |
| `jyPrinter_PaperCheck()` | 용지 상태 | |
| `jyPrinter_CoverCheck()` | 커버 상태 | |
| `jyPrinter_OverheatCheck()` | 과열 상태 | |
| `jyPrinterClose()` | **사용 금지** | 스택 오버플로우 크래시 |
| `jyPrinterRawData(byte[])` | **사용 금지** | 절단 기능 손상 |

### 절단 방식

```kotlin
// 전체 절단
DevicePrinter.write(EscPosCommands.feedLines(4))
DevicePrinter.write(EscPosCommands.fullCut())    // GS V 0x00

// 부분 절단
DevicePrinter.write(EscPosCommands.feedLines(4))
DevicePrinter.write(EscPosCommands.partialCut())  // GS V 0x01
```

---

## 4. 네트워크 인쇄 서버

### WebServerService

`WebServerService`는 Foreground Service로 3개의 인쇄 서버를 호스팅합니다.
각 서버는 관리자 설정에서 개별 토글 가능합니다.

### 4.1 웹 관리 서버 (HTTP :8080)

**기술 스택**: NanoHTTPD

**기능**:
- 파일 업로드 인쇄 (이미지, PDF, 텍스트)
- 텍스트 직접 입력 인쇄
- 기기 상태 조회 (IP, 프린터 상태, 서버 상태)
- 기기 설정 변경 (학교 URL, 절단 모드, 밝기 등)
- 비밀번호 인증 (JWT 토큰)

**REST API 엔드포인트**:

| 경로 | 메서드 | 설명 |
|------|--------|------|
| `/` | GET | 웹 관리 UI (HTML/CSS/JS) |
| `/api/auth/login` | POST | 로그인 (비밀번호 인증) |
| `/api/device/info` | GET | 기기 정보 (IP, 버전, 프린터 상태) |
| `/api/device/settings` | GET/POST | 설정 조회/변경 |
| `/api/printer/print` | POST | 파일 인쇄 (multipart) |
| `/api/printer/text` | POST | 텍스트 인쇄 |
| `/api/printer/status` | GET | 프린터 상태 |
| `/api/printer/cut` | POST | 용지 절단 |

### 4.2 RAW 인쇄 서버 (TCP :9100)

Windows 네트워크 프린터용 TCP 소켓 서버입니다.

**동작 원리**:
1. PC에서 TCP :9100으로 ESC/POS 또는 래스터 데이터 전송
2. 수신 데이터를 그대로 `jyPrintString()`으로 프린터에 전달
3. 후처리: 후행 공백/폼피드 트리밍

**Windows PC 설정 방법**:
1. 제어판 → 장치 및 프린터 → 프린터 추가
2. "TCP/IP 주소로 프린터 추가" 선택
3. 호스트 이름/IP 주소: `<키오스크 IP>` 입력
4. 포트: `9100`
5. 드라이버: "Generic / Text Only" 선택

### 4.3 IPP 인쇄 서버 (HTTP :6631)

Internet Printing Protocol(IPP) 서버입니다. 스마트폰 인쇄 플러그인과 연동됩니다.

**지원 IPP 동작**:
- `Get-Printer-Attributes`: 프린터 속성 조회
- `Print-Job`: 인쇄 작업 수신 (PDF/이미지)
- `Validate-Job`: 인쇄 작업 유효성 검증
- `Get-Jobs`: 작업 목록 조회

**mDNS 등록**: `_ipp._tcp` 서비스로 네트워크에 자동 등록 → 스마트폰에서 자동 검색

**용지 크기**: 4x6인치 (102x152mm) 기본 설정

> 참고: Android 기본 포트 631은 privileged port (EACCES)이므로 6631 사용

---

## 5. 웹 관리 인터페이스

### 접속 방법

브라우저에서 `http://<키오스크 IP>:8080` 접속

### 기능

| 기능 | 설명 |
|------|------|
| 파일 인쇄 | 이미지(JPG/PNG) 또는 PDF 파일 업로드하여 인쇄 |
| 텍스트 인쇄 | 텍스트 직접 입력하여 인쇄 |
| 기기 상태 | IP 주소, 앱 버전, 프린터 상태, 서버 상태 표시 |
| 설정 변경 | 학교 URL, 절단 모드, 밝기 등 원격 변경 |
| 인증 | 비밀번호 기반 로그인 (JWT 토큰) |

### 인증 방식

- `AuthManager.kt`에서 JWT 토큰 발행
- 기본 비밀번호: `1234` (관리자 설정에서 변경 가능)
- 토큰은 24시간 유효

---

## 6. 관리자 설정

### 접속 방법

- 홈 화면 좌측 상단 설정 아이콘 클릭
- 비밀번호 입력 (기본: `1234`)
- 게임 화면에서 설정 아이콘 → 항상 비밀번호 재입력 필요

### 시스템 탭

| 항목 | 설명 |
|------|------|
| IP 주소 / 서버 상태 | 기기 IP, 각 서버(웹/RAW/IPP) 실행 상태 및 접속 주소 |
| 서버 토글 | 웹 관리(:8080), RAW 인쇄(:9100), IPP 인쇄(:6631) 개별 ON/OFF |
| 사용 매뉴얼 | PC/스마트폰/브라우저 인쇄 접속 방법 안내 |
| 프린터 상태 | 용지, 커버, 과열 상태 확인 |
| 기기 정보 | 모델명, Android 버전, 앱 버전, 빌드 플레이버 |

### 설정 탭

| 항목 | 기본값 | 설명 |
|------|--------|------|
| **프린터 접속 정보** | - | IP 주소, 서버별 접속 URL, PC/스마트폰/브라우저 인쇄 방법 안내 |
| 학교주소 설정 | read365.edunet.net | WebView 홈페이지 URL |
| 부팅 후 자동실행 | OFF | 부팅 시 앱 자동 시작 (init.rc 스크립트 설치) |
| 홈 화면 종료버튼 | OFF | 홈 화면에 종료 버튼 표시 |
| 홈 화면 시작/종료시간 | OFF | 홈 화면에 오늘 운영 시간 표시 |
| 절단 모드 | 전체절단 | 전체절단/부분절단 선택 |
| 홈 화면 시계 | ON | 홈 화면에 디지털 시계 표시 |
| 화면전환 표시 | OFF | 세로/가로 전환 버튼 표시 |
| 홈 화면 게임 표시 | OFF | 사다리/빙고 게임 버튼 표시 |
| 야간 절전모드 | ON | 주간 활성시간 외 화면 어둡게 |
| 요일별 운영 일정 | - | 요일별 시작/종료 시간 개별 설정 |

### AppPrefs 설정 키

| 키 | 타입 | 기본값 | 설명 |
|----|------|--------|------|
| `school_url` | String | `https://read365.edunet.net/SchoolSearch` | 홈페이지 URL |
| `auto_start` | Boolean | false | 부팅 자동실행 |
| `show_power_btn` | Boolean | false | 종료 버튼 표시 |
| `show_schedule` | Boolean | false | 운영 시간 표시 |
| `admin_password` | String | `1234` | 관리자 비밀번호 |
| `cut_mode` | String | `full` | 절단 모드 (full/partial) |
| `mobile_mode` | Boolean | true | 모바일 모드 |
| `show_clock` | Boolean | true | 시계 표시 |
| `landscape_mode` | Boolean | false | 가로 모드 |
| `show_rotate_btn` | Boolean | false | 화면전환 버튼 |
| `show_games` | Boolean | false | 게임 버튼 표시 |
| `night_save_mode` | Boolean | true | 야간 절전 |
| `sched_{day}_on/sh/sm/eh/em` | Mixed | - | 요일별 스케줄 |

---

## 7. 게임 기능

### 7.1 사다리 게임 (LadderGameActivity)

**파일**: `LadderGameActivity.kt`, `LadderGenerator.kt`, `LadderView.kt`

**기능**:
- 2~10명 참가자 설정
- 참가자별 결과 직접 입력 (예: "1등상", "꽝" 등)
- 랜덤 사다리 생성 (가로 다리 자동 배치)
- Canvas 기반 사다리 렌더링 (컬러, 애니메이션)
- 참가자 선택 → 경로 추적 → 결과 공개
- 사다리 전체 인쇄 (감열 프린터)

**인쇄 사양**:
- `LadderView.toBitmap(576)` → 576px 폭 흑백 비트맵 생성
- `BitmapConverter.toMonochrome()` → 1bpp 변환
- 부분/전체 절단 선택 가능

### 7.2 빙고 게임 (BingoGameActivity)

**파일**: `BingoGameActivity.kt`, `BingoGenerator.kt`, `BingoCardView.kt`

#### 게임 진행 (3단계)

**Phase 1 - 설정**:
- 그리드 크기 선택: 3x3 / 4x4 / 5x5
- 숫자 범위: 기본 자동 (1~9, 1~16, 1~25) + 커스텀 (시작~끝 입력)
- 참가자 수: 1~30명

**Phase 2 - 카드 확인 + 인쇄**:
- 생성된 모든 카드를 화면에 표시 (LazyVerticalGrid)
- "전체 출력" 버튼 → 카드 1장씩 부분절단으로 인쇄
- 각 카드는 BingoCardView로 Canvas 렌더링

**Phase 3 - 번호 추첨**:
- 빙고 조건 설정: 1줄 / 2줄 / 3줄 (gridSize에 따라 최대값 조정)
- "자동 추첨" 버튼 → 랜덤 번호 1개 추첨
- "번호를 선택하세요" → 수동 번호 선택 다이얼로그
- 추첨된 번호: 각 카드에 빨간 동그라미 표시
- 빙고 라인 완성 시: 색상별 줄긋기 (분홍/파랑/초록/주황/보라)
- 카드별 빙고 달성 현황: FlowRow에 "#N: X줄" 표시
- 당첨 발생 시: AlertDialog 알림 + 당첨 카드 자동 인쇄

#### BingoGenerator 핵심 로직

```kotlin
class BingoGenerator(gridSize: Int, numberRange: IntRange, playerCount: Int) {
    val cards: List<List<Int>>           // 카드별 숫자 배열
    val drawnNumbers: MutableList<Int>   // 추첨된 번호
    val availableNumbers: MutableList<Int>

    fun drawNumber(): Int?               // 자동 추첨
    fun drawSpecificNumber(num: Int): Boolean  // 수동 추첨
    fun getCompletedLines(cardIdx: Int): List<List<Int>>  // 완성된 줄 (셀 인덱스)
    fun getWinners(requiredLines: Int): List<Int>  // 당첨자 목록
}
```

**빙고 판정**: 가로(gridSize줄) + 세로(gridSize줄) + 대각선(2줄) = 최대 2*gridSize+2줄

#### BingoCardView 렌더링

**화면 표시** (`onDraw`):
- 사각 그리드 (굵은 외곽선, 얇은 내부선)
- 숫자 가운데 정렬 (나눔고딕 폰트)
- 추첨된 번호: 반투명 빨간 원 + 빨간 테두리
- 완성된 줄: 색상별 줄긋기 (셀 중심 약간 넘어서)

**인쇄용** (`renderToBitmap` static 메서드):
- View 없이 직접 Canvas에 그리기 (Android 7 호환)
- 576px 폭으로 스케일링
- 줄긋기 포함

> **Android 7 호환**: BingoCardView의 인쇄는 static `renderToBitmap()` 메서드를 사용합니다.
> View를 백그라운드 스레드에서 생성하면 Android 7에서 Looper 관련 크래시가 발생하기 때문입니다.

---

## 8. 인쇄 플러그인 (printplugin)

별도 모듈로, 스마트폰에 설치하여 키오스크 프린터로 인쇄할 수 있게 합니다.

### 개요

| 항목 | 값 |
|------|-----|
| 패키지명 | `com.betona.libroprintplugin` |
| 용지 크기 | 72mm (2835 mils) |
| 프로토콜 | IPP (Print-Job) |
| 검색 | mDNS `_ipp._tcp` |

### 동작 흐름

```
스마트폰 앱 (Chrome, 갤러리 등)
    │ PrintJob
    ▼
LibroNetPrintService         ← Android PrintService (72mm 용지)
    │ PDF 생성
    ▼
IppClient                   ← IPP Print-Job 전송
    │ HTTP POST
    ▼
키오스크 IppServer (:6631)   ← IPP 수신 + 감열 인쇄
```

### 핵심 포인트

- **72mm 용지**: 일반 A4(210mm)로 인쇄하면 34% 축소되어 글자가 작아짐. 72mm 전용 플러그인이 원본 크기 유지
- **mDNS 검색**: `_ipp._tcp` 서비스 중 "LibroPrinter" 접두사를 가진 프린터 자동 검색
- **메인 스레드 필수**: `generatePrinterId()` / `addPrinters()`는 반드시 메인 스레드에서 호출

상세: [printplugin/TECHNICAL_DOC.md](printplugin/TECHNICAL_DOC.md)

---

## 9. 빌드 및 배포

### 빌드 명령

```bash
# 전체 빌드 (standard + a40)
./gradlew :app:assembleDebug

# JY-P1000 전용
./gradlew :app:assembleStandardDebug

# A40i 전용
./gradlew :app:assembleA40Debug

# 인쇄 플러그인
./gradlew :printplugin:assembleDebug
```

### 설치 명령

```bash
# JY-P1000 (standard)
adb -s <serial> install -r app/build/outputs/apk/standard/debug/app-standard-debug.apk

# A40i (a40)
adb -s <serial> install -r app/build/outputs/apk/a40/debug/app-a40-debug.apk

# 인쇄 플러그인 (스마트폰)
adb install -r printplugin/build/outputs/apk/debug/printplugin-debug.apk
```

### APK 출력 경로

| 플레이버 | 경로 |
|----------|------|
| standard debug | `app/build/outputs/apk/standard/debug/app-standard-debug.apk` |
| a40 debug | `app/build/outputs/apk/a40/debug/app-a40-debug.apk` |
| printplugin | `printplugin/build/outputs/apk/debug/printplugin-debug.apk` |

### 빌드 환경

| 항목 | 버전 |
|------|------|
| Gradle | 8.4 |
| Android Gradle Plugin | 8.3.0 |
| Kotlin | 1.9.0 |
| Jetpack Compose BOM | 2023.10.01 |
| compileSdk | 34 |
| targetSdk | 26 |
| minSdk | 24 |

---

## 10. 문제 해결

### IP 주소가 표시되지 않음

앱은 `NetworkInterface`를 통해 IPv4 주소를 가져옵니다 (WiFi/이더넷 모두 지원).
"N/A" 표시 시:
- 네트워크 연결 상태 확인
- `adb shell ifconfig` 또는 `ip addr`로 인터페이스 확인

### A40i BackgroundManagerService

A40i 기기는 `com.android` 접두사가 아닌 앱의 서비스를 강제 종료합니다.
→ 빌드 플레이버 `a40` 사용 (`com.android.printdriver` 패키지명)

### A40i PrintSpooler 크래시

mdpi(160dpi)에서 drawable XML 자기참조 문제.
→ 패치된 PrintSpooler.apk 설치 필요 (상세: `patches/android7/ANDROID7_PATCH_GUIDE.md`)

### Android 7 빙고 인쇄 크래시

View를 백그라운드 스레드에서 생성 시 Looper 크래시.
→ `BingoCardView.renderToBitmap()` static 메서드 사용 (View 의존성 없음)

### jyPrinterClose() 크래시

네이티브 라이브러리의 스택 오버플로우 버그.
→ 호출하지 않음. fd는 프로세스 종료 시 OS에서 자동 해제.

### RAW 인쇄가 작동하지 않음

1. 서버 실행 상태 확인 (관리자 → 시스템 → RAW 인쇄 토글)
2. PC와 키오스크가 같은 네트워크에 있는지 확인
3. 방화벽에서 포트 9100 허용 확인
4. `telnet <키오스크IP> 9100`으로 연결 테스트

### IPP 인쇄 프린터가 검색되지 않음

1. 키오스크에서 IPP 서버 토글 ON 확인
2. 스마트폰과 키오스크가 같은 WiFi 확인
3. printplugin APK가 스마트폰에 설치되어 있는지 확인
4. 스마트폰 설정 → 인쇄 → LibroPrintPlugin 활성화 확인
