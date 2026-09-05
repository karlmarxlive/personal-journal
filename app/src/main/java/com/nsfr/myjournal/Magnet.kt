package com.nsfr.myjournal

import kotlin.math.*

data class Point(val x: Float, val y: Float, val time: Long = 0)
data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = (right-left).coerceAtLeast(1f)
    val height get() = (bottom-top).coerceAtLeast(1f)
    val centerY get() = (top+bottom)/2
    fun contains(x: Float, y: Float, margin: Float = 0f) = x >= left-margin && x <= right+margin && y >= top-margin && y <= bottom+margin
}
data class Glyph(val offset: Int, val box: Bounds, val baseline: Float)
data class TextGeometry(val id: String, val sectionId: String, val text: String, val bounds: Bounds, val glyphs: List<Glyph>)
data class Recognition(val type: String, val block: TextGeometry, val start: Int, val end: Int)

object Magnet {
    fun recognize(points: List<Point>, blocks: List<TextGeometry>): Recognition? {
        if (points.size < 4) return null
        val box = Bounds(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
        val candidates = blocks.filter { b -> b.text.isNotBlank() && points.all { b.bounds.contains(it.x, it.y, 12f) } }
        if (candidates.size != 1) return null
        val block = candidates.single()
        val length = points.zipWithNext().sumOf { (a,b) -> hypot((b.x-a.x).toDouble(), (b.y-a.y).toDouble()) }.toFloat()
        val distance = hypot(points.last().x-points.first().x, points.last().y-points.first().y)
        val enclosed = block.glyphs.filter { !block.text[it.offset].isWhitespace() && box.contains((it.box.left+it.box.right)/2, it.box.centerY) }
        if (box.width > 12 && box.height > 12 && distance < min(box.width,box.height)*0.28f && length > 2*(box.width+box.height)*0.72f && length < 2*(box.width+box.height)*1.5f && enclosed.isNotEmpty()) {
            // Reject scribbles/self intersections and poorly enclosed text; require one turn.
            val cx=(box.left+box.right)/2; val cy=box.centerY
            val angles=points.map { atan2((it.y-cy)/box.height,(it.x-cx)/box.width) }
            val turns=angles.zipWithNext().map { (a,b) -> var d=b-a; while(d>PI) d-=(2*PI).toFloat(); while(d < -PI) d+=(2*PI).toFloat(); d }
            val winding=abs(turns.sum())
            val directional=abs(turns.sum()) / turns.sumOf { abs(it).toDouble() }.toFloat().coerceAtLeast(.01f)
            val inside=enclosed.all { g -> pointInside((g.box.left+g.box.right)/2,g.box.centerY,points) }
            if (winding in 5.2f..7.2f && directional > .86f && inside) return Recognition("CIRCLE",block,enclosed.first().offset,enclosed.last().offset+1)
        }
        if (box.width < 16 || box.height > 7 || distance / length.coerceAtLeast(1f) < .94f) return null
        val selected = block.glyphs.filter { !block.text[it.offset].isWhitespace() && (it.box.left+it.box.right)/2 in box.left..box.right }
        val y=points.map { it.y }.average().toFloat()
        val underline=selected.filter { abs(y-(it.baseline+2f)) <= 3.5f }
        val strike=selected.filter { abs(y-it.box.centerY) <= 3f }
        val (type, chars) = when {
            underline.isNotEmpty() && underline.map { it.baseline }.distinct().size == 1 -> "UNDERLINE" to underline
            strike.isNotEmpty() && strike.map { it.baseline }.distinct().size == 1 -> "STRIKE" to strike
            else -> return null
        }
        if (chars.last().box.right-chars.first().box.left < box.width*.65f) return null
        return Recognition(type, block, chars.first().offset, chars.last().offset+1)
    }
    private fun pointInside(x: Float,y: Float,p: List<Point>): Boolean {
        var inside=false; var j=p.lastIndex
        for (i in p.indices) { val a=p[i]; val b=p[j]; if ((a.y>y)!=(b.y>y) && x < (b.x-a.x)*(y-a.y)/(b.y-a.y)+a.x) inside=!inside; j=i }
        return inside
    }
    fun remapRange(old: String, new: String, start: Int, end: Int): IntRange? {
        var prefix=0
        while(prefix < min(old.length,new.length) && old[prefix]==new[prefix]) prefix++
        var suffix=0
        while(suffix < min(old.length,new.length)-prefix && old[old.lastIndex-suffix]==new[new.lastIndex-suffix]) suffix++
        val oldEnd=old.length-suffix
        return when {
            old==new -> start until end
            oldEnd <= start -> (start+new.length-old.length) until (end+new.length-old.length)
            prefix >= end -> start until end
            else -> null
        }
    }
    fun undo(ink: List<InkStroke>): List<InkStroke> {
        val id=ink.lastOrNull { it.active }?.id ?: return ink
        return ink.map { if(it.id==id) it.copy(active=false) else it }
    }
    fun redo(ink: List<InkStroke>): List<InkStroke> {
        val id=ink.firstOrNull { !it.active }?.id ?: return ink
        return ink.map { if(it.id==id) it.copy(active=true) else it }
    }
}
