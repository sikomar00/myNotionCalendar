# Phase 7 구현 계획: 가계부(LD-01~10) + 통합 위젯 💰 탭(WG-11)

## 배경
PRD §5.9, §11 Phase 7 지시문에 따른 작업이다. 목표는 '이번 주에 얼마 더 쓸 수 있나'를 홈 화면 위젯과 앱 💰 탭에서 바로 보게 하는 것이다. 데이터는 노션 '💰 자금 관리'의 DB 셋을 쓴다.
- 거래내역: 내용·날짜·시간·금액·타입·대분류·결제수단·예정·앱입력
- 예산: 예산명·기간유형·기간값·그룹·예산금액
- 카테고리 마스터: 대분류 → 그룹

2026-10-19에 노션을 조회해 확인한 사실:
- 거래내역은 0건이다. 사용자가 CSV를 병합해 실기기 확인용 데이터를 넣는다.
- 예산은 월 행(2026-01~12, 그룹 '전체')만 있고 예산금액이 모두 비어 있다. 주 행은 없다. 실기기 확인용으로 2026-10 월 예산금액과 2026-W43 주 예산 행은 Claude가 노션 연결로 넣는다(값은 사용자가 나중에 바꾸면 된다).

사용자 결정(2026-10-19):
- 지출 = 타입 '지출'이면서 그룹이 '금융/자산'·'이체/기타'가 아닌 행이다.
- 예산 '전체'도 이 지출과 비교하고, '생활비'는 그룹 생활비와만 비교한다.
- 타입 '이체'는 어디에도 넣지 않는다.

PRD 지시대로 두 묶음으로 나눠 진행한다.
- **A 묶음**: LD-01~06·08·09 + WG-11 💰 탭 → 실기기 확인
- **B 묶음**: LD-07 입력 + LD-10 대조 → 실기기 확인

## 설계 요약 (Phase 6 습관 구조를 그대로 따른다)

**DB 종류**
- `data_sources.kind`에 LEDGER(거래내역, 날짜 있음), BUDGET(예산), CATEGORY(카테고리 마스터)를 더한다.
- BUDGET·CATEGORY는 날짜가 없다. 🌱 습관처럼 동기화 때마다 필터 없이 전체 조회해 전용 표(`budgets`, `category_groups`)를 통째로 바꾼다.
- 짝 관계는 BUDGET·CATEGORY 행의 `linkDataSourceId`가 거래내역을 가리키는 방식으로 둔다. `DataSourceRepository.pairOf`가 세 DB를 함께 켜고 끄고 지운다.

**DB v10 (AutoMigration 9→10)**
- `data_sources`
  - `rolePropIds`(JSON). 거래내역은 amount·flow(타입)·category(대분류)·time(시간)·payment(결제수단)·planned(예정)·manual(앱입력), 예산은 periodType·periodValue·group·amount, 카테고리는 group.
  - `spendNegative`(기본 1, LD-02 '지출 = 음수' 설정값)
  - `budgetGroup`(기본 '전체')
- `events`: `amount`(Long), `flow`(OUT·IN·TRANSFER), `category`, `txTime`, `payment`, `planned`, `manual`, `txKey` + 인덱스(txKey)
- 새 표
  - `budgets`(pageId PK, dataSourceId, periodType, periodKey, groupName, amount): 캐시
  - `category_groups`(pageId PK, dataSourceId, category, groupName): 캐시
  - `ledger_dup_keeps`(pageIdA+pageIdB PK, at): 앱 상태라서 `RecoveringOpenHelperFactory`의 KEEP 목록에 넣는다.
- `widget_configs.hideAmounts`(LD-06)
- `MigrationTest` v9→v10과 복구 테스트를 추가한다.

**노션 매핑**
- 읽기: `PropertyMapping`에 거래 속성 ID를 더하고, `NotionPageMapper`가 RemoteEvent 거래 필드를 읽는다. 금액은 number → Long 반올림, 타입·대분류·결제수단은 select 이름, 시간은 rich_text, 예정·앱입력은 checkbox다.
- 예산·카테고리는 HabitPageMapper처럼 전용 매퍼(`BudgetPageMapper`·`CategoryPageMapper`)로 읽는다.
- 쓰기(B 묶음과 LD-08·09 액션): `EventChange.ledger`와 `PageWrite.ledger`를 더하고, `PropertyEncoder`가 number·select{name}·rich_text·checkbox를 쓴다.

**계산 (순수 함수, `core/data/.../ledger/LedgerMath.kt`)**
- 거래 목록, 대분류→그룹, '둘 다 유지' 쌍, 반영 기준일, 오늘, 주 시작, 예산을 받아 다음을 낸다.
  - 날짜별·주·월의 지출·예정·수입
  - 중복 묶음, '확인 필요' 예정 행, '정리 대상' 앱입력 행
  - 월·주 예산 남음
- 중복(LD-08): 키는 날짜·시간·금액·내용·결제수단이다. 한 번만 세고, 먼저 만든 행을 대표로 둔다.
- 반영 기준일(LD-09): 예정·앱입력이 아닌 행 중 가장 늦은 날짜다. Room `MAX(startDate)` 쿼리로 구한다.
- 앱입력 대조(LD-10): 날짜 ±1일, 금액이 같은 가져온 거래와 1:1로 짝지어 뺀다.
- 주 키(LD-04): 주 안의 월요일의 ISO 주를 `YYYY-Www`로 만든다. 예산 기간값은 정규식으로 읽어 W43과 W043을 같은 값으로 본다.
- 예산이 없거나 비면 같은 유형의 가장 최근 값을 이어 쓴다.
- `MoneyFormat.short`(LD-03): 0, 9,800, 1.2만, 123만.
- PRD는 '합계는 Room 쿼리'라 했지만, 중복·예정·대조 규칙이 SQL로는 복잡하다. 그래서 Room은 범위의 행을 읽기만 하고 계산은 Kotlin에서 한다. 2,000건 월 합계 50ms(LD-02)는 성능 테스트로 확인한다.

**동기화**
- LEDGER는 일정과 같은 범위·증분·삭제 감지를 쓴다(이번 달 ±2개월).
- BUDGET·CATEGORY는 `syncHabits`처럼 전체 조회한다.
- `SchemaCheck`에 거래·예산 역할을 더한다. 금액 속성이 number가 아니면 막고 알린다.
- '노션과 비교'(§12)에 가계부 월 지출 합계 대조를 더한다.

**등록 (LD-01)**
- DB 추가 화면 종류에 '가계부(거래내역)'를 더한다.
- 거래내역의 부모 페이지 자식 DB 중 제목이 '예산'·'카테고리 마스터'인 것을 자동으로 찾는다. 새 API인 database.parent와 `GET blocks/{id}/children`을 쓴다. 못 찾으면 두 링크를 직접 넣는 칸을 보인다.
- 속성은 이름 프리셋으로 추천한다.
- 금액이 number가 아니면 등록을 막고 이유를 보인다.
- 예산 그룹은 예산 DB '그룹' 옵션 중에서 고른다(기본 '전체'). '지출 = 음수' 스위치도 둔다.

**화면 (:feature:ledger, :core:data·:core:designsystem만 의존)**
- 💰 탭(`HomeTab.LEDGER`)
  - 이번 달 지출·예정·수입·월 예산 남음, '10/8까지 반영'
  - 이번 주 예산 줄(남음·초과 경고 + 막대)
  - 월 그리드: 지난 날은 지출, 앞날은 예정(흐리게 + 밑줄), 수입은 초록 '+'
  - 알림 카드: '중복 N건'(먼저 만든 것만 남기기 / 둘 다 유지), '확인 필요 N건'(삭제 / 실제로 전환), '정리 대상 N건'(B 묶음)
  - 쓰기는 모두 `EventWriter`(아웃박스)로 한다.
- B 묶음 `LedgerInputActivity`(LD-07)
  - 위젯 '+'·날짜 탭은 `AppIntents.ACTION_LEDGER_INPUT`, 앱은 FAB에서 연다.
  - 금액 칸에 자동 포커스(숫자 자판), 지출·수입 토글, 대분류 칩(최근 쓴 순), 내용(선택)
  - 앞날이면 예정, 오늘·지난 날이면 앱입력을 켠다. 저장하면 바로 위젯에 반영된다.

**위젯 (:widget)**
- `LedgerWidget` + receiver + xml(기본 4×4, 최소 4×2)
  - 큼: 요약 + 주 예산 줄 + 6주 그리드
  - 4×2: 요약 + 주 예산 줄 + 이번 주 7칸
- `WidgetTab.LEDGER`를 더해 통합 위젯에 💰 탭을 붙인다. 탭 줄 자식은 3개다.
- 금액 숨김(LD-06)은 위젯 설정 스위치로 켜고, 금액 대신 ●●●를 보이며 막대는 남긴다.
- `WidgetKinds`·`AllWidgets`·리시버 기반·미리보기(WG-08)에 등록한다. Glance 자식은 10개 이하로 둔다.

**보안 (LD-06·SEC-02)**
- `Redactor`에 금액 가리기를 더한다.
- 진단 글·충돌 기록에서 거래 내용·금액이 드러나지 않게 테스트한다.

## 작업 순서 (단계마다 테스트를 먼저 쓴다)

**A 묶음**
1. `LedgerMath`·`MoneyFormat`·주 키 테스트를 먼저 쓴다. 15행 내보내기 구조를 본뜬 픽스처로 다음을 다룬다.
   - 부호·환불, 이체 제외, 그룹 제외
   - 중복 CSV 두 번 병합
   - 예정 10/20 시나리오
   - 일요일 주 10/18~24 → W43, 두 달에 걸친 주 9/27~10/3
   - 예산 없음·빈 값·최근 값
   - 표기 0·9,800·12,300·1,234,000
2. DB v10, 엔티티·DAO, MigrationTest, 복구 KEEP.
3. 매핑과 동기화: NotionPageMapper·인코더·Budget/Category 매퍼, 전체 조회, SchemaCheck, FakeNotionStore에 가계부 DB 셋 추가, 비교에 월 합계 대조.
4. `LedgerRepository`: 보드 Flow, 중복 정리·둘 다 유지, 확인 필요 삭제·실제로 전환. 2,000건 성능 테스트.
5. 등록(LD-01): ConnectionService 자동 찾기와 수동 링크 대체, 등록 화면, 짝 켜기·삭제.
6. `:feature:ledger` 💰 탭과 하단 탭 추가(HomeTabsTest 보강).
7. 위젯: 가계부 위젯, 통합 💰 탭, 금액 숨김, Glance·RemoteViews 테스트.
8. A 묶음 실기기 확인.
   - 사용자가 CSV를 병합한다(같은 기간 두 번 → 중복 확인).
   - Claude가 노션 연결로 2026-10 월 예산금액과 2026-W43 주 예산 행을 넣는다.
   - 등록 → 위젯·💰 탭, 금액 숨김 켜고 끄고 스크린샷을 찍는다.

**B 묶음**
9. LD-07 입력 시트와 LD-10 대조를 테스트를 먼저 쓰고 구현한다.
   - 오프라인 저장 → 연결 뒤 노션 생성
   - 앱입력 1건 + 같은 거래 CSV → 1건분
   - 짝 없는 현금 앱입력은 계속 센다
10. B 묶음 실기기 확인(위젯 '+' → 저장 → 노션 생성), PRD §10·§11·§12 갱신, 커밋.

## 다시 쓰는 것
- 짝 등록·전체 조회 패턴: `HabitRegistration.kt`, `NotionSyncEngine.syncHabits`
- 쓰기: `EventWriter`, `OutboxProcessor`
- 위젯 세션·탭 패턴: `HabitSession`, `TabbedWidget`, `WidgetKinds`
- 리시버 기반: `NotionCalWidgetReceiverBase`
- 테스트 대역: `FakeNotionStore`(DB별 구성)
- 성능 테스트: `LargeDataQueryTest`
- 화면 하단 탭: `HomeTabs`

## 검증
- `gradlew.bat test lint assembleDebug` 통과, lint 0건
- 실기기 S25(`-s R3KYC03RGCD`)
  - 가계부 등록(DB 셋)
  - 💰 탭과 위젯 4×4·4×2, 통합 위젯 💰 탭 4×4·4×2
  - 금액 숨김 켜고 끄고, 중복 정리
  - B 묶음 입력 → 노션 반영(노션 MCP로 조회)
- StrictMode 경고 0줄, 콜드 스타트 1초 이내
- 확인 못 한 항목은 '미검증'으로 보고한다.
