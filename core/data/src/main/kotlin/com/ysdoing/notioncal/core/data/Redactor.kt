package com.ysdoing.notioncal.core.data

import java.security.MessageDigest

/**
 * SEC-02: 밖으로 나가는 글(로그 내보내기, 크래시 리포트)에서 토큰과 일정 제목을 지운다.
 * 토큰 형식은 노션 통합 토큰(`ntn_`, 옛 `secret_`)과 Authorization 헤더 값이다.
 */
object Redactor {
    const val TOKEN_MASK = "<토큰>"
    const val TEXT_MASK = "<내용>"

    private val tokenPatterns = listOf(
        Regex("""\b(?:ntn|secret)_[A-Za-z0-9]{8,}"""),
        Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/=-]+"""),
    )

    /** 따옴표 안 문자열. 직렬화 오류 메시지는 노션 응답 JSON(일정 제목 포함)을 그대로 담는다. */
    private val quoted = Regex(""""(?:[^"\\]|\\.)*"""")

    /** kotlinx.serialization 오류의 "JSON input: …" 꼬리. */
    private val jsonInput = Regex("""(?s)JSON input:.*""")

    fun scrubTokens(text: String): String =
        tokenPatterns.fold(text) { acc, pattern -> pattern.replace(acc, TOKEN_MASK) }

    /**
     * 크래시 리포트용. 제목 목록을 알 수 없으므로 토큰을 지우고, 응답 본문이 섞일 수 있는 따옴표 문자열과
     * JSON 꼬리를 통째로 가린다. 예외 종류·스택 위치는 남는다.
     */
    fun scrubReport(text: String): String {
        val noTokens = scrubTokens(text)
        val noJson = jsonInput.replace(noTokens, "JSON input: $TEXT_MASK")
        return quoted.replace(noJson, "\"$TEXT_MASK\"")
    }

    /** 제목을 알고 있을 때(내보내기). 같은 제목은 같은 표지로 바뀌어 항목끼리 구분은 된다. */
    fun maskTitle(title: String): String = "일정#" + shortHash(title)

    private fun shortHash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.take(3).joinToString("") { "%02x".format(it) }
    }
}
