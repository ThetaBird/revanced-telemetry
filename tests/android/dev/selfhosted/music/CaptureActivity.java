package dev.selfhosted.music;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Visible test-only view tree matching the APK's carousel/header ownership. */
public final class CaptureActivity extends Activity {
    View item;
    View outside;
    TextView title;
    FrameLayout header;
    LinearLayout shelf;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        shelf = new LinearLayout(this);
        shelf.setOrientation(LinearLayout.VERTICAL);
        header = new FrameLayout(this);
        header.setId(0x7f0b0550);
        title = new TextView(this);
        title.setId(0x7f0b01ea);
        title.setText("Quick picks");
        header.addView(title);
        shelf.addView(header);
        FrameLayout carousel = new FrameLayout(this);
        carousel.setId(0x7f0b01de);
        item = new TextView(this);
        ((TextView) item).setText("Selected song");
        carousel.addView(item);
        shelf.addView(carousel);
        root.addView(shelf);
        outside = new TextView(this);
        ((TextView) outside).setText("Outside carousel");
        root.addView(outside);
        setContentView(root);
    }
}
