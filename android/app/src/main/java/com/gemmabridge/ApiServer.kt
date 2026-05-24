package com.gemmabridge

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json

/**
 * Starts an embedded Ktor server on `127.0.0.1:[port]` that exposes an OpenAI-compatible
 * surface backed by [engine] and authenticated via [keys].
 *
 * The server intentionally binds only to the loopback interface. For internet exposure,
 * run a tunnel (Cloudflare Tunnel, ngrok) as a separate process — the API key is the
 * authentication boundary in both cases.
 */
class ApiServer(
    private val engine: LlamaEngine,
    private val keys: KeyManager,
    private val port: Int = DEFAULT_PORT,
) {

    @Volatile private var server: NettyApplicationEngine? = null

    fun start() {
        if (server != null) return
        server = embeddedServer(
            factory = Netty,
            port = port,
            host = "127.0.0.1",
        ) {
            configure(engine, keys)
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
        server = null
    }

    val isRunning: Boolean get() = server != null

    companion object {
        const val DEFAULT_PORT = 11434
    }
}

private fun Application.configure(engine: LlamaEngine, keys: KeyManager) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    install(CORS) {
        anyHost()
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Options)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf(
                    "error" to mapOf(
                        "type" to (cause::class.simpleName ?: "Throwable"),
                        "message" to (cause.message ?: ""),
                    ),
                ),
            )
        }
    }

    routing {
        get("/health") {
            call.respond(mapOf("ok" to true))
        }

        get("/v1/models") {
            if (!authorized(call.request.header(HttpHeaders.Authorization), keys)) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid api key"))
                return@get
            }
            call.respondText(engine.listModels(), ContentType.Application.Json)
        }

        post("/v1/chat/completions") {
            if (!authorized(call.request.header(HttpHeaders.Authorization), keys)) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid api key"))
                return@post
            }
            val body = call.receiveText()
            val isStream = STREAM_TRUE.containsMatchIn(body)

            if (!isStream) {
                call.respondText(engine.chatCompletions(body), ContentType.Application.Json)
                return@post
            }

            call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                engine.chatCompletionsStream(body).collect { line ->
                    write(line)
                    write("\n")
                    flush()
                }
            }
        }
    }
}

private val STREAM_TRUE = Regex("\"stream\"\\s*:\\s*true")

private fun authorized(header: String?, keys: KeyManager): Boolean {
    val prefix = "Bearer "
    if (header == null || !header.startsWith(prefix)) return false
    return keys.isValid(header.substring(prefix.length).trim())
}
