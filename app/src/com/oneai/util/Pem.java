package com.oneai.util;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal PEM reader for X.509 certificates.
 *
 * <p>Pure Java, no Android dependencies, so it can be tested directly. Android
 * ships no PEM parser below API 8 ({@code android.util.Base64} does not exist
 * at all on the older targets), so this has to exist regardless.
 */
public final class Pem {

    private static final String BEGIN = "-----BEGIN CERTIFICATE-----";
    private static final String END = "-----END CERTIFICATE-----";

    private Pem() {
    }

    /** Decodes every certificate block in {@code pem}. */
    public static List<byte[]> certificates(String pem) {
        List<byte[]> out = new ArrayList<byte[]>();
        if (pem == null) {
            return out;
        }

        int from = 0;
        while (true) {
            int begin = pem.indexOf(BEGIN, from);
            if (begin < 0) {
                break;
            }
            int end = pem.indexOf(END, begin);
            if (end < 0) {
                break;
            }
            String body = pem.substring(begin + BEGIN.length(), end);
            byte[] der = decodeBase64(body);
            if (der != null && der.length > 0) {
                out.add(der);
            }
            from = end + END.length();
        }
        return out;
    }

    /** Standard Base64, ignoring all whitespace. Returns null on bad input. */
    public static byte[] decodeBase64(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(text.length() * 3 / 4);
        int buffer = 0;
        int bits = 0;
        try {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                // Skip anything that is not part of the alphabet. That covers
                // newlines, spaces, and the padding character.
                int value = valueOf(c);
                if (value < 0) {
                    continue;
                }
                buffer = (buffer << 6) | value;
                bits += 6;
                if (bits >= 8) {
                    bits -= 8;
                    out.write((buffer >> bits) & 0xff);
                }
            }
        } catch (RuntimeException bad) {
            return null;
        }
        return out.toByteArray();
    }

    /**
     * Base64 alphabet, or -1 for anything else. Kept as explicit arithmetic
     * rather than a lookup table so it is obviously correct at a glance.
     */
    private static int valueOf(char c) {
        if (c >= 'A' && c <= 'Z') {
            return c - 'A';
        }
        if (c >= 'a' && c <= 'z') {
            return c - 'a' + 26;
        }
        if (c >= '0' && c <= '9') {
            return c - '0' + 52;
        }
        if (c == '+') {
            return 62;
        }
        if (c == '/') {
            return 63;
        }
        return -1;
    }

    /** Encodes bytes as standard Base64. Used for tests and diagnostics. */
    public static String encodeBase64(byte[] data) {
        final char[] alphabet =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
        StringBuilder sb = new StringBuilder(((data.length + 2) / 3) * 4);
        int i = 0;
        while (i < data.length) {
            int b0 = data[i++] & 0xff;
            int b1 = i < data.length ? data[i++] & 0xff : -1;
            int b2 = i < data.length ? data[i++] & 0xff : -1;

            sb.append(alphabet[b0 >> 2]);
            sb.append(alphabet[((b0 & 0x03) << 4) | (b1 < 0 ? 0 : b1 >> 4)]);
            sb.append(b1 < 0 ? '=' : alphabet[((b1 & 0x0f) << 2) | (b2 < 0 ? 0 : b2 >> 6)]);
            sb.append(b2 < 0 ? '=' : alphabet[b2 & 0x3f]);
        }
        return sb.toString();
    }
}
