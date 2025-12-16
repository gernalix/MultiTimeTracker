// v2
package com.example.multitimetracker.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * Colore "casuale" ma stabile: stesso seed -> stesso colore.
 *
 * Evita Random(): in Compose cambierebbe a ogni recomposition.
 * Qui deriviamo H/S/V dall'hash per ottenere colori diversi e riconoscibili.
 */
fun tagColorFromSeed(seed: String): Color {
    val h = ((seed.hashCode().toLong() and 0xFFFFFFFFL) % 360L).toFloat()

    // Varia leggermente saturazione/valore in modo stabile (ma senza diventare troppo "neon").
    val satBits = abs(seed.hashCode()) % 100
    val valBits = abs(seed.reversed().hashCode()) % 100

    val s = 0.55f + (satBits / 100f) * 0.25f   // 0.55 .. 0.80
    val v = 0.85f + (valBits / 100f) * 0.12f   // 0.85 .. 0.97

    val hsv = floatArrayOf(h, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))
    return Color(android.graphics.Color.HSVToColor(hsv))
}
