package com.oneai.util;

import android.text.SpannableStringBuilder;
import android.text.Spannable;
import android.text.style.BackgroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.graphics.Typeface;

/**
 * A small Markdown renderer for model output.
 *
 * <p>Written by hand rather than pulled in as a library for two reasons: every
 * Markdown library worth using needs a WebView or AndroidX, and neither exists
 * on the Android versions this app targets.
 *
 * <p>Only spans present at API 1 are used -- StyleSpan, TypefaceSpan,
 * BackgroundColorSpan, RelativeSizeSpan -- so the same code path runs on 1.6
 * and on 14. Nothing here parses HTML or executes anything; the output is a
 * {@link CharSequence} of styled text and nothing else.
 *
 * <p>This is deliberately run once, when a stream finishes, never per token.
 * Re-parsing on every delta would be ruinous on the hardware this targets.
 */
public final class Markdown {

    private static final int CODE_BG = 0x33ffffff;
    private static final int QUOTE_COLOR = 0xff9e9e9e;

    private Markdown() {
    }

    public static CharSequence render(String source) {
        if (source == null || source.length() == 0) {
            return "";
        }
        SpannableStringBuilder out = new SpannableStringBuilder();
        String[] lines = source.split("\n", -1);

        boolean inFence = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.startsWith("```")) {
                inFence = !inFence;
                continue;
            }

            if (inFence) {
                int start = out.length();
                out.append(line);
                out.setSpan(new TypefaceSpan("monospace"), start, out.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new BackgroundColorSpan(CODE_BG), start, out.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (trimmed.length() == 0) {
                // Collapse runs of blank lines so output does not drift apart.
                if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
                    out.append('\n');
                }
            } else if (isRule(trimmed)) {
                out.append("────────────");
            } else if (trimmed.charAt(0) == '#') {
                int level = 0;
                while (level < trimmed.length() && trimmed.charAt(level) == '#') {
                    level++;
                }
                String text = trimmed.substring(level).trim();
                int start = out.length();
                appendInline(out, text);
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new RelativeSizeSpan(level <= 1 ? 1.25f : 1.12f), start, out.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (isBullet(trimmed)) {
                out.append("•  ");
                appendInline(out, trimmed.substring(1).trim());
            } else if (trimmed.charAt(0) == '>') {
                int start = out.length();
                out.append("  ");
                appendInline(out, trimmed.substring(1).trim());
                out.setSpan(new StyleSpan(Typeface.ITALIC), start, out.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new android.text.style.ForegroundColorSpan(QUOTE_COLOR),
                        start, out.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                appendInline(out, line);
            }

            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return out;
    }

    private static boolean isRule(String s) {
        if (s.length() < 3) {
            return false;
        }
        char c = s.charAt(0);
        if (c != '-' && c != '*' && c != '_') {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) != c) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBullet(String s) {
        if (s.length() < 2) {
            return false;
        }
        char c = s.charAt(0);
        return (c == '-' || c == '*' || c == '+') && s.charAt(1) == ' ';
    }

    /**
     * Applies inline spans: {@code code}, **bold**, *italic*, and their
     * underscore equivalents.
     */
    private static void appendInline(SpannableStringBuilder out, String text) {
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);

            if (c == '`') {
                int end = text.indexOf('`', i + 1);
                if (end > i) {
                    int start = out.length();
                    out.append(text, i + 1, end);
                    out.setSpan(new TypefaceSpan("monospace"), start, out.length(),
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new BackgroundColorSpan(CODE_BG), start, out.length(),
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }

            if (c == '*' || c == '_') {
                int run = 1;
                while (i + run < n && text.charAt(i + run) == c) {
                    run++;
                }
                int style = run >= 2 ? Typeface.BOLD : Typeface.ITALIC;
                String marker = run >= 2 ? (c == '*' ? "**" : "__") : String.valueOf(c);
                int end = text.indexOf(marker, i + marker.length());
                // Require the closing run to be preceded by non-space, so
                // "2 * 3 * 4" is not mistaken for emphasis.
                if (end > i && end > i + marker.length()
                        && !Character.isWhitespace(text.charAt(end - 1))) {
                    int start = out.length();
                    out.append(text, i + marker.length(), end);
                    out.setSpan(new StyleSpan(style), start, out.length(),
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + marker.length();
                    continue;
                }
            }

            out.append(c);
            i++;
        }
    }
}
