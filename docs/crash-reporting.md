# 크래시 수집 도구 비교 (PF-04)

2026-10-13 작성. PRD §15 결정 항목. **선택은 사용자가 하고, 연결은 선택 후(늦어도 Phase 4 전)에 한다.**

## 결정 (2026-10-14, Phase 4)

**Sentry**로 연결했다. 구현은 `app/.../crash/CrashReporting.kt`.

- 의존: `io.sentry:sentry-android-core` 8.60.0. 화면 녹화(replay)·NDK 모듈은 넣지 않는다(일정 내용이 화면에 보이고, 네이티브 코드가 없음).
- DSN: `local.properties`의 `sentry.dsn=...`. 빌드 때 `BuildConfig.SENTRY_DSN`으로 들어가고 비어 있으면 수집하지 않는다. 저장소에는 넣지 않는다.
- 초기화: 매니페스트에서 자동 초기화를 끄고(`io.sentry.auto-init=false`) `NotionCalApp`이 IO 스레드에서 시작한다(PF-03). 초기화 전 몇 ms의 크래시는 놓칠 수 있다.
- 대상 빌드: debug·release(일상 사용이 debug 빌드라 debug도 수집, `environment`로 구분). 성능 측정 빌드(benchmarkRelease 등)는 끈다.
- 켠 것: ANR(debug 포함), Android 17 메모리 한도 종료(`isMemoryLimiterEnabled`).
- 끈 것: 화면 캡처, 뷰 계층, 사용자 상호작용·네트워크 브레드크럼, 기본 PII.
- SEC-02: `beforeSend`가 예외 메시지·메시지·브레드크럼의 토큰 형식과 따옴표 안 문자열(노션 응답·제목)을 지우고, HTTP 브레드크럼은 버린다(`CrashReportingTest`).
- 한계: Gradle 플러그인을 쓰지 않아 release의 R8 매핑 파일이 올라가지 않는다. release 스택은 난독화된 채 보인다.

## 요약

| 항목 | Firebase Crashlytics | Sentry |
| --- | --- | --- |
| 필요한 계정·파일 | Google 계정 + Firebase 프로젝트, `google-services.json`(앱 ID 등록 필요, 디버그 `.debug` 접미사도 별도 등록) | Sentry 계정 + 프로젝트, DSN 문자열 1개 |
| Gradle 설정 | 플러그인 2개(`com.google.gms.google-services` 4.5.0, `com.google.firebase.crashlytics` 3.0.8) + Firebase BoM 35.0.0 | 플러그인 1개(`io.sentry.android.gradle`, 선택) + `io.sentry:sentry-android` 8.60.0 |
| 비용 | 무료, 건수 제한 없음 | 무료(Developer) 플랜은 월 오류 건수 한도 있음 — 개인 1인 사용이면 충분할 것으로 보이나 **한도 수치는 가입 시 확인** |
| ANR 수집 | 지원(Android 11+는 ApplicationExitInfo 기반) | 지원. Android 11+는 ApplicationExitInfo 기반(v2), Play Console과 같은 데이터 원천 |
| Android 17 메모리 한도 종료(S25-09) | 별도 지원 확인 안 됨 → 앱이 ApplicationExitInfo를 직접 읽어야 함 | **Memory Limiter 통합**(SDK 8.57.0+, API 37+, 기본 꺼짐)이 `MemoryLimitExceeded` 치명 이벤트로 수집 |
| 전송 전 가공(SEC-02 마스킹) | 전송 직전 훅 없음. 커스텀 로그·키에 민감 정보를 애초에 넣지 않는 방식으로만 통제 | `beforeSend`/`beforeBreadcrumb` 콜백으로 이벤트를 고치거나 버릴 수 있음 |
| 자동 수집으로 인한 노출 위험 | 낮음(스택트레이스·기기 정보 위주) | 기본 브레드크럼·OkHttp 통합 등이 URL·화면 정보를 남길 수 있어 **꺼야 할 통합을 명시적으로 정해야 함** |
| 개인 APK(Play 미배포) | 문제 없음. 매핑 파일 업로드는 플러그인이 처리 | 문제 없음. 매핑 파일 업로드는 Gradle 플러그인(auth token 필요) 또는 수동 |
| 비밀값 관리 | `google-services.json`은 저장소에 넣지 않음(.gitignore 등록됨) | DSN은 공개돼도 수신만 가능한 값이지만 저장소에는 넣지 않음. 업로드용 auth token은 `sentry.properties`(.gitignore 등록됨) |
| 앱 시작 비용(PF-01) | ContentProvider 자동 초기화 | ContentProvider 자동 초기화(수동 초기화로 전환 가능) |

## 판단 포인트

- **Android 17 메모리 한도 종료를 자동으로 잡고 싶다** → Sentry가 유리. Crashlytics를 고르면 S25-09 진단 화면용으로 ApplicationExitInfo 읽기를 어차피 직접 구현해야 한다(이건 어느 쪽이든 Phase 4에서 구현 예정).
- **설정 파일·플러그인 수를 줄이고 마스킹을 코드로 강제하고 싶다** → Sentry(`beforeSend`에서 토큰·일정 제목 제거, SEC-02 테스트 대상이 명확).
- **비용 한도를 신경 쓰고 싶지 않고 Google 계정 하나로 끝내고 싶다** → Crashlytics.

## 선택 후 작업(Phase 4 이전)

1. 계정·프로젝트 생성은 사용자가 한다. 키 파일·DSN은 저장소 밖(`local.properties` 또는 환경 변수)에서 읽게 한다.
2. 릴리스 빌드에서만 전송하고 디버그 빌드는 끈다.
3. SEC-02: 토큰·일정 제목 마스킹 단위 테스트 추가.
4. 테스트 크래시를 일으켜 대시보드 표시 확인(PF-04 완료 기준).

## 출처

- Sentry ANR: https://docs.sentry.io/platforms/android/configuration/app-not-respond
- Sentry Memory Limiter: https://docs.sentry.io/platforms/android/configuration/memory-limiter/
- 버전 값: Maven Central(`io.sentry:sentry-android`), Google Maven(`firebase-bom`, `firebase-crashlytics-gradle`, `google-services`) 메타데이터, 2026-10-13 조회
