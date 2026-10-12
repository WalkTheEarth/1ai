package com.oneai.net.android;

import android.content.Context;

import com.oneai.net.Conn;
import com.oneai.net.Transport;

import org.bouncycastle.tls.Certificate;
import org.bouncycastle.tls.CertificateRequest;
import org.bouncycastle.tls.DefaultTlsClient;
import org.bouncycastle.tls.NameType;
import org.bouncycastle.tls.ServerName;
import org.bouncycastle.tls.TlsAuthentication;
import org.bouncycastle.tls.TlsClientProtocol;
import org.bouncycastle.tls.TlsCredentials;
import org.bouncycastle.tls.TlsExtensionsUtils;
import org.bouncycastle.tls.TlsServerCertificate;
import org.bouncycastle.tls.crypto.TlsCertificate;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import java.util.Vector;

/**
 * HTTPS over Bouncy Castle's pure-Java TLS, for devices that have no TLS 1.2.
 *
 * <p>Because we own the socket, we also own HTTP: this class writes the request
 * line and headers itself and parses the response head, including chunked
 * transfer-encoding. That is the price of not having {@code HttpsURLConnection}
 * available on these API levels.
 *
 * <p>Certificate validation is mandatory and happens inside the TLS handshake,
 * via {@link TlsAuthentication#notifyServerCertificate}. Throwing there aborts
 * the connection before a single byte of application data is exchanged.
 */
final class BcTlsTransportImpl implements Transport {

    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final String HTTP_VERSION = "HTTP/1.1";

    private final Context appContext;

    BcTlsTransportImpl(Context context) {
        this.appContext = context.getApplicationContext();
    }

    @Override
    public Conn open(String method, String url, Map<String, String> headers, byte[] body)
            throws IOException {
        URL parsed = new URL(url);
        if (!"https".equalsIgnoreCase(parsed.getProtocol())) {
            throw new IOException("the bundled TLS transport only speaks https");
        }
        String host = parsed.getHost();
        int port = parsed.getPort() > 0 ? parsed.getPort() : 443;
        String target = parsed.getFile();
        if (target == null || target.length() == 0) {
            target = "/";
        }

        Socket socket = new Socket();
        TlsClientProtocol protocol = null;
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            // No read timeout: a streaming response legitimately pauses between
            // tokens, and a timeout would abort healthy long generations.
            socket.setSoTimeout(0);

            protocol = new TlsClientProtocol(socket.getInputStream(), socket.getOutputStream());
            protocol.connect(new SecureClient(host, appContext));

            OutputStream out = protocol.getOutputStream();
            writeRequest(out, method, target, host, port, headers, body);
            out.flush();

            InputStream in = protocol.getInputStream();
            ResponseHead head = readHead(in);

            InputStream payload = head.chunked ? new ChunkedInputStream(in) : in;
            return new BcConn(socket, protocol, head, payload);
        } catch (IOException e) {
            closeQuietly(protocol, socket);
            throw e;
        } catch (RuntimeException e) {
            closeQuietly(protocol, socket);
            throw new IOException("TLS connection failed: " + e, e);
        }
    }

    // ------------------------------------------------------------- request

    private static void writeRequest(OutputStream out, String method, String target,
                                     String host, int port, Map<String, String> headers,
                                     byte[] body) throws IOException {
        StringBuilder sb = new StringBuilder(256);
        sb.append(method).append(' ').append(target).append(' ').append(HTTP_VERSION).append("\r\n");
        sb.append("Host: ").append(host);
        if (port != 443) {
            sb.append(':').append(port);
        }
        sb.append("\r\n");

        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
        }
        sb.append("Content-Length: ").append(body == null ? 0 : body.length).append("\r\n");
        // One request per connection. It also means an error body with no
        // Content-Length is delimited by the close, which we can read to EOF.
        sb.append("Connection: close\r\n");
        sb.append("\r\n");

        out.write(sb.toString().getBytes("US-ASCII"));
        if (body != null && body.length > 0) {
            out.write(body);
        }
    }

    // ------------------------------------------------------------ response

    private static final class ResponseHead {
        int status;
        boolean chunked;
        final Map<String, String> headers = new HashMap<String, String>();
    }

    private static ResponseHead readHead(InputStream in) throws IOException {
        String statusLine = readLine(in);
        if (statusLine == null) {
            throw new IOException("server closed the connection before responding");
        }

        ResponseHead head = new ResponseHead();
        int firstSpace = statusLine.indexOf(' ');
        int secondSpace = firstSpace < 0 ? -1 : statusLine.indexOf(' ', firstSpace + 1);
        String code = secondSpace < 0
                ? statusLine.substring(firstSpace + 1).trim()
                : statusLine.substring(firstSpace + 1, secondSpace);
        try {
            head.status = Integer.parseInt(code);
        } catch (NumberFormatException bad) {
            throw new IOException("malformed status line: " + statusLine);
        }

        String line;
        while ((line = readLine(in)) != null && line.length() > 0) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim().toLowerCase();
            String value = line.substring(colon + 1).trim();
            head.headers.put(name, value);
        }

        String transferEncoding = head.headers.get("transfer-encoding");
        head.chunked = transferEncoding != null
                && transferEncoding.toLowerCase().indexOf("chunked") >= 0;
        return head;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(64);
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

    // --------------------------------------------------------------- TLS

    /**
     * TLS client that sends SNI and refuses untrusted or misaddressed
     * certificates.
     *
     * <p>SNI is not optional here: these APIs sit behind CDNs that serve many
     * hosts from one address and will not present the right certificate
     * without it.
     */
    private static final class SecureClient extends DefaultTlsClient {

        private final String host;
        private final Context context;

        SecureClient(String host, Context context) {
            super(new BcTlsCrypto());
            this.host = host;
            this.context = context;
        }

        @Override
        public Hashtable getClientExtensions() throws IOException {
            Hashtable extensions =
                    TlsExtensionsUtils.ensureExtensionsInitialised(super.getClientExtensions());
            Vector names = new Vector();
            names.addElement(new ServerName(NameType.host_name, host.getBytes("US-ASCII")));
            TlsExtensionsUtils.addServerNameExtensionClient(extensions, names);
            return extensions;
        }

        @Override
        public TlsAuthentication getAuthentication() throws IOException {
            return new TlsAuthentication() {

                @Override
                public void notifyServerCertificate(TlsServerCertificate serverCertificate)
                        throws IOException {
                    Certificate cert = serverCertificate == null ? null
                            : serverCertificate.getCertificate();
                    if (cert == null || cert.isEmpty()) {
                        throw new IOException("server presented no certificate");
                    }

                    TlsCertificate[] list = cert.getCertificateList();
                    byte[][] chain = new byte[list.length][];
                    for (int i = 0; i < list.length; i++) {
                        chain[i] = list[i].getEncoded();
                    }

                    // Chain first, then name. Both must hold, and throwing here
                    // aborts the handshake before any data is sent or read.
                    try {
                        Certificates.verifyChain(
                                Certificates.trustManager(context), chain);
                    } catch (Exception untrusted) {
                        throw new IOException("certificate is not trusted: " + untrusted,
                                untrusted);
                    }
                    if (!Certificates.hostnameMatches(host, chain[0])) {
                        throw new IOException("certificate is not valid for " + host);
                    }
                }

                @Override
                public TlsCredentials getClientCredentials(CertificateRequest request) {
                    // No client certificates: these APIs authenticate with a
                    // bearer token or an API key header, not mutual TLS.
                    return null;
                }
            };
        }
    }

    // -------------------------------------------------------------- conn

    private static final class BcConn implements Conn {
        private final Socket socket;
        private final TlsClientProtocol protocol;
        private final ResponseHead head;
        private final InputStream payload;

        BcConn(Socket socket, TlsClientProtocol protocol, ResponseHead head, InputStream payload) {
            this.socket = socket;
            this.protocol = protocol;
            this.head = head;
            this.payload = payload;
        }

        @Override
        public int status() {
            return head.status;
        }

        @Override
        public String header(String name) {
            return name == null ? null : head.headers.get(name.toLowerCase());
        }

        @Override
        public InputStream body() {
            return payload;
        }

        @Override
        public void close() {
            // Closing the socket is what unblocks a thread parked in a read;
            // that is how the Stop button cancels a stream mid-flight.
            closeQuietly(protocol, socket);
        }
    }

    private static void closeQuietly(TlsClientProtocol protocol, Socket socket) {
        if (protocol != null) {
            try {
                protocol.close();
            } catch (Exception ignored) {
                // Already closing.
            }
        }
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {
                // Already closing.
            }
        }
    }
}
