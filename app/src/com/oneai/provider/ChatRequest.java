package com.oneai.provider;

import java.util.ArrayList;
import java.util.List;

/** A provider-neutral generation request. */
public final class ChatRequest {

    public String model;
    /** System prompt, or null to omit it entirely. */
    public String system;
    public final List<ChatMessage> messages = new ArrayList<ChatMessage>();

    public int maxTokens = 8192;

    /**
     * Sampling temperature, or null to leave it out of the request.
     *
     * <p>Null is the default on purpose. Anthropic returns a 400 for
     * temperature on every current Claude model, and OpenAI's reasoning-class
     * models reject it too, so "unset" has to be a distinguishable state from
     * "set to some default number".
     */
    public Double temperature;

    /**
     * Reasoning effort: Anthropic's {@code output_config.effort} or OpenAI's
     * {@code reasoning_effort}. Null omits it and lets the provider default
     * apply, which is what you want unless you are deliberately tuning.
     */
    public String effort;
}
