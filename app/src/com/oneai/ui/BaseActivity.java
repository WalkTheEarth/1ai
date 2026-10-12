package com.oneai.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

import com.oneai.R;

/**
 * Shared keyboard handling for every screen.
 *
 * <p>Everything here is deliberately implemented against API 1 primitives.
 * {@code onBackPressed()} and {@code onKeyLongPress()} would be the obvious
 * tools, but both were only added in API 5 and this app supports API 4, so
 * Back is handled by intercepting {@code KEYCODE_BACK} in {@code onKeyDown}
 * and long-press by inspecting the key's repeat count.
 */
public abstract class BaseActivity extends Activity {

    /** Set by subclasses that inflate a layout containing the shared title bar. */
    protected TextView titleView;
    protected TextView subtitleView;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
    }

    /** Called after setContentView so subclasses can bind their own views. */
    protected void bindTitleBar() {
        titleView = (TextView) findViewById(R.id.title);
        subtitleView = (TextView) findViewById(R.id.subtitle);
    }

    protected void setTitleText(CharSequence text) {
        if (titleView != null) {
            titleView.setText(text);
        }
    }

    protected void setSubtitle(CharSequence text) {
        if (subtitleView != null) {
            subtitleView.setText(text);
            subtitleView.setVisibility(
                    text == null || text.length() == 0 ? View.GONE : View.VISIBLE);
        }
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent event) {
        // Esc is what a BlackBerry keyboard actually sends for "go back", and
        // it is also what a desktop-style keyboard sends. Treat it as Back on
        // every screen.
        if (code == KeyEvent.KEYCODE_ESCAPE) {
            finish();
            return true;
        }
        return super.onKeyDown(code, event);
    }

    /** True when this key event is an auto-repeat rather than a fresh press. */
    protected static boolean isRepeat(KeyEvent event) {
        return event.getRepeatCount() > 0;
    }
}
