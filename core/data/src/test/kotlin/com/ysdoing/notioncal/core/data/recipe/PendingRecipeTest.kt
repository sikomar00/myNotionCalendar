package com.ysdoing.notioncal.core.data.recipe

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.SettingsRepository
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.WidgetConfigRepository
import com.ysdoing.notioncal.core.data.WidgetRefresher
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Phase 11h 핀 요청 전후의 '대기 레시피': 요청할 때 저장해 두고, 홈 화면에 놓인 위젯의 번호가 정해지면(핀 콜백) 또는 그 위젯의 설정 화면이 열리면
 * 위젯 설정으로 옮긴다. 둘 중 먼저 온 쪽만 가져가고(한 번만), 5분이 지나거나 이미 저장된 위젯이면 쓰지 않는다.
 */
@RunWith(RobolectricTestRunner::class)
class PendingRecipeTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var db: NotionCalDatabase
    private val recipe = RecipeConfig("""{"mode":"SECTIONS"}""", """{"v1":{"dateToday":true}}""")
    private val t0 = 1_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NotionCalDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun TestScope.claims(): RecipeClaims {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "settings.preferences_pb") }
        return RecipeClaims(WidgetConfigRepository(db.widgetConfigDao()), SettingsRepository(store, WidgetRefresher {}))
    }

    // --- 순수 부분

    @Test
    fun aFreshPendingRecipeBecomesTheNewWidgetsConfig() {
        val pending = PendingRecipe(recipe.layoutJson, recipe.formJson, t0)
        assertEquals(WidgetConfig(7, layoutJson = recipe.layoutJson, formJson = recipe.formJson), PendingRecipes.configFor(7, pending, t0 + 1_000))
        assertNotNull(PendingRecipes.configFor(7, pending, t0 + PendingRecipes.TTL_MS)) // 5분 되는 때까지는 쓴다
    }

    @Test
    fun anExpiredOrFutureOrMissingPendingRecipeGivesNoConfig() {
        val pending = PendingRecipe(recipe.layoutJson, recipe.formJson, t0)
        assertNull(PendingRecipes.configFor(7, pending, t0 + PendingRecipes.TTL_MS + 1))
        assertNull(PendingRecipes.configFor(7, pending, t0 - 1)) // 시계가 거꾸로 갔다
        assertNull(PendingRecipes.configFor(7, null, t0))
    }

    @Test
    fun pendingRecipesRoundTripAndGarbageReadsAsNone() {
        val pending = PendingRecipe(recipe.layoutJson, recipe.formJson, t0)
        assertEquals(pending, PendingRecipes.decode(PendingRecipes.encode(pending)))
        listOf(null, "", "  ", "not json", "[]", """{"layoutJson":3}""").forEach { assertNull(it, PendingRecipes.decode(it)) }
    }

    // --- 저장소와 함께

    @Test
    fun theCallbackWritesThePendingRecipeToANewWidgetOnceAndOnlyOnce() = runTest {
        val claims = claims()
        claims.request(recipe, t0)
        val applied = claims.applyToNew(7, t0 + 5_000)!!
        assertEquals(recipe.layoutJson, applied.layoutJson)
        assertEquals(recipe.formJson, db.widgetConfigDao().get(7)!!.formJson) // 저장됐다
        assertNull(claims.applyToNew(8, t0 + 6_000)) // 한 번 가져가면 다른 위젯에는 안 붙는다
        assertNull(db.widgetConfigDao().get(8))
    }

    @Test
    fun theCallbackLeavesAWidgetThatAlreadyHasSavedSettingsAlone() = runTest {
        val claims = claims()
        db.widgetConfigDao().upsert(com.ysdoing.notioncal.core.data.db.WidgetConfigEntity(7, layoutJson = "mine"))
        claims.request(recipe, t0)
        assertNull(claims.applyToNew(7, t0 + 1_000))
        assertEquals("mine", db.widgetConfigDao().get(7)!!.layoutJson) // 설정 화면에서 먼저 저장했으면 덮지 않는다
        assertNull(claims.startFor(8, t0 + 2_000)) // 그래도 대기는 이미 소모돼(위젯 7의 것) 다른 위젯에 붙지 않는다
    }

    @Test
    fun theConfigScreenSeesThePendingRecipeWithoutTakingItSoARotationStillStartsFromIt() = runTest {
        val claims = claims()
        claims.request(recipe, t0)
        val start = claims.startFor(7, t0 + 2_000)!!
        assertEquals(recipe.formJson, start.formJson)
        assertNull(db.widgetConfigDao().get(7)) // 저장은 사용자가 '저장'을 누를 때
        assertEquals(start, claims.startFor(7, t0 + 2_500)) // 화면이 다시 만들어져도(회전·글꼴 변경) 같은 구성으로 시작한다
    }

    @Test
    fun savingTheConfigScreenConsumesThePendingRecipeSoNothingElseGetsIt() = runTest {
        val claims = claims()
        claims.request(recipe, t0)
        claims.startFor(7, t0 + 1_000)
        db.widgetConfigDao().upsert(com.ysdoing.notioncal.core.data.db.WidgetConfigEntity(7, layoutJson = "saved")) // 화면이 '저장'을 눌렀다
        claims.consume()
        assertNull(claims.applyToNew(7, t0 + 2_000)) // 콜백이 뒤늦게 와도 저장된 설정을 덮지 않는다
        assertNull(claims.startFor(8, t0 + 3_000)) // 다른 새 위젯에도 붙지 않는다
        assertEquals("saved", db.widgetConfigDao().get(7)!!.layoutJson)
    }

    @Test
    fun theCallbackArrivingWhileTheConfigScreenIsOpenWritesTheRecipeForTheScreenToSave() = runTest {
        val claims = claims()
        claims.request(recipe, t0)
        val start = claims.startFor(7, t0 + 1_000)!!
        val applied = claims.applyToNew(7, t0 + 2_000)!!
        assertEquals(start, applied) // 화면이 본 구성과 같다
        assertEquals(recipe.formJson, db.widgetConfigDao().get(7)!!.formJson)
    }

    @Test
    fun theConfigScreenOfAnAlreadySavedWidgetDoesNotConsumeThePendingRecipe() = runTest {
        val claims = claims()
        db.widgetConfigDao().upsert(com.ysdoing.notioncal.core.data.db.WidgetConfigEntity(7))
        claims.request(recipe, t0)
        assertNull(claims.startFor(7, t0 + 1_000)) // 다시 설정하는 위젯은 레시피를 가져가지 않는다
        assertNotNull(claims.applyToNew(9, t0 + 2_000)) // 그래서 새로 놓인 위젯이 가져갈 수 있다
    }

    @Test
    fun anExpiredPendingRecipeIsDroppedWithoutBeingApplied() = runTest {
        val claims = claims()
        claims.request(recipe, t0)
        assertNull(claims.applyToNew(7, t0 + PendingRecipes.TTL_MS + 1))
        assertNull(db.widgetConfigDao().get(7))
        assertNull(claims.startFor(8, t0 + PendingRecipes.TTL_MS + 2)) // 지워졌다
    }

    @Test
    fun aNewRequestReplacesTheOlderPendingRecipe() = runTest {
        val claims = claims()
        claims.request(recipe, t0)
        claims.request(RecipeConfig("second", ""), t0 + 10_000)
        assertEquals("second", claims.applyToNew(7, t0 + 11_000)!!.layoutJson)
    }
}
