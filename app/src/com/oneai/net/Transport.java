package com.oneai.net;

import java.io.IOException;
import java.util.Map;

/**
 * Opens HTTP requests. Swapped out at runtime depending on what the device's
 * TLS stack can actually do, and swapped out again in tests.
 */
public interface Transport {

    Conn open(String method, String url, Map<String, String> headers, byte[] body)
            throws IOException;

    /**
     * Human-readable name, for diagnostics.
     *
     * <p>So a report can say which stack actually carried a request rather than
     * which one was configured. Those differ whenever a fallback engages, and
     * conflating them makes a diagnostic actively misleading.
     */
    String name();
}
