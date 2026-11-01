# Phase 6 구현 계획 — 해빗 트래커(HB-01~05) + 통합 탭 위젯(WG-11 📅·✅)

## Context
PRD §5.8·§5.2·§11 Phase 6. 습관을 하다 말다 반복하는 사용자가 홈 화면에서 늘 보고 탭 한 번으로 체크하게 한다.
노션은 준비됨: 🌱 습관(습관 1개 = 1행: 이름·이모지·주 목표·진행 중·시작일·순서, 날짜 없음) +
✅ 습관 기록(체크 1번 = 1행: 이름·날짜·습관 relation). 앱 연결 추가 완료.
현재 코드(v8)는 '완료' 외 체크박스·relation을 못 읽고/못 쓰며, 위젯에서 쓰는 액션이 없고, 달력 조회에 DB 역할 구분이 없다.

## 설계 요약
- **역할**: `data_sources.kind` = CALENDAR(기본)·HABIT(🌱)·HABIT_LOG(✅). PRD의 'role'과 같은 뜻이다(기존 `SchemaIssue.role`과 헷갈리지 않게 열 이름은 kind). 달력·검색·위젯 일정 조회는 CALENDAR만. 모르는 값(뒤 Phase의 LEDGER 등)은 `valueOf` 대신 안전하게 읽고 건너뛴다.
- **기록 행 = events 행**: ✅ 기록은 제목·날짜가 있어 기존 동기화·아웃박스·임시 ID·삭제 감지를 그대로 쓴다.
  추가: `events.linkPageId`(relation 대상), `data_sources.linkPropId`. 체크 = `EventWriter.create(..., link)`,
  해제 = 기존 `EventWriter.trash`(임시 ID면 대기 작업까지 지움 — 이미 구현, EventWriter.kt:75).
- **습관 행 = habits 테이블**(캐시): 🌱는 날짜가 없어 동기화 때마다 필터 없이 전체 조회해 교체(삭제 포함).
  전용 `HabitPageMapper`로 읽는다(EX-07 ExtraTypes는 건드리지 않아 기존 기능 회귀 위험을 줄임).
- **datePropId**: PRD §10대로 `String?`로 바꾼다(검증 메모 1). 빈 문자열 대체값은 NotionComparer·재연결(keepValidMapping)로 새어 쓰지 않는다.
  `PropertyMapping.datePropId`는 `String` 그대로 두고, 날짜가 있는 종류(CALENDAR·HABIT_LOG)만 매핑을 만든다. Phase 7 예산·카테고리도 같은 방식.

## 작업 순서 (테스트 먼저)
1. **SEC-05**: `app/src/main/res/xml/data_extraction_rules.xml`, `backup_rules.xml`에 `notioncal.db`(+`-wal`·`-shm`) 제외.
2. **DB v9 (AutoMigration 8→9)**: `Entities.kt` data_sources(kind `@ColumnInfo(defaultValue="CALENDAR")`, datePropId nullable, linkPropId, linkDataSourceId, habitPropIds), events(linkPageId + 인덱스),
   새 `HabitEntity`/`HabitDao`, `WidgetConfigEntity.selectedTab`(defaultValue 지정, `WidgetConfig` 모델·toModel·toEntity에도 추가 — 검증 메모 3). `NotionCalDatabase.kt` 버전·autoMigrations, 스키마 9.json,
   `MigrationTest` v8→v9. habits는 캐시라 `RecoveringOpenHelperFactory` KEEP에 넣지 않음(data_sources 새 열 복원 확인).
3. **달력 분리**: `Daos.kt` observeRange·search에 `d.role='CALENDAR'`. `DataSourceRepository.observeCalendarSources()`를
   만들어 MonthViewModel 필터·QuickSheetViewModel DB 칩·기본 DB·WidgetConfigActivity DB 목록에 쓴다. 설정 화면은 전체 + 역할 표시.
   테스트: `EventDaoTest`에 습관 기록 행이 달력·검색에 안 나오는지.
4. **relation 읽기·쓰기**: `NotionPageMapper`(linkPropId의 relation[0].id → RemoteEvent.linkPageId), `PropertyMapping.linkPropId`,
   `EventChange.link`, `PageWrite.link`, `PropertyEncoder` relation 쓰기, `OutboxProcessor.toPageWrite`, `EventEntity.applying`.
   `NotionDtos.PropertySchemaDto`에 relation(data_source_id). 테스트: NotionPageMapperTest·PropertyEncoderTest·EventWriterTest.
5. **🌱 동기화**: `NotionSyncEngine.syncSource`에서 role HABIT이면 `syncHabits`(전체 조회 → HabitPageMapper → habits 교체) 후 반환.
   `NotionComparer`는 HABIT 건너뜀. FakeNotionStore에 두 DB 스키마·relation 추가. 테스트: NotionSyncEngineTest(추가·변경·삭제·진행 중 해제).
6. **습관 계산(HB-04·05)**: `core/data/.../habit/HabitStats.kt` 순수 함수 — 최근 7일 상태, 이번 주 n/목표(주 시작 = 설정),
   상태(이어가는 중 N일째 / 어제 쉼 / N일 쉼 / 주 단위: 이번 주 2/3·연속 달성 주), 이번 달 한 날 수, 시작일 이전 제외.
   `HabitStatsTest` 먼저(연속·1일 공백·장기 공백·첫날·주 3회·시작일·주 경계).
7. **HabitRepository(HB-02)**: `observeBoard(today)` = 진행 중 습관 + 기록(오늘-42일~오늘, 습관·날짜별 중복 1개로) → HabitStats.
   `toggle(habitId, date)`: 기록 있으면 모두 trash, 없으면 create(제목 "명상 2026-10-19", link). 오늘-6일~오늘만 허용.
   `HabitRepositoryTest`(오프라인 체크→해제→체크 = 생성 1건, 중복 2개 해제 = 둘 다 trash, 동시 탭 2번 = 최종 해제).
   toggle은 싱글턴 Mutex로 읽기·판단·쓰기를 묶는다(검증 메모 7). 통계 조회 범위는 오늘-400일(또는 가장 이른 시작일)~오늘의 (습관, 날짜) 쌍.
8. **등록(HB-01)**: `ConnectionService.inspect`가 relation 속성을 찾으면 '습관 기록' 종류 제안, `registerHabit`이 두 행(HABIT_LOG·HABIT)을
   한 번에 저장하고 🌱 속성(이모지·주 목표·진행 중·시작일·순서)을 이름·타입으로 추천(`MappingSuggester`). `AddDatabaseScreen`에 종류 선택.
9. **:feature:habit + 하단 탭(사용자 결정 2026-10-19)**: 새 모듈(`notioncal.android.library`·compose·hilt, 의존 :core:data·:core:designsystem),
   `HabitRoute`/`HabitViewModel`(오늘 체크·7일·이번 달 잔디·상태 문구). `NotionCalNavHost`를 하단 탭(📅 달력 · ✅ 습관, Phase 7에 💰)으로
   감싸고 시작 탭은 달력. 탭 전환해도 월 화면 상태 유지, 엣지 투 엣지 인셋·콜드 스타트 1초(PF-01) 재측정. settings.gradle.kts에 포함.
   진단 화면 '캐시 초기화'(`DiagnosticsRepository.deleteSynced`)에서 habits도 비운다.
10. **위젯(HB-03, WG-11)**: `HabitWidget`+`HabitWidgetReceiver`+`habit_widget_info.xml`(최소 2×2, 기본 4×2), `HabitToggleAction`,
    `WidgetDependencies.habits()`. 크기: 2×2 오늘 칩 / 4×2 습관 줄(이름 + 7칸 + "3/5", LazyColumn) / 4×4 + 이번 달 잔디.
    `TabbedWidget`(+receiver·xml, 기본 4×4): 탭 줄(📅·✅) + 기존 `WidgetContent` / `HabitContent` 재사용, `TabAction`이
    `selectedTab` 저장 후 그 위젯만 update. `AllWidgets.updateAll`에 둘 추가. Glance 단위 테스트(크기별 자식 10개 이하, 탭 클릭 액션).
11. **PRD 갱신**: §10(실제 스키마: events.role 대신 data_sources.role 조인, datePropId 처리), §11 진행 현황, §12 체크.

## 재사용
- 쓰기: `EventWriter.create/trash` (core/data/.../EventWriter.kt), 아웃박스 `OutboxProcessor`, 위젯 갱신 `GlanceWidgetRefresher`.
- 위젯 액션 패턴: `MonthNavAction`(widget/.../MonthNavAction.kt), 위젯 설정 `WidgetConfigRepository`.
- 위젯 테스트 패턴: `runGlanceAppWidgetUnitTest`(widget/src/test/.../SplitWidgetTest.kt), 샘플 `WidgetSamples.kt`.
- 마이그레이션 테스트 패턴: `MigrationTest.kt`의 vN→vN+1.

## 진행 방식 (사용자 결정 2026-10-19)
- 구현·검증은 Windows PC의 로컬 Claude Code에서 한다(이 클라우드 컨테이너는 dl.google.com 차단으로 빌드 불가).
- 승인되면 이 계획을 `docs/plans/phase-6.md`로 브랜치 `ccr-6810527c-es92oi`에 커밋·푸시해, PC 세션이 그대로 이어받게 한다.
- 진행 중인 설계 검증(OutboxProcessor 경로·AutoMigration·Glance 탭 위젯 제약)의 결과는 커밋 전에 이 문서 끝 '검증 메모'로 덧붙인다.
- PC 세션 지시: "docs/plans/phase-6.md 계획대로 Phase 6을 구현해. 단계마다 테스트 먼저, 끝나면 test lint와 실기기 확인."
- 달력 이외 조회 누수 점검 결과: 알림(ReminderScheduler)·위젯·검색은 observeRange/search를 쓰므로 role 조건 하나로 막힌다.
  NotionComparer는 HABIT_LOG에 그대로 동작하고 HABIT은 건너뛴다.

## 검증
- `gradlew.bat test lint assembleDebug` (JAVA_HOME = Android Studio JBR) 통과.
- 실기기 S25: 설치 → 설정에서 ✅ 습관 기록 등록 → 동기화 → 습관 5개 표시, 위젯 체크 → 노션에 기록 생성·해제 시 휴지통 확인(비행기 모드 포함).
- adb 스크린샷: 습관 위젯 2×2·4×2·4×4, 통합 위젯 📅·✅ 탭, 습관 화면 → `docs/shots/`.
- `adb logcat -d -s NotionCalStrictMode:W` 0줄(PF-03). 확인 못 한 항목은 '미검증'으로 보고.

## 검증 메모 (2026-10-19 설계 검증 결과 — 구현 때 반드시 반영)
1. **datePropId는 nullable로.** 대체값 ""는 NotionComparer(HABIT을 늘 '날짜 없음'으로 건너뜀)·keepValidMapping(재연결 매핑 실패)으로 샌다. 실제 읽는 곳은 6곳(SchemaCheck, NotionComparer, NotionSyncEngine reconcile·toMapping, ConnectionService 비교·keepValidMapping, Repositories.selectableExtraProps, AddDatabaseScreen). nullability 변경은 AutoMigration이 테이블을 다시 만들어 처리한다.
2. **ConnectionService.register가 DataSourceEntity를 새로 만든다**(ConnectionService.kt:108). 재연결·재등록 때 kind·linkPropId·habitPropIds가 지워지지 않게 기존 값에서 복사한다.
3. **WidgetConfig.toEntity()가 전체 행을 upsert한다**(WidgetConfigRepository.kt:47). selectedTab을 모델에 넣지 않으면 월 이동·설정 저장 때마다 탭이 초기화된다.
4. **생성 전송 중에 해제하면 체크가 되살아난다.** EventWriter.trash가 OutboxProcessor 뮤텍스 밖에서 `local-` 행과 아웃박스를 지우는데, createPage 응답 뒤 applyServerPage가 다시 upsert한다. OutboxProcessor.create 트랜잭션에서 `outbox.get(row.id) == null`이면 취소로 보고, 만들어진 page.id를 휴지통으로 보내고(TRASH 작업 추가 또는 trashPage) 반영을 건너뛴다. 이미 전송된 CREATE에 update가 합쳐지는 기존 경로도 같은 틈이 있음을 기록한다.
5. **MonthNavAction이 `NotionCalWidget().update`를 하드코딩한다.** 통합 위젯의 📅 탭에서 ◀/▶를 누르면 탭 없는 월 위젯으로 그려질 수 있다. `AppWidgetManager.getAppWidgetInfo(id).provider.className`으로 위젯 종류를 찾는 공용 함수를 쓴다. WidgetConfigActivity의 `else -> Kind.MONTH`도 새 종류를 넣거나, 습관 위젯 xml에서 configure를 뺀다.
6. **통계·동기화 범위가 짧다.** 42일·이번 달±2개월로는 긴 연속·연속 달성 주가 잘리고 캐시 초기화 뒤 옛 기록을 잃는다. HABIT_LOG 조회 범위 = max(가장 이른 습관 시작일, 오늘-400일)~오늘.
7. **toggle 경쟁**: 위젯 연타 두 번이 둘 다 '기록 없음'을 보고 둘 다 만든다. HabitRepository에 Mutex.
8. **EventChange.link 반영 위치**: mergedWith·isEmpty·applying(임시 행에 linkPageId), toPageWrite, EventSnapshot·snapshot()·toEntity()(거절된 TRASH 복원 때 연결 유지), applyServerPage(서버 매핑이 null이면 로컬 linkPageId 유지). ChangeField·valuesFor 충돌 처리에는 넣지 않는다(기록은 UPDATE하지 않음).
9. **짝 정보**: HABIT_LOG 행에 linkDataSourceId(🌱 습관 data source) 저장. 삭제·켜기/끄기는 두 행을 함께, 삭제 때 habits도 비운다.
10. **종류별 스키마 점검(NS-09)**: HABIT_LOG는 연결 속성이 relation이 아니면 생성 막고 알림. HABIT은 제목만 필수, 나머지는 기본값(이모지 없음, 주 목표 7, 진행 중 true, 시작일 없음, 이름순). 주 목표는 1~7로 자르고 소수는 반올림.
11. **통합 탭 위젯**: WidgetContent가 LocalSize로 크기를 정하므로 탭 줄(~30dp)을 뺀 높이를 넘긴다. NotionCalWidget의 초기 first() 로드를 상태를 받는 composable로 떼어내 두 탭 모두 미리 읽는다(전환 때 빈 화면 방지). 고른 탭의 Flow만 모은다(remember(tab)). SizeMode.Exact. ActionParameters는 String/Long(epochDay). LazyColumn 항목 id는 pageId 해시. 2×2에 습관 8개·글꼴 최대면 칩이 넘치므로 LazyColumn 줄. 4×4 잔디는 LazyColumn 첫 항목으로.
12. **위젯 배선**: AllWidgets.anyPlaced에 새 리시버 추가(안 하면 습관 위젯만 있을 때 자정 알람이 꺼짐). 새 리시버에 onUpdate(scheduleNext)·onDeleted(config 삭제)·onDisabled·providePreview(WG-08). 0.5초 목표를 위해 HabitToggleAction은 그 위젯만 바로 update.
13. **FakeNotionStore가 dataSourceId를 무시한다**(queryAll·createPage·dataSource, 스키마 하드코딩). DB별로 나눈다.
14. **등록**: PropertySchemaDto에 relation{data_source_id, database_id}. HABIT 행의 databaseId·이름은 client.database로. 🌱이 연결되지 않았으면 NotShared를 구체적으로 안내. 🌱 링크를 붙여 넣으면 '✅ 습관 기록 링크를 넣으세요'로 안내. 📅 Calendar에도 relation이 있으므로 '습관 기록' 종류는 사용자가 고른다. HABIT_LOG는 done·color·sort를 null로. 두 행 모두 동기화 요청.
15. **알려진 한계**: 노션에서 만든 지 15분이 안 된 중복 기록은 아직 Room에 없어 해제로 지우지 못한다(다음 동기화 뒤 표시됨).
- 추가 테스트: 생성 전송 중 해제 경쟁, selectedTab이 월 이동 뒤 유지, 통합 위젯 id에서 MonthNavAction, 동시 toggle, 42일 넘는 연속, Comparer의 HABIT 건너뜀.
- SEC-05 제외 대상은 `notioncal.db`·`-wal`·`-shm`·`-journal`(domain="database"). DB 등록 정보·아웃박스도 백업에서 빠지는데, 토큰도 백업하지 않으므로 PRD 의도와 맞다.
- `:feature:habit`은 settings.gradle.kts include와 :app 의존을 추가한다. PRD §10의 events.role은 두지 않고 data_sources.kind 조인으로 대신한다(PRD에 기록).
