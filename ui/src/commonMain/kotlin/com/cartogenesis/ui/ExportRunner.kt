package com.cartogenesis.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cartogenesis.cartography.ExportedWorld
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One export asked for: at what size, and the line the progress banner shows while it runs. */
internal class ExportRequest(val size: Int, val stage: String)

/**
 * The one export in flight, owned by the request that started it.
 *
 * Exports have state of their own, apart from generation's: a generation and an export can run
 * together, and neither's end is the other's business. A new request cancels the one in flight,
 * explicitly, and takes its place. A cancelled export is not a failed one and says nothing; and
 * only the request that is still [running] may report or clear anything, so an older request that
 * finishes, fails or unwinds after a newer one started can never write over the newer one's notice
 * or put the interface back to idle under it.
 */
internal class ExportRunner(private val scope: CoroutineScope) {

    /** The export under way, or null. Snapshot state, so what shows it redraws when it moves. */
    var running: ExportRequest? by mutableStateOf(null)
        private set

    private var job: Job? = null

    /**
     * Starts [request], cancelling any export in flight, and hands [onNotice] what [work] says it
     * did — or what went wrong — if [request] is still the running one when it is done.
     */
    fun start(request: ExportRequest, work: suspend () -> String, onNotice: (String) -> Unit) {
        job?.cancel()
        running = request
        job = scope.launch {
            try {
                val notice = work()
                if (running === request) onNotice(notice)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (running === request) onNotice("Export failed: ${failure::class.simpleName} ${failure.message.orEmpty()}")
            } finally {
                if (running === request) running = null
            }
        }
    }

    companion object {
        /** What a finished export says on the status line; null is the reader backing out. */
        fun notice(outcome: ExportOutcome?): String {
            if (outcome == null) return "Export cancelled"
            val saved = "Saved ${outcome.description} - ${outcome.bytes / 1024 / 1024} MB in ${outcome.millis / 1000}s"
            return when (outcome.source) {
                ExportedWorld.OnScreen -> saved
                is ExportedWorld.MadeAgain -> "$saved, made again at that size from this world's settings"
            }
        }
    }
}
