package com.audiobooster;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import java.util.concurrent.atomic.AtomicBoolean;

@RequiresApi(api = Build.VERSION_CODES.Q)
public class AudioProcessingService extends Service {

    private static final String TAG = "AudioBooster";
    private static final String CHANNEL_ID = "audio_booster_channel";
    private static final int NOTIF_ID = 1;

    public static final String EXTRA_RESULT_CODE     = "result_code";
    public static final String EXTRA_PROJECTION_DATA = "projection_data";

    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_IN  = AudioFormat.CHANNEL_IN_STEREO;
    private static final int CHANNEL_OUT = AudioFormat.CHANNEL_OUT_STEREO;
    private static final int ENCODING    = AudioFormat.ENCODING_PCM_FLOAT;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread processingThread;
    private MediaProjection mediaProjection;
    private AudioManager audioManager;

    private volatile float gain          = 1.0f;
    private volatile float bassDb        = 0f;
    private volatile float midDb         = 0f;
    private volatile float trebleDb      = 0f;
    private volatile boolean limiterEnabled = true;

    private final BiquadFilter bassL   = new BiquadFilter();
    private final BiquadFilter bassR   = new BiquadFilter();
    private final BiquadFilter midL    = new BiquadFilter();
    private final BiquadFilter midR    = new BiquadFilter();
    private final BiquadFilter trebleL = new BiquadFilter();
    private final BiquadFilter trebleR = new BiquadFilter();

    private volatile boolean bassChanged   = true;
    private volatile boolean midChanged    = true;
    private volatile boolean trebleChanged = true;

    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public AudioProcessingService getService() { return AudioProcessingService.this; }
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1);
        Intent projData = intent.getParcelableExtra(EXTRA_PROJECTION_DATA);

        startForeground(NOTIF_ID, buildNotification());

        MediaProjectionManager mpm =
            (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mediaProjection = mpm.getMediaProjection(resultCode, projData);
        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stopSelf(); }
        }, null);

        startProcessing();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running.set(false);
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        super.onDestroy();
    }

    public void setGain(float g)             { gain = Math.max(0f, Math.min(4f, g)); }
    public void setBassDb(int db)            { bassDb   = db; bassChanged   = true; }
    public void setMidDb(int db)             { midDb    = db; midChanged    = true; }
    public void setTrebleDb(int db)          { trebleDb = db; trebleChanged = true; }
    public void setLimiterEnabled(boolean e) { limiterEnabled = e; }

    private void startProcessing() {
        running.set(true);
        processingThread = new Thread(this::audioLoop, "AudioBooster-Thread");
        processingThread.setPriority(Thread.MAX_PRIORITY);
        processingThread.start();
    }

    private void audioLoop() {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING);
        int bufSize = Math.max(minBuf, 4096) * 2;

        AudioPlaybackCaptureConfiguration captureConfig =
            new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build();

        AudioRecord recorder;
        try {
            recorder = new AudioRecord.Builder()
                .setAudioFormat(new AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(ENCODING)
                    .setChannelMask(CHANNEL_IN)
                    .build())
                .setBufferSizeInBytes(bufSize)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build();
        } catch (Exception e) {
            Log.e(TAG, "Error creando AudioRecord", e);
            stopSelf();
            return;
        }

        int minTrack = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING);

        AudioTrack track = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .setAudioFormat(new AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(ENCODING)
                .setChannelMask(CHANNEL_OUT)
                .build())
            .setBufferSizeInBytes(Math.max(minTrack, bufSize))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build();

        routeToHeadphones(track);

        recorder.startRecording();
        track.play();

        float[] buffer = new float[bufSize / 4];
        recalcFiltersIfNeeded();

        while (running.get()) {
            int read = recorder.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
            if (read <= 0) continue;

            recalcFiltersIfNeeded();

            for (int i = 0; i < read - 1; i += 2) {
                float l = buffer[i];
                float r = buffer[i + 1];

                l *= gain;
                r *= gain;

                l = bassL.process(l);
                r = bassR.process(r);

                l = midL.process(l);
                r = midR.process(r);

                l = trebleL.process(l);
                r = trebleR.process(r);

                if (limiterEnabled) {
                    l = softClip(l);
                    r = softClip(r);
                }

                buffer[i]     = l;
                buffer[i + 1] = r;
            }

            track.write(buffer, 0, read, AudioTrack.WRITE_BLOCKING);
        }

        recorder.stop();
        recorder.release();
        track.stop();
        track.release();
    }

    private void routeToHeadphones(AudioTrack track) {
        AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        AudioDeviceInfo preferred = null;
        int bestScore = -1;

        for (AudioDeviceInfo dev : devices) {
            int score = 0;
            switch (dev.getType()) {
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:   score = 4; break;
                case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:    score = 3; break;
                case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                case AudioDeviceInfo.TYPE_WIRED_HEADSET:    score = 2; break;
                case AudioDeviceInfo.TYPE_USB_HEADSET:      score = 2; break;
                case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:  score = 1; break;
            }
            if (score > bestScore) {
                bestScore = score;
                preferred = dev;
            }
        }

        if (preferred != null) {
            track.setPreferredDevice(preferred);
            Log.d(TAG, "Salida: " + preferred.getProductName() + " tipo=" + preferred.getType());
        }
    }

    private static float softClip(float x) {
        if (x >  1f) return  1f - (float) Math.exp(-(x - 1f));
        if (x < -1f) return -1f + (float) Math.exp( (x + 1f));
        return x;
    }

    private void recalcFiltersIfNeeded() {
        if (bassChanged) {
            double[] c = BiquadFilter.lowShelf(SAMPLE_RATE, 250.0, 0.707, bassDb);
            bassL.setCoeffs(c); bassR.setCoeffs(c);
            bassChanged = false;
        }
        if (midChanged) {
            double[] c = BiquadFilter.peakingEQ(SAMPLE_RATE, 1000.0, 1.0, midDb);
            midL.setCoeffs(c); midR.setCoeffs(c);
            midChanged = false;
        }
        if (trebleChanged) {
            double[] c = BiquadFilter.highShelf(SAMPLE_RATE, 6000.0, 0.707, trebleDb);
            trebleL.setCoeffs(c); trebleR.setCoeffs(c);
            trebleChanged = false;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "AudioBooster", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Procesado de audio en tiempo real");
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        PendingIntent openPi = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AudioBooster activo")
            .setContentText("EQ en tiempo real → auriculares")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openPi)
            .setOngoing(true)
            .build();
    }
}
