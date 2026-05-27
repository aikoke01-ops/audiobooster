package com.audiobooster;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
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

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private MediaProjectionManager projectionManager;
    private AudioProcessingService audioService;
    private boolean serviceBound = false;
    private boolean isRunning = false;

    private final ActivityResultLauncher<Intent> projectionLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                startAudioService(result.getResultCode(), result.getData());
            } else {
                Toast.makeText(this, "Permiso de captura denegado", Toast.LENGTH_SHORT).show();
            }
        });

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            audioService = ((AudioProcessingService.LocalBinder) binder).getService();
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

        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this,
                new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 100);
        }

        setupControls();
        updateOutputDeviceLabel();
        tryBindService();
    }

    private void setupControls() {
        binding.btnToggle.setOnClickListener(v -> {
            if (!isRunning) {
                // Verificar que hay auriculares conectados
                if (!hasHeadphonesConnected()) {
                    Toast.makeText(this,
                        "⚠ Conecta auriculares o Bluetooth antes de iniciar",
                        Toast.LENGTH_LONG).show();
                    // Permitir continuar igualmente (usará altavoz)
                }
                projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                projectionLauncher.launch(projectionManager.createScreenCaptureIntent());
            } else {
                stopAudioService();
            }
        });

        // Ganancia
        binding.seekGain.setMax(400);
        binding.seekGain.setProgress(100);
        updateGainLabel(100);
        binding.seekGain.setOnSeekBarChangeListener(simpleSeekListener(p -> {
            updateGainLabel(p);
            if (serviceBound) audioService.setGain(p / 100f);
        }));

        // EQ bands
        setupEqBand(binding.seekBass,   binding.tvBassValue,   "bass");
        setupEqBand(binding.seekMid,    binding.tvMidValue,    "mid");
        setupEqBand(binding.seekTreble, binding.tvTrebleValue, "treble");

        // Limiter
        binding.switchLimiter.setOnCheckedChangeListener((btn, checked) -> {
            if (serviceBound) audioService.setLimiterEnabled(checked);
        });
    }

    private void setupEqBand(SeekBar seek, TextView label, String band) {
        seek.setMax(24);
        seek.setProgress(12);
        updateEqLabel(label, 0);
        seek.setOnSeekBarChangeListener(simpleSeekListener(p -> {
            int db = p - 12;
            updateEqLabel(label, db);
            if (serviceBound) {
                switch (band) {
                    case "bass":   audioService.setBassDb(db);   break;
                    case "mid":    audioService.setMidDb(db);    break;
                    case "treble": audioService.setTrebleDb(db); break;
                }
            }
        }));
    }

    private void updateGainLabel(int percent) {
        binding.tvGainValue.setText(percent + "%");
    }

    private void updateEqLabel(TextView tv, int db) {
        tv.setText((db >= 0 ? "+" : "") + db + " dB");
    }

    private void updateOutputDeviceLabel() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        AudioDeviceInfo[] devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        String deviceName = "Altavoz del dispositivo";
        int bestScore = 0;
        for (AudioDeviceInfo d : devices) {
            int score = 0;
            switch (d.getType()) {
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:   score = 4; break;
                case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:    score = 3; break;
                case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                case AudioDeviceInfo.TYPE_WIRED_HEADSET:    score = 2; break;
                case AudioDeviceInfo.TYPE_USB_HEADSET:      score = 2; break;
            }
            if (score > bestScore) {
                bestScore = score;
                deviceName = d.getProductName() != null
                    ? d.getProductName().toString() : "Auriculares";
            }
        }
        binding.tvOutputDevice.setText("🎧 Salida: " + deviceName);
    }

    private boolean hasHeadphonesConnected() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP   ||
                t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO    ||
                t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                t == AudioDeviceInfo.TYPE_WIRED_HEADSET    ||
                t == AudioDeviceInfo.TYPE_USB_HEADSET) {
                return true;
            }
        }
        return false;
    }

    private void startAudioService(int resultCode, Intent data) {
        Intent si = new Intent(this, AudioProcessingService.class);
        si.putExtra(AudioProcessingService.EXTRA_RESULT_CODE, resultCode);
        si.putExtra(AudioProcessingService.EXTRA_PROJECTION_DATA, data);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(si);
        else startService(si);

        isRunning = true;
        binding.btnToggle.setText("⏹ Detener");
        binding.btnToggle.setBackgroundColor(getColor(R.color.stop_red));
        binding.tvStatus.setText("Estado: Activo");
        updateOutputDeviceLabel();
        tryBindService();
    }

    private void stopAudioService() {
        stopService(new Intent(this, AudioProcessingService.class));
        isRunning = false;
        binding.btnToggle.setText("▶ Iniciar captura");
        binding.btnToggle.setBackgroundColor(getColor(R.color.start_blue));
        binding.tvStatus.setText("Estado: Inactivo");
    }

    private void tryBindService() {
        bindService(new Intent(this, AudioProcessingService.class),
            serviceConnection, Context.BIND_AUTO_CREATE);
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
        if (serviceBound) unbindService(serviceConnection);
    }

    // Helper para SeekBar listeners con lambda
    private SeekBar.OnSeekBarChangeListener simpleSeekListener(java.util.function.IntConsumer onChange) {
        return new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) { onChange.accept(p); }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        };
    }
}
