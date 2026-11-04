package com.ysdoing.notioncal.core.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.time.LocalDate

/**
 * 화면·위젯의 단일 출처(PRD §7). 버전을 올릴 때는 마이그레이션과 MigrationTestHelper 테스트를 함께 추가한다(PF-09).
 * phone_events·holidays 테이블은 해당 Phase에서 마이그레이션으로 추가한다.
 *
 * 버전 기록
 * - 1: data_sources, events, sync_log (Phase 1)
 * - 2: widget_configs 추가 (Phase 2, 테이블 추가만이라 AutoMigration)
 * - 3: outbox·conflicts 추가, events.pendingOps·colorOptionId, data_sources.colorOptions (Phase 3, AutoMigration)
 * - 4: data_sources.propCatalog·extraPropIds, events.extraValues (EX-07, 열 추가만이라 AutoMigration)
 * - 5: data_sources.useOptionColors(기본 1)·mappedPropNames·schemaIssues (EX-03·NS-09, AutoMigration)
 * - 6: holidays 추가 (EX-02, 테이블 추가만이라 AutoMigration)
 * - 7: events.titleHasMention (제목 언급 편집 경고, AutoMigration)
 * - 8: data_sources.emphasisPropId·emphasisOptionIds (EX-11 우선순위 강조, AutoMigration)
 * - 9: 해빗 트래커(HB-01·02, WG-11). data_sources.kind(기본 CALENDAR)·linkPropId·linkDataSourceId·habitPropIds,
 *      datePropId nullable(🌱 습관은 날짜 없음, AutoMigration이 표를 다시 만든다), events.linkPageId + 인덱스,
 *      habits 추가, widget_configs.selectedTab(기본 CALENDAR)
 * - 10: 가계부(LD-01~10, WG-11 💰). data_sources.rolePropIds·spendNegative(기본 1)·budgetGroup(기본 전체),
 *      events.createdTime·amount·flow·category·txTime·payment·planned·manual, widget_configs.hideAmounts,
 *      budgets·category_groups(캐시)·ledger_dup_keeps(앱 상태) 추가
 * - 11: 디데이·날씨(DD-01~05, WX-01~05). dday_pins(앱 상태)·weather_days·weather_hours(캐시) 추가,
 *      widget_configs.ddayPageId·showWeather(기본 0)
 * - 12: 노션 보기 위젯(VW-01). view_snapshots(등록한 보기+설정)·view_rows(행 스냅샷, 캐시) 추가, AutoMigration
 * - 13: widget_configs.viewId(VW-04, AutoMigration)
 * - 14: 보기 행 쓰기(M3). view_snapshots.columnInfo·view_rows.typed 열과 view_writes(보내지 않은 바꿈 큐) 추가, AutoMigration
 * - 15: 책 위젯(M5). view_rows.coverUrl(표지 주소, 기본 빈 글자)·progress(읽은 비율, null 가능) 추가, AutoMigration
 * - 16: Phase 10 기반. source_schemas(소스 스키마 전체, 캐시) 추가, view_writes.baseValue(충돌 확인용 이전 값)·
 *      sync_log.requests(주기당 요청 수, 기본 0)·view_rows(pageId) 인덱스 추가, AutoMigration
 * - 17: Phase 11a. view_snapshots.viewConfig(보기 설정 원본)·rowsComplete, widget_configs.viewPage(보드 열 넘김) 추가, AutoMigration
 * - 18: Phase 11c. view_writes.sendAfter(전송 지연, 되돌리기 창), view_snapshots.filterJson(보기 필터 원본), widget_configs.numberStep(숫자 ± 단계값)·
 *      presetsJson(프리셋) 추가, AutoMigration
 * - 19: Phase 11d. widget_configs.layoutJson(위젯 배치: 단일·탭·섹션)·lastTab(마지막으로 본 탭) 추가, AutoMigration
 */
@Database(
    entities = [
        DataSourceEntity::class,
        EventEntity::class,
        SyncLogEntity::class,
        WidgetConfigEntity::class,
        OutboxEntity::class,
        ConflictEntity::class,
        HolidayEntity::class,
        HabitEntity::class,
        BudgetEntity::class,
        CategoryGroupEntity::class,
        LedgerDupKeepEntity::class,
        DdayPinEntity::class,
        WeatherDayEntity::class,
        WeatherHourEntity::class,
        ViewSnapshotEntity::class,
        ViewRowEntity::class,
        ViewWriteEntity::class,
        SourceSchemaEntity::class,
    ],
    version = 19,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7), AutoMigration(from = 7, to = 8), AutoMigration(from = 8, to = 9), AutoMigration(from = 9, to = 10), AutoMigration(from = 10, to = 11), AutoMigration(from = 11, to = 12), AutoMigration(from = 12, to = 13), AutoMigration(from = 13, to = 14), AutoMigration(from = 14, to = 15), AutoMigration(from = 15, to = 16), AutoMigration(from = 16, to = 17), AutoMigration(from = 17, to = 18), AutoMigration(from = 18, to = 19)],
)
@TypeConverters(Converters::class)
abstract class NotionCalDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun dataSourceDao(): DataSourceDao
    abstract fun syncLogDao(): SyncLogDao
    abstract fun widgetConfigDao(): WidgetConfigDao
    abstract fun outboxDao(): OutboxDao
    abstract fun conflictDao(): ConflictDao
    abstract fun holidayDao(): HolidayDao
    abstract fun habitDao(): HabitDao
    abstract fun ledgerDao(): LedgerDao
    abstract fun ddayDao(): DdayDao
    abstract fun weatherDao(): WeatherDao
    abstract fun viewDao(): ViewDao

    companion object {
        const val NAME = "notioncal.db"
    }
}

/** ISO 문자열(yyyy-MM-dd)로 저장해 문자열 비교가 날짜 순서와 같게 한다. */
class Converters {
    @TypeConverter
    fun fromLocalDate(value: LocalDate?): String? = value?.toString()

    @TypeConverter
    fun toLocalDate(value: String?): LocalDate? = value?.let(LocalDate::parse)
}
