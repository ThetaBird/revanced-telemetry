package dev.selfhosted.music;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** Native settings form opened from Music's existing preference screen. */
@SuppressWarnings("deprecation")
public final class TelemetrySettings {
    private static final String KEY = "selfhosted_music_telemetry";
    private TelemetrySettings() {}

    /** Return false for every unrelated preference, preserving the host click path. */
    public static boolean onPreferenceClick(Context context, String key) {
        if (!KEY.equals(key)) return false;
        try {
            for (int depth = 0; depth < 16 && context != null; depth++) {
                if (context instanceof Activity) {
                    show((Activity) context);
                    return true;
                }
                if (!(context instanceof ContextWrapper)) break;
                Context next = ((ContextWrapper) context).getBaseContext();
                if (next == context) break;
                context = next;
            }
        } catch (Throwable failure) {
            android.util.Log.w("MusicTelemetry", "Cannot open telemetry settings (" + failure.getClass().getSimpleName() + ")");
            if (context != null) android.widget.Toast.makeText(context, "Cannot open telemetry settings. Please reopen Music and try again.", android.widget.Toast.LENGTH_LONG).show();
            return true;
        }
        android.util.Log.w("MusicTelemetry", "Cannot open telemetry settings: Activity unavailable");
        return false;
    }

    public static void show(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        final int spacing = (int) (16 * activity.getResources().getDisplayMetrics().density);
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(spacing, spacing, spacing, spacing);
        Switch enabled = new Switch(activity);
        enabled.setText("Enable telemetry");
        enabled.setTag("telemetry_enabled");
        enabled.setSaveEnabled(false);
        form.addView(enabled);
        label(form, "Endpoint URL", spacing);
        EditText endpoint = new EditText(activity);
        endpoint.setTag("telemetry_endpoint");
        endpoint.setContentDescription("Endpoint URL");
        endpoint.setSingleLine(true);
        endpoint.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        endpoint.setHint("HTTPS URL ending in /api/events");
        endpoint.setSaveEnabled(false);
        endpoint.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        form.addView(endpoint);
        label(form, "Listen write token", spacing);
        EditText token = new EditText(activity);
        token.setTag("telemetry_token");
        token.setContentDescription("Listen write token");
        token.setSingleLine(true);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setSaveEnabled(false);
        token.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        form.addView(token);
        TextView explanation = new TextView(activity);
        explanation.setPadding(0, spacing, 0, spacing);
        explanation.setText("Save applies changes without restarting. Turning telemetry off pauses capture and uploads. "
                + "A request already in progress may finish. Changing the URL discards queued events for the previous server. "
                + "Your token stays in this app's private storage and is excluded from Android backup.");
        form.addView(explanation);
        TextView status = new TextView(activity);
        status.setTag("telemetry_status");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setText("Loading settings…");
        form.addView(status);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Listen telemetry")
                .setView(scroll)
                .setNegativeButton("Cancel", (unused, which) -> {})
                .setPositiveButton("Save", null)
                .create();
        dialog.setOnShowListener(unused -> {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            enabled.setEnabled(false);
            endpoint.setEnabled(false);
            token.setEnabled(false);
            Telemetry.loadSettings(activity, (config, error) -> {
                if (!dialog.isShowing() || activity.isDestroyed()) return;
                if (config != null) {
                    endpoint.setText(config.endpoint);
                    token.setText(config.token);
                    enabled.setChecked(config.enabled);
                }
                status.setText(error == null ? "" : error);
                enabled.setEnabled(true);
                endpoint.setEnabled(true);
                token.setEnabled(true);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                TelemetryConfig config = new TelemetryConfig(endpoint.getText().toString(), token.getText().toString(), enabled.isChecked());
                String invalid = config.validationError();
                if (invalid != null) { status.setText(invalid); return; }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                status.setText("Saving settings…");
                Telemetry.saveSettings(activity, config, (saved, error) -> {
                    if (!dialog.isShowing() || activity.isDestroyed()) return;
                    if (error == null) dialog.dismiss();
                    else {
                        status.setText(error);
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    }
                });
            });
        });
        dialog.show();
    }

    private static void label(LinearLayout parent, String text, int spacing) {
        TextView label = new TextView(parent.getContext());
        label.setText(text);
        label.setTypeface(null, Typeface.BOLD);
        label.setPadding(0, spacing, 0, 0);
        parent.addView(label);
    }
}
