package com.gemmabridge

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * On-device LLM engine backed by Google's MediaPipe LLM Inference API.
 *
 * Supply a Gemma `.task` or `.litertlm` bundle path (the user downloads it from
 * Hugging Face — see `android/README.md` for links). The engine reshapes OpenAI-style
 * `chat/completions` requests into Gemma's `<start_of_turn>` template and streams the
 * response back as SSE chunks, matching the surface of `ProxyEngine`.
 *
 * The expensive `LlmInference` instance is created once (in `init`) and shared across
 * all requests. A fresh `LlmInferenceSession` is created per request — that's the cheap
 * part, and using a per-request session keeps `temperature` / `topK` request-scoped
 * and avoids leaking state between callers.
 */
class MediaPipeEngine(
    context: Context,
    private val modelPath: String,
    private val maxTokens: Int = 2048,
) : LlamaEngine {

    init {
        require(File(modelPath).exists()) { "Model file not found: $modelPath" }
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val llm: LlmInference = LlmInference.createFromOptions(
        context,
        LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(maxTokens)
            .build(),
    )

    override suspend fun chatCompletions(requestBody: String): String =
        withContext(Dispatchers.Default) {
            val req = json.decodeFromString<ChatRequest>(requestBody)
            val prompt = buildPrompt(req.messages)
            val session = newSession(req)
            try {
                session.addQueryChunk(prompt)
                wrapResponse(session.generateResponse())
            } finally {
                session.close()
            }
        }

    override fun chatCompletionsStream(requestBody: String): Flow<String> = channelFlow {
        val req = json.decodeFromString<ChatRequest>(requestBody)
        val prompt = buildPrompt(req.messages)
        val session = newSession(req)

        session.addQueryChunk(prompt)
        session.generateResponseAsync { partial, done ->
            if (!partial.isNullOrEmpty()) {
                trySend("data: ${makeChunk(partial)}\n")
            }
            if (done) {
                trySend("data: [DONE]\n")
                close()
            }
        }
        awaitClose { session.close() }
    }

    override suspend fun listModels(): String {
        val name = File(modelPath).nameWithoutExtension.ifBlank { "gemma" }
        return buildJsonObject {
            put("object", JsonPrimitive("list"))
            put("data", buildJsonArray {
                add(buildJsonObject {
                    put("id", JsonPrimitive(name))
                    put("object", JsonPrimitive("model"))
                })
            })
        }.toString()
    }

    fun close() = llm.close()

    // ---------------------------------------------------------------------------

    private fun newSession(req: ChatRequest): LlmInferenceSession =
        LlmInferenceSession.createFromOptions(
            llm,
            LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(req.topK ?: 40)
                .setTemperature(req.temperature ?: 0.7f)
                .build(),
        )

    /**
     * Build Gemma's chat template from OpenAI messages.
     *
     * Gemma doesn't have a dedicated system role at the token level, so any `system`
     * messages are folded into the first `user` turn.
     */
    private fun buildPrompt(messages: List<Message>): String {
        val sb = StringBuilder()
        var pendingSystem: String? = null

        for (m in messages) {
            when (m.role) {
                "system" -> {
                    pendingSystem = (pendingSystem ?: "") + m.content + "\n\n"
                }
                "user" -> {
                    sb.append("<start_of_turn>user\n")
                    if (pendingSystem != null) {
                        sb.append(pendingSystem)
                        pendingSystem = null
                    }
                    sb.append(m.content).append("<end_of_turn>\n")
                }
                "assistant", "model" -> sb
                    .append("<start_of_turn>model\n")
                    .append(m.content)
                    .append("<end_of_turn>\n")
            }
        }
        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }

    private fun wrapResponse(content: String): String = buildJsonObject {
        put("id", JsonPrimitive("chatcmpl-${System.currentTimeMillis()}"))
        put("object", JsonPrimitive("chat.completion"))
        put("choices", buildJsonArray {
            add(buildJsonObject {
                put("index", JsonPrimitive(0))
                put("message", buildJsonObject {
                    put("role", JsonPrimitive("assistant"))
                    put("content", JsonPrimitive(content))
                })
                put("finish_reason", JsonPrimitive("stop"))
            })
        })
    }.toString()

    private fun makeChunk(delta: String): String = buildJsonObject {
        put("object", JsonPrimitive("chat.completion.chunk"))
        put("choices", buildJsonArray {
            add(buildJsonObject {
                put("delta", buildJsonObject {
                    put("content", JsonPrimitive(delta))
                })
            })
        })
    }.toString()

    @Serializable
    data class ChatRequest(
        val model: String? = null,
        val messages: List<Message> = emptyList(),
        val temperature: Float? = null,
        val topK: Int? = null,
        val stream: Boolean? = null,
    )

    @Serializable
    data class Message(
        val role: String,
        val content: String,
    )
}
