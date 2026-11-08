package org.chameleonos.screenrecorder;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import android.graphics.Typeface;

public class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 900;
    private Spinner fpsSpinner, resolutionSpinner, qualitySpinner, aspectSpinner;
    private TextView status;
    private MediaProjectionManager projectionManager;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        projectionManager = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 28, 24, 24);
        root.setBackgroundColor(0xff101820);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("SCREEN RECORDER");
        title.setTextColor(0xffffffff);
        title.setTextSize(25);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("High-frame-rate capture • performance controls");
        subtitle.setTextColor(0xffb9c7d5);
        subtitle.setTextSize(14);
        subtitle.setPadding(0, 6, 0, 22);
        root.addView(subtitle);

        addLabel(root, "FPS TARGET / FRAME THROTTLE");
        fpsSpinner = spinner(new String[]{"30 FPS","60 FPS","120 FPS","240 FPS","500 FPS (device/encoder permitting)"});
        root.addView(fpsSpinner);

        addLabel(root, "RESOLUTION");
        resolutionSpinner = spinner(new String[]{"Native resolution","720p output (scaled)","6K upscale (experimental; no added detail)"});
        root.addView(resolutionSpinner);

        addLabel(root, "VIDEO BITRATE");
        qualitySpinner = spinner(new String[]{"12 Mbps (high detail)","8 Mbps (balanced)","4 Mbps (lower load)","2 Mbps (lowest load)"});
        root.addView(qualitySpinner);

        addLabel(root, "OUTPUT SHAPE / STRETCH");
        aspectSpinner = spinner(new String[]{"Original aspect ratio","Stretch 16:9 landscape","Stretch 4:3 landscape","Stretch 20:9 portrait","Stretch 9:16 portrait","Stretch 1:1 square"});
        root.addView(aspectSpinner);

        TextView note = new TextView(this);
        note.setText("\n500 FPS is a requested target, not a guarantee. Android screen capture is limited by the display refresh rate and the hardware encoder. The app caps the requested rate to the display refresh and encoder capability. 500 FPS touch sampling cannot be enabled by an ordinary app: the touchscreen controller and Android input driver determine touch sampling. Android also does not let this recorder change swipe DPI or game input latency. 720p scales the captured image down. 6K is an upscale from your screen (no extra detail) and may be rejected by the encoder. A screen recorder cannot record touch events alone as a video; Android screen capture records pixels. No setting can guarantee zero lag or zero crashes on every device.");
        note.setTextColor(0xffd2dbe4);
        note.setTextSize(13);
        root.addView(note);

        status = new TextView(this);
        status.setText("Ready. Start will show Android's screen-sharing consent prompt.");
        status.setTextColor(0xff8ee6a5);
        status.setPadding(0, 18, 0, 18);
        root.addView(status);

        Button start = button("START RECORDING");
        start.setOnClickListener(v -> projectionConsent());
        root.addView(start);

        Button stop = button("STOP RECORDING");
        stop.setOnClickListener(v -> {
            Intent i = new Intent(this, RecorderService.class);
            i.setAction(RecorderService.ACTION_STOP);
            startService(i);
            status.setText("Stopping recording…");
        });
        root.addView(stop);
        setContentView(scroll);
    }

    private void addLabel(LinearLayout root, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xff77c8ff);
        t.setTextSize(12);
        t.setTypeface(null, Typeface.BOLD);
        t.setPadding(0, 16, 0, 6);
        root.addView(t);
    }
    private Spinner spinner(String[] values) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        return s;
    }
    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        return b;
    }
    private void projectionConsent() {
        try { startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CAPTURE); }
        catch (Exception e) { status.setText("Unable to request screen capture: " + e.getMessage()); }
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            status.setText("Screen capture permission was not granted.");
            return;
        }
        int[] rates = {30,60,120,240,500};
        int fps = rates[fpsSpinner.getSelectedItemPosition()];
        int[] scales = {100,720,6000};
        int scale = scales[resolutionSpinner.getSelectedItemPosition()];
        int aspect = aspectSpinner.getSelectedItemPosition();
        int[] bitrates = {12000000,8000000,4000000,2000000};
        int bitrate = bitrates[qualitySpinner.getSelectedItemPosition()];
        Intent service = new Intent(this, RecorderService.class);
        service.setAction(RecorderService.ACTION_START);
        service.putExtra(RecorderService.EXTRA_RESULT_CODE, resultCode);
        service.putExtra(RecorderService.EXTRA_DATA, data);
        service.putExtra(RecorderService.EXTRA_FPS, fps);
        service.putExtra(RecorderService.EXTRA_SCALE, scale);
        service.putExtra(RecorderService.EXTRA_BITRATE, bitrate);
        service.putExtra(RecorderService.EXTRA_ASPECT, aspect);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
            status.setText("Recording started. Use the persistent notification to stop.");
        } catch (Exception e) { status.setText("Could not start recorder: " + e.getMessage()); }
    }
}
