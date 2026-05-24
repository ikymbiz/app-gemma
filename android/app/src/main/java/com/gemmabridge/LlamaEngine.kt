package com.gemmabridge

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Abstraction over the underlying inference engine. Two implementations:
 *
 *  - [ProxyEngine]: forwards to an OpenAI-compatible HTTP server (e.g. llama-server
 *    running locally via JNI in a future iteration, or on the same Wi-Fi while you wire
 *    up native bindings).
 *  - NativeEngine (not included): JNI wrapper around llama.cpp built with the NDK. See
 *    android/README.md for build pointers.
 */
interface LlamaEngine {
    /** Returns the response body (already OpenAI-formatted) for a non-streamed completion. */
    suspend fun chatCompletions(requestBody: String): String

    /** Emits each Server-Sent-Events `data: ...` line from upstream. */
    fun chatCompletionsStream(requestBody: String): Flow<String>

    /** Lists models exposed by the engine in OpenAI format. */
    suspend fun listModels(): String
}

/**
 * Forwards to an OpenAI-compatible backend at [upstreamBaseUrl]. By default this is
 * `http://127.0.0.1:8081` — a `llama-server` process you run on the same device.
 */
class ProxyEngine(private val upstreamBaseUrl: String) : LlamaEngine {

    private val client = HttpClient(CIO) {
        expectSuccess = false
        engine {
            requestTimeout = 0 // no request timeout; streams can be long
        }
    }

    override suspend fun chatCompletions(requestBody: String): String {
        val r: HttpResponse = client.post("$upstreamBaseUrl/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        return r.bodyAsText()
    }

    override fun chatCompletionsStream(requestBody: String): Flow<String> = flow {
        val r: HttpResponse = client.post("$upstreamBaseUrl/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(requestBody)
            headers { append("Accept", "text/event-stream") }
        }
        val channel: ByteReadChannel = r.bodyAsChannel()
        while (true) {
            val line = channel.readUTF8Line() ?: break
            // pass through every line including blank separators
            emit(line)
        }
    }

    override suspend fun listModels(): String {
        val r: HttpResponse = client.get("$upstreamBaseUrl/v1/models")
        return r.bodyAsText()
    }

    fun close() = client.close()
}
