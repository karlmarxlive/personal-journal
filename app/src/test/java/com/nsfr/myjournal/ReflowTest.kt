package com.nsfr.myjournal

import org.junit.Assert.*
import org.junit.Test

class ReflowTest {
    private val old=(0..5).map { Glyph(it,Bounds(it*10f,0f,it*10f+10,20f),17f) }
    private fun stroke(type: String)=InkStroke(entryId="e",sectionId="s",serialized=byteArrayOf(),position=0,color=0,thickness=3f,type=type,anchor="b",start=0,end=6,referenceWidth=60f,referenceHeight=20f,anchorGeometry=InkEngine.geometry(old))
    @Test fun lineFollowsReflowWithoutDrawingDiagonalBetweenLines() {
        val glyphs=old.mapIndexed { i,g -> if(i<3) g else g.copy(box=Bounds((i-3)*10f,30f,(i-3)*10f+10,50f),baseline=47f) }
        val current=TextGeometry("b","s","Привет",Bounds(0f,0f,30f,50f),glyphs)
        val points=(0..5).map { Point(it*10f+5,19f,it.toLong()) }
        val projected=InkEngine.projectedPoints(stroke("UNDERLINE"),listOf(current),points)
        assertEquals(2,projected.size)
        assertEquals(19f,projected[0][0].y,.01f); assertEquals(49f,projected[1][0].y,.01f)
        assertEquals(5f,projected[1][0].x,.01f)
    }
    @Test fun freeInkFollowsContentInsertedAboveItsAnchor() {
        val current=TextGeometry("b","s","",Bounds(10f,200f,70f,220f),emptyList())
        val points=listOf(Point(5f,5f),Point(18f,15f))
        val projected=InkEngine.projectedPoints(stroke("FREE"),listOf(current),points).single()
        assertEquals(Point(15f,205f),projected.first()); assertEquals(Point(28f,215f),projected.last())
    }
    @Test fun circleRetainsItsHandDrawnShapeAfterBlockMoves() {
        val current=TextGeometry("b","s","Привет",Bounds(0f,100f,60f,120f),old.map { g -> g.copy(box=g.box.copy(top=g.box.top+100,bottom=g.box.bottom+100),baseline=g.baseline+100) })
        val points=listOf(Point(-2f,3f),Point(14f,-3f),Point(63f,8f),Point(44f,25f))
        val projected=InkEngine.projectedPoints(stroke("CIRCLE"),listOf(current),points).single()
        points.zip(projected).forEach { (a,b) -> assertEquals(a.x,b.x,.001f); assertEquals(a.y+100,b.y,.001f) }
    }
}
