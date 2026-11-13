package io.github.bakeron.tapedo.core.settings

/**
 * AI providers in the order they are asked: free ones first, paid ones last (Gemini too - Google gives its
 * free plan only to some accounts and projects). Models checked with the providers on 7.11.2026; they come
 * and go, so the user can type another one and the free lists are read from the provider when possible.
 */
enum class AiProvider(
    /** Stable name in settings and usage counters - never rename. */
    val id: String,
    val free: Boolean,
    val defaultModel: String,
    /** Pause between two calls, so free per-minute limits are not hit. */
    val pauseMillis: Long,
    /** Free models offered when the provider's own list cannot be read. */
    val savedFreeModels: List<String>,
    /** Where the user creates a key. */
    val keyPage: String,
) {
    GROQ(
        "groq", true, "openai/gpt-oss-120b", 2_500,
        listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b", "llama-3.3-70b-versatile", "llama-3.1-8b-instant", "qwen/qwen3.8-27b"),
        "https://console.groq.com/keys",
    ),
    OPENROUTER(
        // "openrouter/free" picks a free model that is not busy at the moment - one popular free model
        // was often "rate-limited upstream" after a single call (Tablica ofert, 7.11.2026).
        "openrouter", true, "openrouter/free", 3_500,
        listOf("openrouter/free", "google/gemma-4-31b-it:free", "google/gemma-4-26b-a4b-it:free", "nvidia/nemotron-3.5-super-vl:free"),
        "https://openrouter.ai/keys",
    ),
    // Sonnet: accurate enough for sorting notes at about 2-3 grosze a minute of speech; Opus costs twice as much
    // for no gain here, Haiku is cheaper but wrong more often (author's shared AI rules, 11.11.2026).
    ANTHROPIC("anthropic", false, "claude-sonnet-5-5", 500, emptyList(), "https://platform.claude.com/settings/keys"),
    OPENAI("openai", false, "gpt-6-luna", 500, emptyList(), "https://platform.openai.com/api-keys"),
    GEMINI("gemini", false, "gemini-3.5-flash-lite", 500, emptyList(), "https://aistudio.google.com/apikey"),
    ;

    companion object {
        fun ofId(id: String): AiProvider? = entries.firstOrNull { it.id == id.trim() }
    }
}
