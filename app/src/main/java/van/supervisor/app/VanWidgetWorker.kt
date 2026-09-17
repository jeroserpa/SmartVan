package van.supervisor.app

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** One widget refresh: read van-core if reachable, store, redraw. */
class VanWidgetWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val states = VanFeed.fetch(applicationContext)
        if (states != null) {
            VanStore.saveOk(applicationContext, states)
        } else {
            VanStore.saveMiss(applicationContext)
        }
        // renderFilling, not renderAll: this is the one place with a thread of
        // its own to run the liquid up to the new charge on. It falls back to a
        // single frame when nothing moved.
        VanWidget.renderFilling(applicationContext)
        // Always success: being out of range is the normal state, and a retry
        // with backoff would only spend phone battery on it.
        return Result.success()
    }
}
