package com.oneai.chat;

import android.content.Context;

import com.oneai.data.Prefs;
import com.oneai.net.Conn;
import com.oneai.net.Transport;
import com.oneai.net.android.TransportFactory;
import com.oneai.provider.ChatMessage;
import com.oneai.provider.ChatRequest;
import com.oneai.provider.Provider;

import java.io.InputStream;
import java.util.Map;

/**
 * Answers the question "can this device actually talk to the API, and with
 * which TLS stack?" without spending a request against the user's quota.
 *
 * <p>It sends a deliberately invalid request. A 401 or 400 back is a
 * <em>success</em> for our purposes: it proves DNS, TCP, the TLS handshake,
 * certificate validation, HTTP framing and the response path all work. Only a
 * connection-level failure means the device genuinely cannot reach the service.
 *
 * <p>This matters most on old hardware, where "it doesn't work" has several
 * very different causes and no way to tell them apart from the UI.
 */
public final class ConnectionProbe {

    public static final class Result {
        public boolean reachedServer;
        public int status;
        public String transport;
        public String detail;

        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("Transport: ").append(transport).append('\n');
            if (reachedServer) {
                sb.append("Result: reached the server (HTTP ").append(status).append(")\n\n");
                sb.append(status == 401 || status == 403
                        ? "TLS, DNS and HTTP all work. The 401 is expected here: "
                          + "this probe deliberately sends no valid key."
                        : "TLS, DNS and HTTP all work.");
            } else {
                sb.append("Result: could not reach the server.\n\n").append(detail);
            }
            return sb.toString();
        }
    }

    private ConnectionProbe() {
    }

    public static Result run(Context context, Prefs prefs, Provider provider) {
        Result result = new Result();
        result.transport = prefsUnderLegacyTls(prefs)
                ? "bundled TLS 1.2 (Bouncy Castle)"
                : "platform TLS";

        // Reuse the provider's own URL and header builders rather than
        // hand-rolling a request here, so the probe exercises exactly the code
        // path a real message would take.
        ChatRequest request = new ChatRequest();
        request.model = provider.defaultModel();
        request.maxTokens = 1;
        request.messages.add(ChatMessage.user("ping"));

        String url;
        Map<String, String> headers;
        String body;
        try {
            url = provider.url(prefs.baseUrl(provider), request);
            headers = provider.headers("probe-invalid-key");
            body = provider.body(request);
        } catch (Exception e) {
            result.reachedServer = false;
            result.detail = "Could not build a request: " + e;
            return result;
        }

        Conn conn = null;
        try {
            conn = TransportFactory.create(context, prefs)
                    .open("POST", url, headers, body.getBytes("UTF-8"));
            result.status = conn.status();
            result.reachedServer = true;
            drain(conn.body());
        } catch (Throwable t) {
            result.reachedServer = false;
            // Log the full trace as well as the summary: on a device this old
            // the class name is often the only clue about what went wrong.
            android.util.Log.e("1AI", "connection probe failed", t);
            result.detail = describeFailure(t);
        } finally {
            if (conn != null) {
                conn.close();
            }
        }
        return result;
    }

    private static boolean prefsUnderLegacyTls(Prefs prefs) {
        String mode = prefs.legacyTls();
        if (Prefs.TLS_ALWAYS.equals(mode)) {
            return true;
        }
        if (Prefs.TLS_NEVER.equals(mode)) {
            return false;
        }
        return TransportFactory.platformTlsIsInsufficient();
    }

    private static void drain(InputStream in) {
        if (in == null) {
            return;
        }
        try {
            byte[] buffer = new byte[2048];
            while (in.read(buffer) > 0) {
                // Discard: we only care that the response arrived at all.
            }
        } catch (Exception ignored) {
            // A partially readable error body is still a successful probe.
        }
    }

    private static String describeFailure(Throwable t) {
        // Some exceptions -- NullPointerException above all -- carry no
        // message at all, so the class name is what actually identifies them.
        String name = t.getClass().getName();
        String message = t.getMessage() == null || t.getMessage().length() == 0
                ? name
                : name + ": " + t.getMessage();
        String lower = message.toLowerCase();

        if (lower.contains("not trusted") || lower.contains("certificate")) {
            return "The TLS handshake completed but the certificate was rejected: "
                    + message + "\n\nIf this is a corporate network, its proxy certificate "
                    + "is not in the trust store.";
        }
        if (lower.contains("not valid for")) {
            return "The certificate presented does not match the hostname: " + message;
        }
        if (lower.contains("protocol") || lower.contains("handshake") || lower.contains("ssl")) {
            return "The TLS handshake failed: " + message
                    + "\n\nThis usually means the network is intercepting HTTPS.";
        }
        if (lower.contains("unknownhost") || lower.contains("unable to resolve")) {
            return "Could not resolve the hostname. Check the device's network connection.";
        }
        if (lower.contains("timed out") || lower.contains("timeout")) {
            return "The connection timed out. The network may be blocking this host.";
        }
        return message;
    }
}
