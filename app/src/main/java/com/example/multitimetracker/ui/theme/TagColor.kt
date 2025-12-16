// v3
package com.example.multitimetracker.ui.theme

import androidx.compose.ui.graphics.Color
import com.example.multitimetracker.model.Tag
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


/**
 * Assegna colori ai tag scegliendo, da una palette discreta, quelli più "lontani" possibile
 * dai colori già assegnati, così da evitare chip indistinguibili (es. 3 gialli quasi uguali).
 *
 * La mappa risultante è stabile: stesso set di tag -> stessi colori (ordinamento per id).
 */
fun assignDistinctTagColors(tags: List<Tag>): Map<Long, Color> {
    if (tags.isEmpty()) return emptyMap()

    // Palette volutamente "a grana grossa" (colori ben separati).
    // Nota: includiamo UN giallo, ma evitiamo varianti giallino/ocra/senape tutte insieme.
    val palette: List<Color> = listOf(
        Color(0xFFFFD54F), // giallo
        Color(0xFFE57373), // rosso
        Color(0xFF64B5F6), // blu
        Color(0xFF81C784), // verde
        Color(0xFFBA68C8), // viola
        Color(0xFFFFB74D), // arancione
        Color(0xFF4DB6AC), // teal
        Color(0xFFA1887F), // marrone
        Color(0xFFF06292), // rosa
        Color(0xFF90A4AE), // grigio-blu
        Color(0xFFFF8A65), // corallo
        Color(0xFF9575CD), // indaco
        Color(0xFF4FC3F7), // azzurro
        Color(0xFFDCE775), // lime (un solo giallo-verde, comunque distante dal giallo puro)
    )

    val ordered = tags.sortedBy { it.id }
    val assigned = LinkedHashMap<Long, Color>(ordered.size)

    fun dist(a: Color, b: Color): Float {
        val dr = a.red - b.red
        val dg = a.green - b.green
        val db = a.blue - b.blue
        return dr * dr + dg * dg + db * db
    }

    for (tag in ordered) {
        // Se abbiamo più tag della palette, ricadiamo su un colore stabile da seed.
        if (assigned.size >= palette.size) {
            assigned[tag.id] = tagColorFromSeed(tag.id.toString())
            continue
        }

        val used = assigned.values.toList()
        val best = palette
            .asSequence()
            .filter { candidate -> used.none { it.value == candidate.value } }
            .maxByOrNull { candidate ->
                if (used.isEmpty()) Float.MAX_VALUE else used.minOf { u -> dist(candidate, u) }
            } ?: palette[assigned.size % palette.size]

        assigned[tag.id] = best
    }

    return assigned
}
