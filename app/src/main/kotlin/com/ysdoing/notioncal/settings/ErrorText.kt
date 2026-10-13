package com.ysdoing.notioncal.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ysdoing.notioncal.R
import com.ysdoing.notioncal.core.notion.NotionError

/** NS-01: 실패 원인을 사용자가 고칠 수 있는 말로 보여준다. 토큰 값은 절대 표시하지 않는다. */
@Composable
fun NotionError.text(): String = when (this) {
    NotionError.Unauthorized -> stringResource(R.string.error_unauthorized)
    NotionError.NotShared -> stringResource(R.string.error_not_shared)
    NotionError.Network -> stringResource(R.string.error_network)
    NotionError.RateLimited -> stringResource(R.string.error_rate_limited)
    is NotionError.Validation -> stringResource(R.string.error_validation, code ?: "400")
    is NotionError.Server -> stringResource(R.string.error_server)
}
