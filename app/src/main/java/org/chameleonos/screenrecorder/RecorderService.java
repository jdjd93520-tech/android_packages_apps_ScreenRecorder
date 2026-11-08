package org.chameleonos.screenrecorder;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
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
    public static final String EXTRA_FRAME_SKIP = "frame_skip";
    public static final String EXTRA_FAST_RENDER = "fast_render";
    private static final int NOTIFICATION_ID = 41;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private MediaRecorder recorder;
    private boolean recording;
    private boolean cleaning;
    private MediaProjection.Callback projectionCallback;
    private File outputFile;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) {
            stopRecording(true);
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;

        try {
            if (recording || projection != null) stopRecording(false);
            startAsForeground();
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
            Intent data = getProjectionData(intent);
            if (data == null || resultCode != Activity.RESULT_OK)
                throw new IllegalStateException("Screen-sharing permission data is missing. Press Start and approve the Android prompt again.");

            int requestedFps = intent.getIntExtra(EXTRA_FPS, 60);
            int scale = intent.getIntExtra(EXTRA_SCALE, 100);
            int bitrate = intent.getIntExtra(EXTRA_BITRATE, 8000000);
            int aspect = intent.getIntExtra(EXTRA_ASPECT, 0);
            int frameSkip = Math.max(1, intent.getIntExtra(EXTRA_FRAME_SKIP, 1));
            boolean fastRender = intent.getBooleanExtra(EXTRA_FAST_RENDER, false);
            startCapture(resultCode, data, requestedFps, scale, bitrate, aspect, frameSkip, fastRender);
        } catch (Exception e) {
            updateNotification("Could not start: " + safeMessage(e));
            stopRecording(true);
        }
        return START_NOT_STICKY;
    }

    private Intent getProjectionData(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) return intent.getParcelableExtra(EXTRA_DATA, Intent.class);
        //noinspection deprecation
        return intent.getParcelableExtra(EXTRA_DATA);
    }

    private void startAsForeground() {
        Notification n = notification("Preparing screen recording…");
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void startCapture(int resultCode, Intent data, int requestedFps, int scale,
                              int bitrate, int aspect, int frameSkip, boolean fastRender) throws Exception {
        WindowManager wm = (WindowManager)getSystemService(WINDOW_SERVICE);
        if (wm == null) throw new IllegalStateException("Display service is unavailable");
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        int nativeW = evenAtLeastTwo(dm.widthPixels);
        int nativeH = evenAtLeastTwo(dm.heightPixels);

        int width = nativeW;
        int height = nativeH;
        if (scale == 720 || (scale == 6000 && !fastRender)) {
            int longEdge = scale == 720 ? 1280 : 6144;
            float factor = (float)longEdge / Math.max(nativeW, nativeH);
            width = evenAtLeastTwo(Math.round(nativeW * factor));
            height = evenAtLeastTwo(Math.round(nativeH * factor));
        }
        if (aspect > 0) {
            float ratio;
            switch (aspect) {
                case 1: ratio = 16f / 9f; break;
                case 2: ratio = 4f / 3f; break;
                case 3: ratio = 20f / 9f; break;
                case 4: ratio = 9f / 16f; break;
                case 5: ratio = 1f; break;
                default: ratio = (float)width / height;
            }
            int longEdge = Math.max(width, height);
            if (ratio >= 1f) {
                width = evenAtLeastTwo(longEdge);
                height = evenAtLeastTwo(Math.round(longEdge / ratio));
            } else {
                height = evenAtLeastTwo(longEdge);
                width = evenAtLeastTwo(Math.round(longEdge * ratio));
            }
        }

        int displayRate = Math.max(1, Math.round(wm.getDefaultDisplay().getRefreshRate()));
        int encoderRate = maxEncoderFps();
        int outputFps = Math.max(1, Math.min(Math.max(1, requestedFps / frameSkip),
                Math.min(displayRate, encoderRate)));
        int outputBitrate = Math.max(1000000, bitrate);
        if (fastRender) outputBitrate = Math.min(outputBitrate, 4000000);

        File base = getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES);
        if (base == null) base = getFilesDir();
        File dir = new File(base, "ScreenRecorder");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Cannot create Movies/ScreenRecorder folder");
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        outputFile = new File(dir, "Screen_" + stamp + ".mp4");

        projection = ((MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE))
                .getMediaProjection(resultCode, data);
        if (projection == null) throw new IllegalStateException("Android did not create a screen-capture session");

        // Android 14+ requires a callback to be registered before createVirtualDisplay().
        projectionCallback = new MediaProjection.Callback() {
            @Override public void onStop() {
                stopRecording(true);
            }
        };
        projection.registerCallback(projectionCallback, new Handler(Looper.getMainLooper()));

        ArrayList<int[]> candidates = new ArrayList<>();
        addCandidate(candidates, width, height);
        addCandidate(candidates, nativeW, nativeH);
        if (nativeW >= nativeH) {
            addCandidate(candidates, 1280, 720);
            addCandidate(candidates, 854, 480);
        } else {
            addCandidate(candidates, 720, 1280);
            addCandidate(candidates, 480, 854);
        }

        Exception lastError = null;
        int chosenW = 0, chosenH = 0;
        for (int[] size : candidates) {
            MediaRecorder candidate = new MediaRecorder();
            try {
                candidate.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                candidate.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                candidate.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
                candidate.setVideoSize(size[0], size[1]);
                candidate.setVideoFrameRate(outputFps);
                candidate.setVideoEncodingBitRate(outputBitrate);
                candidate.setOutputFile(outputFile.getAbsolutePath());
                candidate.prepare();
                recorder = candidate;
                chosenW = size[0];
                chosenH = size[1];
                break;
            } catch (Exception e) {
                lastError = e;
                try { candidate.reset(); } catch (Exception ignored) { }
                try { candidate.release(); } catch (Exception ignored) { }
            }
        }
        if (recorder == null) {
            throw new IllegalStateException("This device's encoder rejected the selected size/FPS. Try Native resolution, 30/60 FPS, and Fast Render. " + safeMessage(lastError));
        }

        virtualDisplay = projection.createVirtualDisplay("ScreenRecorder",
                chosenW, chosenH, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.getSurface(), null, new Handler(Looper.getMainLooper()));
        if (virtualDisplay == null) throw new IllegalStateException("Android could not create the capture display");
        recorder.start();
        recording = true;
        updateNotification("RECORDING • " + chosenW + "×" + chosenH + " • up to " + outputFps + " FPS");
    }

    private int evenAtLeastTwo(int value) {
        return Math.max(2, value & ~1);
    }

    private void addCandidate(ArrayList<int[]> list, int width, int height) {
        width = evenAtLeastTwo(width);
        height = evenAtLeastTwo(height);
        for (int[] item : list) if (item[0] == width && item[1] == height) return;
        list.add(new int[]{width, height});
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

    private void stopRecording(boolean stopSelfNow) {
        if (cleaning) return;
        cleaning = true;
        boolean hadRecording = recording;
        recording = false;
        if (recorder != null) {
            try {
                if (hadRecording) recorder.stop();
            } catch (Exception ignored) {
                if (outputFile != null) outputFile.delete();
            }
            try { recorder.reset(); } catch (Exception ignored) { }
            try { recorder.release(); } catch (Exception ignored) { }
            recorder = null;
        }
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Exception ignored) { }
            virtualDisplay = null;
        }
        if (!hadRecording && outputFile != null && outputFile.exists()) {
            try { outputFile.delete(); } catch (Exception ignored) { }
        }
        if (projection != null) {
            try {
                if (projectionCallback != null) projection.unregisterCallback(projectionCallback);
            } catch (Exception ignored) { }
            try { projection.stop(); } catch (Exception ignored) { }
            projection = null;
            projectionCallback = null;
        }
        try { stopForeground(true); } catch (Exception ignored) { }
        cleaning = false;
        if (stopSelfNow) stopSelf();
    }

    private String safeMessage(Throwable e) {
        if (e == null) return "Unknown encoder error";
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : message;
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
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "screen_recording") : new Notification.Builder(this);
        b.setContentTitle("Screen Recorder").setContentText(text)
                .setSmallIcon(android.R.drawable.presence_video_online).setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "Stop", pi);
        return b.build();
    }

    private void updateNotification(String text) {
        try {
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE))
                    .notify(NOTIFICATION_ID, notification(text));
        } catch (Exception ignored) { }
    }

    @Override public void onDestroy() {
        stopRecording(false);
        super.onDestroy();
    }

    @Override public android.os.IBinder onBind(Intent intent) { return null; }
}
