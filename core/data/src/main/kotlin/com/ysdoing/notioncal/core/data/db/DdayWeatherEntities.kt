package com.ysdoing.notioncal.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * DD-01 디데이로 고정한 일정(v11). 앱 상태라 DB 복구 때 남긴다(재설치하면 다시 고정, 2026-10-19 결정).
 * [countMode]는 DdayMode 이름(DOWN·UP·DAYS).
 */
@Entity(tableName = "dday_pins")
data class DdayPinEntity(
    @PrimaryKey val pageId: String,
    val sortOrder: Int = 0,
    @ColumnInfo(defaultValue = "DOWN") val countMode: String = "DOWN",
    @ColumnInfo(defaultValue = "7") val hideAfterDays: Int = 7,
)

/** 고정 + 그 일정(없으면 동기화 전이라 제목·날짜가 null). */
data class DdayPinRow(
    val pageId: String,
    val sortOrder: Int,
    val countMode: String,
    val hideAfterDays: Int,
    val title: String?,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val dataSourceId: String?,
    val colorHex: String?,
)

/** WX-01 일별 예보(v11). 캐시다. [code]는 WMO 날씨 코드, [pop]은 강수확률(%). */
@Entity(tableName = "weather_days")
data class WeatherDayEntity(
    @PrimaryKey val date: LocalDate,
    val code: Int?,
    val tMax: Double?,
    val tMin: Double?,
    val pop: Int?,
    /** 받아온 시각(epoch ms). */
    val fetchedAt: Long,
)

/** WX-01 시간별 예보 48시간(v11). [time]은 epoch ms. 캐시다. */
@Entity(tableName = "weather_hours")
data class WeatherHourEntity(
    @PrimaryKey val time: Long,
    val code: Int?,
    val temp: Double?,
    val pop: Int?,
)

@Dao
interface DdayDao {
    @Query(
        """
        SELECT p.pageId, p.sortOrder, p.countMode, p.hideAfterDays, e.title, e.startDate, e.endDate, e.dataSourceId, d.colorHex
        FROM dday_pins p LEFT JOIN events e ON e.pageId = p.pageId LEFT JOIN data_sources d ON d.dataSourceId = e.dataSourceId
        ORDER BY p.sortOrder, p.pageId
        """,
    )
    fun observe(): Flow<List<DdayPinRow>>

    @Query("SELECT * FROM dday_pins")
    suspend fun all(): List<DdayPinEntity>

    @Query("SELECT pageId FROM dday_pins")
    fun observeIds(): Flow<List<String>>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM dday_pins")
    suspend fun nextOrder(): Int

    @Upsert
    suspend fun upsert(pin: DdayPinEntity)

    @Query("DELETE FROM dday_pins WHERE pageId = :pageId")
    suspend fun delete(pageId: String)

    @Query("UPDATE dday_pins SET countMode = :mode WHERE pageId = :pageId")
    suspend fun setMode(pageId: String, mode: String)
}

@Dao
interface WeatherDao {
    @Query("SELECT * FROM weather_days WHERE date BETWEEN :from AND :to ORDER BY date")
    fun observeDays(from: LocalDate, to: LocalDate): Flow<List<WeatherDayEntity>>

    @Query("SELECT * FROM weather_hours WHERE time >= :from ORDER BY time LIMIT :limit")
    fun observeHours(from: Long, limit: Int): Flow<List<WeatherHourEntity>>

    @Query("SELECT MAX(fetchedAt) FROM weather_days")
    fun observeFetchedAt(): Flow<Long?>

    @Upsert
    suspend fun upsertDays(days: List<WeatherDayEntity>)

    @Upsert
    suspend fun upsertHours(hours: List<WeatherHourEntity>)

    /** 새 예보로 바꾼다. 지난 날은 지우고(어제까지), 받아온 날은 덮어쓴다. 실패하면 부르지 않으므로 캐시가 남는다(WX-01). */
    @Transaction
    suspend fun replace(days: List<WeatherDayEntity>, hours: List<WeatherHourEntity>, keepFrom: LocalDate, keepFromTime: Long) {
        deleteDaysBefore(keepFrom)
        deleteHoursBefore(keepFromTime)
        upsertDays(days)
        upsertHours(hours)
    }

    @Query("DELETE FROM weather_days WHERE date < :date")
    suspend fun deleteDaysBefore(date: LocalDate)

    @Query("DELETE FROM weather_hours WHERE time < :time")
    suspend fun deleteHoursBefore(time: Long)

    @Query("DELETE FROM weather_days")
    suspend fun deleteAllDays()

    @Query("DELETE FROM weather_hours")
    suspend fun deleteAllHours()
}
