// v1
package com.example.multitimetracker.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Colore "casuale" ma stabile: stesso seed -> stesso colore.
 * Utile per dare a ogni tag un chip con background riconoscibile.
 */
fun tagColorFromSeed(seed: String): Color {
    val h = ((seed.hashCode().toLong() and 0xFFFFFFFFL) % 360L).toFloat()
    val hsv = floatArrayOf(h, 0.45f, 0.95f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}
