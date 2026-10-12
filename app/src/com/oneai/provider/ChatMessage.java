package com.oneai.provider;

import com.oneai.data.Attachment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One turn, in a provider-neutral form. */
public final class ChatMessage {

    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";

    public final String role;
    public final String content;

    /**
     * Attached files, with their bytes already loaded.
     *
     * <p>Only ever populated on user turns. Anthropic rejects image blocks in
     * assistant turns outright, and re-sending a model's own images back to it
     * is not something any of the three APIs expects.
     */
    public final List<Attachment> attachments;

    public ChatMessage(String role, String content) {
        this(role, content, null);
    }

    public ChatMessage(String role, String content, List<Attachment> attachments) {
        this.role = role;
        this.content = content == null ? "" : content;
        this.attachments = attachments == null
                ? Collections.<Attachment>emptyList()
                : new ArrayList<Attachment>(attachments);
    }

    public static ChatMessage user(String text) {
        return new ChatMessage(USER, text);
    }

    public static ChatMessage user(String text, List<Attachment> attachments) {
        return new ChatMessage(USER, text, attachments);
    }

    public static ChatMessage assistant(String text) {
        return new ChatMessage(ASSISTANT, text);
    }

    public boolean hasAttachments() {
        return !attachments.isEmpty();
    }
}
