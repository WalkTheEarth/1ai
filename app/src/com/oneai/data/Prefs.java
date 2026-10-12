package com.oneai.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.oneai.R;

import com.oneai.provider.Provider;
import com.oneai.provider.Providers;

/**
 * Settings and API keys.
 *
 * <p>Every write uses {@code commit()} rather than {@code apply()}. {@code apply()}
 * was only added in API 9 and would throw NoSuchMethodError on the Android 1.6
 * devices this app targets. Writes here happen on user action, never in a loop,
 * so the synchronous cost is irrelevant.
 *
 * <p>Keys are stored in plain app-private preferences. That is a deliberate,
 * documented choice rather than an oversight: the Android Keystore does not
 * exist below API 18, and the devices this targets are below it. The README
 * says so plainly instead of implying a protection that is not there.
 */
public final class Prefs {

    private static final String FILE = "oneai_settings";

    /** Legacy TLS modes. */
    public static final String TLS_AUTO = "auto";
    public static final String TLS_ALWAYS = "always";
    public static final String TLS_NEVER = "never";

    private final SharedPreferences sp;
    private final Context context;

    public Prefs(Context context) {
        this.context = context.getApplicationContext();
        this.sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------ providers

    public String defaultProvider() {
        return sp.getString("default_provider", Providers.DEFAULT);
    }

    public void setDefaultProvider(String id) {
        put("default_provider", id);
    }

    public String apiKey(String providerId) {
        String k = sp.getString("key_" + providerId, "");
        return k == null ? "" : k.trim();
    }

    public void setApiKey(String providerId, String key) {
        put("key_" + providerId, key == null ? "" : key.trim());
    }

    /** Null when the provider's own default should be used. */
    public String modelOverride(String providerId) {
        String m = sp.getString("model_" + providerId, "");
        return m == null || m.length() == 0 ? null : m;
    }

    public void setModelOverride(String providerId, String model) {
        put("model_" + providerId, model == null ? "" : model.trim());
    }

    public String baseUrl(Provider provider) {
        String u = sp.getString("base_" + provider.id(), "");
        return u == null || u.length() == 0 ? provider.defaultBaseUrl() : u.trim();
    }

    public void setBaseUrl(String providerId, String url) {
        put("base_" + providerId, url == null ? "" : url.trim());
    }

    /** Null when the provider's own default should be used. */
    public String effort(String providerId) {
        String e = sp.getString("effort_" + providerId, "");
        return e == null || e.length() == 0 ? null : e;
    }

    public void setEffort(String providerId, String effort) {
        put("effort_" + providerId, effort == null ? "" : effort);
    }

    /** Null when temperature should be omitted from the request entirely. */
    public Double temperature(String providerId) {
        String t = sp.getString("temp_" + providerId, "");
        if (t == null || t.length() == 0) {
            return null;
        }
        try {
            return Double.valueOf(t);
        } catch (NumberFormatException bad) {
            return null;
        }
    }

    public void setTemperature(String providerId, Double value) {
        put("temp_" + providerId, value == null ? "" : String.valueOf(value));
    }

    // --------------------------------------------------------------- global

    /**
     * The system prompt to send.
     *
     * <p>Falls back to a bundled default rather than to nothing. An empty
     * prompt is not neutral -- it leaves the model to guess at tone and length,
     * which on a small screen is usually the wrong guess -- so clearing the
     * field restores the default rather than disabling it.
     */
    public String systemPrompt() {
        String s = sp.getString("system_prompt", "");
        if (s == null || s.trim().length() == 0) {
            return context.getString(R.string.default_system_prompt);
        }
        return s;
    }

    /** True when the user has not set a prompt of their own. */
    public boolean usingDefaultSystemPrompt() {
        String s = sp.getString("system_prompt", "");
        return s == null || s.trim().length() == 0;
    }

    public void setSystemPrompt(String prompt) {
        put("system_prompt", prompt == null ? "" : prompt);
    }

    public int maxTokens() {
        return sp.getInt("max_tokens", 8192);
    }

    public void setMaxTokens(int n) {
        putInt("max_tokens", n);
    }

    public boolean showThinking() {
        return sp.getBoolean("show_thinking", false);
    }

    public void setShowThinking(boolean on) {
        putBoolean("show_thinking", on);
    }

    /**
     * Whether to send temperature/top_p to Anthropic. Off by default because
     * every current Claude model rejects them with a 400; only older models
     * accept them.
     */
    public boolean sendSamplingParams() {
        return sp.getBoolean("send_sampling", false);
    }

    public void setSendSamplingParams(boolean on) {
        putBoolean("send_sampling", on);
    }

    public String legacyTls() {
        return sp.getString("legacy_tls", TLS_AUTO);
    }

    public void setLegacyTls(String mode) {
        put("legacy_tls", mode);
    }

    // ---------------------------------------------------------------- plumb

    private void put(String key, String value) {
        // commit(), never apply() -- see the class comment.
        sp.edit().putString(key, value).commit();
    }

    private void putInt(String key, int value) {
        sp.edit().putInt(key, value).commit();
    }

    private void putBoolean(String key, boolean value) {
        sp.edit().putBoolean(key, value).commit();
    }
}
