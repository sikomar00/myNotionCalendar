package com.ysdoing.notioncal.core.sync

import com.ysdoing.notioncal.core.data.IoDispatcher
import com.ysdoing.notioncal.core.data.WidgetRefresher
import com.ysdoing.notioncal.core.data.db.ViewDao
import com.ysdoing.notioncal.core.data.db.ViewWriteEntity
import com.ysdoing.notioncal.core.data.view.ViewJson
import com.ysdoing.notioncal.core.notion.NotionError
import com.ysdoing.notioncal.core.notion.NotionException
import com.ysdoing.notioncal.core.notion.NotionReader
import com.ysdoing.notioncal.core.notion.NotionWriter
import com.ysdoing.notioncal.core.notion.dto.DataSourceDto
import com.ysdoing.notioncal.core.notion.mapping.encodeViewValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 한 번 처리한 결과. [retryError]가 있으면 일시 오류로 멈춘 것이라 나중에 다시 실행한다. */
data class ViewWriteOutcome(val sent: Int, val dropped: Int, val remaining: Int, val retryError: NotionError?)

/**
 * 위젯에서 바꾼 보기 행 속성(view_writes)을 노션으로 보낸다. 만든 순서대로 하나씩 보내고, 일시 오류(연결·5xx·속도 제한·인증)면
 * 큐 전체를 멈춰 다음 실행에서 이어 간다. 노션이 거부(400)하거나 페이지·속성이 없으면, 값이 깨졌으면, 너무 여러 번 실패하면
 * 그 바꿈을 버리고 그 보기를 다시 받아 위젯을 노션 값으로 되돌린다. 일정 아웃박스([OutboxProcessor])와 따로 도는 큐라
 * 서로의 순서·충돌 규칙에 영향이 없다.
 */
@Singleton
class ViewWriteProcessor @Inject constructor(
    private val reader: NotionReader,
    private val writer: NotionWriter,
    private val dao: ViewDao,
    private val views: ViewSync,
    private val widgets: WidgetRefresher,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val mutex = Mutex()

    private class Drain(val outcome: ViewWriteOutcome, val rejectedViews: Set<String>)

    suspend fun process(): ViewWriteOutcome = withContext(io) {
        val drain = mutex.withLock { drain() }
        // 버린 바꿈의 보기는 큐를 다 비운 뒤, 잠금 밖에서 다시 받는다. 연결 문제로 멈췄다면 지금 받아도 실패하니 다음 동기화가 한다.
        if (drain.outcome.retryError == null) drain.rejectedViews.forEach { views.sync(it) }
        drain.outcome
    }

    private suspend fun drain(): Drain {
        val schemas = HashMap<String, DataSourceDto>()
        val rejected = LinkedHashSet<String>()
        var sent = 0
        var dropped = 0
        var retryError: NotionError? = null
        suspend fun drop(write: ViewWriteEntity) {
            dao.deleteWriteById(write.id)
            rejected += write.viewId
            dropped++
        }
        while (true) {
            val write = dao.firstWrite() ?: break
            if (write.attempts >= MAX_ATTEMPTS) {
                drop(write) // 끝없이 실패하는 바꿈 하나가 그 보기의 새로 받기를 영원히 막지 않게 한다
                continue
            }
            try {
                val schema = schemas.getOrPut(write.dataSourceId) { reader.dataSource(write.dataSourceId) }
                // 키는 보내는 순간의 속성 이름이다. 속성이 지워졌다면 ID를 키로 보내도 노션이 오류 없이 무시하므로 보내지 않고 되돌린다.
                val key = schema.properties.values.firstOrNull { it.id == write.propId }?.name
                val value = ViewJson.parseValue(write.value)
                val properties = if (key != null && value != null) encodeViewValue(key, write.propType, value) else null
                if (properties == null) {
                    drop(write)
                    continue
                }
                writer.updatePage(write.pageId, properties)
                dao.deleteWriteById(write.id)
                sent++
            } catch (e: NotionException) {
                if (e.error is NotionError.Validation || e.error == NotionError.NotShared) {
                    drop(write)
                } else {
                    dao.markWriteFailed(write.id, e.error.storedName())
                    retryError = e.error
                    break
                }
            }
        }
        if (sent + dropped > 0) widgets.requestUpdate()
        return Drain(ViewWriteOutcome(sent, dropped, dao.pendingWriteCount(), retryError), rejected)
    }

    private companion object {
        /** 이만큼 실패하면 버린다(15분 주기로 약 2시간 반). */
        const val MAX_ATTEMPTS = 10
    }
}
