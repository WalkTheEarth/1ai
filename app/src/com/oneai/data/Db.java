package com.oneai.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private static final int VERSION = 2;

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
        createAttachments(db);
    }

    private static void createAttachments(SQLiteDatabase db) {
        // Only metadata lives here. The bytes go to app-private files, because
        // base64 blobs in SQLite would inflate the database by a third, make
        // every conversation query read them, and gain nothing.
        db.execSQL("CREATE TABLE attachments ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "message_id INTEGER NOT NULL,"
                + "mime TEXT NOT NULL,"
                + "file_name TEXT,"
                + "path TEXT NOT NULL,"
                + "thumb_path TEXT,"
                + "width INTEGER NOT NULL DEFAULT 0,"
                + "height INTEGER NOT NULL DEFAULT 0,"
                + "size_bytes INTEGER NOT NULL DEFAULT 0,"
                + "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_attachments_message ON attachments(message_id)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int from, int to) {
        // Migrate, never drop. Losing someone's conversations and attachments
        // to an app update would be unforgivable, and this is the only place
        // that decision gets made.
        if (from < 2) {
            createAttachments(db);
        }
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
        // Files first: once the rows are gone there is nothing left to tell us
        // which files on disk belonged to this conversation, and they would
        // accumulate forever.
        deleteAttachmentFiles(db, "message_id IN (SELECT _id FROM messages WHERE conversation_id = ?)",
                new String[]{String.valueOf(id)});
        db.delete("attachments", "message_id IN (SELECT _id FROM messages WHERE conversation_id = ?)",
                new String[]{String.valueOf(id)});
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
        SQLiteDatabase db = getWritableDatabase();
        deleteAttachmentFiles(db, "message_id = ?", new String[]{String.valueOf(id)});
        db.delete("attachments", "message_id = ?", new String[]{String.valueOf(id)});
        db.delete("messages", "_id = ?", new String[]{String.valueOf(id)});
    }

    /** Removes every message after {@code id}, used by retry and edit-and-resend. */
    public void deleteMessagesAfter(long conversationId, long id) {
        SQLiteDatabase db = getWritableDatabase();
        String where = "message_id IN (SELECT _id FROM messages "
                + "WHERE conversation_id = ? AND _id > ?)";
        String[] args = {String.valueOf(conversationId), String.valueOf(id)};
        deleteAttachmentFiles(db, where, args);
        db.delete("attachments", where, args);
        db.delete("messages", "conversation_id = ? AND _id > ?", args);
    }

    public void clearMessages(long conversationId) {
        SQLiteDatabase db = getWritableDatabase();
        String where = "message_id IN (SELECT _id FROM messages WHERE conversation_id = ?)";
        String[] args = {String.valueOf(conversationId)};
        deleteAttachmentFiles(db, where, args);
        db.delete("attachments", where, args);
        db.delete("messages", "conversation_id = ?", args);
        updatePreview(conversationId, null);
    }

    // ------------------------------------------------------------ attachments

    public long addAttachment(Attachment a) {
        ContentValues v = new ContentValues();
        v.put("message_id", a.messageId);
        v.put("mime", a.mimeType);
        v.put("file_name", a.fileName);
        v.put("path", a.path);
        v.put("thumb_path", a.thumbPath);
        v.put("width", a.width);
        v.put("height", a.height);
        v.put("size_bytes", a.sizeBytes);
        v.put("created_at", a.createdAt > 0 ? a.createdAt : System.currentTimeMillis());
        return getWritableDatabase().insert("attachments", null, v);
    }

    /** Every attachment on a conversation, grouped by message id. */
    public Map<Long, List<Attachment>> attachmentsForConversation(long conversationId) {
        Map<Long, List<Attachment>> out = new HashMap<Long, List<Attachment>>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT a._id, a.message_id, a.mime, a.file_name, a.path, a.thumb_path,"
                        + " a.width, a.height, a.size_bytes, a.created_at"
                        + " FROM attachments a JOIN messages m ON a.message_id = m._id"
                        + " WHERE m.conversation_id = ? ORDER BY a._id ASC",
                new String[]{String.valueOf(conversationId)});
        try {
            while (c.moveToNext()) {
                Attachment a = readAttachment(c);
                List<Attachment> list = out.get(Long.valueOf(a.messageId));
                if (list == null) {
                    list = new ArrayList<Attachment>();
                    out.put(Long.valueOf(a.messageId), list);
                }
                list.add(a);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public List<Attachment> attachmentsFor(long messageId) {
        List<Attachment> out = new ArrayList<Attachment>();
        Cursor c = getReadableDatabase().query("attachments",
                new String[]{"_id", "message_id", "mime", "file_name", "path", "thumb_path",
                        "width", "height", "size_bytes", "created_at"},
                "message_id = ?", new String[]{String.valueOf(messageId)},
                null, null, "_id ASC");
        try {
            while (c.moveToNext()) {
                out.add(readAttachment(c));
            }
        } finally {
            c.close();
        }
        return out;
    }

    private static Attachment readAttachment(Cursor c) {
        Attachment a = new Attachment();
        a.id = c.getLong(0);
        a.messageId = c.getLong(1);
        a.mimeType = c.getString(2);
        a.fileName = c.isNull(3) ? null : c.getString(3);
        a.path = c.getString(4);
        a.thumbPath = c.isNull(5) ? null : c.getString(5);
        a.width = c.getInt(6);
        a.height = c.getInt(7);
        a.sizeBytes = c.getLong(8);
        a.createdAt = c.getLong(9);
        return a;
    }

    /** Removes the files behind a set of attachment rows, then the rows. */
    private void deleteAttachmentFiles(SQLiteDatabase db, String where, String[] args) {
        Cursor c = db.query("attachments", new String[]{"path", "thumb_path"}, where, args,
                null, null, null);
        try {
            while (c.moveToNext()) {
                AttachmentStore.deletePaths(c.getString(0), c.isNull(1) ? null : c.getString(1));
            }
        } finally {
            c.close();
        }
    }

    private static String shorten(String text) {
        String flat = text.replace('\n', ' ').trim();
        return flat.length() <= PREVIEW_LENGTH ? flat : flat.substring(0, PREVIEW_LENGTH) + "…";
    }
}
