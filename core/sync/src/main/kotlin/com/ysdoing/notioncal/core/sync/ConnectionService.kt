package com.ysdoing.notioncal.core.sync

import com.ysdoing.notioncal.core.data.IoDispatcher
import com.ysdoing.notioncal.core.data.SecretStore
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
        val databaseId = NotionLinkParser.databaseId(link) ?: return@withContext InspectResult.InvalidLink
        try {
            val database = client.database(databaseId)
            val refs = database.dataSources.map { it.id to it.name }
            val dataSourceId = preferredDataSourceId?.takeIf { id -> refs.any { it.first == id } }
                ?: refs.firstOrNull()?.first
                ?: return@withContext InspectResult.Failed(NotionError.NotShared)
            val schema = client.dataSource(dataSourceId)
            val name = database.title.joinToString("") { it.plainText }.ifBlank {
                schema.title.joinToString("") { it.plainText }
            }
            InspectResult.Ok(
                DatabaseInspection(
                    databaseId = database.id,
                    dataSources = refs,
                    dataSourceId = dataSourceId,
                    name = name,
                    schema = schema,
                    candidates = MappingSuggester.candidates(schema),
                    suggestion = MappingSuggester.suggest(schema),
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
            MappingChoice(existing.titlePropId, existing.datePropId, existing.donePropId, existing.colorPropId, existing.sortPropId) != choice
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
            ),
        )
        scheduler.request(SyncTrigger.APP_OPEN, inspection.dataSourceId)
    }
}
