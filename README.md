# 🎧 AudioBooster — APK de amplificación y EQ del sistema

Captura el audio del sistema Android en tiempo real usando **MediaProjection**,
lo procesa con amplificación de ganancia y un EQ de 3 bandas (filtros biquad)
y lo reproduce instantáneamente. Todo sin pasar por la Play Store.

---

## Requisitos

| Herramienta | Versión mínima |
|---|---|
| Android Studio | Hedgehog (2023.1) o superior |
| Android SDK | API 34 (compileSdk) |
| JDK | 11+ (incluido en Android Studio) |
| Dispositivo/Emulador | Android 10 (API 29) o superior |

---

## 1. Compilar el APK

### Opción A — Android Studio (recomendado)
1. Abre Android Studio → **File › Open** → selecciona esta carpeta
2. Espera a que Gradle sincronice
3. Menú **Build › Build Bundle(s) / APK(s) › Build APK(s)**
4. El APK estará en: `app/build/outputs/apk/debug/app-debug.apk`

### Opción B — Línea de comandos
```bash
# En la raíz del proyecto
chmod +x gradlew
./gradlew assembleDebug

# APK generado en:
# app/build/outputs/apk/debug/app-debug.apk
```

---

## 2. Instalar en el dispositivo (sideload)

### Requisitos previos en el teléfono
1. **Ajustes → Acerca del teléfono** → pulsa 7 veces en *Número de compilación* (activa opciones de desarrollador)
2. **Ajustes → Sistema → Opciones de desarrollador** → activa *Depuración USB*
3. **Ajustes → Seguridad** → activa *Instalar apps de fuentes desconocidas* (o permite la instalación desde tu gestor de archivos)

### Instalar via ADB
```bash
adb devices              # verifica que el dispositivo aparece
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Instalar manualmente
1. Copia el APK al teléfono (USB, correo, Drive, etc.)
2. Ábrelo desde el gestor de archivos del teléfono
3. Acepta el aviso de seguridad

---

## 3. Uso de la app

1. **Abre AudioBooster** en el teléfono
2. Pulsa **▶ Iniciar captura**
3. El sistema mostrará un diálogo pidiendo permiso para capturar la pantalla
   → acepta (es necesario para que `MediaProjection` capture el audio)
4. La app queda activa en segundo plano con una **notificación persistente**
5. Ajusta los controles en tiempo real:
   - **Amplificación** 0–400 %
   - **Bajos** ±12 dB (low shelf a 250 Hz)
   - **Medios** ±12 dB (peaking EQ a 1 kHz)
   - **Agudos** ±12 dB (high shelf a 6 kHz)
   - **Límiter** soft-clip para evitar distorsión

---

## Arquitectura técnica

```
MainActivity  ──────────────────────────────────────────────────────┐
│  UI: SeekBars + botón toggle                                       │
│  Solicita permiso MediaProjection                                   │
│  Bind → AudioProcessingService                                     │
└────────────────────────────────────────────────────────────────────┘
                          │ startForegroundService()
                          ▼
AudioProcessingService  ────────────────────────────────────────────┐
│  ForegroundService (tipo: mediaProjection)                         │
│  MediaProjection → AudioPlaybackCaptureConfiguration               │
│  AudioRecord (PCM Float 44100 Hz, Stereo)                          │
│  Thread de procesado (prioridad MAX):                              │
│    buffer → gain × sample                                          │
│           → BiquadFilter bass  (low shelf  250 Hz)                 │
│           → BiquadFilter mid   (peaking EQ 1 kHz)                  │
│           → BiquadFilter treble(high shelf 6 kHz)                  │
│           → softClip() limiter                                     │
│  AudioTrack → altavoces/auriculares                                │
└────────────────────────────────────────────────────────────────────┘

BiquadFilter  ──────────────────────────────────────────────────────┐
│  IIR biquad Direct Form II Transposed                              │
│  Fórmulas: Audio EQ Cookbook (R. Bristow-Johnson)                  │
│  Métodos estáticos: lowShelf(), highShelf(), peakingEQ()           │
└────────────────────────────────────────────────────────────────────┘
```

---

## Notas importantes

- **Android 10+ obligatorio**: `AudioPlaybackCaptureConfiguration` solo existe desde API 29
- Algunas apps (Spotify, Netflix) pueden **bloquear la captura** si marcan su audio como privado (`ALLOW_CAPTURE_BY_NONE`). Esto es una restricción del sistema, no de la app.
- El audio procesado **reemplaza** al audio original en los altavoces — no es una mezcla adicional.
- Para **firmar el APK** para distribución fuera de Play Store usa:
  ```bash
  ./gradlew assembleRelease
  # Luego firma con apksigner o Android Studio
  ```

---

## Solución de problemas

| Problema | Solución |
|---|---|
| No se escucha audio | Asegúrate de que hay audio reproduciéndose **antes** de iniciar la captura |
| El diálogo de permiso se rechaza | El sistema revoca el permiso cada vez; pulsa Iniciar de nuevo |
| Distorsión alta | Baja la amplificación o activa el Límiter |
| `AudioRecord` falla | El micrófono puede estar en uso por otra app; ciérrala |
| ADB no detecta el dispositivo | Instala los drivers USB de tu fabricante (Samsung, Xiaomi, etc.) |
