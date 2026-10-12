package com.oneai.net;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Minimal Server-Sent Events reader, shared by all three providers.
 *
 * <p>The three APIs frame their streams differently enough that naive line
 * splitting breaks on all of them:
 *
 * <ul>
 *   <li>OpenAI emits comment lines ({@code ": ..."}) that must be skipped.</li>
 *   <li>Anthropic names events with {@code event:} lines and also repeats the
 *       type inside the JSON.</li>
 *   <li>Google sends no terminator at all; EOF is the end of the stream.</li>
 * </ul>
 *
 * <p>Reading goes through an {@link InputStreamReader} pinned to UTF-8, which
 * matters more than it looks: a multi-byte character can be split across two
 * TCP reads, and decoding each read independently would corrupt it. The reader
 * buffers the partial sequence correctly, so text arrives intact however the
 * network chops it up.
 */
public final class SseReader {

    /** Receives one fully-assembled event. {@code event} may be null. */
    public interface Handler {
        void onEvent(String event, String data) throws IOException;
    }

    private final BufferedReader in;

    public SseReader(InputStream stream) throws IOException {
        this.in = new BufferedReader(new InputStreamReader(stream, "UTF-8"), 8192);
    }

    /**
     * Reads until the stream ends, dispatching each event.
     *
     * <p>Returns normally at EOF. A stream that ends without an explicit
     * terminator is not an error -- Google relies on exactly that.
     */
    public void read(Handler handler) throws IOException {
        String event = null;
        StringBuilder data = null;

        String line;
        while ((line = in.readLine()) != null) {
            if (line.length() == 0) {
                // Blank line: dispatch whatever has accumulated.
                if (data != null) {
                    handler.onEvent(event, data.toString());
                    event = null;
                    data = null;
                }
                continue;
            }

            // Comment. OpenAI interleaves these; they carry no payload.
            if (line.charAt(0) == ':') {
                continue;
            }

            int colon = line.indexOf(':');
            String field, value;
            if (colon < 0) {
                field = line;
                value = "";
            } else {
                field = line.substring(0, colon);
                value = line.substring(colon + 1);
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
            }

            if ("data".equals(field)) {
                if (data == null) {
                    data = new StringBuilder();
                } else {
                    // The SSE spec joins repeated data: fields with newlines.
                    data.append('\n');
                }
                data.append(value);
            } else if ("event".equals(field)) {
                event = value;
            }
            // "id" and "retry" are ignored: none of these APIs use them for
            // resumption and we never reconnect mid-stream.
        }

        // Stream ended without a trailing blank line. Flush a pending event
        // rather than dropping it -- a truncated final frame is still worth
        // delivering, and Google's last chunk often arrives this way.
        if (data != null) {
            handler.onEvent(event, data.toString());
        }
    }
}
