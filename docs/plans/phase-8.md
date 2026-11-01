# Phase 8 구현 계획: 디데이(DD-01~05) + 날씨(WX-01~05) + 바로 여는 링크(EX-12) + 나우 브리프 시험(EX-08)

## Context
PRD §11 Phase 8 지시문에 따른 작업이다. 목표는 셋이다.
- 고정한 일정의 남은 날을 홈 화면에 늘 띄운다(디데이 위젯 1×1·2×1·목록).
- 날짜 옆에 날씨를 붙인다(아웃룩처럼).
- 나우 브리프·루틴에서 앱 화면을 바로 열 수 있게 한다.

2026-10-19에 확인한 사실과 사용자 결정은 다음과 같다.
- **날씨 모델**: Open-Meteo 공지에 따르면 기상청(KMA) 모델은 데이터 갱신이 멈춰 있다. 기상청이 2026-03 말 UM 모델을 중단했고, Open-Meteo가 KIM 모델로 옮기는 중이다. 실제 조회에서도 kma_seamless·ldps·gdps 값은 전부 null이었고, 기본 모델(best_match)은 10일·48시간이 모두 왔다.
  → 사용자 결정: **기본 모델만** 쓴다. 출처 표기는 'Weather data by Open-Meteo.com'이다. 제공처 코드는 `:core:weather` 안에 가둬 두어, 나중에 기상청이 돌아오면 바꾸기 쉽게 한다.
- **디데이 관리 화면**: **설정 안 '디데이'** 항목에 둔다. 하단 탭은 지금처럼 3개다.
- **EX-08 시험 스위치**: **디버그 메뉴**에만 둔다.

PRD 지시대로 테스트를 먼저 쓴다. 묶음마다 실기기에서 확인한다.
- **A 묶음**: 디데이 + 링크
- **B 묶음**: 날씨
- **C 묶음**: EX-08 시험

## 데이터 (DB v11, AutoMigration 10→11)
- 새 표
  - `dday_pins(pageId PK, sortOrder, countMode DOWN|UP|DAYS, hideAfterDays 기본 7)`: 앱 상태라서 `RecoveringOpenHelperFactory` KEEP 목록에 넣는다.
  - `weather_days(date PK, code, tMax, tMin, pop, fetchedAt)`, `weather_hours(time PK, code, temp, pop)`: 캐시다.
- `widget_configs`에 열을 더한다: `ddayPageId`(DD-03·04), `showWeather`(WX-04).
- DataStore(`AppSettings`)에 더한다.
  - 날씨: `weatherEnabled`, `weatherLocationMode`(REGION|COARSE), `weatherRegion`(이름·위도·경도, 소수 둘째 자리), `weatherMonthCellIcons`, `weatherFetchedAt`
  - 디데이: `ddayRemovedNotice`(노션에서 지워져 풀린 디데이 제목들)
- MigrationTest v10→v11, DatabaseRecoveryTest(dday_pins 유지).

## A 묶음: 디데이(DD-01~05) + 링크(EX-12)
1. **계산 `core/data/.../dday/DdayMath.kt`** (순수 함수, 테스트 먼저)
   - `label(start, end, today, mode)`의 결과:
     - 'D-12' / 'D-Day' / 'D+3'
     - 기간 일정 진행 중이면 'D-Day · 2/3일째'
     - UP 모드는 'D+100', DAYS 모드는 '100일째'
   - DOWN 모드에서 지난 날이 hideAfterDays를 넘으면 숨긴다.
   - 테스트: 자정 경계, 윤년(2028-02-29), 월말, 기간 일정, 1000일 이상(네 자리).
2. **`DdayRepository`(:core:data)**
   - 고정 목록을 events와 조인해 Flow로 내보낸다(가까운 순).
   - pin, unpin, setMode를 둔다.
   - 데이터가 바뀌면 `WidgetRefresher`를 부른다.
3. **동기화(:core:sync, `NotionSyncEngine`)**
   - 증분 조회가 날짜 변경은 이미 따라간다.
   - 휴지통 감지: 동기화 범위(±2개월) 밖 고정 페이지는 APP_OPEN·전체 대조 때 `NotionClient.pageOrNull`로 확인한다. 범위 안은 reconcile이 한다.
   - 지워졌으면 행을 지우고 고정을 풀고, `ddayRemovedNotice`에 남긴다.
   - 테스트: `FakeNotionStore`로 날짜 변경 반영, 범위 밖 휴지통 → 고정 해제.
4. **고정 버튼(DD-01)**: `feature/quicksheet/.../Editors.kt`의 `EventEditorForm`에 '디데이로 고정·해제'를 둔다. 편집 시트는 위젯 퀵 시트와 날짜 시트(DaySheet)가 같이 쓰므로 둘 다에 생긴다.
5. **디데이 화면**: 새 모듈 `:feature:dday`(:core:data·:core:designsystem만)
   - 고정 목록에서 표기 미리보기, 세는 방식(D-N / D+N / N일째), 해제를 한다.
   - '일정 찾아 고정': Room 제목 검색(`EventDao`)으로 찾아 고정한다.
   - 풀린 디데이 안내 배너를 보인다.
   - 설정 화면에 '디데이' 항목을 두고 이 화면을 연다.
6. **위젯(:widget)**
   - `DdayWidget`: 1×1·2×1 공용 코드다. 리시버 둘(기본 1×1, 기본 2×1)로 위젯 선택 목록에 따로 보인다. 실제 폭으로 모양을 정한다.
     - 1×1은 'D-12'를 크게, 제목 한 줄. 네 자리면 한 단계 작게.
     - 2×1은 'D-12' + 제목 + '10/31 금'.
     - 탭하면 편집 시트를 연다(`quickSheetIntent(MODE_EDIT)`).
   - `DdayListWidget`(2×2 이상): Glance `LazyColumn`, 가까운 순.
     - 줄을 누르면 편집 시트, '+'는 디데이 화면(`notioncal://dday`), 비면 안내 문구.
   - `WidgetConfigActivity`에 Kind.DDAY를 더한다(고정한 디데이 중 하나 고르기).
   - `WidgetKinds`·`AllWidgets`(자정 갱신 포함)·`anyPlaced`·미리보기(WG-08)·리시버 기반 클래스에 등록한다.
   - 테스트: Glance 단위 테스트, RemoteViews 변환, 글꼴 최대에서 세 자리·네 자리 잘림 0, 20개 목록.
7. **링크(EX-12)**: `MainActivity`에 exported VIEW intent-filter를 단다(scheme `notioncal`).
   - 받는 host: `today`, `habit`, `ledger`, `date/2026-10-20`, `dday`.
   - 순수 함수 `DeepLinks.parse(uri)`로 화면만 정한다. 쓰기는 없고, 모르는 링크는 무시한다.
   - 기존 내부 data URI(위젯 `notioncal://tab/...`, 퀵 시트 action)와 겹치지 않게 테스트한다.
8. **A 묶음 실기기 확인**
   - 노션 일정 하나 고정 → 1×1·2×1·목록 위젯 스크린샷
   - 노션에서 날짜 변경 → 숫자 갱신
   - `adb shell am start -d notioncal://...` 다섯 개

## B 묶음: 날씨(WX-01~05)
1. **`:core:weather`**: 새 모듈. OkHttp + kotlinx.serialization, `:core:data` 의존 없음.
   - `WeatherClient.fetch(lat, lon)`: Open-Meteo `/v1/forecast`를 부른다.
     - daily: weather_code·max·min·precipitation_probability_max, 10일
     - hourly: weather_code·temperature_2m·precipitation_probability, 48시간
     - `timezone=Asia/Seoul`
   - 좌표는 소수 둘째 자리로 줄인다(WX-02). 로그에서 좌표를 가린다(Redactor 패턴).
   - `WeatherCode.sky(code)`: WMO 코드를 8종(맑음·구름 조금·흐림·비·소나기·눈·뇌우·안개)으로 바꾼다.
   - 테스트: MockWebServer 픽스처, null·빈 응답, 코드 매핑.
   - 모듈 규칙: CLAUDE.md에 `:core:sync → :core:weather`, `:core:weather`는 데이터 모듈에 의존하지 않음을 적는다.
2. **`WeatherWorker`(:core:sync)**: `HolidaySync` 스케줄러 패턴을 다시 쓴다.
   - 앱을 열 때 3시간이 지났으면 한 번 받고, 3시간 주기 작업을 둔다(네트워크 조건).
   - Room에 저장한 뒤 위젯 갱신을 요청한다. 실패해도 캐시를 지우지 않는다. 날씨를 끄면 작업을 취소한다.
3. **지역(WX-02)**
   - 시·군·구 좌표 목록을 `core/data/src/main/assets/regions.json`에 넣는다.
     - 개발 때 Open-Meteo 지오코딩(GeoNames, CC BY 4.0)으로 한 번 만든다. 시·도 이름이 맞는지 스크립트로 검사하고, 출처는 라이선스 화면에 적는다.
     - 앱 화면에서는 검색 API를 부르지 않는다.
   - '대략 위치'(ACCESS_COARSE_LOCATION, 앱 사용 중에만)는 설정 화면에서 `LocationManager` 네트워크 위치를 한 번 읽어 반올림해 저장한다. 거부하면 지역 선택으로 쓴다.
4. **앱 표시(WX-03, :feature:month)**
   - 주·3일·목록 보기 날짜 머리에 아이콘 + '21°/12°'를 붙인다. 낭독은 '맑음, 최고 21도 최저 12도'.
   - 월 보기는 머리(오늘)와 오늘 칸에만 두고, 설정을 켜면 예보가 있는 칸마다 아이콘을 둔다.
   - 날짜 시트 위쪽에 3시간 간격 시간별 줄을 둔다.
   - 12시간 넘은 예보는 흐리게 하고 받아온 시각을 표시한다.
   - 화면 모듈은 `WeatherRepository`(:core:data, Room)만 읽는다.
5. **위젯(WX-04)**
   - 월간·'달력 + 일정'(SplitWidget) 머리에 오늘 아이콘·최고·최저를 둔다. 위젯 설정에서 위젯별로 켠다.
   - '달력 + 일정' 목록의 날짜 머리에 일별 아이콘을 붙인다. 2×2~4×4 잘림 0.
   - WG-10 '오늘' 위젯은 P2라 하지 않는다.
6. **아이콘·출처(WX-05)**
   - 8종 벡터 아이콘은 Material Symbols(Apache 2.0)에서 가져와 `:core:designsystem`에 둔다. 라이트·다크 대비를 확인하고 DESIGN.md에 적는다.
   - 출처 'Weather data by Open-Meteo.com'과 CC BY 4.0 링크를 날씨가 보이는 앱 화면(설정 날씨 항목·날짜 시트)과 위젯 설정 화면에 표기한다.
7. **설정**: 날씨 켜기, 위치 방식, 지역 검색(목록 안 검색), 월 칸 아이콘.
8. **B 묶음 실기기 확인**
   - 주 보기 날짜 머리, 날씨 켠 월간·'달력 + 일정' 위젯 스크린샷
   - 비행기 모드에서 마지막 예보와 받아온 시각
   - 위치 권한 거부 → 지역 선택으로 동작

## C 묶음: EX-08 시험(디버그 메뉴)
- `PhoneCalendarMirror`(:core:sync)를 둔다.
  - 켜면 `ACCOUNT_TYPE_LOCAL` 캘린더 '노션 캘린더'를 만들고, 앞으로 30일 노션 일정(CALENDAR 종류)을 읽기 전용으로 복사한다. 동기화 뒤마다 다시 맞춘다.
  - 끄면 그 캘린더를 통째로 지운다.
- WRITE_CALENDAR 권한을 선언한다. 시험 중에는 권한이 없으면 디버그 메뉴가 그렇다고 알린다.
- 디버그 메뉴 항목은 '폰 캘린더 미러 켜기(시험)' / '끄기(삭제)'다. `BuildConfig.DEBUG`로 막는다.
- 테스트: Robolectric ContentProvider 대신 순수 함수(일정 → ContentValues 변환, 끄기 = 삭제 쿼리)를 테스트한다.
- 실기기: 켜고 → 삼성 캘린더에 보이는지 → 나우 브리프 일정 카드에 뜨는지 확인한다.
  - 나우 브리프는 시간대·조건에 따라 카드가 바뀌므로, 못 보면 '미검증'으로 그대로 보고한다.
  - 끄고 → 캘린더가 사라지는지 확인한다.

## 다시 쓰는 것
- 위젯: `NotionCalWidgetReceiverBase`, `WidgetKinds`, `AllWidgets`, `WidgetPreviews`, `WidgetConfigActivity`, `quickSheetIntent`, `ledgerTabIntent` 패턴
- 작업 예약: `HolidaySync.kt`(HolidayScheduler·Worker), `PeriodicSync`
- 페이지 확인: `NotionClient.pageOrNull`
- 테스트: `FakeNotionStore`, `MigrationTest`, `DatabaseRecoveryTest`, Glance 단위·RemoteViews 테스트(`LedgerWidgetTest` 형태)
- 색: `DesignTokens`

## 검증
- 묶음마다 `gradlew.bat test lint assembleDebug`, lint 0건
- S25 실기기
  - 디데이 위젯 1×1·2×1·목록, 날씨 켠 월간·'달력 + 일정' 위젯, 주 보기 날짜 머리 스크린샷
  - 링크 다섯 개
  - StrictMode 경고 0줄
- 확인 못 한 항목은 '미검증'으로 보고한다(예: 자정 넘김 1분은 실기기에서 시간을 바꿔 보거나 테스트로만).
- 끝나면 PRD §10·§11·§12, DESIGN.md, CLAUDE.md 모듈 규칙을 갱신한다. 계획은 `docs/plans/phase-8.md`로 저장하고 커밋한다. 푸시는 요청할 때만 한다.
