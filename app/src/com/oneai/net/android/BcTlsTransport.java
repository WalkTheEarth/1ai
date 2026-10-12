package com.oneai.net.android;

import android.content.Context;

import com.oneai.net.Transport;

/**
 * TLS 1.2 implemented in pure Java, for devices whose own TLS stack is too old.
 *
 * <p>Android below 4.1 has no TLS 1.2 at all, and all three AI providers
 * require it, so on those devices the platform stack cannot reach them
 * whatever we do. Bouncy Castle's lightweight TLS runs on any Java runtime, so
 * it works on Dalvik 1.6 onward.
 */
public final class BcTlsTransport {

    private static final String PROBE = "org.bouncycastle.tls.TlsClientProtocol";

    private BcTlsTransport() {
    }

    /** True when the bundled TLS stack is present in this build. */
    public static boolean isAvailable() {
        try {
            Class.forName(PROBE);
            return true;
        } catch (Throwable missing) {
            return false;
        }
    }

    /** Returns null when the bundled TLS stack is not in this build. */
    public static Transport createIfAvailable(Context context) {
        if (!isAvailable()) {
            return null;
        }
        return new BcTlsTransportImpl(context);
    }
}
