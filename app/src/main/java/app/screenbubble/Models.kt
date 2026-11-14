package app.screenbubble

import android.content.Context

enum class Provider(
    val label: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
) {
    OPENAI("ChatGPT (OpenAI)", "https://api.openai.com/v1", "gpt-4o-mini"),
    GEMINI("Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.0-flash"),
    CLAUDE("Claude", "https://api.anthropic.com/v1", "claude-sonnet-4-5"),
    // Any OpenAI-compatible service (same wire format as OpenAI); pre-filled with aiapi.world.
    CUSTOM("aiapi.world (OpenAI-compatible)", "https://aiapi.world/v1", "gpt-4o-mini"),
}

enum class Direction { AUTO, FA_TO_EN, EN_TO_FA }

data class ProviderConfig(
    val provider: Provider,
    val apiKey: String,
    val baseUrl: String,
    val model: String,
)

/** One piece of on-screen text. Box values are fractions 0..1 of the screenshot, or null. */
data class Block(
    val original: String,
    val translation: String,
    val top: Float?,
    val left: Float?,
    val bottom: Float?,
    val right: Float?,
) {
    val hasBox: Boolean get() = top != null && left != null && bottom != null && right != null
}

data class ProbeResult(val ok: Boolean, val millis: Long, val detail: String)

class LlmException(val code: Int, message: String) : Exception(message)

/** Plain SharedPreferences storage; the API keys never leave the device except to the chosen API. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var provider: Provider
        get() = runCatching { Provider.valueOf(sp.getString("provider", "")!!) }.getOrDefault(Provider.OPENAI)
        set(value) = sp.edit().putString("provider", value.name).apply()

    var direction: Direction
        get() = runCatching { Direction.valueOf(sp.getString("direction", "")!!) }.getOrDefault(Direction.AUTO)
        set(value) = sp.edit().putString("direction", value.name).apply()

    fun apiKey(p: Provider): String = sp.getString("key_${p.name}", "") ?: ""
    fun setApiKey(p: Provider, v: String) = sp.edit().putString("key_${p.name}", v).apply()

    fun baseUrl(p: Provider): String = sp.getString("url_${p.name}", "")?.ifBlank { null } ?: p.defaultBaseUrl
    fun setBaseUrl(p: Provider, v: String) = sp.edit().putString("url_${p.name}", v).apply()

    fun model(p: Provider): String = sp.getString("model_${p.name}", "")?.ifBlank { null } ?: p.defaultModel
    fun setModel(p: Provider, v: String) = sp.edit().putString("model_${p.name}", v).apply()

    fun config(p: Provider = provider) = ProviderConfig(p, apiKey(p).trim(), baseUrl(p).trim().trimEnd('/'), model(p).trim())
}
