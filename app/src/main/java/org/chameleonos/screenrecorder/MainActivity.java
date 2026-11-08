package org.chameleonos.screenrecorder;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import android.graphics.Typeface;

public class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 900;
    private Spinner fpsSpinner, resolutionSpinner, qualitySpinner, aspectSpinner, frameSkipSpinner;
    private CheckBox fastRenderCheck;
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
        subtitle.setText("Stable screen capture • performance controls");
        subtitle.setTextColor(0xffb9c7d5);
        subtitle.setTextSize(14);
        subtitle.setPadding(0, 6, 0, 22);
        root.addView(subtitle);

        addLabel(root, "FPS TARGET");
        fpsSpinner = spinner(new String[]{"30 FPS","60 FPS","120 FPS","240 FPS","500 FPS target (hardware limited)"});
        root.addView(fpsSpinner);

        addLabel(root, "FRAME SKIP");
        frameSkipSpinner = spinner(new String[]{"Off","Skip 1 of every 2 frames (half rate)","Skip 2 of every 3 frames (one-third rate)"});
        root.addView(frameSkipSpinner);

        fastRenderCheck = new CheckBox(this);
        fastRenderCheck.setText("Fast Render (favor stability and lower recording load)");
        fastRenderCheck.setTextColor(0xffffffff);
        fastRenderCheck.setPadding(0, 12, 0, 8);
        root.addView(fastRenderCheck);

        addLabel(root, "RESOLUTION");
        resolutionSpinner = spinner(new String[]{"Native resolution","720p output (scaled)","6K upscale (experimental; no added detail)"});
        root.addView(resolutionSpinner);

        addLabel(root, "VIDEO BITRATE");
        qualitySpinner = spinner(new String[]{"12 Mbps (high detail)","8 Mbps (balanced)","4 Mbps (lower load)","2 Mbps (lowest load)"});
        root.addView(qualitySpinner);

        addLabel(root, "OUTPUT SHAPE / STRETCH");
        aspectSpinner = spinner(new String[]{"Original aspect ratio","16:9 landscape","4:3 landscape","20:9 portrait","9:16 portrait","1:1 square"});
        root.addView(aspectSpinner);

        TextView note = new TextView(this);
        note.setText("\nFrame Skip reduces the output frame rate; it does not change game rendering. Fast Render uses conservative recording settings to reduce encoder load. Android limits capture to the display and encoder capabilities. 500 FPS is only a target, not a promise. A normal app cannot overclock touch sampling, bypass touch DPI, change swipe hardware, or remove game input lag. 6K is an upscale and may be unsupported. Stretch changes the output canvas, not the game's rendered resolution.");
        note.setTextColor(0xffd2dbe4);
        note.setTextSize(13);
        root.addView(note);

        status = new TextView(this);
        status.setText("Ready. Start will show Android's screen-sharing prompt.");
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
            try {
                startService(i);
                status.setText("Stop requested.");
            } catch (Exception e) { status.setText("Could not stop recorder: " + e.getMessage()); }
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
        try {
            if (projectionManager == null) throw new IllegalStateException("Screen capture is unavailable on this device");
            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CAPTURE);
        } catch (Exception e) { status.setText("Unable to request screen capture: " + e.getMessage()); }
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
        int skip = frameSkipSpinner.getSelectedItemPosition() + 1;
        Intent service = new Intent(this, RecorderService.class);
        service.setAction(RecorderService.ACTION_START);
        service.putExtra(RecorderService.EXTRA_RESULT_CODE, resultCode);
        service.putExtra(RecorderService.EXTRA_DATA, data);
        service.putExtra(RecorderService.EXTRA_FPS, fps);
        service.putExtra(RecorderService.EXTRA_SCALE, scale);
        service.putExtra(RecorderService.EXTRA_BITRATE, bitrate);
        service.putExtra(RecorderService.EXTRA_ASPECT, aspect);
        service.putExtra(RecorderService.EXTRA_FRAME_SKIP, skip);
        service.putExtra(RecorderService.EXTRA_FAST_RENDER, fastRenderCheck.isChecked());
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
            status.setText("Recorder service requested. Check the ongoing Screen Recorder notification for its status.");
        } catch (Exception e) { status.setText("Could not start recorder service: " + e.getMessage()); }
    }
}
