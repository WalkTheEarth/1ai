package com.oneai.provider;

import com.oneai.data.Attachment;
import com.oneai.util.Pem;

/**
 * Turns attachment bytes into the encodings the three APIs expect.
 *
 * <p>Base64 is hand-rolled in {@link Pem} rather than taken from the platform:
 * {@code android.util.Base64} only exists from API 8 and {@code java.util.Base64}
 * from API 26, and this app runs below both.
 *
 * <p>Note that every stored image is JPEG or PNG by the time it reaches here.
 * {@link com.oneai.data.AttachmentStore} re-encodes everything it ingests, which
 * sidesteps a real incompatibility: Gemini does not accept GIF at all, and
 * Anthropic and OpenAI accept it only as a still first frame. Normalising once
 * at ingest means no provider ever has to care.
 */
final class Attachments {

    private Attachments() {
    }

    /** Raw base64, no data: prefix. Anthropic and Google both want this form. */
    static String base64(Attachment a) {
        if (a == null || a.data == null) {
            return "";
        }
        return Pem.encodeBase64(a.data);
    }

    /** A data: URL, which is how OpenAI takes inline image and file bytes. */
    static String dataUrl(Attachment a) {
        if (a == null || a.data == null || a.mimeType == null) {
            return "";
        }
        return "data:" + a.mimeType + ";base64," + Pem.encodeBase64(a.data);
    }

    static boolean usable(Attachment a) {
        return a != null && a.data != null && a.data.length > 0 && a.mimeType != null;
    }
}
