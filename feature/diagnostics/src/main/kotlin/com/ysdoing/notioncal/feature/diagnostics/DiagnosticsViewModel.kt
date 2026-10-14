package com.ysdoing.notioncal.feature.diagnostics

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.AppFailure
import com.ysdoing.notioncal.core.data.AppHealth
import com.ysdoing.notioncal.core.data.DiagnosticsRepository
import com.ysdoing.notioncal.core.data.IoDispatcher
import com.ysdoing.notioncal.core.data.Redactor
import com.ysdoing.notioncal.core.data.ReportSection
import com.ysdoing.notioncal.core.data.SyncDiagnostics
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** 진단 화면 알림. */
enum class DiagnosticsNotice { CACHE_CLEARED, RESET_ON_RESTART, EXPORT_FAILED, DEBUG_DONE }

data class DiagnosticsUiState(
    /** DB를 열 수 없으면 null로 남는다(그래도 화면은 그린다, PF-10). */
    val sync: SyncDiagnostics? = null,
    val power: PowerStatus? = null,
    val exits: List<ExitRecord> = emptyList(),
    val failure: AppFailure? = null,
    val busy: Boolean = false,
    val notice: DiagnosticsNotice? = null,
    /** [DiagnosticsNotice.DEBUG_DONE]에 붙는 항목 이름. */
    val noticeArg: String? = null,
)

private data class LocalState(
    val power: PowerStatus? = null,
    val exits: List<ExitRecord> = emptyList(),
    val busy: Boolean = false,
    val notice: DiagnosticsNotice? = null,
    val noticeArg: String? = null,
)

/** S25-03·04·09, PF-04 로그 내보내기, PF-10 진단·복구 도구. */
@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DiagnosticsRepository,
    private val health: AppHealth,
    debugMenu: DebugMenu,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val reader = SystemStatusReader(context)
    private val local = MutableStateFlow(LocalState())

    private val sync: Flow<SyncDiagnostics?> = repository.sync
        .map<SyncDiagnostics, SyncDiagnostics?> { it }
        .catch { emit(null) } // DB를 열지 못해도 진단 화면은 뜬다

    val state: StateFlow<DiagnosticsUiState> = combine(sync, health.failure, local) { s, failure, l ->
        DiagnosticsUiState(s, l.power, l.exits, failure, l.busy, l.notice, l.noticeArg)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticsUiState(failure = health.failure.value))

    val debugActions: List<DebugAction> = debugMenu.actions()

    private val _share = Channel<Uri>(Channel.BUFFERED)

    /** 내보낸 파일. 화면이 공유 시트를 연다. */
    val share: Flow<Uri> = _share.receiveAsFlow()

    /** 화면이 다시 보일 때마다(설정 화면에서 돌아올 때) 다시 읽는다. */
    fun refreshSystem() {
        viewModelScope.launch {
            val power = withContext(io) { runCatching { reader.power() }.getOrNull() }
            val exits = withContext(io) { runCatching { reader.recentExits() }.getOrDefault(emptyList()) }
            local.update { it.copy(power = power, exits = exits) }
        }
    }

    fun clearCache() = busy {
        val cleared = runCatching { repository.clearCache() }.isSuccess
        if (!cleared) health.requestDatabaseReset() // DB 자체가 안 열리면 다음 실행에 새로 만든다
        notify(if (cleared) DiagnosticsNotice.CACHE_CLEARED else DiagnosticsNotice.RESET_ON_RESTART)
    }

    fun export() = busy {
        val uri = runCatching { withContext(io) { writeReport() } }.getOrNull()
        if (uri == null) notify(DiagnosticsNotice.EXPORT_FAILED) else _share.send(uri)
    }

    fun runDebug(action: DebugAction) = busy {
        action.run()
        notify(DiagnosticsNotice.DEBUG_DONE, action.label)
    }

    fun dismissNotice() = local.update { it.copy(notice = null, noticeArg = null) }

    /** 실패 안내를 닫고 정상 화면으로. 실행 표지도 지운다. */
    fun acknowledgeFailure(then: () -> Unit) {
        viewModelScope.launch {
            health.clear()
            then()
        }
    }

    private fun busy(block: suspend () -> Unit) {
        viewModelScope.launch {
            local.update { it.copy(busy = true) }
            try {
                block()
            } finally {
                local.update { it.copy(busy = false) }
            }
        }
    }

    private fun notify(notice: DiagnosticsNotice, arg: String? = null) =
        local.update { it.copy(notice = notice, noticeArg = arg) }

    private suspend fun writeReport(): Uri {
        val failure = health.failure.value
        val header = listOfNotNull(
            "앱 버전 ${reader.versionName()} (${context.packageName})",
            "기기 ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            failure?.let { "초기화 실패: ${it.kind}${if (it.safeMode) " (안전 모드)" else ""}" },
        )
        val power = runCatching { reader.power() }.getOrNull()
        val exits = runCatching { reader.recentExits() }.getOrDefault(emptyList())
        val sections = listOf(
            ReportSection(
                "절전",
                listOfNotNull(
                    power?.let { "배터리 최적화 예외: ${if (it.ignoringOptimizations) "예" else "아니오"}" },
                    power?.let { "대기 등급: ${context.getString(standbyBucketLabel(it.standbyBucket))} (${it.standbyBucket})" },
                ),
            ),
            ReportSection(
                "최근 종료 사유",
                exits.map { e ->
                    val label = context.getString(if (e.memoryLimit) R.string.diag_exit_memory_limit else exitReasonLabel(e.reason))
                    "${TIME.format(Instant.ofEpochMilli(e.at).atZone(APP_ZONE))} $label ${e.description.orEmpty()}"
                },
            ),
        )
        // DB를 못 열면 화면 쪽 정보만이라도 내보낸다.
        val text = runCatching { repository.report(header, sections) }.getOrElse {
            Redactor.scrubTokens(
                (header + "DB를 열 수 없음: ${it.javaClass.simpleName}" + sections.flatMap { s -> listOf("", "## ${s.title}") + s.lines })
                    .joinToString("\n"),
            )
        }
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // 이전 내보내기는 남기지 않는다
        val file = File(dir, "notioncal-diagnostics-${FILE_TIME.format(Instant.now().atZone(APP_ZONE))}.txt")
        file.writeText(text)
        return FileProvider.getUriForFile(context, "${context.packageName}.diagnostics.files", file)
    }

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val FILE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
