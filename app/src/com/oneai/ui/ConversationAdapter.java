package com.oneai.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.oneai.R;
import com.oneai.data.Conversation;
import com.oneai.provider.Provider;
import com.oneai.provider.Providers;

import java.util.ArrayList;
import java.util.List;

/** Rows for the conversation list. */
public final class ConversationAdapter extends BaseAdapter {

    private final LayoutInflater inflater;
    private final List<Conversation> items = new ArrayList<Conversation>();

    public ConversationAdapter(Context context) {
        this.inflater = LayoutInflater.from(context);
    }

    public void setItems(List<Conversation> conversations) {
        items.clear();
        if (conversations != null) {
            items.addAll(conversations);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Object getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).id;
    }

    public Conversation conversationAt(int position) {
        return items.get(position);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View row = convertView;
        if (row == null) {
            row = inflater.inflate(R.layout.row_conversation, parent, false);
        }

        Conversation c = items.get(position);
        TextView title = (TextView) row.findViewById(R.id.row_title);
        TextView subtitle = (TextView) row.findViewById(R.id.row_subtitle);

        title.setText(c.title);

        Provider provider = Providers.byIdOrDefault(c.provider);
        String model = c.model != null ? c.model : provider.defaultModel();
        StringBuilder meta = new StringBuilder(provider.displayName()).append(" · ").append(model);

        // The preview is what actually distinguishes one thread from another,
        // so it goes on its own line rather than being truncated into the
        // metadata line.
        if (c.preview != null && c.preview.length() > 0) {
            row.findViewById(R.id.row_preview).setVisibility(View.VISIBLE);
            ((TextView) row.findViewById(R.id.row_preview)).setText(c.preview);
        } else {
            row.findViewById(R.id.row_preview).setVisibility(View.GONE);
        }

        subtitle.setText(meta);
        return row;
    }
}
