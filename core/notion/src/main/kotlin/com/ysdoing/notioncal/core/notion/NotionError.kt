package com.ysdoing.notioncal.core.notion

/** 화면에 원인을 보여주기 위한 노션 호출 실패 분류(NS-01). 메시지에 토큰·일정 제목을 넣지 않는다. */
sealed interface NotionError {
    /** 401: 토큰이 없거나 잘못됐거나 연결이 삭제됨. */
    data object Unauthorized : NotionError

    /** 403·404: 페이지·DB에 연결을 추가(공유)하지 않았거나 없는 ID. */
    data object NotShared : NotionError

    /** 400: 요청 형식 오류. 재시도하지 않는다. */
    data class Validation(val code: String?) : NotionError

    /** 429·529: 재시도 후에도 속도 제한. */
    data object RateLimited : NotionError

    /** 연결 실패·시간 초과. */
    data object Network : NotionError

    /** 5xx 또는 응답 해석 실패. */
    data class Server(val status: Int?) : NotionError
}

class NotionException(val error: NotionError, cause: Throwable? = null) :
    Exception(error.toString(), cause)
