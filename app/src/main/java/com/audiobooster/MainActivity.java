package com.audiobooster;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.audiobooster.databinding.ActivityMainBinding;
import com.google.android.material.button.MaterialButton;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private MediaProjectionManager projectionManager;
    private AudioProcessingService audioService;
    private boolean serviceBound = false;
    private boolean isRunning = false;

    // Launcher para el permiso de MediaProjection
    private final ActivityResultLauncher<Intent> projectionLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                startAudioService(result.getResultCode(), result.getData());
            } else {
                Toast.makeText(this, "Permiso de captura de pantalla denegado", Toast.LENGTH_SHORT).show();
            }
        });

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            AudioProcessingService.LocalBinder lb = (AudioProcessingService.LocalBinder) binder;
            audioService = lb.getService();
            serviceBound = true;
            syncControlsToService();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            audioService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        // Pedir permiso de notificaciones (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this,
                new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 100);
        }

        setupControls();
        tryBindService();
    }

    private void setupControls() {
        // --- Botón START / STOP ---
        binding.btnToggle.setOnClickListener(v -> {
            if (!isRunning) {
                requestProjectionPermission();
            } else {
                stopAudioService();
            }
        });

        // --- Amplificación global (0–400 %, por defecto 100 %) ---
        binding.seekGain.setMax(400);
        binding.seekGain.setProgress(100);
        updateGainLabel(100);
        binding.seekGain.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                updateGainLabel(p);
                if (serviceBound) audioService.setGain(p / 100f);
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });

        // --- EQ: Bajos (±12 dB) ---
        setupEqBand(binding.seekBass, binding.tvBassValue, "bass");
        // --- EQ: Medios (±12 dB) ---
        setupEqBand(binding.seekMid, binding.tvMidValue, "mid");
        // --- EQ: Agudos (±12 dB) ---
        setupEqBand(binding.seekTreble, binding.tvTrebleValue, "treble");

        // --- Límiter: on/off ---
        binding.switchLimiter.setOnCheckedChangeListener((btn, checked) -> {
            if (serviceBound) audioService.setLimiterEnabled(checked);
        });
    }

    private void setupEqBand(SeekBar seek, TextView label, String band) {
        seek.setMax(24);          // 0 = -12 dB, 12 = 0 dB, 24 = +12 dB
        seek.setProgress(12);     // Centro = 0 dB
        updateEqLabel(label, 0);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                int db = p - 12;
                updateEqLabel(label, db);
                if (serviceBound) {
                    switch (band) {
                        case "bass":   audioService.setBassDb(db);   break;
                        case "mid":    audioService.setMidDb(db);    break;
                        case "treble": audioService.setTrebleDb(db); break;
                    }
                }
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
    }

    private void updateGainLabel(int percent) {
        binding.tvGainValue.setText(percent + "%");
    }

    private void updateEqLabel(TextView tv, int db) {
        tv.setText((db >= 0 ? "+" : "") + db + " dB");
    }

    private void requestProjectionPermission() {
        Intent intent = projectionManager.createScreenCaptureIntent();
        projectionLauncher.launch(intent);
    }

    private void startAudioService(int resultCode, Intent data) {
        Intent serviceIntent = new Intent(this, AudioProcessingService.class);
        serviceIntent.putExtra(AudioProcessingService.EXTRA_RESULT_CODE, resultCode);
        serviceIntent.putExtra(AudioProcessingService.EXTRA_PROJECTION_DATA, data);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        isRunning = true;
        binding.btnToggle.setText("⏹ Detener");
        binding.btnToggle.setBackgroundColor(getColor(R.color.stop_red));
        binding.statusDot.setColorFilter(getColor(R.color.active_green));
        binding.tvStatus.setText("Estado: Activo");

        tryBindService();
    }

    private void stopAudioService() {
        stopService(new Intent(this, AudioProcessingService.class));
        isRunning = false;
        binding.btnToggle.setText("▶ Iniciar captura");
        binding.btnToggle.setBackgroundColor(getColor(R.color.start_blue));
        binding.statusDot.setColorFilter(getColor(R.color.inactive_gray));
        binding.tvStatus.setText("Estado: Inactivo");
    }

    private void tryBindService() {
        Intent intent = new Intent(this, AudioProcessingService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void syncControlsToService() {
        if (!serviceBound) return;
        audioService.setGain(binding.seekGain.getProgress() / 100f);
        audioService.setBassDb(binding.seekBass.getProgress() - 12);
        audioService.setMidDb(binding.seekMid.getProgress() - 12);
        audioService.setTrebleDb(binding.seekTreble.getProgress() - 12);
        audioService.setLimiterEnabled(binding.switchLimiter.isChecked());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (serviceBound) {
            unbindService(serviceConnection);
        }
    }
}
