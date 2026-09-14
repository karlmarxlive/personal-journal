package com.nsfr.myjournal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.text.TextLayoutResult
import kotlin.math.roundToInt

internal class GeometryNode(val section: String, var text: String) {
    var coordinates: LayoutCoordinates? = null
    var layout: TextLayoutResult? = null
    var cached: TextGeometry? = null
    var cachedLayout: TextLayoutResult? = null
    var localGlyphs: List<Glyph> = emptyList()
    var glyphBuilds = 0
        private set

    fun update(id: String, bounds: Bounds, density: Float): Boolean {
        val old = cached
        val currentLayout = layout
        val layoutChanged = cachedLayout !== currentLayout || old?.text != text
        if (!layoutChanged && old?.bounds == bounds) return false
        if (layoutChanged) {
            localGlyphs = if (currentLayout == null) emptyList() else {
                glyphBuilds++
                // A text edit can precede the corresponding layout callback.
                (0 until minOf(text.length, currentLayout.layoutInput.text.length)).map { index ->
                    val box = currentLayout.getBoundingBox(index)
                    Glyph(index, Bounds(box.left / density, box.top / density,
                        box.right / density, box.bottom / density),
                        currentLayout.getLineBaseline(currentLayout.getLineForOffset(index)) / density)
                }
            }
            cachedLayout = currentLayout
        }
        val glyphs = localGlyphs.map { g ->
            g.copy(box = Bounds(g.box.left + bounds.left, g.box.top + bounds.top,
                g.box.right + bounds.left, g.box.bottom + bounds.top), baseline = g.baseline + bounds.top)
        }
        cached = TextGeometry(id, section, text, bounds, glyphs)
        return true
    }
}

internal class PageGeometry(private val density: Float) {
    var root: LayoutCoordinates? = null
    val nodes = linkedMapOf<String, GeometryNode>()
    var revision by mutableIntStateOf(0)
        private set
    private var snapshot: List<TextGeometry>? = null

    fun measure(): List<TextGeometry> = snapshot ?: nodes.values.mapNotNull { it.cached }.also { snapshot = it }

    fun changed(node: GeometryNode? = null) {
        val parent = root?.takeIf { it.isAttached } ?: return
        var changed = false
        val candidates = if (node == null) nodes.asSequence().map { it.key to it.value } else {
            val id = node.cached?.id ?: nodes.entries.firstOrNull { it.value === node }?.key ?: return
            sequenceOf(id to node)
        }
        for ((id, value) in candidates) {
            val coords = value.coordinates?.takeIf { it.isAttached } ?: continue
            val position = parent.localPositionOf(coords, Offset.Zero)
            // Layout positions are integral pixels. Ignore inverse-transform rounding during zoom.
            val offset = Offset(position.x.roundToInt() / density, position.y.roundToInt() / density)
            changed = value.update(id, Bounds(offset.x, offset.y,
                offset.x + coords.size.width / density, offset.y + coords.size.height / density), density) || changed
        }
        if (changed) invalidate()
    }

    fun remove(id: String) {
        if (nodes.remove(id) != null) invalidate()
    }

    private fun invalidate() { snapshot = null; revision++ }
}
