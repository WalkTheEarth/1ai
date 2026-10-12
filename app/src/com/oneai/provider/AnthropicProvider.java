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

/** Anthropic Messages API. */
public final class AnthropicProvider extends Provider {

    public static final String ID = "anthropic";
    private static final String VERSION = "2023-06-01";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Anthropic";
    }

    @Override
    public String defaultBaseUrl() {
        return "https://api.anthropic.com";
    }

    @Override
    public String defaultModel() {
        return "claude-opus-5-5";
    }

    @Override
    public List<String> knownModels() {
        return Arrays.asList(
                "claude-opus-5-5",
                "claude-sonnet-5-5",
                "claude-haiku-5-5",
                "claude-opus-5",
                "claude-sonnet-5",
                "claude-opus-4-8",
                "claude-haiku-4-5");
    }

    /**
     * False, and this is not a stylistic choice. Every current Claude model
     * (Opus 5.5 / 5, Sonnet 5.5 / 5, Haiku 5.5, Opus 4.7 / 4.8, Fable 5)
     * rejects temperature, top_p and top_k with a 400. They are only accepted
     * by the previous generation, so the control is hidden by default and
     * revealed by an explicit advanced toggle.
     */
    @Override
    public boolean supportsTemperature() {
        return false;
    }

    @Override
    public String url(String baseUrl, ChatRequest request) {
        return trimSlash(baseUrl) + "/v1/messages";
    }

    @Override
    public Map<String, String> headers(String apiKey) {
        Map<String, String> h = new HashMap<String, String>();
        h.put("Content-Type", "application/json");
        h.put("x-api-key", apiKey);
        h.put("anthropic-version", VERSION);
        return h;
    }

    @Override
    public String body(ChatRequest r) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("model", r.model);
        o.put("max_tokens", r.maxTokens);
        o.put("stream", true);

        if (r.system != null && r.system.length() > 0) {
            o.put("system", r.system);
        }

        JSONArray msgs = new JSONArray();
        for (int i = 0; i < r.messages.size(); i++) {
            ChatMessage m = r.messages.get(i);
            JSONObject jm = new JSONObject();
            jm.put("role", m.role);
            jm.put("content", contentFor(m));
            msgs.put(jm);
        }
        o.put("messages", msgs);

        if (r.temperature != null) {
            o.put("temperature", r.temperature.doubleValue());
        }
        if (r.effort != null && r.effort.length() > 0) {
            JSONObject oc = new JSONObject();
            oc.put("effort", r.effort);
            o.put("output_config", oc);
        }
        return o.toString();
    }

    /**
     * A bare string when nothing is attached, and an array of content blocks
     * when something is. Keeping the simple case simple matters: it is what the
     * API examples send and it keeps the common request body small.
     *
     * <p>Attachments are only ever emitted on user turns. Anthropic rejects
     * image blocks in assistant turns outright, so re-sending a model's own
     * images back to it is not merely pointless, it is an error.
     */
    private static Object contentFor(ChatMessage m) throws JSONException {
        if (!m.hasAttachments() || !ChatMessage.USER.equals(m.role)) {
            return m.content;
        }
        JSONArray blocks = new JSONArray();
        // Text first: the documented convention, and what the models expect.
        if (m.content.length() > 0) {
            JSONObject text = new JSONObject();
            text.put("type", "text");
            text.put("text", m.content);
            blocks.put(text);
        }
        for (int i = 0; i < m.attachments.size(); i++) {
            Attachment a = m.attachments.get(i);
            if (!Attachments.usable(a)) {
                continue;
            }
            JSONObject source = new JSONObject();
            source.put("type", "base64");
            source.put("media_type", a.mimeType);
            source.put("data", Attachments.base64(a));

            JSONObject block = new JSONObject();
            // PDFs use a document block; the source shape is otherwise identical.
            block.put("type", a.isPdf() ? "document" : "image");
            block.put("source", source);
            blocks.put(block);
        }
        return blocks.length() == 0 ? m.content : blocks;
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
                // The event name on the wire and the "type" inside the payload
                // agree; trusting the payload means a mis-framed event line
                // cannot silently drop content.
                String type = o.optString("type", event == null ? "" : event);

                if ("message_start".equals(type)) {
                    JSONObject msg = o.optJSONObject("message");
                    if (msg != null) {
                        JSONObject u = msg.optJSONObject("usage");
                        if (u != null) {
                            inputTokens = u.optInt("input_tokens", 0);
                        }
                    }
                } else if ("content_block_delta".equals(type)) {
                    JSONObject d = o.optJSONObject("delta");
                    if (d != null) {
                        String dt = d.optString("type", "");
                        if ("text_delta".equals(dt)) {
                            String t = d.optString("text", "");
                            if (t.length() > 0) {
                                l.onText(t);
                            }
                        } else if ("thinking_delta".equals(dt)) {
                            String t = d.optString("thinking", "");
                            if (t.length() > 0) {
                                l.onThinking(t);
                            }
                        }
                    }
                } else if ("message_delta".equals(type)) {
                    JSONObject d = o.optJSONObject("delta");
                    if (d != null && d.has("stop_reason") && !d.isNull("stop_reason")) {
                        stopReason = d.optString("stop_reason", null);
                    }
                    JSONObject u = o.optJSONObject("usage");
                    if (u != null) {
                        outputTokens = u.optInt("output_tokens", outputTokens);
                    }
                } else if ("message_stop".equals(type)) {
                    finish();
                } else if ("error".equals(type)) {
                    // An error can arrive mid-stream after a 200. Surface it
                    // rather than treating the truncated text as complete.
                    JSONObject e = o.optJSONObject("error");
                    String detail = e == null ? data : e.optString("message", data);
                    String code = e == null ? null : e.optString("type", null);
                    throw new IOException(new ProviderException(
                            ProviderException.NO_STATUS, code, detail,
                            "Anthropic stopped the response: " + detail, true));
                }
                // "ping" and "content_block_start"/"stop" need no handling.
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
                // Covers a stream cut off before message_stop.
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
                code = e.optString("type", null);
                detail = e.optString("message", null);
            }
        } catch (JSONException ignored) {
            // Not JSON; fall through to the generic wording.
        }
        if (status == 401) {
            return new ProviderException(status, code, detail,
                    "API key rejected by Anthropic. Check it in Settings.", false);
        }
        if (status == 429) {
            return new ProviderException(status, code, detail,
                    "Rate limited by Anthropic. Wait a moment and retry.", true);
        }
        if (status == 400 && detail != null && detail.indexOf("temperature") >= 0) {
            return new ProviderException(status, code, detail,
                    "This Claude model rejects temperature. Turn off sampling params "
                            + "in Settings.", false);
        }
        if (status == 400 && detail != null && detail.indexOf("credit") >= 0) {
            return new ProviderException(status, code, detail,
                    "Anthropic account has insufficient credit.", false);
        }
        if (status >= 500) {
            return new ProviderException(status, code, detail,
                    "Anthropic is having trouble. Retry shortly.", true);
        }
        return ProviderException.fromStatus(status, "Anthropic", detail);
    }

    private static String trimSlash(String s) {
        if (s == null || s.length() == 0) {
            return "https://api.anthropic.com";
        }
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
