package com.gameocr.app.translate

import com.gameocr.app.data.withApiTimeout
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Wire protocol an endpoint speaks. The user is never limited to a preset model list. */
enum class ApiKind { OPENAI, ANTHROPIC }

/** Result of auto-detecting what a base URL + key speaks and which models it lists. */
data class ApiDetection(
    val kind: ApiKind?,
    val models: List<String>,
    val message: String,
)

/** Result of one real (1-token) request against one model. */
data class ModelProbeResult(
    val active: Boolean,
    val latencyMs: Long,
    val detail: String,
)

internal fun openAiProbeBase(baseUrl: String): String =
    baseUrl.trim().trimEnd('/')

internal fun anthropicProbeBase(baseUrl: String): String =
    baseUrl.trim().trimEnd('/').let { if (it.endsWith("/v1")) it else "$it/v1" }

internal fun parseModelIds(raw: String, json: Json): List<String> = runCatching {
    val root = json.parseToJsonElement(raw)
    val array = when (root) {
        is JsonObject -> root["data"] as? JsonArray ?: root["models"] as? JsonArray
        is JsonArray -> root
        else -> null
    } ?: return@runCatching emptyList()
    array.mapNotNull { item ->
        when (item) {
            is JsonObject -> (item["id"] as? JsonPrimitive)?.content
                ?: (item["name"] as? JsonPrimitive)?.content
            is JsonPrimitive -> item.content
            else -> null
        }?.trim()?.removePrefix("models/")?.takeIf { it.isNotEmpty() }
    }.distinct().sorted()
}.getOrDefault(emptyList())

/** Pulls a short human-readable reason out of an error body (OpenAI or Anthropic shape). */
internal fun probeErrorDetail(raw: String, json: Json): String {
    val message = runCatching {
        val error = (json.parseToJsonElement(raw) as? JsonObject)?.get("error")
        when (error) {
            is JsonObject -> (error["message"] as? JsonPrimitive)?.content
            is JsonPrimitive -> error.content
            else -> null
        }
    }.getOrNull()
    return (message ?: raw).trim().replace(Regex("\\s+"), " ").take(160)
}

/**
 * Auto-detects the API style of an endpoint and tests individual models with a real
 * max_tokens=1 request, so the user can see which listed models are actually usable.
 * Credentials are only ever sent to the user-entered base URL; redirects are not followed.
 */
@Singleton
class ModelProbeClient @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
) {
    suspend fun detect(baseUrl: String, apiKey: String, timeoutSeconds: Int): ApiDetection {
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            return ApiDetection(null, emptyList(), "Base URL and API key are required")
        }
        val http = http(timeoutSeconds)
        val openAiModels = listModels(
            http,
            Request.Builder().url(openAiProbeBase(baseUrl) + "/models")
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .header("Accept", "application/json").get().build(),
        )
        if (openAiModels.isSuccess) {
            val models = openAiModels.getOrThrow()
            return ApiDetection(ApiKind.OPENAI, models, "OpenAI-compatible")
        }
        val anthropicModels = listModels(
            http,
            Request.Builder().url(anthropicProbeBase(baseUrl) + "/models")
                .header("x-api-key", apiKey.trim())
                .header("anthropic-version", ANTHROPIC_API_VERSION)
                .header("Accept", "application/json").get().build(),
        )
        if (anthropicModels.isSuccess) {
            return ApiDetection(ApiKind.ANTHROPIC, anthropicModels.getOrThrow(), "Anthropic")
        }
        val reason = (openAiModels.exceptionOrNull() ?: anthropicModels.exceptionOrNull())
            ?.message.orEmpty()
        return ApiDetection(null, emptyList(), reason.ifBlank { "Unknown API" })
    }

    suspend fun probe(
        kind: ApiKind,
        baseUrl: String,
        apiKey: String,
        model: String,
        timeoutSeconds: Int,
    ): ModelProbeResult {
        val first = probeOnce(kind, baseUrl, apiKey, model, timeoutSeconds, completionTokens = false)
        // Newer reasoning models reject max_tokens and want max_completion_tokens instead.
        val retry = kind == ApiKind.OPENAI && !first.active &&
            first.detail.contains("max_completion_tokens", ignoreCase = true)
        return if (retry) {
            probeOnce(kind, baseUrl, apiKey, model, timeoutSeconds, completionTokens = true)
        } else {
            first
        }
    }

    private suspend fun probeOnce(
        kind: ApiKind,
        baseUrl: String,
        apiKey: String,
        model: String,
        timeoutSeconds: Int,
        completionTokens: Boolean,
    ): ModelProbeResult {
        val request = when (kind) {
            ApiKind.OPENAI -> Request.Builder()
                .url(openAiProbeBase(baseUrl) + "/chat/completions")
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .post(
                    buildJsonObject {
                        put("model", model)
                        put(if (completionTokens) "max_completion_tokens" else "max_tokens", 1)
                        put("stream", false)
                        put("messages", buildJsonArray {
                            add(buildJsonObject {
                                put("role", "user")
                                put("content", "ping")
                            })
                        })
                    }.toString().toRequestBody(JSON_MEDIA),
                ).build()
            ApiKind.ANTHROPIC -> Request.Builder()
                .url(anthropicProbeBase(baseUrl) + "/messages")
                .header("x-api-key", apiKey.trim())
                .header("anthropic-version", ANTHROPIC_API_VERSION)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .post(
                    buildJsonObject {
                        put("model", model)
                        put("max_tokens", 1)
                        put("messages", buildJsonArray {
                            add(buildJsonObject {
                                put("role", "user")
                                put("content", "ping")
                            })
                        })
                    }.toString().toRequestBody(JSON_MEDIA),
                ).build()
        }
        val startedAt = System.currentTimeMillis()
        return try {
            withContext(Dispatchers.IO) {
                http(timeoutSeconds).newCall(request).execute().use { response ->
                    val latency = System.currentTimeMillis() - startedAt
                    val raw = response.body?.source()?.let { source ->
                        source.request(MAX_BODY_BYTES)
                        source.buffer.snapshot().utf8()
                    }.orEmpty()
                    if (response.isSuccessful) {
                        ModelProbeResult(true, latency, "OK")
                    } else {
                        ModelProbeResult(
                            false, latency,
                            "HTTP ${response.code}: ${probeErrorDetail(raw, json)}".trimEnd(' ', ':'),
                        )
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Exception messages can echo the URL; keep only the type.
            ModelProbeResult(false, System.currentTimeMillis() - startedAt, error.javaClass.simpleName)
        }
    }

    private fun http(timeoutSeconds: Int): OkHttpClient =
        client.withApiTimeout(timeoutSeconds.coerceIn(5, 300)).newBuilder()
            .followRedirects(false).followSslRedirects(false).build()

    private suspend fun listModels(http: OkHttpClient, request: Request): Result<List<String>> =
        try {
            withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use Result.failure<List<String>>(IOException("HTTP ${response.code}"))
                    }
                    val source = response.body?.source()
                        ?: return@use Result.failure<List<String>>(IOException("empty body"))
                    source.request(MAX_BODY_BYTES + 1)
                    if (source.buffer.size > MAX_BODY_BYTES) {
                        return@use Result.failure<List<String>>(IOException("body too large"))
                    }
                    val models = parseModelIds(source.buffer.snapshot().utf8(), json)
                    if (models.isEmpty()) Result.failure<List<String>>(IOException("no models"))
                    else Result.success<List<String>>(models)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure<List<String>>(IOException(error.javaClass.simpleName))
        }

    private companion object {
        val JSON_MEDIA = "application/json".toMediaType()
        const val MAX_BODY_BYTES = 1024 * 1024L
    }
}
