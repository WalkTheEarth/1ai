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

        for (final Provider p : Providers.all()) {
            section(p.displayName());

            row(getString(R.string.label_api_key, p.displayName()),
                    mask(prefs.apiKey(p.id())),
                    new Runnable() {
                        @Override
                        public void run() {
                            editText(getString(R.string.label_api_key, p.displayName()),
                                    prefs.apiKey(p.id()), false, false, new TextSink() {
                                        @Override
                                        public void accept(String value) {
                                            prefs.setApiKey(p.id(), value);
                                            build();
                                        }
                                    });
                        }
                    });

            row(getString(R.string.label_model, p.displayName()),
                    orDefault(prefs.modelOverride(p.id()), p.defaultModel()),
                    new Runnable() {
                        @Override
                        public void run() {
                            editText(getString(R.string.label_model, p.displayName()),
                                    orEmpty(prefs.modelOverride(p.id())), false, false, new TextSink() {
                                        @Override
                                        public void accept(String value) {
                                            prefs.setModelOverride(p.id(), value);
                                            build();
                                        }
                                    });
                        }
                    });

            row(getString(R.string.label_base_url),
                    orDefault(emptyToNull(prefs.baseUrl(p)), p.defaultBaseUrl()),
                    new Runnable() {
                        @Override
                        public void run() {
                            editText(getString(R.string.label_base_url),
                                    prefs.baseUrl(p), false, false, new TextSink() {
                                        @Override
                                        public void accept(String value) {
                                            prefs.setBaseUrl(p.id(), value);
                                            build();
                                        }
                                    });
                        }
                    });

            if (com.oneai.provider.AnthropicProvider.ID.equals(p.id())
                    || com.oneai.provider.OpenAiProvider.ID.equals(p.id())) {
                final String[] options = com.oneai.provider.OpenAiProvider.ID.equals(p.id())
                        ? EFFORT_OPENAI : EFFORT_NONE;
                row(getString(R.string.label_effort), orDefault(prefs.effort(p.id()), "(default)"),
                        new Runnable() {
                            @Override
                            public void run() {
                                choose(getString(R.string.label_effort), options,
                                        prefs.effort(p.id()), new ChoiceSink() {
                                            @Override
                                            public void accept(String value) {
                                                prefs.setEffort(p.id(),
                                                        value != null && value.startsWith("(")
                                                                ? null : value);
                                                build();
                                            }
                                        });
                            }
                        });
            }

            // Offering a temperature control for a backend whose models
            // reject it would be offering a button that produces a 400.
            if (p.supportsTemperature()) {
                Double t = prefs.temperature(p.id());
                row(getString(R.string.label_temperature), t == null ? "(provider default)"
                                : String.valueOf(t),
                        new Runnable() {
                            @Override
                            public void run() {
                                editText(getString(R.string.label_temperature),
                                        orEmpty(prefs.temperature(p.id()) == null ? null
                                                : String.valueOf(prefs.temperature(p.id()))),
                                        false, true, new TextSink() {
                                            @Override
                                            public void accept(String value) {
                                                if (value.length() == 0) {
                                                    prefs.setTemperature(p.id(), null);
                                                } else {
                                                    try {
                                                        prefs.setTemperature(p.id(),
                                                                Double.valueOf(value));
                                                    } catch (NumberFormatException ignored) {
                                                        // Leave the old value in place.
                                                    }
                                                }
                                                build();
                                            }
                                        });
                            }
                        });
            }
        }

        section(R.string.section_generation);
        row(getString(R.string.label_system_prompt),
                prefs.systemPrompt().length() == 0 ? "(none)" : prefs.systemPrompt(),
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
