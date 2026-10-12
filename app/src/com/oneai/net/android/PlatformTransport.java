package com.oneai.net.android;

import android.os.Build;

import com.oneai.net.Conn;
import com.oneai.net.Transport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Transport backed by the platform's own HTTPS stack.
 *
 * <p>This is the preferred path wherever it works, because it is the device's
 * own well-tested TLS implementation. It is only insufficient below API 16,
 * where TLS 1.2 either does not exist (below 16) or exists but is switched off
 * by default (16-19), and where the system trust store is too old to recognise
 * modern roots.
 */
public final class PlatformTransport implements Transport {

    private static final int CONNECT_TIMEOUT_MS = 20000;

    private final boolean forceTls12;

    public PlatformTransport() {
        // TLS 1.2 exists from API 16 but is not enabled by default until API
        // 20, so on 16-19 we have to turn it on explicitly or every request to
        // a modern endpoint fails during the handshake.
        this.forceTls12 = Build.VERSION.SDK_INT >= 16 && Build.VERSION.SDK_INT < 20;
    }

    @Override
    public Conn open(String method, String url, Map<String, String> headers, byte[] body)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();

        if (forceTls12 && c instanceof javax.net.ssl.HttpsURLConnection) {
            ((javax.net.ssl.HttpsURLConnection) c).setSSLSocketFactory(tls12Factory());
        }

        c.setRequestMethod(method);
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        // No read timeout: a streaming response legitimately sits idle between
        // tokens, and a read timeout would abort healthy long generations.
        c.setReadTimeout(0);
        c.setDoOutput(body != null);

        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                c.setRequestProperty(e.getKey(), e.getValue());
            }
        }

        if (body != null) {
            OutputStream out = c.getOutputStream();
            out.write(body);
            out.flush();
            out.close();
        }

        return new PlatformConn(c);
    }

    /** Wraps the platform factory so every socket gets TLS 1.2 switched on. */
    private static SSLSocketFactory tls12Factory() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, null, null);
            return new Tls12Factory(ctx.getSocketFactory());
        } catch (Exception e) {
            // If this fails we are no worse off than before: fall back to the
            // platform default and let the caller see a normal handshake error.
            return null;
        }
    }

    private static final class Tls12Factory extends SSLSocketFactory {
        private final SSLSocketFactory base;

        Tls12Factory(SSLSocketFactory base) {
            this.base = base;
        }

        /**
         * Enables TLS 1.2 only if the platform actually supports it, by
         * intersecting with getSupportedProtocols(). Asking for a protocol the
         * runtime has never heard of throws IllegalArgumentException, which
         * would turn a working connection into a crash.
         */
        private Socket enable(Socket s) {
            if (s instanceof SSLSocket) {
                SSLSocket ssl = (SSLSocket) s;
                String[] supported = ssl.getSupportedProtocols();
                for (int i = 0; i < supported.length; i++) {
                    if ("TLSv1.2".equals(supported[i])) {
                        ssl.setEnabledProtocols(new String[]{"TLSv1.2"});
                        break;
                    }
                }
            }
            return s;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return base.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return base.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose)
                throws IOException {
            return enable(base.createSocket(s, host, port, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return enable(base.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress local, int localPort)
                throws IOException {
            return enable(base.createSocket(host, port, local, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return enable(base.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress local, int localPort)
                throws IOException {
            return enable(base.createSocket(address, port, local, localPort));
        }
    }

    // ------------------------------------------------------------------

    private static final class PlatformConn implements Conn {
        private final HttpURLConnection conn;
        private volatile InputStream stream;

        PlatformConn(HttpURLConnection conn) {
            this.conn = conn;
        }

        @Override
        public int status() throws IOException {
            return conn.getResponseCode();
        }

        @Override
        public String header(String name) {
            return conn.getHeaderField(name);
        }

        @Override
        public InputStream body() throws IOException {
            InputStream in = conn.getResponseCode() >= 400 ? conn.getErrorStream() : conn.getInputStream();
            stream = in != null ? in : new ByteArrayInputStream(new byte[0]);
            return stream;
        }

        @Override
        public void close() {
            // Closing the stream is what actually unblocks a thread parked in
            // a read; disconnect() alone is unreliable for that, and the Stop
            // button depends on this working.
            InputStream in = stream;
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // Already closing; nothing useful to do.
                }
            }
            conn.disconnect();
        }
    }
}
