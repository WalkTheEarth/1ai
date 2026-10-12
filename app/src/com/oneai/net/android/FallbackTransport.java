package com.oneai.net.android;

import com.oneai.net.Conn;
import com.oneai.net.Transport;

import java.io.IOException;
import java.util.Map;

/**
 * Tries one transport, and quietly retries with another if the TLS handshake
 * fails.
 *
 * <p>The platform stack is preferable where it works: it is the device's own
 * well-tested implementation and costs nothing in APK size. But "where it
 * works" is narrower than it looks. Android 4.1-4.4 supports TLS 1.2 yet leaves
 * it disabled, and both the API for switching it on and the cipher suites
 * available vary by release and vendor. Meanwhile the other side keeps moving:
 * DeepSeek, for instance, now refuses TLS 1.0 and 1.1 outright, so a phone
 * offering only those cannot reach it however correct the app is.
 *
 * <p>Rather than predict which combination works, this tries the platform first
 * and falls back on a handshake failure. The bundled TLS is pure Java and has
 * no opinion about the device's OpenSSL build, so it closes the gap.
 *
 * <p>Only TLS-level failures trigger the retry. A 401 or a 404 means the
 * connection worked and the server answered, so retrying would just repeat a
 * pointless request.
 */
public final class FallbackTransport implements Transport {

    private final Transport primary;
    private final Transport fallback;

    /** Whether the most recent request needed the fallback. */
    private volatile boolean usedFallback;

    public FallbackTransport(Transport primary, Transport fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public String name() {
        if (usedFallback && fallback != null) {
            return fallback.name();
        }
        return primary.name();
    }

    /** True when the last request only succeeded by falling back. */
    public boolean usedFallback() {
        return usedFallback;
    }

    @Override
    public Conn open(String method, String url, Map<String, String> headers, byte[] body)
            throws IOException {
        usedFallback = false;
        try {
            return primary.open(method, url, headers, body);
        } catch (IOException e) {
            if (fallback == null || !isTlsFailure(e)) {
                throw e;
            }
            android.util.Log.i("1AI", "platform TLS failed ("
                    + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + "); retrying with the bundled TLS stack");
            try {
                Conn conn = fallback.open(method, url, headers, body);
                usedFallback = true;
                return conn;
            } catch (IOException fallbackFailure) {
                // Report both. The platform error is usually the informative
                // one, and losing it would send someone debugging the wrong
                // half of the problem.
                throw new IOException("platform TLS failed (" + e.getMessage()
                        + "), and the bundled TLS also failed ("
                        + fallbackFailure.getMessage() + ")", fallbackFailure);
            }
        }
    }

    /** Delegates to the pure-Java rule, which is unit tested. */
    static boolean isTlsFailure(Throwable t) {
        return com.oneai.net.TlsFailures.isTransportFailure(t);
    }

}
