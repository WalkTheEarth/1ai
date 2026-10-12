package com.oneai.util;

import java.util.List;

/**
 * RFC 6125 hostname matching.
 *
 * <p>Bouncy Castle's lightweight TLS deliberately does not check that the
 * certificate it received actually belongs to the host we dialled -- that is
 * the caller's job, and getting it wrong is the difference between a secure
 * connection and one that accepts any valid certificate for any site, which is
 * no protection at all.
 *
 * <p>Kept free of Android types and of any certificate-parsing library so the
 * matching rules themselves can be unit-tested directly, including the
 * rejections.
 */
public final class Hostname {

    private Hostname() {
    }

    /**
     * True when {@code host} is identified by at least one of {@code patterns}.
     *
     * <p>An empty or null list returns false. Callers must pass certificate
     * names, and a certificate naming nobody identifies nobody.
     */
    public static boolean matchesAny(String host, List<String> patterns) {
        if (host == null || patterns == null || patterns.isEmpty()) {
            return false;
        }
        for (int i = 0; i < patterns.size(); i++) {
            if (matches(host, patterns.get(i))) {
                return true;
            }
        }
        return false;
    }

    /** Matches one hostname against one certificate name, wildcards included. */
    public static boolean matches(String host, String pattern) {
        if (host == null || pattern == null) {
            return false;
        }
        String h = normalise(host);
        String p = normalise(pattern);
        if (h.length() == 0 || p.length() == 0) {
            return false;
        }

        int star = p.indexOf('*');
        if (star < 0) {
            return h.equals(p);
        }

        // A wildcard is only meaningful as the entire leftmost label. Anywhere
        // else -- "foo*.example.com", "a.*.example.com", "*.com" -- it is not a
        // rule we are willing to honour.
        if (star != 0 || p.indexOf('*', 1) >= 0) {
            return false;
        }
        String suffix = p.substring(1);
        if (!suffix.startsWith(".")) {
            return false;
        }
        // "*.com" would match every .com host on the internet, which no CA is
        // permitted to issue. Require at least two labels after the wildcard.
        if (suffix.indexOf('.', 1) < 0) {
            return false;
        }

        if (!h.endsWith(suffix)) {
            return false;
        }
        String label = h.substring(0, h.length() - suffix.length());
        // The wildcard stands for exactly one label, and that label must be
        // non-empty: "*.example.com" must not match "example.com".
        return label.length() > 0 && label.indexOf('.') < 0;
    }

    private static String normalise(String s) {
        String t = s.trim();
        // A trailing dot is a legal fully-qualified form; strip it so
        // "example.com." and "example.com" compare equal.
        while (t.endsWith(".")) {
            t = t.substring(0, t.length() - 1);
        }
        return t.toLowerCase();
    }
}
