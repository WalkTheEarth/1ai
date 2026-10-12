package com.oneai.data;

/** One turn in a conversation. */
public class Message {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    /** A locally generated failure notice, never sent to any API. */
    public static final String ROLE_ERROR = "error";

    public long id;
    public long conversationId;
    public String role;
    public String content;

    /** Which model actually produced this, for assistant turns. */
    public String provider;
    public String model;

    /** 0 when the provider did not report usage. */
    public int inputTokens;
    public int outputTokens;

    public long createdAt;

    public Message() {
    }

    public Message(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public boolean isUser() {
        return ROLE_USER.equals(role);
    }

    public boolean isError() {
        return ROLE_ERROR.equals(role);
    }
}
