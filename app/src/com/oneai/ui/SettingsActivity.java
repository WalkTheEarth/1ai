package com.oneai.ui;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.oneai.R;
import com.oneai.data.Prefs;
import com.oneai.data.Store;
import com.oneai.net.android.TransportFactory;
import com.oneai.provider.Provider;
import com.oneai.provider.Providers;

/**
 * Settings.
 *
 * <p>Rows are built in code rather than declared in XML. The sheet is mostly
 * repetitive label/value pairs generated per provider, so generating them keeps
 * the three providers in sync automatically -- adding a fourth needs no layout
 * work at all.
 */
public class SettingsActivity extends BaseActivity {

    private Prefs prefs;
    private LinearLayout content;

    private static final String[] EFFORT_NONE = {"(provider default)", "low", "medium", "high",
            "xhigh", "max"};
    private static final String[] EFFORT_OPENAI = {"(provider default)", "none", "minimal", "low",
            "medium", "high", "xhigh", "max"};

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_settings);
        bindTitleBar();
        setTitleText(getString(R.string.title_settings));

        prefs = Store.prefs(this);
        content = (LinearLayout) findViewById(R.id.settings_content);
        build();
    }

    // ------------------------------------------------------------- building

    private void build() {
        content.removeAllViews();

        section(R.string.section_provider);
        row(getString(R.string.label_default_provider),
                Providers.byIdOrDefault(prefs.defaultProvider()).displayName(),
                new Runnable() {
                    @Override
                    public void run() {
                        pickProvider();
                    }
                });

        // One row per provider, opening a detail sheet, rather than four rows
        // inline. With the compatible endpoints included there are seventeen
        // providers, and the flat version would have been sixty-odd rows.
        section("Providers");
        Provider[] primary = Providers.primary();
        for (int i = 0; i < primary.length; i++) {
            providerRow(primary[i]);
        }

        section("More providers (OpenAI-compatible)");
        Provider[] compatible = Providers.compatible();
        for (int i = 0; i < compatible.length; i++) {
            providerRow(compatible[i]);
        }

        section(R.string.section_generation);
        row(getString(R.string.label_system_prompt),
                (prefs.usingDefaultSystemPrompt()
                        ? getString(R.string.label_system_prompt_default) + "  "
                        : "") + prefs.systemPrompt(),
                new Runnable() {
                    @Override
                    public void run() {
                        editText(getString(R.string.label_system_prompt), prefs.systemPrompt(),
                                true, false, new TextSink() {
                                    @Override
                                    public void accept(String value) {
                                        prefs.setSystemPrompt(value);
                                        build();
                                    }
                                });
                    }
                });

        row(getString(R.string.label_max_tokens), String.valueOf(prefs.maxTokens()),
                new Runnable() {
                    @Override
                    public void run() {
                        editText(getString(R.string.label_max_tokens),
                                String.valueOf(prefs.maxTokens()), false, true, new TextSink() {
                                    @Override
                                    public void accept(String value) {
                                        try {
                                            int n = Integer.parseInt(value.trim());
                                            if (n > 0) {
                                                prefs.setMaxTokens(n);
                                            }
                                        } catch (NumberFormatException ignored) {
                                            // Keep the previous value.
                                        }
                                        build();
                                    }
                                });
                    }
                });

        row(getString(R.string.label_show_thinking), onOff(prefs.showThinking()),
                new Runnable() {
                    @Override
                    public void run() {
                        prefs.setShowThinking(!prefs.showThinking());
                        build();
                    }
                });

        row(getString(R.string.label_send_sampling),
                onOff(prefs.sendSamplingParams()),
                new Runnable() {
                    @Override
                    public void run() {
                        prefs.setSendSamplingParams(!prefs.sendSamplingParams());
                        build();
                    }
                });
        help(getString(R.string.label_send_sampling_hint));

        section(R.string.section_advanced);
        row("Test connection", "checks DNS, TLS and HTTP without using your quota",
                new Runnable() {
                    @Override
                    public void run() {
                        runProbe();
                    }
                });
        row(getString(R.string.label_legacy_tls), tlsLabel(prefs.legacyTls()),
                new Runnable() {
                    @Override
                    public void run() {
                        final String[] labels = {
                                getString(R.string.tls_auto),
                                getString(R.string.tls_always),
                                getString(R.string.tls_never),
                        };
                        final String[] values = {Prefs.TLS_AUTO, Prefs.TLS_ALWAYS, Prefs.TLS_NEVER};
                        new AlertDialog.Builder(SettingsActivity.this)
                                .setTitle(R.string.label_legacy_tls)
                                .setItems(labels, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int which) {
                                        prefs.setLegacyTls(values[which]);
                                        build();
                                    }
                                })
                                .show();
                    }
                });
        help(TransportFactory.describe(prefs));
    }

    // ------------------------------------------------------------ providers

    private void providerRow(final Provider p) {
        String key = prefs.apiKey(p.id());
        String model = effectiveModel(p);
        StringBuilder value = new StringBuilder();
        // Never show the key itself. A masked hint is enough to tell one key
        // from another without putting a secret on screen.
        value.append(key.length() == 0 ? "no API key" : mask(key));
        value.append("  \u00b7  ").append(model);
        row(p.displayName(), value.toString(), new Runnable() {
            @Override
            public void run() {
                providerDetail(p);
            }
        });

        if (p instanceof com.oneai.provider.CompatibleProvider) {
            com.oneai.provider.CompatibleEndpoints.Endpoint e =
                    ((com.oneai.provider.CompatibleProvider) p).endpoint();
            // Showing the URL that will actually be called turns a confusing
            // 404 into something the user can see and fix in one edit.
            StringBuilder helpText = new StringBuilder();
            if (e.note.length() > 0) {
                helpText.append(e.note).append('\n');
            }
            helpText.append(resolvedUrl(p));
            help(helpText.toString());
        }
    }

    private String resolvedUrl(Provider p) {
        com.oneai.provider.ChatRequest probe = new com.oneai.provider.ChatRequest();
        probe.model = effectiveModel(p);
        return p.url(prefs.baseUrl(p), probe);
    }

    private void providerDetail(final Provider p) {
        java.util.List<CharSequence> labels = new java.util.ArrayList<CharSequence>();
        labels.add(getString(R.string.label_api_key, p.displayName()));
        labels.add(getString(R.string.label_model));
        labels.add(getString(R.string.label_base_url));
        final boolean wantsEffort = isEffortProvider(p);
        if (wantsEffort) {
            labels.add(getString(R.string.label_effort));
        }
        final boolean wantsTemperature = p.supportsTemperature();
        if (wantsTemperature) {
            labels.add(getString(R.string.label_temperature));
        }

        new AlertDialog.Builder(this)
                .setTitle(p.displayName())
                .setItems(labels.toArray(new CharSequence[labels.size()]),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                if (which == 0) {
                                    editApiKey(p);
                                } else if (which == 1) {
                                    editModel(p);
                                } else if (which == 2) {
                                    editBaseUrl(p);
                                } else if (wantsEffort && which == 3) {
                                    editEffort(p);
                                } else {
                                    editTemperature(p);
                                }
                            }
                        })
                .show();
    }

    private void editApiKey(final Provider p) {
        editText(getString(R.string.label_api_key, p.displayName()),
                prefs.apiKey(p.id()), false, false, new TextSink() {
                    @Override
                    public void accept(String value) {
                        prefs.setApiKey(p.id(), value);
                        build();
                    }
                });
    }

    private void editModel(final Provider p) {
        final java.util.List<String> models = p.knownModels();
        final CharSequence[] items = new CharSequence[models.size() + 1];
        for (int i = 0; i < models.size(); i++) {
            items[i] = models.get(i);
        }
        items[models.size()] = "Custom\u2026";
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.label_model))
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == models.size()) {
                            editText(getString(R.string.label_custom_model),
                                    orEmpty(prefs.modelOverride(p.id())), false, false,
                                    new TextSink() {
                                        @Override
                                        public void accept(String value) {
                                            prefs.setModelOverride(p.id(), value);
                                            build();
                                        }
                                    });
                        } else {
                            prefs.setModelOverride(p.id(), models.get(which));
                            build();
                        }
                    }
                })
                .show();
    }

    private void editBaseUrl(final Provider p) {
        editText(getString(R.string.label_base_url), prefs.baseUrl(p), false, false,
                new TextSink() {
                    @Override
                    public void accept(String value) {
                        prefs.setBaseUrl(p.id(), value);
                        build();
                    }
                });
    }

    private void editEffort(final Provider p) {
        final String[] options = com.oneai.provider.OpenAiProvider.ID.equals(p.id())
                ? EFFORT_OPENAI : EFFORT_NONE;
        choose(getString(R.string.label_effort), options, prefs.effort(p.id()),
                new ChoiceSink() {
                    @Override
                    public void accept(String value) {
                        prefs.setEffort(p.id(),
                                value != null && value.startsWith("(") ? null : value);
                        build();
                    }
                });
    }

    private void editTemperature(final Provider p) {
        Double current = prefs.temperature(p.id());
        editText(getString(R.string.label_temperature),
                current == null ? "" : String.valueOf(current), false, true,
                new TextSink() {
                    @Override
                    public void accept(String value) {
                        if (value.length() == 0) {
                            prefs.setTemperature(p.id(), null);
                        } else {
                            try {
                                prefs.setTemperature(p.id(), Double.valueOf(value));
                            } catch (NumberFormatException ignored) {
                                // Keep whatever was there before.
                            }
                        }
                        build();
                    }
                });
    }

    private static boolean isEffortProvider(Provider p) {
        return com.oneai.provider.AnthropicProvider.ID.equals(p.id())
                || com.oneai.provider.OpenAiProvider.ID.equals(p.id());
    }

    /** The model id that will actually be used, following the fallbacks. */
    private String effectiveModel(Provider p) {
        String override = prefs.modelOverride(p.id());
        return override != null ? override : p.defaultModel();
    }

    // ---------------------------------------------------------------- rows

    private void section(int titleRes) {
        section(getString(titleRes));
    }

    private void section(String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(12);
        t.setTextColor(getResources().getColor(R.color.accent));
        t.setPadding(dp(10), dp(16), dp(10), dp(4));
        content.addView(t, matchWrap());
    }

    private void help(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(11);
        t.setTextColor(getResources().getColor(R.color.text_secondary));
        t.setPadding(dp(10), 0, dp(10), dp(6));
        content.addView(t, matchWrap());
    }

    private void row(String label, String value, final Runnable onClick) {
        LinearLayout rowLayout = new LinearLayout(this);
        rowLayout.setOrientation(LinearLayout.VERTICAL);
        rowLayout.setBackgroundDrawable(getResources().getDrawable(R.drawable.row_bg));
        rowLayout.setPadding(dp(10), dp(9), dp(10), dp(9));
        rowLayout.setFocusable(true);
        rowLayout.setClickable(true);
        rowLayout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onClick.run();
            }
        });

        TextView title = new TextView(this);
        title.setText(label);
        title.setTextSize(15);
        title.setTextColor(getResources().getColor(R.color.row_title));
        rowLayout.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText(value);
        subtitle.setTextSize(12);
        subtitle.setTextColor(getResources().getColor(R.color.row_subtitle));
        subtitle.setPadding(0, dp(2), 0, 0);
        rowLayout.addView(subtitle, matchWrap());

        content.addView(rowLayout, matchWrap());
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.FILL_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ------------------------------------------------------------- editors

    private interface TextSink {
        void accept(String value);
    }

    private interface ChoiceSink {
        void accept(String value);
    }

    private void editText(String title, String initial, boolean multiline, boolean numeric,
                          final TextSink sink) {
        final EditText editor = new EditText(this);
        editor.setText(initial == null ? "" : initial);
        if (multiline) {
            editor.setMinLines(3);
        } else {
            editor.setSingleLine(true);
        }
        if (numeric) {
            editor.setInputType(InputType.TYPE_CLASS_NUMBER
                    | InputType.TYPE_NUMBER_FLAG_DECIMAL
                    | InputType.TYPE_NUMBER_FLAG_SIGNED);
        }

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(editor)
                .setPositiveButton(R.string.action_save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        sink.accept(editor.getText().toString().trim());
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void choose(String title, final String[] options, String current,
                        final ChoiceSink sink) {
        int checked = 0;
        for (int i = 0; i < options.length; i++) {
            if (options[i].equals(current)) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(options, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        sink.accept(options[which]);
                        d.dismiss();
                    }
                })
                .show();
    }

    private void pickProvider() {
        final Provider[] all = Providers.all();
        final CharSequence[] names = new CharSequence[all.length];
        for (int i = 0; i < all.length; i++) {
            names[i] = all[i].displayName();
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.label_default_provider)
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        prefs.setDefaultProvider(all[which].id());
                        build();
                    }
                })
                .show();
    }

    // -------------------------------------------------------------- helpers


    /**
     * Runs a connection check off the UI thread and shows the outcome.
     *
     * <p>The request carries a deliberately invalid key, so a 401 is the
     * success case: it proves the whole stack works while spending nothing.
     */
    private void runProbe() {
        final com.oneai.provider.Provider provider =
                com.oneai.provider.Providers.byIdOrDefault(prefs.defaultProvider());
        final AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle("Test connection")
                .setMessage("Contacting " + provider.displayName() + "\u2026")
                .setCancelable(false)
                .show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                final com.oneai.chat.ConnectionProbe.Result result =
                        com.oneai.chat.ConnectionProbe.run(
                                SettingsActivity.this, prefs, provider);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        progress.dismiss();
                        new AlertDialog.Builder(SettingsActivity.this)
                                .setTitle(result.reachedServer
                                        ? "Connection OK" : "Connection failed")
                                .setMessage(result.describe())
                                .setPositiveButton(R.string.action_ok, null)
                                .show();
                    }
                });
            }
        }).start();
    }

    private static String onOff(boolean on) {
        return on ? "On" : "Off";
    }

    private static String mask(String key) {
        if (key == null || key.length() == 0) {
            return "(not set)";
        }
        if (key.length() <= 8) {
            return "••••";
        }
        return key.substring(0, 4) + "••••••"
                + key.substring(key.length() - 4);
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.length() == 0 ? fallback : value;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.length() == 0 ? null : value;
    }

    private String tlsLabel(String mode) {
        if (Prefs.TLS_ALWAYS.equals(mode)) {
            return getString(R.string.tls_always);
        }
        if (Prefs.TLS_NEVER.equals(mode)) {
            return getString(R.string.tls_never);
        }
        return getString(R.string.tls_auto);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent event) {
        if (code == KeyEvent.KEYCODE_ESCAPE) {
            finish();
            return true;
        }
        return super.onKeyDown(code, event);
    }
}
