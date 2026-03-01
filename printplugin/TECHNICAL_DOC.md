# LibroPrinter 네트워크 인쇄 플러그인 — 기술 문서

## 1. 프로젝트 개요

### 1.1 목적
Android 기본 인쇄 서비스(BIPS/Mopria)는 커스텀 용지 크기를 지원하지 않아,
다른 폰에서 IPP로 인쇄 시 A4(210mm) 기준 렌더링 → 72mm 감열지에 축소되어
**글씨가 34% 크기로 줄어드는 문제** 발생.

이 플러그인은 별도 PrintService로서 **72mm 용지 크기를 정확히 보고**하여,
폰이 처음부터 72mm 폭에 맞게 콘텐츠를 레이아웃하도록 함.

### 1.2 인쇄 흐름
```
사용자 폰 (앱에서 인쇄)
    ↓
LibroPrinter 플러그인 (72mm 용지로 PDF 생성)
    ↓ IPP Print-Job (HTTP POST)
키오스크 IppServer (포트 6631)
    ↓ PdfRenderer → BitmapConverter
감열 프린터 (/dev/printer)
```

### 1.3 핵심 가치
| 항목 | BIPS/Mopria (기존) | LibroPrinter 플러그인 |
|---|---|---|
| 용지 크기 | A4 (210mm) | **72mm** |
| 축소 비율 | 34% (읽기 어려움) | **100% (원본 크기)** |
| 마진 | 시스템 기본값 | **0mm (전체 사용)** |
| 색상 | 컬러 (불필요) | **모노크롬** |


## 2. 프로젝트 구조

### 2.1 패키지 정보
- **Package**: `com.betona.libroprintplugin`
- **앱 이름**: LibroPrinter 플러그인
- **SDK**: minSdk=24, targetSdk=26, compileSdk=34
- **언어**: Kotlin 1.9.0
- **의존성**: androidx.core-ktx, androidx.appcompat (경량, ~3MB APK)
- **네이티브 코드**: 없음 (jyndklib 불필요)

### 2.2 파일 구조
```
printplugin/
├── build.gradle.kts
├── src/main/
│   ├── AndroidManifest.xml
│   ├── res/xml/
│   │   └── print_service_info.xml
│   └── java/com/betona/libroprintplugin/
│       ├── LibroNetPrintService.kt    # PrintService 진입점
│       ├── LibroNetDiscoverySession.kt # 프린터 탐색 + 용지 크기
│       └── IppClient.kt               # IPP 프로토콜 클라이언트
```


## 3. 별도 프로젝트 빌드 (Google Play 배포용)

### 3.1 Android Studio에서 새 프로젝트 생성

1. **File → New → New Project**
2. **"No Activity"** 템플릿 선택 (UI가 없는 서비스 전용 앱)
3. 설정:
   - Name: `LibroPrintPlugin`
   - Package name: `com.betona.libroprintplugin`
   - Save location: 원하는 경로
   - Language: **Kotlin**
   - Minimum SDK: **API 24 (Android 7.0)**
4. **Finish** 클릭

### 3.2 불필요한 파일 삭제

Android Studio가 자동 생성하는 아래 파일/폴더 삭제:
```
삭제 대상:
  src/main/res/layout/          ← UI 없으므로 불필요
  src/main/res/values/themes.xml ← 테마 불필요
  src/androidTest/              ← 계측 테스트 불필요 시
  src/test/                     ← 단위 테스트 불필요 시
```

### 3.3 build.gradle.kts (Module: app) 수정

기존 내용을 아래로 **전체 교체**:

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.betona.libroprintplugin"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.betona.libroprintplugin"
        minSdk = 24
        targetSdk = 34  // Google Play 요구사항 (2024.08~ 최소 34)
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
}
```

**설명:**
- `com.android.application`: 라이브러리가 아닌 설치 가능한 APK 생성
- `targetSdk = 34`: Google Play 업로드 최소 요구사항
- 의존성은 `core-ktx`와 `appcompat` 2개만 (경량 APK ~3MB)
- Compose, Material, jyndklib 등 **모두 불필요**

### 3.4 파일 생성/복사

아래 5개 파일을 생성합니다. 전체 소스 코드는 섹션 4에 있습니다.

```
복사할 파일 목록:

1. src/main/AndroidManifest.xml           ← 전체 교체
2. src/main/res/xml/print_service_info.xml ← 새로 생성 (xml 폴더도 생성)
3. src/main/java/com/betona/libroprintplugin/LibroNetPrintService.kt
4. src/main/java/com/betona/libroprintplugin/LibroNetDiscoverySession.kt
5. src/main/java/com/betona/libroprintplugin/IppClient.kt
```

### 3.5 빌드 및 테스트

```bash
# 디버그 빌드
./gradlew assembleDebug

# 빌드 결과물 위치
app/build/outputs/apk/debug/app-debug.apk

# ADB로 테스트 기기에 설치
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 인쇄 서비스 ADB로 강제 활성화 (선택사항, 설정에서 수동 활성화도 가능)
adb shell settings put secure enabled_print_services \
  com.betona.libroprintplugin/com.betona.libroprintplugin.LibroNetPrintService
```

### 3.6 릴리스 빌드 (Google Play 업로드용)

#### 3.6.1 서명 키 생성
```bash
keytool -genkey -v -keystore libroprintplugin-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias release
```

#### 3.6.2 build.gradle.kts에 서명 설정 추가
```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file("libroprintplugin-release.jks")
            storePassword = "비밀번호"
            keyAlias = "release"
            keyPassword = "비밀번호"
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }
}
```

#### 3.6.3 릴리스 빌드 실행
```bash
# APK 빌드
./gradlew assembleRelease

# 또는 AAB (Google Play 권장 형식)
./gradlew bundleRelease

# 결과물 위치
app/build/outputs/apk/release/app-release.apk
app/build/outputs/bundle/release/app-release.aab
```

#### 3.6.4 Google Play Console 업로드
1. https://play.google.com/console 접속
2. 앱 만들기 → 앱 이름: "LibroPrinter 플러그인"
3. 프로덕션 → 새 릴리스 만들기 → AAB 파일 업로드
4. 스토어 등록정보 작성 (섹션 8 참고)
5. 심사 제출

### 3.7 Google Play targetSdk=34 호환성

| 항목 | 영향 | 대응 |
|---|---|---|
| Foreground Service 타입 | Android 14+ 필수 | PrintService는 시스템 바인딩이므로 해당 없음 |
| NSD 콜백 API 변경 | `resolveService()` deprecated API 34+ | `@Suppress("DEPRECATION")` 이미 적용됨 |
| 백그라운드 제한 | Android 12+ 강화 | PrintService는 시스템이 바인딩하므로 영향 없음 |
| 정확한 알람 | Android 12+ 권한 필요 | 사용하지 않으므로 해당 없음 |

결론: **코드 변경 없이 targetSdk=34 빌드 가능**


## 4. 전체 소스 코드 (주석 포함)

### 4.1 AndroidManifest.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- IPP 통신을 위한 인터넷 권한 -->
    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:allowBackup="false"
        android:label="LibroPrinter 플러그인"
        android:supportsRtl="true">

        <!--
            PrintService 선언.
            - exported=true: 시스템 PrintSpooler가 바인딩할 수 있도록 공개
            - permission=BIND_PRINT_SERVICE: 시스템만 바인딩 가능 (보안)
            - intent-filter: 시스템이 "인쇄 서비스"로 자동 인식
            - meta-data: 인쇄 서비스 설정 XML 참조
        -->
        <service
            android:name=".LibroNetPrintService"
            android:exported="true"
            android:permission="android.permission.BIND_PRINT_SERVICE">
            <intent-filter>
                <action android:name="android.printservice.PrintService" />
            </intent-filter>
            <meta-data
                android:name="android.print.printservice"
                android:resource="@xml/print_service_info" />
        </service>

    </application>
</manifest>
```

**포인트:**
- Activity가 없음 — 순수 서비스 앱 (런처 아이콘 표시 안 됨)
- 사용자는 **설정 → 인쇄**에서 활성화/비활성화

### 4.2 res/xml/print_service_info.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
    PrintService 메타데이터. 시스템이 인쇄 서비스 목록에 표시할 때 참조.
    vendor: 제조사 이름 (설정 화면에 표시)
-->
<print-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:vendor="LibroPrinter" />
```

### 4.3 LibroNetPrintService.kt — 인쇄 서비스 메인 클래스

```kotlin
package com.betona.libroprintplugin

import android.os.Handler
import android.os.Looper
import android.print.PrinterId
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 네트워크 PrintService 플러그인.
 *
 * Android PrintService는 3개의 필수 콜백을 가짐:
 * 1. onCreatePrinterDiscoverySession() — 프린터 검색 세션 생성
 * 2. onRequestCancelPrintJob()         — 인쇄 취소 요청
 * 3. onPrintJobQueued()                — 인쇄 작업 실행 (핵심)
 *
 * 시스템 PrintSpooler가 이 서비스에 바인딩하여 콜백을 호출함.
 * Activity 없이 동작하는 백그라운드 서비스.
 */
class LibroNetPrintService : PrintService() {

    companion object {
        private const val TAG = "LibroNetPrintSvc"
    }

    // PrintJob.complete()/fail()은 반드시 메인 스레드에서 호출해야 함
    private val mainHandler = Handler(Looper.getMainLooper())

    // 현재 활성 탐색 세션 참조 (프린터 IP/포트 조회용)
    private var discoverySession: LibroNetDiscoverySession? = null

    /**
     * 시스템이 프린터 탐색을 시작할 때 호출.
     * LibroNetDiscoverySession을 생성하여 반환.
     */
    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession {
        Log.d(TAG, "onCreatePrinterDiscoverySession")
        return LibroNetDiscoverySession(this).also { discoverySession = it }
    }

    /**
     * 사용자가 인쇄 취소 버튼을 누를 때 호출.
     */
    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        Log.d(TAG, "onRequestCancelPrintJob")
        printJob.cancel()
    }

    /**
     * 인쇄 작업이 큐에 들어왔을 때 호출 (핵심 로직).
     *
     * 처리 흐름:
     * 1. printJob.start() — 시스템에 "처리 중" 알림
     * 2. printJob.document.data — PDF 파일 디스크립터 획득
     * 3. discoverySession.getEndpoint() — 프린터 IP/포트 조회
     * 4. 백그라운드 스레드에서 PDF → IppClient로 전송
     * 5. 결과에 따라 complete() 또는 fail() 호출
     */
    override fun onPrintJobQueued(printJob: PrintJob) {
        val info = printJob.info
        val media = info.attributes.mediaSize
        Log.i(TAG, "onPrintJobQueued: ${info.label}, paper=${media?.id}")

        // 1단계: 작업 시작 표시
        if (!printJob.start()) {
            Log.e(TAG, "printJob.start() failed")
            return
        }

        // 2단계: PDF 데이터 획득
        val fd = printJob.document.data
        if (fd == null) {
            Log.e(TAG, "Document data is null")
            printJob.fail("인쇄 데이터가 없습니다")
            return
        }

        // 3단계: 대상 프린터 IP/포트 조회
        val printerId = info.printerId
        if (printerId == null) {
            Log.e(TAG, "Printer ID is null")
            printJob.fail("프린터 ID가 없습니다")
            fd.close()
            return
        }
        val endpoint = discoverySession?.getEndpoint(printerId)
        if (endpoint == null) {
            Log.e(TAG, "Printer endpoint not found for ${printerId.localId}")
            printJob.fail("프린터를 찾을 수 없습니다")
            fd.close()
            return
        }

        Log.i(TAG, "Sending to ${endpoint.host}:${endpoint.port}")

        // 4단계: 백그라운드 스레드에서 IPP 전송
        Thread {
            try {
                // ParcelFileDescriptor → byte[] 변환
                val pdfBytes = ByteArrayOutputStream().use { baos ->
                    fd.fileDescriptor.let { fdesc ->
                        java.io.FileInputStream(fdesc).use { input ->
                            input.copyTo(baos)
                        }
                    }
                    baos.toByteArray()
                }
                fd.close()

                Log.i(TAG, "PDF data: ${pdfBytes.size} bytes")

                // IPP Print-Job 요청 전송
                val success = IppClient.sendPrintJob(endpoint.host, endpoint.port, pdfBytes)

                // 5단계: 메인 스레드에서 결과 보고
                // ※ PrintJob.complete()/fail()은 반드시 메인 스레드에서 호출
                val latch = CountDownLatch(1)
                mainHandler.post {
                    if (success) {
                        printJob.complete()
                        Log.i(TAG, "Print job COMPLETED")
                    } else {
                        printJob.fail("IPP 인쇄 요청 실패")
                        Log.e(TAG, "Print job FAILED")
                    }
                    latch.countDown()
                }
                latch.await(5, TimeUnit.SECONDS)

            } catch (e: Throwable) {
                Log.e(TAG, "Print FAILED", e)
                mainHandler.post {
                    try {
                        printJob.fail("인쇄 오류: ${e.message}")
                    } catch (_: Exception) {}
                }
            }
        }.start()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        discoverySession = null
        super.onDestroy()
    }
}
```

### 4.4 LibroNetDiscoverySession.kt — 프린터 탐색 + 용지 크기

```kotlin
package com.betona.libroprintplugin

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrinterDiscoverySession
import android.util.Log

/**
 * mDNS(_ipp._tcp)로 같은 WiFi의 LibroPrinter 키오스크를 자동 탐색.
 *
 * 이 클래스의 핵심 역할:
 * 1. NsdManager로 네트워크의 IPP 프린터 검색
 * 2. "LibroPrinter"로 시작하는 서비스만 필터링
 * 3. 72mm 용지 크기를 PrinterCapabilitiesInfo로 보고
 *    → 이것이 앱이 72mm 폭에 맞게 콘텐츠를 레이아웃하게 만드는 핵심!
 *
 * 스레딩 주의:
 * - NSD 콜백은 ConnectivityThread에서 실행됨
 * - generatePrinterId(), addPrinters()는 메인 스레드에서만 호출 가능
 * - Handler(mainLooper).post{}로 반드시 감싸야 함 (안 하면 크래시)
 */
class LibroNetDiscoverySession(
    private val service: LibroNetPrintService
) : PrinterDiscoverySession() {

    companion object {
        private const val TAG = "LibroNetDiscovery"
        private const val SERVICE_TYPE = "_ipp._tcp."  // mDNS 서비스 타입
        private const val PRINTER_NAME_PREFIX = "LibroPrinter"  // 필터링 키워드
    }

    // 메인 스레드 핸들러 — PrintService API 호출용
    private val mainHandler = Handler(Looper.getMainLooper())
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    // 발견된 프린터 맵: localId → (host, port, name)
    // localId는 "libro-net-{서비스이름}" 형식
    private val discoveredPrinters = mutableMapOf<String, PrinterEndpoint>()

    data class PrinterEndpoint(val host: String, val port: Int, val name: String)

    // ── PrinterDiscoverySession 콜백 ─────────────────────────────────────

    /**
     * 시스템이 프린터 탐색 시작을 요청할 때 호출.
     * 인쇄 대화상자가 열릴 때 자동으로 호출됨.
     */
    override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
        Log.d(TAG, "onStartPrinterDiscovery")
        startNsdDiscovery()
    }

    /**
     * 시스템이 프린터 탐색 중지를 요청할 때 호출.
     * 인쇄 대화상자가 닫힐 때 호출됨.
     */
    override fun onStopPrinterDiscovery() {
        Log.d(TAG, "onStopPrinterDiscovery")
        stopNsdDiscovery()
    }

    override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {}

    /**
     * 사용자가 특정 프린터를 선택했을 때 호출.
     * 이 시점에 PrinterCapabilitiesInfo (용지 크기, 해상도 등)를 보고.
     *
     * ★★★ 핵심: 여기서 72mm 용지를 등록하면
     * ★★★ 앱이 72mm 폭에 맞게 콘텐츠를 레이아웃함!
     */
    override fun onStartPrinterStateTracking(printerId: PrinterId) {
        Log.d(TAG, "onStartPrinterStateTracking: ${printerId.localId}")

        val endpoint = discoveredPrinters[printerId.localId] ?: return

        // 해상도: 203 DPI (감열 프린터 표준)
        val resolution = PrintAttributes.Resolution("203dpi", "203 DPI", 203, 203)

        // ── 용지 크기 정의 ──
        // 72mm = 2835 mils (계산: 72 / 25.4 × 1000 = 2834.6 ≈ 2835)
        // 1 mil = 1/1000 인치

        val receipt200 = PrintAttributes.MediaSize(
            "RECEIPT_72x200",     // 고유 ID
            "72mm x 200mm",       // 사용자에게 표시되는 이름
            2835, 7874            // 폭 x 높이 (mils)
        )
        val receipt300 = PrintAttributes.MediaSize(
            "RECEIPT_72x300", "72mm x 300mm", 2835, 11811
        )
        val receipt600 = PrintAttributes.MediaSize(
            "RECEIPT_72x600", "72mm x 600mm", 2835, 23622
        )

        // PrinterCapabilitiesInfo: 이 프린터가 지원하는 기능 목록
        val capabilities = PrinterCapabilitiesInfo.Builder(printerId)
            .addMediaSize(receipt200, true)   // true = 기본 용지
            .addMediaSize(receipt300, false)   // 선택 가능한 추가 용지
            .addMediaSize(receipt600, false)
            .addResolution(resolution, true)  // true = 기본 해상도
            .setColorModes(
                PrintAttributes.COLOR_MODE_MONOCHROME,  // 지원 모드
                PrintAttributes.COLOR_MODE_MONOCHROME   // 기본 모드
            )
            .setMinMargins(PrintAttributes.Margins(0, 0, 0, 0)) // 마진 없음
            .build()

        // 프린터 이름에 "(72mm)" 추가 — Mopria가 발견한 같은 프린터와 구분
        val displayName = "${endpoint.name} (72mm)"
        addPrinters(listOf(
            PrinterInfo.Builder(printerId, displayName, PrinterInfo.STATUS_IDLE)
                .setCapabilities(capabilities)
                .build()
        ))
    }

    override fun onStopPrinterStateTracking(printerId: PrinterId) {}

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        stopNsdDiscovery()
        discoveredPrinters.clear()
    }

    // ── NSD (mDNS) 탐색 ──────────────────────────────────────────────────

    /**
     * NsdManager로 _ipp._tcp 서비스 탐색 시작.
     *
     * 탐색 흐름:
     * 1. discoverServices() 호출 → mDNS 멀티캐스트 쿼리 전송
     * 2. onServiceFound() 콜백 → "LibroPrinter" 이름 필터링
     * 3. resolveService() → IP 주소 + 포트 획득
     * 4. onServiceResolved() → 프린터 목록에 추가
     */
    private fun startNsdDiscovery() {
        val mgr = service.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsdManager = mgr

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "NSD discovery started")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "NSD discovery stopped")
            }

            /**
             * mDNS 서비스 발견 시 호출.
             * "LibroPrinter"가 이름에 포함된 서비스만 resolve 진행.
             */
            @Suppress("DEPRECATION")
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "NSD found: ${serviceInfo.serviceName}")
                if (serviceInfo.serviceName.contains(PRINTER_NAME_PREFIX, ignoreCase = true)) {
                    mgr.resolveService(serviceInfo, createResolveListener())
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "NSD lost: ${serviceInfo.serviceName}")
                val localId = "libro-net-${serviceInfo.serviceName}"
                discoveredPrinters.remove(localId)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "NSD start failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "NSD stop failed: $errorCode")
            }
        }

        discoveryListener = listener
        mgr.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    /**
     * NSD resolve 리스너 생성.
     * resolve 성공 시 IP/포트를 맵에 저장하고 addPrinters() 호출.
     *
     * ★ 주의: 이 콜백은 ConnectivityThread에서 실행됨!
     * ★ generatePrinterId(), addPrinters()는 메인 스레드 필수!
     * ★ mainHandler.post{} 로 감싸지 않으면 크래시 발생:
     * ★   IllegalAccessError: must be called from the main thread
     */
    @Suppress("DEPRECATION")
    private fun createResolveListener() = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e(TAG, "NSD resolve failed: ${serviceInfo.serviceName}, error=$errorCode")
        }

        @Suppress("DEPRECATION")
        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            val host = serviceInfo.host?.hostAddress ?: return
            val port = serviceInfo.port
            val name = serviceInfo.serviceName
            Log.i(TAG, "NSD resolved: $name -> $host:$port")

            // 프린터 정보 저장 (나중에 인쇄 시 IP/포트 조회용)
            val localId = "libro-net-$name"
            discoveredPrinters[localId] = PrinterEndpoint(host, port, name)

            // ★ 반드시 메인 스레드에서 호출 ★
            mainHandler.post {
                val printerId = service.generatePrinterId(localId)
                val displayName = "$name (72mm)"
                addPrinters(listOf(
                    PrinterInfo.Builder(printerId, displayName, PrinterInfo.STATUS_IDLE).build()
                ))
            }
        }
    }

    private fun stopNsdDiscovery() {
        discoveryListener?.let { listener ->
            try {
                nsdManager?.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                Log.e(TAG, "NSD stop error", e)
            }
        }
        discoveryListener = null
    }

    /**
     * 프린터 ID로 엔드포인트(IP/포트) 조회.
     * LibroNetPrintService.onPrintJobQueued()에서 호출.
     */
    fun getEndpoint(printerId: PrinterId): PrinterEndpoint? {
        return discoveredPrinters[printerId.localId]
    }
}
```

### 4.5 IppClient.kt — IPP 프로토콜 클라이언트

```kotlin
package com.betona.libroprintplugin

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * IPP (Internet Printing Protocol) 클라이언트.
 * 키오스크의 IppServer에 Print-Job 요청을 전송.
 *
 * IPP는 HTTP 위에서 동작하는 바이너리 프로토콜:
 * - HTTP POST /ipp/print
 * - Content-Type: application/ipp
 * - Body: IPP 헤더 + 속성 + PDF 데이터
 *
 * 외부 라이브러리 없이 HttpURLConnection만으로 구현.
 */
object IppClient {

    private const val TAG = "IppClient"
    private var requestIdCounter = 1  // IPP 요청마다 고유 ID 부여

    /**
     * PDF 문서를 IPP Print-Job으로 전송.
     *
     * @param host  키오스크 IP (예: "192.168.50.243")
     * @param port  IPP 포트 (예: 6631)
     * @param pdfData  PDF 파일 바이트 배열
     * @return true = 인쇄 성공, false = 실패
     */
    fun sendPrintJob(host: String, port: Int, pdfData: ByteArray): Boolean {
        val printerUri = "ipp://$host:$port/ipp/print"
        val requestId = synchronized(this) { requestIdCounter++ }

        Log.i(TAG, "Sending Print-Job to $printerUri (${pdfData.size} bytes, reqId=$requestId)")

        // IPP 바이너리 요청 생성 (헤더 + 속성 + PDF)
        val ippRequest = buildPrintJobRequest(printerUri, requestId, pdfData)

        // HTTP POST로 전송 (IPP는 HTTP 위에서 동작)
        val httpUrl = "http://$host:$port/ipp/print"
        val connection = URL(httpUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/ipp")
            connection.setRequestProperty("Content-Length", ippRequest.size.toString())
            connection.connectTimeout = 10_000   // 연결 10초
            connection.readTimeout = 60_000      // 응답 60초 (인쇄 대기)

            // 요청 전송
            connection.outputStream.use { it.write(ippRequest) }

            val responseCode = connection.responseCode
            Log.d(TAG, "HTTP response: $responseCode")

            val responseStream: InputStream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                Log.e(TAG, "HTTP error: $responseCode")
                return false
            }

            // IPP 응답 파싱
            val responseBody = responseStream.use { it.readBytes() }
            return parseIppResponse(responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "Print-Job request failed", e)
            return false
        } finally {
            connection.disconnect()
        }
    }

    /**
     * IPP Print-Job 요청 바이너리 생성.
     *
     * IPP 요청 구조:
     * ┌──────────────────────────────────────┐
     * │ Version (2 bytes): 2.0              │ ← IPP 2.0
     * │ Operation (2 bytes): 0x0002         │ ← Print-Job
     * │ Request-ID (4 bytes): 순차 증가      │
     * ├──────────────────────────────────────┤
     * │ 0x01 — Operation Attributes 그룹    │
     * │   attributes-charset: utf-8         │ ← 태그 0x47
     * │   attributes-natural-language: en   │ ← 태그 0x48
     * │   printer-uri: ipp://host/ipp/print │ ← 태그 0x45
     * │   requesting-user-name: ...         │ ← 태그 0x42
     * │   job-name: Print Job               │ ← 태그 0x42
     * │   document-format: application/pdf  │ ← 태그 0x49
     * ├──────────────────────────────────────┤
     * │ 0x03 — End of Attributes            │
     * ├──────────────────────────────────────┤
     * │ PDF 바이트 데이터 (가변 길이)         │
     * └──────────────────────────────────────┘
     *
     * 각 속성의 바이너리 포맷:
     * [태그 1B][이름길이 2B][이름 nB][값길이 2B][값 nB]
     */
    private fun buildPrintJobRequest(printerUri: String, requestId: Int, pdfData: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()

        // IPP version 2.0
        out.write(2) // major
        out.write(0) // minor

        // Operation: Print-Job (0x0002)
        out.writeShortBE(0x0002)

        // Request ID (4바이트 빅엔디안)
        out.writeIntBE(requestId)

        // Operation attributes group 시작 태그
        out.write(0x01)

        // 필수 속성: charset과 language (IPP 스펙 필수)
        out.writeStringAttr(0x47, "attributes-charset", "utf-8")
        out.writeStringAttr(0x48, "attributes-natural-language", "en")

        // 대상 프린터 URI
        out.writeStringAttr(0x45, "printer-uri", printerUri)

        // 요청자 이름 (로그용)
        out.writeStringAttr(0x42, "requesting-user-name", "LibroPrintPlugin")

        // 작업 이름 (로그용)
        out.writeStringAttr(0x42, "job-name", "Print Job")

        // 문서 형식: PDF
        out.writeStringAttr(0x49, "document-format", "application/pdf")

        // End of Attributes
        out.write(0x03)

        // Document Data — PDF 바이트 그대로 추가
        out.write(pdfData)

        return out.toByteArray()
    }

    /**
     * IPP 응답 파싱.
     *
     * 응답 구조 (처음 4바이트만 확인):
     * [version-major 1B][version-minor 1B][status-code 2B]
     *
     * status-code:
     * - 0x0000 ~ 0x00FF: 성공 (successful)
     * - 0x0100 ~ 0x01FF: informational
     * - 0x0300 ~ 0x03FF: redirection
     * - 0x0400 ~ 0x04FF: client-error
     * - 0x0500 ~ 0x05FF: server-error
     */
    private fun parseIppResponse(body: ByteArray): Boolean {
        if (body.size < 4) {
            Log.e(TAG, "IPP response too short: ${body.size} bytes")
            return false
        }

        val verMajor = body[0].toInt() and 0xFF
        val verMinor = body[1].toInt() and 0xFF
        val statusCode = ((body[2].toInt() and 0xFF) shl 8) or (body[3].toInt() and 0xFF)

        Log.i(TAG, "IPP response: v$verMajor.$verMinor status=0x${statusCode.toString(16).padStart(4, '0')}")

        // 0x0000~0x00FF = successful
        return statusCode <= 0x00FF
    }

    // ── ByteArrayOutputStream 확장 함수 (빅엔디안 바이너리 쓰기) ────────

    /** 2바이트 빅엔디안 정수 쓰기 */
    private fun ByteArrayOutputStream.writeShortBE(v: Int) {
        write((v shr 8) and 0xFF)
        write(v and 0xFF)
    }

    /** 4바이트 빅엔디안 정수 쓰기 */
    private fun ByteArrayOutputStream.writeIntBE(v: Int) {
        write((v shr 24) and 0xFF)
        write((v shr 16) and 0xFF)
        write((v shr 8) and 0xFF)
        write(v and 0xFF)
    }

    /**
     * IPP 속성 쓰기.
     * 바이너리 포맷: [태그 1B][이름길이 2B][이름][값길이 2B][값]
     *
     * @param tag   속성 태그 (0x42=name, 0x45=uri, 0x47=charset 등)
     * @param name  속성 이름 (예: "printer-uri")
     * @param value 속성 값 (예: "ipp://192.168.50.243:6631/ipp/print")
     */
    private fun ByteArrayOutputStream.writeStringAttr(tag: Int, name: String, value: String) {
        write(tag)
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        writeShortBE(nameBytes.size)
        write(nameBytes)
        val valueBytes = value.toByteArray(Charsets.UTF_8)
        writeShortBE(valueBytes.size)
        write(valueBytes)
    }
}
```


## 5. 용지 크기 계산 참고

### 5.1 mils 단위 변환
```
1 mil = 1/1000 inch = 0.0254 mm
mm → mils 변환: mils = mm / 25.4 × 1000
```

### 5.2 프로젝트에서 사용하는 용지 크기
| 용지 ID | mm | mils (W × H) | 용도 |
|---|---|---|---|
| RECEIPT_72x200 | 72 × 200 | 2835 × 7874 | **기본** (짧은 영수증) |
| RECEIPT_72x300 | 72 × 300 | 2835 × 11811 | 중간 영수증 |
| RECEIPT_72x600 | 72 × 600 | 2835 × 23622 | 긴 영수증 |

### 5.3 A4와의 비교
```
A4: 210 × 297mm → 72mm 감열지에 축소 시 = 72/210 = 34% 크기
72mm 용지: 72mm → 축소 없이 100% 크기로 인쇄
```


## 6. 키오스크 측 요구사항

플러그인이 동작하려면 키오스크에서 다음이 실행 중이어야 함:

### 6.1 IppServer (포트 6631)
- `IppServer.kt` — 키오스크 앱(`com.betona.printdriver`)에 포함
- TCP 포트 6631에서 IPP 요청 수신
- mDNS로 `_ipp._tcp` 서비스 등록 (이름: `LibroPrinter-{모델}`)
- Print-Job 수신 → PdfRenderer → BitmapConverter → 감열 인쇄

### 6.2 mDNS 서비스 정보
```
Service Type: _ipp._tcp
Service Name: LibroPrinter-{모델명}   (예: LibroPrinter-JY3568_r)
Port: 6631
TXT Records:
  txtvers=1
  pdl=application/pdf
  rp=ipp/print
  ty=LibroPrinter-{모델명}
  UUID={고유ID}
  product=(LibroPrinter Thermal)
```

### 6.3 네트워크
- 폰과 키오스크가 **같은 WiFi 네트워크**에 있어야 함
- 키오스크 IP가 mDNS resolve로 자동 검색됨


## 7. 개발 중 발견된 이슈 및 해결

### 7.1 크래시: IllegalAccessError (메인 스레드)
- **증상**: `must be called from the main thread` 크래시
- **원인**: NSD `onServiceResolved()` 콜백이 `ConnectivityThread`에서 실행됨
- **해결**: `Handler(Looper.getMainLooper()).post { }` 로 `generatePrinterId()`, `addPrinters()` 감싸기

### 7.2 프린터 중복 표시
- **증상**: 기본 인쇄 서비스(Mopria)와 플러그인이 같은 프린터를 각각 표시
- **원인**: 두 서비스 모두 `_ipp._tcp` mDNS 탐색
- **해결**: 플러그인 프린터 이름에 `(72mm)` 접미사 추가하여 구분

### 7.3 설치 차단: INSTALL_FAILED_VERIFICATION_FAILURE
- **증상**: 일부 기기에서 APK 설치 거부
- **해결**:
  ```
  adb shell settings put global verifier_verify_adb_installs 0
  adb shell settings put global package_verifier_enable 0
  ```
  (Google Play 배포 시에는 해당 없음 — Play Protect가 허용)


## 8. Google Play 스토어 등록 정보

### 8.1 기본 정보
- **카테고리**: 도구 > 인쇄
- **앱 이름**: LibroPrinter 플러그인
- **영문명**: LibroPrinter Print Plugin
- **짧은 설명**: LibroPrinter 키오스크 감열 프린터용 72mm 인쇄 플러그인

### 8.2 설명 예시
```
LibroPrinter 키오스크의 감열 프린터에 최적화된 인쇄 플러그인입니다.

기능:
• 72mm 감열 용지에 최적화된 레이아웃
• WiFi 네트워크에서 자동 프린터 검색 (mDNS)
• IPP 프로토콜로 PDF 전송
• 크롬, 갤러리, 문서 등 모든 앱에서 인쇄 가능

사용법:
1. 앱 설치 후 설정 → 인쇄 → "LibroPrinter 플러그인" 활성화
2. LibroPrinter 키오스크와 같은 WiFi에 연결
3. 아무 앱에서 인쇄 → "(72mm)" 표시된 프린터 선택

요구사항:
• LibroPrinter 키오스크가 같은 WiFi 네트워크에 있어야 합니다
• Android 7.0 이상
```

### 8.3 서명 키
- 별도의 keystore 생성 필요 (키오스크 앱과 다른 서명)
- Google Play App Signing 사용 권장


## 9. 테스트 체크리스트

- [ ] 플러그인 설치 후 설정 → 인쇄에 "LibroPrinter 플러그인" 표시
- [ ] 플러그인 활성화 후 크래시 없음
- [ ] 같은 WiFi에서 키오스크 프린터 자동 발견 (이름에 "(72mm)" 포함)
- [ ] 인쇄 미리보기에서 용지 크기 "72mm x 200mm" 확인
- [ ] 인쇄 실행 → 키오스크에서 정상 출력
- [ ] 출력물 글씨 크기가 적절함 (A4 축소 대비)
- [ ] 프린터 오프라인 시 적절한 에러 메시지


## 10. IPP 속성 태그 참조표

| 태그 | 타입 | 용도 |
|---|---|---|
| 0x01 | operation-attributes-tag | 그룹 시작 |
| 0x03 | end-of-attributes-tag | 속성 끝, 이후 문서 데이터 |
| 0x21 | integer | 정수 값 (4바이트) |
| 0x41 | textWithoutLanguage | 텍스트 값 |
| 0x42 | nameWithoutLanguage | 이름 값 |
| 0x44 | keyword | 키워드 값 |
| 0x45 | uri | URI 값 |
| 0x47 | charset | 문자셋 (utf-8) |
| 0x48 | naturalLanguage | 자연어 (en) |
| 0x49 | mimeMediaType | MIME 타입 (application/pdf) |


---

## 11. 키오스크 시스템 이슈 및 해결

이 섹션은 키오스크 기기(JY-P1000, A40i)에서 인쇄 시스템을 동작시키기 위해
해결해야 했던 문제들과 그 해결 방법을 기록합니다.

### 11.1 WebView 이슈 (A40i — Android 7)

#### 11.1.1 문제: 기본 WebView 버전이 너무 낮음

```
기본 WebView: com.android.webview v52 (Chromium 52, 2016년)
설치된 Chrome: com.android.chrome v119.0.6045.194 (2023년)
```

A40i의 기본 WebView은 Chromium 52로, 최신 웹사이트가 렌더링되지 않습니다.
Chrome 119가 설치되어 있지만 시스템 설정(`config_webview_packages.xml`)에
`com.android.webview`만 등록되어 있어 Chrome을 WebView 엔진으로 사용할 수 없습니다.

#### 11.1.2 해결: framework-res.apk 패치

Android의 WebView 프로바이더 목록은 `/system/framework/framework-res.apk` 내부의
`res/xml/config_webview_packages.xml`에 **바이너리 XML** 형태로 저장되어 있습니다.

**패치 전 (원본):**
```xml
<webviewproviders>
    <webviewprovider description="Android WebView"
        packageName="com.android.webview" availableByDefault="true" />
</webviewproviders>
```

**패치 후 (Chrome 추가):**
```xml
<webviewproviders>
    <webviewprovider description="Chrome"
        packageName="com.android.chrome" availableByDefault="true" />
    <webviewprovider description="Android WebView"
        packageName="com.android.webview" availableByDefault="true" />
</webviewproviders>
```

#### 11.1.3 패치 절차

**필요 도구**: Python 3, ADB

**스크립트 파일 (patches/android7/ 폴더):**
| 파일 | 역할 |
|---|---|
| `patch_webview_config.py` | 패치된 바이너리 XML 생성 |
| `patch_framework_res.py` | framework-res.apk 내부 XML 교체 |
| `config_webview_packages_patched.bin` | 미리 생성된 패치 바이너리 |

```bash
# 1. 원본 백업 (필수!)
adb root && adb remount
adb shell cp /system/framework/framework-res.apk /sdcard/framework-res-backup.apk

# 2. framework-res.apk 추출
adb exec-out "cat /system/framework/framework-res.apk" > framework-res.apk

# 3. 패치된 바이너리 XML 생성 (이미 있으면 생략)
python patch_webview_config.py config_webview_packages_patched.bin

# 4. APK 내부 XML 교체
python patch_framework_res.py framework-res.apk config_webview_packages_patched.bin

# 5. 패치된 APK 기기에 적용
adb push framework-res-patched.apk /sdcard/
adb shell cp /sdcard/framework-res-patched.apk /system/framework/framework-res.apk
adb shell chmod 644 /system/framework/framework-res.apk

# 6. WebView 프로바이더를 Chrome으로 설정
adb shell settings put global webview_provider com.android.chrome

# 7. 재부팅
adb reboot
```

#### 11.1.4 패치 검증
```bash
# WebView 프로바이더 확인
adb shell settings get global webview_provider
# 출력: com.android.chrome

# WebView 로그 확인
adb shell logcat -d | grep "cr_SplitCompatApp"
# 출력: Launched version=119.0.6045.194
```

#### 11.1.5 복구 (부팅 실패 시)
```bash
adb root && adb remount
adb shell cp /sdcard/framework-res-backup.apk /system/framework/framework-res.apk
adb shell chmod 644 /system/framework/framework-res.apk
adb reboot
```

#### 11.1.6 브라우저별 인쇄 지원 (A40i)

| 브라우저 | 버전 | 인쇄 지원 | 비고 |
|---|---|---|---|
| Chrome 119 | Android 7 최신 | 인쇄 메뉴 없음 | 공유→인쇄도 무반응 |
| Firefox 143 | Android 7 최신 | 메뉴→인쇄 가능 | 간헐적 `documentInfo: null` |
| 앱 내 WebView | Chrome 119 엔진 | 팝업 기반 인쇄 | WebPrintActivity에서 처리 |

**결론**: A40i에서는 Firefox 143 또는 앱 내 WebView 브라우저 사용.

---

### 11.2 PrintSpooler 이슈 (A40i — Android 7)

#### 11.2.1 문제 1: Drawable 자기참조 크래시

**증상:**
```
Resources$NotFoundException: Drawable
com.android.printspooler:drawable/ic_expand_more with resource ID #0x7f020005
```

**원인:**
PrintSpooler의 `res/drawable/ic_expand_more.xml`과 `ic_expand_less.xml`이
자기 자신을 참조하는 selector:

```xml
<!-- ic_expand_more.xml — 자기참조! -->
<selector>
    <item><bitmap android:src="@drawable/ic_expand_more" ... /></item>
</selector>
```

hdpi (240dpi+) 기기에서는 `res/drawable-hdpi-v4/` 폴더의 PNG가 우선 로드되어
문제가 없지만, A40i는 **mdpi (160dpi)** 이므로:
XML 참조 → 자기 자신(XML) → 무한 재귀 → 크래시

**해결 절차:**

```bash
# 1. PrintSpooler.apk 추출
adb exec-out "cat /system/app/PrintSpooler/PrintSpooler.apk" > PrintSpooler.apk

# 2. apktool로 디컴파일
apktool d PrintSpooler.apk -o PrintSpooler_decompiled

# 3. hdpi PNG를 mdpi 폴더에 복사
cd PrintSpooler_decompiled
mkdir -p res/drawable-mdpi-v4
cp res/drawable-hdpi-v4/ic_expand_more.png res/drawable-mdpi-v4/
cp res/drawable-hdpi-v4/ic_expand_less.png res/drawable-mdpi-v4/

# 4. 재빌드
apktool b PrintSpooler_decompiled -o PrintSpooler_patched_unsigned.apk

# 5. AOSP 테스트 키로 서명 (시스템 앱이므로 platform key 필요)
#    platform.x509.pem, platform.pk8: aosp-mirror/platform_build에서 획득
java -jar apksigner.jar sign \
  --key platform.pk8 \
  --cert platform.x509.pem \
  --out PrintSpooler_patched.apk \
  PrintSpooler_patched_unsigned.apk

# 6. 기기에 설치
adb root && adb remount
adb push PrintSpooler_patched.apk /sdcard/
adb shell cp /sdcard/PrintSpooler_patched.apk /system/app/PrintSpooler/PrintSpooler.apk
adb shell chmod 644 /system/app/PrintSpooler/PrintSpooler.apk

# 7. packages.xml 인증서 업데이트
#    /data/system/packages.xml에서:
#    - <keyset-settings> → <keys> 섹션에 새 인증서 인덱스 추가
#    - PrintSpooler의 <sigs> 항목을 새 인증서로 변경
#    (adb shell로 편집 또는 pull→수정→push)

# 8. 재부팅
adb reboot
```

#### 11.2.2 문제 2: 위치 권한 크래시

**증상:**
```
SecurityException: "fused" location provider requires
ACCESS_COARSE_LOCATION or ACCESS_FINE_LOCATION
```

**원인:** PrintSpooler가 내부적으로 fused location provider에 접근하지만,
AndroidManifest에 위치 권한을 선언하지 않음.

**해결:**
```bash
adb shell pm grant com.android.printspooler android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant com.android.printspooler android.permission.ACCESS_FINE_LOCATION
```

#### 11.2.3 문제 3: 설정 초기화 (재부팅 시)

**증상:** `enabled_print_services` 설정이 재부팅 후 사라짐.

**해결:** init 스크립트로 부팅 시 자동 재설정.

**configure_print.sh** (설치 경로: `/system/bin/configure_print.sh`):
```bash
#!/system/bin/sh
sleep 15
settings put secure enabled_print_services com.android.printdriver/com.betona.printdriver.LibroPrintService
settings put secure disabled_print_services ""
pm grant com.android.printspooler android.permission.ACCESS_COARSE_LOCATION
pm grant com.android.printspooler android.permission.ACCESS_FINE_LOCATION
log -t PrintConfig "Print service configured"
```

**printdriver.rc** (설치 경로: `/system/etc/init/printdriver.rc`):
```
on property:sys.boot_completed=1
    start configure_print

service configure_print /system/bin/sh /system/bin/configure_print.sh
    class late_start
    user root
    oneshot
    disabled
```

**설치 명령:**
```bash
adb root && adb remount

# A40i 전용 마운트 (일반 remount가 안 될 경우)
# adb shell mount -o rw,remount /dev/block/by-name/system /system

adb push configure_print.sh /sdcard/
adb push printdriver.rc /sdcard/

adb shell cp /sdcard/configure_print.sh /system/bin/configure_print.sh
adb shell chmod 755 /system/bin/configure_print.sh

adb shell mkdir -p /system/etc/init
adb shell cp /sdcard/printdriver.rc /system/etc/init/printdriver.rc
adb shell chmod 644 /system/etc/init/printdriver.rc

adb reboot
```

**주의: CRLF 문제**
Windows에서 작성한 셸 스크립트는 `\r\n` 줄바꿈을 포함할 수 있으며,
`#!/system/bin/sh\r`로 인해 **사일런트 실패**합니다.
반드시 LF(`\n`)만 사용하거나 `tr -d "\r"` 처리 필요.

---

### 11.3 BackgroundManagerService 이슈 (A40i 전용)

**증상:**
```
skipService com.betona.printdriver/.LibroPrintService because of activity not started!
```

**원인:** Allwinner/Softwinner 커스텀 `BackgroundManagerService`가
화이트리스트(`com.android`, `com.google`, `com.softwinner` 등)에 없는 앱의
백그라운드 서비스를 강제 종료.

**시도했지만 실패한 방법:**
- `settings put global background_manager_enabled 0` — 무시됨
- `deviceidle whitelist` — 영향 없음
- `cmd appops set RUN_IN_BACKGROUND allow` — 영향 없음
- `/system/priv-app/`으로 이동 — 영향 없음

**해결:** applicationId를 `com.android.printdriver`로 변경 (빌드 플레이버 `a40`).
화이트리스트의 `com.android` 접두사에 매칭됨.

---

### 11.4 A40i 전체 패치 순서 (최초 설정)

```
순서  작업                           비고
───────────────────────────────────────────────
 1.   PrintSpooler 패치              drawable 크래시 수정 (최초 1회)
 2.   APK 설치                       com.android.printdriver (a40 플레이버)
 3.   부트 스크립트 설치              configure_print.sh + printdriver.rc
 4.   WebView 패치                   framework-res.apk (Chrome 119 등록)
 5.   재부팅
 6.   인쇄 테스트                    Firefox 143 또는 앱 내 WebView
```

### 11.5 필요 파일 목록 (patches/android7/)

| 파일 | 설명 |
|---|---|
| `app-a40-release.apk` | A40i용 앱 APK (`com.android.printdriver`) |
| `configure_print.sh` | 부팅 시 PrintService 자동 활성화 스크립트 |
| `printdriver.rc` | Android init 서비스 정의 |
| `patch_webview_config.py` | WebView config 바이너리 XML 생성 (Python 3) |
| `patch_framework_res.py` | framework-res.apk 내부 XML 교체 (Python 3) |
| `config_webview_packages_patched.bin` | 미리 생성된 패치 바이너리 |

---

## 12. 사용자 설명서

### 12.1 사용자용 (폰에서 인쇄하는 사람)

#### 12.1.1 준비물
- Android 7.0 이상 스마트폰/태블릿
- LibroPrinter 플러그인 APK (Google Play 또는 직접 설치)
- LibroPrinter 키오스크와 **같은 WiFi** 연결

#### 12.1.2 최초 설정 (1회)

**1단계: 앱 설치**
- Google Play에서 "LibroPrinter 플러그인" 검색 → 설치
- 또는 APK 파일 직접 설치

**2단계: 인쇄 서비스 활성화**
1. 폰의 **설정** 열기
2. **연결된 기기** → **인쇄** (또는 설정에서 "인쇄" 검색)
3. **LibroPrinter 플러그인** 찾기 → **켜기**

#### 12.1.3 인쇄 방법

**1단계: WiFi 확인**
- 폰과 키오스크가 같은 WiFi에 연결되어 있는지 확인

**2단계: 인쇄 실행**
1. 인쇄할 앱 열기 (크롬, 갤러리, 문서 뷰어 등)
2. 메뉴 → **인쇄** (또는 **공유** → **인쇄**)
3. 프린터 목록에서 **"LibroPrinter-○○○ (72mm)"** 선택
   - 반드시 **(72mm)** 이 붙은 프린터를 선택!
   - (72mm) 없는 프린터는 기본 서비스(A4 축소) 사용됨
4. 용지 크기가 **72mm x 200mm** 인지 확인
5. **인쇄** 버튼 터치

**3단계: 출력 확인**
- 키오스크의 감열 프린터에서 출력물 확인
- 자동으로 용지 절단됨

#### 12.1.4 용지 크기 선택 가이드

| 용지 | 용도 | 선택 시점 |
|---|---|---|
| 72mm x 200mm | 짧은 내용 (영수증, 메모) | **기본값** — 대부분 이것 |
| 72mm x 300mm | 중간 내용 (웹페이지 일부) | 내용이 잘릴 때 |
| 72mm x 600mm | 긴 내용 (전체 웹페이지) | 긴 문서 인쇄 시 |

#### 12.1.5 문제 해결 (사용자)

| 증상 | 원인 | 해결 |
|---|---|---|
| 프린터가 목록에 안 보임 | WiFi 다름 또는 키오스크 꺼짐 | 같은 WiFi 연결 확인, 키오스크 전원 확인 |
| (72mm) 프린터가 안 보임 | 플러그인 비활성화 | 설정 → 인쇄 → 플러그인 켜기 |
| 글씨가 너무 작음 | (72mm) 아닌 프린터 선택 | **(72mm)** 붙은 프린터 재선택 |
| 인쇄 실패 에러 | 네트워크 문제 | WiFi 재연결 후 재시도 |
| 빈 종이만 나옴 | PDF 렌더링 문제 | 다른 앱에서 다시 시도 |

---

### 12.2 관리자용 (키오스크 설치/관리)

#### 12.2.1 시스템 구성

```
┌─────────────────────────────────────────────┐
│              WiFi 네트워크                    │
│                                              │
│  ┌──────────┐         ┌──────────────────┐  │
│  │ 사용자 폰 │ ──IPP──▶│  키오스크 기기    │  │
│  │          │         │                  │  │
│  │ 플러그인  │         │ ┌──────────────┐ │  │
│  │ APK 설치 │         │ │ IppServer    │ │  │
│  └──────────┘         │ │ (포트 6631)  │ │  │
│                       │ ├──────────────┤ │  │
│                       │ │ WebServer    │ │  │
│                       │ │ (포트 8080)  │ │  │
│                       │ ├──────────────┤ │  │
│                       │ │ /dev/printer │ │  │
│                       │ │ 감열 프린터   │ │  │
│                       │ └──────────────┘ │  │
│                       └──────────────────┘  │
└─────────────────────────────────────────────┘
```

#### 12.2.2 키오스크 초기 설정

##### JY-P1000 (Android 11) — 간단

```bash
# 1. APK 빌드 및 설치
./gradlew assembleStandardRelease
adb install -r app/build/outputs/apk/standard/release/app-standard-release.apk

# 2. PrintService 활성화
#    방법 A: 기기에서 설정 → 연결된 기기 → 인쇄 → LibroPrintDriver 켜기
#    방법 B: ADB
adb shell settings put secure enabled_print_services \
  com.betona.printdriver/com.betona.printdriver.LibroPrintService

# 3. 테스트
#    앱 실행 → 연결 테스트 → 이미지 인쇄 테스트
```

##### A40i (Android 7) — 패치 필요

```bash
# 1. PrintSpooler 패치 (drawable 크래시 수정)
#    → 섹션 11.2.1 참조

# 2. APK 설치
./gradlew assembleA40Release
adb install -r app/build/outputs/apk/a40/release/app-a40-release.apk

# 3. 부트 스크립트 설치 (PrintService 자동 활성화)
#    → 섹션 11.2.3 참조

# 4. WebView 패치 (Chrome 119 엔진 등록)
#    → 섹션 11.1.3 참조

# 5. 재부팅
adb reboot

# 6. 테스트: Firefox 143 설치 → 메뉴 → 인쇄
```

#### 12.2.3 키오스크 서비스 포트

| 포트 | 서비스 | 용도 |
|---|---|---|
| 6631 | IppServer | IPP 인쇄 수신 (플러그인/BIPS에서 접속) |
| 8080 | WebManagementServer | 웹 관리 인터페이스 (브라우저에서 접속) |

#### 12.2.4 네트워크 확인

```bash
# 키오스크 IP 확인
adb shell ip addr show wlan0 | grep "inet "

# IPP 서버 동작 확인
adb shell logcat -d | grep "IppServer"
# 출력: IPP server listening on port 6631

# mDNS 서비스 등록 확인
adb shell logcat -d | grep "mDNS registered"
# 출력: mDNS registered: LibroPrinter-JY3568_r
```

#### 12.2.5 일상 관리

##### 인쇄 로그 확인
```bash
# 실시간 인쇄 로그
adb logcat -s IppServer:* LibroPrintService:* BitmapConverter:*

# 최근 인쇄 작업 로그
adb shell logcat -d | grep "Print-Job"
```

##### 프린터 상태 확인
```bash
# PrintService 상태
adb shell dumpsys print

# 프린터 장치 존재 여부
adb shell ls -la /dev/printer
```

##### 앱 업데이트
```bash
# 새 버전 빌드 후 설치 (데이터 유지)
adb install -r app-standard-release.apk   # JY-P1000
adb install -r app-a40-release.apk        # A40i
```

#### 12.2.6 문제 해결 (관리자)

| 증상 | 진단 | 해결 |
|---|---|---|
| 프린터가 네트워크에서 안 보임 | `logcat | grep mDNS` — 등록 여부 확인 | WiFi 연결 확인, 앱 재시작 |
| IPP 인쇄 요청이 안 옴 | `logcat | grep IppServer` — 클라이언트 접속 확인 | 방화벽/AP격리 설정 확인 |
| 인쇄물이 안 나옴 | `logcat | grep DevicePrinter` — open/print 확인 | `/dev/printer` 존재 확인, 용지 확인 |
| 글씨가 너무 작게 나옴 | BIPS/Mopria 프린터 선택됨 | 폰에서 **(72mm)** 프린터 선택 안내 |
| A40i 서비스가 재부팅 후 중지 | `logcat | grep PrintConfig` | configure_print.sh 설치 확인 |
| A40i PrintSpooler 크래시 | `logcat | grep PrintSpooler` | PrintSpooler 패치 재적용 |
| A40i WebView 오류 | `logcat | grep WebView` | framework-res.apk 패치 확인 |
| 용지 걸림 / 과열 | 프린터 하드웨어 | 전원 끄고 용지 제거, 냉각 대기 |

#### 12.2.7 유용한 ADB 명령어 모음

```bash
# ── 상태 확인 ──
adb shell dumpsys print                          # PrintService 전체 상태
adb shell settings get secure enabled_print_services  # 활성 인쇄 서비스
adb shell settings get global webview_provider   # WebView 프로바이더 (A40i)
adb shell ls -la /dev/printer                    # 프린터 장치 확인

# ── 서비스 제어 ──
adb shell am force-stop com.betona.printdriver   # 앱 강제 종료 (재시작용)
adb shell am startservice com.betona.printdriver/.web.WebServerService  # 웹서버 시작

# ── 로그 ──
adb logcat -s IppServer:* LibroNetPrintSvc:* LibroNetDiscovery:*  # 인쇄 관련
adb logcat -s WebView:* cr_SplitCompatApp:*     # WebView 관련
adb logcat -d | grep -i "crash\|fatal\|exception" | tail -20  # 크래시 확인

# ── 네트워크 ──
adb shell ip addr show wlan0                     # WiFi IP 확인
adb shell ping -c 3 <폰IP>                      # 폰↔키오스크 연결 확인
```
