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
}
