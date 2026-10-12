package com.oneai.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.oneai.R;

/**
 * Shows an attachment at full size.
 *
 * <p>Deliberately its own Activity with its own ImageView rather than handing
 * the file to another app. Passing a {@code file://} URI out of the process
 * throws FileUriExposedException from API 24 onwards, and the fix for that is
 * a ContentProvider -- a lot of machinery to display a picture we already own.
 * We can simply read the file ourselves, on every version, with no permissions
 * and no exposure.
 */
public class ImageViewerActivity extends BaseActivity {

    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_NAME = "name";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_image);
        bindTitleBar();

        String path = getIntent().getStringExtra(EXTRA_PATH);
        String name = getIntent().getStringExtra(EXTRA_NAME);
        setTitleText(name == null || name.length() == 0 ? "Attachment" : name);

        final ImageView view = (ImageView) findViewById(R.id.image);
        if (path == null) {
            finish();
            return;
        }

        // Decode at roughly the size of the screen rather than at the stored
        // 1568px. A phone with a 320x480 display gains nothing from the larger
        // bitmap and would allocate several megabytes to show it.
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);

        int screen = Math.max(getWindowManager().getDefaultDisplay().getWidth(),
                getWindowManager().getDefaultDisplay().getHeight());
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, screen);

        Bitmap bitmap = null;
        try {
            bitmap = BitmapFactory.decodeFile(path, options);
        } catch (OutOfMemoryError oom) {
            // Fall through to the message below rather than crashing.
        }

        if (bitmap == null) {
            TextView message = new TextView(this);
            message.setText("That image could not be opened.");
            message.setTextColor(getResources().getColor(R.color.text_primary));
            message.setPadding(16, 16, 16, 16);
            setContentView(message);
            return;
        }
        view.setImageBitmap(bitmap);
    }

    static int sampleSize(int width, int height, int target) {
        int sample = 1;
        while (target > 0 && Math.max(width, height) / (sample * 2) >= target) {
            sample *= 2;
        }
        return sample;
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent event) {
        // Arrow keys and Esc close it, so a BlackBerry user is never stuck in
        // a full-screen image with no obvious way back.
        if (code == KeyEvent.KEYCODE_ESCAPE || code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER) {
            finish();
            return true;
        }
        return super.onKeyDown(code, event);
    }
}
