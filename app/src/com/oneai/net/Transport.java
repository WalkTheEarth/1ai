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
}
