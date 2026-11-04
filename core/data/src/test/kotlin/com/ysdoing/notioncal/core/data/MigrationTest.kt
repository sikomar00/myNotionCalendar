package com.ysdoing.notioncal.core.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.ysdoing.notioncal.core.data.db.BudgetEntity
import com.ysdoing.notioncal.core.data.db.HabitEntity
import com.ysdoing.notioncal.core.data.db.LedgerDupKeepEntity
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/** PF-09: 이전 버전 DB로 시작해도 등록 DB·일정이 그대로 남고 새 테이블을 쓸 수 있다. */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbFile = context.getDatabasePath("migration-test.db")

    // 파일·드라이버 기반 생성자를 쓴다(이름 기반 생성자는 Windows 경로에서 이름 비교가 어긋남).
    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = dbFile,
        driver = AndroidSQLiteDriver(),
        databaseClass = NotionCalDatabase::class,
    )

    @Test
    fun v1ToV2KeepsDataAndAddsWidgetConfigs() = runTest {
        helper.createDatabase(1).use { db ->
            db.execSQL(
                """INSERT INTO data_sources (dataSourceId, databaseId, name, colorHex, titlePropId, datePropId,
                   donePropId, doneType, doneOption, undoneOption, colorPropId, sortPropId, enabled, lastSyncAt,
                   lastFullSyncAt, position)
                   VALUES ('ds', 'db', 'Calendar', '#0B6E99', 'title', 'dAtE', 'dOnE', 'CHECKBOX', NULL, NULL,
                   'cTgR', NULL, 1, 1000, 1000, 0)""",
            )
            db.execSQL(
                """INSERT INTO events (pageId, dataSourceId, title, startDate, endDate, startTime, endTime, isAllDay,
                   done, colorKey, sortKey, url, lastEditedTime)
                   VALUES ('p1', 'ds', '영어 스터디', '2026-10-13', NULL, NULL, NULL, 1, 0, 'blue', NULL, '', 0)""",
            )
        }

        // 마이그레이션 SQL과 결과 스키마가 내보낸 2.json과 일치하는지 검증한다.
        helper.runMigrationsAndValidate(2).close()
        helper.runMigrationsAndValidate(3).close()

        val db = Room.databaseBuilder(context, NotionCalDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()
        try {
            val events = EventRepository(db.eventDao()).observeRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)).first()
            assertEquals(listOf("영어 스터디"), events.map { it.title })
            assertEquals(1000L, db.dataSourceDao().get("ds")!!.lastSyncAt)

            val widgets = WidgetConfigRepository(db.widgetConfigDao())
            assertEquals(WidgetConfig(7), widgets.get(7)) // 저장 전에는 기본값
            widgets.shiftMonth(7, -1)
            assertEquals(-1, widgets.get(7).monthOffset)
        } finally {
            db.close()
        }
    }

    @Test
    fun v2ToV3KeepsWidgetConfigAndAddsOutbox() = runTest {
        helper.createDatabase(2).use { db ->
            db.execSQL(
                """INSERT INTO data_sources (dataSourceId, databaseId, name, colorHex, titlePropId, datePropId,
                   donePropId, doneType, doneOption, undoneOption, colorPropId, sortPropId, enabled, lastSyncAt,
                   lastFullSyncAt, position)
                   VALUES ('ds', 'db', 'Calendar', '#0B6E99', 'title', 'dAtE', NULL, NULL, NULL, NULL, NULL, NULL, 1, NULL, NULL, 0)""",
            )
            db.execSQL(
                """INSERT INTO events (pageId, dataSourceId, title, startDate, endDate, startTime, endTime, isAllDay,
                   done, colorKey, sortKey, url, lastEditedTime)
                   VALUES ('p1', 'ds', '회의', '2026-10-13', NULL, NULL, NULL, 1, 0, NULL, NULL, '', 0)""",
            )
            db.execSQL(
                """INSERT INTO widget_configs (appWidgetId, monthOffset, dataSourceIds, theme, opacity, fontScale,
                   maxPerCell, lineSpacing, hideDone, showPhoneCal)
                   VALUES (7, 2, '', 'DARK', 80, 120, 0, 'NORMAL', 0, 0)""",
            )
        }
        helper.runMigrationsAndValidate(3).close()

        val db = Room.databaseBuilder(context, NotionCalDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()
        try {
            val event = db.eventDao().get("p1")!!
            assertEquals(0, event.pendingOps)
            assertEquals(null, event.colorOptionId)
            assertEquals(WidgetTheme.DARK, WidgetConfigRepository(db.widgetConfigDao()).get(7).theme)
            assertEquals(2, WidgetConfigRepository(db.widgetConfigDao()).get(7).monthOffset)
            assertEquals(emptyList<Any>(), db.outboxDao().all())
        } finally {
            db.close()
        }
    }

    @Test
    fun v3ToV4AddsExtraPropertyColumns() = runTest {
        helper.createDatabase(3).use { db ->
            db.execSQL(
                """INSERT INTO data_sources (dataSourceId, databaseId, name, colorHex, titlePropId, datePropId,
                   donePropId, doneType, doneOption, undoneOption, colorPropId, sortPropId, enabled, lastSyncAt,
                   lastFullSyncAt, position, colorOptions)
                   VALUES ('ds', 'db', 'Calendar', '#0B6E99', 'title', 'dAtE', NULL, NULL, NULL, NULL, 'cTgR', NULL, 1, 500, 500, 0, '[]')""",
            )
            db.execSQL(
                """INSERT INTO events (pageId, dataSourceId, title, startDate, endDate, startTime, endTime, isAllDay,
                   done, colorKey, sortKey, url, lastEditedTime, colorOptionId, pendingOps)
                   VALUES ('p1', 'ds', '회의', '2026-10-13', NULL, NULL, NULL, 1, 0, NULL, NULL, '', 0, 'o1', 1)""",
            )
            db.execSQL(
                """INSERT INTO outbox (pageId, dataSourceId, op, changes, baseValues, baseLastEdited, createdAt, attempts,
                   nextAttemptAt, lastError) VALUES ('p1', 'ds', 'UPDATE', '{"title":"회의2"}', '{"title":"회의"}', 0, 1, 0, 0, NULL)""",
            )
        }
        helper.runMigrationsAndValidate(4).close()

        val db = Room.databaseBuilder(context, NotionCalDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()
        try {
            val source = db.dataSourceDao().get("ds")!!
            assertEquals(null, source.propCatalog)
            assertEquals(null, source.extraPropIds)
            assertEquals(500L, source.lastSyncAt)
            val event = db.eventDao().get("p1")!!
            assertEquals(null, event.extraValues)
            assertEquals(1, event.pendingOps)
            // v3 때 쌓인 아웃박스 JSON(props 없음)도 그대로 읽힌다.
            val change = OutboxJson.decodeFromString(EventChange.serializer(), db.outboxDao().all().single().changes)
            assertEquals(EventChange(title = "회의2"), change)
        } finally {
            db.close()
        }
    }
    /** v4 → v5: 색 기준(기본 '속성 값 색')·NS-09 열이 붙고 기존 값은 그대로다. 색 기준을 끄면 DB 색으로 읽힌다(EX-03). */
    @Test
    fun v4ToV5AddsColorStyleAndSchemaColumns() = runTest {
        helper.createDatabase(4).use { db ->
            db.execSQL(
                """INSERT INTO data_sources (dataSourceId, databaseId, name, colorHex, titlePropId, datePropId,
                   donePropId, doneType, doneOption, undoneOption, colorPropId, sortPropId, enabled, lastSyncAt,
                   lastFullSyncAt, position, colorOptions, propCatalog, extraPropIds)
                   VALUES ('ds', 'db', 'Calendar', '#0B6E99', 'title', 'dAtE', NULL, NULL, NULL, NULL, 'cTgR', NULL, 1, 500, 500, 0, '[]', '[]', '["p"]')""",
            )
            db.execSQL(
                """INSERT INTO events (pageId, dataSourceId, title, startDate, endDate, startTime, endTime, isAllDay,
                   done, colorKey, sortKey, url, lastEditedTime, colorOptionId, pendingOps, extraValues)
                   VALUES ('p1', 'ds', '회의', '2026-10-13', NULL, NULL, NULL, 1, 0, 'yellow', NULL, '', 0, 'o1', 0, NULL)""",
            )
        }
        helper.runMigrationsAndValidate(5).close()

        val db = Room.databaseBuilder(context, NotionCalDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()
        try {
            val source = db.dataSourceDao().get("ds")!!
            assertEquals(true, source.useOptionColors)
            assertEquals(null, source.schemaIssues)
            assertEquals("[\"p\"]", source.extraPropIds)
            val repo = EventRepository(db.eventDao())
            val day = java.time.LocalDate.of(2026, 10, 13)
            assertEquals("#DFAB01", repo.observeRange(day, day).first().single().colorHex) // Ctgr 노랑
            db.dataSourceDao().setColorStyle("ds", "#E03E3E", useOptionColors = false)
            assertEquals("#E03E3E", repo.observeRange(day, day).first().single().colorHex) // DB 색 하나로
        } finally {
            db.close()
        }
    }

    /** v5 → v6: holidays 테이블이 붙고 기존 데이터는 그대로다(EX-02). */
    @Test
    fun v5ToV6AddsHolidays() = runTest {
        helper.createDatabase(5).close()
        helper.runMigrationsAndValidate(6).close()
        val db = Room.databaseBuilder(context, NotionCalDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()
        try {
            val day = java.time.LocalDate.of(2026, 10, 3)
            db.holidayDao().replaceRange(day.minusDays(10), day.plusDays(10), listOf(com.ysdoing.notioncal.core.data.db.HolidayEntity(day, "개천절", "GOOGLE")))
            assertEquals(listOf("개천절"), db.holidayDao().observeRange(day, day).first().map { it.name })
            // 범위를 다시 채우면 이전 값은 지워진다(캘린더에서 빠진 공휴일 정리).
            db.holidayDao().replaceRange(day.minusDays(10), day.plusDays(10), emptyList())
            assertEquals(emptyList<Any>(), db.holidayDao().observeRange(day, day).first())
        } finally {
            db.close()
        }
    }
    /** v6 → v7: events.titleHasMention(기본 0)이 붙는다. */
    @Test
    fun v6ToV7AddsTitleMentionFlag() = runTest {
        helper.createDatabase(6).use { db ->
            db.execSQL(
                """INSERT INTO events (pageId, dataSourceId, title, startDate, endDate, startTime, endTime, isAllDay,
                   done, colorKey, sortKey, url, lastEditedTime, colorOptionId, pendingOps, extraValues)
                   VALUES ('p1', 'ds', '회의', '2026-10-13', NULL, NULL, NULL, 1, 0, NULL, NULL, '', 0, NULL, 0, NULL)""",
            )
        }
        helper.runMigrationsAndValidate(7).close()
        val db = Room.databaseBuilder(context, NotionCalDatabase::class.java, dbFile.absolutePath).allowMainThreadQueries().build()
        try {
            assertEquals(false, db.eventDao().get("p1")!!.titleHasMention)
        } finally {
            db.close()
        }
    }

    /** v7 → v8: data_sources에 강조 기준 열(EX-11)이 붙고, 기존 DB는 강조 없음(null)이다. */
    @Test
    fun v7ToV8AddsEmphasisColumns() = runTest {
        helper.createDatabase(7).use { db ->
            db.execSQL(
                """INSERT INTO data_sources (dataSourceId, databaseId, name, colorHex, titlePropId, datePropId,
                   donePropId, doneType, doneOption, undoneOption, colorPropId, sortPropId, enabled, lastSyncAt,
                   lastFullSyncAt, position, colorOptions, propCatalog, extraPropIds, useOptionColors, mappedPropNames, schemaIssues)
                   VALUES ('ds', 'db', 'Calendar', '#0B6E99', 'title', 'dAtE', NULL, NULL, NULL, NULL, NULL, NULL, 1, 500, 500, 0,
                   NULL, NULL, NULL, 1, NULL, NULL)""",
            )
        }
        helper.runMigrationsAndValidate(8).close()
        val db = Room.databaseBuilder(context, NotionCalDatabase::class.java, dbFile.absolutePath).allowMainThreadQueries().build()
        try {
            val source = db.dataSourceDao().get("ds")!!
            assertEquals(null, source.emphasisPropId)
            assertEquals(null, source.emphasisOptionIds)
            assertEquals(500L, source.lastSyncAt)
        } finally {
            db.close()
        }
    }

    /**
     * v8 → v9(해빗 트래커): 기존 DB는 일정(CALENDAR) 종류, 일정에는 습관 연결이 없고, 위젯은 📅 탭이다.
     * datePropId가 nullable이 되어 날짜 없는 🌱 습관 DB를 저장할 수 있고 habits 표를 쓸 수 있다.
     */
    @Test
    fun v8ToV9AddsHabitColumnsAndTable() = runTest {
        helper.createDatabase(8).use { db ->
            db.execSQL(
                """INSERT INTO data_sources (dataSourceId, databaseId, name, colorHex, titlePropId, datePropId,
                   donePropId, doneType, doneOption, undoneOption, colorPropId, sortPropId, enabled, lastSyncAt,
                   lastFullSyncAt, position, colorOptions, propCatalog, extraPropIds, useOptionColors, mappedPropNames, schemaIssues,
                   emphasisPropId, emphasisOptionIds)
                   VALUES ('ds', 'db', 'Calendar', '#0B6E99', 'title', 'dAtE', 'dOnE', 'CHECKBOX', NULL, NULL, NULL, NULL, 1, 500, 500, 0,
                   NULL, NULL, NULL, 1, NULL, NULL, 'pRi', '["pri-1"]')""",
            )
            db.execSQL(
                """INSERT INTO events (pageId, dataSourceId, title, startDate, endDate, startTime, endTime, isAllDay,
                   done, colorKey, sortKey, url, lastEditedTime, colorOptionId, pendingOps, extraValues, titleHasMention)
                   VALUES ('p1', 'ds', '회의', '2026-10-13', NULL, NULL, NULL, 1, 0, NULL, NULL, '', 0, NULL, 1, NULL, 0)""",
            )
            db.execSQL(
                """INSERT INTO widget_configs (appWidgetId, monthOffset, dataSourceIds, theme, opacity, fontScale, maxPerCell,
                   lineSpacing, hideDone, showPhoneCal) VALUES (7, 2, '', 'DARK', 80, 100, 0, 'NORMAL', 0, 0)""",
            )
        }
        helper.runMigrationsAndValidate(9).close()
        val db = Room.databaseBuilder(context, NotionCalDatabase::class.java, dbFile.absolutePath).allowMainThreadQueries().build()
        try {
            val source = db.dataSourceDao().get("ds")!!
            assertEquals(SourceKind.CALENDAR, source.sourceKind)
            assertEquals("dAtE", source.datePropId)
            assertEquals("pRi", source.emphasisPropId)
            assertEquals(null, source.linkPropId)
            val event = db.eventDao().get("p1")!!
            assertEquals(null, event.linkPageId)
            assertEquals(1, event.pendingOps)
            val widget = WidgetConfigRepository(db.widgetConfigDao()).get(7)
            assertEquals(WidgetTab.CALENDAR, widget.selectedTab)
            assertEquals(2, widget.monthOffset)

            db.dataSourceDao().upsert(source.copy(dataSourceId = "habits", datePropId = null, kind = SourceKind.HABIT.name))
            assertEquals(null, db.dataSourceDao().get("habits")!!.datePropId)
            db.habitDao().insert(listOf(HabitEntity("h1", "habits", "명상", "🧘", 7, true, LocalDate.of(2026, 10, 19), 1.0)))
            assertEquals(listOf("h1"), db.habitDao().observeActive().first().map { it.pageId })
        } finally {
            db.close()
        }
    }

    /** v9 → v10(가계부): 기존 DB는 지출 음수·예산 그룹 '전체', 일정은 거래 값 없음, 위젯은 금액 보임. 새 표를 쓸 수 있다. */
    @Test
    fun v9ToV10AddsLedgerColumnsAndTables() = runTest {
        helper.createDatabase(9).use { db ->
            db.execSQL(
                """INSERT INTO data_sources (dataSourceId, databaseId, name, colorHex, titlePropId, datePropId,
                   donePropId, doneType, doneOption, undoneOption, colorPropId, sortPropId, enabled, lastSyncAt,
                   lastFullSyncAt, position, colorOptions, propCatalog, extraPropIds, useOptionColors, mappedPropNames, schemaIssues,
                   emphasisPropId, emphasisOptionIds, kind, linkPropId, linkDataSourceId, habitPropIds)
                   VALUES ('ds', 'db', 'Calendar', '#0B6E99', 'title', 'dAtE', NULL, NULL, NULL, NULL, NULL, NULL, 1, 500, 500, 0,
                   NULL, NULL, NULL, 1, NULL, NULL, NULL, NULL, 'CALENDAR', NULL, NULL, NULL)""",
            )
            db.execSQL(
                """INSERT INTO events (pageId, dataSourceId, title, startDate, endDate, startTime, endTime, isAllDay,
                   done, colorKey, sortKey, url, lastEditedTime, colorOptionId, pendingOps, extraValues, titleHasMention, linkPageId)
                   VALUES ('p1', 'ds', '회의', '2026-10-13', NULL, NULL, NULL, 1, 0, NULL, NULL, '', 0, NULL, 0, NULL, 0, NULL)""",
            )
            db.execSQL(
                """INSERT INTO widget_configs (appWidgetId, monthOffset, dataSourceIds, theme, opacity, fontScale, maxPerCell,
                   lineSpacing, hideDone, showPhoneCal, selectedTab) VALUES (7, 0, '', 'DARK', 80, 100, 0, 'NORMAL', 0, 0, 'HABIT')""",
            )
        }
        helper.runMigrationsAndValidate(10).close()
        val db = Room.databaseBuilder(context, NotionCalDatabase::class.java, dbFile.absolutePath).allowMainThreadQueries().build()
        try {
            val source = db.dataSourceDao().get("ds")!!
            assertEquals(true, source.spendNegative)
            assertEquals("전체", source.budgetGroup)
            assertEquals(null, source.rolePropIds)
            val event = db.eventDao().get("p1")!!
            assertEquals(null, event.amount)
            assertEquals(false, event.planned)
            assertEquals(0L, event.createdTime)
            val widget = WidgetConfigRepository(db.widgetConfigDao()).get(7)
            assertEquals(WidgetTab.HABIT, widget.selectedTab)
            assertEquals(false, widget.hideAmounts)

            db.ledgerDao().replaceBudgets("budget", listOf(BudgetEntity("b1", "budget", "월", "2026-10", "전체", 1_000_000)))
            db.ledgerDao().insertDupKeep(LedgerDupKeepEntity("a", "b", 1))
            assertEquals(listOf("a" to "b"), db.ledgerDao().observeDupKeeps().first().map { it.pageIdA to it.pageIdB })
        } finally {
            db.close()
        }
    }

    /** v10 → v11(디데이·날씨): 위젯은 디데이 없음·날씨 끔, 디데이 고정·예보 표를 쓸 수 있다. */
    @Test
    fun v10ToV11AddsDdayAndWeather() = runTest {
        helper.createDatabase(10).use { db ->
            db.execSQL(
                """INSERT INTO widget_configs (appWidgetId, monthOffset, dataSourceIds, theme, opacity, fontScale, maxPerCell,
                   lineSpacing, hideDone, showPhoneCal, selectedTab, hideAmounts) VALUES (7, 0, '', 'DARK', 80, 100, 0, 'NORMAL', 0, 0, 'LEDGER', 1)""",
            )
        }
        helper.runMigrationsAndValidate(11).close()
        val db = Room.databaseBuilder(context, NotionCalDatabase::class.java, dbFile.absolutePath).allowMainThreadQueries().build()
        try {
            val widget = WidgetConfigRepository(db.widgetConfigDao()).get(7)
            assertEquals(WidgetTab.LEDGER, widget.selectedTab)
            assertEquals(true, widget.hideAmounts)
            assertEquals(null, widget.ddayPageId)
            assertEquals(false, widget.showWeather)

            db.ddayDao().upsert(com.ysdoing.notioncal.core.data.db.DdayPinEntity("p1"))
            assertEquals(listOf("p1" to "DOWN"), db.ddayDao().all().map { it.pageId to it.countMode })
            val day = java.time.LocalDate.of(2026, 10, 19)
            db.weatherDao().upsertDays(listOf(com.ysdoing.notioncal.core.data.db.WeatherDayEntity(day, 0, 21.0, 12.0, 10, 5)))
            assertEquals(1, db.weatherDao().observeDays(day, day).first().size)
        } finally {
            db.close()
        }
    }

    /** v11 → v12(노션 보기 위젯): 기존 데이터는 그대로고 보기·행 표를 쓸 수 있다. */
    @Test
    fun v11ToV12AddsViewTables() = runTest {
        helper.createDatabase(11).use { db ->
            db.execSQL("INSERT INTO dday_pins (pageId, sortOrder, countMode, hideAfterDays) VALUES ('p1', 0, 'DOWN', 7)")
        }
        helper.runMigrationsAndValidate(12).close()
        val db = Room.databaseBuilder(context, NotionCalDatabase::class.java, dbFile.absolutePath).allowMainThreadQueries().build()
        try {
            assertEquals(listOf("p1"), db.ddayDao().all().map { it.pageId })
            val view = com.ysdoing.notioncal.core.data.db.ViewSnapshotEntity("v1", "ds1", "할 일", "table")
            db.viewDao().upsert(view)
            db.viewDao().replace(view, listOf(com.ysdoing.notioncal.core.data.db.ViewRowEntity("v1", 0, "pg", "https://n", "a")))
            assertEquals(listOf("pg"), db.viewDao().rows("v1").map { it.pageId })
        } finally {
            db.close()
        }
    }

    /** v12 → v13(보기 위젯 연결): 기존 위젯은 보기 없음(null)이고 보기를 연결할 수 있다. */
    @Test
    fun v12ToV13AddsWidgetViewId() = runTest {
        helper.createDatabase(12).use { db ->
            db.execSQL(
                """INSERT INTO widget_configs (appWidgetId, monthOffset, dataSourceIds, theme, opacity, fontScale, maxPerCell,
                   lineSpacing, hideDone, showPhoneCal, selectedTab, hideAmounts, showWeather) VALUES (9, 0, '', 'DARK', 80, 100, 0, 'NORMAL', 0, 0, 'CALENDAR', 0, 0)""",
            )
        }
        helper.runMigrationsAndValidate(13).close()
        val db = Room.databaseBuilder(context, NotionCalDatabase::class.java, dbFile.absolutePath).allowMainThreadQueries().build()
        try {
            val dao = db.widgetConfigDao()
            assertEquals(null, dao.get(9)!!.viewId)
            dao.upsert(dao.get(9)!!.copy(viewId = "v1"))
            assertEquals("v1", dao.get(9)!!.viewId)
        } finally {
            db.close()
        }
    }

    /** v13 → v14(보기 행 쓰기): 기존 보기·행은 그대로이고 새 열은 비어 있으며, 보내지 않은 바꿈 큐를 쓸 수 있다. */
    @Test
    fun v13ToV14AddsViewWriteInfoAndQueue() = runTest {
        helper.createDatabase(13).use { db ->
            db.execSQL("INSERT INTO view_snapshots (viewId, dataSourceId, name, viewType, columns, syncedAt) VALUES ('v1', 'ds1', '할 일', 'table', '이름', 5)")
            db.execSQL("INSERT INTO view_rows (viewId, position, pageId, url, cells) VALUES ('v1', 0, 'pg', 'https://n', '첫째')")
        }
        helper.runMigrationsAndValidate(14).close()
        val db = Room.databaseBuilder(context, NotionCalDatabase::class.java, dbFile.absolutePath).allowMainThreadQueries().build()
        try {
            val dao = db.viewDao()
            assertEquals("", dao.snapshot("v1")!!.columnInfo)
            val row = dao.row("v1", "pg")!!
            assertEquals("첫째", row.cells)
            assertEquals("", row.typed)
            val write = com.ysdoing.notioncal.core.data.db.ViewWriteEntity(
                viewId = "v1", pageId = "pg", dataSourceId = "ds1", propId = "dn", propType = "checkbox", value = "true", createdAt = 1,
            )
            dao.enqueue(row.copy(typed = "{}"), write)
            assertEquals(1, dao.pendingWriteCount())
            assertEquals("true", dao.firstWrite()!!.value)
        } finally {
            db.close()
        }
    }

    /** v14 → v15(책 위젯 M5): 기존 행은 그대로이고 표지 주소는 빈 글자·읽은 비율은 null이며, 새 값을 쓸 수 있다. */
    @Test
    fun v14ToV15AddsCoverUrlAndProgress() = runTest {
        helper.createDatabase(14).use { db ->
            db.execSQL("INSERT INTO view_snapshots (viewId, dataSourceId, name, viewType, columns, syncedAt) VALUES ('v1', 'ds1', '읽는 중', 'gallery', '제목', 5)")
            db.execSQL("INSERT INTO view_rows (viewId, position, pageId, url, cells, typed) VALUES ('v1', 0, 'pg', 'https://n', '책', '{}')")
        }
        helper.runMigrationsAndValidate(15).close()
        val db = Room.databaseBuilder(context, NotionCalDatabase::class.java, dbFile.absolutePath).allowMainThreadQueries().build()
        try {
            val dao = db.viewDao()
            val row = dao.row("v1", "pg")!!
            assertEquals(listOf("책", "{}", "", null), listOf(row.cells, row.typed, row.coverUrl, row.progress))
            dao.upsert(dao.snapshot("v1")!!)
            dao.replace(dao.snapshot("v1")!!, listOf(row.copy(coverUrl = "https://img/1.jpg", progress = 0.4f)))
            assertEquals(listOf("https://img/1.jpg", 0.4f), dao.row("v1", "pg")!!.let { listOf(it.coverUrl, it.progress) })
            assertEquals(listOf("https://img/1.jpg"), dao.coverUrls())
        } finally {
            db.close()
        }
    }

    /** v15 → v16(Phase 10 기반): 기존 데이터는 그대로이고 새 열은 기본값, 새 표와 인덱스를 쓸 수 있다. */
    @Test
    fun v15ToV16AddsSchemaCacheBaseValueRequestCountAndPageIndex() = runTest {
        helper.createDatabase(15).use { db ->
            db.execSQL("INSERT INTO view_snapshots (viewId, dataSourceId, name, viewType, columns, syncedAt) VALUES ('v1', 'ds1', '읽는 중', 'gallery', '제목', 5)")
            db.execSQL("INSERT INTO view_rows (viewId, position, pageId, url, cells, typed, coverUrl, progress) VALUES ('v1', 0, 'pg', 'https://n', '책', '{}', 'https://img/1.jpg', 0.12)")
            db.execSQL("INSERT INTO view_writes (viewId, pageId, dataSourceId, propId, propType, value, createdAt, attempts) VALUES ('v1', 'pg', 'ds1', 'ing', 'number', '40.0', 7, 2)")
            db.execSQL("INSERT INTO sync_log (at, trigger, result, durationMs, pulled, pushed, error) VALUES (9, 'PERIODIC', 'OK', 120, 3, 1, NULL)")
        }
        helper.runMigrationsAndValidate(16).close()
        val db = Room.databaseBuilder(context, NotionCalDatabase::class.java, dbFile.absolutePath).allowMainThreadQueries().build()
        try {
            val dao = db.viewDao()
            val row = dao.row("v1", "pg")!!
            assertEquals(listOf("https://img/1.jpg", 0.12f), listOf(row.coverUrl, row.progress)) // 표지·읽은 비율 보존
            val write = dao.firstWrite()!!
            assertEquals(listOf("40.0", 2, null), listOf(write.value, write.attempts, write.baseValue)) // 대기 중인 쓰기 보존
            assertEquals(0, db.syncLogDao().recent(5).single().requests)
            assertEquals(listOf("v1"), dao.viewsContaining("pg"))
            dao.upsertSchema(com.ysdoing.notioncal.core.data.db.SourceSchemaEntity("ds1", "{\"id\":\"ds1\"}", 1))
            assertEquals("{\"id\":\"ds1\"}", dao.schema("ds1")!!.schemaJson)
        } finally {
            db.close()
        }
    }

    /** v16 → v17(Phase 11a): 보기 설정 원본·행 완전 여부·보드 열 넘김 위치 열은 기본값이고 기존 데이터는 그대로다. */
    @Test
    fun v16ToV17AddsViewConfigRowsCompleteAndViewPage() = runTest {
        helper.createDatabase(16).use { db ->
            db.execSQL("INSERT INTO view_snapshots (viewId, dataSourceId, name, viewType, columns, syncedAt) VALUES ('v1', 'ds1', '보드', 'board', '이름', 5)")
            db.execSQL("INSERT INTO widget_configs (appWidgetId, monthOffset, dataSourceIds, theme, opacity, fontScale, maxPerCell, lineSpacing, hideDone, showPhoneCal, viewId) VALUES (7, 0, '', 'SYSTEM', 100, 100, 0, 'NORMAL', 0, 0, 'v1')")
        }
        helper.runMigrationsAndValidate(17).close()
        val db = Room.databaseBuilder(context, NotionCalDatabase::class.java, dbFile.absolutePath).allowMainThreadQueries().build()
        try {
            val view = db.viewDao().snapshot("v1")!!
            assertEquals(listOf("보드", "", true), listOf(view.name, view.viewConfig, view.rowsComplete))
            val widget = db.widgetConfigDao().get(7)!!
            assertEquals(listOf("v1", 0), listOf(widget.viewId, widget.viewPage))
        } finally {
            db.close()
        }
    }

    /** v17 → v18(Phase 11c): 전송 지연(sendAfter)·보기 필터 원본·숫자 ± 단계값·프리셋 열은 기본값이고, 대기 중인 쓰기는 지연 없이 그대로 나간다. */
    @Test
    fun v17ToV18AddsSendAfterFilterJsonNumberStepAndPresets() = runTest {
        helper.createDatabase(17).use { db ->
            db.execSQL("INSERT INTO view_snapshots (viewId, dataSourceId, name, viewType, columns, syncedAt) VALUES ('v1', 'ds1', '할 일', 'table', '이름', 5)")
            db.execSQL("INSERT INTO view_writes (viewId, pageId, dataSourceId, propId, propType, value, createdAt, attempts) VALUES ('v1', 'p1', 'ds1', 'dn', 'checkbox', 'true', 9, 1)")
            db.execSQL("INSERT INTO widget_configs (appWidgetId, monthOffset, dataSourceIds, theme, opacity, fontScale, maxPerCell, lineSpacing, hideDone, showPhoneCal, viewId) VALUES (7, 0, '', 'SYSTEM', 100, 100, 0, 'NORMAL', 0, 0, 'v1')")
        }
        helper.runMigrationsAndValidate(18).close()
        val db = Room.databaseBuilder(context, NotionCalDatabase::class.java, dbFile.absolutePath).allowMainThreadQueries().build()
        try {
            val write = db.viewDao().firstDueWrite(now = 10)!!
            assertEquals(listOf("true", 1, 0L), listOf(write.value, write.attempts, write.sendAfter)) // 옛 쓰기는 바로 보낼 수 있다
            assertEquals("", db.viewDao().snapshot("v1")!!.filterJson)
            val widget = db.widgetConfigDao().get(7)!!
            assertEquals(listOf(1.0, ""), listOf(widget.numberStep, widget.presetsJson))
        } finally {
            db.close()
        }
    }

    /** v18 → v19(Phase 11d): 위젯 배치(layoutJson)와 마지막으로 본 탭(lastTab) 열은 기본값이고 기존 위젯 설정은 그대로다. */
    @Test
    fun v18ToV19AddsLayoutJsonAndLastTab() = runTest {
        helper.createDatabase(18).use { db ->
            db.execSQL("INSERT INTO widget_configs (appWidgetId, monthOffset, dataSourceIds, theme, opacity, fontScale, maxPerCell, lineSpacing, hideDone, showPhoneCal, viewId, numberStep, presetsJson) VALUES (7, 0, '', 'SYSTEM', 100, 100, 0, 'NORMAL', 0, 0, 'v1', 5.0, '[]')")
        }
        helper.runMigrationsAndValidate(19).close()
        val db = Room.databaseBuilder(context, NotionCalDatabase::class.java, dbFile.absolutePath).allowMainThreadQueries().build()
        try {
            val widget = db.widgetConfigDao().get(7)!!
            assertEquals(listOf("v1", 5.0, "[]"), listOf(widget.viewId, widget.numberStep, widget.presetsJson)) // 기존 설정 보존
            assertEquals(listOf("", 0), listOf(widget.layoutJson, widget.lastTab))
        } finally {
            db.close()
        }
    }
}
