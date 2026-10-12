package com.oneai.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.widget.EditText;

import com.oneai.R;
import com.oneai.data.Conversation;
import com.oneai.data.Db;
import com.oneai.data.Store;
import com.oneai.provider.Provider;
import com.oneai.provider.Providers;

import java.util.List;

/**
 * Two-step provider/model chooser.
 *
 * <p>Flat dialogs rather than a custom picker screen, because
 * {@code AlertDialog.setItems} is API 1, is fully D-pad navigable for free, and
 * keeps the screen count down -- which matters when every screen is something
 * the user has to navigate back out of with a trackpad.
 */
public final class ModelPicker {

    private ModelPicker() {
    }

    public static void show(final Activity activity, final Conversation conversation) {
        final Provider[] all = Providers.all();
        final CharSequence[] names = new CharSequence[all.length];
        for (int i = 0; i < all.length; i++) {
            names[i] = all[i].displayName();
        }

        new AlertDialog.Builder(activity)
                .setTitle(R.string.section_provider)
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        pickModel(activity, conversation, all[which]);
                    }
                })
                .show();
    }

    private static void pickModel(final Activity activity, final Conversation conversation,
                                  final Provider provider) {
        final List<String> models = provider.knownModels();

        // One extra row for "type it yourself", so a model released after this
        // build can still be used without waiting for an update.
        final CharSequence[] items = new CharSequence[models.size() + 1];
        for (int i = 0; i < models.size(); i++) {
            items[i] = models.get(i);
        }
        items[models.size()] = "Custom…";

        new AlertDialog.Builder(activity)
                .setTitle(provider.displayName())
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == models.size()) {
                            custom(activity, conversation, provider);
                        } else {
                            apply(activity, conversation, provider,
                                    models.get(which));
                        }
                    }
                })
                .show();
    }

    private static void custom(final Activity activity, final Conversation conversation,
                               final Provider provider) {
        final EditText input = new EditText(activity);
        input.setHint(R.string.label_custom_model);
        input.setSingleLine(true);
        if (conversation.model != null) {
            input.setText(conversation.model);
        }

        new AlertDialog.Builder(activity)
                .setTitle(R.string.label_custom_model)
                .setView(input)
                .setPositiveButton(R.string.action_ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String model = input.getText().toString().trim();
                        if (model.length() > 0) {
                            apply(activity, conversation, provider, model);
                        }
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private static void apply(Activity activity, Conversation conversation, Provider provider,
                              String model) {
        Db db = Store.db(activity);
        db.setConversationModel(conversation.id, provider.id(), model);
        conversation.provider = provider.id();
        conversation.model = model;
        if (activity instanceof ChatActivity) {
            ((ChatActivity) activity).onConversationChanged();
        }
    }
}
