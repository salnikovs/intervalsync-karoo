package icu.intervalsync.karoo.extension

import icu.intervalsync.karoo.core.RideSnapshot
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Numeric data field showing one value of the engine's [RideSnapshot].
 */
class SnapshotDataType(
    extension: String,
    typeId: String,
    private val engine: RideEngine,
    private val value: (RideSnapshot) -> Double?,
) : DataTypeImpl(extension, typeId) {
    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            engine.snapshots
                .map { snapshot -> snapshot?.let(value) }
                .distinctUntilChanged()
                .collect {
                    emitter.onNext(
                        if (it != null) {
                            StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to it)))
                        } else {
                            StreamState.NotAvailable
                        },
                    )
                }
        }
        emitter.setCancellable {
            job.cancel()
        }
    }
}
