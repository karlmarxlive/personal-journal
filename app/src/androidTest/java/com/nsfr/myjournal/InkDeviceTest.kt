package com.nsfr.myjournal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class InkDeviceTest {
    @Test fun pdfClipsCrossingStrokeOnBothA4Pages() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val e=JournalEntry(date="2026-09-05",status="SEALED")
        val s=JournalSection(entryId=e.id,question="Граница страниц",position=0)
        val b=ContentBlock(sectionId=s.id,position=0)
        val blank=EntryPage(e,listOf(s),listOf(b),emptyList(),emptyList())
        val exporter=JournalExport(context); val plan=exporter.plan(blank)
        val anchor=plan.geometry.first { it.id==b.id }
        val points=(0..20).map { Point(160f,plan.pageHeight-20+it*2,it*10L) }
        val ink=InkStroke(entryId=e.id,sectionId=s.id,serialized=InkEngine.encode(points),position=0,color=1,thickness=6f,anchor=b.id,originX=anchor.bounds.left,originY=anchor.bounds.top)
        val file=File(context.cacheDir,"pagination-test.pdf")
        try {
            val count=file.outputStream().use { exporter.writePdf(listOf(blank.copy(ink=listOf(ink))),it) }
            assertEquals(2,count)
            android.graphics.pdf.PdfRenderer(android.os.ParcelFileDescriptor.open(file,android.os.ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf ->
                assertEquals(2,pdf.pageCount)
                repeat(2) { index -> pdf.openPage(index).use { page ->
                    assertEquals(595,page.width); assertEquals(842,page.height)
                    val bitmap=Bitmap.createBitmap(595,842,Bitmap.Config.ARGB_8888)
                    page.render(bitmap,null,null,android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val color=bitmap.getPixel(269,if(index==0) 790 else 85)
                    assertTrue("Crossing orange stroke must appear on page ${index+1}",android.graphics.Color.red(color)>180 && android.graphics.Color.green(color) in 70..160 && android.graphics.Color.blue(color)<90)
                    bitmap.recycle()
                } }
            }
        } finally { file.delete() }
    }
    @Test fun inkStorageReflowAndPdfRenderer() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val e=JournalEntry(date="2026-09-05",status="SEALED")
        val s=JournalSection(entryId=e.id,question="Вопрос",position=0)
        val b=ContentBlock(sectionId=s.id,position=0,text="Привет мир")
        val original=TextGeometry(b.id,s.id,b.text,Bounds(0f,0f,100f,24f),b.text.indices.map { Glyph(it,Bounds(it*10f,0f,it*10f+10,20f),17f) })
        val points=(0..20).map { Point(it*3f+10,19f,it*8L) }
        val page=EntryPage(e,listOf(s),listOf(b),emptyList(),emptyList())
        val ink=InkEngine.capture(points,page,listOf(original),0,3f)
        assertEquals("UNDERLINE",ink.type)
        val loaded=InkEngine.points(ink.serialized)
        assertEquals(points.size,loaded.size)
        assertEquals(points.last().x,loaded.last().x,.05f)
        val moved=original.copy(bounds=Bounds(0f,100f,100f,124f),glyphs=original.glyphs.map { it.copy(box=it.box.copy(top=it.box.top+100,bottom=it.box.bottom+100),baseline=it.baseline+100) })
        assertEquals(119f,InkEngine.projected(ink,listOf(moved)).first().first().y,.1f)
        val bitmap=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888)
        InkPainter().draw(Canvas(bitmap),listOf(ink),listOf(moved),Matrix())
        assertTrue((0 until bitmap.width).any { x -> (110..125).any { y -> bitmap.getPixel(x,y)!=0 } })
        val pdf=ByteArrayOutputStream(); JournalExport(context).writePdf(listOf(page.copy(ink=listOf(ink))),pdf)
        assertTrue(pdf.toByteArray().toString(Charsets.ISO_8859_1).startsWith("%PDF-"))
        bitmap.recycle()
    }
}
