package com.oneai.provider;

/**
 * A failed request, carrying both the raw detail (for the curious) and a short
 * human sentence (for the screen).
 *
 * <p>The raw material here is an HTTP status plus a provider-specific JSON
 * error envelope. Showing that verbatim to someone typing on a phone keyboard
 * is useless, so every provider maps its own envelope into {@link #friendly}.
 */
public class ProviderException extends Exception {

    /** Used when the request never got far enough to have a status. */
    public static final int NO_STATUS = 0;

    public final int status;
    /** Provider error code or type, e.g. "invalid_request_error". May be null. */
    public final String code;
    /** Verbatim provider message, for the detail view. May be null. */
    public final String detail;
    /** Short sentence suitable for showing directly. Never null. */
    public final String friendly;
    /** True when retrying the same request could plausibly succeed. */
    public final boolean retryable;

    public ProviderException(int status, String code, String detail,
                             String friendly, boolean retryable) {
        super(friendly + (detail == null ? "" : " (" + detail + ")"));
        this.status = status;
        this.code = code;
        this.detail = detail;
        this.friendly = friendly;
        this.retryable = retryable;
    }

    public static ProviderException transport(String detail, boolean retryable) {
        return new ProviderException(NO_STATUS, null, detail,
                retryable ? "Could not reach the server." : "Connection failed.",
                retryable);
    }

    /**
     * Fallback wording when a provider's own envelope could not be understood.
     * Kept generic but still specific enough to act on.
     */
    public static ProviderException fromStatus(int status, String providerName, String detail) {
        String friendly;
        boolean retryable = false;
        switch (status) {
            case 400:
                friendly = providerName + " rejected the request.";
                break;
            case 401:
                friendly = "API key rejected by " + providerName + ". Check it in Settings.";
                break;
            case 403:
                friendly = "Access denied by " + providerName
                        + ". The key may lack permission, or the region is blocked.";
                break;
            case 404:
                friendly = "Model not found. Check the model ID in Settings.";
                break;
            case 413:
                friendly = "Conversation too long for this model's context window.";
                break;
            case 429:
                friendly = "Rate limited or out of quota on " + providerName + ".";
                retryable = true;
                break;
            case 500:
            case 502:
            case 503:
            case 504:
                friendly = providerName + " is having trouble (HTTP " + status + ").";
                retryable = true;
                break;
            default:
                friendly = providerName + " returned HTTP " + status + ".";
                retryable = status >= 500;
                break;
        }
        return new ProviderException(status, null, detail, friendly, retryable);
    }
}
