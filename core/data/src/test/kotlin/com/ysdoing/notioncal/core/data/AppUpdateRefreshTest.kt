package com.ysdoing.notioncal.core.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** 앱 업데이트 직후 첫 동기화는 전체 범위를 다시 받는다(업데이트로 바뀐 페이지 해석을 이번 달 밖 캐시에도 반영). */
@RunWith(RobolectricTestRunner::class)
class AppUpdateRefreshTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var db: NotionCalDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NotionCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun newInstallClearsFullSyncMarkOnce() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val store = PreferenceDataStoreFactory.create(scope = scope.backgroundScope) { File(tmp.root, "settings.preferences_pb") }
        val refresh = AppUpdateRefresh(db, SettingsRepository(store, WidgetRefresher {}))
        db.dataSourceDao().upsert(
            DataSourceEntity(
                dataSourceId = "ds", databaseId = "db", name = "Calendar", colorHex = "#0B6E99", titlePropId = "title",
                datePropId = "dAtE", donePropId = null, doneType = null, doneOption = null, undoneOption = null,
                colorPropId = null, sortPropId = null, lastFullSyncAt = 1_000L,
            ),
        )

        assertTrue(refresh.onStart(installedAt = 5_000L))
        assertNull(db.dataSourceDao().get("ds")!!.lastFullSyncAt)

        // 같은 설치본으로 다시 시작하면 그대로 둔다.
        db.dataSourceDao().setLastFullSyncAt("ds", 9_000L)
        assertFalse(refresh.onStart(installedAt = 5_000L))
        assertEquals(9_000L, db.dataSourceDao().get("ds")!!.lastFullSyncAt)

        // 다시 업데이트되면 또 비운다.
        assertTrue(refresh.onStart(installedAt = 6_000L))
        assertNull(db.dataSourceDao().get("ds")!!.lastFullSyncAt)
    }
}
