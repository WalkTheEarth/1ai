package com.oneai.test;

import com.oneai.net.Conn;
import com.oneai.net.Transport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/** A Transport that replays a canned response and records what was sent. */
public final class MockTransport implements Transport {

    private final int status;
    private final String responseBody;
    private final String contentType;
    private final int drip;

    public String capturedUrl;
    public String capturedMethod;
    public String capturedBody;
    public Map<String, String> capturedHeaders;

    public MockTransport(int status, String responseBody) {
        this(status, responseBody, "text/event-stream", 0);
    }

    /**
     * @param drip when > 0, the body is handed back in chunks of at most this
     *             many bytes. Set to 1 to simulate the worst possible network
     *             fragmentation, which is how split UTF-8 sequences and
     *             split SSE frames get exercised for real.
     */
    public MockTransport(int status, String responseBody, String contentType, int drip) {
        this.status = status;
        this.responseBody = responseBody;
        this.contentType = contentType;
        this.drip = drip;
    }

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public Conn open(String method, String url, Map<String, String> headers, byte[] body)
            throws IOException {
        capturedMethod = method;
        capturedUrl = url;
        capturedHeaders = headers;
        capturedBody = body == null ? null : new String(body, "UTF-8");

        final byte[] bytes = responseBody.getBytes("UTF-8");
        final InputStream stream = drip > 0
                ? new DripStream(bytes, drip)
                : new ByteArrayInputStream(bytes);

        return new Conn() {
            private volatile boolean closed;

            @Override
            public int status() {
                return status;
            }

            @Override
            public String header(String name) {
                return "content-type".equalsIgnoreCase(name) ? contentType : null;
            }

            @Override
            public InputStream body() {
                return stream;
            }

            @Override
            public void close() {
                closed = true;
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // Nothing useful to do while closing.
                }
            }
        };
    }

    /** Returns at most {@code max} bytes per read, however much is asked for. */
    static final class DripStream extends InputStream {
        private final byte[] data;
        private final int max;
        private int pos;

        DripStream(byte[] data, int max) {
            this.data = data;
            this.max = max;
        }

        @Override
        public int read() {
            return pos < data.length ? data[pos++] & 0xff : -1;
        }

        @Override
        public int read(byte[] out, int off, int len) {
            if (pos >= data.length) {
                return -1;
            }
            int n = Math.min(Math.min(len, max), data.length - pos);
            System.arraycopy(data, pos, out, off, n);
            pos += n;
            return n;
        }
    }
}
