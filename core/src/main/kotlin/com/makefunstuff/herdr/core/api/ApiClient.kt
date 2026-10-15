package com.makefunstuff.herdr.core.api

import com.makefunstuff.herdr.core.ssh.Duplex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * Makes each request on a fresh connection, which is how herdr's API socket works: it reads one
 * request, answers it, and closes.
 */
class ApiClient(private val open: suspend () -> Duplex) : ApiCaller {
    private val nextId = AtomicLong(1)

    override suspend fun call(method: String, params: JsonObject, timeoutMs: Long): JsonObject {
        val id = "r${nextId.getAndIncrement()}"
        val line = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("method", JsonPrimitive(method))
            put("params", params)
        }.toString() + "\n"
        val duplex = open()
        return try {
            coroutineScope {
                // Blocking SSH reads don't respond to cancellation; closing the channel does.
                val watchdog = launch { delay(timeoutMs); duplex.close() }
                val reply = withContext(Dispatchers.IO) {
                    duplex.output.write(line.toByteArray(Charsets.UTF_8))
                    duplex.output.flush()
                    duplex.input.bufferedReader(Charsets.UTF_8).readLine()
                }
                watchdog.cancel()
                reply ?: throw IOException("herdr closed the connection without answering $method")
                ApiConnection.unwrap(HerdrJson.parseToJsonElement(reply).jsonObject)
            }
        } finally {
            withContext(Dispatchers.IO) { runCatching { duplex.close() } }
        }
    }
}
