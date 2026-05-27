package com.audiobooster;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
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

/**
 * Servicio rediseñado para Opción B:
 *  1. Solicita foco de audio exclusivo (silencia otras apps)
 *  2. Captura el audio del sistema con MediaProjection
 *  3. Procesa: ganancia + EQ biquad 3 bandas + soft-clip limiter
 *  4. Reproduce el audio procesado por auriculares/bluetooth (AudioTrack)
 *
 * Requiere auriculares conectados (jack 3.5mm o Bluetooth).
 */
@RequiresApi(api = Build.VERSION_CODES.Q)
public class AudioProcessingService extends Service {

    private static final String TAG = "AudioBooster";
    private static final String CHANNEL_ID = "audio_booster_channel";
    private static final int NOTIF_ID = 1;

    public static final String EXTRA_RESULT_CODE    = "result_code";
    public static final String EXTRA_PROJECTION_DATA = "projection_data";

    // ── Audio config ──────────────────────────────────────────────────────────
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_IN  = AudioFormat.CHANNEL_IN_STEREO;
    private static final int CHANNEL_OUT = AudioFormat.CHANNEL_OUT_STEREO;
    private static final int ENCODING    = AudioFormat.ENCODING_PCM_FLOAT;

    // ── Estado ────────────────────────────────────────────────────────────────
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread processingThread;
    private MediaProjection mediaProjection;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;

    // ── Parámetros de procesado (volátiles, seguros entre hilos) ──────────────
    private volatile float gain         = 1.0f;
    private volatile float bassDb       = 0f;
    private volatile float midDb        = 0f;
    private volatile float trebleDb     = 0f;
    private volatile boolean limiterEnabled = true;

    // ── Filtros biquad ────────────────────────────────────────────────────────
    private final BiquadFilter bassL   = new BiquadFilter();
    private final BiquadFilter bassR   = new BiquadFilter();
    private final BiquadFilter midL    = new BiquadFilter();
    private final BiquadFilter midR    = new BiquadFilter();
    private final BiquadFilter trebleL = new BiquadFilter();
    private final BiquadFilter trebleR = new BiquadFilter();

    private volatile boolean bassChanged   = true;
    private volatile boolean midChanged    = true;
    private volatile boolean trebleChanged = true;

    // ── Binder ────────────────────────────────────────────────────────────────
    private final IBinder binder = new LocalBinder();
    public class LocalBinder extends Binder {
        public AudioProcessingService getService() { return AudioProcessingService.this; }
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    // ─────────────────────────────────────────────────────────────────────────
    //  Ciclo de vida
    // ─────────────────────────────────────────────────────────────────────────

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

        // Obtener MediaProjection
        MediaProjectionManager mpm =
            (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mediaProjection = mpm.getMediaProjection(resultCode, projData);
        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stopSelf(); }
        }, null);

        // Solicitar foco de audio: silencia otras apps y toma control
        requestAudioFocus();

        startProcessing();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running.set(false);
        abandonAudioFocus();
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        super.onDestroy();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Foco de audio
    // ─────────────────────────────────────────────────────────────────────────

    private void requestAudioFocus() {
        AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();

        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(focusChange -> {
                if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                    stopSelf();
                }
            })
            .build();

        audioManager.requestAudioFocus(focusRequest);
    }

    private void abandonAudioFocus() {
        if (focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  API pública para la UI
    // ─────────────────────────────────────────────────────────────────────────

    public void setGain(float g)             { gain = Math.max(0f, Math.min(4f, g)); }
    public void setBassDb(int db)            { bassDb   = db; bassChanged   = true; }
    public void setMidDb(int db)             { midDb    = db; midChanged    = true; }
    public void setTrebleDb(int db)          { trebleDb = db; trebleChanged = true; }
    public void setLimiterEnabled(boolean e) { limiterEnabled = e; }

    // ─────────────────────────────────────────────────────────────────────────
    //  Motor de audio
    // ─────────────────────────────────────────────────────────────────────────

    private void startProcessing() {
        running.set(true);
        processingThread = new Thread(this::audioLoop, "AudioBooster-Thread");
        processingThread.setPriority(Thread.MAX_PRIORITY);
        processingThread.start();
    }

    private void audioLoop() {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING);
        int bufSize = Math.max(minBuf, 4096) * 2;

        // ── Configurar captura ────────────────────────────────────────────────
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

        // ── Configurar reproducción → auriculares/bluetooth ──────────────────
        int minTrack = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING);

        AudioAttributes playbackAttrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();

        AudioTrack track = new AudioTrack.Builder()
            .setAudioAttributes(playbackAttrs)
            .setAudioFormat(new AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(ENCODING)
                .setChannelMask(CHANNEL_OUT)
                .build())
            .setBufferSizeInBytes(Math.max(minTrack, bufSize))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build();

        // Intentar dirigir la salida a auriculares si están conectados
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

                // 1. Ganancia
                l *= gain;
                r *= gain;

                // 2. EQ bajos
                l = bassL.process(l);
                r = bassR.process(r);

                // 3. EQ medios
                l = midL.process(l);
                r = midR.process(r);

                // 4. EQ agudos
                l = trebleL.process(l);
                r = trebleR.process(r);

                // 5. Limiter
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

    /**
     * Intenta redirigir la salida del AudioTrack a auriculares (jack o BT).
     * Si no hay auriculares conectados, usa el altavoz por defecto.
     */
    private void routeToHeadphones(AudioTrack track) {
        AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        AudioDeviceInfo preferred = null;

        // Prioridad: BT A2DP > BT SCO > Jack wired > altavoz
        int bestScore = -1;
        for (AudioDeviceInfo dev : devices) {
            int score = 0;
            switch (dev.getType()) {
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: score = 4; break;
                case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:  score = 3; break;
                case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                case AudioDeviceInfo.TYPE_WIRED_HEADSET:  score = 2; break;
                case AudioDeviceInfo.TYPE_USB_HEADSET:    score = 2; break;
                case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: score = 1; break;
            }
            if (score > bestScore) {
                bestScore = score;
                preferred = dev;
            }
        }

        if (preferred != null) {
            track.setPreferredDevice(preferred);
            Log.d(TAG, "Salida de audio: " + preferred.getProductName()
                + " (tipo " + preferred.getType() + ")");
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

    // ─────────────────────────────────────────────────────────────────────────
    //  Notificación
    // ─────────────────────────────────────────────────────────────────────────

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
