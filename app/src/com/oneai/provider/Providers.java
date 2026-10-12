package com.oneai.provider;

/** Registry of the supported backends. */
public final class Providers {

    public static final String DEFAULT = AnthropicProvider.ID;

    private static final Provider[] ALL = {
            new AnthropicProvider(),
            new OpenAiProvider(),
            new GoogleProvider(),
    };

    private Providers() {
    }

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
        return p != null ? p : ALL[0];
    }
}
