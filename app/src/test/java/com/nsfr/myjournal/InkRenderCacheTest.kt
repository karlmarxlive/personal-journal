package com.nsfr.myjournal

import org.junit.Assert.*
import org.junit.Test

class InkRenderCacheTest {
    @Test fun scrollReusesMoreThan160StrokesAndOnlyChangedAnchorsRebuild() {
        val cache = InkRenderCache<Any>()
        val strokes = (0 until 200).map { InkStroke(id="$it",entryId="e",sectionId="s",serialized=byteArrayOf(1),position=it,color=0,thickness=3f,anchor="b$it") }
        val geometry = strokes.map { TextGeometry(it.anchor,"s","",Bounds(0f,0f,10f,10f),emptyList()) }
        var builds = 0
        fun render(s: List<InkStroke> = strokes, g: List<TextGeometry> = geometry, dark: Boolean = false, pdf: Boolean = false) =
            cache.get(s,g,dark,pdf) { builds++; Any() }
        val first = render()
        repeat(120) { assertSame(first,render()) }
        assertEquals(200,builds)
        val moved = geometry.toMutableList().apply { this[0] = this[0].copy(bounds=Bounds(0f,20f,10f,30f)) }
        val second = render(g=moved)
        assertEquals(201,builds)
        assertNotSame(first[0],second[0]); assertSame(first[1],second[1])
        render(s=strokes.toMutableList().apply { this[1]=this[1].copy(color=2,thickness=5f) },g=moved)
        assertEquals(202,builds)
        render(dark=true); assertEquals(402,builds)
        render(dark=true,pdf=true); assertEquals(602,builds)
    }

    @Test fun undoRemovesPreparedStrokeAndRedoRestoresIt() {
        val cache=InkRenderCache<String>()
        val stroke=InkStroke(id="1",entryId="e",sectionId="s",serialized=byteArrayOf(),position=0,color=0,thickness=3f,anchor="b")
        assertEquals(listOf("1"),cache.get(listOf(stroke),emptyList(),false,false) { it.id })
        assertTrue(cache.get(listOf(stroke.copy(active=false)),emptyList(),false,false) { it.id }.isEmpty())
        assertEquals(listOf("1"),cache.get(listOf(stroke),emptyList(),false,false) { it.id })
    }
}
