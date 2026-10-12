package com.oneai.net;

import javax.net.ssl.SSLException;

/**
 * Decides whether a failure happened during the TLS handshake or after it.
 *
 * <p>This is the trigger for retrying a request on the other TLS stack, so it
 * has to be right in both directions. A false negative means the fallback
 * never engages and the user sees a failure the app could have avoided; a
 * false positive means retrying requests that were answered perfectly well,
 * doubling traffic and hiding the server's actual response.
 *
 * <p>Pure Java and free of Android types, so the decision itself is tested
 * rather than assumed.
 */
public final class TlsFailures {

    private TlsFailures() {
    }

    /**
     * True when a request failed because the connection could not be
     * established, rather than because the server answered.
     *
     * <p>The whole cause chain is walked: Android reports protocol problems
     * through several different classes depending on release, and they often
     * arrive wrapped in a plain IOException.
     */
    public static boolean isTransportFailure(Throwable t) {
        int guard = 0;
        for (Throwable c = t; c != null && guard++ < 16; c = c.getCause()) {
            if (c instanceof SSLException) {
                return true;
            }

            String name = c.getClass().getName();
            if (name.indexOf("SSL") >= 0 || name.indexOf("CertPath") >= 0
                    || name.indexOf("Certificate") >= 0) {
                return true;
            }

            String message = c.getMessage();
            if (message != null && looksLikeTls(message)) {
                return true;
            }

            // A self-referential cause chain would otherwise loop forever.
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }

    private static boolean looksLikeTls(String message) {
        String lower = message.toLowerCase();
        return lower.indexOf("ssl") >= 0
                || lower.indexOf("tls") >= 0
                || lower.indexOf("protocol") >= 0
                || lower.indexOf("handshake") >= 0
                || lower.indexOf("certificate") >= 0
                || lower.indexOf("trust anchor") >= 0
                || lower.indexOf("no cipher suites") >= 0;
    }
}
