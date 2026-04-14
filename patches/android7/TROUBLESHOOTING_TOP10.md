# 인기도서 TOP10 그리드 표시 안 됨 — 트러블슈팅 (2026-04-14)

## 증상

- A40i (Android 7) 기기에서 독서로 페이지 로드 시 인기도서 TOP10 섹션이 보이지 않음
- 인쇄 기능은 정상 동작
- 동일 버전(v1.6.5) 설치된 다른 A40i 기기에서는 정상 표시

## 원인 분석

### 1차 원인: `top_book_grid` 설정값 `false`

문제 기기의 SharedPreferences(`libro_prefs.xml`)에 `top_book_grid`가 `false`로 저장되어 있었음.
기본값은 `true`이지만, 관리자 화면에서 수동으로 끈 적이 있거나 이전 버전에서 마이그레이션 시 `false`로 저장됨.

잘 되는 기기는 이 키 자체가 없어서 기본값 `true`가 적용됨.

**비교:**
| 항목 | 문제 기기 (가재초) | 정상 기기 (서울고산초) |
|------|-------------------|---------------------|
| `top_book_grid` | `false` (명시 저장) | 키 없음 (기본값 `true`) |
| framework-res | 25,686,016 (패치됨) | 25,713,345 (원본) |

### 2차 원인: `document.head` null 에러

`onPageFinished`에서 CSS를 주입할 때 `document.head.appendChild(style)`를 호출하는데,
Vue SPA 특성상 `onPageFinished` 시점에 `document.head`가 `null`인 경우가 있음.

logcat 에러:
```
[INFO:CONSOLE(5)] "Uncaught TypeError: Cannot read properties of null (reading 'appendChild')"
```

이 에러로 인해 CSS 주입뿐 아니라 폰트/select 스타일 수정도 실패함.

## 해결

### 코드 수정 (`WebPrintActivity.kt`)

`document.head.appendChild()`를 `(document.head || document.documentElement).appendChild()`로 변경 (2곳):

- 218행: select/font 스타일 주입
- 243행: TOP10 그리드 CSS 주입

### 설정 수정

문제 기기에서 `top_book_grid`를 `true`로 변경:
- 관리자 화면 → "인기도서 TOP10 그리드 표시" 토글 ON
- 또는 SharedPreferences 직접 수정

## A40i 초기 설치 시 누락되기 쉬운 항목

이번 사례에서 문제 기기에 누락되었던 패치 목록:

| 패치 | 상태 | 비고 |
|------|------|------|
| PrintSpooler 패치 (696KB) | 미적용 → 적용 | mdpi drawable 크래시 수정 |
| Chrome 113 | 미설치 (v76) → 설치 | WebView 엔진 |
| framework-res 패치 | 미적용 → 적용 | Chrome을 WebView provider로 등록 |
| 부트 스크립트 | 미설치 → 설치 | 재부팅 시 설정 자동 복원 |
| 인쇄 서비스 활성화 | `null` → 활성화 | `enabled_print_services` |
| WRITE_SECURE_SETTINGS | 미부여 → 부여 | 앱 자동 인쇄 드라이버 재활성화 |
| install_non_market_apps | `0` → `1` | OTA 업데이트용 |

**교훈:** 새 기기에는 반드시 `setup_a40i.sh` 스크립트를 실행할 것.
수동으로 개별 패치하면 누락 위험이 높음.
