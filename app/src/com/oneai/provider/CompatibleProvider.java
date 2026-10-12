package com.oneai.provider;

/**
 * A third-party service that speaks the OpenAI chat-completions format.
 *
 * <p>Nothing is overridden but the identity. That is the whole point of the
 * preset list: OpenRouter, Groq, DeepSeek, xAI and the rest differ in base URL
 * and model names, not in protocol, so one parser serves all of them.
 */
public final class CompatibleProvider extends OpenAiProvider {

    private final CompatibleEndpoints.Endpoint endpoint;

    public CompatibleProvider(CompatibleEndpoints.Endpoint endpoint) {
        super(endpoint.id, endpoint.name, endpoint.baseUrl, endpoint.defaultModel, endpoint.models);
        this.endpoint = endpoint;
    }

    public CompatibleEndpoints.Endpoint endpoint() {
        return endpoint;
    }

    /**
     * True here, unlike for OpenAI itself. These services generally do accept
     * temperature, and the setting is omitted unless the user actually sets a
     * value, so offering the control is safe.
     */
    @Override
    public boolean supportsTemperature() {
        return true;
    }
}
