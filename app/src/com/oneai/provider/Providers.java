package com.oneai.provider;

/**
 * Registry of every backend the app can talk to.
 *
 * <p>Split into two groups because they are genuinely different things.
 * {@link #primary()} are the three first-class integrations, each with its own
 * request shape and stream format. {@link #compatible()} are services that
 * speak the OpenAI chat-completions format, differing only in base URL and
 * model names; they exist so a preset list can offer a dozen providers without
 * a dozen parsers.
 *
 * <p>The distinction is only used for grouping in Settings. Everything else
 * treats them uniformly, which is why adding a service to
 * {@link CompatibleEndpoints} needs no other change anywhere.
 */
public final class Providers {

    public static final String DEFAULT = AnthropicProvider.ID;

    private static final Provider[] PRIMARY = {
            new AnthropicProvider(),
            new OpenAiProvider(),
            new GoogleProvider(),
    };

    private static final Provider[] COMPATIBLE = buildCompatible();

    private static final Provider[] ALL = concatenate(PRIMARY, COMPATIBLE);

    private Providers() {
    }

    private static Provider[] buildCompatible() {
        CompatibleEndpoints.Endpoint[] endpoints = CompatibleEndpoints.ALL;
        Provider[] out = new Provider[endpoints.length];
        for (int i = 0; i < endpoints.length; i++) {
            out[i] = new CompatibleProvider(endpoints[i]);
        }
        return out;
    }

    private static Provider[] concatenate(Provider[] a, Provider[] b) {
        Provider[] out = new Provider[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** Anthropic, OpenAI and Google. */
    public static Provider[] primary() {
        return PRIMARY;
    }

    /** Services reachable through the OpenAI-compatible wire format. */
    public static Provider[] compatible() {
        return COMPATIBLE;
    }

    /** Everything, primary group first. */
    public static Provider[] all() {
        return ALL;
    }

    public static Provider byId(String id) {
        for (int i = 0; i < ALL.length; i++) {
            if (ALL[i].id().equals(id)) {
                return ALL[i];
            }
        }
        return null;
    }

    /** Never null; falls back to the default provider for an unknown id. */
    public static Provider byIdOrDefault(String id) {
        Provider p = byId(id);
        return p != null ? p : PRIMARY[0];
    }
}
