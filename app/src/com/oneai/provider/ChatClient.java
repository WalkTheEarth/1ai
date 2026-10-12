package com.oneai.provider;

import com.oneai.net.Conn;
import com.oneai.net.SseReader;
import com.oneai.net.Transport;

import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs one streaming request: open, check status, decode SSE, dispatch.
 *
 * <p>Knows nothing about which provider it is talking to beyond the {@link Provider}
 * it is handed, and nothing about HTTP beyond the {@link Transport} interface.
 */
public final class ChatClient {

    private final Transport transport;

    public ChatClient(Transport transport) {
        this.transport = transport;
    }

    /**
     * Handle for cancelling an in-flight stream from another thread.
     *
     * <p>Cancellation works by closing the connection, which unblocks the
     * reading thread with an IOException. Interrupting the thread would not
     * help: a thread parked in a socket read does not respond to interrupts.
     */
    public static final class Session {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile Conn conn;

        void attach(Conn c) {
            this.conn = c;
            if (c != null && cancelled.get()) {
                c.close();
            }
        }

        public void cancel() {
            cancelled.set(true);
            Conn c = conn;
            if (c != null) {
                c.close();
            }
        }

        public boolean isCancelled() {
            return cancelled.get();
        }
    }

    /** Streams a response, invoking {@code listener} as content arrives. */
    public void stream(Provider provider, String apiKey, String baseUrl,
                       ChatRequest request, final StreamListener listener,
                       Session session) throws ProviderException, IOException {

        if (session.isCancelled()) {
            return;
        }

        byte[] payload;
        try {
            payload = provider.body(request).getBytes("UTF-8");
        } catch (JSONException e) {
            // Nothing was sent, so this is a local bug rather than a server
            // complaint -- say so plainly instead of blaming the network.
            throw new ProviderException(ProviderException.NO_STATUS, null,
                    String.valueOf(e.getMessage()),
                    "Could not build the request.", false);
        }

        Conn conn = transport.open("POST", provider.url(baseUrl, request),
                provider.headers(apiKey), payload);
        session.attach(conn);

        if (session.isCancelled()) {
            return;
        }

        try {
            int status = conn.status();
            InputStream stream = conn.body();

            if (status >= 400) {
                throw provider.error(status, readAll(stream));
            }

            final Provider.StreamParser parser = provider.newParser(listener);

            new SseReader(stream).read(new SseReader.Handler() {
                @Override
                public void onEvent(String event, String data) throws IOException {
                    try {
                        parser.onEvent(event, data);
                    } catch (JSONException bad) {
                        // A frame we cannot parse is dropped rather than
                        // killing the stream: whatever is left is still worth
                        // delivering, and a truncated tail is common when a
                        // connection is cut mid-write.
                        if (session.isCancelled()) {
                            throw new IOException("cancelled");
                        }
                    }
                }
            });

            // Only reached if the stream ended without a terminator, which is
            // the normal case for Google.
            parser.onEnd();

        } catch (IOException e) {
            if (session.isCancelled()) {
                return;
            }
            // A provider error raised mid-stream travels wrapped, because the
            // SSE reader's contract is IOException. Unwrap it so the caller
            // sees the real message.
            Throwable cause = e.getCause();
            if (cause instanceof ProviderException) {
                throw (ProviderException) cause;
            }
            throw ProviderException.transport(String.valueOf(e.getMessage()), true);
        } finally {
            session.attach(null);
            conn.close();
        }
    }

    private static String readAll(InputStream in) {
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), "UTF-8");
        } catch (IOException e) {
            return "";
        }
    }
}
