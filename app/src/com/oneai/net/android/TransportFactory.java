package com.oneai.net.android;

import android.content.Context;
import android.os.Build;

import com.oneai.data.Prefs;
import com.oneai.net.Transport;

/**
 * Chooses between the platform TLS stack and the bundled Bouncy Castle one.
 *
 * <p>The platform stack is preferred wherever it works: it is the device's own
 * well-tested implementation, and it is free. It stops working below API 16,
 * where TLS 1.2 does not exist at all -- and all three AI providers require
 * TLS 1.2, so below 16 there is simply no way to reach them without bringing
 * our own TLS.
 */
public final class TransportFactory {

    private TransportFactory() {
    }

    /** True when the platform stack cannot reach a modern endpoint. */
    public static boolean platformTlsIsInsufficient() {
        return Build.VERSION.SDK_INT < 16;
    }

    public static Transport create(Context context, Prefs prefs) {
        String mode = prefs.legacyTls();

        if (Prefs.TLS_ALWAYS.equals(mode)) {
            Transport bc = BcTlsTransport.createIfAvailable(context);
            return bc != null ? bc : new PlatformTransport();
        }
        if (Prefs.TLS_NEVER.equals(mode)) {
            return new PlatformTransport();
        }

        // AUTO: reach for Bouncy Castle only when the platform cannot do it.
        if (platformTlsIsInsufficient()) {
            Transport bc = BcTlsTransport.createIfAvailable(context);
            if (bc != null) {
                return bc;
            }
        }
        return new PlatformTransport();
    }

    /**
     * A sentence explaining what this device can and cannot do.
     *
     * <p>Below API 16 this is a bad-news message, and it says so. The bundled
     * TLS is present and works, but only on ART: on Dalvik the bytecode the
     * dexer emits for Bouncy Castle fails the old verifier outright, so the
     * attempt is doomed before it starts. Pretending otherwise would send
     * someone hunting for a network fault that does not exist.
     */
    public static String describe(Prefs prefs) {
        if (!platformTlsIsInsufficient()) {
            return "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT
                    + ") has TLS 1.2. All good.";
        }
        return "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT
                + ") has no TLS 1.2, which these APIs require. The bundled TLS cannot help here: "
                + "it needs Android 5.0 or newer, where it is also unnecessary. "
                + "Network requests will not work on this device.";
    }
}
