package com.oneai.data;

import android.content.Context;

/**
 * Process-wide instances of the database and settings.
 *
 * <p>A single {@code SQLiteOpenHelper} per process is the documented way to use
 * it; creating one per Activity leaks cursors and connection pools. Holding the
 * application context avoids leaking an Activity.
 */
public final class Store {

    private static Db db;
    private static Prefs prefs;

    private Store() {
    }

    public static synchronized Db db(Context context) {
        if (db == null) {
            db = new Db(context.getApplicationContext());
        }
        return db;
    }

    public static synchronized Prefs prefs(Context context) {
        if (prefs == null) {
            prefs = new Prefs(context.getApplicationContext());
        }
        return prefs;
    }
}
