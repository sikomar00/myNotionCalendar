# 퍼펙트 노션 캘린더 Android PRD

Oct 12, 2026 · @Omar Kim

## 0. 이 문서 사용법

이 문서를 Markdown으로 내보내 프로젝트의 `docs/PRD.md`로 저장하고, Claude Code에는 한 번에 한 Phase(§11)씩 구현을 지시한다.

- 저장: 이 문서 → Markdown 내보내기 → `docs/PRD.md`. 아래 불변 규칙은 프로젝트 루트 `CLAUDE.md`에 복사한다.
- 진행: 계획 → 구현 → §12 완료 기준 검증 → 커밋. 다음 Phase는 이전 Phase의 완료 기준을 통과한 뒤 시작한다.
- 요구사항 ID: `MV`(월별 보기), `WG`(위젯), `WS`(위젯 배율), `WI`(위젯 상호작용), `NS`(노션 동기화), `CAL`(폰 캘린더), `PF`(성능·안정성), `S25`(기기 최적화), `EX`(추가 기능), SEC(보안), A11Y(접근성). 커밋 메시지에 ID를 적는다.
- 우선순위: P0 = 첫 사용 가능 버전 필수, P1 = 다음 개선, P2 = 이후.
- 근거 표기: \[리뷰\] 리뷰·평가에서 확인, \[요구\] 사용자 지정, \[제약\] 플랫폼·API 사실.

```markdown
# CLAUDE.md — 불변 규칙
- 기준 문서는 docs/PRD.md. 요구사항은 ID로 참조하고 범위 밖 기능은 추가하지 않는다.
- 화면과 위젯은 로컬 DB(Room)만 읽는다. 네트워크 호출은 sync 모듈에서만 한다.
- 메인 스레드에서 네트워크·DB·JSON 파싱 금지. 디버그 빌드는 StrictMode로 위반을 잡는다.
- 노션 쓰기는 모두 아웃박스 큐를 거친다. 화면이나 위젯에서 API를 직접 호출하지 않는다.
- Notion 토큰은 코드·로그·커밋·테스트 픽스처에 넣지 않는다.
- 데이터가 바뀌면 즉시 위젯 갱신을 요청한다. 위젯 코드에서 네트워크를 호출하지 않는다.
- Phase 종료 시 ./gradlew test lint 통과, 실기기 설치, adb 스크린샷으로 화면 확인. 확인 못 한 항목은 '미검증'으로 보고한다.
```

## 1. 배경과 문제 정의

노션 캘린더 Android는 Google Play 평점 3.8(리뷰 약 1.74만 건)이다. 리뷰 기준 모바일 앱은 최대 3일 보기만 있고, 2026년 6\~9월 리뷰에는 실행 불가와 로그인 풀림이 몰려 있다([Google Play](https://play.google.com/store/apps/details?id=com.cron.calendar)). 이 앱은 여러 노션 DB의 일정을 S25 홈 화면에서 월 단위로 보고, 위젯에서 바로 추가·완료하며, 노션 원본 DB와 양방향으로 맞춘다.

- 노션 캘린더 Android는 2024-07-17 출시됐고, 구글 캘린더 계정 연동이 중심이며 노션 DB 항목을 함께 보여주는 구조다([Notion 릴리스](https://www.notion.com/releases/2024-07-17)).
- 이 앱에서는 노션 DB가 주 데이터다. 폰 캘린더(구글·삼성)는 읽기 전용 보조 데이터다.
- 서버는 없다. 폰이 노션 API를 직접 호출하고, 데이터는 폰의 로컬 DB에 캐시한다.

핵심 사용 시나리오

1. 홈 화면 월간 위젯에서 이번 달 노션 일정(여러 DB, DB별 색)과 폰 캘린더 일정을 앱을 열지 않고 본다.
2. 위젯의 날짜를 눌러 일정을 추가하면 노션 DB에 페이지가 생긴다.
3. 위젯에서 체크박스를 누르면 노션의 체크박스 속성이 바뀐다.
4. 맥북에서 노션을 고치면 주기 동기화로 폰에 반영되고, 앱을 열면 즉시 갱신된다.
5. 오프라인에서도 마지막 데이터로 바로 열리고, 입력은 저장해 두었다가 연결되면 노션에 반영된다.

## 2. 리뷰·평가 조사 결과

수집한 Android 리뷰 35건 중 15건이 '앱이 안 열리거나 꺼짐'을 지목했다. 그중 12건이 2026년 6\~9월 작성이고, 로그인 풀림 리뷰는 전부 2026년 6\~8월 작성이다.

&#91;embedded content: Google Play 웹 노출 리뷰 31건(10개 언어 페이지) + AppBrain 노출 4건 · 작성 2024-08\~2026-10 · 2026-10-12 수집\]

상위 3개(실행 불가, 로그인, 월·주 보기)는 기능 부족보다 '앱을 믿고 열 수 있는가'의 문제다. 그래서 이 PRD는 안정성과 오프라인 우선 구조를 P0로 둔다.

### 조사 범위와 한계

- Google Play 웹은 언어별 페이지에 리뷰를 3건씩만 보여준다. 10개 언어 페이지(영어는 3개 지역)에서 고유 리뷰 31건, [AppBrain](https://www.appbrain.com/app/notion-calendar/com.cron.calendar)에서 4건을 모았다.
- 노출 순서는 Google의 유용성 기준이라 무작위 표본이 아니다. 비율은 우선순위 참고용이다.
- 전체 약 1.74만 건의 전수 수집은 이 작업 환경에서 Google Play 서버 접속이 차단돼 하지 못했다. 맥북에서 돌릴 수집 스크립트를 별도 파일로 제공한다.
- 평점 분포(AppBrain, 17,414건)는 1\~2점 37%, 3점 4%, 4\~5점 59%로 양극화돼 있다.
- 보조 근거: [App Store](https://apps.apple.com/us/app/notion-calendar/id1607562761) 리뷰, [worldsapps 리뷰 모음](https://worldsapps.com/reviews-notion-calendar), [marlvel.ai 리뷰 분석](https://marlvel.ai/apps/notion-calendar), [toolguide](https://toolguide.io/en/tool/notion-calendar/), [unstar 1\~3점 리뷰 분석](https://unstar.app/hi/blog/fantastical-google-apple-outlook-notion-calendar-apps-ranked-2026).

### 불편 사항과 이 앱의 대응

| 불편 사항 | 근거 | 이 앱의 대응 (요구사항 ID) |
| --- | --- | --- |
| 실행 불가·크래시: 로고·검은 화면에서 멈춤, 업데이트 후 안 열림, Galaxy S25에서 실행 안 됨 | Play 2026-06\~09, S25 사용자 리뷰 2026-08 | 로컬 캐시 우선 실행, DB 마이그레이션 안전장치, 크래시 수집 (PF-01, PF-02, PF-04, PF-09) |
| 로그인 풀림·로그인 루프: 하루 2회 재로그인, 그동안 위젯이 빈 화면 | Play 2026-06\~08 | 만료 없는 내부 통합 토큰, 인증 오류여도 캐시 표시 (NS-01, PF-05, WG-07) |
| 월·주 보기 없음: 최대 3일 보기 | Play 2024-08\~2026-09, App Store·worldsapps 2026-02 | 월 그리드, 주·3일·목록 보기 (MV-01\~MV-04, MV-06) |
| 노션 연동 한계: 위젯에 노션 항목 없음, 항목명 대신 DB명 표시, 속성 보기·편집 불가, 노션 항목 반복 불가 | Play 2024-10\~2026-09 | 페이지 제목으로 표시, 속성 편집, 반복 일괄 생성 (WG-01, MV-05, EX-07, NS-10) |
| 화면 배율·색상: 확대할 수 없어 정보가 적게 보임, 색 선택지 부족 | Play 2025-03\~2026-08 | 앱·위젯 배율 조절, DB·옵션별 색 (WS-02, MV-08, EX-03) |
| 알림: 최대 1시간 전, 노션 항목 알림 없음 | Play 2025-07·2026-07, toolguide | 시간 자유 설정·복수 알림 (EX-01) |
| 로딩·위젯 열림 지연 | Play 2026-07, AppBrain 2026-09 | 1초 내 표시, 위젯은 캐시만 읽음 (PF-01, WG-02) |
| 동기화 실패·일정 소실: 생성 직후 삭제, 지운 일정 잔존 | Play 2026-07, AppBrain 2026-09, App Store·worldsapps 2024-04 | 아웃박스 큐, 삭제 감지, 속성 단위 충돌 처리 (NS-03\~NS-05) |
| 입력 오류: 시간 입력 버그, 시간 칸 탭 무반응, 날짜·반복 요일이 하루 밀림 | Play 2025-05·2026-07, worldsapps 2024 | 퀵 입력 시트, 날짜·시간대 테스트 (WI-02, NS-07) |
| 노션 페이지 유실: 일정 변환 시 노션 페이지 삭제 | worldsapps 2024-07 | 삭제는 휴지통 이동만, 변환 기능 없음 (NS-04) |
| 다른 앱에서 노션 일정이 안 보임: 휴대폰 비서가 접근 못 함 | Play 2025-05 | 노션 일정을 폰 캘린더(로컬)로 미러링 (EX-08) |
| 동기화 지연: 노션과 캘린더 사이 반영이 늦음 | unstar 리뷰 분석 | 앱·위젯 조작 시 즉시 동기화 + 주기 동기화 (NS-06) |
| 오프라인: 보기만 되고 편집·동기화는 안 됨 | toolguide 2026-09 | 오프라인 편집을 아웃박스에 저장 후 전송 (PF-02, NS-04) |

## 3. 목표, 비목표, 성공 지표

P0 버전의 합격선은 'S25에서 1초 안에 월 화면이 뜨고, 위젯 조작이 노션에 반영되며, 2주 실사용 동안 크래시 0건'이다.

목표

- 여러 노션 DB와 폰 캘린더를 한 월 화면과 한 위젯에 표시한다.
- 위젯에서 보기·추가·완료·월 이동을 앱 진입 없이 처리한다.
- 네트워크·인증 상태와 무관하게 마지막 데이터를 즉시 보여준다.

성공 지표

| 지표 | 목표 | 측정 방법 |
| --- | --- | --- |
| 콜드 스타트 → 월 화면 첫 프레임 | 1.0초 이하 (릴리스 빌드, S25) | Macrobenchmark StartupTimingMetric 10회 중앙값 |
| 위젯 월 이동·체크 반영 | 0.5초 이내 | 액션 수신과 위젯 갱신 완료 로그의 시간 차 |
| 앱·위젯 변경 → 노션 반영 (온라인) | 5초 이내 | 아웃박스 작업 완료 로그 |
| 노션 변경 → 앱 반영 (앱을 열 때) | 3초 이내 (현재 월 증분 동기화) | 동기화 로그 |
| 노션 변경 → 위젯 반영 (앱 미실행) | 설정 주기(기본 15분) + 5분 이내가 90% 이상, 절전 예외 등록 상태 기준 | 동기화 로그 2주 집계 |
| 크래시·ANR | 2주 실사용 0건 | 크래시 수집 도구 + logcat |
| 월 스와이프 프레임 | 느린 프레임 5% 미만 (120Hz) | JankStats |
| 동기화 정확도 | 노션과 로컬 대조 불일치 0건 | 디버그 메뉴의 대조 기능 (§12) |

비목표

- 노션 페이지 본문 편집. 본문은 노션 앱으로 연다.
- 구글·삼성 캘린더 일정의 생성·수정. 읽기 전용이다.
- 회의 예약 링크, 초대, 팀 공유.
- iOS, iPad, Wear OS.
- 서버와 푸시(노션 웹훅). 주기 동기화로 결정했다.
- Play 스토어 배포. 개인 설치(APK)가 전제다.
- 노션 DB 스키마 생성·변경. 기존 속성만 쓴다.

## 4. 대상 환경

대상 기기는 Galaxy S25(One UI 9.0, Android 17 = API 37)이고, 개발은 맥북(M1 Pro)에서 Claude Code와 Android Studio로 한다.

| 항목 | 값 | 근거 |
| --- | --- | --- |
| 기기 | Galaxy S25 기본 모델. 6.2인치 1080×2340(약 416ppi), 최대 120Hz, RAM 12GB | [GSMArena](https://www.gsmarena.com/samsung_galaxy_s25-13610.php) |
| OS | One UI 9.0 / Android 17. S25 시리즈 국내 안정판 배포는 2026-09 말 시작 | [Sammyfans](https://www.sammyfans.com/2026/09/28/samsung-galaxy-s25-one-ui-9-stable-update/amp/) |
| compileSdk / targetSdk | 37 | [Android 17 동작 변경](https://developer.android.com/about/versions/17/behavior-changes-17) |
| minSdk | 31 (Android 12). 반응형 크기·체크박스 등 현행 위젯 API 기준. 보조 기기 테스트도 가능 | \[제약\] |
| 위젯 라이브러리 | Jetpack Glance 1.2.0 안정판(2026-08-26). 1.3 알파는 쓰지 않는다 | [Glance 릴리스](https://developer.android.com/jetpack/androidx/releases/glance) |
| Notion API | `Notion-Version: 2026-03-11` | [Notion versioning](https://developers.notion.com/reference/versioning) |
| 개발 PC | MacBook M1 Pro, Android Studio 최신 안정판, Claude Code | 사용자 환경 |
| 실기기 연결 | USB 디버깅 또는 무선 디버깅(adb pair) |  |
| 보조 테스트 기기 | Galaxy Tab S9 (큰 화면·창 크기 변경 확인용) | 사용자 보유 |
| 언어·지역 | 한국어 UI, Asia/Seoul, 주 시작 요일 설정(기본 일요일) |  |

라이브러리 버전은 Claude Code가 Phase 0에서 그 시점 최신 안정판을 확인해 버전 카탈로그(`libs.versions.toml`)에 고정한다. 알파·베타는 쓰지 않는다.

## 5. 기능 요구사항

P0는 월 보기, 월간 위젯, 배율 조절, 위젯 입력, 노션 양방향 동기화다. 리뷰에서 나온 나머지는 P1·P2로 둔다. 성능·안정성(PF)과 기기 최적화(S25)는 §6·§9에 있다.

### 5.1 월별 보기 (MV)

| ID | 우선 | 요구사항 | 완료 기준 | 근거 |
| --- | --- | --- | --- | --- |
| MV-01 | P0 | 6주 월 그리드. 주 시작 요일(일/월), 오늘 강조, 토·일·공휴일 색 구분 | 5주·6주짜리 달 화면 테스트 통과, 설정 변경 즉시 반영 | 요구 |
| MV-02 | P0 | 여러 날 일정은 이어진 막대로 표시. 칸별 표시 개수는 자동 계산하고 넘치면 '+N' | 같은 날 5개·3일짜리 3개 픽스처에서 누락·겹침 0 | 리뷰(겹친 일정 사라짐) |
| MV-03 | P0 | 좌우 스와이프로 월 이동, 연·월 선택기, 오늘 버튼 | 로컬 데이터 기준 월 전환 100ms 이내 | 요구 |
| MV-04 | P0 | 날짜를 누르면 그날 목록 시트: 체크, 편집, 추가 | 시트의 변경이 월 화면·위젯에 1초 안에 반영 | 요구 |
| MV-05 | P0 | 일정 이름은 페이지 제목 + DB 색 점. 완료 항목은 취소선(숨김 선택) | DB 이름만 보이는 경우 0 | 리뷰(DB명만 표시) |
| MV-06 | P1 | 주·3일·목록(아젠다) 보기 | 보기를 바꿔도 같은 날짜 유지 | 리뷰 |
| MV-07 | P1 | 일정을 길게 눌러 다른 날로 끌어 옮기면 노션 날짜 갱신 | 시간·기간 길이 유지 | 노션 캘린더 데스크톱 기능 |
| MV-08 | P1 | 화면 배율: 두 손가락으로 칸 높이·글자 크기 조절, 값 저장 | 70\~150%, 재실행 후 유지 | 리뷰(확대 불가) |
| MV-09 | P1 | 필터: DB별 표시·숨김, 완료 숨김, select 값별 숨김 | 앱 필터와 위젯 설정이 따로 저장 | 요구 |
| MV-10 | P1 | 제목 검색(로컬 캐시) | 1,000건에서 100ms 이내 |  |

### 5.2 월간 위젯 (WG)

| ID | 우선 | 요구사항 | 완료 기준 | 근거 |
| --- | --- | --- | --- | --- |
| WG-01 | P0 | Jetpack Glance 월 그리드 위젯. 노션 DB 항목과 폰 캘린더 일정을 각각의 색으로 표시 | 노션 항목이 위젯에서 빠지는 경우 0 | 리뷰(위젯에 노션 항목 없음) |
| WG-02 | P0 | 위젯은 로컬 DB만 읽는다. 위젯 코드에서 네트워크 호출 금지 | 비행기 모드에서 정상 표시 | 리뷰(위젯 지연) |
| WG-03 | P0 | 자정, 날짜·시간·시간대 변경 시 즉시 갱신 | 자정 후 1분 안에 오늘 칸 이동 | \[제약\] |
| WG-04 | P0 | 위젯별 설정: 표시 DB, 테마, 투명도, 배율, 표시 개수. 설정 화면에 실시간 미리보기 | 위젯 2개를 서로 다른 설정으로 운용 | 요구 |
| WG-05 | P0 | 데이터가 바뀌면 모든 위젯을 즉시 갱신 | 0.5초 이내(§3) | 요구 |
| WG-06 | P1 | 보조 위젯: 오늘·다가오는 일정 목록 | 2×2\~4×2에서 잘림 0 | 노션 캘린더 기능 |
| WG-07 | P1 | 마지막 동기화 시각과 오류 점 표시 | 토큰 무효 상태에서도 내용 유지 + 오류 표시 | 리뷰(위젯 빈 화면) |
| WG-08 | P2 | 위젯 선택 화면 미리보기(Glance providePreview) |  | [Glance 릴리스](https://developer.android.com/jetpack/androidx/releases/glance) |

### 5.3 위젯 배율 조절 (WS)

| ID | 우선 | 요구사항 | 완료 기준 | 근거 |
| --- | --- | --- | --- | --- |
| WS-01 | P0 | 홈 화면에서 크기 자유 조절. 기본 4×4, 최소 2×2, 최대 화면 전체 | One UI 홈 격자 설정 전부에서 최소\~최대 렌더링 확인 | 요구 |
| WS-02 | P0 | 내용 배율: 글자 70\~150%(10% 단위), 칸당 일정 수(자동 또는 0\~6), 줄 간격 | 설정 변경 1초 안에 반영 | 요구, 리뷰 |
| WS-03 | P0 | 크기별 단계 레이아웃: 작음(점만) / 중간(제목 1개 + N) / 큼(제목 여러 개) | SizeMode.Responsive 3단계 이상, 경계에서 잘림 0 | \[제약\] |
| WS-04 | P1 | 배경 투명도 0\~100%, 라이트·다크·시스템·Material You | 다크 모드 전환 즉시 반영 |  |
| WS-05 | P1 | One UI 글꼴 크기와 위젯 배율을 곱해 적용. 넘치면 한 단계 작은 레이아웃으로 자동 전환 | 시스템 글꼴 최대에서 칸 넘침 0 | \[제약\] |

### 5.4 위젯 입력·상호작용 (WI)

위젯은 RemoteViews가 지원하는 뷰만 쓸 수 있어 위젯 안에 글자 입력 칸을 둘 수 없다. 체크박스·스위치는 Android 12부터 지원되며 상태 저장은 앱이 맡는다([Android 위젯 문서](https://developer.android.com/develop/ui/views/appwidgets)). 그래서 입력은 위젯 위에 뜨는 반투명 시트로 받는다.

| ID | 우선 | 요구사항 | 완료 기준 | 근거 |
| --- | --- | --- | --- | --- |
| WI-01 | P0 | 날짜 칸 탭 → 그날 퀵 시트(반투명 Activity): 일정 목록 + 추가 입력칸, 키보드 자동 표시 | 탭에서 키보드까지 0.5초 이내 | \[제약\] |
| WI-02 | P0 | '+' 버튼 → 퀵 추가: 제목, 날짜(기본 선택일), 시간(선택), DB(기본 DB). 저장 즉시 위젯 반영, 노션은 아웃박스로 전송 | 오프라인 저장 후 연결되면 노션에 생성 | 요구 |
| WI-03 | P0 | 체크 토글: 위젯·퀵 시트에서 완료 처리. 체크박스 속성 또는 상태 속성의 완료 값으로 쓴다(§8) | 즉시 반영, 실패하면 되돌리고 표시 | 요구 |
| WI-04 | P0 | 이전·다음 달, 오늘 버튼. 위젯마다 보고 있는 달을 저장 | 0.5초 이내, 재배치 후에도 유지 | 요구 |
| WI-05 | P0 | 일정 탭 → 편집 시트: 제목·날짜·시간·완료·select 값, '노션에서 열기' | 노션 앱이 해당 페이지로 열림 | 요구 |
| WI-06 | P1 | '내일로' 버튼: 날짜 +1일. 📅 Calendar DB의 toTMR 버튼과 같은 동작을 앱이 날짜 속성을 직접 고쳐서 수행 | 종일·시간·기간 일정 모두 정확히 +1일 | 사용자 DB |
| WI-07 | P1 | 한국어 빠른 입력 해석: '내일 3시 회의', '10/20\~22 출장', '다음 주 월요일' | 규칙 기반 파서, 테스트 문장 50개 통과 |  |
| WI-08 | P1 | 음성 입력(키보드 음성 또는 RecognizerIntent) |  |  |

### 5.5 노션 양방향 연동 (NS)

| ID | 우선 | 요구사항 | 완료 기준 | 근거 |
| --- | --- | --- | --- | --- |
| NS-01 | P0 | 연결: 노션 내부 연결(internal connection) 토큰을 붙여넣고 연결 테스트. 토큰은 암호화 저장 | 잘못된 토큰이면 원인을 표시 | 리뷰(로그인 풀림) |
| NS-02 | P0 | DB 등록: DB 링크 붙여넣기 → data source 조회 → 속성 매핑(제목·날짜·완료·색 기준·정렬용 select) → DB 색 지정. 여러 DB | §8 프리셋의 두 DB 등록 성공 | 요구 |
| NS-03 | P0 | 읽기 동기화: 보이는 범위(기본 이번 달 ±2개월) 조회 + 증분 조회 + 삭제 감지 | 노션에서 생성·수정·삭제한 항목이 다음 동기화에 반영 | 리뷰(지운 일정 잔존) |
| NS-04 | P0 | 쓰기 동기화: 생성·수정·완료·삭제를 아웃박스 큐로 전송. 삭제는 휴지통 이동(in\_trash)만 | 비행기 모드에서 10건 작업 → 연결 후 10건 순서대로 반영 | 리뷰(일정 소실) |
| NS-05 | P0 | 충돌 처리: 바뀐 속성만 전송. 같은 속성이 양쪽에서 바뀌면 노션 값 우선, 내 변경은 충돌 기록에 남김 | §12 충돌 시나리오 3종 통과 | \[제약\] |
| NS-06 | P0 | 동기화 시점: 앱 열 때, 당겨서 새로고침, 위젯 새로고침 버튼, 주기(15·30·60분 선택), 네트워크 복구 | 트리거별 로그 확인 | 결정 사항 |
| NS-07 | P0 | 날짜 처리: 날짜만·시간 포함·기간 모두 지원, Asia/Seoul. 날짜만 있는 일정은 시간대 변환 금지 | 자정 전후·월말·윤년 테스트 통과 | 리뷰(날짜 하루 밀림) |
| NS-08 | P0 | 속도 제한 준수: 분당 180회 이하, 429·529는 Retry-After만큼 대기 후 재시도 | 대량 동기화 중 429에서 자동 회복 | [Notion 요청 제한](https://developers.notion.com/reference/request-limits) |
| NS-09 | P1 | 스키마 변경 감지: 매핑한 속성의 이름 변경·삭제 알림, 재매핑 유도 | 속성이 사라져도 앱이 죽지 않고 경고 | \[제약\] |
| NS-10 | P2 | 반복 일정: 앱에서 반복 규칙을 정하면 기간 내 페이지를 한 번에 생성 | 생성 전 미리보기와 개수 확인 | 리뷰(노션 항목 반복 불가) |

### 5.6 폰 캘린더 표시 (CAL)

| ID | 우선 | 요구사항 | 완료 기준 | 근거 |
| --- | --- | --- | --- | --- |
| CAL-01 | P1 | 폰 캘린더(구글·삼성) 읽기 전용 표시. READ\_CALENDAR 권한, 표시할 캘린더 선택, 캘린더 색 사용 | 권한을 거부해도 노션 일정은 정상 | 결정 사항 |
| CAL-02 | P1 | 폰 캘린더 일정 탭 → 원래 캘린더 앱으로 열기 | 편집은 그 앱에서 | 결정 사항 |
| CAL-03 | P1 | 위젯에도 표시(위젯별 켜기·끄기) |  | 결정 사항 |

### 5.7 추가 기능 (EX)

| ID | 우선 | 요구사항 | 완료 기준 | 근거 |
| --- | --- | --- | --- | --- |
| EX-01 | P1 | 알림: 시간 일정은 N분 전(자유 값, 여러 개), 종일 일정은 지정 시각 | 1시간보다 이른 알림 가능 | 리뷰(최대 1시간 전) |
| EX-02 | P1 | 한국 공휴일·대체공휴일: 폰의 구글 공휴일 캘린더가 있으면 사용, 없으면 [한국천문연구원 특일 정보 API](https://www.data.go.kr/data/15012690/openapi.do)(연 1회 캐시) | 2026·2027 공휴일 표시 | 한국 사용 환경 |
| EX-03 | P1 | 색: DB 색 또는 select 옵션 색 중 선택, 색상 선택기 | 📅 Calendar의 Ctgr 색(Event 노랑·Learn 파랑·Habit 빨강) 재현 | 리뷰(색 부족) |
| EX-04 | P2 | 음력 날짜 표시(선택) | 한국천문연구원 음양력 데이터로 검증 | 한국 사용 환경 |
| EX-05 | P2 | 공유 시트로 추가: 다른 앱의 글을 공유하면 퀵 추가 시트 |  |  |
| EX-06 | P2 | 빠른 설정 타일·앱 아이콘 길게 누르기 메뉴의 '빠른 추가' |  |  |
| EX-07 | P1 | 속성 보기·편집 확장: select·multi-select·status·number·url 표시 선택과 편집 | 편집 시트에서 수정 후 노션 반영 | 리뷰(속성 편집 불가) |
| EX-08 | P2 | 노션 일정을 폰 캘린더(로컬 계정)로 읽기 전용 미러링 | 끄면 미러 일정 완전 삭제 | 리뷰(다른 앱에서 안 보임) |
| EX-09 | P2 | 하위 항목 표시: 📅 Calendar의 SubItem 관계를 날짜 시트에 들여쓰기로 |  | 사용자 DB |
| EX-10 | P2 | 설정 백업: DB 매핑·위젯 설정 JSON 내보내기·가져오기(토큰 제외) | 재설치 후 복원 | 리뷰(업데이트 후 사용 불가) |

## 6. 비기능 요구사항

안정성 요구는 '앱은 항상 열린다'에 맞춘다. 로컬 캐시에서 즉시 그리고, 인증·네트워크·업데이트 실패가 화면을 막지 않게 한다. 이는 §2의 상위 두 불편(실행 불가, 로그인 풀림)에 대한 대응이다.

### 6.1 성능·안정성 (PF)

| ID | 우선 | 요구사항 | 완료 기준 |
| --- | --- | --- | --- |
| PF-01 | P0 | 콜드 스타트는 로컬 DB에서 바로 그리고, 동기화는 첫 프레임 뒤 백그라운드에서 한다. Baseline Profile 적용 | §3 목표 1.0초 |
| PF-02 | P0 | 오프라인 우선: 모든 화면·위젯이 네트워크 없이 동작, 쓰기는 아웃박스에 저장 | 비행기 모드 E2E 테스트 통과 |
| PF-03 | P0 | 메인 스레드에서 네트워크·DB·JSON 파싱 금지. 디버그 빌드 StrictMode로 탐지 | StrictMode 위반 로그 0 |
| PF-04 | P0 | 크래시·ANR 수집(Firebase Crashlytics 또는 Sentry 중 하나) + 앱 내 '로그 내보내기'(최근 동기화·오류 기록) | 테스트 크래시가 수집 대시보드에 표시 |
| PF-05 | P0 | 인증 실패(401)에도 캐시 표시를 유지하고 설정 안내 배너만 띄운다. 로그인 화면으로 강제 이동하는 흐름은 만들지 않는다 | 토큰 무효 테스트에서 화면·위젯 내용 유지 |
| PF-06 | P0 | 프로세스 종료·회전·창 크기 변경 후 상태 복원 | 개발자 옵션 '활동 유지 안 함'에서 입력 내용 유지 |
| PF-07 | P0 | 메모리: Android 17은 RAM 기준 앱 메모리 한도를 강제하고, 위젯 비트맵이 '화면 폭×높이×4바이트×1.5'를 넘으면 앱이 죽는다. 위젯은 비트맵 없이 레이아웃으로 그린다 | 위젯 IllegalArgumentException 0, 최대 크기 위젯 메모리 측정 |
| PF-08 | P1 | 대량 데이터: DB당 2,000건에서도 월 전환 100ms. Room 인덱스(dataSourceId, startDate) | 성능 테스트 통과 |
| PF-09 | P0 | 업데이트 안전: Room 스키마 버전 관리와 마이그레이션 테스트. 실패하면 캐시만 지우고 재동기화(토큰·설정·미전송 아웃박스 보존) | 이전 버전 DB 마이그레이션 테스트 통과 |
| PF-10 | P0 | 초기화가 실패해도 로고 화면에 멈추지 않고 진단 화면(오류 내용, 캐시 초기화, 로그 내보내기)을 띄운다 | 강제 예외 주입 테스트 통과 |

### 6.2 보안 (SEC)

| ID | 우선 | 요구사항 | 완료 기준 |
| --- | --- | --- | --- |
| SEC-01 | P0 | 토큰은 DataStore + Tink AEAD로 암호화하고 키는 Android Keystore에 둔다. EncryptedSharedPreferences는 쓰지 않는다(security-crypto 1.1.0에서 전부 deprecated) | 앱 데이터 파일에 평문 토큰 0 |
| SEC-02 | P0 | 로그·크래시 리포트에서 토큰과 일정 제목 마스킹 | 로그 검사 테스트 통과 |
| SEC-03 | P0 | 토큰 파일은 자동 백업에서 제외(dataExtractionRules) | 백업 규칙 파일 확인 |
| SEC-04 | P0 | HTTPS만 허용, 평문 통신 금지 | network security config 확인 |

### 6.3 접근성 (A11Y)

| ID | 우선 | 요구사항 | 완료 기준 |
| --- | --- | --- | --- |
| A11Y-01 | P1 | TalkBack: 날짜 칸을 '10월 12일, 일정 3개'처럼 읽고, 체크 상태를 읽는다 | TalkBack 수동 점검 |
| A11Y-02 | P1 | 위젯·앱 버튼 터치 영역 48dp 이상 | 레이아웃 검사 |
| A11Y-03 | P1 | 색만으로 구분하지 않는다. 완료는 취소선 + 체크 아이콘 | 흑백 화면 점검 |

근거: [Android 17 동작 변경(타깃 37)](https://developer.android.com/about/versions/17/behavior-changes-17), [Android 17 동작 변경(전체 앱)](https://developer.android.com/about/versions/17/behavior-changes-all), [EncryptedSharedPreferences 지원 중단 정리](https://blog.includesecurity.com/2026/08/encryptedsharedpreferences-is-dead-heres-what-you-should-use-instead/).

## 7. 아키텍처와 기술 스택

모든 화면과 위젯은 로컬 DB(Room)만 읽고, 노션과의 통신은 동기화 엔진만 한다. 네트워크·인증 상태가 화면 표시를 막지 못하게 하는 구조다.

&#91;embedded content: 앱 구성 요소와 호출 방향 · 상자 11개\]

쓰기는 Room에 먼저 반영돼 화면·위젯이 바로 바뀌고, 노션 전송은 아웃박스를 거쳐 뒤따른다.

### 기술 스택

| 영역 | 선택 | 이유 |
| --- | --- | --- |
| 언어·UI | Kotlin, Jetpack Compose(Material 3) | 안드로이드 공식 UI 도구 |
| 위젯 | Jetpack Glance 1.2 | Compose 문법, 크기별 레이아웃(SizeMode), 체크박스 |
| 로컬 DB | Room + Flow | 화면·위젯 공용 단일 출처, 마이그레이션 테스트 지원 |
| 백그라운드 | WorkManager | 재부팅 후에도 예약 유지. 주기 작업은 최소 15분 간격 |
| 네트워크 | OkHttp + Retrofit, kotlinx.serialization | 인터셉터로 버전 헤더·속도 제한·로그 마스킹 처리 |
| 의존성 주입 | Hilt |  |
| 설정 저장 | DataStore(Preferences) |  |
| 비밀 저장 | DataStore + Tink AEAD + Android Keystore | §6 SEC-01 |
| 크래시 수집 | Firebase Crashlytics 또는 Sentry | §6 PF-04 |
| 날짜·시간 | java.time, ZoneId Asia/Seoul |  |

### 데이터 흐름 규칙

1. 쓰기: Repository가 Room에 즉시 반영하고 아웃박스 행을 추가한다. OutboxWorker가 노션에 보내고, 성공하면 서버 응답으로 덮어쓴 뒤 행을 지운다. 400 검증 오류면 되돌리고 알린다.
2. 읽기: SyncWorker가 증분 조회 → upsert → 범위 대조로 삭제 반영 → 위젯 갱신기 호출 순서로 돈다.
3. 위젯 갱신: 데이터가 바뀐 지점마다 updateAll()을 호출한다(300ms 디바운스). 위젯 렌더링에서는 Room 조회만 한다.
4. 날짜 넘김: 자정 직후 알람과 시간·시간대 변경 수신에서 위젯을 갱신하고, 앱 시작·위젯 갱신 때마다 '오늘'을 다시 계산한다. 한 가지 브로드캐스트에만 의존하지 않는다.
5. 모듈: `:app`, `:core:data`(Room·Repository), `:core:notion`(API·매핑), `:core:sync`(WorkManager), `:feature:month`, `:feature:quicksheet`, `:widget`. `:widget`은 `:core:notion`에 의존하지 않게 해서 위젯의 네트워크 호출을 빌드 단계에서 막는다.

## 8. Notion API 연동 명세

`Notion-Version: 2026-03-11`로 data source 단위 조회·생성·수정을 하고, 내부 연결 토큰 하나로 여러 DB를 다룬다. 2025-09-03 버전부터 DB 하나가 data source 여러 개를 가질 수 있어, 조회·생성은 database\_id가 아니라 data\_source\_id로 한다([업그레이드 가이드 2025-09-03](https://developers.notion.com/docs/upgrade-guide-2025-09-03), [2026-03-11](https://developers.notion.com/guides/get-started/upgrade-guide-2026-03-11)).

### 8.1 연결 준비 (사용자 작업)

1. 노션 개발자 포털(app.notion.com/developers/connections)에서 내부 연결을 만든다. 워크스페이스 소유자 권한이 필요하다. Configuration 탭의 토큰을 복사한다.
2. 연동할 DB 페이지마다 ••• → 연결 추가 → 만든 연결을 고른다. 공유하지 않은 페이지는 API가 오류를 돌려준다.
3. 앱 설정에 토큰을 붙여넣고 DB 링크를 등록한다.

출처: [Notion 인증 문서](https://developers.notion.com/docs/authorization). 공개(OAuth) 연결은 client secret으로 토큰을 교환해야 해서 앱 단독으로는 안전하게 구현할 수 없다. 개인용이므로 내부 연결만 쓴다.

### 8.2 사용 엔드포인트

| 목적 | 호출 | 비고 |
| --- | --- | --- |
| 토큰 확인 | `GET /v1/users/me` | 연결 테스트 |
| DB에서 data source 찾기 | `GET /v1/databases/{database_id}` | 응답의 `data_sources[].id` |
| 스키마 조회 | `GET /v1/data_sources/{data_source_id}` | 속성 이름·ID·유형, select 옵션 색, status 그룹 |
| 범위·증분 조회 | `POST /v1/data_sources/{data_source_id}/query` | `page_size` 100, `next_cursor`로 반복, 한 쿼리 최대 1만 건 |
| 생성 | `POST /v1/pages` | parent는 `{"type":"data_source_id", ...}` |
| 수정·완료·삭제 | `PATCH /v1/pages/{page_id}` | 바뀐 속성만. 삭제는 `"in_trash": true` |
| 단건 재조회 | `GET /v1/pages/{page_id}` | 충돌 확인 |

공통 헤더는 `Authorization: Bearer <토큰>`, `Notion-Version: 2026-03-11`, `Content-Type: application/json`이다.

### 8.3 속성 매핑

| 앱 개념 | 노션 속성 유형 | 읽기 | 쓰기 |
| --- | --- | --- | --- |
| 제목 | title | plain\_text 이어붙이기 | rich\_text 1개, 2,000자 이하 |
| 날짜 | date | start, end | 날짜만 `2026-10-12`, 시간 포함 `2026-10-12T15:00:00+09:00` |
| 완료 | checkbox 또는 status | checkbox 값, 또는 status가 완료(complete) 그룹에 속하는지 | checkbox true/false. status는 완료 그룹 첫 옵션, 해제 시 할 일(to\_do) 그룹 첫 옵션 |
| 색 | DB 색 또는 select 색 | select 옵션 color | 쓰지 않음 |
| 하루 안 정렬 | select | 옵션 순서 | 편집 시트에서만 |
| 노션에서 열기 | page.url | 링크 | - |

매핑은 속성 이름이 아니라 속성 ID로 저장해 노션에서 이름을 바꿔도 끊기지 않게 한다.

### 8.4 등록 프리셋 (사용자 노션에서 확인한 구조)

| DB | 제목 | 날짜 | 완료 | 색 기준 | 정렬 보조 | 비고 |
| --- | --- | --- | --- | --- | --- | --- |
| [📅 Calendar](https://app.notion.com/p/309654132dbd80bbb759cf1f4240d184) | Name | Date (날짜만 있는 항목 위주) | 완료 (checkbox) | Ctgr: Event·Learn·Habit | Time: 새벽·오전·오후·저녁·밤 | toTMR 버튼은 WI-06으로 대체, SubItem은 EX-09, Dday는 수식(읽기 전용) |
| [일정 · 작업](https://app.notion.com/p/3e6654132dbd80e28926f8e4c9da0704) | 이름 | 날짜 | 상태 (status, Done이 완료 그룹) | 단계 또는 유형 | - | 담당자·관계 속성은 쓰지 않음 |

두 DB의 완료 방식이 다르다(checkbox와 status). 그래서 완료 매핑은 두 유형을 모두 지원해야 한다. 앱은 이 값을 코드에 넣지 않고, 등록 화면에서 추천 기본값으로만 보여준다.

### 8.5 동기화 알고리즘

1. 범위 조회: 보이는 범위(이번 달 ±2개월)보다 시작을 31일 앞당겨 날짜 필터로 조회하고, 기간 일정의 겹침은 로컬에서 판정한다. 종료일이 있는 날짜에 필터가 어떻게 적용되는지는 문서에 없다([필터 문서](https://developers.notion.com/reference/filter-data-source-entries)).
2. 증분 조회: 날짜 필터 없이 `last_edited_time`이 '마지막 동기화 시각 − 2분' 이후인 페이지를 조회해 upsert한다. 노션은 이 값을 분 단위로 내림하므로 겹쳐 조회하고 page\_id로 중복을 없앤다([변경 공지](https://developers.notion.com/changelog/last-edited-time-is-now-rounded-to-the-nearest-minute)).
3. 삭제 감지: 증분 조회로는 삭제를 알 수 없다. 앱을 열 때는 현재 달, 하루 1회는 전체 범위를 조회해 ID를 대조하고 없어진 항목을 로컬에서 지운다.
4. 시간대: 필터 값에는 항상 `+09:00`을 붙인다. 시간대가 없으면 UTC로 해석된다.
5. 쓰기 순서: 같은 페이지의 아웃박스 작업은 순서대로 보낸다. 생성 전에 만든 임시 ID는 생성 응답의 page\_id로 바꾼다.
6. 충돌: PATCH 직전에 페이지를 다시 조회한다. 마지막으로 받은 뒤 서버에서도 바뀐 속성은 충돌로 기록하고, 나머지 속성만 보낸다.
7. 재시도: 분당 180회 토큰 버킷. 429·529는 Retry-After(초)를 따르고, 없으면 min(2^n, 30)초 + 최대 250ms 지터, 최대 6회. 400 validation\_error는 재시도하지 않는다. 5xx 재시도는 GET에만 한다([요청 제한 문서](https://developers.notion.com/reference/request-limits)).
8. 생성 중복 방지: 생성 요청이 시간 초과·5xx로 끝나면, 재전송 전에 같은 제목·날짜로 방금 생긴 페이지가 있는지 조회한다.

### 8.6 요청 예시

```json
POST /v1/data_sources/{data_source_id}/query
{
  "page_size": 100,
  "filter": {
    "timestamp": "last_edited_time",
    "last_edited_time": { "on_or_after": "2026-10-12T00:13:00+09:00" }
  }
}

POST /v1/pages
{
  "parent": { "type": "data_source_id", "data_source_id": "<id>" },
  "properties": {
    "Name": { "title": [ { "text": { "content": "영어 스터디" } } ] },
    "Date": { "date": { "start": "2026-10-13" } },
    "완료": { "checkbox": false }
  }
}

PATCH /v1/pages/{page_id}
{ "properties": { "상태": { "status": { "name": "Done" } } } }

PATCH /v1/pages/{page_id}
{ "in_trash": true }
```

## 9. S25·One UI 9.0 최적화 체크리스트

One UI 9.0(Android 17) 대응의 핵심은 삼성 절전 정책, 타깃 37 동작 변경, 위젯 크기와 글꼴 조합 검증이다. 삼성은 백그라운드 제한이 가장 강한 등급(5/5)으로 분류되고, 개발자 쪽 해결책은 없으며 사용자가 설정을 바꿔야 한다([dontkillmyapp](https://dontkillmyapp.com/samsung)).

- [ ] S25-01 (P0) targetSdk 37로 빌드하고 Android 17 동작 변경을 점검한다. 위젯에서 퀵 시트를 여는 PendingIntent는 사용자 탭으로만 시작하고, 백그라운드 Activity 시작에 의존하지 않는다(Android 17에서 IntentSender까지 제한 확대).
- [ ] S25-02 (P0) 위젯 비트맵·아이콘 메모리 한도(화면 폭×높이×4바이트×1.5)를 넘지 않는다. 넘으면 앱이 죽는다(PF-07).
- [ ] S25-03 (P0) 절전 안내: 첫 실행과 진단 화면에서 현재 상태(배터리 최적화 예외 여부)를 보여주고, 설정 화면으로 바로 보내는 버튼을 둔다. 안내 대상: 백그라운드 사용 제한의 절전·깊은 절전 목록에서 빼기, '사용하지 않는 앱 절전' 끄기.
- [ ] S25-04 (P0) 동기화 진단 화면: 마지막 성공·실패 시각, 다음 예정 시각, 절전 상태, 아웃박스 대기 건수, 최근 종료 사유.
- [ ] S25-05 (P0) 120Hz에서 월 스와이프를 JankStats로 측정한다(§3 목표).
- [ ] S25-06 (P0) 조합 검증: One UI 홈 화면 격자 설정 전부 × 위젯 크기(최소\~최대) × 글꼴 크기(최소·기본·최대) × 화면 확대(기본·최대). adb 스크린샷으로 기록한다.
- [ ] S25-07 (P0) 위젯 모서리는 시스템 값(`system_app_widget_background_radius`)을 따른다.
- [ ] S25-08 (P0) targetSdk 37에서는 엣지 투 엣지 화면이 기본이므로 상태 바·내비게이션 바 인셋을 처리한다.
- [ ] S25-09 (P0) 앱 종료 사유 기록: ApplicationExitInfo를 읽어 진단 화면에 보여준다. Android 17 메모리 한도 종료는 설명에 'MemoryLimiter:AnonSwap'이 남는다. 검사는 `adb shell am memory-limiter`로 한다.
- [ ] S25-10 (P1) Material You 동적 색과 다크 모드.
- [ ] S25-11 (P1) 알림(EX-01)을 켤 때 알림 권한과 정확한 알람 권한 상태를 확인하고, 없으면 부정확 알람으로 대체한 뒤 화면에 표시한다.
- [ ] S25-12 (P1) Galaxy Tab S9 확인: Android 17은 너비 600dp 이상 화면에서 방향·크기 고정 설정을 무시하므로 가로·분할 화면에서 레이아웃이 깨지지 않게 한다.

근거: [Android 17 동작 변경(타깃 37)](https://developer.android.com/about/versions/17/behavior-changes-17), [Android 17 동작 변경(전체 앱)](https://developer.android.com/about/versions/17/behavior-changes-all), [dontkillmyapp 삼성](https://dontkillmyapp.com/samsung). 배터리 최적화 예외를 앱이 직접 요청하는 방식은 Play 정책 위반 위험이 있다고 dontkillmyapp은 적고 있다. 개인 설치에서는 설정 화면 이동 버튼으로 충분하다.

## 10. 데이터 모델

로컬 DB는 Room 테이블 8개로 나누고, 노션 페이지 1개는 `events` 1행이다. 폰 캘린더 일정도 Room에 복사해 위젯이 Room만 읽게 한다.

| 테이블 | 주요 필드 | 용도 |
| --- | --- | --- |
| data\_sources | dataSourceId(PK), databaseId, name, colorHex, titlePropId, datePropId, donePropId, doneType(CHECKBOX·STATUS), doneOption, undoneOption, colorPropId, sortPropId, enabled, lastSyncAt, lastFullSyncAt | 등록 DB와 속성 매핑(NS-02). 속성은 ID로 저장 |
| events | pageId(PK, 생성 전에는 임시 ID), dataSourceId, title, startDate, startTime, endDate, endTime, isAllDay, done, colorKey, sortKey, url, lastEditedTime, pendingOps | 노션 페이지. 인덱스 (startDate), (dataSourceId, startDate) |
| outbox | id, pageId, op(CREATE·UPDATE·TRASH), changedProps(JSON), baseLastEdited, createdAt, attempts, nextAttemptAt, lastError | 미전송 쓰기. pageId별 createdAt 순서 보장(NS-04) |
| conflicts | id, pageId, propId, localValue, remoteValue, at, seen | 충돌 기록(NS-05) |
| phone\_events | instanceId, eventId, calendarId, title, begin, end, allDay, colorHex | CalendarContract.Instances에서 범위 복사. 반복 일정은 펼친 상태(CAL-01) |
| widget\_configs | appWidgetId(PK), monthOffset, dataSourceIds, showPhoneCal, theme, opacity, fontScale, maxPerCell, hideDone | 위젯별 설정과 보고 있는 달(WG-04, WI-04) |
| sync\_log | id, at, trigger, result, durationMs, pulled, pushed, error | 진단 화면과 §3 지표 집계 |
| holidays | date, name, source | 공휴일(EX-02) |

- DataStore(일반): 주 시작 요일, 기본 DB, 동기화 주기, 테마, 알림 설정.
- DataStore(암호화, SEC-01): 노션 토큰만.
- 폰 캘린더 복사는 앱 열기·동기화 때와 캘린더 변경 감지 시(WorkManager content URI 트리거) 실행한다.
- 날짜만 있는 일정은 LocalDate로, 시간 일정은 Instant + 시간대로 저장해 섞지 않는다(NS-07).

## 11. 단계별 개발 계획

읽기 전용 앱(Phase 1)을 먼저 완성하고, 위젯(Phase 2)과 쓰기(Phase 3)를 차례로 붙인다. 각 Phase는 게이트를 통과해야 다음으로 넘어간다.

&#91;embedded content: 개발 단계 6개와 통과 기준 5개 · 기간은 정하지 않음\]

읽기를 먼저 고정해 두면 쓰기 단계에서 생기는 충돌·중복 문제를 따로 떼어 검증할 수 있다. P2 항목은 Phase 5 이후 필요할 때 같은 방식으로 진행한다.

### Claude Code 진행 방법

- 각 Phase는 plan 모드로 시작한다(`claude --permission-mode plan` 또는 대화 중 Shift+Tab). 계획을 승인하기 전에는 파일을 고치지 않는다([공식 문서](https://code.claude.com/docs/en/common-workflows)).
- CLAUDE.md는 200줄 이하로 유지하고 PRD를 `@` 문법으로 불러오지 않는다. `@`로 불러온 파일은 매 세션 시작 때 통째로 로드된다([공식 문서](https://code.claude.com/docs/en/memory)). 지시할 때 섹션 번호와 요구사항 ID를 적는다.
- 실기기 화면은 `adb exec-out screencap -p > shot.png`로 찍고, 이미지 경로를 주며 확인을 지시한다. Claude Code는 경로로 받은 이미지를 읽는다.
- Phase가 끝날 때마다 git 커밋한다. 끊긴 세션은 `claude --continue`로 이어간다.

### Phase별 지시문

```text
[Phase 0]
docs/PRD.md를 기준으로 Phase 0만 진행해. 먼저 계획을 보여줘.
- §4 환경과 §7 모듈 구조대로 Android 프로젝트를 만든다. 라이브러리는 지금 시점 최신 안정판을 확인해 libs.versions.toml에 고정한다(알파·베타 금지).
- 디버그 빌드에 StrictMode를 켠다(PF-03). 크래시 수집은 Crashlytics와 Sentry의 설정 절차를 비교만 하고, 내가 고르면 연결한다(PF-04).
- 완료 기준: 게이트 0. adb로 S25에 설치하고 스크린샷으로 실행 화면을 확인한다.
```

```text
[Phase 1]
Phase 1을 진행해. 대상: SEC-01, NS-01~03, NS-07, NS-08, MV-01~05, PF-01~02.
- Notion API는 §8을 그대로 따른다(Notion-Version 2026-03-11, data source 엔드포인트).
- 네트워크 계층은 MockWebServer 테스트를 먼저 쓰고 구현한다. 픽스처는 §8.4 두 DB 구조를 본떠 만든다.
- 토큰은 내가 앱 화면에서 직접 입력한다. 코드·로그·테스트에 실제 토큰을 넣지 않는다.
- 완료 기준: 게이트 1.
```

```text
[Phase 2]
Phase 2를 진행해. 대상: WG-01~05, WS-01~03, WI-04.
- 위젯은 Jetpack Glance로 만들고 :widget 모듈은 :core:notion에 의존하지 않는다.
- Glance의 Row·Column은 자식이 10개를 넘으면 오류가 난다(§13). 6주×7일 그리드와 칸 안의 일정 칩을 이 제한 안에서 설계한다.
- 크기 단계별 레이아웃은 Glance 단위 테스트(runGlanceAppWidgetUnitTest)로 검증하고, 실기기 스크린샷으로 잘림을 확인한다.
- 완료 기준: 게이트 2.
```

```text
[Phase 3]
Phase 3을 진행해. 대상: NS-04~06, WI-01~03, WI-05, MV-04.
- 아웃박스는 Room 테이블과 WorkManager(네트워크 조건, 지수 백오프)로 만든다.
- §8.5의 충돌 처리(6)와 생성 중복 방지(8)를 테스트로 먼저 고정한다.
- 완료 기준: 게이트 3.
```

```text
[Phase 4]
Phase 4를 진행해. 대상: §6 PF·SEC 전체, §9 체크리스트 P0.
- Macrobenchmark로 콜드 스타트, JankStats로 월 스와이프를 측정해 docs/metrics.md에 기록한다.
- 절전 안내 화면과 동기화 진단 화면을 만든다.
- 완료 기준: 게이트 4.
```

```text
[Phase 5]
Phase 5를 진행해. §5·§6의 P1 항목을 하나씩 구현하고, 항목마다 완료 기준을 테스트로 확인한 뒤 커밋한다.
```

## 12. 테스트·검증 기준

게이트 통과는 사람의 눈이 아니라 아래 자동 테스트와 실기기 기록으로 판단한다. Claude Code는 테스트를 먼저 쓰고 구현한다.

| 종류 | 도구 | 대상과 통과 기준 |
| --- | --- | --- |
| 단위 | JUnit, kotlinx-coroutines-test, Turbine | 날짜·시간대 변환, 증분 기준 시각, 충돌 병합, 자연어 입력 해석 |
| API 계약 | OkHttp MockWebServer | §8 요청 모양·헤더, 429·529·5xx 재시도 |
| DB | Room MigrationTestHelper | 모든 이전 스키마에서 현재 스키마로 이전 |
| 위젯 | Glance 단위 테스트(runGlanceAppWidgetUnitTest), 실기기 스크린샷 | 크기 단계별 구성, 컨테이너 자식 10개 이하 |
| 화면 스크린샷 | Roborazzi | 월 화면 5·6주, 다크, 글꼴 최대 |
| E2E | Maestro(실기기) | 퀵 추가, 체크, 오프라인 후 온라인 반영 |
| 성능 | Macrobenchmark, JankStats, Baseline Profile | §3 지표 |
| 실사용 | 2주 일상 사용 + sync\_log | 크래시 0, 반영 지연 집계 |

필수 시나리오

- [ ] 날짜: 종일 일정 2026-10-31이 10/31 칸에 보이고, 기기 시간대를 UTC로 바꿔도 10/31에 남는다.
- [ ] 기간: 10/30\~11/2 일정이 10월·11월 화면에 모두 보인다.
- [ ] 자정: 23:59에서 00:01로 넘어가면 위젯의 오늘 칸이 1분 안에 옮겨진다.
- [ ] 충돌 1: 폰에서 제목, 노션에서 날짜를 바꾸면 둘 다 반영된다.
- [ ] 충돌 2: 같은 제목을 양쪽에서 바꾸면 노션 값이 남고 충돌 기록 1건이 생긴다.
- [ ] 충돌 3: 폰에서 고친 항목을 노션에서 지우면 로컬에서도 사라지고, 아웃박스 작업은 폐기되며 기록이 남는다.
- [ ] 인증: 토큰을 무효화해도 화면·위젯 내용이 유지되고 배너만 뜬다. 재시작 루프는 0회다.
- [ ] 속도 제한: 429(Retry-After: 2) 응답 후 2초 뒤 재시도해 성공한다.
- [ ] 생성 중복: 생성 요청이 시간 초과로 끝나도 노션에 페이지가 1개만 생긴다.
- [ ] 대조: 디버그 메뉴의 '노션과 비교'가 현재 범위 조회 결과와 로컬 DB를 비교해 불일치 0을 보여준다.
- [ ] 업데이트: 이전 버전 DB 파일로 시작해도 실행되고, 토큰·설정·미전송 아웃박스가 유지된다.
- [ ] 삼성 절전: 앱을 절전 목록에 넣은 상태와 뺀 상태에서 24시간 동안 주기 동기화 횟수를 sync\_log로 비교한다.

## 13. 리스크와 제약

가장 큰 리스크는 기술보다 플랫폼 정책이다. 서버가 없는 이상, 삼성 절전과 WorkManager 최소 주기 때문에 노션에서 고친 내용이 앱을 열지 않은 상태로 수 분 안에 위젯에 뜨는 것은 보장할 수 없다.

| 리스크·제약 | 근거 | 대응 |
| --- | --- | --- |
| 삼성 절전으로 백그라운드 동기화 지연·중단 | [dontkillmyapp](https://dontkillmyapp.com/samsung): 삼성 5/5, 개발자 쪽 해결책 없음 | 절전 예외 안내(S25-03), 앱·위젯 조작 시 즉시 동기화, 진단 화면 |
| 노션 → 폰 반영이 실시간이 아님 | 주기 작업 최소 15분, 서버 없음 결정 | 필요하면 §14 웹훅 중계안 추가 |
| 노션 API 속도 제한 | 분당 180회(Business 이상 600회) | 증분 조회로 호출 최소화. 첫 동기화는 100건당 요청 1회라 DB 크기에 비례해 길어짐 |
| 노션 API 버전 변경 | 2025-09-03, 2026-03-11 연속으로 호환성이 깨지는 변경 | 버전 헤더 고정, API 응답을 앱 모델로 바꾸는 매핑 계층 분리 |
| 날짜 범위 필터 동작 미문서화 | [필터 문서](https://developers.notion.com/reference/filter-data-source-entries) | 조회 범위 확장 + 로컬 판정(§8.5) |
| last\_edited\_time 분 단위 내림 | [변경 공지](https://developers.notion.com/changelog/last-edited-time-is-now-rounded-to-the-nearest-minute) | 2분 겹침 조회 |
| Glance Row·Column 자식 10개 제한(11개면 IllegalArgumentException) | [Kotlin Slack 답변](https://slack-chats.kotlinlang.org/t/2916976/is-there-a-limit-of-10-children-inside-a-column-or-row-in-gl), 공식 문서 확인 안 됨 | 중첩 레이아웃 설계, Phase 2에서 실제 동작 확인 |
| 위젯 안 텍스트 입력 불가 | RemoteViews 지원 뷰 제한 | 퀵 시트(WI-01) |
| 위젯 비트맵 메모리 초과 시 앱 종료 | Android 17 타깃 37 변경 | 비트맵 없는 위젯(PF-07) |
| 노션 캘린더도 S25에서 안 열린다는 리뷰 존재 | Play 리뷰 2026-08 | 매 게이트에 S25 실기기 설치·실행 포함 |
| Kotlin·Android 경험이 없으면(현재 주력은 Python) 생성 코드 결함을 직접 찾기 어렵다 | 사용자 이력 | 테스트 우선, Phase 게이트, 모르는 코드는 Claude Code에 설명을 요청 |
| 서명 키를 잃으면 업데이트 설치 불가 | Android는 다른 키로 서명한 APK를 업데이트로 설치하지 않는다 | 릴리스 키스토어 백업, 설정 백업(EX-10) |

## 14. 대안과 보완 도구

위젯에서 노션 속성을 직접 바꾸는 요구(체크·추가)는 직접 개발(A)만 충족한다. B는 빠르지만 보기 전용이고, C는 A의 반영 지연을 줄이는 확장이다.

| 대안 | 방법 | 장점 | 한계 |
| --- | --- | --- | --- |
| A. 직접 개발(이 PRD) | Kotlin 앱 + Glance 위젯 + 노션 API | 위젯에서 체크·추가·월 이동, 노션 속성 그대로 사용 | 개발·유지보수 시간, 절전에 따른 백그라운드 지연 |
| B. 노션 → 구글 캘린더 동기화 + 기존 캘린더 위젯 | 이전에 검토한 Apps Script 단방향 동기화, 또는 n8n·Make 시나리오 | 성숙한 월간 위젯을 바로 사용 | 위젯에서 노션 체크·추가 불가, 동기화 지연, 중복 관리 |
| C. A + 노션 웹훅 중계 | 노션 연결 웹훅(page·data\_source 이벤트) → 서버리스 함수 → FCM 데이터 메시지 → 앱 증분 동기화 | 앱을 열지 않아도 빠르게 반영 | 공개 HTTPS 주소·서명 검증·FCM 설정·운영 필요. 절전 예외 설정은 여전히 필요 |

웹훅 이벤트 종류와 구독 설정은 [업그레이드 가이드 2025-09-03](https://developers.notion.com/docs/upgrade-guide-2025-09-03)와 [2026-03-11](https://developers.notion.com/guides/get-started/upgrade-guide-2026-03-11)에 있다.

### 개발 보조 도구

| 도구 | 용도 |
| --- | --- |
| Android Studio App Inspection(Database·Background Task·Network Inspector) | 실행 중 Room 데이터, WorkManager 작업, HTTP 요청 확인 |
| adb(screencap, logcat) | 실기기 화면·로그를 Claude Code에 전달 |
| Perfetto | 느린 시작·버벅임 원인 추적 |
| Maestro | 실기기 E2E 시나리오 자동화 |
| curl 또는 Bruno | 코드 작성 전 노션 API 요청·응답 모양 확인 |
| Claude Code + Notion MCP | 실제 DB 구조를 읽어 테스트 픽스처 작성 |
| 리뷰 전수 수집 스크립트(google-play-scraper) | 이 문서와 함께 제공. 맥북에서 실행해 전체 리뷰를 내려받고 유형별로 집계한 뒤 §2를 갱신 |

## 15. 결정 필요 사항

아래는 기본값을 정해 두었지만 Phase 시작 전에 확정하면 재작업이 줄어드는 항목이다.

- [ ] 폰 캘린더 표시(CAL)를 P0로 올릴지. 현재는 P1(Phase 5)이다.
- [ ] 기본 동기화 주기: 15분(빠른 반영) 또는 30분(배터리). 기본값은 15분이다.
- [ ] 크래시 수집 도구: Crashlytics(Firebase 프로젝트 필요) 또는 Sentry(별도 계정 필요). Phase 0에서 비교 후 고른다.
- [ ] 완료 항목 기본 표시: 취소선 또는 숨김. 기본값은 취소선이다.
- [ ] 주 시작 요일 기본값: 일요일 또는 월요일.
- [ ] 📅 Calendar의 Time(새벽\~밤)을 하루 안 정렬 기준으로 쓸지.
- [ ] 두 프리셋 외에 스터디·지출 기록 등 다른 DB도 처음부터 등록할지.
- [ ] 앱 이름과 아이콘.
- [ ] 노션 웹훅 중계(§14 C)를 나중에 추가할지. 추가하면 서버 운영이 생긴다.

## 16. 출처

모두 2026-10-12에 열어 확인한 페이지다.

리뷰·평가

- [Google Play: Notion Calendar(com.cron.calendar)](https://play.google.com/store/apps/details?id=com.cron.calendar) — 영어(미국·영국·호주)·한국어·일본어·스페인어·독일어·포르투갈어·프랑스어·네덜란드어·스웨덴어·핀란드어 페이지
- [AppBrain: Notion Calendar](https://www.appbrain.com/app/notion-calendar/com.cron.calendar)
- [App Store: Notion Calendar](https://apps.apple.com/us/app/notion-calendar/id1607562761)
- [worldsapps: Notion Calendar 리뷰](https://worldsapps.com/reviews-notion-calendar)
- [marlvel.ai: Notion Calendar 리뷰 분석](https://marlvel.ai/apps/notion-calendar)
- [toolguide: Notion Calendar](https://toolguide.io/en/tool/notion-calendar/)
- [unstar: 캘린더 앱 1점 리뷰 분석](https://unstar.app/hi/blog/fantastical-google-apple-outlook-notion-calendar-apps-ranked-2026)
- [Notion 릴리스: Notion Calendar is now on Android](https://www.notion.com/releases/2024-07-17)

Notion API

- [Versioning](https://developers.notion.com/reference/versioning)
- [Upgrade guide 2025-09-03](https://developers.notion.com/docs/upgrade-guide-2025-09-03)
- [Upgrade guide 2026-03-11](https://developers.notion.com/guides/get-started/upgrade-guide-2026-03-11)
- [Request limits](https://developers.notion.com/reference/request-limits)
- [Query a data source](https://developers.notion.com/reference/query-a-data-source)
- [Filter data source entries](https://developers.notion.com/reference/filter-data-source-entries)
- [Authorization](https://developers.notion.com/docs/authorization)
- [last\_edited\_time 분 단위 내림 공지](https://developers.notion.com/changelog/last-edited-time-is-now-rounded-to-the-nearest-minute)

Android·삼성

- [Android 17 동작 변경: 타깃 37](https://developer.android.com/about/versions/17/behavior-changes-17)
- [Android 17 동작 변경: 전체 앱](https://developer.android.com/about/versions/17/behavior-changes-all)
- [Jetpack Glance 릴리스 노트](https://developer.android.com/jetpack/androidx/releases/glance)
- [Android 앱 위젯 문서](https://developer.android.com/develop/ui/views/appwidgets)
- [Kotlin Slack: Glance 자식 10개 제한](https://slack-chats.kotlinlang.org/t/2916976/is-there-a-limit-of-10-children-inside-a-column-or-row-in-gl)
- [dontkillmyapp: Samsung](https://dontkillmyapp.com/samsung)
- [Sammyfans: Galaxy S25 One UI 9 안정판](https://www.sammyfans.com/2026/09/28/samsung-galaxy-s25-one-ui-9-stable-update/amp/)
- [GSMArena: Galaxy S25 사양](https://www.gsmarena.com/samsung_galaxy_s25-13610.php)
- [Include Security: EncryptedSharedPreferences 대체 방안](https://blog.includesecurity.com/2026/08/encryptedsharedpreferences-is-dead-heres-what-you-should-use-instead/)
- [public-apis-4Kr: 한국천문연구원 특일 정보 API 링크](https://github.com/yybmion/public-apis-4Kr)

Claude Code

- [CLAUDE.md와 메모리](https://code.claude.com/docs/en/memory)
- [Common workflows](https://code.claude.com/docs/en/common-workflows)

사용자 노션: [📅 Calendar](https://app.notion.com/p/309654132dbd80bbb759cf1f4240d184), [일정 · 작업](https://app.notion.com/p/3e6654132dbd80e28926f8e4c9da0704) 속성 구조(읽기 전용 조회).
