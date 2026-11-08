package org.chameleonos.screenrecorder;

import android.app.*;
import android.content.*;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class RecorderService extends Service {
    public static final String ACTION_START = "org.chameleonos.screenrecorder.START";
    public static final String ACTION_STOP = "org.chameleonos.screenrecorder.STOP";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_DATA = "projection_data";
    public static final String EXTRA_FPS = "fps";
    public static final String EXTRA_SCALE = "scale";
    public static final String EXTRA_BITRATE = "bitrate";
    public static final String EXTRA_ASPECT = "aspect";
    private static final int NOTIFICATION_ID = 41;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private MediaRecorder recorder;
    private boolean recording;
    private MediaProjection.Callback projectionCallback;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) { stopRecording(); return START_NOT_STICKY; }
        if (!ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;
        startForeground(NOTIFICATION_ID, notification("Preparing screen recording…"));
        try {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
            Intent data = intent.getParcelableExtra(EXTRA_DATA);
            if (data == null || resultCode != Activity.RESULT_OK) throw new IllegalStateException("Missing screen capture permission");
            int requestedFps = intent.getIntExtra(EXTRA_FPS, 60);
            int scale = intent.getIntExtra(EXTRA_SCALE, 100);
            int bitrate = intent.getIntExtra(EXTRA_BITRATE, 8000000);
            int aspect = intent.getIntExtra(EXTRA_ASPECT, 0);
            startCapture(resultCode, data, requestedFps, scale, bitrate, aspect);
        } catch (Exception e) {
            updateNotification("Recorder error: " + e.getMessage());
            stopRecording();
        }
        return START_NOT_STICKY;
    }

    private void startCapture(int resultCode, Intent data, int requestedFps, int scale, int bitrate, int aspect) throws Exception {
        WindowManager wm = (WindowManager)getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        int width;
        int height;
        if (scale == 720 || scale == 6000) {
            int longEdge = scale == 720 ? 1280 : 6144;
            float factor = (float) longEdge / Math.max(dm.widthPixels, dm.heightPixels);
            width = Math.max(2, ((int) (dm.widthPixels * factor)) & ~1);
            height = Math.max(2, ((int) (dm.heightPixels * factor)) & ~1);
        } else {
            width = Math.max(2, dm.widthPixels & ~1);
            height = Math.max(2, dm.heightPixels & ~1);
        }
        // Output-shape presets alter the encoded canvas; device behavior may vary.
        if (aspect > 0) {
            float ratio;
            switch (aspect) {
                case 1: ratio = 16f / 9f; break;
                case 2: ratio = 4f / 3f; break;
                case 3: ratio = 20f / 9f; break;
                case 4: ratio = 9f / 16f; break;
                case 5: ratio = 1f; break;
                default: ratio = (float) width / height;
            }
            int longEdge = Math.max(width, height);
            if (ratio >= 1f) {
                width = longEdge & ~1;
                height = Math.max(2, ((int) (longEdge / ratio)) & ~1);
            } else {
                height = longEdge & ~1;
                width = Math.max(2, ((int) (longEdge * ratio)) & ~1);
            }
        }
        int displayRate = Math.max(1, Math.round(wm.getDefaultDisplay().getRefreshRate()));
        int encoderRate = maxEncoderFps();
        int actualFps = Math.max(1, Math.min(requestedFps, Math.min(displayRate, encoderRate)));
        int actualBitrate = Math.max(1000000, bitrate);

        File dir = new File(getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES), "ScreenRecorder");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Cannot create output folder");
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File output = new File(dir, "Screen_" + stamp + ".mp4");

        projection = ((MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE)).getMediaProjection(resultCode, data);
        if (projection == null) throw new IllegalStateException("Android did not provide screen capture");

        recorder = new MediaRecorder();
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        recorder.setVideoSize(width, height);
        recorder.setVideoFrameRate(actualFps);
        recorder.setVideoEncodingBitRate(actualBitrate);
        recorder.setOutputFile(output.getAbsolutePath());
        recorder.prepare();

        virtualDisplay = projection.createVirtualDisplay("ScreenRecorder",
                width, height, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.getSurface(), null, null);
        recorder.start();
        recording = true;
        updateNotification("Recording " + width + "×" + height + " at up to " + actualFps + " FPS. Saved in app Movies/ScreenRecorder.");
    }

    private int maxEncoderFps() {
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            int best = 30;
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (!info.isEncoder()) continue;
                try {
                    MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType("video/avc");
                    MediaCodecInfo.VideoCapabilities vc = caps.getVideoCapabilities();
                    int max = (int)Math.floor(vc.getSupportedFrameRates().getUpper());
                    if (max > best) best = max;
                } catch (Exception ignored) { }
            }
            return Math.max(1, best);
        } catch (Exception e) { return 30; }
    }

    private void stopRecording() {
        if (recording) {
            try { recorder.stop(); } catch (Exception ignored) { }
            try { recorder.reset(); recorder.release(); } catch (Exception ignored) { }
            recorder = null;
            recording = false;
        }
        if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
        if (projection != null) {
            try { if (projectionCallback != null) projection.unregisterCallback(projectionCallback); } catch (Exception ignored) { }
            try { projection.stop(); } catch (Exception ignored) { }
            projection = null; projectionCallback = null;
        }
        stopForeground(true);
        stopSelf();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel("screen_recording", "Screen recording", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Screen recorder controls");
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }
    private Notification notification(String text) {
        Intent stop = new Intent(this, RecorderService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent pi = PendingIntent.getService(this, 42, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "screen_recording") : new Notification.Builder(this);
        b.setContentTitle("Screen Recorder").setContentText(text).setSmallIcon(android.R.drawable.presence_video_online).setOngoing(true)
         .addAction(android.R.drawable.ic_media_pause, "Stop", pi);
        return b.build();
    }
    private void updateNotification(String text) {
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification(text));
    }
    @Override public void onDestroy() { stopRecording(); super.onDestroy(); }
    @Override public android.os.IBinder onBind(Intent intent) { return null; }
}
