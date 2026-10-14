package icu.intervalsync.karoo.extension

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import icu.intervalsync.karoo.R
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * W′ balance as a percentage with a bar that empties and changes colour as the reserve drains.
 *
 * Karoo renders the number from the stream; the view adds the bar underneath it.
 */
class WPrimeGraphicDataType(
    extension: String,
    private val engine: RideEngine,
) : SnapshotDataType(extension, "wbal-graphic", engine, { s -> s.wPrimeFraction?.let { (it * 100).roundToInt().toDouble() } }) {
    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            emitter.onNext(UpdateGraphicConfig(formatDataTypeId = DataType.Type.PERCENT_MAX_HR))
            engine.snapshots
                .map { snapshot -> snapshot?.wPrimeFraction?.let { (it * 100).roundToInt() } }
                .distinctUntilChanged()
                .conflate()
                .collect { percent ->
                    // The page editor has no ride to show, so preview a part-used bar
                    emitter.updateView(barView(context, percent ?: if (config.preview) PREVIEW_PERCENT else null))
                    // Karoo drops view updates sent less than a second apart
                    delay(1000)
                }
        }
        emitter.setCancellable {
            job.cancel()
        }
    }

    private fun barView(context: Context, percent: Int?): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.view_wbal)
        val shown = when {
            percent == null || percent >= GOOD_FROM -> R.id.wbal_bar_good
            percent >= WARN_FROM -> R.id.wbal_bar_warn
            else -> R.id.wbal_bar_low
        }
        for (bar in listOf(R.id.wbal_bar_good, R.id.wbal_bar_warn, R.id.wbal_bar_low)) {
            views.setViewVisibility(bar, if (bar == shown) View.VISIBLE else View.GONE)
        }
        views.setProgressBar(shown, 100, percent ?: 0, false)
        return views
    }

    companion object {
        private const val GOOD_FROM = 50
        private const val WARN_FROM = 25
        private const val PREVIEW_PERCENT = 65
    }
}
