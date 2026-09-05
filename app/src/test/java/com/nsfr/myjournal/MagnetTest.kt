package com.nsfr.myjournal

import org.junit.Test
import org.junit.Assert.*
import kotlin.math.*

class MagnetTest {
    private val block=TextGeometry("text","section","Привет мир",Bounds(0f,0f,100f,24f),(0..9).map { Glyph(it,Bounds(it*10f,0f,it*10f+10,20f),17f) })
    private fun line(y: Float)=(0..20).map { Point(10f+it*3f,y,it.toLong()) }
    @Test fun underlineRecognized() { assertEquals("UNDERLINE",Magnet.recognize(line(19f),listOf(block))?.type) }
    @Test fun strikeRecognized() { assertEquals("STRIKE",Magnet.recognize(line(10f),listOf(block))?.type) }
    @Test fun circleRecognized() {
        val circle=(0..80).map { val a=it*2*PI/80; Point(40f+34*cos(a).toFloat(),10f+14*sin(a).toFloat()) }
        val result=Magnet.recognize(circle,listOf(block)); assertEquals("CIRCLE",result?.type)
    }
    @Test fun uncertainAndMultiBlockStrokesStayFree() {
        assertNull(Magnet.recognize(line(30f),listOf(block)))
        assertNull(Magnet.recognize(line(19f),listOf(block,block.copy(id="other"))))
        assertNull(Magnet.recognize(listOf(Point(1f,1f),Point(40f,25f),Point(25f,20f),Point(40f,25f),Point(35f,10f)),listOf(block)))
        assertNull(Magnet.recognize((0..30).map { Point(it*3f,(sin(it.toDouble())*10+10).toFloat()) },listOf(block)))
    }
    @Test fun editsOutsideRangeShiftOrPreserveIt() {
        assertEquals(3 until 6,Magnet.remapRange("abcdef","Xabcdef",2,5))
        assertEquals(2 until 5,Magnet.remapRange("abcdef","abcdef!",2,5))
        assertEquals(1 until 4,Magnet.remapRange("abcdef","bcdef",2,5))
    }
    @Test fun intersectingEditsDetach() { assertNull(Magnet.remapRange("abcdef","abXdef",2,5)); assertNull(Magnet.remapRange("abcdef","abef",2,5)) }
    @Test fun undoRedoUsesDeterministicOrder() {
        val ink=(0..2).map { InkStroke(entryId="e",sectionId="s",serialized=byteArrayOf(),position=it,color=0,thickness=3f,anchor="b") }
        val undo=Magnet.undo(Magnet.undo(ink)); assertEquals(listOf(true,false,false),undo.map { it.active })
        assertEquals(listOf(true,true,false),Magnet.redo(undo).map { it.active })
    }
}
