# CLAUDE.md — 불변 규칙
- 기준 문서는 docs/PRD.md. 요구사항은 ID로 참조하고 범위 밖 기능은 추가하지 않는다.
- 화면과 위젯은 로컬 DB(Room)만 읽는다. 네트워크 호출은 sync 모듈에서만 한다.
- 메인 스레드에서 네트워크·DB·JSON 파싱 금지. 디버그 빌드는 StrictMode로 위반을 잡는다.
- 노션 쓰기는 모두 아웃박스 큐를 거친다. 화면이나 위젯에서 API를 직접 호출하지 않는다.
- Notion 토큰은 코드·로그·커밋·테스트 픽스처에 넣지 않는다.
- 데이터가 바뀌면 즉시 위젯 갱신을 요청한다. 위젯 코드에서 네트워크를 호출하지 않는다.
- Phase 종료 시 ./gradlew test lint 통과, 실기기 설치, adb 스크린샷으로 화면 확인. 확인 못 한 항목은 '미검증'으로 보고한다.

# 개발 환경 (Windows 11)
- PRD §4는 맥북 기준이지만 실제 개발은 Windows PC에서 한다. 셸은 PowerShell, Bash(Git Bash) 둘 다 있다.
- JDK: Android Studio 내장 JBR (`C:\Program Files\Android\Android Studio\jbr`). PATH에 없으므로 빌드 전 `JAVA_HOME`을 이 경로로 지정한다.
- SDK: `%LOCALAPPDATA%\Android\Sdk` (local.properties의 sdk.dir). adb: `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`.
- 빌드: `gradlew.bat test lint assembleDebug`. 스크린샷: `adb exec-out screencap -p > docs/shots/<이름>.png`.
- StrictMode 확인(PF-03): `adb logcat -d -s NotionCalStrictMode:W` 결과가 0줄이어야 한다. One UI 내부 위반(Typeface.setFlipFonts, IdsController, InputRune, 권한 창 등 startActivity 때 시스템 서버 ASKS)은 D 레벨 'OEM 위반 무시'로만 남는다. 새 OEM 예외는 근거 스택을 확인한 뒤에만 `StrictModeSetup.oemFrames`에 추가한다.

# 모듈 규칙 (PRD §7)
- `:app` → `:feature:*`, `:widget`, `:core:sync`, `:core:data`, `:core:designsystem`
- `:core:sync` → `:core:notion`, `:core:data`, `:core:weather`
- `:core:weather` → 의존 없음(OkHttp·kotlinx.serialization만). 날씨 제공처(Open-Meteo 기본 모델)를 가둔다. `:core:sync`의 WeatherWorker만 부른다. 화면·위젯은 `:core:data`의 `WeatherRepository`(Room)만 읽는다.
- `:widget` → `:core:data`, `:core:designsystem`만. `:core:notion` 의존 금지(위젯 네트워크 호출을 빌드 단계에서 차단).
- `:core:designsystem` → `:core:data`만(Compose 테마·글꼴·모양, UI 전용, 네트워크·DB 없음). 화면 틀 색 값은 `:core:data`의 `DesignTokens`. 기준 문서는 DESIGN.md.
- `:feature:*` → `:core:data`, `:core:designsystem`(+ 화면 간 공유는 `:feature:quicksheet`).
- `:feature:diagnostics` → `:core:data`만. 다음 동기화 시각 등 sync 정보는 `:core:data` 인터페이스(`SyncScheduleInfo`)로 받는다.
- `:baselineprofile`(com.android.test) → `:app` 측정 전용. 실행: `gradlew :app:generateBaselineProfile`, `:baselineprofile:connectedBenchmarkReleaseAndroidTest`.
- debug 전용 코드는 소스 세트를 나누지 않고 `BuildConfig.DEBUG`로 막는다(benchmarkRelease 빌드가 `src/release`를 쓰지 않음).
- 공통 빌드 설정은 `build-logic/` convention plugin(`notioncal.android.*`)에 둔다.
- 라이브러리 버전은 `gradle/libs.versions.toml`에만 적는다. 알파·베타 금지.
