package dev.beyonddev.beyondlingo.util

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope

/**
 * 可取消的延迟执行器，用于输入防抖。
 *
 * 参照 STranslate 的 `DebounceExecutor`：每次调用取消上一个任务，
 * 只有静默满 [schedule] 指定的时间才真正执行。
 */
class Debouncer(private val scope: CoroutineScope) {

    private var job: Job? = null

    fun schedule(delayMs: Long, action: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch {
            delay(delayMs)
            action()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}
