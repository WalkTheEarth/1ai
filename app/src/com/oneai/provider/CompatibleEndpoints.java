package com.oneai.provider;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Well-known services that speak the OpenAI chat-completions wire format.
 *
 * <p>Everything here was read off the vendor's own documentation rather than
 * recalled, because base URLs are exactly the kind of detail that drifts and a
 * wrong one produces a confusing failure rather than an obvious one. Several
 * are easy to get wrong: most include {@code /v1} in the base URL but DeepSeek's
 * does not, and Google's own curl samples for Gemini use snake_case where the
 * published JSON schema says camelCase.
 *
 * <p>Two popular services are deliberately absent:
 *
 * <ul>
 *   <li><b>Perplexity</b> — its documented base is now
 *       {@code https://api.perplexity.ai/router/v1}, and Sonar chat-completions
 *       support ended in September 2026 in favour of a different API. A preset
 *       pointing at the old shape would simply fail.</li>
 *   <li><b>Azure OpenAI</b> — the URL is deployment-based with a mandatory
 *       {@code api-version} query parameter, and it authenticates with an
 *       {@code api-key} header rather than a bearer token. It cannot be
 *       expressed as a base URL at all, so it needs a provider of its own
 *       rather than a preset.</li>
 * </ul>
 */
public final class CompatibleEndpoints {

    /** One preset endpoint. */
    public static final class Endpoint {

        public final String id;
        public final String name;
        /**
         * Base URL up to but not including {@code /chat/completions}. Where a
         * vendor documents a versioned root, it is included here.
         */
        public final String baseUrl;
        public final String defaultModel;
        public final List<String> models;
        /** Shown in settings; use for caveats the user should know. */
        public final String note;

        Endpoint(String id, String name, String baseUrl, String defaultModel, String note,
                 String... models) {
            this.id = id;
            this.name = name;
            this.baseUrl = baseUrl;
            this.defaultModel = defaultModel;
            this.note = note == null ? "" : note;
            this.models = Collections.unmodifiableList(Arrays.asList(models));
        }
    }

    private static final List<String> LOCAL_ONLY = Collections.singletonList(
            "Runs on your own machine. From a phone, point this at the computer's "
                    + "address on your network (e.g. http://192.168.1.20:11434/v1), "
                    + "not localhost.");

    public static final Endpoint[] ALL = {

            new Endpoint("openrouter", "OpenRouter",
                    "https://openrouter.ai/api/v1", "openai/gpt-4o",
                    "Routes to many vendors behind one key.",
                    "openai/gpt-4o",
                    "anthropic/claude-3.5-sonnet",
                    "google/gemini-2.5-pro-preview",
                    "poolside/laguna-s-2.1:free"),

            new Endpoint("groq", "Groq",
                    "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile",
                    "Very fast inference. Also accepts openai/gpt-oss-120b.",
                    "llama-3.3-70b-versatile",
                    "llama-3.1-8b-instant",
                    "openai/gpt-oss-120b",
                    "openai/gpt-oss-20b"),

            new Endpoint("deepseek", "DeepSeek",
                    "https://api.deepseek.com/v1", "deepseek-flash",
                    "DeepSeek's own docs give the base without /v1. If you see a "
                            + "404, try removing /v1 from the endpoint.",
                    "deepseek-flash", "deepseek-v4-pro"),

            new Endpoint("xai", "xAI (Grok)",
                    "https://api.x.ai/v1", "grok-4.7", null,
                    "grok-4.7", "grok-4.6", "grok-4.5", "grok-4.3"),

            new Endpoint("mistral", "Mistral",
                    "https://api.mistral.ai/v1", "mistral-large-latest", null,
                    "mistral-large-latest", "mistral-small-latest"),

            new Endpoint("together", "Together AI",
                    "https://api.together.ai/v1",
                    "meta-llama/Llama-3.3-70B-Instruct-Turbo",
                    "Model ids are namespaced; a bare gpt-4o will 404.",
                    "meta-llama/Llama-3.3-70B-Instruct-Turbo",
                    "openai/gpt-oss-120b"),

            new Endpoint("fireworks", "Fireworks AI",
                    "https://api.fireworks.ai/inference/v1",
                    "accounts/fireworks/models/llama-v3p1-8b-instruct",
                    "Silently truncates to fit the context instead of erroring.",
                    "accounts/fireworks/models/llama-v3p1-8b-instruct",
                    "accounts/fireworks/models/llama-v3p3-70b-instruct"),

            new Endpoint("cerebras", "Cerebras",
                    "https://api.cerebras.ai/v1", "qwen-3.8-27b", null,
                    "qwen-3.8-27b", "gpt-oss-120b", "gemma-4-31b", "kimi-k2.7-code"),

            new Endpoint("sambanova", "SambaNova",
                    "https://api.sambanova.ai/v1", "Meta-Llama-3.3-70B-Instruct", null,
                    "Meta-Llama-3.3-70B-Instruct",
                    "DeepSeek-V3.1",
                    "gpt-oss-120b",
                    "Llama-4-Maverick-17B-128E-Instruct",
                    "Qwen3-32B"),

            new Endpoint("hyperbolic", "Hyperbolic",
                    "https://api.hyperbolic.xyz/v1",
                    "meta-llama/Llama-3.3-70B-Instruct", null,
                    "meta-llama/Llama-3.3-70B-Instruct"),

            new Endpoint("meta", "Meta Llama API",
                    "https://api.llama.com/compat/v1",
                    "Llama-4-Maverick-17B-128E-Instruct-FP8",
                    "The OpenAI-compatible surface lives under /compat/v1.",
                    "Llama-4-Maverick-17B-128E-Instruct-FP8"),

            new Endpoint("ollama", "Ollama (local)",
                    "http://localhost:11434/v1", "gemma4:e2b",
                    LOCAL_ONLY.get(0),
                    "gemma4:e2b", "gemma4:31b"),

            new Endpoint("lmstudio", "LM Studio (local)",
                    "http://localhost:1234/v1", "",
                    LOCAL_ONLY.get(0) + " Set the model to whatever you have loaded.",
                    ""),

            new Endpoint("vllm", "vLLM (local)",
                    "http://localhost:8000/v1", "",
                    LOCAL_ONLY.get(0) + " Set the model to whatever you served.",
                    ""),
    };

    private CompatibleEndpoints() {
    }

    public static Endpoint byId(String id) {
        for (int i = 0; i < ALL.length; i++) {
            if (ALL[i].id.equals(id)) {
                return ALL[i];
            }
        }
        return null;
    }

    public static Endpoint defaultEndpoint() {
        return ALL[0];
    }

    public static String[] ids() {
        String[] out = new String[ALL.length];
        for (int i = 0; i < ALL.length; i++) {
            out[i] = ALL[i].id;
        }
        return out;
    }

    public static CharSequence[] names() {
        CharSequence[] out = new CharSequence[ALL.length];
        for (int i = 0; i < ALL.length; i++) {
            out[i] = ALL[i].name;
        }
        return out;
    }
}
