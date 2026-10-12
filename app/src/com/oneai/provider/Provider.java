package com.oneai.provider;

import org.json.JSONException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * One backend's wire format.
 *
 * <p>Everything here is a pure function of the request plus a small streaming
 * state machine, with no Android types anywhere, so the exact JSON each
 * provider emits can be asserted in JVM tests rather than discovered by
 * watching a phone fail.
 */
public abstract class Provider {

    public abstract String id();

    public abstract String displayName();

    public abstract String defaultBaseUrl();

    public abstract String defaultModel();

    /** Models offered in the picker. Users can still type any model id. */
    public abstract List<String> knownModels();

    /**
     * Whether temperature is meaningful for this backend's current models.
     * When false the settings screen hides the control entirely, because
     * offering a knob that produces a 400 is worse than not offering it.
     */
    public abstract boolean supportsTemperature();

    /** Absolute URL for a streaming request. */
    public abstract String url(String baseUrl, ChatRequest request);

    public abstract Map<String, String> headers(String apiKey);

    /** The JSON request body. */
    public abstract String body(ChatRequest request) throws JSONException;

    /** A fresh parser for one streaming response. */
    public abstract StreamParser newParser(StreamListener listener);

    /**
     * Turn this provider's own error envelope into a readable message.
     * Implementations must not throw: this runs on an already-failing path.
     */
    public abstract ProviderException error(int status, String responseBody);

    /** Human-readable name for an API key field, e.g. "Anthropic". */
    public String keyLabel() {
        return displayName();
    }

    // ------------------------------------------------------------------

    /** Consumes decoded SSE events and feeds the listener. */
    public interface StreamParser {

        void onEvent(String event, String data) throws IOException, JSONException;

        /** Called once when the stream ends, however it ended. */
        void onEnd();
    }
}
