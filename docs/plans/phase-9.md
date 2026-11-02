# Phase 9 — 노션 보기 위젯 (VW-01~07)

승인된 계획(2026-10-21). 기준 요구사항은 `docs/PRD.md` §5.12.

## 결정 기록 (ADR)
**보기 위젯은 `events`가 아니라 표시용 스냅샷 테이블 `view_snapshots`에 저장한다.**
- 배경: 사용자가 "어떤 DB든 노션의 보기대로 위젯에 띄우는" 기능을 요구했다. 전면 범용 앱 전환·별도 앱도 검토했다.
- 선택: 기존 앱은 유지하고 범용 층을 옆에 추가한다(사용자 결정 2026-10-21). 전환은 종류 6개(CALENDAR·HABIT·HABIT_LOG·LEDGER·BUDGET·CATEGORY)마다 매퍼·DAO·위젯이 따로 있어 약 4,000줄 교체이고, 그동안 쓰는 기능이 불안정해진다.
- 이유: `events`는 `startDate`가 필수인 날짜 모델이라(`Entities.kt:83`) 날짜 없는 DB를 못 담는다. 읽기 전용이므로 값을 표시용 글자로 바꿔 저장하면 속성 타입마다 모델이 필요 없다.
- 결과: 위젯에서 앱이 정렬·필터를 다시 할 수 없다(보기 설정을 노션이 적용). 쓰기는 이번 범위 밖(VW-08).
- 되돌리는 법: 테이블·위젯·설정 항목만 지우면 되고 기존 kind 코드는 건드리지 않는다.

## 범위 밖
갤러리 커버 이미지, 보드 열 배치·타임라인 막대, 노션으로 쓰기, 대시보드·폼·차트·지도 보기, 배포·약관, 기존 kind·화면 변경, 새 라이브러리.

## 단계 (각 단계 끝: `gradlew.bat test lint assembleDebug`, 실기기 설치, adb 스크린샷, StrictMode 0줄, 문서 승인)
- **9-A 확인·문서:** PRD §5.12·이 문서 승인 → API 모양 확인(spike): 디버그 전용 진단 메뉴(`BuildConfig.DEBUG`)에서 실제 DB로 `GET /views?database_id=`·`GET /views/{id}`·`POST /views/{id}/queries`를 불러, 응답을 제목·값을 가린 JSON으로 저장해 시험 픽스처로 쓴다. 토큰은 코드·로그·픽스처에 넣지 않는다(`.env`/앱 설정에 사용자가 직접).
- **9-B 읽기 층:** `NotionApi` 엔드포인트 3개, View·ViewQuery DTO, `ViewReader`, `PropertyDisplay`(순수 함수), Room `view_snapshots`(DB v12), `ViewSync`(`PeriodicSync` 재사용). 시험: `PropertyDisplay` 타입별 표, `ViewReader` 페이지 반복·만료 재시도, `ViewSync` 교체·실패 시 유지, `FakeNotionStore`에 views 응답 추가.
- **9-C 화면·위젯:** 설정 '보기 추가', `ViewWidget`(+ receiver·위젯 정보 xml·매니페스트·`WidgetKinds` 한 줄), `WidgetConfigEntity.viewId`(DB v12 + 마이그레이션 + `schemas/12.json` + 마이그레이션 시험, PF-09).
- **9-D QA:** `rules/qa.md` — Claude QA(VW-xx별 기대값·실제값·Pass/Fail) + 사람 QA 목록(내 DB 3종 이상에서 보기와 위젯 비교, 홈 화면 체감, 오래됨 표시).

## 모듈 규칙
`:widget`은 `:core:data`·`:core:designsystem`만 의존(`:core:notion` 금지). 화면·위젯은 Room만 읽는다. 네트워크는 `:core:sync`의 `ViewSync`에서만. 파일 500줄·함수 30줄 이하.

## 위험
- 보기 설정은 `GET /views/{id}`(9-A에서 실기기로 확인). 값은 데이터 소스 쿼리로 받으므로 보기 쿼리의 15분 캐시·추가 필터 불가 제약은 해당 없다.
- 빠른 필터(`quick_filters`)가 보기 필터와 별개라 합쳐 넣어야 한다. 합친 결과가 보기 쿼리와 어긋나는 종류(board 1건)는 '목록으로 표시' 한계로 둔다.
- 필터에 쓰는 속성이 지워졌거나 타입이 바뀌면 데이터 소스 쿼리가 400(Validation)일 수 있다 → 그 보기는 '보기를 읽지 못함'으로 표시하고 이전 스냅샷을 유지한다.
- 큰 DB 보기는 노션 쪽 쿼리가 느릴 수 있다(9-A: 보기 쿼리 포함 9회 3.9초).

## 9-A 결과 (2026-10-21, 실기기 R3KYC03RGCD, 등록 DB 3개·보기 10개)
- **호출:** `GET /v1/views?data_source_id=`는 `{object, id}`만 주고(보기 이름·종류는 `GET /v1/views/{id}`로), 보기 설정은 `type`·`filter`·`sorts`·`quick_filters`·`configuration.properties[]`(`property_id`·`property_name`·`visible`·`width`)를 준다. 9회 호출(DB 3개 × 목록·보기·쿼리) 3.9초.
- **중요 — 보기 쿼리는 값을 주지 않는다.** `POST /views/{id}/queries`의 `results`는 `{object:"page", id}`뿐이다(문서도 동일). 값을 받으려면 행마다 `GET /pages/{id}`(50행이면 50요청)이거나 데이터 소스 쿼리가 필요하다.
- **결정 변경:** 값은 데이터 소스 쿼리에 보기의 `filter`와 `quick_filters`(`{속성 id: {타입: 조건}}`, 속성 필터 조건과 같은 모양이라 `{property, 타입: 조건}`으로 바꿔)를 `and`로 묶고 `sorts`를 그대로 넣어 한 번에 받는다. 보기 쿼리와 id·순서를 비교: **10개 중 9개 완전 일치**(table 3·list 1·calendar 3·gallery 1·timeline 1). `quick_filters`를 합치기 전에는 4개가 달랐다(빠른 필터가 `filter`에 없음).
- **불일치 1개:** board(빠른 필터 `relation is_empty`)는 보기 쿼리가 0행, 데이터 소스 쿼리가 100행. 같은 빠른 필터의 calendar는 일치했으므로 board 보기 쿼리 쪽 동작으로 보이지만 원인은 확인 못 했다. board는 어차피 '목록으로 표시'(VW-05)라 한계로만 적는다.
- **GET /views/{id}가 `Validation`(400)인 보기 2개:** 종류를 알 수 없다(목록 응답에 종류가 없음). 조회 실패는 '지원하지 않는 보기'로 건너뛴다(VW-05·06).
- **열 이름·순서:** `configuration.properties[]`가 `property_name`·`visible`을 직접 주므로 `dataSource(id)` 스키마 호출은 필요 없다(계획 변경).
- **표시 타입:** 시험 DB 행에서 본 속성 타입은 date·files·formula·last_edited_time·multi_select·number·relation·status·title·url(PropertyDisplay 시험 대상).
- **검증 방법:** 구조 파일 `core/notion/src/test/resources/fixtures/views_probe_shape.json`(값은 `"S"`로 가림, 토큰·내용 없음). 디버그 메뉴 '보기 API 시험(VW)'은 9-B에서 `ViewReader`로 대체하면서 지운다.

## 9-C 결과 (2026-11-02)
- **만든 것:** 설정 '노션 보기 추가'(`AddViewScreen`·`AddViewViewModel`), `ViewRegistration`(보기 목록·등록), `ViewRepository`(Room에서 위젯이 읽는 모양으로), `ViewWidget`(+ receiver·`view_widget_info.xml`·매니페스트·`WidgetKinds`), 위젯 설정의 보기 고르기, Room v13(`widget_configs.viewId`, `schemas/13.json`, `MigrationTest`). 계획의 'DB v12 + `schemas/12.json`'은 9-B가 v12를 먼저 써서 **v13**이 됐다.
- **검증:** `gradlew test lint assembleDebug`(캐시 없이 전체 재실행) 시험 542개, 실패 0, 건너뜀 0, lint 이슈 0건. 병합 직후 502개 대비 +40개가 9-C 시험 전체다. 첫 전체 빌드에서 `EventSearchTest.limitAndSpeedWithThousandRows`(검색 100ms 판정) 한 번 실패했다(호출 하나가 170ms, 나머지 8·13·1ms). 단독 3회와 이후 전체 재실행은 통과해서 병렬 부하에 의한 타이밍 편차로 본다. 이 시험은 9-C와 무관하다.
- **코드 리뷰 반영(10건 중 9건):**
  - 릴리스(R8, `isMinifyEnabled = true`, 유지 규칙 없음)가 `::class.simpleName`을 바꾸므로 `lastError`를 `NotionError`별 고정 문자열로 남긴다(`when`이 모든 경우를 다뤄야 컴파일된다). 404 안내가 릴리스에서만 안 뜨던 문제.
  - `SyncWorker`는 `ViewSync`를 막지 않는데 `NotionException`만 잡았다. 보기마다 예상 밖 예외를 잡고 그 보기만 실패로 남긴다.
  - 429·5xx도 보기 전체에 같은 문제라 남은 보기를 더 부르지 않는다.
  - 받는 사이 사용자가 등록을 지우면 `replace`가 보기를 되살렸다. `replace`는 등록된 보기만 바꾼다.
  - 보기 목록은 400만 '지원하지 않음'이고 나머지 오류는 목록 실패다. 등록은 그 보기만 받는다.
  - 이름 없는 보기는 기본 라벨, 머리의 접근성 설명은 '이름, 새로고침'. 등록 화면 목록은 생명주기를 따르고 처음 값 전에는 '없음'을 깜빡이지 않는다.
- **직접 찾은 것:** DB 손상 복구(PF-09)의 KEEP 목록에 `view_snapshots`가 없어, 복구 때 위젯 설정은 남는데 보기 등록이 사라졌다. KEEP에 넣고, 되살린 뒤 받은 시각·오류는 지워 위젯이 '표시할 행 없음'이 아니라 '아직 받지 못함'을 알리게 했다.
- **VW-06 확정:** 상태는 `SyncBadge`가 아니라 머리 아래 한 줄 글자로 낸다(`SyncBadge`는 달력 위젯용). 404(`NotShared`)·고르지 않음·등록 해제는 눌러서 위젯 설정을 연다. 문구의 ' · ' 연결은 DESIGN.md 규칙에 맞춰 쉼표로 바꿨다.
- **알려진 한계(고치지 않음):** ① '오래됨'(12시간) 판정은 위젯이 다시 그려질 때만 갱신된다. 동기화가 멈추면 바뀌지 않으므로 자정 알람 같은 갱신 경로가 필요하면 따로 정한다. ② 글자 배율 150% + 시스템 글꼴 130%의 2×2는 첫 줄이 일부만 보인다(100%에서는 머리와 한 줄이 들어간다, `ViewWidgetTest`). ③ 보기 목록 조회는 보기마다 차례로 부른다.
- **실기기 확인(Galaxy S25 SM-S931N, Android 17, 2026-11-02):** 폰을 연결해 최종 빌드를 `adb install -r`로 덮어 설치했다.
  - 마이그레이션: 10/22 빌드(v12) 위에 설치해 v13으로 올랐다. 앱이 정상 실행됐고, 등록해 둔 보기(`Reading`)와 기존 위젯(달력 + 일정, 통합 탭)이 그대로 표시됐다.
  - 보기 추가 화면: 📅 Calendar 링크(PRD §8.4에 공개된 링크)로 보기 5개를 불러왔다(Monthly·Weekly는 calendar, TDY(list)는 board라 '목록으로 표시', AllTodos는 table, NoDateToDos는 list). `AllTodos`를 등록하면 그 보기만 받는다(`ViewSync.sync`).
  - 위젯: 선택기에 `노션 보기`(2×2)가 나오고 `추가` → 위젯 설정(보기 고르기, 미리보기) → `저장`으로 놓인다. 위젯은 실제 노션 표를 그린다(4×4에서 6열, 약 2×2에서 제목 포함 2열). 행 탭은 노션 앱을 연다. 길게 눌러 `설정`으로 `Reading`으로 바꾸면 3초 안에 반영되고 이전 보기 내용이 남지 않는다.
  - 오프라인: 비행기 모드에서 위젯은 마지막 표를 그대로 보여 준다. 머리를 눌러도 '동기화 실패'가 뜨지 않는데, 새로고침이 `NetworkType.CONNECTED` 조건이라 연결될 때까지 보류되기 때문이다(설계대로).
  - 로그: 충돌·StrictMode(`NotionCalStrictMode:W`)·보기 경고·ANR 0건. 비행기 모드는 원래 값(0)으로 되돌렸다.
- **기기에서 찾아 고친 결함 3건:**
  - 표 보기는 노션 API의 열 순서에서 제목(`Name`)이 네 번째였다. 위젯은 폭이 좁으면 뒤 열부터 줄여서 제목이 사라지고 목록 모드에서는 비어 있는 첫 열이 제목 줄이 됐다. 제목을 항상 첫 열로 둔다.
  - `Reading`(보드·갤러리·목록 계열로 보임) 위젯은 행이 통째로 비었다. 이런 보기는 카드 제목이 항상 보여서 설정의 열 목록에 제목이 없을 수 있어, 값이 빈 속성만 남았다. 설정에 제목이 없으면 첫 열로 넣고, 설정이 제목을 `visible=false`로 숨겼으면 그 선택을 따른다(`visibleColumns`).
  - 위젯 설정 화면의 미리보기에 머리와 열 머리만 나오고 행이 비었다. 설정 화면은 `GlanceRemoteViews` 결과를 View에 직접 적용하는데 그 방식에서는 목록(`LazyColumn`) 행이 그려지지 않는다(같은 방식으로 `Agenda` 위젯을 그려도 행이 비는 것을 시험 환경에서 확인했다). 미리보기만 고정 `Column`으로 앞 8행을 그린다. **다른 목록 위젯(Agenda·Split·디데이 목록)의 설정 미리보기도 같은 증상일 수 있다.** 기기에서는 확인하지 못했고, 이번에 고치지 않았다.
- **실기기 미검증:** 보기를 지웠을 때(404)의 안내와 눌러서 설정 열기, '동기화 실패'·'오래됨' 줄, 위젯 둘을 동시에 놓고 서로 다른 보기 보이기(VW-04 완료 기준의 후반), 글자 배율 150%·시스템 큰 글꼴, 릴리스(R8) 빌드. 스크린샷은 개인 일정이 보여 저장소에 올리지 않았다(기존 `vw9b_home.png`·`vw9c_*`는 첫 화면, 보기 추가 빈 화면, 설정 버튼만 보여 준다).
- **관찰:** 이 폰의 런처는 위젯 크기를 실제보다 크게 보고한다(`hsResizeRatio` 0.71, 4×4에 501dp). 그래서 좁은 위젯도 표 모드(`TABLE_MIN_WIDTH` 220dp 이상)로 그려지고 글자가 작게 보인다.
- **남은 것:** 9-D QA(`rules/qa.md`: Claude QA 항목별 기대값·실제값, 사람 QA 목록).
