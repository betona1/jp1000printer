# LibroPrinterService 앱 종합 테스트 및 크래시 수정 프롬프트

아래 프롬프트를 Claude Code 터미널에 붙여넣기하세요.

---

## 프롬프트 (복사용)

```
이 프로젝트의 모든 소스코드를 분석하여 크래시 가능성을 찾고 자동 수정해줘.
CLAUDE.md를 먼저 읽어서 프로젝트 구조와 프린터 스펙을 파악해.

## 앱 기능 목록 (테스트 대상)

이 앱은 Android All-in-One 모바일도서검색기로, 아래 기능을 모두 포함한다:

### 핵심 기능
1. **독서로(DLS) 웹 접속**: WebView로 독서로 페이지 접속하여 도서 검색
2. **청구기호 인쇄**: 검색된 도서의 청구기호를 80mm 감열지 프린터로 인쇄
3. **ESC/POS 래스터 인쇄**: 웹페이지 캡처 → Bitmap → ESC/POS 래스터 변환 → 내장 프린터 출력

### 프린터 서버 기능
4. **IPP 프린터 서버**: 네트워크에서 IPP 프로토콜로 인쇄 수신
5. **RAW 9100 포트 서버**: PC에서 TCP 9100 포트로 직접 RAW 인쇄 데이터 수신
6. **모든 앱/웹에서 인쇄**: Android 시스템 Print Service로 등록되어 범용 인쇄 지원

### 관리자 기능
7. **관리자 모드**: 비밀번호 인증 후 설정 화면 진입
8. **웹 관리 (8080포트)**: 웹브라우저에서 http://기기IP:8080 으로 원격 설정
9. **네트워크 보안 설정**: 서버 서비스(IPP/9100/8080) ON/OFF 토글, 로컬 전용 모드 지원

### 절전/스케줄 기능
10. **야전 절전모드**: 화면 밝기 자동 조절, 완전 절전
11. **요일별 업무시간 설정**: 요일/시간대별 화면 ON/OFF 스케줄

### 부가 기능
12. **사다리 게임**: 간단한 미니게임
13. **빙고 게임**: 간단한 미니게임

---

## 분석 및 수정 작업 (순서대로 진행)

### Phase 1: 정적 분석 — 크래시 패턴 찾기

모든 Java/Kotlin 소스 파일을 스캔하여 아래 패턴을 찾아 수정해:

**NullPointerException 위험:**
- nullable 변수를 null 체크 없이 사용하는 곳
- Intent extras를 getStringExtra() 등으로 받을 때 null 체크 누락
- Bundle, SharedPreferences에서 값 가져올 때 기본값 미설정
- Activity/Fragment의 view 참조가 onDestroyView 이후 접근 가능한 곳
- getActivity()가 null 반환할 수 있는 Fragment 내 코드

**IllegalStateException 위험:**
- Fragment 트랜잭션이 onSaveInstanceState 이후 실행되는 곳
- Activity가 finish된 후 UI 업데이트하는 곳
- 잘못된 Lifecycle 상태에서 작업 수행

**NetworkOnMainThreadException 위험:**
- 메인 스레드에서 네트워크 호출 (Socket, HttpURLConnection, OkHttp 동기 호출)
- IPP 서버, 9100 서버의 accept/read가 메인 스레드에서 실행되는지 확인

**IndexOutOfBoundsException 위험:**
- 배열/리스트 접근 시 범위 체크 누락
- 빈 리스트에서 get(0) 호출

**SecurityException 위험:**
- 런타임 퍼미션 (BLUETOOTH, CAMERA, STORAGE 등) 체크 누락
- Android 12+ Bluetooth 퍼미션 (BLUETOOTH_CONNECT, BLUETOOTH_SCAN)
- Android 13+ 알림 퍼미션 (POST_NOTIFICATIONS)

**WebView 크래시:**
- WebView가 destroy 후 접근되는 경우
- JavaScript Interface에서 UI 스레드 접근
- SSL 에러 핸들링 누락
- WebView 메모리 누수 (Activity 참조)

### Phase 2: 서버 안정성 검증

**IPP 서버 (TCP):**
- ServerSocket accept 루프에 try-catch 있는지
- 클라이언트 연결 끊김 시 예외 처리
- 동시 연결 처리 (스레드 풀 또는 코루틴)
- 서버 중지 시 소켓 정상 close
- 서비스 재시작 시 "Address already in use" 방지 (setReuseAddress)

**RAW 9100 서버:**
- 위와 동일한 소켓 안정성 검증
- 대용량 인쇄 데이터 수신 시 OOM 방지 (버퍼 크기 제한)
- 타임아웃 설정 (소켓 무한 대기 방지)

**웹 관리 서버 (8080):**
- HTTP 파싱 오류 시 예외 처리
- 잘못된 요청에 대한 400/404 응답
- XSS, 경로 탐색 등 기본 보안 검증

### Phase 3: 프린터 통신 안정성

**Serial/USB 프린터:**
- 프린터 연결 해제 시 IOException 처리
- 프린터 busy/offline 상태 처리
- 용지 없음 상태 처리
- 대용량 래스터 데이터 전송 시 분할 전송 (프린터 버퍼 오버플로우 방지)
- ESC/POS 명령 전송 사이 적절한 딜레이

**Bitmap 변환:**
- 매우 큰 이미지 변환 시 OutOfMemoryError 처리
- Bitmap.recycle() 호출 확인 (메모리 누수 방지)
- 576 dots 리사이즈 시 0 크기 방지

### Phase 4: 생명주기 및 메모리 관리

**Activity/Service 생명주기:**
- 화면 회전 시 상태 보존 (onSaveInstanceState)
- 백그라운드 진입 시 서버 서비스 유지 확인
- Service가 kill 후 재시작되는지 (START_STICKY)
- 절전모드 진입 시 WakeLock 관리

**메모리 누수:**
- static 변수에 Context/Activity 참조 보유
- Handler에 Activity 참조 (WeakReference 사용 여부)
- 등록된 BroadcastReceiver 해제 누락
- 콜백/리스너에서 Activity 참조

### Phase 5: 스케줄/절전 기능 안정성

**요일별 스케줄:**
- AlarmManager 또는 WorkManager 사용 확인
- Doze 모드에서 정확한 알람 동작 (setExactAndAllowWhileIdle)
- 시간대 변경, 날짜 변경 시 스케줄 재계산
- 잘못된 시간 입력 (시작 > 종료) 처리

**절전 모드:**
- 화면 밝기 조절 권한 (WRITE_SETTINGS) 확인
- PowerManager WakeLock 누수 방지
- 절전 해제 트리거 정상 동작

### Phase 6: 게임 기능

**사다리 게임 / 빙고 게임:**
- 화면 회전 시 게임 상태 유실 방지
- 빠른 터치 반복 시 동시성 문제
- 게임 종료 후 메모리 정리

---

## 수정 규칙

1. **모든 수정에 주석 추가**: `// BUGFIX: [설명]` 형태로
2. **try-catch 추가 시** 반드시 로그 출력: `Log.e(TAG, "설명", e)`
3. **null 체크 추가 시** 안전한 기본값 제공
4. **스레드 안전성**: synchronized 또는 ConcurrentHashMap 등 적절한 동기화
5. **기존 로직 변경 최소화**: 크래시 방지 코드만 추가, 기능 변경 금지
6. **수정 완료 후 요약 리포트 작성**: 파일별 수정 내용 목록

작업 완료 후 아래 형식으로 리포트 출력해:

### 수정 리포트
| # | 파일 | 위험도 | 문제 | 수정 내용 |
|---|------|--------|------|-----------|
| 1 | ... | 높음/중간/낮음 | ... | ... |
```

---

## 사용법

1. VS터미널 또는 프로젝트 폴더의 터미널에서 `claude` 실행
2. 위 프롬프트 전체를 복사하여 붙여넣기
3. Claude Code가 파일을 하나씩 분석하며 수정 시작
4. 중간에 파일 수정 허용 물어보면 `y` 또는 `yes` 입력
5. 완료 후 리포트 확인

## 추가 프롬프트 (선택)

### 수정 후 빌드 확인
```
빌드해서 컴파일 에러 없는지 확인하고, 에러 있으면 수정해줘.
```

### 특정 기능만 집중 테스트
```
IPP 서버와 9100 RAW 서버 코드만 집중 분석해줘.
동시 접속, 연결 끊김, 대용량 데이터, 서버 재시작 시나리오에서
크래시 가능성을 찾고 수정해.
```

### ProGuard/R8 난독화 문제 확인
```
Release 빌드 시 ProGuard/R8 난독화로 인한 크래시 가능성을 확인해.
- Serializable/Parcelable 클래스 keep 규칙
- Reflection 사용하는 클래스 keep 규칙
- WebView JavaScript Interface keep 규칙
proguard-rules.pro 파일을 검토하고 필요한 규칙을 추가해줘.
```

### 네트워크 보안 강화
```
8080 웹 관리 서버와 IPP/9100 서버의 보안을 검토해줘.
- 비인가 접속 차단
- 로컬 네트워크만 허용하는 IP 필터링
- 관리자 인증 토큰 검증
- 입력값 검증 (인젝션 방지)
```
