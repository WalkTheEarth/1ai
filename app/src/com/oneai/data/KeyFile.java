package com.oneai.data;

import com.oneai.provider.CompatibleEndpoints;
import com.oneai.provider.Provider;
import com.oneai.provider.Providers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads API keys out of a plain text file.
 *
 * <p>Typing a 60-character key on a BlackBerry keyboard is miserable, and
 * getting it wrong is silent until a request fails. Dropping a text file on the
 * device and importing it is far kinder:
 *
 * <pre>
 *   # 1ai-keys.txt
 *   DEEPSEEK=[sk-...]
 *   GOOGLE=[AIza...]
 *   CLAUDE/ANTHROPIC=[sk-ant-...]
 *
 *   BASE_URL_DEEPSEEK=https://api.deepseek.com/v1
 *   MODEL_GOOGLE=gemini-3.8-flash
 * </pre>
 *
 * <p>Names are matched loosely: case, spaces, underscores and hyphens are
 * ignored, several aliases are accepted for the well-known services, and a
 * slash-separated name matches on either half so "CLAUDE/ANTHROPIC" works as
 * written. Surrounding brackets or quotes are stripped, because they are how
 * people write placeholders and often get typed literally.
 *
 * <p>Pure Java and free of Android types, so the parsing rules are tested
 * directly rather than discovered from a device.
 */
public final class KeyFile {

    /** Provider id to key, in file order. */
    public final Map<String, String> apiKeys = new LinkedHashMap<String, String>();
    /** Provider id to base URL override. */
    public final Map<String, String> baseUrls = new LinkedHashMap<String, String>();
    /** Provider id to model override. */
    public final Map<String, String> models = new LinkedHashMap<String, String>();
    /** Names that looked like settings but matched no provider. */
    public final List<String> unknown = new ArrayList<String>();
    /** Lines that could not be parsed at all, as "line N: ...". */
    public final List<String> problems = new ArrayList<String>();
    /** Number of non-blank, non-comment lines seen. */
    public int entries;

    public boolean isEmpty() {
        return apiKeys.isEmpty() && baseUrls.isEmpty() && models.isEmpty();
    }

    /** "3 API keys, 1 endpoint" — for the confirmation dialog. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        if (!apiKeys.isEmpty()) {
            sb.append(apiKeys.size()).append(apiKeys.size() == 1 ? " API key" : " API keys");
        }
        if (!baseUrls.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(baseUrls.size()).append(baseUrls.size() == 1 ? " endpoint" : " endpoints");
        }
        if (!models.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(models.size()).append(models.size() == 1 ? " model" : " models");
        }
        return sb.length() == 0 ? "nothing" : sb.toString();
    }

    /** Which provider ids were found, for the confirmation dialog. */
    public List<String> providerNames() {
        List<String> names = new ArrayList<String>();
        for (String id : apiKeys.keySet()) {
            Provider p = Providers.byId(id);
            String name = p != null ? p.displayName() : id;
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    public static KeyFile parse(String text) {
        KeyFile out = new KeyFile();
        if (text == null) {
            return out;
        }

        // A BOM survives most editors and would otherwise make the first name
        // unrecognisable for reasons nobody could see.
        if (text.length() > 0 && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }

        String[] lines = text.split("\r\n|\n|\r", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() == 0 || line.charAt(0) == '#' || line.charAt(0) == ';') {
                continue;
            }

            int eq = line.indexOf('=');
            if (eq <= 0) {
                out.problems.add("line " + (i + 1) + ": no '=' in " + shorten(line));
                continue;
            }

            String rawName = line.substring(0, eq).trim();
            String value = unquote(line.substring(eq + 1).trim());
            if (rawName.length() == 0) {
                out.problems.add("line " + (i + 1) + ": empty name");
                continue;
            }
            out.entries++;

            // MODEL_ and BASE_URL_ prefixes are settings rather than keys, so
            // the same file can carry a whole configuration.
            String kind = "key";
            String name = rawName;
            String upper = rawName.toUpperCase();
            if (upper.startsWith("BASE_URL_") || upper.startsWith("BASEURL_")
                    || upper.startsWith("URL_")) {
                kind = "url";
                name = rawName.substring(rawName.indexOf('_') + 1);
                if (name.toUpperCase().startsWith("URL_")) {
                    name = name.substring(4);
                }
            } else if (upper.startsWith("MODEL_")) {
                kind = "model";
                name = rawName.substring("MODEL_".length());
            }

            String id = resolveProviderId(name);
            if (id == null) {
                // A key line with an unrecognised name is worth reporting; an
                // empty value on a known name is just a placeholder.
                out.unknown.add(rawName);
                continue;
            }
            if (value.length() == 0) {
                out.problems.add("line " + (i + 1) + ": " + rawName + " has no value");
                continue;
            }

            if ("url".equals(kind)) {
                out.baseUrls.put(id, value);
            } else if ("model".equals(kind)) {
                out.models.put(id, value);
            } else {
                out.apiKeys.put(id, value);
            }
        }
        return out;
    }

    /**
     * Maps a written name onto a provider id, generously.
     *
     * <p>The file is written by hand, so "Claude", "CLAUDE/ANTHROPIC",
     * "claude_anthropic" and "anthropic" should all land in the same place.
     */
    static String resolveProviderId(String rawName) {
        if (rawName == null) {
            return null;
        }
        // Try the whole name, then each part of a compound one. People write
        // the same thing many ways -- "CLAUDE/ANTHROPIC", "CLAUDE_ANTHROPIC",
        // "Claude Anthropic" -- and all of them should land in the same place.
        // Splitting first is what makes the underscore form work, which
        // normalising alone would not: CLAUDE_ANTHROPIC becomes the single
        // token CLAUDEANTHROPIC and matches nothing.
        String[] candidates = rawName.split("[/|,_\\-\\s]+");
        for (int i = 0; i < candidates.length; i++) {
            String id = lookup(candidates[i]);
            if (id != null) {
                return id;
            }
        }
        return lookup(rawName);
    }

    private static String lookup(String name) {
        String norm = normalise(name);
        if (norm.length() == 0) {
            return null;
        }

        String alias = aliasFor(norm);
        if (alias != null) {
            return alias;
        }

        // Fall back to a provider id, so every compatible endpoint works
        // without needing its own alias.
        String lowered = norm.toLowerCase();
        if (Providers.byId(lowered) != null) {
            return lowered;
        }
        CompatibleEndpoints.Endpoint endpoint = CompatibleEndpoints.byId(lowered);
        return endpoint != null ? endpoint.id : null;
    }

    private static String aliasFor(String norm) {
        if ("ANTHROPIC".equals(norm) || "CLAUDE".equals(norm)) {
            return "anthropic";
        }
        if ("OPENAI".equals(norm) || "GPT".equals(norm) || "CHATGPT".equals(norm)) {
            return "openai";
        }
        if ("GOOGLE".equals(norm) || "GEMINI".equals(norm) || "GOOGLEAI".equals(norm)) {
            return "google";
        }
        if ("DEEPSEEK".equals(norm)) {
            return "deepseek";
        }
        if ("XAI".equals(norm) || "GROK".equals(norm)) {
            return "xai";
        }
        if ("META".equals(norm) || "LLAMA".equals(norm)) {
            return "meta";
        }
        if ("LMSTUDIO".equals(norm) || "LMSTUDIO".equals(norm)) {
            return "lmstudio";
        }
        return null;
    }

    /** Uppercase, letters and digits only: "Claude / Anthropic" -> "CLAUDEANTHROPIC". */
    private static String normalise(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toUpperCase(c));
            }
        }
        return sb.toString();
    }

    /**
     * Strips surrounding brackets and quotes.
     *
     * <p>Documentation writes placeholders as {@code [key]}, and people paste
     * them literally. Silently sending "[sk-abc...]" as a key produces a 401
     * that looks like a wrong key rather than a formatting mistake.
     */
    static String unquote(String value) {
        String v = value.trim();
        while (v.length() >= 2) {
            char first = v.charAt(0);
            char last = v.charAt(v.length() - 1);
            if ((first == '[' && last == ']')
                    || (first == '"' && last == '"')
                    || (first == '\'' && last == '\'')
                    || (first == '<' && last == '>')) {
                v = v.substring(1, v.length() - 1).trim();
            } else {
                break;
            }
        }
        return v;
    }

    private static String shorten(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "…";
    }
}
