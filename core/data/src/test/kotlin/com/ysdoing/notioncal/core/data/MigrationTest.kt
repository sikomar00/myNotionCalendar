package com.ysdoing.notioncal.core.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
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
}
