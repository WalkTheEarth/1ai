package com.oneai.net;

import java.io.IOException;
import java.io.InputStream;

/**
 * One open HTTP response.
 *
 * <p>Deliberately tiny, and deliberately free of any Android type, so the whole
 * provider layer above it can be exercised in plain JVM unit tests. The two
 * implementations are {@code PlatformConn} (HttpsURLConnection) and the
 * Bouncy Castle one used on devices too old to do TLS 1.2.
 */
public interface Conn {

    /** HTTP status code. */
    int status() throws IOException;

    /** Response header, or null. Names are matched case-insensitively. */
    String header(String name);

    /** The body, or the error body when the status is >= 400. */
    InputStream body() throws IOException;

    /**
     * Aborts the request and unblocks any thread currently reading
     * {@link #body()}. Must be safe to call from another thread while a read is
     * in flight, and safe to call more than once -- this is how the Stop button
     * cancels a stream that is blocked waiting on the network.
     */
    void close();
}
