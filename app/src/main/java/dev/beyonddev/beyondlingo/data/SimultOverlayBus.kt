package dev.beyonddev.beyondlingo.data

import dev.beyonddev.beyondlingo.vm.SubtitleLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 同传字幕广播总线。
 *
 * ViewModel 是字幕的唯一数据源；悬浮窗服务订阅这里的数据渲染。
 * 用单例进程内共享，避免 Service 与 ViewModel 直接耦合（不 bind、不传 Binder）。
 *
 * 只保留最近 [MAX_LINES] 条，避免悬浮窗无限增长。
 */
object SimultOverlayBus {

    private const val MAX_LINES = 6

    data class Snapshot(
        val partial: String = "",
        val lines: List<SubtitleLine> = emptyList(),
        val running: Boolean = false,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    fun publish(partial: String, lines: List<SubtitleLine>, running: Boolean) {
        _state.update {
            Snapshot(
                partial = partial,
                lines = lines.takeLast(MAX_LINES),
                running = running,
            )
        }
    }

    fun clear() {
        _state.update { Snapshot() }
    }
}
