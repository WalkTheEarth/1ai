package com.oneai.net.android;

import android.content.Context;

import com.oneai.R;
import com.oneai.util.Hostname;
import com.oneai.util.Pem;

import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.Certificate;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Certificate trust for the bundled TLS stack.
 *
 * <p>Two problems have to be solved together on old devices. First, Bouncy
 * Castle verifies nothing on its own -- the caller must validate the chain.
 * Second, even a correct chain check fails on an Android 2.x or 4.x device,
 * because its system trust store predates the roots that today's endpoints
 * actually use (Let's Encrypt's ISRG Root X1 only shipped in Android 7.1, for
 * instance). So we validate against the device's own store <em>and</em> against
 * a current Mozilla bundle shipped in the APK.
 *
 * <p>Verification is never disabled. There is no "trust all" path here, and no
 * setting that turns one on.
 */
public final class Certificates {

    private static volatile X509TrustManager trust;

    private Certificates() {
    }

    /** The composite trust manager, built once per process. */
    public static X509TrustManager trustManager(Context context) throws Exception {
        X509TrustManager existing = trust;
        if (existing != null) {
            return existing;
        }
        synchronized (Certificates.class) {
            if (trust == null) {
                trust = build(context);
            }
            return trust;
        }
    }

    private static X509TrustManager build(Context context) throws Exception {
        X509TrustManager platform = systemTrustManager();
        X509TrustManager bundled = bundleTrustManager(context);
        if (bundled == null) {
            return platform;
        }
        if (platform == null) {
            return bundled;
        }
        return new EitherTrustManager(platform, bundled);
    }

    private static X509TrustManager systemTrustManager() {
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            return firstX509(tmf);
        } catch (Exception e) {
            return null;
        }
    }

    private static X509TrustManager bundleTrustManager(Context context) throws Exception {
        String pem = new String(readAll(context.getResources()
                .openRawResource(R.raw.cacert)), "UTF-8");
        List<byte[]> ders = Pem.certificates(pem);
        if (ders.isEmpty()) {
            return null;
        }

        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);

        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        for (int i = 0; i < ders.size(); i++) {
            X509Certificate cert = (X509Certificate) factory.generateCertificate(
                    new ByteArrayInputStream(ders.get(i)));
            store.setCertificateEntry("ca" + i, cert);
        }

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(store);
        return firstX509(tmf);
    }

    private static X509TrustManager firstX509(TrustManagerFactory tmf) {
        TrustManager[] managers = tmf.getTrustManagers();
        for (int i = 0; i < managers.length; i++) {
            if (managers[i] instanceof X509TrustManager) {
                return (X509TrustManager) managers[i];
            }
        }
        return null;
    }

    /**
     * The DNS names a certificate is valid for.
     *
     * <p>Subject Alternative Name wins outright when present: RFC 6125 requires
     * the legacy Common Name to be ignored once a SAN exists, and every
     * certificate issued in the last decade has one.
     */
    public static List<String> dnsNames(byte[] der) {
        List<String> names = new ArrayList<String>();
        try {
            Certificate cert = Certificate.getInstance(der);
            Extensions extensions = cert.getTBSCertificate().getExtensions();

            if (extensions != null) {
                GeneralNames san = GeneralNames.fromExtensions(
                        extensions, Extension.subjectAlternativeName);
                if (san != null) {
                    GeneralName[] all = san.getNames();
                    for (int i = 0; i < all.length; i++) {
                        if (all[i].getTagNo() == GeneralName.dNSName) {
                            names.add(all[i].getName().toString());
                        }
                    }
                    if (!names.isEmpty()) {
                        return names;
                    }
                }
            }

            X500Name subject = X500Name.getInstance(cert.getTBSCertificate().getSubject());
            RDN[] cns = subject.getRDNs(BCStyle.CN);
            for (int i = 0; i < cns.length; i++) {
                if (cns[i].getFirst() != null) {
                    names.add(cns[i].getFirst().toString());
                }
            }
        } catch (Exception unparseable) {
            // Return whatever was collected; an unparseable certificate then
            // fails the hostname check, which is the safe direction.
        }
        return names;
    }

    /** Validates a peer chain against the bundle, the device store, or both. */
    public static void verifyChain(X509TrustManager manager, byte[][] chain)
            throws CertificateException {
        if (chain == null || chain.length == 0) {
            throw new CertificateException("server presented no certificate");
        }
        X509Certificate[] certs = new X509Certificate[chain.length];
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            for (int i = 0; i < chain.length; i++) {
                certs[i] = (X509Certificate) factory.generateCertificate(
                        new ByteArrayInputStream(chain[i]));
            }
        } catch (Exception e) {
            throw new CertificateException("could not parse server certificate: " + e);
        }
        manager.checkServerTrusted(certs, "RSA");
    }

    static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(256 * 1024);
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // Nothing useful to do while closing.
            }
        }
    }

    /**
     * Accepts a chain if either underlying manager does. Both are genuine
     * validators over genuine trust anchors, so this widens which roots are
     * accepted without weakening how a chain is checked.
     */
    private static final class EitherTrustManager implements X509TrustManager {
        private final X509TrustManager a;
        private final X509TrustManager b;

        EitherTrustManager(X509TrustManager a, X509TrustManager b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            a.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            try {
                a.checkServerTrusted(chain, authType);
            } catch (CertificateException first) {
                b.checkServerTrusted(chain, authType);
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            X509Certificate[] x = a.getAcceptedIssuers();
            X509Certificate[] y = b.getAcceptedIssuers();
            X509Certificate[] all = new X509Certificate[x.length + y.length];
            System.arraycopy(x, 0, all, 0, x.length);
            System.arraycopy(y, 0, all, x.length, y.length);
            return all;
        }
    }

    /** Convenience for callers that only have a hostname and a DER chain. */
    public static boolean hostnameMatches(String host, byte[] leafDer) {
        return Hostname.matchesAny(host, dnsNames(leafDer));
    }
}
