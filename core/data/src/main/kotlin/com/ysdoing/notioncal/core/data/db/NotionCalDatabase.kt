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
    ],
    version = 8,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7), AutoMigration(from = 7, to = 8)],
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
