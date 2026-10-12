package com.oneai.data;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads picked files, shrinks images to something a phone from 2010 can hold,
 * and stores the result in app-private storage.
 *
 * <p>The downscale is not just about memory. Every provider re-encodes or
 * downsizes oversized images server-side anyway, so sending a 12-megapixel
 * photo wastes bandwidth, wastes the user's data plan, and -- because image
 * cost is billed per 28x28 or 32x32 patch -- wastes money for no gain in
 * quality. Shrinking once on the device is strictly better.
 *
 * <p>{@link #MAX_DIMENSION} is 1568 because that single number satisfies all
 * three providers at once: it is Anthropic's long-edge limit for the standard
 * resolution tier, it sits under the ~1600px that OpenAI's patch budget allows
 * for the largest models, and it is far below Google's 3072px. It also keeps
 * multi-image requests under Anthropic's stricter limit, which applies to every
 * image once a request carries more than twenty.
 */
public final class AttachmentStore {

    /** Long-edge cap applied to every image before it is stored. */
    public static final int MAX_DIMENSION = 1568;

    private static final int THUMB_DIMENSION = 160;
    private static final int JPEG_QUALITY = 85;

    /** Refuse anything this large outright rather than trying to decode it. */
    private static final long MAX_INPUT_BYTES = 24L * 1024 * 1024;

    /** Keeps a request comfortably under the per-image caps OpenAI drops at 8MB. */
    private static final long MAX_STORED_BYTES = 6L * 1024 * 1024;

    private static final String DIR = "attachments";

    /** Tiny bounded cache so scrolling the message list does not re-decode. */
    private static final int THUMB_CACHE = 24;
    private static final Map<String, Bitmap> THUMBS =
            new LinkedHashMap<String, Bitmap>(THUMB_CACHE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    boolean evict = size() > THUMB_CACHE;
                    if (evict) {
                        // Old devices have small heaps; letting the GC find these
                        // is not good enough when a screen full of thumbnails is
                        // what exhausted it.
                        eldest.getValue().recycle();
                    }
                    return evict;
                }
            };

    private AttachmentStore() {
    }

    private static File dir(Context context) {
        File d = new File(context.getFilesDir(), DIR);
        if (!d.exists() && !d.mkdirs()) {
            // Fall through: the write below will fail with a useful error.
        }
        return d;
    }

    // ---------------------------------------------------------------- ingest

    /** Copies a picked file into app storage, shrinking it if it is an image. */
    public static Attachment ingest(Context context, Uri uri, String displayName)
            throws IOException {
        String sniffed = sniff(context, uri);
        if (sniffed == null) {
            throw new IOException("That file type is not supported. "
                    + "Attach an image (JPEG, PNG, GIF or WebP) or a PDF.");
        }

        Attachment a = new Attachment();
        a.mimeType = sniffed;
        a.fileName = displayName;
        a.createdAt = System.currentTimeMillis();

        if (sniffed.startsWith("image/")) {
            ingestImage(context, uri, a);
        } else {
            ingestRaw(context, uri, a);
        }
        return a;
    }

    private static void ingestImage(Context context, Uri uri, Attachment a) throws IOException {
        long size = sizeOf(context, uri);
        if (size > MAX_INPUT_BYTES) {
            throw new IOException("That image is " + Attachment.humanSize(size)
                    + ", which is too large to open on this device.");
        }

        // Pass one: bounds only, so we know how far to subsample before
        // allocating anything. Decoding a 12MP photo at full size would throw
        // OutOfMemoryError on the hardware this targets.
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream in = context.getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IOException("Could not open that file.");
        }
        try {
            BitmapFactory.decodeStream(in, null, bounds);
        } finally {
            closeQuietly(in);
        }

        int width = bounds.outWidth;
        int height = bounds.outHeight;
        if (width <= 0 || height <= 0) {
            throw new IOException("That image could not be read.");
        }

        Bitmap decoded = decodeScaled(context, uri, width, height);

        boolean keepAlpha = Bitmap.Config.ARGB_8888 == decoded.getConfig()
                && decoded.hasAlpha() && Attachment.IMAGE_PNG.equals(a.mimeType);
        String outMime = keepAlpha ? Attachment.IMAGE_PNG : Attachment.IMAGE_JPEG;
        Bitmap.CompressFormat format = keepAlpha
                ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG;

        byte[] encoded = compress(decoded, format);
        a.width = decoded.getWidth();
        a.height = decoded.getHeight();
        decoded.recycle();

        if (encoded.length > MAX_STORED_BYTES) {
            throw new IOException("That image is still too large after resizing.");
        }

        a.mimeType = outMime;
        writeFile(context, a, encoded, "img");
        writeThumbnail(context, a, encoded);
        a.sizeBytes = encoded.length;
    }

    private static void ingestRaw(Context context, Uri uri, Attachment a) throws IOException {
        long size = sizeOf(context, uri);
        if (size > MAX_INPUT_BYTES) {
            throw new IOException("That file is " + Attachment.humanSize(size)
                    + ", which is too large to attach.");
        }
        byte[] data = readAll(context, uri);
        if (data.length > MAX_STORED_BYTES) {
            throw new IOException("That file is too large to attach.");
        }
        a.sizeBytes = data.length;
        writeFile(context, a, data, "doc");
    }

    private static Bitmap decodeScaled(Context context, Uri uri, int width, int height)
            throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSizeFor(width, height, MAX_DIMENSION);

        Bitmap bitmap = null;
        // Subsampling gets us close, but inSampleSize only takes powers of two,
        // so the result can still be up to twice the target. If decoding still
        // exhausts the heap, back off and try again rather than giving up.
        for (int attempt = 0; attempt < 3 && bitmap == null; attempt++) {
            InputStream in = context.getContentResolver().openInputStream(uri);
            if (in == null) {
                throw new IOException("Could not open that file.");
            }
            try {
                bitmap = BitmapFactory.decodeStream(in, null, options);
            } catch (OutOfMemoryError oom) {
                options.inSampleSize *= 2;
            } finally {
                closeQuietly(in);
            }
        }
        if (bitmap == null) {
            throw new IOException("That image is too large to open on this device.");
        }

        int longEdge = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (longEdge <= MAX_DIMENSION) {
            return bitmap;
        }

        float scale = MAX_DIMENSION / (float) longEdge;
        int targetW = Math.max(1, Math.round(bitmap.getWidth() * scale));
        int targetH = Math.max(1, Math.round(bitmap.getHeight() * scale));
        Bitmap scaled;
        try {
            scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true);
        } catch (OutOfMemoryError oom) {
            bitmap.recycle();
            throw new IOException("That image is too large to resize on this device.");
        }
        if (scaled != bitmap) {
            bitmap.recycle();
        }
        return scaled;
    }

    private static byte[] compress(Bitmap bitmap, Bitmap.CompressFormat format)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256 * 1024);
        if (!bitmap.compress(format, JPEG_QUALITY, out)) {
            throw new IOException("Could not re-encode that image.");
        }
        return out.toByteArray();
    }

    /** Largest power of two that keeps both edges at or above the target. */
    static int sampleSizeFor(int width, int height, int target) {
        int sample = 1;
        while (Math.max(width, height) / (sample * 2) >= target) {
            sample *= 2;
        }
        return sample;
    }

    // ----------------------------------------------------------------- files

    private static void writeFile(Context context, Attachment a, byte[] data, String prefix)
            throws IOException {
        File file = new File(dir(context), prefix + "_" + System.currentTimeMillis()
                + "_" + Math.abs(a.hashCode()) + extension(a.mimeType));
        OutputStream out = new FileOutputStream(file);
        try {
            out.write(data);
        } finally {
            closeQuietly(out);
        }
        a.path = file.getAbsolutePath();
    }

    private static void writeThumbnail(Context context, Attachment a, byte[] original) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(original, 0, original.length, bounds);

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, THUMB_DIMENSION);

        Bitmap thumb = null;
        try {
            thumb = BitmapFactory.decodeByteArray(original, 0, original.length, options);
        } catch (OutOfMemoryError oom) {
            return;
        }
        if (thumb == null) {
            return;
        }
        try {
            int longEdge = Math.max(thumb.getWidth(), thumb.getHeight());
            if (longEdge > THUMB_DIMENSION) {
                float scale = THUMB_DIMENSION / (float) longEdge;
                Bitmap small = Bitmap.createScaledBitmap(thumb,
                        Math.max(1, Math.round(thumb.getWidth() * scale)),
                        Math.max(1, Math.round(thumb.getHeight() * scale)), true);
                if (small != thumb) {
                    thumb.recycle();
                    thumb = small;
                }
            }
            File file = new File(dir(context), "thumb_" + Math.abs(a.hashCode()) + ".jpg");
            OutputStream out = new FileOutputStream(file);
            try {
                thumb.compress(Bitmap.CompressFormat.JPEG, 80, out);
            } finally {
                closeQuietly(out);
            }
            a.thumbPath = file.getAbsolutePath();
        } catch (IOException ignored) {
            // A missing thumbnail is cosmetic; the attachment itself is fine.
        } finally {
            thumb.recycle();
        }
    }

    /** Decodes the stored thumbnail, cached. Returns null if unavailable. */
    public static Bitmap thumbnail(Attachment a) {
        if (a == null || a.thumbPath == null) {
            return null;
        }
        synchronized (THUMBS) {
            Bitmap cached = THUMBS.get(a.thumbPath);
            if (cached != null && !cached.isRecycled()) {
                return cached;
            }
        }
        try {
            Bitmap decoded = BitmapFactory.decodeFile(a.thumbPath);
            if (decoded == null) {
                return null;
            }
            synchronized (THUMBS) {
                THUMBS.put(a.thumbPath, decoded);
            }
            return decoded;
        } catch (OutOfMemoryError oom) {
            return null;
        }
    }

    public static byte[] read(Attachment a) throws IOException {
        if (a == null || a.path == null) {
            throw new IOException("attachment has no file");
        }
        File file = new File(a.path);
        if (!file.exists()) {
            throw new IOException("attachment file is missing");
        }
        if (file.length() > MAX_INPUT_BYTES) {
            throw new IOException("attachment is too large to send");
        }
        byte[] data = new byte[(int) file.length()];
        InputStream in = new java.io.FileInputStream(file);
        try {
            int read = 0;
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
        } finally {
            closeQuietly(in);
        }
        return data;
    }

    public static void delete(Attachment a) {
        if (a == null) {
            return;
        }
        deleteQuietly(a.path);
        deleteQuietly(a.thumbPath);
        synchronized (THUMBS) {
            Bitmap cached = a.thumbPath == null ? null : THUMBS.remove(a.thumbPath);
            if (cached != null && !cached.isRecycled()) {
                cached.recycle();
            }
        }
    }

    /** Deletes stored files by path, for when only the row is available. */
    public static void deletePaths(String path, String thumbPath) {
        deleteQuietly(path);
        deleteQuietly(thumbPath);
        if (thumbPath != null) {
            synchronized (THUMBS) {
                Bitmap cached = THUMBS.remove(thumbPath);
                if (cached != null && !cached.isRecycled()) {
                    cached.recycle();
                }
            }
        }
    }

    private static void deleteQuietly(String path) {
        if (path != null) {
            File f = new File(path);
            if (f.exists() && !f.delete()) {
                // Left for the OS to reclaim; not worth surfacing.
            }
        }
    }

    // ---------------------------------------------------------------- types

    /**
     * Works out what a file actually is.
     *
     * <p>The magic bytes are checked first because {@code getType} returns null
     * for a great many providers on the older Android versions this app
     * targets, and a wrong MIME type means the API rejects the request with an
     * unhelpful message.
     */
    private static String sniff(Context context, Uri uri) {
        InputStream in = null;
        try {
            in = context.getContentResolver().openInputStream(uri);
            if (in == null) {
                return null;
            }
            byte[] head = new byte[12];
            int read = 0;
            while (read < head.length) {
                int n = in.read(head, read, head.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            return sniffBytes(head, read, context.getContentResolver().getType(uri));
        } catch (Exception e) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    static String sniffBytes(byte[] b, int length, String declared) {
        if (length >= 3 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8 && (b[2] & 0xff) == 0xFF) {
            return Attachment.IMAGE_JPEG;
        }
        if (length >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return Attachment.IMAGE_PNG;
        }
        if (length >= 4 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F') {
            return Attachment.IMAGE_GIF;
        }
        if (length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return Attachment.IMAGE_WEBP;
        }
        if (length >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') {
            return Attachment.APPLICATION_PDF;
        }
        // Only trust the resolver's answer for types we actually accept, so an
        // unexpected provider cannot talk us into sending something the API
        // will reject.
        if (declared != null) {
            if (declared.equals(Attachment.IMAGE_JPEG) || declared.equals(Attachment.IMAGE_PNG)
                    || declared.equals(Attachment.IMAGE_GIF)
                    || declared.equals(Attachment.IMAGE_WEBP)
                    || declared.equals(Attachment.APPLICATION_PDF)) {
                return declared;
            }
            // "image/jpg" is not a real MIME type but is reported often enough.
            if ("image/jpg".equalsIgnoreCase(declared)) {
                return Attachment.IMAGE_JPEG;
            }
        }
        return null;
    }

    private static String extension(String mime) {
        if (Attachment.IMAGE_PNG.equals(mime)) {
            return ".png";
        }
        if (Attachment.IMAGE_GIF.equals(mime)) {
            return ".gif";
        }
        if (Attachment.IMAGE_WEBP.equals(mime)) {
            return ".webp";
        }
        if (Attachment.APPLICATION_PDF.equals(mime)) {
            return ".pdf";
        }
        return ".jpg";
    }

    // ----------------------------------------------------------------- plumb

    private static long sizeOf(Context context, Uri uri) {
        InputStream in = null;
        try {
            in = context.getContentResolver().openInputStream(uri);
            if (in == null) {
                return -1;
            }
            long total = 0;
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (total > MAX_INPUT_BYTES) {
                    return total;
                }
            }
            return total;
        } catch (Exception e) {
            return -1;
        } finally {
            closeQuietly(in);
        }
    }

    private static byte[] readAll(Context context, Uri uri) throws IOException {
        InputStream in = context.getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IOException("Could not open that file.");
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            closeQuietly(in);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // Nothing useful to do while closing.
            }
        }
    }

    /** Exposed for the DB, which needs the same directory for cleanup. */
    public static File directory(Context context) {
        return dir(context);
    }
}
