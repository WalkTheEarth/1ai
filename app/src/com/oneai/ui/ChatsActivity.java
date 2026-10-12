package com.oneai.ui;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import com.oneai.R;
import com.oneai.data.Conversation;
import com.oneai.data.Db;
import com.oneai.data.KeyImport;
import com.oneai.data.KeySync;
import com.oneai.data.Prefs;
import com.oneai.data.Store;
import com.oneai.provider.Provider;
import com.oneai.provider.Providers;

/** Conversation list. This is the launcher activity. */
public class ChatsActivity extends BaseActivity {

    private ListView list;
    private View empty;
    private ConversationAdapter adapter;
    private Db db;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_chats);
        bindTitleBar();

        db = Store.db(this);
        list = (ListView) findViewById(R.id.list);
        empty = findViewById(R.id.empty);
        adapter = new ConversationAdapter(this);
        list.setAdapter(adapter);

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                open(adapter.conversationAt(position));
            }
        });

        // Long-press is the touch route to rename/delete; the keyboard route is
        // the menu key, and both land in the same place.
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                showConversationMenu(adapter.conversationAt(position));
                return true;
            }
        });

        View newButton = findViewById(R.id.btn_action);
        newButton.setVisibility(View.VISIBLE);
        ((TextView) newButton).setText(R.string.action_new);
        newButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newConversation();
            }
        });

        View settingsButton = findViewById(R.id.btn_action2);
        settingsButton.setVisibility(View.VISIBLE);
        ((TextView) settingsButton).setText(R.string.action_settings);
        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(ChatsActivity.this, SettingsActivity.class));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        adapter.setItems(db.conversations());
        boolean nothing = adapter.getCount() == 0;
        empty.setVisibility(nothing ? View.VISIBLE : View.GONE);
        list.setVisibility(nothing ? View.GONE : View.VISIBLE);
        if (!nothing) {
            // Restore the focus ring so the trackpad keeps working after a
            // dialog or after returning from another screen.
            if (!list.hasFocus()) {
                list.requestFocus();
            }
        }
    }

    private void newConversation() {
        Prefs prefs = Store.prefs(this);
        final String providerId = prefs.defaultProvider();
        Provider provider = Providers.byIdOrDefault(providerId);

        final EditText input = new EditText(this);
        input.setHint(R.string.hint_rename);
        input.setText(provider.displayName());
        input.setSingleLine(true);

        new AlertDialog.Builder(this)
                .setTitle(R.string.action_new)
                .setView(input)
                .setPositiveButton(R.string.action_ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String title = input.getText().toString().trim();
                        if (title.length() == 0) {
                            title = "Chat";
                        }
                        long id = db.createConversation(providerId, title);
                        Conversation created = db.conversation(id);
                        open(created);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void open(Conversation conversation) {
        if (conversation == null) {
            return;
        }
        Intent i = new Intent(this, ChatActivity.class);
        i.putExtra(ChatActivity.EXTRA_CONVERSATION_ID, conversation.id);
        startActivity(i);
    }

    private void showConversationMenu(final Conversation conversation) {
        final CharSequence[] items = {
                getString(R.string.action_rename),
                getString(R.string.action_model),
                getString(R.string.action_settings),
                getString(R.string.action_delete),
        };
        new AlertDialog.Builder(this)
                .setTitle(conversation.title)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        switch (which) {
                            case 0:
                                rename(conversation);
                                break;
                            case 1:
                                ModelPicker.show(ChatsActivity.this, conversation);
                                break;
                            case 2:
                                startActivity(new Intent(ChatsActivity.this,
                                        SettingsActivity.class));
                                break;
                            default:
                                confirmDelete(conversation);
                                break;
                        }
                    }
                })
                .show();
    }

    private void rename(final Conversation conversation) {
        final EditText input = new EditText(this);
        input.setText(conversation.title);
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.action_rename)
                .setView(input)
                .setPositiveButton(R.string.action_ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String title = input.getText().toString().trim();
                        if (title.length() > 0) {
                            db.renameConversation(conversation.id, title);
                            reload();
                        }
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void confirmDelete(final Conversation conversation) {
        new AlertDialog.Builder(this)
                .setTitle(conversation.title)
                .setMessage("Delete this conversation and all of its messages?")
                .setPositiveButton(R.string.action_delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        db.deleteConversation(conversation.id);
                        reload();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private Conversation selected() {
        int position = list.getSelectedItemPosition();
        if (position == AdapterView.INVALID_POSITION || position >= adapter.getCount()) {
            return null;
        }
        return adapter.conversationAt(position);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent event) {
        if (isRepeat(event)) {
            return super.onKeyDown(code, event);
        }

        // Letter shortcuts are safe on this screen because nothing here takes
        // free text input.
        switch (code) {
            case KeyEvent.KEYCODE_N:
                newConversation();
                return true;
            case KeyEvent.KEYCODE_R: {
                Conversation c = selected();
                if (c != null) {
                    rename(c);
                    return true;
                }
                break;
            }
            case KeyEvent.KEYCODE_DEL: {
                Conversation c = selected();
                if (c != null) {
                    confirmDelete(c);
                    return true;
                }
                break;
            }
            case KeyEvent.KEYCODE_MENU: {
                Conversation c = selected();
                if (c != null) {
                    showConversationMenu(c);
                    return true;
                }
                break;
            }
            default:
                break;
        }
        return super.onKeyDown(code, event);
    }
}
