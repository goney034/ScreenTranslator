package app.screenbubble

import android.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

private const val ANTHROPIC_VERSION = "2023-06-01"
private val JSON_TYPE = "application/json".toMediaType()

/** Builds the instruction sent together with the screenshot. */
internal fun translatePrompt(direction: Direction): String {
    val rule = when (direction) {
        Direction.AUTO -> "Persian (Farsi) text must be translated to English, and English text must be translated to Persian (Farsi). Leave text in any other language out."
        Direction.FA_TO_EN -> "Translate Persian (Farsi) text to English. Leave all other text out."
        Direction.EN_TO_FA -> "Translate English text to Persian (Farsi). Leave all other text out."
    }
    return """
You are a screen translator. Look at the screenshot and find all readable text (menus, buttons, chat messages, subtitles, captions, labels).
$rule
Group each line or paragraph that belongs together into one block, and translate naturally, keeping names and numbers.
Reply with ONLY one JSON object, no markdown, in exactly this form:
{"blocks":[{"text":"original text","translation":"translated text","box":[ymin,xmin,ymax,xmax]}]}
"box" holds integers from 0 to 1000 relative to the image (0,0 is the top-left corner, 1000,1000 the bottom-right corner) and tightly surrounds that text. If nothing needs translating reply {"blocks":[]}.
""".trim()
}

/** Extracts the blocks from a model reply, tolerating code fences and extra prose. */
internal fun parseBlocks(raw: String): List<Block> {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    if (start < 0 || end <= start) throw LlmException(0, "Unexpected reply from the model")
    val array = JSONObject(raw.substring(start, end + 1)).optJSONArray("blocks") ?: return emptyList()
    val result = ArrayList<Block>()
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val translation = item.optString("translation").trim()
        if (translation.isEmpty()) continue
        val box = item.optJSONArray("box")
        fun frac(index: Int): Float? {
            if (box == null || box.length() < 4 || box.isNull(index)) return null
            return (box.optDouble(index, Double.NaN) / 1000.0).takeIf { !it.isNaN() }?.coerceIn(0.0, 1.0)?.toFloat()
        }
        var top = frac(0)
        var left = frac(1)
        var bottom = frac(2)
        var right = frac(3)
        if (top != null && bottom != null && top > bottom) { val t = top; top = bottom; bottom = t }
        if (left != null && right != null && left > right) { val t = left; left = right; right = t }
        result.add(Block(item.optString("text").trim(), translation, top, left, bottom, right))
    }
    return result
}

internal fun errorDetail(raw: String): String {
    val message = runCatching {
        val error = JSONObject(raw).opt("error")
        when (error) {
            is JSONObject -> error.optString("message")
            is String -> error
            else -> ""
        }
    }.getOrNull().orEmpty()
    return (message.ifBlank { raw }).trim().replace(Regex("\\s+"), " ").take(200)
}

/**
 * Talks to OpenAI-style, Gemini and Claude endpoints. Models are free text; nothing is limited to a preset list.
 * Redirects are not followed so a key can never be forwarded to another host.
 */
class LlmClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) {
    suspend fun translate(cfg: ProviderConfig, jpeg: ByteArray, direction: Direction): List<Block> =
        withContext(Dispatchers.IO) {
            val image = Base64.encodeToString(jpeg, Base64.NO_WRAP)
            val prompt = translatePrompt(direction)
            val reply = when (cfg.provider) {
                Provider.OPENAI, Provider.CUSTOM -> openAiChat(cfg, cfg.model, prompt, image, maxTokens = null)
                Provider.GEMINI -> geminiGenerate(cfg, cfg.model, prompt, image, maxTokens = null, json = true)
                Provider.CLAUDE -> claudeMessage(cfg, cfg.model, prompt, image, maxTokens = 4096)
            }
            parseBlocks(reply)
        }

    /** Lists every model the endpoint exposes. */
    suspend fun listModels(cfg: ProviderConfig): List<String> = withContext(Dispatchers.IO) {
        val request = when (cfg.provider) {
            Provider.OPENAI, Provider.CUSTOM -> Request.Builder().url(cfg.baseUrl + "/models")
                .header("Authorization", "Bearer ${cfg.apiKey}")
            Provider.GEMINI -> Request.Builder().url(cfg.baseUrl + "/models?pageSize=1000")
                .header("x-goog-api-key", cfg.apiKey)
            Provider.CLAUDE -> Request.Builder().url(cfg.baseUrl + "/models?limit=1000")
                .header("x-api-key", cfg.apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
        }.get().build()
        val root = JSONObject(execute(request))
        val names = ArrayList<String>()
        when (cfg.provider) {
            Provider.GEMINI -> {
                val models = root.optJSONArray("models") ?: JSONArray()
                for (i in 0 until models.length()) {
                    val item = models.optJSONObject(i) ?: continue
                    val methods = item.optJSONArray("supportedGenerationMethods")
                    val canGenerate = methods == null || (0 until methods.length()).any { methods.optString(it) == "generateContent" }
                    if (canGenerate) names.add(item.optString("name").removePrefix("models/"))
                }
            }
            else -> {
                val data = root.optJSONArray("data") ?: JSONArray()
                for (i in 0 until data.length()) names.add(data.optJSONObject(i)?.optString("id").orEmpty())
            }
        }
        names.filter { it.isNotBlank() }.distinct().sorted()
    }

    /** One tiny real request; tells whether this model is usable with the current key. */
    suspend fun probe(cfg: ProviderConfig, model: String): ProbeResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        try {
            when (cfg.provider) {
                Provider.OPENAI, Provider.CUSTOM -> try {
                    openAiChat(cfg, model, "ping", null, maxTokens = 1)
                } catch (e: LlmException) {
                    // Newer reasoning models want max_completion_tokens instead of max_tokens.
                    if (e.message.orEmpty().contains("max_completion_tokens")) {
                        openAiChat(cfg, model, "ping", null, maxTokens = 1, completionTokens = true)
                    } else {
                        throw e
                    }
                }
                Provider.GEMINI -> geminiGenerate(cfg, model, "ping", null, maxTokens = 1, json = false)
                Provider.CLAUDE -> claudeMessage(cfg, model, "ping", null, maxTokens = 1)
            }
            ProbeResult(true, System.currentTimeMillis() - started, "OK")
        } catch (e: LlmException) {
            ProbeResult(false, System.currentTimeMillis() - started, if (e.code > 0) "HTTP ${e.code}: ${e.message}" else e.message.orEmpty())
        } catch (e: Exception) {
            // Exception texts can echo the URL; keep only the type.
            ProbeResult(false, System.currentTimeMillis() - started, e.javaClass.simpleName)
        }
    }

    private fun openAiChat(
        cfg: ProviderConfig,
        model: String,
        prompt: String,
        imageBase64: String?,
        maxTokens: Int?,
        completionTokens: Boolean = false,
    ): String {
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
        if (imageBase64 != null) {
            content.put(
                JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$imageBase64")),
            )
        }
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        if (maxTokens != null) body.put(if (completionTokens) "max_completion_tokens" else "max_tokens", maxTokens)
        val request = Request.Builder().url(cfg.baseUrl + "/chat/completions")
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .post(body.toString().toRequestBody(JSON_TYPE)).build()
        val message = JSONObject(execute(request)).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
        return message?.optString("content").orEmpty()
    }

    private fun claudeMessage(
        cfg: ProviderConfig,
        model: String,
        prompt: String,
        imageBase64: String?,
        maxTokens: Int,
    ): String {
        val content = JSONArray()
        if (imageBase64 != null) {
            content.put(
                JSONObject().put("type", "image").put(
                    "source",
                    JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", imageBase64),
                ),
            )
        }
        content.put(JSONObject().put("type", "text").put("text", prompt))
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        val request = Request.Builder().url(cfg.baseUrl + "/messages")
            .header("x-api-key", cfg.apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .post(body.toString().toRequestBody(JSON_TYPE)).build()
        val parts = JSONObject(execute(request)).optJSONArray("content") ?: return ""
        return (0 until parts.length()).joinToString("") { parts.optJSONObject(it)?.optString("text").orEmpty() }
    }

    private fun geminiGenerate(
        cfg: ProviderConfig,
        model: String,
        prompt: String,
        imageBase64: String?,
        maxTokens: Int?,
        json: Boolean,
    ): String {
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        if (imageBase64 != null) {
            parts.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject().put("mime_type", "image/jpeg").put("data", imageBase64),
                ),
            )
        }
        val generation = JSONObject()
        if (json) generation.put("responseMimeType", "application/json")
        if (maxTokens != null) generation.put("maxOutputTokens", maxTokens)
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("generationConfig", generation)
        val request = Request.Builder().url(cfg.baseUrl + "/models/" + model.removePrefix("models/") + ":generateContent")
            .header("x-goog-api-key", cfg.apiKey)
            .post(body.toString().toRequestBody(JSON_TYPE)).build()
        val candidate = JSONObject(execute(request)).optJSONArray("candidates")?.optJSONObject(0)
        val texts = candidate?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
        return (0 until texts.length()).joinToString("") { texts.optJSONObject(it)?.optString("text").orEmpty() }
    }

    private fun execute(request: Request): String =
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw LlmException(response.code, errorDetail(raw))
            raw
        }
}
