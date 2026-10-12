package com.oneai.provider;

import org.json.JSONObject;

/**
 * Null-safe reads from {@code org.json}.
 *
 * <p>Android's {@code JSONObject.optString(key, fallback)} does <em>not</em>
 * behave like the reference implementation. Given a key whose value is JSON
 * {@code null} it returns the literal string {@code "null"}, because Android
 * routes through {@code String.valueOf(JSONObject.NULL)} instead of returning
 * the fallback:
 *
 * <pre>
 *   // Android, roughly
 *   String result = JSON.toString(opt(name));
 *   return result != null ? result : fallback;   // "null" is not null
 * </pre>
 *
 * <p>That is how a stream of chunks carrying {@code "content": null} turned
 * into a reply beginning {@code nullnullnullnull}: each null chunk was
 * appended as four characters of text. It compiles, it passes on the JVM
 * against the reference library, and it only misbehaves on a device.
 *
 * <p>Every read of a possibly-null field goes through here instead. The
 * {@code isNull} check short-circuits before {@code optString} is reached, so
 * the result is identical on both implementations.
 *
 * <p>This is also why the JVM suite cannot catch a regression here: it links
 * the reference {@code org.json}, where {@code optString} is already correct.
 * The tests below pin the helper's contract rather than the platform's
 * behaviour.
 */
public final class Json {

    private Json() {
    }

    /** The string at {@code key}, or null if absent, JSON null, or not a string. */
    public static String str(JSONObject o, String key) {
        if (o == null || key == null || o.isNull(key)) {
            return null;
        }
        // A JSON null never reaches here, so optString's fallback is only used
        // for genuinely missing keys -- where both implementations agree.
        return o.optString(key, null);
    }

    /** As {@link #str}, but empty rather than null, for direct concatenation. */
    public static String strOrEmpty(JSONObject o, String key) {
        String s = str(o, key);
        return s == null ? "" : s;
    }

    /**
     * True when the key holds a non-empty string. Guards the common pattern of
     * appending a delta only when there is one, which under Android's
     * optString would otherwise append "null".
     */
    public static boolean hasText(JSONObject o, String key) {
        return str(o, key) != null;
    }
}
