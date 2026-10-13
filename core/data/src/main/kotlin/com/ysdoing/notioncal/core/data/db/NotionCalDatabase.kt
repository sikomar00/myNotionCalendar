package com.ysdoing.notioncal.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.time.LocalDate

/**
 * 화면·위젯의 단일 출처(PRD §7). 버전을 올릴 때는 마이그레이션과 MigrationTestHelper 테스트를 함께 추가한다(PF-09).
 * outbox·conflicts·phone_events·widget_configs·holidays 테이블은 해당 Phase에서 마이그레이션으로 추가한다.
 */
@Database(
    entities = [DataSourceEntity::class, EventEntity::class, SyncLogEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class NotionCalDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun dataSourceDao(): DataSourceDao
    abstract fun syncLogDao(): SyncLogDao

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
