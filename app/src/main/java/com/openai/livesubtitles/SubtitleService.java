package com.openai.livesubtitles;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class SubtitleService extends Service {
    public static final String ACTION_START = "com.openai.livesubtitles.START";
    public static final String ACTION_STOP = "com.openai.livesubtitles.STOP";
    public static final String EXTRA_API_KEY = "api_key";
    public static final String EXTRA_STT_MODEL = "stt_model";
    public static final String EXTRA_OUTPUT_LANGUAGE = "output_language";

    private static final int NOTIFICATION_ID = 41;
    private static final String CHANNEL_ID = "live_subtitles";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNELS = 1;
    private static final double AUDIO_WINDOW_SECONDS = 4.0;
    private static final double MIN_AUDIO_SECONDS = 0.9;
    private static final double STT_INTERVAL_SECONDS = 0.60;
    private static final double SILENCE_RMS = 100.0;
    private static final int SUBTITLE_HISTORY_SIZE = 7;
    private static final double SUBTITLE_HISTORY_TIMEOUT = 10.0;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CoreLogic.TranscriptAssembler assembler = new CoreLogic.TranscriptAssembler();
    private final DisplayState displayState = new DisplayState();
    private final TranslationHistory translationHistory = new TranslationHistory();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean sttBusy = new AtomicBoolean(false);
    private final AtomicBoolean translationBusy = new AtomicBoolean(false);
    private final AtomicReference<SubtitleSource> latestTranslation = new AtomicReference<>();

    private AudioRecord audioRecord;
    private Thread audioThread;
    private AudioRingBuffer audioRing;
    private ScheduledExecutorService scheduler;
    private ExecutorService networkExecutor;
    private OpenRouterClient client;
    private String lastSource = "";

    private WindowManager windowManager;
    private LinearLayout overlay;
    private WindowManager.LayoutParams overlayParams;
    private final List<TextView> overlayLabels = new ArrayList<>();

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        if (ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!ACTION_START.equals(intent.getAction()) || running.get()) {
            return START_NOT_STICKY;
        }

        String apiKey = safe(intent.getStringExtra(EXTRA_API_KEY));
        String sttModel = safe(intent.getStringExtra(EXTRA_STT_MODEL));
        String outputLanguage = safe(intent.getStringExtra(EXTRA_OUTPUT_LANGUAGE));

        if (apiKey.isEmpty() || sttModel.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (outputLanguage.isEmpty()) outputLanguage = "German";

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                || !Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        client = new OpenRouterClient(apiKey, sttModel, outputLanguage);
        try {
            createOverlay();
            startEngine();
        } catch (Exception e) {
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private void startEngine() {
        if (!running.compareAndSet(false, true)) return;

        int minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        int bufferSize = Math.max(minBuffer * 2, SAMPLE_RATE / 5 * 2);

        audioRecord = createRecorder(MediaRecorder.AudioSource.VOICE_RECOGNITION, bufferSize);
        if (audioRecord == null || audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            if (audioRecord != null) audioRecord.release();
            audioRecord = createRecorder(MediaRecorder.AudioSource.MIC, bufferSize);
        }
        if (audioRecord == null || audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IllegalStateException("Microphone could not be initialized");
        }

        audioRing = new AudioRingBuffer(SAMPLE_RATE, CHANNELS, AUDIO_WINDOW_SECONDS);
        networkExecutor = Executors.newFixedThreadPool(2);
        scheduler = Executors.newScheduledThreadPool(2);

        audioRecord.startRecording();
        audioThread = new Thread(() -> captureLoop(bufferSize), "subtitle-mic");
        audioThread.start();

        scheduler.scheduleAtFixedRate(
                this::runSttTick,
                900,
                (long) (STT_INTERVAL_SECONDS * 1000),
                TimeUnit.MILLISECONDS
        );

        scheduler.scheduleAtFixedRate(
                this::runSegmenterTick,
                50,
                50,
                TimeUnit.MILLISECONDS
        );

        scheduler.scheduleAtFixedRate(
                () -> mainHandler.post(this::renderOverlay),
                500,
                500,
                TimeUnit.MILLISECONDS
        );
    }

    private AudioRecord createRecorder(int source, int bufferSize) {
        try {
            return new AudioRecord(
                    source,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
            );
        } catch (Exception e) {
            return null;
        }
    }

    private void captureLoop(int bufferSize) {
        byte[] readBuffer = new byte[Math.max(2048, bufferSize / 2)];
        while (running.get()) {
            int read = audioRecord.read(readBuffer, 0, readBuffer.length);
            if (read > 0) {
                audioRing.append(Arrays.copyOf(readBuffer, read));
            }
        }
    }

    private void runSttTick() {
        if (!running.get() || audioRing == null || !sttBusy.compareAndSet(false, true)) return;

        byte[] pcm = audioRing.snapshot();
        double duration = audioRing.duration();

        if (duration < MIN_AUDIO_SECONDS || pcm.length == 0 || AudioRingBuffer.rms(pcm) < SILENCE_RMS) {
            sttBusy.set(false);
            return;
        }

        networkExecutor.execute(() -> {
            try {
                byte[] wav = WavUtil.pcmToWav(pcm, SAMPLE_RATE, CHANNELS);
                String source = client.transcribe(wav);
                if (!source.isEmpty()
                        && !CoreLogic.looksUseless(source)
                        && !CoreLogic.cleanText(source).equals(CoreLogic.cleanText(lastSource))) {
                    lastSource = source;
                    assembler.add(source);
                }
            } catch (Exception ignored) {
            } finally {
                sttBusy.set(false);
            }
        });
    }

    private void runSegmenterTick() {
        if (!running.get()) return;

        boolean pause = assembler.secondsSinceChange() >= CoreLogic.PAUSE_FLUSH_SECONDS;
        int segmentLength = assembler.findSegmentLength(pause);
        if (segmentLength <= 0) return;

        String previousContext = assembler.getContextText();
        String source = assembler.popWords(segmentLength);
        if (source.isEmpty()) return;

        TranslationHistory.Snapshot history = translationHistory.get();
        String context = previousContext;

        if (!history.previousSource.isEmpty() && !context.contains(history.previousSource)) {
            context = CoreLogic.cleanText(context + " " + history.previousSource);
            List<String> words = CoreLogic.tokenize(context);
            int start = Math.max(0, words.size() - CoreLogic.PREVIOUS_SOURCE_CONTEXT_WORDS);
            context = CoreLogic.detokenize(words.subList(start, words.size()));
        }

        latestTranslation.set(new SubtitleSource(source, context, history.previousTranslation));
        startTranslationLoopIfNeeded();
    }

    private void startTranslationLoopIfNeeded() {
        if (!translationBusy.compareAndSet(false, true)) return;

        networkExecutor.execute(() -> {
            try {
                while (running.get()) {
                    SubtitleSource item = latestTranslation.getAndSet(null);
                    if (item == null) break;

                    String translated = client.translate(item);
                    if (!translated.isEmpty()) {
                        translationHistory.set(item.text, translated);
                        displayState.add(translated, item.text);
                        mainHandler.post(this::renderOverlay);
                    }
                }
            } finally {
                translationBusy.set(false);
                if (latestTranslation.get() != null && running.get()) {
                    startTranslationLoopIfNeeded();
                }
            }
        });
    }

    private void createOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setPadding(dp(10), dp(7), dp(10), dp(7));

        for (int i = 0; i < 3; i++) {
            TextView label = new TextView(this);
            label.setTextColor(Color.WHITE);
            label.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            label.setPadding(dp(7), dp(3), dp(7), dp(3));
            label.setVisibility(View.GONE);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            lp.topMargin = dp(2);
            overlay.addView(label, lp);
            overlayLabels.add(label);
        }

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;

        overlayParams = new WindowManager.LayoutParams(
                Math.max(dp(280), screenWidth - dp(24)),
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        overlayParams.gravity = Gravity.TOP | Gravity.START;
        overlayParams.x = dp(12);
        overlayParams.y = Math.max(dp(40), screenHeight - dp(220));

        overlay.setOnTouchListener(new DragTouchListener());
        windowManager.addView(overlay, overlayParams);
        renderOverlay();
    }

    private void renderOverlay() {
        if (overlay == null) return;
        List<String> entries = displayState.getRecent(3);

        for (int i = 0; i < overlayLabels.size(); i++) {
            TextView label = overlayLabels.get(i);
            int entryIndex = i - (overlayLabels.size() - entries.size());

            if (entryIndex < 0) {
                label.setText("");
                label.setVisibility(View.GONE);
                continue;
            }

            label.setText(CoreLogic.compactSubtitle(entries.get(entryIndex)));
            label.setVisibility(View.VISIBLE);

            int distance = overlayLabels.size() - i - 1;
            int fontSize;
            int textAlpha;
            int backgroundAlpha;

            if (distance == 0) {
                fontSize = 21;
                textAlpha = 255;
                backgroundAlpha = 190;
                label.setTypeface(null, android.graphics.Typeface.BOLD);
            } else if (distance == 1) {
                fontSize = 19;
                textAlpha = 190;
                backgroundAlpha = 145;
                label.setTypeface(null, android.graphics.Typeface.NORMAL);
            } else {
                fontSize = 18;
                textAlpha = 115;
                backgroundAlpha = 105;
                label.setTypeface(null, android.graphics.Typeface.NORMAL);
            }

            label.setTextSize(fontSize);
            label.setTextColor(Color.argb(textAlpha, 255, 255, 255));

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.argb(backgroundAlpha, 0, 0, 0));
            bg.setCornerRadius(dp(5));
            label.setBackground(bg);
        }
    }

    private final class DragTouchListener implements View.OnTouchListener {
        private float downRawX, downRawY;
        private int downX, downY;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (overlayParams == null || windowManager == null) return false;

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    downX = overlayParams.x;
                    downY = overlayParams.y;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    overlayParams.x = downX + Math.round(event.getRawX() - downRawX);
                    overlayParams.y = downY + Math.round(event.getRawY() - downRawY);
                    windowManager.updateViewLayout(overlay, overlayParams);
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    return true;
            }
            return false;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Live subtitles",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Microphone capture for live translated subtitles");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent activityIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setContentTitle("Live subtitles are running")
                .setContentText("Listening to the microphone")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        running.set(false);

        if (scheduler != null) scheduler.shutdownNow();
        if (networkExecutor != null) networkExecutor.shutdownNow();

        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (Exception ignored) {}
            audioRecord.release();
            audioRecord = null;
        }

        if (audioThread != null) {
            try {
                audioThread.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        if (windowManager != null && overlay != null) {
            try {
                windowManager.removeView(overlay);
            } catch (Exception ignored) {}
            overlay = null;
        }

        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    static final class SubtitleSource {
        final String text;
        final String previousSourceContext;
        final String previousTranslation;

        SubtitleSource(String text, String previousSourceContext, String previousTranslation) {
            this.text = text;
            this.previousSourceContext = previousSourceContext;
            this.previousTranslation = previousTranslation;
        }
    }

    private static final class TranslationHistory {
        private String previousSource = "";
        private String previousTranslation = "";

        synchronized Snapshot get() {
            return new Snapshot(previousSource, previousTranslation);
        }

        synchronized void set(String source, String translation) {
            previousSource = source;
            previousTranslation = translation;
        }

        static final class Snapshot {
            final String previousSource;
            final String previousTranslation;
            Snapshot(String previousSource, String previousTranslation) {
                this.previousSource = previousSource;
                this.previousTranslation = previousTranslation;
            }
        }
    }

    private static final class DisplayState {
        private final Deque<Entry> entries = new ArrayDeque<>();

        synchronized void add(String subtitle, String source) {
            subtitle = CoreLogic.cleanText(subtitle);
            source = CoreLogic.cleanText(source);
            if (subtitle.isEmpty()) return;

            long now = System.nanoTime();
            Entry previous = entries.peekLast();
            if (previous != null && CoreLogic.cleanText(previous.subtitle).equals(subtitle)) {
                previous.updatedNanos = now;
                return;
            }

            entries.addLast(new Entry(subtitle, source, now));
            while (entries.size() > SUBTITLE_HISTORY_SIZE) entries.removeFirst();
        }

        synchronized List<String> getRecent(int max) {
            long now = System.nanoTime();
            Entry newest = entries.peekLast();

            if (newest != null
                    && (now - newest.updatedNanos) / 1_000_000_000.0 > SUBTITLE_HISTORY_TIMEOUT) {
                entries.clear();
            }

            List<String> all = new ArrayList<>();
            for (Entry e : entries) all.add(e.subtitle);
            int start = Math.max(0, all.size() - max);
            return new ArrayList<>(all.subList(start, all.size()));
        }

        private static final class Entry {
            final String subtitle;
            final String source;
            long updatedNanos;

            Entry(String subtitle, String source, long now) {
                this.subtitle = subtitle;
                this.source = source;
                this.updatedNanos = now;
            }
        }
    }
}
