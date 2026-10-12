package com.oneai.data;

/**
 * A file attached to a message.
 *
 * <p>The bytes never live in the database. Images are re-encoded to something a
 * phone from 2010 can hold in memory and are written to app-private storage;
 * the row holds only a path and some metadata. Putting base64 blobs in SQLite
 * would bloat the database by a third and make every conversation query slow.
 */
public final class Attachment {

    public static final String IMAGE_JPEG = "image/jpeg";
    public static final String IMAGE_PNG = "image/png";
    public static final String IMAGE_GIF = "image/gif";
    public static final String IMAGE_WEBP = "image/webp";
    public static final String APPLICATION_PDF = "application/pdf";

    public long id;
    public long messageId;

    /** One of the constants above, or whatever the picker reported. */
    public String mimeType;
    /** Original file name, for display only. May be null. */
    public String fileName;
    /** Absolute path to the stored bytes. */
    public String path;
    /** Absolute path to the thumbnail, for images. May be null. */
    public String thumbPath;

    public int width;
    public int height;
    public long sizeBytes;
    public long createdAt;

    public Attachment() {
    }

    public Attachment(String mimeType, String path, long sizeBytes) {
        this.mimeType = mimeType;
        this.path = path;
        this.sizeBytes = sizeBytes;
        this.createdAt = System.currentTimeMillis();
    }

    public boolean isImage() {
        return mimeType != null && mimeType.startsWith("image/");
    }

    public boolean isPdf() {
        return APPLICATION_PDF.equals(mimeType);
    }

    /** True when this provider-independent type is one the APIs accept. */
    public boolean isSupported() {
        return isImage() || isPdf();
    }

    /** e.g. "photo.jpg · 245 KB", for the attachment chip. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(fileName != null && fileName.length() > 0 ? fileName : shortType());
        if (sizeBytes > 0) {
            sb.append("  ·  ").append(humanSize(sizeBytes));
        }
        return sb.toString();
    }

    private String shortType() {
        if (isPdf()) {
            return "PDF";
        }
        if (mimeType != null && mimeType.startsWith("image/")) {
            return mimeType.substring("image/".length()).toUpperCase();
        }
        return "file";
    }

    public static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + " KB";
        }
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
    }
}
