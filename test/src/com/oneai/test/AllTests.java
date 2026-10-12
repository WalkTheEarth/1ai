package com.oneai.test;

import com.oneai.provider.AnthropicProvider;
import com.oneai.provider.CompatibleEndpoints;
import com.oneai.provider.CompatibleProvider;
import com.oneai.provider.ChatClient;
import com.oneai.provider.ChatMessage;
import com.oneai.provider.ChatRequest;
import com.oneai.provider.GoogleProvider;
import com.oneai.provider.OpenAiProvider;
import com.oneai.provider.Provider;
import com.oneai.provider.ProviderException;
import com.oneai.provider.Providers;
import com.oneai.provider.StreamListener;

import org.json.JSONObject;

import com.oneai.net.TlsFailures;
import com.oneai.provider.Json;
import com.oneai.util.Hostname;
import com.oneai.util.Pem;

/** Wire-format tests for the three providers. */
public final class AllTests {

    // ---------------------------------------------------------------- fixtures

    private static final String ANTHROPIC_SSE = """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","usage":{"input_tokens":12,"output_tokens":1}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: ping
            data: {"type":"ping"}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Let me think."}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":", world"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":25}}

            event: message_stop
            data: {"type":"message_stop"}

            """;

    private static final String OPENAI_SSE = """
            data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,"model":"gpt-6-astra","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}],"usage":null}

            : Intermediate chat completion chunks omitted.

            data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,"model":"gpt-6-astra","choices":[{"index":0,"delta":{"content":"Hel","obfuscation":"xZ9"},"finish_reason":null}],"usage":null}

            data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,"model":"gpt-6-astra","choices":[{"index":0,"delta":{"content":"lo"},"finish_reason":null}],"usage":null}

            data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,"model":"gpt-6-astra","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":null}

            data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,"model":"gpt-6-astra","choices":[],"usage":{"prompt_tokens":9,"completion_tokens":2,"total_tokens":11}}

            data: [DONE]

            """;

    /** No terminator: Google ends the stream by closing it. */
    private static final String GOOGLE_SSE = """
            data: {"candidates":[{"content":{"parts":[{"text":"Hel"}],"role":"model"},"index":0}],"usageMetadata":{"promptTokenCount":5,"totalTokenCount":5}}

            data: {"candidates":[{"content":{"parts":[{"text":"lo"}],"role":"model"},"index":0}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":2,"totalTokenCount":7}}

            data: {"candidates":[{"content":{"parts":[{"thought":true,"text":"hmm"},{"thoughtSignature":"abc123"}],"role":"model"},"finishReason":"STOP","index":0}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":2,"totalTokenCount":7}}

            """;

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        anthropic();
        openai();
        google();
        framing();
        errors();
        tls();
        attachments();
        endpoints();
        nullHandling();
        tlsFailures();
        System.exit(T.report());
    }

    // ---------------------------------------------------------- attachments

    private static com.oneai.data.Attachment image(String mime, String content) {
        com.oneai.data.Attachment a = new com.oneai.data.Attachment();
        a.mimeType = mime;
        a.fileName = "photo." + (mime.endsWith("png") ? "png" : "jpg");
        try {
            a.data = content.getBytes("UTF-8");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return a;
    }

    private static ChatRequest withAttachment(Provider p, com.oneai.data.Attachment a) {
        ChatRequest r = new ChatRequest();
        r.model = p.defaultModel();
        r.maxTokens = 100;
        r.messages.add(ChatMessage.user("what is this?", java.util.Collections.singletonList(a)));
        return r;
    }

    private static void attachments() {
        T.section("Attachments");

        T.test("Anthropic sends an image source block", () -> {
            try {
                ChatRequest r = withAttachment(new AnthropicProvider(),
                        image("image/jpeg", "JPEGBYTES"));
                org.json.JSONArray content = new JSONObject(new AnthropicProvider().body(r))
                        .getJSONArray("messages").getJSONObject(0).getJSONArray("content");
                // Text first, then the image: the documented convention.
                T.eq("text", content.getJSONObject(0).getString("type"), "text block first");
                org.json.JSONObject img = content.getJSONObject(1);
                T.eq("image", img.getString("type"), "block type");
                org.json.JSONObject src = img.getJSONObject("source");
                T.eq("base64", src.getString("type"), "source type");
                T.eq("image/jpeg", src.getString("media_type"), "media_type is snake_case");
                T.eq("JPEGBYTES", new String(Pem.decodeBase64(src.getString("data")), "UTF-8"),
                        "raw base64, no data: prefix");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        T.test("Anthropic sends a PDF as a document block", () -> {
            try {
                ChatRequest r = withAttachment(new AnthropicProvider(),
                        image("application/pdf", "PDFBYTES"));
                org.json.JSONArray content = new JSONObject(new AnthropicProvider().body(r))
                        .getJSONArray("messages").getJSONObject(0).getJSONArray("content");
                T.eq("document", content.getJSONObject(1).getString("type"), "document block");
                T.eq("application/pdf",
                        content.getJSONObject(1).getJSONObject("source").getString("media_type"),
                        "pdf media type");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        T.test("OpenAI nests image_url as an object with a data URL", () -> {
            try {
                ChatRequest r = withAttachment(new OpenAiProvider(),
                        image("image/png", "PNGBYTES"));
                org.json.JSONArray content = new JSONObject(new OpenAiProvider().body(r))
                        .getJSONArray("messages").getJSONObject(0).getJSONArray("content");
                org.json.JSONObject part = content.getJSONObject(1);
                T.eq("image_url", part.getString("type"), "part type");
                // The single most confusable detail: on chat completions this is
                // an object, on /v1/responses it is a bare string.
                T.isTrue(part.get("image_url") instanceof org.json.JSONObject,
                        "image_url must be an object here");
                T.contains(part.getJSONObject("image_url").getString("url"),
                        "data:image/png;base64,", "data URL with prefix");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        T.test("OpenAI sends a PDF as a file part nested under 'file'", () -> {
            try {
                ChatRequest r = withAttachment(new OpenAiProvider(),
                        image("application/pdf", "PDFBYTES"));
                org.json.JSONArray content = new JSONObject(new OpenAiProvider().body(r))
                        .getJSONArray("messages").getJSONObject(0).getJSONArray("content");
                org.json.JSONObject part = content.getJSONObject(1);
                T.eq("file", part.getString("type"), "part type");
                T.contains(part.getJSONObject("file").getString("file_data"),
                        "data:application/pdf;base64,", "file_data");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        T.test("Google uses camelCase inlineData", () -> {
            try {
                ChatRequest r = withAttachment(new GoogleProvider(),
                        image("image/jpeg", "JPEGBYTES"));
                org.json.JSONArray parts = new JSONObject(new GoogleProvider().body(r))
                        .getJSONArray("contents").getJSONObject(0).getJSONArray("parts");
                T.eq("what is this?", parts.getJSONObject(0).getString("text"), "text first");
                org.json.JSONObject blob = parts.getJSONObject(1).getJSONObject("inlineData");
                T.eq("image/jpeg", blob.getString("mimeType"), "mimeType is camelCase");
                T.eq("JPEGBYTES", new String(Pem.decodeBase64(blob.getString("data")), "UTF-8"),
                        "raw base64");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        T.test("a message with no attachments stays a plain string", () -> {
            try {
                // The array form is only used when it has to be: it is noisier
                // and there is no reason to send it for a text-only turn.
                ChatRequest r = new ChatRequest();
                r.model = "m";
                r.messages.add(ChatMessage.user("hello"));
                String body = new AnthropicProvider().body(r);
                T.isTrue(new JSONObject(body).getJSONArray("messages")
                        .getJSONObject(0).get("content") instanceof String,
                        "Anthropic content is a string");
                T.isTrue(new JSONObject(new OpenAiProvider().body(r)).getJSONArray("messages")
                        .getJSONObject(0).get("content") instanceof String,
                        "OpenAI content is a string");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        T.test("attachments are never sent on assistant turns", () -> {
            try {
                // Anthropic rejects image blocks in assistant turns outright,
                // and replaying a model's own output as media is meaningless.
                ChatRequest r = new ChatRequest();
                r.model = "m";
                r.messages.add(new ChatMessage(ChatMessage.ASSISTANT, "hi",
                        java.util.Collections.singletonList(image("image/jpeg", "X"))));
                T.isTrue(new JSONObject(new AnthropicProvider().body(r))
                        .getJSONArray("messages").getJSONObject(0).get("content")
                        instanceof String, "Anthropic drops them");
                T.isTrue(new JSONObject(new GoogleProvider().body(r))
                        .getJSONArray("contents").getJSONObject(0)
                        .getJSONArray("parts").length() == 1, "Google drops them");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    // -------------------------------------------------------- null handling

    /**
     * Android's org.json returns the literal string "null" from
     * optString(key, fallback) when the value is JSON null, where the reference
     * implementation returns the fallback. That turned a stream of chunks
     * carrying "content": null into a reply beginning "nullnullnullnull".
     *
     * <p>These tests cannot reproduce the Android quirk -- the JVM links the
     * reference library, where optString already behaves. What they pin is the
     * contract the fix relies on: the helper never hands back the string
     * "null", on either implementation, so the parsers behave identically
     * everywhere.
     */
    private static void nullHandling() {
        T.section("JSON null handling");

        T.test("a JSON null is not the string \"null\"", () -> {
            try {
                JSONObject o = new JSONObject("{\"content\":null}");
                T.isTrue(Json.str(o, "content") == null, "str returns null");
                T.eq("", Json.strOrEmpty(o, "content"), "strOrEmpty returns empty");
                T.isTrue(!Json.hasText(o, "content"), "hasText is false");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        T.test("a missing key is null, not the string \"null\"", () -> {
            try {
                JSONObject o = new JSONObject("{}");
                T.isTrue(Json.str(o, "content") == null, "str returns null");
                T.eq("", Json.strOrEmpty(o, "content"), "strOrEmpty returns empty");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        T.test("a real value still comes through", () -> {
            try {
                JSONObject o = new JSONObject("{\"content\":\"hello\"}");
                T.eq("hello", Json.str(o, "content"), "str");
                T.isTrue(Json.hasText(o, "content"), "hasText");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        T.test("OpenAI chunks with null content emit nothing", () -> {
            try {
                // The exact shape DeepSeek sends on chunks that carry no text:
                // a role-only opening delta, then nulls, then real content.
                String sse =
                    "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":null},\"finish_reason\":null}]}\n\n"
                  + "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":null},\"finish_reason\":null}]}\n\n"
                  + "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":null},\"finish_reason\":null}]}\n\n"
                  + "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"i am here\"},\"finish_reason\":null}]}\n\n"
                  + "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                  + "data: [DONE]\n\n";
                Recorder rec = new Recorder();
                new ChatClient(new MockTransport(200, sse, "text/event-stream", 0))
                        .stream(new OpenAiProvider(), "k",
                                new OpenAiProvider().defaultBaseUrl(),
                                req(new OpenAiProvider()), rec, new ChatClient.Session());
                T.eq("i am here", rec.text.toString(), "no stray nulls in the reply");
                T.lacks(rec.text.toString(), "null", "the word null never appears");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        T.test("Anthropic and Google ignore null deltas too", () -> {
            try {
                String anthropic =
                    "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":null}}\n\n"
                  + "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}\n\n"
                  + "data: {\"type\":\"message_stop\"}\n\n";
                T.eq("ok", runDrip(new AnthropicProvider(), anthropic, 0).text.toString(),
                        "anthropic");

                String google =
                    "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":null}],\"role\":\"model\"}}]}\n\n"
                  + "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}],\"role\":\"model\"}}]}\n\n";
                T.eq("ok", runDrip(new GoogleProvider(), google, 0).text.toString(), "google");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    // -------------------------------------------------------- TLS retry rule

    private static void tlsFailures() {
        T.section("TLS failure detection");

        T.test("handshake failures trigger a retry", () -> {
            T.isTrue(TlsFailures.isTransportFailure(
                    new javax.net.ssl.SSLHandshakeException("unsupported protocol")),
                    "SSLHandshakeException");
            T.isTrue(TlsFailures.isTransportFailure(
                    new java.io.IOException("SSL handshake aborted")), "message only");
            T.isTrue(TlsFailures.isTransportFailure(
                    new java.io.IOException("outer",
                            new javax.net.ssl.SSLException("inner"))), "wrapped");
            T.isTrue(TlsFailures.isTransportFailure(
                    new java.io.IOException("Trust anchor for certification path not found")),
                    "certificate problem");
        });

        T.test("server answers do NOT trigger a retry", () -> {
            // A 401 means the connection worked. Retrying would double the
            // request and hide the server's actual answer.
            T.isTrue(!TlsFailures.isTransportFailure(
                    new java.io.IOException("HTTP 401 Unauthorized")), "http status");
            T.isTrue(!TlsFailures.isTransportFailure(
                    new ProviderException(429, null, "slow down", "rate limited", true)),
                    "provider error");
            T.isTrue(!TlsFailures.isTransportFailure(new java.io.IOException((String) null)),
                    "null message");
            T.isTrue(!TlsFailures.isTransportFailure(null), "null throwable");
        });

        T.test("a self-referential cause chain terminates", () -> {
            final java.io.IOException a = new java.io.IOException("a");
            java.io.IOException b = new java.io.IOException("b", a) {
                @Override
                public synchronized Throwable getCause() {
                    return this;
                }
            };
            T.isTrue(!TlsFailures.isTransportFailure(b), "does not hang");
        });
    }

    // ------------------------------------------------------------ endpoints

    private static void endpoints() {
        T.section("Compatible endpoints");

        T.test("a base URL that already has /v1 is not doubled", () -> {
            // Most compatible services document a versioned base; OpenAI's own
            // does not. Appending blindly asks OpenRouter for /v1/v1/...
            T.eq("https://openrouter.ai/api/v1/chat/completions",
                    OpenAiProvider.completionsUrl("https://openrouter.ai/api/v1"), "versioned");
            T.eq("https://api.openai.com/v1/chat/completions",
                    OpenAiProvider.completionsUrl("https://api.openai.com"), "unversioned");
            T.eq("https://api.openai.com/v1/chat/completions",
                    OpenAiProvider.completionsUrl("https://api.openai.com/v1"), "already has it");
            T.eq("https://api.groq.com/openai/v1/chat/completions",
                    OpenAiProvider.completionsUrl("https://api.groq.com/openai/v1/"), "trailing slash");
        });

        T.test("every preset produces a well-formed completions URL", () -> {
            for (int i = 0; i < CompatibleEndpoints.ALL.length; i++) {
                CompatibleEndpoints.Endpoint e = CompatibleEndpoints.ALL[i];
                String url = OpenAiProvider.completionsUrl(e.baseUrl);
                T.contains(url, "/chat/completions", e.id + " ends correctly");
                T.isTrue(!url.contains("/v1/v1/"), e.id + " does not double the version");
                T.isTrue(e.name != null && e.name.length() > 0, e.id + " has a name");
            }
        });

        T.test("compatible providers inherit the OpenAI wire format", () -> {
            try {
                CompatibleProvider p = new CompatibleProvider(
                        CompatibleEndpoints.byId("openrouter"));
                T.eq("openrouter", p.id(), "id");
                T.isTrue(new JSONObject(p.body(req(p))).has("max_completion_tokens"),
                        "uses the OpenAI body");
                T.contains(p.url(p.defaultBaseUrl(), req(p)),
                        "openrouter.ai/api/v1/chat/completions", "url");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        T.test("registry resolves every provider by id", () -> {
            T.eq(Integer.valueOf(Providers.primary().length + Providers.compatible().length),
                    Integer.valueOf(Providers.all().length), "all() covers both groups");
            for (int i = 0; i < Providers.all().length; i++) {
                Provider p = Providers.all()[i];
                T.isTrue(Providers.byId(p.id()) == p, "resolve " + p.id());
            }
            T.isTrue(Providers.byId("nope") == null, "unknown id is null");
            T.isTrue(Providers.byIdOrDefault("nope") == Providers.primary()[0],
                    "unknown id falls back to the default");
        });
    }

    // ------------------------------------------------------------------ TLS

    private static void tls() {
        T.section("TLS hostname verification");

        T.test("exact match", () -> {
            T.isTrue(Hostname.matches("api.anthropic.com", "api.anthropic.com"), "exact");
            T.isTrue(Hostname.matches("API.Anthropic.COM", "api.anthropic.com"),
                    "case-insensitive");
            T.isTrue(Hostname.matches("api.anthropic.com.", "api.anthropic.com"),
                    "trailing dot is the same host");
        });

        T.test("rejects the wrong host", () -> {
            T.isTrue(!Hostname.matches("evil.com", "api.anthropic.com"), "unrelated host");
            T.isTrue(!Hostname.matches("api.anthropic.com.evil.com", "api.anthropic.com"),
                    "suffix attack");
            T.isTrue(!Hostname.matches("xapi.anthropic.com", "api.anthropic.com"),
                    "prefix attack");
        });

        T.test("wildcard covers exactly one label", () -> {
            T.isTrue(Hostname.matches("api.example.com", "*.example.com"), "one label");
            T.isTrue(Hostname.matches("www.example.com", "*.example.com"), "one label again");
        });

        T.test("wildcard does NOT cover the bare domain", () -> {
            T.isTrue(!Hostname.matches("example.com", "*.example.com"),
                    "*.example.com must not match example.com");
        });

        T.test("wildcard does NOT cross label boundaries", () -> {
            T.isTrue(!Hostname.matches("a.b.example.com", "*.example.com"),
                    "wildcard stands for one label, not a dotted path");
        });

        T.test("wildcard in a non-leftmost label is refused", () -> {
            T.isTrue(!Hostname.matches("a.b.example.com", "a.*.example.com"), "middle wildcard");
            T.isTrue(!Hostname.matches("foo.example.com", "foo*.example.com"), "partial label");
            T.isTrue(!Hostname.matches("a.example.com", "a.*.com"), "suffix wildcard");
        });

        T.test("public-suffix wildcards are refused", () -> {
            T.isTrue(!Hostname.matches("anything.com", "*.com"),
                    "*.com would be every host on the internet");
        });

        T.test("null and empty are refused, never accepted", () -> {
            T.isTrue(!Hostname.matches(null, "a.com"), "null host");
            T.isTrue(!Hostname.matches("a.com", null), "null pattern");
            T.isTrue(!Hostname.matches("", "a.com"), "empty host");
            T.isTrue(!Hostname.matchesAny("a.com", new java.util.ArrayList<String>()),
                    "empty name list identifies nobody");
        });

        T.test("base64 round-trips", () -> {
            for (int len = 0; len < 20; len++) {
                byte[] data = new byte[len];
                for (int i = 0; i < len; i++) {
                    data[i] = (byte) (i * 37 + 11);
                }
                String encoded = Pem.encodeBase64(data);
                byte[] back = Pem.decodeBase64(encoded);
                T.isTrue(java.util.Arrays.equals(data, back), "round trip of length " + len);
            }
        });

        T.test("base64 ignores the whitespace PEM files are full of", () -> {
            try {
                byte[] raw = "hello, world".getBytes("UTF-8");
                String clean = Pem.encodeBase64(raw);
                StringBuilder wrapped = new StringBuilder();
                for (int i = 0; i < clean.length(); i++) {
                    wrapped.append(clean.charAt(i));
                    if (i % 8 == 7) {
                        wrapped.append("\r\n");
                    }
                }
                T.isTrue(java.util.Arrays.equals(raw, Pem.decodeBase64(wrapped.toString())),
                        "line-wrapped base64 decodes identically");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        T.test("PEM reader finds every certificate in the bundle", () -> {
            try {
                String pem = "-----BEGIN CERTIFICATE-----\n"
                        + Pem.encodeBase64("first".getBytes("UTF-8")) + "\n"
                        + "-----END CERTIFICATE-----\n"
                        + "some intervening text\n"
                        + "-----BEGIN CERTIFICATE-----\n"
                        + Pem.encodeBase64("second".getBytes("UTF-8")) + "\n"
                        + "-----END CERTIFICATE-----\n";
                java.util.List<byte[]> certs = Pem.certificates(pem);
                T.eq(2, certs.size(), "two blocks found");
                T.eq("first", new String(certs.get(0), "UTF-8"), "first block");
                T.eq("second", new String(certs.get(1), "UTF-8"), "second block");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        T.test("PEM reader survives a truncated file", () -> {
            java.util.List<byte[]> certs = Pem.certificates(
                    "-----BEGIN CERTIFICATE-----\nAAAA\n");
            T.eq(0, certs.size(), "unterminated block is ignored, not a crash");
        });
    }

    // ------------------------------------------------------------- providers

    private static void anthropic() throws Exception {
        T.section("Anthropic");
        AnthropicProvider p = new AnthropicProvider();

        T.test("omits temperature when unset", () -> {
            try {
                ChatRequest r = req(p);
                JSONObject o = new JSONObject(p.body(r));
                T.isTrue(!o.has("temperature"),
                        "temperature must be absent, not defaulted: current Claude models 400 on it");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        T.test("includes temperature only when explicitly set", () -> {
            try {
                ChatRequest r = req(p);
                r.temperature = Double.valueOf(0.7);
                JSONObject o = new JSONObject(p.body(r));
                T.eq(Double.valueOf(0.7), o.getDouble("temperature"), "temperature");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        T.test("sends required fields and the system prompt", () -> {
            try {
                ChatRequest r = req(p);
                r.system = "Be terse.";
                JSONObject o = new JSONObject(p.body(r));
                T.eq("claude-opus-5-5", o.getString("model"), "model");
                T.eq(Integer.valueOf(8192), Integer.valueOf(o.getInt("max_tokens")), "max_tokens");
                T.eq(Boolean.TRUE, Boolean.valueOf(o.getBoolean("stream")), "stream");
                T.eq("Be terse.", o.getString("system"), "system");
                T.eq(1, o.getJSONArray("messages").length(), "messages");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        T.test("headers", () -> {
            T.eq("secret", p.headers("secret").get("x-api-key"), "x-api-key");
            T.eq("2023-06-01", p.headers("secret").get("anthropic-version"), "anthropic-version");
        });

        T.test("URL", () -> {
            T.eq("https://api.anthropic.com/v1/messages",
                    p.url(p.defaultBaseUrl(), req(p)), "url");
            T.eq("https://proxy.local/v1/messages",
                    p.url("https://proxy.local/", req(p)), "trailing slash trimmed");
        });

        T.test("streams text, thinking, usage and stop reason", () -> {
            try {
                Recorder rec = run(p, ANTHROPIC_SSE);
                T.eq("Hello, world", rec.text.toString(), "text");
                T.eq("Let me think.", rec.thinking.toString(), "thinking");
                T.eq(12, rec.inputTokens, "input tokens");
                T.eq(25, rec.outputTokens, "output tokens");
                T.eq("end_turn", rec.stopReason, "stop reason");
                T.eq(1, rec.doneCount, "onDone called exactly once");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        T.test("effort is sent as output_config.effort", () -> {
            try {
                ChatRequest r = req(p);
                r.effort = "high";
                JSONObject o = new JSONObject(p.body(r));
                T.eq("high", o.getJSONObject("output_config").getString("effort"), "effort");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    private static void openai() throws Exception {
        T.section("OpenAI");
        OpenAiProvider p = new OpenAiProvider();

        T.test("sends max_completion_tokens and never max_tokens", () -> {
            try {
                JSONObject o = new JSONObject(p.body(req(p)));
                T.isTrue(o.has("max_completion_tokens"),
                        "must use max_completion_tokens");
                T.isTrue(!o.has("max_tokens"),
                        "max_tokens is a hard 400 on GPT-5-and-later");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        T.test("opts out of obfuscation and into usage", () -> {
            try {
                JSONObject o = new JSONObject(p.body(req(p)));
                JSONObject so = o.getJSONObject("stream_options");
                T.eq(Boolean.TRUE, Boolean.valueOf(so.getBoolean("include_usage")), "include_usage");
                T.eq(Boolean.FALSE, Boolean.valueOf(so.getBoolean("include_obfuscation")),
                        "include_obfuscation defaults to true and pads deltas with junk");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        T.test("system prompt becomes a leading system message", () -> {
            try {
                ChatRequest r = req(p);
                r.system = "Be terse.";
                JSONObject o = new JSONObject(p.body(r));
                T.eq("system",
                        o.getJSONArray("messages").getJSONObject(0).getString("role"), "first role");
                T.eq(2, o.getJSONArray("messages").length(), "system + user");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        T.test("headers use Bearer", () -> {
            T.eq("Bearer secret", p.headers("secret").get("Authorization"), "Authorization");
        });

        T.test("streams text and survives the empty-choices usage chunk", () -> {
            try {
                Recorder rec = run(p, OPENAI_SSE);
                T.eq("Hello", rec.text.toString(), "text");
                T.eq(9, rec.inputTokens, "prompt tokens");
                T.eq(2, rec.outputTokens, "completion tokens");
                T.eq("stop", rec.stopReason, "finish_reason");
                T.eq(1, rec.doneCount, "onDone called exactly once");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    private static void google() throws Exception {
        T.section("Google");
        GoogleProvider p = new GoogleProvider();

        T.test("URL requires alt=sse and carries the model", () -> {
            String u = p.url(p.defaultBaseUrl(), req(p));
            T.contains(u, "/v1beta/models/gemini-3.8-flash:streamGenerateContent", "path");
            T.contains(u, "alt=sse", "alt=sse is mandatory or the body is a JSON array");
        });

        T.test("assistant role is 'model', not 'assistant'", () -> {
            try {
                ChatRequest r = req(p);
                r.messages.add(ChatMessage.assistant("hi"));
                JSONObject o = new JSONObject(p.body(r));
                T.eq("model", o.getJSONArray("contents").getJSONObject(1).getString("role"),
                        "role");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        T.test("systemInstruction and generationConfig shapes", () -> {
            try {
                ChatRequest r = req(p);
                r.system = "Be terse.";
                JSONObject o = new JSONObject(p.body(r));
                T.eq("Be terse.", o.getJSONObject("systemInstruction")
                        .getJSONArray("parts").getJSONObject(0).getString("text"), "system text");
                T.eq(Integer.valueOf(8192), Integer.valueOf(
                        o.getJSONObject("generationConfig").getInt("maxOutputTokens")),
                        "maxOutputTokens");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        T.test("uses the x-goog-api-key header, not Bearer", () -> {
            T.eq("secret", p.headers("secret").get("x-goog-api-key"), "x-goog-api-key");
            T.isTrue(p.headers("secret").get("Authorization") == null, "no Authorization header");
        });

        T.test("streams text, skips thought parts, ends at EOF", () -> {
            try {
                Recorder rec = run(p, GOOGLE_SSE);
                T.eq("Hello", rec.text.toString(), "text");
                T.eq("hmm", rec.thinking.toString(), "thought part routed to thinking");
                T.eq(5, rec.inputTokens, "prompt tokens");
                T.eq(2, rec.outputTokens, "candidate tokens");
                T.eq("STOP", rec.stopReason, "finishReason");
                T.eq(1, rec.doneCount, "onDone fires on EOF even with no terminator");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    // --------------------------------------------------------------- framing

    private static void framing() {
        T.section("Framing");

        T.test("survives one byte per read", () -> {
            try {
                Recorder rec = runDrip(new AnthropicProvider(), ANTHROPIC_SSE, 1);
                T.eq("Hello, world", rec.text.toString(), "text");
                T.eq(25, rec.outputTokens, "usage survives fragmentation");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        // A multi-byte character split across two reads is the classic way to
        // end up with mojibake, and it only shows up under real network
        // fragmentation, never in a local test that reads everything at once.
        T.test("reassembles multi-byte UTF-8 split across reads", () -> {
            try {
                String sse = "data: {\"type\":\"content_block_delta\",\"delta\":"
                        + "{\"type\":\"text_delta\",\"text\":\"你好 — éèê "
                        + "🚀\"}}\n\n"
                        + "data: {\"type\":\"message_stop\"}\n\n";
                Recorder whole = runDrip(new AnthropicProvider(), sse, 0);
                Recorder split = runDrip(new AnthropicProvider(), sse, 1);
                T.eq("你好 — éèê 🚀",
                        whole.text.toString(), "undripped");
                T.eq(whole.text.toString(), split.text.toString(),
                        "byte-at-a-time must decode identically");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        T.test("handles CRLF line endings", () -> {
            try {
                String sse = "data: {\"type\":\"content_block_delta\",\"delta\":"
                        + "{\"type\":\"text_delta\",\"text\":\"ok\"}}\r\n\r\n"
                        + "data: {\"type\":\"message_stop\"}\r\n\r\n";
                Recorder rec = runDrip(new AnthropicProvider(), sse, 0);
                T.eq("ok", rec.text.toString(), "CRLF terminated frames");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        T.test("a stream cut off mid-frame still ends exactly once", () -> {
            try {
                Recorder rec = runDrip(new AnthropicProvider(),
                        "data: {\"type\":\"message_stop\"}\n\n"
                                + "data: {\"type\":\"content_block_delta\",\"delta\":"
                                + "{\"type\":\"text_delta\",\"text\":\"tail", 0);
                T.eq(1, rec.doneCount, "onDone called exactly once");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    // ---------------------------------------------------------------- errors

    private static void errors() {
        T.section("Errors");

        T.test("Anthropic 401 explains itself", () -> {
            ProviderException e = new AnthropicProvider().error(401,
                    "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\","
                            + "\"message\":\"invalid x-api-key\"}}");
            T.contains(e.friendly, "API key rejected", "friendly text");
            T.eq("authentication_error", e.code, "code");
            T.eq(Boolean.FALSE, Boolean.valueOf(e.retryable), "not retryable");
        });

        T.test("Anthropic temperature 400 gives actionable advice", () -> {
            ProviderException e = new AnthropicProvider().error(400,
                    "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                            + "\"message\":\"temperature: Extra inputs are not permitted\"}}");
            T.contains(e.friendly, "temperature", "names the offending parameter");
        });

        T.test("OpenAI 429 distinguishes quota from rate limit", () -> {
            ProviderException quota = new OpenAiProvider().error(429,
                    "{\"error\":{\"message\":\"You exceeded your current quota\","
                            + "\"type\":\"insufficient_quota\",\"code\":\"insufficient_quota\"}}");
            T.contains(quota.friendly, "credit", "quota wording");
            T.eq(Boolean.FALSE, Boolean.valueOf(quota.retryable), "quota is not retryable");

            ProviderException rate = new OpenAiProvider().error(429,
                    "{\"error\":{\"message\":\"Rate limit reached\",\"type\":\"rate_limit_error\","
                            + "\"code\":\"rate_limit_exceeded\"}}");
            T.eq(Boolean.TRUE, Boolean.valueOf(rate.retryable), "rate limit is retryable");
        });

        T.test("Google bad key is a 400, not a 401", () -> {
            ProviderException e = new GoogleProvider().error(400,
                    "{\"error\":{\"code\":400,\"message\":\"API key not valid. "
                            + "Please pass a valid API key.\",\"status\":\"INVALID_ARGUMENT\"}}");
            T.contains(e.friendly, "rejected", "recognises API_KEY_INVALID");
        });

        T.test("non-JSON error bodies still produce a readable message", () -> {
            ProviderException e = new AnthropicProvider().error(503, "<html>gateway</html>");
            T.eq(Boolean.TRUE, Boolean.valueOf(e.retryable), "5xx is retryable");
            T.isTrue(e.friendly != null && e.friendly.length() > 0, "friendly text present");
            T.lacks(e.friendly, "<html>", "raw HTML must never reach the screen");
        });

        T.test("unrecognised status has no friendly text leak", () -> {
            ProviderException e = new OpenAiProvider().error(418, "nonsense");
            T.contains(e.friendly, "OpenAI", "names the provider");
            T.eq(Boolean.FALSE, Boolean.valueOf(e.retryable), "4xx not retryable");
        });

        T.test("HTTP error surfaces as ProviderException, not a raw stream", () -> {
            try {
                MockTransport t = new MockTransport(401,
                        "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\","
                                + "\"message\":\"invalid x-api-key\"}}",
                        "application/json", 0);
                AnthropicProvider p = new AnthropicProvider();
                try {
                    new ChatClient(t).stream(p, "bad", p.defaultBaseUrl(), req(p),
                            new Recorder(), new ChatClient.Session());
                    throw new AssertionError("expected ProviderException");
                } catch (ProviderException expected) {
                    T.eq(401, expected.status, "status carried through");
                }
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    // ---------------------------------------------------------------- helpers

    private static ChatRequest req(Provider p) {
        ChatRequest r = new ChatRequest();
        r.model = p.defaultModel();
        r.maxTokens = 8192;
        r.messages.add(ChatMessage.user("hi"));
        return r;
    }

    private static Recorder run(Provider p, String sse) throws Exception {
        return runDrip(p, sse, 0);
    }

    private static Recorder runDrip(Provider p, String sse, int drip) throws Exception {
        Recorder rec = new Recorder();
        MockTransport t = new MockTransport(200, sse, "text/event-stream", drip);
        new ChatClient(t).stream(p, "k", p.defaultBaseUrl(), req(p), rec,
                new ChatClient.Session());
        return rec;
    }

    static final class Recorder implements StreamListener {
        final StringBuilder text = new StringBuilder();
        final StringBuilder thinking = new StringBuilder();
        int inputTokens;
        int outputTokens;
        String stopReason;
        int doneCount;

        @Override
        public void onText(String delta) {
            text.append(delta);
        }

        @Override
        public void onThinking(String delta) {
            thinking.append(delta);
        }

        @Override
        public void onUsage(int in, int out) {
            inputTokens = in;
            outputTokens = out;
        }

        @Override
        public void onDone(String reason) {
            stopReason = reason;
            doneCount++;
        }
    }

    private AllTests() {
    }
}
