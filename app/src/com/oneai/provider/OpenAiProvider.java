package com.oneai.provider;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI chat completions.
 *
 * <p>Uses {@code /v1/chat/completions} rather than the newer {@code /v1/responses}.
 * Responses is what OpenAI recommends for new projects, but it is a different
 * wire format (typed events, input/output instead of messages/choices) and chat
 * completions carries no announced sunset date, so the simpler and far more
 * widely implemented endpoint is the right trade for this client.
 */
public final class OpenAiProvider extends Provider {

    public static final String ID = "openai";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "OpenAI";
    }

    @Override
    public String defaultBaseUrl() {
        return "https://api.openai.com";
    }

    @Override
    public String defaultModel() {
        return "gpt-6-astra";
    }

    @Override
    public List<String> knownModels() {
        return Arrays.asList(
                "gpt-6-astra",
                "gpt-6.1-sol",
                "gpt-6-luna");
    }

    /**
     * Nominally allowed, but the current reasoning-class models reject it, so
     * the settings screen keeps it behind an explicit opt-in rather than
     * sending it by default. Null temperature omits the field entirely.
     */
    @Override
    public boolean supportsTemperature() {
        return false;
    }

    @Override
    public String url(String baseUrl, ChatRequest request) {
        return trimSlash(baseUrl) + "/v1/chat/completions";
    }

    @Override
    public Map<String, String> headers(String apiKey) {
        Map<String, String> h = new HashMap<String, String>();
        h.put("Content-Type", "application/json");
        h.put("Authorization", "Bearer " + apiKey);
        return h;
    }

    @Override
    public String body(ChatRequest r) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("model", r.model);
        o.put("stream", true);

        // Usage is not sent unless asked for, and obfuscation defaults to ON,
        // which pads delta events with random junk. Neither default is useful
        // to us, so both are set explicitly.
        JSONObject so = new JSONObject();
        so.put("include_usage", true);
        so.put("include_obfuscation", false);
        o.put("stream_options", so);

        JSONArray msgs = new JSONArray();
        if (r.system != null && r.system.length() > 0) {
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", r.system);
            msgs.put(sys);
        }
        for (int i = 0; i < r.messages.size(); i++) {
            ChatMessage m = r.messages.get(i);
            JSONObject jm = new JSONObject();
            jm.put("role", m.role);
            jm.put("content", m.content);
            msgs.put(jm);
        }
        o.put("messages", msgs);

        // max_tokens is hard-rejected with a 400 by every GPT-5-and-later
        // model ("Use 'max_completion_tokens' instead"), so we never send it.
        o.put("max_completion_tokens", r.maxTokens);

        if (r.temperature != null) {
            o.put("temperature", r.temperature.doubleValue());
        }
        if (r.effort != null && r.effort.length() > 0) {
            o.put("reasoning_effort", r.effort);
        }
        return o.toString();
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
                if (data == null) {
                    return;
                }
                if ("[DONE]".equals(data.trim())) {
                    finish();
                    return;
                }
                JSONObject o = new JSONObject(data);

                JSONObject u = o.optJSONObject("usage");
                if (u != null) {
                    inputTokens = u.optInt("prompt_tokens", inputTokens);
                    outputTokens = u.optInt("completion_tokens", outputTokens);
                }

                JSONArray choices = o.optJSONArray("choices");
                // The final usage chunk carries an empty choices array, so this
                // guard is load-bearing: without it every single response ends
                // in an IndexOutOfBoundsException.
                if (choices == null || choices.length() == 0) {
                    return;
                }
                JSONObject choice = choices.optJSONObject(0);
                if (choice == null) {
                    return;
                }
                if (choice.has("finish_reason") && !choice.isNull("finish_reason")) {
                    stopReason = choice.optString("finish_reason", null);
                }
                JSONObject delta = choice.optJSONObject("delta");
                if (delta != null) {
                    String text = delta.optString("content", "");
                    if (text.length() > 0) {
                        l.onText(text);
                    }
                    // Some reasoning models expose a separate channel.
                    String reasoning = delta.optString("reasoning_content", "");
                    if (reasoning.length() > 0) {
                        l.onThinking(reasoning);
                    }
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
                code = e.optString("code", null);
                if (code == null || code.length() == 0 || "null".equals(code)) {
                    code = e.optString("type", null);
                }
            }
        } catch (JSONException ignored) {
            // Not JSON; fall through.
        }
        if (status == 401) {
            return new ProviderException(status, code, detail,
                    "API key rejected by OpenAI. Check it in Settings.", false);
        }
        if (status == 429) {
            String lower = detail == null ? "" : detail.toLowerCase();
            boolean quota = lower.indexOf("quota") >= 0 || lower.indexOf("billing") >= 0
                    || lower.indexOf("credit") >= 0;
            return new ProviderException(status, code, detail,
                    quota ? "OpenAI account is out of credit or over its spend limit."
                          : "Rate limited by OpenAI. Wait a moment and retry.",
                    !quota);
        }
        if (status == 400 && detail != null && detail.indexOf("max_completion_tokens") >= 0) {
            return new ProviderException(status, code, detail,
                    "This model needs max_completion_tokens, not max_tokens.", false);
        }
        if (status >= 500) {
            return new ProviderException(status, code, detail,
                    "OpenAI is having trouble. Retry shortly.", true);
        }
        return ProviderException.fromStatus(status, "OpenAI", detail);
    }

    private static String trimSlash(String s) {
        if (s == null || s.length() == 0) {
            return "https://api.openai.com";
        }
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
