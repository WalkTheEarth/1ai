package com.oneai.provider;

import com.oneai.data.Attachment;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Google Gemini (Generative Language API). */
public final class GoogleProvider extends Provider {

    public static final String ID = "google";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Google";
    }

    @Override
    public String defaultBaseUrl() {
        return "https://generativelanguage.googleapis.com";
    }

    @Override
    public String defaultModel() {
        return "gemini-3.8-flash";
    }

    @Override
    public List<String> knownModels() {
        return Arrays.asList(
                "gemini-3.8-flash",
                "gemini-3.6-flash",
                "gemini-3.5-flash-lite",
                "gemini-3.1-flash-lite",
                "gemini-3.1-pro-preview",
                "gemini-2.5-flash",
                "gemini-2.5-pro");
    }

    @Override
    public boolean supportsTemperature() {
        return true;
    }

    @Override
    public String url(String baseUrl, ChatRequest r) {
        // alt=sse is not optional. Without it the endpoint returns a single
        // JSON array of all responses at once, delivered only when the model
        // has finished -- which would defeat streaming entirely.
        return trimSlash(baseUrl) + "/v1beta/models/" + r.model
                + ":streamGenerateContent?alt=sse";
    }

    @Override
    public Map<String, String> headers(String apiKey) {
        Map<String, String> h = new HashMap<String, String>();
        h.put("Content-Type", "application/json");
        // The documented header. The ?key= query parameter still works but
        // leaks the key into URLs and logs.
        h.put("x-goog-api-key", apiKey);
        return h;
    }

    @Override
    public String body(ChatRequest r) throws JSONException {
        JSONObject o = new JSONObject();

        JSONArray contents = new JSONArray();
        for (int i = 0; i < r.messages.size(); i++) {
            ChatMessage m = r.messages.get(i);
            JSONObject c = new JSONObject();
            // Gemini calls the assistant "model". "assistant" is not a valid
            // role here and is rejected.
            boolean isUser = !ChatMessage.ASSISTANT.equals(m.role);
            c.put("role", isUser ? "user" : "model");
            c.put("parts", partsFor(m, isUser));
            contents.put(c);
        }
        o.put("contents", contents);

        if (r.system != null && r.system.length() > 0) {
            JSONObject si = new JSONObject();
            JSONArray parts = new JSONArray();
            JSONObject part = new JSONObject();
            part.put("text", r.system);
            parts.put(part);
            si.put("parts", parts);
            o.put("systemInstruction", si);
        }

        JSONObject gen = new JSONObject();
        gen.put("maxOutputTokens", r.maxTokens);
        if (r.temperature != null) {
            gen.put("temperature", r.temperature.doubleValue());
        }
        o.put("generationConfig", gen);

        return o.toString();
    }

    /**
     * Text and media as peer entries in one parts array.
     *
     * <p>camelCase ({@code inlineData} / {@code mimeType}) rather than the
     * snake_case of Google's own curl samples. Both are accepted by proto3 JSON,
     * but the published JSON schema documents camelCase, so that is what the
     * REST endpoint is specified against. The {@code "type": "image"}
     * discriminator seen in newer Google docs belongs to the Interactions API
     * and is not valid here.
     */
    private static JSONArray partsFor(ChatMessage m, boolean isUser) throws JSONException {
        JSONArray parts = new JSONArray();
        // Text before media, matching the documented convention.
        if (m.content.length() > 0) {
            JSONObject text = new JSONObject();
            text.put("text", m.content);
            parts.put(text);
        }
        if (isUser && m.hasAttachments()) {
            for (int i = 0; i < m.attachments.size(); i++) {
                Attachment a = m.attachments.get(i);
                if (!Attachments.usable(a)) {
                    continue;
                }
                JSONObject blob = new JSONObject();
                blob.put("mimeType", a.mimeType);
                blob.put("data", Attachments.base64(a));

                JSONObject part = new JSONObject();
                part.put("inlineData", blob);
                parts.put(part);
            }
        }
        if (parts.length() == 0) {
            // Gemini rejects an empty parts array, which can otherwise happen
            // for an assistant turn whose text was empty.
            JSONObject empty = new JSONObject();
            empty.put("text", "");
            parts.put(empty);
        }
        return parts;
    }

    @Override
    public StreamParser newParser(final StreamListener l) {
        return new StreamParser() {
            private int inputTokens;
            private int outputTokens;
            private String stopReason;
            private boolean done;

            @Override
            public void onEvent(String event, String data) throws IOException, JSONException {
                if (data == null || data.length() == 0) {
                    return;
                }
                JSONObject o = new JSONObject(data);

                JSONObject u = o.optJSONObject("usageMetadata");
                if (u != null) {
                    inputTokens = u.optInt("promptTokenCount", inputTokens);
                    outputTokens = u.optInt("candidatesTokenCount", outputTokens);
                }

                JSONArray candidates = o.optJSONArray("candidates");
                if (candidates != null && candidates.length() > 0) {
                    JSONObject cand = candidates.optJSONObject(0);
                    if (cand != null) {
                        if (cand.has("finishReason") && !cand.isNull("finishReason")) {
                            stopReason = cand.optString("finishReason", null);
                        }
                        JSONObject content = cand.optJSONObject("content");
                        if (content != null) {
                            JSONArray parts = content.optJSONArray("parts");
                            if (parts != null) {
                                for (int i = 0; i < parts.length(); i++) {
                                    JSONObject part = parts.optJSONObject(i);
                                    if (part == null) {
                                        continue;
                                    }
                                    // Chunks may carry parts with no text at
                                    // all (thought signatures), and thinking
                                    // parts are flagged rather than omitted.
                                    if (!part.has("text") || part.isNull("text")) {
                                        continue;
                                    }
                                    String text = part.optString("text", "");
                                    if (text.length() == 0) {
                                        continue;
                                    }
                                    if (part.optBoolean("thought", false)) {
                                        l.onThinking(text);
                                    } else {
                                        l.onText(text);
                                    }
                                }
                            }
                        }
                    }
                }

                // A prompt rejected outright is reported here rather than as
                // an HTTP error.
                JSONObject feedback = o.optJSONObject("promptFeedback");
                if (feedback != null && feedback.has("blockReason")
                        && !feedback.isNull("blockReason")) {
                    throw new IOException(new ProviderException(
                            ProviderException.NO_STATUS, null, null,
                            "Google blocked this prompt ("
                                    + feedback.optString("blockReason") + ").", false));
                }
            }

            private void finish() {
                if (done) {
                    return;
                }
                done = true;
                if (inputTokens > 0 || outputTokens > 0) {
                    l.onUsage(inputTokens, outputTokens);
                }
                l.onDone(stopReason);
            }

            @Override
            public void onEnd() {
                // Google sends no terminator; the stream simply closes, so
                // reaching the end is the only completion signal there is.
                finish();
            }
        };
    }

    @Override
    public ProviderException error(int status, String responseBody) {
        String code = null;
        String detail = null;
        try {
            JSONObject o = new JSONObject(responseBody);
            JSONObject e = o.optJSONObject("error");
            if (e != null) {
                detail = e.optString("message", null);
                code = e.optString("status", null);
            }
        } catch (JSONException ignored) {
            // Not JSON; fall through.
        }
        // A bad AI Studio key surfaces as 400 INVALID_ARGUMENT with
        // API_KEY_INVALID rather than as a 401, which is easy to mis-handle.
        if (detail != null && detail.indexOf("API key not valid") >= 0) {
            return new ProviderException(status, code, detail,
                    "API key rejected by Google. Check it in Settings.", false);
        }
        if (status == 429) {
            return new ProviderException(status, code, detail,
                    "Rate limited by Google. Wait a moment and retry.", true);
        }
        if (status == 403) {
            return new ProviderException(status, code, detail,
                    "Google denied access. The key may lack permission for this model.", false);
        }
        if (status == 404) {
            return new ProviderException(status, code, detail,
                    "Google does not recognise that model id.", false);
        }
        if (status >= 500) {
            return new ProviderException(status, code, detail,
                    "Google is having trouble. Retry shortly.", true);
        }
        return ProviderException.fromStatus(status, "Google", detail);
    }

    private static String trimSlash(String s) {
        if (s == null || s.length() == 0) {
            return "https://generativelanguage.googleapis.com";
        }
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
