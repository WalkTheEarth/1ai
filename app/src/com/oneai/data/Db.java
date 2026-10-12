package com.oneai.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Conversation and message storage.
 *
 * <p>SQLite is available from API 1, so it is the one persistence mechanism
 * that works identically across the entire range this app supports.
 *
 * <p>The conversation row caches a preview of its last message. That is a
 * denormalisation on purpose: it means the list screen is a single cheap query
 * rather than a join with a correlated subquery, which is worth caring about on
 * a 2010 CPU.
 */
public final class Db extends SQLiteOpenHelper {

    private static final String NAME = "oneai.db";
    private static final int VERSION = 1;

    public static final int PREVIEW_LENGTH = 90;

    public Db(Context context) {
        super(context, NAME, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE conversations ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "title TEXT NOT NULL,"
                + "provider TEXT NOT NULL,"
                + "model TEXT,"
                + "system TEXT,"
                + "preview TEXT,"
                + "created_at INTEGER NOT NULL,"
                + "updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE messages ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "conversation_id INTEGER NOT NULL,"
                + "role TEXT NOT NULL,"
                + "content TEXT NOT NULL,"
                + "provider TEXT,"
                + "model TEXT,"
                + "input_tokens INTEGER NOT NULL DEFAULT 0,"
                + "output_tokens INTEGER NOT NULL DEFAULT 0,"
                + "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_messages_conversation ON messages(conversation_id)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int from, int to) {
        // Nothing to migrate yet; v1 is the first shipped schema. When this
        // changes, migrate rather than drop -- losing someone's conversations
        // to an app update would be unforgivable.
    }

    // ---------------------------------------------------------- conversations

    public List<Conversation> conversations() {
        List<Conversation> out = new ArrayList<Conversation>();
        Cursor c = getReadableDatabase().query("conversations",
                new String[]{"_id", "title", "provider", "model", "system", "preview",
                        "created_at", "updated_at"},
                null, null, null, null, "updated_at DESC");
        try {
            while (c.moveToNext()) {
                Conversation cv = new Conversation();
                cv.id = c.getLong(0);
                cv.title = c.getString(1);
                cv.provider = c.getString(2);
                cv.model = c.isNull(3) ? null : c.getString(3);
                cv.systemPrompt = c.isNull(4) ? null : c.getString(4);
                cv.preview = c.isNull(5) ? null : c.getString(5);
                cv.createdAt = c.getLong(6);
                cv.updatedAt = c.getLong(7);
                out.add(cv);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public Conversation conversation(long id) {
        Cursor c = getReadableDatabase().query("conversations",
                new String[]{"_id", "title", "provider", "model", "system", "created_at", "updated_at"},
                "_id = ?", new String[]{String.valueOf(id)}, null, null, null);
        try {
            if (!c.moveToNext()) {
                return null;
            }
            Conversation cv = new Conversation();
            cv.id = c.getLong(0);
            cv.title = c.getString(1);
            cv.provider = c.getString(2);
            cv.model = c.isNull(3) ? null : c.getString(3);
            cv.systemPrompt = c.isNull(4) ? null : c.getString(4);
            cv.createdAt = c.getLong(5);
            cv.updatedAt = c.getLong(6);
            return cv;
        } finally {
            c.close();
        }
    }

    public long createConversation(String provider, String title) {
        long now = System.currentTimeMillis();
        ContentValues v = new ContentValues();
        v.put("title", title);
        v.put("provider", provider);
        v.put("created_at", now);
        v.put("updated_at", now);
        return getWritableDatabase().insert("conversations", null, v);
    }

    public void renameConversation(long id, String title) {
        ContentValues v = new ContentValues();
        v.put("title", title);
        getWritableDatabase().update("conversations", v, "_id = ?",
                new String[]{String.valueOf(id)});
    }

    public void setConversationModel(long id, String provider, String model) {
        ContentValues v = new ContentValues();
        v.put("provider", provider);
        v.put("model", model);
        getWritableDatabase().update("conversations", v, "_id = ?",
                new String[]{String.valueOf(id)});
    }

    public void setConversationSystem(long id, String system) {
        ContentValues v = new ContentValues();
        v.put("system", system);
        getWritableDatabase().update("conversations", v, "_id = ?",
                new String[]{String.valueOf(id)});
    }

    public void deleteConversation(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("messages", "conversation_id = ?", new String[]{String.valueOf(id)});
        db.delete("conversations", "_id = ?", new String[]{String.valueOf(id)});
    }

    public void updatePreview(long id, String text) {
        ContentValues v = new ContentValues();
        v.put("preview", text == null ? null : shorten(text));
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("conversations", v, "_id = ?",
                new String[]{String.valueOf(id)});
    }

    /** Last message preview, for the list screen. May be null. */
    public String preview(long id) {
        Cursor c = getReadableDatabase().query("conversations",
                new String[]{"preview"}, "_id = ?",
                new String[]{String.valueOf(id)}, null, null, null);
        try {
            return c.moveToNext() && !c.isNull(0) ? c.getString(0) : null;
        } finally {
            c.close();
        }
    }

    // --------------------------------------------------------------- messages

    public List<Message> messages(long conversationId) {
        List<Message> out = new ArrayList<Message>();
        Cursor c = getReadableDatabase().query("messages",
                new String[]{"_id", "conversation_id", "role", "content", "provider", "model",
                        "input_tokens", "output_tokens", "created_at"},
                "conversation_id = ?", new String[]{String.valueOf(conversationId)},
                null, null, "_id ASC");
        try {
            while (c.moveToNext()) {
                Message m = new Message();
                m.id = c.getLong(0);
                m.conversationId = c.getLong(1);
                m.role = c.getString(2);
                m.content = c.getString(3);
                m.provider = c.isNull(4) ? null : c.getString(4);
                m.model = c.isNull(5) ? null : c.getString(5);
                m.inputTokens = c.getInt(6);
                m.outputTokens = c.getInt(7);
                m.createdAt = c.getLong(8);
                out.add(m);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public long addMessage(Message m) {
        ContentValues v = new ContentValues();
        v.put("conversation_id", m.conversationId);
        v.put("role", m.role);
        v.put("content", m.content);
        v.put("provider", m.provider);
        v.put("model", m.model);
        v.put("input_tokens", m.inputTokens);
        v.put("output_tokens", m.outputTokens);
        v.put("created_at", m.createdAt > 0 ? m.createdAt : System.currentTimeMillis());
        return getWritableDatabase().insert("messages", null, v);
    }

    public void updateMessageContent(long id, String content) {
        ContentValues v = new ContentValues();
        v.put("content", content);
        getWritableDatabase().update("messages", v, "_id = ?",
                new String[]{String.valueOf(id)});
    }

    public void updateMessageUsage(long id, int inputTokens, int outputTokens) {
        ContentValues v = new ContentValues();
        v.put("input_tokens", inputTokens);
        v.put("output_tokens", outputTokens);
        getWritableDatabase().update("messages", v, "_id = ?",
                new String[]{String.valueOf(id)});
    }

    public void deleteMessage(long id) {
        getWritableDatabase().delete("messages", "_id = ?", new String[]{String.valueOf(id)});
    }

    /** Removes every message after {@code id}, used by retry and edit-and-resend. */
    public void deleteMessagesAfter(long conversationId, long id) {
        getWritableDatabase().delete("messages", "conversation_id = ? AND _id > ?",
                new String[]{String.valueOf(conversationId), String.valueOf(id)});
    }

    public void clearMessages(long conversationId) {
        getWritableDatabase().delete("messages", "conversation_id = ?",
                new String[]{String.valueOf(conversationId)});
        updatePreview(conversationId, null);
    }

    private static String shorten(String text) {
        String flat = text.replace('\n', ' ').trim();
        return flat.length() <= PREVIEW_LENGTH ? flat : flat.substring(0, PREVIEW_LENGTH) + "…";
    }
}
