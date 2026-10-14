package icu.intervalsync.karoo.extension

import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Numeric data field showing one of today's caps from the assistant, so the rider can
 * keep it beside their power or heart rate. Not available on days without that cap.
 */
class TargetDataType(
    extension: String,
    typeId: String,
    private val value: () -> Int?,
) : DataTypeImpl(extension, typeId) {
    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            var last: Int? = -1
            while (isActive) {
                // The cap can arrive or change when the app syncs, so look again now and then
                val current = value()
                if (current != last) {
                    last = current
                    emitter.onNext(
                        if (current != null) {
                            StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to current.toDouble())))
                        } else {
                            StreamState.NotAvailable
                        },
                    )
                }
                delay(REFRESH_MS)
            }
        }
        emitter.setCancellable {
            job.cancel()
        }
    }

    companion object {
        private const val REFRESH_MS = 30_000L
    }
}
