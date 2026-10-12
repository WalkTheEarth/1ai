package com.oneai.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.oneai.R;
import com.oneai.data.Message;
import com.oneai.util.Markdown;

import java.util.ArrayList;
import java.util.List;

/** Rows for the message list. */
public final class MessageAdapter extends BaseAdapter {

    private final LayoutInflater inflater;
    private final List<Message> items = new ArrayList<Message>();

    /**
     * While true, the last row is rendered as plain text rather than parsed as
     * Markdown. Re-parsing on every streamed delta would be far too slow on the
     * hardware this targets, so the expensive pass runs once, at the end.
     */
    private boolean lastRowIsStreaming;

    public MessageAdapter(Context context) {
        this.inflater = LayoutInflater.from(context);
    }

    public void setItems(List<Message> messages) {
        items.clear();
        if (messages != null) {
            items.addAll(messages);
        }
        lastRowIsStreaming = false;
        notifyDataSetChanged();
    }

    public void add(Message message) {
        items.add(message);
        notifyDataSetChanged();
    }

    public void removeLast() {
        if (!items.isEmpty()) {
            items.remove(items.size() - 1);
            notifyDataSetChanged();
        }
    }

    public Message last() {
        return items.isEmpty() ? null : items.get(items.size() - 1);
    }

    public Message at(int position) {
        return position >= 0 && position < items.size() ? items.get(position) : null;
    }

    public void setLastStreaming(boolean streaming) {
        lastRowIsStreaming = streaming;
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

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View row = convertView;
        if (row == null) {
            row = inflater.inflate(R.layout.row_message, parent, false);
        }

        Message m = items.get(position);
        TextView meta = (TextView) row.findViewById(R.id.msg_meta);
        TextView body = (TextView) row.findViewById(R.id.msg_body);

        int bubble;
        if (m.isError()) {
            bubble = R.drawable.bubble_error;
        } else if (m.isUser()) {
            bubble = R.drawable.bubble_user;
        } else {
            bubble = R.drawable.bubble_assistant;
        }
        // setBackgroundDrawable, not setBackground: the latter is API 16 and
        // would throw NoSuchMethodError on everything this app targets.
        body.setBackgroundDrawable(row.getResources().getDrawable(bubble));

        meta.setText(buildMeta(m));

        boolean streaming = lastRowIsStreaming && position == items.size() - 1;
        String text = m.content == null ? "" : m.content;
        if (streaming) {
            body.setText(text);
        } else {
            body.setText(Markdown.render(text));
        }

        return row;
    }

    private String buildMeta(Message m) {
        StringBuilder sb = new StringBuilder();
        if (m.isUser()) {
            sb.append("You");
        } else if (m.isError()) {
            sb.append("Error");
        } else {
            if (m.model != null && m.model.length() > 0) {
                sb.append(m.model);
            } else {
                sb.append("Assistant");
            }
            if (m.inputTokens > 0 || m.outputTokens > 0) {
                sb.append("  ·  ").append(m.inputTokens).append(" in / ")
                        .append(m.outputTokens).append(" out");
            }
        }
        return sb.toString();
    }
}
