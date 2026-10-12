package com.oneai.provider;

/** Callbacks for one streaming response. Called from the network thread. */
public interface StreamListener {

    /** A chunk of the visible answer. */
    void onText(String delta);

    /**
     * A chunk of the model's reasoning, where the provider exposes it.
     * Only called when the caller asked for thinking to be shown.
     */
    void onThinking(String delta);

    /** Token usage, when the provider reports it. May never be called. */
    void onUsage(int inputTokens, int outputTokens);

    /** The stream finished normally. {@code stopReason} may be null. */
    void onDone(String stopReason);
}
