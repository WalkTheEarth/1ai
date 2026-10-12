package com.oneai.data;

import android.content.Context;
import android.os.Build;
import android.os.Environment;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds a key file on the device without needing a file picker.
 *
 * <p>The picker is the tidy mechanism and is used where it exists, but it is
 * not something that can be relied on: many Android builds ship without a
 * document provider, and the BlackBerry 10 runtime this was written for is one
 * of them. "Put a text file on the device" should work by putting a text file
 * on the device.
 *
 * <p>So the app also looks in a handful of obvious places for a file named
 * {@code 1ai-keys.txt}, and imports whichever it finds. The app-specific
 * external directory is checked first because it needs no permission at all on
 * any version.
 */
public final class KeyImport {

    public static final String FILE_NAME = "1ai-keys.txt";

    /** A file that was found, with its contents already read. */
    public static final class Found {
        public final File file;
        public final String text;
        public final long modified;

        Found(File file, String text) {
            this.file = file;
            this.text = text;
            this.modified = file.lastModified();
        }

        public String describe() {
            return file.getAbsolutePath();
        }
    }

    private KeyImport() {
    }

    /** Where the app looks, in order. The first readable file wins. */
    public static List<File> searchPath(Context context) {
        List<File> dirs = new ArrayList<File>();

        // No permission needed on any Android version, so it goes first.
        File appExternal = context.getExternalFilesDir(null);
        if (appExternal != null) {
            dirs.add(appExternal);
        }

        File sd = Environment.getExternalStorageDirectory();
        if (sd != null) {
            dirs.add(sd);
            dirs.add(new File(sd, "Download"));
            dirs.add(new File(sd, "Downloads"));
            dirs.add(new File(sd, "Documents"));
            dirs.add(new File(sd, "1ai"));
        }

        // Some BlackBerry builds mount removable storage somewhere else.
        File legacy = new File("/sdcard");
        if (!dirs.contains(legacy)) {
            dirs.add(legacy);
        }

        List<File> files = new ArrayList<File>();
        for (int i = 0; i < dirs.size(); i++) {
            files.add(new File(dirs.get(i), FILE_NAME));
        }
        return files;
    }

    /** The first key file that exists and can be read, or null. */
    public static Found find(Context context) {
        List<File> candidates = searchPath(context);
        for (int i = 0; i < candidates.size(); i++) {
            File f = candidates.get(i);
            try {
                if (f.isFile() && f.canRead() && f.length() > 0) {
                    return new Found(f, read(f));
                }
            } catch (Exception unreadable) {
                // A directory we cannot list is not an error worth surfacing;
                // move on to the next candidate.
            }
        }
        return null;
    }

    /** A human list of where the app looked, for when nothing was found. */
    public static String describeSearchPath(Context context) {
        List<File> candidates = searchPath(context);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < candidates.size() && i < 6; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(candidates.get(i).getAbsolutePath());
        }
        return sb.toString();
    }

    /**
     * True when reading external storage may need asking for.
     *
     * <p>From API 23 the permission is granted at runtime, and from API 33 it
     * does not cover arbitrary files at all -- there, the picker is the only
     * mechanism that works, which is why both paths exist.
     */
    public static boolean needsPermissionRequest() {
        return Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 33;
    }

    public static String readPermission() {
        return "android.permission.READ_EXTERNAL_STORAGE";
    }

    static String read(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 1024 * 1024) {
                    throw new IOException("file is too large to be a key list");
                }
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // Nothing useful to do while closing.
            }
        }
    }
}
