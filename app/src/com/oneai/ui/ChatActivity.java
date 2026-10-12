package com.oneai.ui;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.content.DialogInterface;
import android.os.Bundle;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.ClipboardManager;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import com.oneai.R;
import com.oneai.chat.StreamRunner;
import com.oneai.data.Attachment;
import com.oneai.data.AttachmentStore;
import com.oneai.data.Conversation;
import com.oneai.data.Db;
import com.oneai.data.Message;
import com.oneai.data.Prefs;
import com.oneai.data.Store;
import com.oneai.provider.Provider;
import com.oneai.provider.ProviderException;
import com.oneai.provider.Providers;

import java.util.ArrayList;
import java.util.List;

/** One conversation, with streaming replies. */
public class ChatActivity extends BaseActivity {

    public static final String EXTRA_CONVERSATION_ID = "conversation_id";

    /** Cap on UI updates per second while a reply streams in. */
    private static final int FLUSH_INTERVAL_MS = 100;

    private Db db;
    private Conversation conversation;
    private ListView list;
    private MessageAdapter adapter;
    private EditText input;
    private TextView sendButton;

    private StreamRunner runner;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private static final int REQUEST_ATTACH = 1;

    /** Picked but not yet sent. Files are deleted if the screen is left first. */
    private final List<Attachment> pending = new ArrayList<Attachment>();
    private LinearLayout pendingStrip;
    private View pendingScroll;

    private final StringBuilder streamingText = new StringBuilder();
    private Message streamingMessage;
    private boolean flushScheduled;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_chat);
        bindTitleBar();

        db = Store.db(this);
        long id = getIntent().getLongExtra(EXTRA_CONVERSATION_ID, -1);
        conversation = db.conversation(id);
        if (conversation == null) {
            finish();
            return;
        }

        list = (ListView) findViewById(R.id.message_list);
        adapter = new MessageAdapter(this);
        list.setAdapter(adapter);
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int pos, long itemId) {
                showMessageMenu(adapter.at(pos));
                return true;
            }
        });

        input = (EditText) findViewById(R.id.input);
        input.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int code, KeyEvent event) {
                if (code == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN) {
                    // Enter sends. Shift+Enter and Alt+Enter insert a newline,
                    // which is the only way to write a multi-line message on a
                    // hardware keyboard that has no other newline key.
                    if (event.isShiftPressed() || event.isAltPressed()) {
                        return false;
                    }
                    if (!isRepeat(event)) {
                        send();
                    }
                    return true;
                }
                return false;
            }
        });

        pendingStrip = (LinearLayout) findViewById(R.id.pending);
        pendingScroll = findViewById(R.id.pending_scroll);

        findViewById(R.id.attach).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickAttachment();
            }
        });

        sendButton = (TextView) findViewById(R.id.send);
        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (runner != null && runner.isRunning()) {
                    stop();
                } else {
                    send();
                }
            }
        });

        runner = new StreamRunner(this);
        updateSubtitle();
        reload();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (runner != null) {
            // dispose(), not cancel(): leaving the screen should release the
            // worker thread, whereas Stop only ends the current stream.
            runner.dispose();
        }
        // Anything picked but never sent leaves a file on disk with no row
        // pointing at it, and nothing would ever find it again.
        for (int i = 0; i < pending.size(); i++) {
            AttachmentStore.delete(pending.get(i));
        }
        pending.clear();
    }

    /** Called by ModelPicker after the provider or model changes. */
    public void onConversationChanged() {
        updateSubtitle();
    }

    private void updateSubtitle() {
        Provider provider = Providers.byIdOrDefault(conversation.provider);
        String model = conversation.model != null ? conversation.model
                : Store.prefs(this).modelOverride(provider.id());
        if (model == null) {
            model = provider.defaultModel();
        }
        setTitleText(conversation.title);
        setSubtitle(provider.displayName() + " · " + model);
    }

    private void reload() {
        adapter.setItems(db.messages(conversation.id),
                db.attachmentsForConversation(conversation.id));
        scrollToBottom();
    }

    // ------------------------------------------------------------- sending

    private void send() {
        if (runner.isRunning()) {
            return;
        }
        String text = input.getText().toString().trim();
        if (text.length() == 0 && pending.isEmpty()) {
            return;
        }
        // Sending an image with no text at all is normal; give the model
        // something to act on rather than an empty turn.
        if (text.length() == 0) {
            text = "What is in this?";
        }

        Message user = new Message(Message.ROLE_USER, text);
        user.conversationId = conversation.id;
        user.createdAt = System.currentTimeMillis();
        user.id = db.addMessage(user);

        // Attachments are only linked once the message has an id to link to.
        for (int i = 0; i < pending.size(); i++) {
            Attachment a = pending.get(i);
            a.messageId = user.id;
            a.id = db.addAttachment(a);
        }
        pending.clear();
        renderPending();

        db.updatePreview(conversation.id, text);
        reload();
        input.setText("");
        scrollToBottom();

        runStream();
    }

    /** Re-sends the conversation, dropping the last assistant turn if present. */
    private void retry() {
        if (runner.isRunning()) {
            return;
        }
        Message last = adapter.last();
        if (last != null && !last.isUser()) {
            db.deleteMessage(last.id);
            adapter.removeLast();
        }
        runStream();
    }

    private void runStream() {
        List<Message> history = db.messages(conversation.id);

        streamingText.setLength(0);
        streamingMessage = new Message(Message.ROLE_ASSISTANT, "");
        streamingMessage.conversationId = conversation.id;
        streamingMessage.createdAt = System.currentTimeMillis();
        streamingMessage.model = conversation.model;
        streamingMessage.provider = conversation.provider;
        adapter.add(streamingMessage);
        adapter.setLastStreaming(true);
        scrollToBottom();

        setComposerBusy(true);

        runner.start(conversation, history, new StreamRunner.Callback() {
            @Override
            public void onText(String delta) {
                streamingText.append(delta);
                scheduleFlush();
            }

            @Override
            public void onThinking(String delta) {
                // Shown only when the user asked for it; appended inline so it
                // is visibly distinct from the answer.
                if (streamingText.length() == 0) {
                    streamingText.append(getString(R.string.thinking)).append(": ");
                }
                streamingText.append(delta);
                scheduleFlush();
            }

            @Override
            public void onDone(String stopReason, int inputTokens, int outputTokens) {
                finishStream(inputTokens, outputTokens, null);
            }

            @Override
            public void onError(ProviderException error) {
                finishStream(0, 0, error);
            }
        });
    }

    private void finishStream(int inputTokens, int outputTokens, ProviderException error) {
        setComposerBusy(false);

        adapter.setLastStreaming(false);

        if (error != null) {
            // Keep whatever text arrived before the failure, then show why it
            // stopped. Discarding a partial answer loses real work.
            if (streamingText.length() > 0) {
                streamingMessage.content = streamingText.toString();
                db.addMessage(streamingMessage);
                adapter.notifyDataSetChanged();
            } else {
                adapter.removeLast();
            }
            Message notice = new Message(Message.ROLE_ERROR, error.friendly);
            notice.conversationId = conversation.id;
            notice.createdAt = System.currentTimeMillis();
            notice.id = db.addMessage(notice);
            adapter.add(notice);
            db.updatePreview(conversation.id, error.friendly);
            scrollToBottom();
            return;
        }

        streamingMessage.content = streamingText.toString();
        streamingMessage.inputTokens = inputTokens;
        streamingMessage.outputTokens = outputTokens;
        streamingMessage.id = db.addMessage(streamingMessage);
        adapter.notifyDataSetChanged();
        db.updatePreview(conversation.id, streamingMessage.content);
        scrollToBottom();

        input.requestFocus();
    }

    private void stop() {
        runner.cancel();
        finishStream(0, 0, null);
        setComposerBusy(false);
    }

    private void setComposerBusy(boolean busy) {
        sendButton.setText(busy ? R.string.action_stop : R.string.action_send);
        // The input deliberately stays enabled and focused while streaming.
        // Disabling it would drop keyboard focus, which on a BlackBerry means
        // the user's next keystroke goes somewhere unhelpful. Sends are
        // blocked by the runner check in send() instead.
        if (!busy) {
            input.requestFocus();
        }
    }

    // ----------------------------------------------------------- streaming

    /**
     * Coalesces streamed deltas into at most {@link #FLUSH_INTERVAL_MS} UI
     * updates. Re-laying out a TextView on every token would visibly stutter on
     * a 2010 CPU.
     */
    private void scheduleFlush() {
        if (flushScheduled) {
            return;
        }
        flushScheduled = true;
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                flushScheduled = false;
                if (streamingMessage != null) {
                    boolean atBottom = isAtBottom();
                    streamingMessage.content = streamingText.toString();
                    adapter.notifyDataSetChanged();
                    if (atBottom) {
                        scrollToBottom();
                    }
                }
            }
        }, FLUSH_INTERVAL_MS);
    }

    private boolean isAtBottom() {
        if (adapter.getCount() == 0) {
            return true;
        }
        return list.getLastVisiblePosition() >= adapter.getCount() - 2;
    }

    private void scrollToBottom() {
        if (adapter.getCount() > 0) {
            list.setSelection(adapter.getCount() - 1);
        }
    }

    // --------------------------------------------------------------- menus

    private void showMenu() {
        final CharSequence[] items = {
                getString(R.string.action_attach_file),
                getString(R.string.action_model),
                getString(R.string.action_system_prompt),
                getString(R.string.action_retry),
                getString(R.string.action_copy),
                getString(R.string.action_clear),
                getString(R.string.action_settings),
        };
        new AlertDialog.Builder(this)
                .setTitle(conversation.title)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        switch (which) {
                            case 0:
                                pickAttachment();
                                break;
                            case 1:
                                ModelPicker.show(ChatActivity.this, conversation);
                                break;
                            case 2:
                                editSystemPrompt();
                                break;
                            case 3:
                                retry();
                                break;
                            case 4:
                                copyLast();
                                break;
                            case 5:
                                confirmClear();
                                break;
                            default:
                                startActivity(new android.content.Intent(ChatActivity.this,
                                        SettingsActivity.class));
                                break;
                        }
                    }
                })
                .show();
    }

    private void showMessageMenu(final Message message) {
        final CharSequence[] items = {
                getString(R.string.action_copy),
                "Resend from here",
                "Delete from here",
        };
        new AlertDialog.Builder(this)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            copy(message.content);
                        } else if (which == 1) {
                            db.deleteMessagesAfter(conversation.id, message.id);
                            reload();
                            retry();
                        } else {
                            db.deleteMessagesAfter(conversation.id, message.id);
                            db.deleteMessage(message.id);
                            reload();
                        }
                    }
                })
                .show();
    }

    private void editSystemPrompt() {
        final EditText editor = new EditText(this);
        editor.setText(conversation.systemPrompt != null ? conversation.systemPrompt
                : Store.prefs(this).systemPrompt());
        editor.setMinLines(3);
        editor.setHint(R.string.label_system_prompt);
        new AlertDialog.Builder(this)
                .setTitle(R.string.action_system_prompt)
                .setView(editor)
                .setPositiveButton(R.string.action_save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String value = editor.getText().toString().trim();
                        db.setConversationSystem(conversation.id, value);
                        conversation.systemPrompt = value;
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    // ---------------------------------------------------------- attachments

    private void pickAttachment() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // "*/*" rather than an image type so PDFs are reachable too. The store
        // sniffs the actual bytes and refuses anything it cannot send, so a
        // wide filter costs nothing but a clearer error.
        intent.setType("*/*");
        if (android.os.Build.VERSION.SDK_INT >= 19) {
            intent.putExtra(Intent.EXTRA_MIME_TYPES,
                    new String[]{"image/*", "application/pdf"});
        }
        try {
            startActivityForResult(intent, REQUEST_ATTACH);
        } catch (ActivityNotFoundException noPicker) {
            showMessage("No file picker on this device.");
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (request != REQUEST_ATTACH || result != RESULT_OK || data == null
                || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        final String name = displayName(uri);

        // Shrinking and re-encoding an image is far too slow for the UI thread;
        // a 12-megapixel photo would freeze the app for seconds.
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final Attachment a = AttachmentStore.ingest(ChatActivity.this, uri, name);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            pending.add(a);
                            renderPending();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            showMessage(String.valueOf(e.getMessage()));
                        }
                    });
                }
            }
        }).start();
    }

    private String displayName(Uri uri) {
        android.database.Cursor c = null;
        try {
            c = getContentResolver().query(uri,
                    new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                    null, null, null);
            if (c != null && c.moveToFirst()) {
                int index = c.getColumnIndex(
                        android.provider.OpenableColumns.DISPLAY_NAME);
                if (index >= 0 && !c.isNull(index)) {
                    return c.getString(index);
                }
            }
        } catch (Exception ignored) {
            // Fall back to the last path segment below.
        } finally {
            if (c != null) {
                c.close();
            }
        }
        String last = uri.getLastPathSegment();
        return last != null && last.length() > 0 ? last : "attachment";
    }

    /** Renders the thumbnails of anything picked but not yet sent. */
    private void renderPending() {
        pendingStrip.removeAllViews();
        if (pending.isEmpty()) {
            pendingScroll.setVisibility(View.GONE);
            return;
        }
        pendingScroll.setVisibility(View.VISIBLE);

        for (int i = 0; i < pending.size(); i++) {
            final Attachment a = pending.get(i);
            View chip;
            Bitmap thumb = AttachmentStore.thumbnail(a);
            if (thumb != null) {
                ImageView image = new ImageView(this);
                image.setImageBitmap(thumb);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                chip = image;
            } else {
                // No thumbnail means it is not an image, so show what it is
                // instead of a meaningless placeholder graphic.
                TextView label = new TextView(this);
                label.setText("PDF");
                label.setTextSize(11);
                label.setTextColor(getResources().getColor(R.color.btn_text));
                label.setBackgroundDrawable(
                        getResources().getDrawable(R.drawable.btn_bg));
                label.setGravity(android.view.Gravity.CENTER);
                chip = label;
            }
            chip.setFocusable(true);
            chip.setClickable(true);
            chip.setContentDescription("Remove " + a.describe());

            int size = dp(40);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.rightMargin = dp(4);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pending.remove(a);
                    AttachmentStore.delete(a);
                    renderPending();
                }
            });
            pendingStrip.addView(chip);
        }
    }

    private void showMessage(String text) {
        new AlertDialog.Builder(this)
                .setMessage(text)
                .setPositiveButton(R.string.action_ok, null)
                .show();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setMessage("Delete all messages in this conversation?")
                .setPositiveButton(R.string.action_delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        db.clearMessages(conversation.id);
                        reload();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void copyLast() {
        Message last = adapter.last();
        if (last != null) {
            copy(last.content);
        }
    }

    private void copy(String text) {
        if (text == null) {
            return;
        }
        // The clipboard API changed class at API 11: the old
        // android.text.ClipboardManager is not what getSystemService returns
        // from Honeycomb onwards, so casting blindly crashes there.
        if (android.os.Build.VERSION.SDK_INT >= 11) {
            android.content.ClipboardManager modern =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            modern.setPrimaryClip(android.content.ClipData.newPlainText("1AI", text));
        } else {
            ClipboardManager legacy = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            legacy.setText(text);
        }
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent event) {
        if (isRepeat(event)) {
            return super.onKeyDown(code, event);
        }

        if (code == KeyEvent.KEYCODE_MENU) {
            showMenu();
            return true;
        }
        if (event.isCtrlPressed()) {
            if (code == KeyEvent.KEYCODE_R) {
                retry();
                return true;
            }
            if (code == KeyEvent.KEYCODE_K) {
                ModelPicker.show(this, conversation);
                return true;
            }
        }
        return super.onKeyDown(code, event);
    }
}
