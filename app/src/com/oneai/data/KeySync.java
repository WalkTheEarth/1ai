package com.oneai.data;

import android.content.Context;

import java.util.Iterator;
import java.util.Map;

/**
 * Applies a parsed key file to settings, and notices when the one on the
 * device has changed.
 *
 * <p>Shared by the Settings action and the startup check so the two cannot
 * drift: an import that behaves one way when you ask for it and another way
 * when it happens automatically would be its own kind of bug.
 */
public final class KeySync {

    private KeySync() {
    }

    /** Writes a parsed file into settings. Returns a one-line summary. */
    public static String apply(Prefs prefs, KeyFile parsed) {
        if (parsed == null) {
            return null;
        }
        Iterator<Map.Entry<String, String>> it;
        it = parsed.apiKeys.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, String> e = it.next();
            prefs.setApiKey(e.getKey(), e.getValue());
        }
        it = parsed.baseUrls.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, String> e = it.next();
            prefs.setBaseUrl(e.getKey(), e.getValue());
        }
        it = parsed.models.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, String> e = it.next();
            prefs.setModelOverride(e.getKey(), e.getValue());
        }
        return parsed.summary();
    }

    /**
     * Imports the key file found on the device.
     *
     * @param force when false, does nothing unless the file is newer than the
     *              last import, so a file dropped on the device is picked up
     *              once rather than on every launch.
     * @return a summary of what changed, or null if there was nothing to do.
     */
    public static String syncFromDevice(Context context, Prefs prefs, boolean force) {
        KeyImport.Found found;
        try {
            found = KeyImport.find(context);
        } catch (Exception unreadable) {
            return null;
        }
        if (found == null) {
            return null;
        }
        if (!force && found.modified <= prefs.lastKeyImport()) {
            return null;
        }

        KeyFile parsed = KeyFile.parse(found.text);
        if (parsed.isEmpty()) {
            return null;
        }
        String summary = apply(prefs, parsed);
        // Recorded only after a successful apply, so a file that failed to
        // parse is retried next launch rather than being written off.
        prefs.setLastKeyImport(found.modified);
        return summary;
    }
}
