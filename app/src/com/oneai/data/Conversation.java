package com.oneai.data;

/** One chat thread. Provider and model are per-conversation so a single list can
 *  hold threads against different backends, which is the point of the app. */
public class Conversation {

    public long id;
    public String title;
    /** Provider id: "anthropic", "openai" or "google". */
    public String provider;
    /** Concrete model id, or null to use whatever the provider's default is. */
    public String model;
    /** Per-conversation system prompt, or null to fall back to the global one. */
    public String systemPrompt;
    /** Cached snippet of the last message, for the list screen. May be null. */
    public String preview;
    public long createdAt;
    public long updatedAt;

    public Conversation() {
    }

    public Conversation(String provider) {
        this.provider = provider;
    }
}
