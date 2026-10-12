package com.oneai.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.oneai.R;
import com.oneai.data.Attachment;
import com.oneai.data.AttachmentStore;
import com.oneai.data.Message;
import com.oneai.util.Markdown;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Rows for the message list. */
public final class MessageAdapter extends BaseAdapter {

    private final LayoutInflater inflater;
    private final Context context;
    private final List<Message> items = new ArrayList<Message>();

    /** Attachments keyed by message id, refreshed alongside the messages. */
    private Map<Long, List<Attachment>> attachments = Collections.emptyMap();

    /**
     * While true, the last row is rendered as plain text rather than parsed as
     * Markdown. Re-parsing on every streamed delta would be far too slow on the
     * hardware this targets, so the expensive pass runs once, at the end.
     */
    private boolean lastRowIsStreaming;

    public MessageAdapter(Context context) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
    }

    public void setItems(List<Message> messages) {
        setItems(messages, null);
    }

    public void setItems(List<Message> messages, Map<Long, List<Attachment>> byMessage) {
        items.clear();
        if (messages != null) {
            items.addAll(messages);
        }
        attachments = byMessage == null
                ? Collections.<Long, List<Attachment>>emptyMap() : byMessage;
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

        bindAttachments(row, m);

        return row;
    }

    /**
     * Draws the thumbnail strip under a message.
     *
     * <p>Views are rebuilt rather than recycled per row. The strip is usually
     * one or two items, and trying to reuse them across roles would cost more
     * in complexity than it saves on a screen this small.
     */
    private void bindAttachments(View row, Message m) {
        LinearLayout strip = (LinearLayout) row.findViewById(R.id.msg_attachments);
        strip.removeAllViews();

        List<Attachment> files = attachments.get(Long.valueOf(m.id));
        if (files == null || files.isEmpty()) {
            strip.setVisibility(View.GONE);
            return;
        }
        strip.setVisibility(View.VISIBLE);

        int size = (int) (56 * context.getResources().getDisplayMetrics().density + 0.5f);
        for (int i = 0; i < files.size(); i++) {
            final Attachment a = files.get(i);
            View chip;
            Bitmap thumb = AttachmentStore.thumbnail(a);
            if (thumb != null) {
                ImageView image = new ImageView(context);
                image.setImageBitmap(thumb);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                chip = image;
            } else {
                TextView label = new TextView(context);
                label.setText("PDF");
                label.setTextSize(10);
                label.setTextColor(context.getResources().getColor(R.color.text_secondary));
                label.setGravity(android.view.Gravity.CENTER);
                chip = label;
            }
            chip.setFocusable(true);
            chip.setClickable(true);
            chip.setContentDescription(a.describe());

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.rightMargin = (int) (4 * context.getResources().getDisplayMetrics().density);
            chip.setLayoutParams(lp);

            // Only images are worth opening; a PDF has nothing to show yet.
            if (thumb != null) {
                chip.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Intent intent = new Intent(context, ImageViewerActivity.class);
                        intent.putExtra(ImageViewerActivity.EXTRA_PATH, a.path);
                        intent.putExtra(ImageViewerActivity.EXTRA_NAME, a.fileName);
                        context.startActivity(intent);
                    }
                });
            }
            strip.addView(chip);
        }
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
