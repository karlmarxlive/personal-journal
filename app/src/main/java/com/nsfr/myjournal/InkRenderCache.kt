package com.nsfr.myjournal

/** Domain snapshots are immutable. Identity checks avoid hashing glyphs/serialized input per frame. */
internal class InkRenderCache<T> {
    private class Item<T>(val source: InkStroke, val anchor: TextGeometry?, val dark: Boolean, val pdf: Boolean, val value: T)
    private var items = emptyMap<String, Item<T>>()
    private var lastStrokes: List<InkStroke>? = null
    private var lastGeometry: List<TextGeometry>? = null
    private var lastDark = false
    private var lastPdf = false
    private var prepared = emptyList<T>()

    fun get(strokes: List<InkStroke>, geometry: List<TextGeometry>, dark: Boolean, pdf: Boolean,
            prepare: (InkStroke) -> T): List<T> {
        if (lastStrokes === strokes && lastGeometry === geometry && lastDark == dark && lastPdf == pdf) return prepared
        val anchors = geometry.associateBy { it.id }
        val next = linkedMapOf<String, Item<T>>()
        for (stroke in strokes) if (stroke.active) {
            val anchor = anchors[stroke.anchor]
            val cached = items[stroke.id]
            next[stroke.id] = if (cached != null && cached.source === stroke && cached.anchor === anchor && cached.dark == dark && cached.pdf == pdf) cached
            else Item(stroke, anchor, dark, pdf, prepare(stroke))
        }
        items = next
        lastStrokes = strokes; lastGeometry = geometry; lastDark = dark; lastPdf = pdf
        prepared = next.values.map { it.value }
        return prepared
    }
}
