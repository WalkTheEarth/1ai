package com.oneai.provider;

/** One turn, in a provider-neutral form. */
public final class ChatMessage {

    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";

    public final String role;
    public final String content;

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public static ChatMessage user(String text) {
        return new ChatMessage(USER, text);
    }

    public static ChatMessage assistant(String text) {
        return new ChatMessage(ASSISTANT, text);
    }
}
