# CLAUDE.md — 불변 규칙 (새 앱 시작용 초안: 새 저장소 루트의 CLAUDE.md로 복사)

> **상태: 대체됨(2026-11-02).** 새 저장소를 전제로 쓴 초안이다. 같은 앱을 이어 가기로 해서 루트 `CLAUDE.md`가 기준이고, 모듈 구조는 `docs/PRD-v2.md` §7.1(정렬 전 초안)을 본다.

- 기준 문서는 docs/PRD.md. 요구사항은 ID로 참조하고 범위 밖 기능은 추가하지 않는다. 조사 근거는 docs/RESEARCH.md.
- 화면과 위젯은 로컬 DB(Room)만 읽는다. 네트워크 호출은 `:core:sync`에서만 한다. `:widget`은 `:core:notion`에 의존하지 않는다(빌드 단계에서 차단).
- 메인 스레드에서 네트워크·DB·JSON 파싱·이미지 디코딩 금지. 위젯 렌더 경로도 같다. 디버그 빌드는 StrictMode로 잡는다.
- 노션 쓰기는 모두 아웃박스 큐를 거친다. 화면이나 위젯에서 API를 직접 호출하지 않는다. 쓰기는 낙관적으로 로컬에 먼저 반영한다.
- Notion 토큰은 코드·로그·커밋·테스트 픽스처에 넣지 않는다. 픽스처는 실제 DB 응답을 **합성 값으로 바꾼 것**만 둔다(제목·이름·이메일·금액·URL·ID 전부).
- 속성 유형은 닫힌 집합이 아니다. 모르는 유형·모르는 키·모르는 보기 유형은 예외 없이 `Unsupported`로 받아 읽기 전용으로 표시하고 앱은 죽지 않는다.
- 읽기 전용 속성(수식·롤업·생성 일시·생성자·최종 편집 일시·최종 편집자·버튼·ID)과 `Unsupported`는 쓰기 경로에 들어올 수 없게 **타입으로 막는다**.
- 속성 매핑·설정은 속성 이름이 아니라 ID로 저장한다. 노션에 보낼 때만 그 순간 스키마에서 ID→이름을 찾아 키로 쓴다(ID 키는 노션이 오류 없이 무시한다).
- 필수 칸을 빈 문자열 같은 대체값으로 때우지 않는다. nullable로 두고 종류별로 처리한다.
- 데이터가 바뀌면 즉시 위젯 갱신을 요청한다. 새 위젯 종류를 추가하면 갱신 목록·"배치된 위젯 있음" 판단·자정 알람·미리보기를 함께 고친다.
- 위젯 이미지·차트 비트맵은 메모리 예산 안에서만 만든다(`6×폭×높이` 바이트, 보수적으로 `폭×높이×4×1.5` 이하). 렌더 경로에서 만들지 않고 동기화·갱신 작업에서 만들어 디스크에서 읽는다.
- Glance `Row`·`Column` 자식은 10개 이하. 넘기면 중첩·`Lazy*`로 나눈다.
- 알파·베타 라이브러리 금지. 버전은 `gradle/libs.versions.toml`에만 적는다.
- 구현은 테스트 먼저. 순수 로직(뷰 엔진·파서·통계)은 JVM 단위 테스트로 고정한다. 장애를 고치면 그 장애를 재현하는 테스트를 먼저 추가한다.
- Phase 종료 시 `gradlew.bat test lint assembleDebug` 통과, 실기기 설치, adb 스크린샷으로 화면 확인. 확인 못 한 항목은 '미검증'으로 보고한다. 거짓으로 '통과'라고 쓰지 않는다.

# 개발 환경 (Windows 11)
- 셸은 PowerShell, Git Bash 둘 다 있다. JDK는 Android Studio 내장 JBR(`C:\Program Files\Android\Android Studio\jbr`)을 빌드 전에 `JAVA_HOME`으로 지정한다.
- SDK: `%LOCALAPPDATA%\Android\Sdk`, adb: `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`. 스크린샷: `adb exec-out screencap -p > docs/shots/<이름>.png`.
- StrictMode 확인: `adb logcat -d -s <태그>:W` 결과가 0줄이어야 한다. 새 OEM 예외는 근거 스택을 확인한 뒤에만 허용 목록에 넣는다.
- 공통 빌드 설정은 `build-logic/` convention plugin에 둔다. debug 전용 코드는 `BuildConfig.DEBUG`로 막는다.

# 모듈 규칙 (RESEARCH §5)
- `:app` → 모든 `:feature:*`, `:widget`, `:core:*`
- `:feature:*` → `:core:model`, `:core:data`, `:core:viewengine`, `:core:designsystem` (네트워크·sync 의존 금지)
- `:widget` → `:core:model`, `:core:data`, `:core:viewengine`, `:core:designsystem`만
- `:core:viewengine` → `:core:model`만(순수 Kotlin, Android 의존 금지)
- `:core:sync` → `:core:notion`, `:core:data`, `:core:model`
- `:core:notion` → `:core:model`만
