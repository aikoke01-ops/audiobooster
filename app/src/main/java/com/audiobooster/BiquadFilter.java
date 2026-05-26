package com.audiobooster;

/**
 * Filtro biquad IIR de segundo orden.
 *
 * Implementa la forma directa II transpuesta (DF2T) que es numéricamente
 * estable y eficiente en punto flotante.
 *
 * Fórmulas basadas en el "Audio EQ Cookbook" de Robert Bristow-Johnson.
 *
 * Transferencia:
 *      H(z) = (b0 + b1·z⁻¹ + b2·z⁻²) / (1 + a1·z⁻¹ + a2·z⁻²)
 *
 * Uso:
 *   double[] coeffs = BiquadFilter.lowShelf(fs, fc, Q, gainDb);
 *   filter.setCoeffs(coeffs);
 *   float out = filter.process(in);
 */
public class BiquadFilter {

    // Coeficientes del filtro (normalizados por a0)
    private double b0 = 1, b1 = 0, b2 = 0;
    private double a1 = 0, a2 = 0;

    // Estado interno (memoria del filtro)
    private double s1 = 0, s2 = 0;

    /**
     * Aplica el filtro a una muestra.
     * Implementación DF2T (Direct Form II Transposed).
     */
    public float process(float x) {
        double y = b0 * x + s1;
        s1 = b1 * x - a1 * y + s2;
        s2 = b2 * x - a2 * y;
        return (float) y;
    }

    /**
     * Establece nuevos coeficientes de forma thread-safe (sincronizada).
     * @param c array [b0, b1, b2, a1, a2] (ya divididos por a0)
     */
    public synchronized void setCoeffs(double[] c) {
        b0 = c[0]; b1 = c[1]; b2 = c[2];
        a1 = c[3]; a2 = c[4];
        // Resetear estado para evitar transitorios al cambiar filtros
        s1 = 0; s2 = 0;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Fábricas de filtros (Audio EQ Cookbook)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Low Shelf (realce/corte de graves)
     * @param fs   frecuencia de muestreo (Hz)
     * @param fc   frecuencia de corte (Hz)
     * @param Q    factor de calidad (0.707 = Butterworth)
     * @param dBgain ganancia en dB (positivo = boost, negativo = cut)
     */
    public static double[] lowShelf(double fs, double fc, double Q, double dBgain) {
        double A  = Math.pow(10.0, dBgain / 40.0);
        double w0 = 2.0 * Math.PI * fc / fs;
        double cosW = Math.cos(w0);
        double sinW = Math.sin(w0);
        double alpha = sinW / (2.0 * Q);
        double sqrtA = Math.sqrt(A);
        double twoSqrtAAlpha = 2.0 * sqrtA * alpha;

        double b0 =  A * ((A + 1) - (A - 1) * cosW + twoSqrtAAlpha);
        double b1 =  2 * A * ((A - 1) - (A + 1) * cosW);
        double b2 =  A * ((A + 1) - (A - 1) * cosW - twoSqrtAAlpha);
        double a0 =       (A + 1) + (A - 1) * cosW + twoSqrtAAlpha;
        double a1 = -2   * ((A - 1) + (A + 1) * cosW);
        double a2 =        (A + 1) + (A - 1) * cosW - twoSqrtAAlpha;

        return normalize(b0, b1, b2, a0, a1, a2);
    }

    /**
     * High Shelf (realce/corte de agudos)
     */
    public static double[] highShelf(double fs, double fc, double Q, double dBgain) {
        double A  = Math.pow(10.0, dBgain / 40.0);
        double w0 = 2.0 * Math.PI * fc / fs;
        double cosW = Math.cos(w0);
        double sinW = Math.sin(w0);
        double alpha = sinW / (2.0 * Q);
        double sqrtA = Math.sqrt(A);
        double twoSqrtAAlpha = 2.0 * sqrtA * alpha;

        double b0 =  A * ((A + 1) + (A - 1) * cosW + twoSqrtAAlpha);
        double b1 = -2 * A * ((A - 1) + (A + 1) * cosW);
        double b2 =  A * ((A + 1) + (A - 1) * cosW - twoSqrtAAlpha);
        double a0 =       (A + 1) - (A - 1) * cosW + twoSqrtAAlpha;
        double a1 =  2   * ((A - 1) - (A + 1) * cosW);
        double a2 =        (A + 1) - (A - 1) * cosW - twoSqrtAAlpha;

        return normalize(b0, b1, b2, a0, a1, a2);
    }

    /**
     * Peaking EQ (campana de medios, boost/cut en una frecuencia central)
     * @param bandwidth ancho de banda (octavas); mayor = más ancho
     */
    public static double[] peakingEQ(double fs, double fc, double bandwidth, double dBgain) {
        double A  = Math.pow(10.0, dBgain / 40.0);
        double w0 = 2.0 * Math.PI * fc / fs;
        double cosW = Math.cos(w0);
        double sinW = Math.sin(w0);
        // alpha via bandwidth (octavas)
        double alpha = sinW * Math.sinh(Math.log(2) / 2.0 * bandwidth * w0 / sinW);

        double b0 =  1 + alpha * A;
        double b1 = -2 * cosW;
        double b2 =  1 - alpha * A;
        double a0 =  1 + alpha / A;
        double a1 = -2 * cosW;
        double a2 =  1 - alpha / A;

        return normalize(b0, b1, b2, a0, a1, a2);
    }

    /** Divide todos los coeficientes por a0 para normalizar. */
    private static double[] normalize(double b0, double b1, double b2,
                                      double a0, double a1, double a2) {
        return new double[]{b0/a0, b1/a0, b2/a0, a1/a0, a2/a0};
    }
}
