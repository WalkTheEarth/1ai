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
        Transport bundled = BcTlsTransport.createIfAvailable(context);

        if (Prefs.TLS_ALWAYS.equals(mode)) {
            return bundled != null ? bundled : new PlatformTransport();
        }
        if (Prefs.TLS_NEVER.equals(mode)) {
            return new PlatformTransport();
        }

        // AUTO. Below API 16 the platform has no TLS 1.2 at all, so there is
        // nothing to try first.
        if (platformTlsIsInsufficient()) {
            return bundled != null ? bundled : new PlatformTransport();
        }

        // Everywhere else, prefer the platform stack -- it is the device's own
        // tested implementation and costs nothing in APK size -- but keep the
        // bundled one directly behind it. Which API levels successfully
        // negotiate TLS 1.2 varies by release and vendor, and the servers keep
        // moving too: DeepSeek now refuses TLS 1.0 and 1.1 outright, so a phone
        // offering only those cannot reach it however correct this code is.
        // Retrying on a handshake failure removes the need to predict that.
        Transport platform = new PlatformTransport();
        return bundled != null ? new FallbackTransport(platform, bundled) : platform;
    }

    /**
     * A sentence explaining what this device can and cannot do, for the
     * settings screen.
     *
     * <p>Below API 16 it is bad news and says so. The bundled TLS is present
     * and works -- verified on Android 4.3, which is what this was built for --
     * but it needs a Dalvik new enough to verify its bytecode, and Android 2.3's
     * is not. Pretending otherwise would send someone hunting for a network
     * fault that does not exist.
     */
    public static String describe(Prefs prefs) {
        if (!platformTlsIsInsufficient()) {
            return "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT
                    + "). The platform TLS is used, with the bundled TLS as a fallback "
                    + "for servers it cannot negotiate with.";
        }
        return "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT
                + ") has no TLS 1.2, which these APIs require. The bundled TLS needs "
                + "Android 4.1 or newer, so network requests will not work here.";
    }
}
