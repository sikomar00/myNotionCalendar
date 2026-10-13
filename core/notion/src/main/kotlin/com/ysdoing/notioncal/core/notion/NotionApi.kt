package com.ysdoing.notioncal.core.notion

import com.ysdoing.notioncal.core.notion.dto.CreatePageDto
import com.ysdoing.notioncal.core.notion.dto.DataSourceDto
import com.ysdoing.notioncal.core.notion.dto.DatabaseDto
import com.ysdoing.notioncal.core.notion.dto.PageDto
import com.ysdoing.notioncal.core.notion.dto.QueryRequestDto
import com.ysdoing.notioncal.core.notion.dto.QueryResponseDto
import com.ysdoing.notioncal.core.notion.dto.UpdatePageDto
import com.ysdoing.notioncal.core.notion.dto.UserDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

/** PRD §8.2 엔드포인트. */
interface NotionApi {
    @GET("v1/users/me")
    suspend fun me(): UserDto

    @GET("v1/databases/{id}")
    suspend fun database(@Path("id") databaseId: String): DatabaseDto

    @GET("v1/data_sources/{id}")
    suspend fun dataSource(@Path("id") dataSourceId: String): DataSourceDto

    @GET("v1/pages/{id}")
    suspend fun page(@Path("id") pageId: String): PageDto

    @POST("v1/pages")
    suspend fun createPage(@Body body: CreatePageDto): PageDto

    @PATCH("v1/pages/{id}")
    suspend fun updatePage(@Path("id") pageId: String, @Body body: UpdatePageDto): PageDto

    @POST("v1/data_sources/{id}/query")
    suspend fun query(@Path("id") dataSourceId: String, @Body body: QueryRequestDto): QueryResponseDto
}
