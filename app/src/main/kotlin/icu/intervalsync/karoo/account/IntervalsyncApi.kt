package icu.intervalsync.karoo.account

import icu.intervalsync.karoo.BuildConfig
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.IOException

/**
 * JSON calls to intervalsync.icu (see docs/device-api.md), sent through Karoo's own
 * HTTP channel so they work over wifi or the companion app's connection.
 */
class IntervalsyncApi(
    private val karooSystem: KarooSystemService,
    private val baseUrl: String = BuildConfig.API_BASE_URL,
) {
    data class Response(val status: Int, val json: JsonObject?)

    suspend fun request(method: String, path: String, body: JsonObject? = null, token: String? = null): Response {
        val headers = buildMap {
            put("Accept", "application/json")
            if (body != null) put("Content-Type", "application/json")
            if (token != null) put("Authorization", "Bearer $token")
        }
        val params = OnHttpResponse.MakeHttpRequest(
            method = method,
            url = baseUrl + path,
            headers = headers,
            body = body?.toString()?.toByteArray(),
            waitForConnection = false,
        )
        val complete = withTimeout(TIMEOUT_MS) {
            callbackFlow {
                val consumerId = karooSystem.addConsumer(
                    params,
                    onError = { close(IOException(it)) },
                ) { event: OnHttpResponse ->
                    (event.state as? HttpResponseState.Complete)?.let { trySend(it) }
                }
                awaitClose { karooSystem.removeConsumer(consumerId) }
            }.first()
        }
        if (complete.statusCode !in 100..599) throw IOException(complete.error ?: "No response")
        val json = complete.body?.let { bytes ->
            runCatching { Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject }.getOrNull()
        }
        return Response(complete.statusCode, json)
    }

    companion object {
        private const val TIMEOUT_MS = 30_000L
    }
}

fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
