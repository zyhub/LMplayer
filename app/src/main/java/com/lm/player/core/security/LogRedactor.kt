package com.lm.player.core.security

/** 日志/崩溃报告脱敏：避免 Token、签名 URL、Authorization 进入 logcat 或剪贴板。 */
object LogRedactor {
    private val querySecret = Regex(
        "([?&](?:token|access_token|auth|authorization|key|api_key|sign|signature|expires|expire|ticket|Bearer)=)[^&\\s]+",
        RegexOption.IGNORE_CASE
    )
    private val bearer = Regex("(Bearer\\s+)[A-Za-z0-9._\\-+/=]+", RegexOption.IGNORE_CASE)
    private val authHeader = Regex("(Authorization\\s*:\\s*)[^\\r\\n]+", RegexOption.IGNORE_CASE)

    fun text(value: String): String = value
        .replace(querySecret, "$1***")
        .replace(authHeader, "$1***")
        .replace(bearer, "$1***")

    fun url(value: String): String = text(value)
}
