package com.ysdoing.notioncal.core.sync

import com.ysdoing.notioncal.core.data.IoDispatcher
import com.ysdoing.notioncal.core.data.SecretStore
import com.ysdoing.notioncal.core.data.SourceKind
import com.ysdoing.notioncal.core.data.sourceKind
import com.ysdoing.notioncal.core.data.SyncTrigger
import com.ysdoing.notioncal.core.data.db.DataSourceDao
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import com.ysdoing.notioncal.core.notion.NotionClient
import com.ysdoing.notioncal.core.notion.NotionError
import com.ysdoing.notioncal.core.notion.NotionException
import com.ysdoing.notioncal.core.notion.dto.DataSourceDto
import com.ysdoing.notioncal.core.notion.mapping.NotionLinkParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 연결 테스트 결과(NS-01). */
sealed interface ConnectionResult {
    data class Connected(val botName: String?) : ConnectionResult
    data class Failed(val error: NotionError) : ConnectionResult
}

/** DB 링크를 조회한 결과. 여러 data source를 가진 DB면 [dataSources]에서 고른다. */
data class DatabaseInspection(
    val databaseId: String,
    val dataSources: List<Pair<String, String>>,
    val dataSourceId: String,
    val name: String,
    val schema: DataSourceDto,
    val candidates: MappingCandidates,
    val suggestion: MappingChoice?,
    /** 이미 등록한 DB면 그 색(다시 연결 때 색을 유지, NS-09). */
    val existingColorHex: String? = null,
    /** 이미 등록한 DB의 종류. ✅ 습관 기록이면 등록 화면이 '습관 기록'으로 연다. */
    val existingKind: SourceKind? = null,
    /** HB-01 '습관 기록'으로 쓸 때의 추천 매핑(제목·날짜·relation이 없으면 null). */
    val logSuggestion: HabitLogChoice? = null,
    /** 날짜 없이 relation만 있다: 🌱 습관 링크를 넣은 것으로 보고 ✅ 습관 기록 링크를 안내한다. */
    val looksLikeHabitDatabase: Boolean = false,
)

sealed interface InspectResult {
    data class Ok(val inspection: DatabaseInspection) : InspectResult
    data object InvalidLink : InspectResult
    data class Failed(val error: NotionError) : InspectResult
}

/** 설정 화면의 토큰 저장·연결 테스트·DB 등록(NS-01, NS-02). */
@Singleton
class ConnectionService @Inject constructor(
    private val secretStore: SecretStore,
    private val client: NotionClient,
    private val dataSourceDao: DataSourceDao,
    private val scheduler: SyncScheduler,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    suspend fun hasToken(): Boolean = withContext(io) { !secretStore.token().isNullOrBlank() }

    /** 토큰을 암호화 저장한 뒤 `users/me`로 확인한다. 실패해도 저장은 유지하고 원인을 돌려준다. */
    suspend fun saveAndTest(token: String): ConnectionResult = withContext(io) {
        secretStore.saveToken(token)
        test()
    }

    suspend fun test(): ConnectionResult = withContext(io) {
        try {
            ConnectionResult.Connected(client.me().name)
        } catch (e: NotionException) {
            ConnectionResult.Failed(e.error)
        }
    }

    suspend fun inspect(link: String, preferredDataSourceId: String? = null): InspectResult = withContext(io) {
        val parsedId = NotionLinkParser.databaseId(link) ?: return@withContext InspectResult.InvalidLink
        // 🌱 습관 다시 연결(NS-09): 짝인 ✅ 습관 기록으로 연다(두 DB를 한 화면에서 함께 맞춘다).
        val pairedLog = preferredDataSourceId?.let { dataSourceDao.get(it) }?.takeIf { it.sourceKind == SourceKind.HABIT }
            ?.let { dataSourceDao.linkedTo(it.dataSourceId).firstOrNull() }
        val databaseId = pairedLog?.databaseId ?: parsedId
        val preferred = pairedLog?.dataSourceId ?: preferredDataSourceId
        try {
            val database = client.database(databaseId)
            val refs = database.dataSources.map { it.id to it.name }
            val dataSourceId = preferred?.takeIf { id -> refs.any { it.first == id } }
                ?: refs.firstOrNull()?.first
                ?: return@withContext InspectResult.Failed(NotionError.NotShared)
            val schema = client.dataSource(dataSourceId)
            val name = database.title.joinToString("") { it.plainText }.ifBlank {
                schema.title.joinToString("") { it.plainText }
            }
            val existing = dataSourceDao.get(dataSourceId)
            val suggested = MappingSuggester.suggest(schema)
            InspectResult.Ok(
                DatabaseInspection(
                    databaseId = database.id,
                    dataSources = refs,
                    dataSourceId = dataSourceId,
                    name = name,
                    schema = schema,
                    candidates = MappingSuggester.candidates(schema),
                    suggestion = existing?.takeIf { it.sourceKind == SourceKind.CALENDAR }?.let { keepValidMapping(it, schema, suggested) } ?: suggested,
                    existingColorHex = existing?.colorHex,
                    existingKind = existing?.sourceKind,
                    logSuggestion = existing?.takeIf { it.sourceKind == SourceKind.HABIT_LOG }?.let { keepValidLog(it, schema) }
                        ?: HabitSuggester.suggestLog(schema),
                    looksLikeHabitDatabase = HabitSuggester.looksLikeHabitDatabase(schema),
                ),
            )
        } catch (e: NotionException) {
            InspectResult.Failed(e.error)
        }
    }

    /** 매핑을 저장하고 해당 DB의 첫 범위 동기화를 요청한다. 같은 data source를 다시 등록하면 매핑만 바꾼다. */
    suspend fun register(inspection: DatabaseInspection, choice: MappingChoice, colorHex: String) = withContext(io) {
        val schemaById = inspection.schema.properties.values.associateBy { it.id }
        val doneProp = choice.donePropId?.let(schemaById::get)
        val (doneOption, undoneOption) = doneProp?.let(MappingSuggester::statusOptions) ?: (null to null)
        val existing = dataSourceDao.get(inspection.dataSourceId)
        val mappingChanged = existing != null &&
            (existing.titlePropId != choice.titlePropId || existing.datePropId != choice.datePropId || existing.donePropId != choice.donePropId ||
                existing.colorPropId != choice.colorPropId || existing.sortPropId != choice.sortPropId)
        dataSourceDao.upsert(
            DataSourceEntity(
                dataSourceId = inspection.dataSourceId,
                databaseId = inspection.databaseId,
                name = inspection.name,
                colorHex = colorHex,
                titlePropId = choice.titlePropId,
                datePropId = choice.datePropId,
                donePropId = choice.donePropId,
                doneType = MappingSuggester.doneType(doneProp)?.name,
                doneOption = doneOption,
                undoneOption = undoneOption,
                colorPropId = choice.colorPropId,
                sortPropId = choice.sortPropId,
                enabled = existing?.enabled ?: true,
                // 매핑이 바뀌면 처음부터 다시 받는다.
                lastSyncAt = existing?.lastSyncAt?.takeUnless { mappingChanged },
                lastFullSyncAt = existing?.lastFullSyncAt?.takeUnless { mappingChanged },
                position = existing?.position ?: dataSourceDao.nextPosition(),
                colorOptions = existing?.colorOptions,
                propCatalog = existing?.propCatalog,
                extraPropIds = existing?.extraPropIds,
                useOptionColors = existing?.useOptionColors ?: true,
                // 다시 연결(NS-09)하면 알림을 지우고, 이름은 다음 동기화에서 새 매핑으로 다시 적는다.
                mappedPropNames = existing?.mappedPropNames?.takeUnless { mappingChanged },
                schemaIssues = null,
                emphasisPropId = existing?.emphasisPropId,
                emphasisOptionIds = existing?.emphasisOptionIds,
                kind = SourceKind.CALENDAR.name,
            ),
        )
        scheduler.request(SyncTrigger.APP_OPEN, inspection.dataSourceId)
    }

    /** HB-01: 기록 DB의 relation([linkPropId])이 가리키는 🌱 습관을 읽어 추천 매핑을 만든다. */
    suspend fun inspectHabits(log: DatabaseInspection, linkPropId: String): HabitInspectResult = withContext(io) {
        val relation = HabitSuggester.relationTarget(log.schema, linkPropId) ?: return@withContext HabitInspectResult.NoRelationTarget
        val habitSourceId = relation.dataSourceId ?: return@withContext HabitInspectResult.NoRelationTarget
        try {
            val schema = client.dataSource(habitSourceId)
            val databaseId = relation.databaseId
            val name = databaseId?.let { id -> runCatching { client.database(id) }.getOrNull()?.title?.joinToString("") { it.plainText } }
                ?.takeIf { it.isNotBlank() }
                ?: schema.title.joinToString("") { it.plainText }
            val existing = dataSourceDao.get(habitSourceId)?.takeIf { it.sourceKind == SourceKind.HABIT }
            val suggested = HabitSuggester.suggestHabit(schema)
            HabitInspectResult.Ok(
                HabitInspection(
                    target = HabitTarget(habitSourceId, databaseId ?: existing?.databaseId ?: habitSourceId, name),
                    candidates = HabitSuggester.candidates(schema),
                    suggestion = existing?.let { keepValidHabit(it, schema) } ?: suggested,
                ),
            )
        } catch (e: NotionException) {
            HabitInspectResult.Failed(e.error)
        }
    }

    /** HB-01: ✅ 습관 기록과 🌱 습관을 한 쌍으로 저장하고 두 DB 모두 동기화를 요청한다(습관을 먼저). */
    suspend fun registerHabit(log: DatabaseInspection, logChoice: HabitLogChoice, habit: HabitInspection, habitChoice: HabitChoice, colorHex: String) =
        withContext(io) {
            val (logRow, habitRow) = habitRows(
                log = HabitTarget(log.dataSourceId, log.databaseId, log.name),
                logChoice = logChoice,
                habit = habit.target,
                habitChoice = habitChoice,
                colorHex = colorHex,
                existingLog = dataSourceDao.get(log.dataSourceId),
                existingHabit = dataSourceDao.get(habit.target.dataSourceId),
                nextPosition = dataSourceDao.nextPosition(),
            )
            dataSourceDao.upsert(logRow)
            dataSourceDao.upsert(habitRow)
            scheduler.request(SyncTrigger.APP_OPEN, habitRow.dataSourceId)
            scheduler.request(SyncTrigger.APP_OPEN, logRow.dataSourceId)
        }
}

/** 다시 연결: 기록 DB의 지금 매핑 중 스키마에 남은 것만 쓰고, 하나라도 사라졌으면 추천값을 쓴다. */
internal fun keepValidLog(existing: DataSourceEntity, schema: DataSourceDto): HabitLogChoice? {
    val types = schema.properties.values.associate { it.id to it.type }
    val date = existing.datePropId?.takeIf { types[it] == "date" }
    val link = existing.linkPropId?.takeIf { types[it] == "relation" }
    val title = existing.titlePropId.takeIf { types[it] == "title" }
    return if (title != null && date != null && link != null) HabitLogChoice(title, date, link) else HabitSuggester.suggestLog(schema)
}

/** 다시 연결: 🌱 습관의 지금 매핑 중 스키마에 남은 것은 두고, 사라진 것만 추천값으로 채운다. */
internal fun keepValidHabit(existing: DataSourceEntity, schema: DataSourceDto): HabitChoice? {
    val suggested = HabitSuggester.suggestHabit(schema) ?: return null
    val types = schema.properties.values.associate { it.id to it.type }
    val ids = com.ysdoing.notioncal.core.data.HabitPropIds.parse(existing.habitPropIds)
    fun keep(id: String?, type: String, fallback: String?) = id?.takeIf { types[it] == type } ?: fallback
    return HabitChoice(
        titlePropId = keep(existing.titlePropId, "title", suggested.titlePropId)!!,
        emoji = keep(ids.emoji, "rich_text", suggested.emoji),
        weeklyTarget = keep(ids.weeklyTarget, "number", suggested.weeklyTarget),
        active = keep(ids.active, "checkbox", suggested.active),
        startDate = keep(ids.startDate, "date", suggested.startDate),
        sortOrder = keep(ids.sortOrder, "number", suggested.sortOrder),
    )
}

/**
 * 다시 연결(NS-09): 지금 매핑 중 스키마에 남아 있는 속성은 그대로 두고, 사라진 것만 추천값으로 채운다.
 * 제목·날짜를 채울 수 없으면 추천값 전체를 쓴다.
 */
internal fun keepValidMapping(existing: DataSourceEntity, schema: DataSourceDto, suggested: MappingChoice?): MappingChoice? {
    val ids = schema.properties.values.map { it.id }.toSet()
    fun keep(id: String?) = id?.takeIf { it in ids }
    val title = keep(existing.titlePropId) ?: suggested?.titlePropId ?: return suggested
    val date = keep(existing.datePropId) ?: suggested?.datePropId ?: return suggested
    return MappingChoice(
        titlePropId = title,
        datePropId = date,
        donePropId = keep(existing.donePropId),
        colorPropId = keep(existing.colorPropId),
        sortPropId = keep(existing.sortPropId),
    )
}
