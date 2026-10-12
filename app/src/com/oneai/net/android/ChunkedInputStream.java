package com.oneai.net.android;

import java.io.IOException;
import java.io.InputStream;

/**
 * Decodes HTTP/1.1 chunked transfer-encoding.
 *
 * <p>Needed only on the bundled-TLS path: {@code HttpsURLConnection} does this
 * for free, but with a raw socket we are the HTTP client and a streaming
 * response with no Content-Length arrives chunked. Feeding those length
 * prefixes and CRLFs straight into the SSE parser would corrupt every frame.
 */
final class ChunkedInputStream extends InputStream {

    private final InputStream in;
    private long remainingInChunk;
    private boolean finished;

    ChunkedInputStream(InputStream in) {
        this.in = in;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] out, int offset, int length) throws IOException {
        if (finished) {
            return -1;
        }
        if (remainingInChunk == 0 && !nextChunk()) {
            return -1;
        }
        int want = (int) Math.min(length, remainingInChunk);
        int n = in.read(out, offset, want);
        if (n < 0) {
            // The connection died mid-chunk. Report end of stream rather than
            // hanging; the caller sees a truncated response, not a freeze.
            finished = true;
            return -1;
        }
        remainingInChunk -= n;
        if (remainingInChunk == 0) {
            skipCrlf();
        }
        return n;
    }

    /** Reads the next chunk size line. Returns false at the terminating chunk. */
    private boolean nextChunk() throws IOException {
        String line = readLine();
        if (line == null) {
            finished = true;
            return false;
        }
        // A chunk-size line may carry extensions after a semicolon:
        // "1a;foo=bar". Only the size matters to us.
        int semicolon = line.indexOf(';');
        if (semicolon >= 0) {
            line = line.substring(0, semicolon);
        }
        line = line.trim();
        if (line.length() == 0) {
            return nextChunk();
        }

        long size;
        try {
            size = Long.parseLong(line, 16);
        } catch (NumberFormatException bad) {
            throw new IOException("malformed chunk size: " + line);
        }
        if (size == 0) {
            finished = true;
            // Consume the trailer section and the final blank line so the
            // socket is left in a clean state.
            String trailer;
            do {
                trailer = readLine();
            } while (trailer != null && trailer.length() > 0);
            return false;
        }
        remainingInChunk = size;
        return true;
    }

    private void skipCrlf() throws IOException {
        String line = readLine();
        if (line != null && line.length() != 0) {
            throw new IOException("expected CRLF after chunk, got: " + line);
        }
    }

    /** Reads one CRLF-terminated ASCII line, or null at end of stream. */
    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder(16);
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                int length = sb.length();
                if (length > 0 && sb.charAt(length - 1) == '\r') {
                    sb.setLength(length - 1);
                }
                return sb.toString();
            }
            sb.append((char) c);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
