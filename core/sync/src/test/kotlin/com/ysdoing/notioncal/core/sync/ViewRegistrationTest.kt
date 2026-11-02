package com.ysdoing.notioncal.core.sync

import com.ysdoing.notioncal.core.notion.NotionError
import com.ysdoing.notioncal.core.notion.NotionException
import com.ysdoing.notioncal.core.notion.ViewReader
import com.ysdoing.notioncal.core.notion.dto.PageDto
import com.ysdoing.notioncal.core.notion.dto.ViewDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** VW-04·05 보기 목록: 설정을 읽지 못하는 보기(400)만 '지원하지 않음'이고, 연결·속도 제한 같은 오류는 목록 전체의 실패다. */
class ViewRegistrationTest {
    private fun reader(onView: (String) -> ViewDto) = object : ViewReader {
        override suspend fun viewIds(dataSourceId: String) = emptyList<String>()
        override suspend fun view(viewId: String): ViewDto = onView(viewId)
        override suspend fun rows(dataSourceId: String, view: ViewDto, limit: Int) = emptyList<PageDto>()
    }

    @Test
    fun readableViewKeepsItsNameTypeAndRegisteredFlag() = runTest {
        val r = reader { ViewDto(id = it, name = "할 일", type = "board") }
        val option = readViewOption(r, "v1", registered = setOf("v1"))
        assertEquals(ViewOption("v1", "할 일", "board", registered = true), option)
        assertEquals(false, readViewOption(r, "v2", registered = setOf("v1")).registered)
    }

    @Test
    fun unreadableViewIsListedAsUnsupportedNotAsFailure() = runTest {
        // 9-A 실측: 일부 보기는 `GET /views/{id}`가 400이다. 종류를 알 수 없어 '지원하지 않는 보기'로 보인다.
        val r = reader { throw NotionException(NotionError.Validation(null)) }
        val option = readViewOption(r, "v1", registered = emptySet())
        assertEquals(null, option.type)
        assertEquals(false, option.supported)
    }

    @Test
    fun connectionAndRateLimitErrorsFailTheWholeList() = runTest {
        // 일부 보기만 '이름 없음·지원하지 않음'으로 보이면 사용자가 보기가 지원되지 않는다고 오해하고 다시 시도하지 못한다.
        val errors = listOf(NotionError.Network, NotionError.RateLimited, NotionError.Unauthorized, NotionError.NotShared, NotionError.Server(503))
        errors.forEach { error ->
            val r = reader { throw NotionException(error) }
            try {
                readViewOption(r, "v1", registered = emptySet())
                fail("$error 는 목록 전체를 실패로 알려야 한다")
            } catch (e: NotionException) {
                assertTrue(e.error == error)
            }
        }
    }
}
