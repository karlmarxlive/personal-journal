package com.nsfr.myjournal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=JournalApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExportTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun page(text: String="Ответ"): EntryPage {
        val e=JournalEntry(date="2026-09-05",status="SEALED")
        val s=JournalSection(entryId=e.id,question="Вопрос?",position=0)
        return EntryPage(e,listOf(s),listOf(ContentBlock(sectionId=s.id,position=0,text=text)),emptyList(),emptyList())
    }
    @Test fun markdownPreservesMarksAndEscapesLiteralMarkup() {
        val p=page("Привет <мир>")
        val ink=listOf("UNDERLINE","CIRCLE","STRIKE","FREE").mapIndexed { i,type -> InkStroke(entryId=p.entry.id,sectionId=p.sections[0].id,serialized=byteArrayOf(),position=i,color=0,thickness=3f,anchor=p.blocks[0].id,type=type,start=0,end=6) }
        val md=Markdown.document(listOf(p.copy(ink=ink)))
        assertTrue(md.contains("<span data-ink=\"circled\"><u>~~Привет~~</u></span>"))
        assertTrue(md.contains("&lt;мир&gt;")); assertTrue(md.contains("Свободные рисунки"))
    }
    @Test fun draftsAreExcludedAndDatesSorted() {
        val p=page(); val older=p.copy(entry=p.entry.copy(id=uuid(),date="2026-09-04"))
        val draft=p.copy(entry=p.entry.copy(status="DRAFT",date="2026-09-03"))
        val md=Markdown.document(listOf(p,draft,older))
        assertFalse(md.contains("## 2026-09-03")); assertTrue(md.indexOf("## 2026-09-04")<md.indexOf("## 2026-09-05"))
    }
    @Test fun longTextCreatesMultipleA4PagesAndKeepsGeometryOrder() {
        val p=page((1..180).joinToString("\n") { "Строка $it. Запись в журнале." })
        val plan=JournalExport(context).plan(p)
        assertTrue(plan.pageCount>=5)
        assertTrue(plan.geometry.last().bounds.top>plan.geometry.first().bounds.top)
        assertEquals(p.blocks[0].text.length,plan.geometry.last().glyphs.size)
    }
    @Test fun photosAreResizedAndTransparencyPreserved() {
        val source=Bitmap.createBitmap(3000,1500,Bitmap.Config.ARGB_8888)
        source.setHasAlpha(true)
        val asset=PhotoStore(context).saveBitmap(source,uuid())
        assertEquals(2560,asset.width); assertEquals(1280,asset.height); assertEquals("image/png",asset.mime)
        val file=File(context.filesDir,asset.path)
        assertEquals(sha256(file),asset.sha256)
        assertTrue(BitmapFactory.decodeFile(file.path).hasAlpha())
        val thumbnail=BitmapFactory.decodeFile(File(context.filesDir,asset.thumbnail).path)
        assertTrue(maxOf(thumbnail.width,thumbnail.height)<=640)
        source.recycle(); thumbnail.recycle()
    }
    @Test fun jpegDropsSourceMetadataAndUsesJpegMime() {
        val source=Bitmap.createBitmap(40,20,Bitmap.Config.RGB_565)
        val asset=PhotoStore(context).saveBitmap(source,uuid())
        assertEquals("image/jpeg",asset.mime)
        val bytes=File(context.filesDir,asset.path).readBytes()
        assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("Exif")); source.recycle()
    }
    @Test fun zipContainsPdfMarkdownAndRelativeMedia() {
        val p=page(); val source=Bitmap.createBitmap(10,10,Bitmap.Config.RGB_565)
        val media=PhotoStore(context).saveBitmap(source,p.entry.id); source.recycle()
        val withPhoto=p.copy(blocks=p.blocks+ContentBlock(sectionId=p.sections[0].id,position=1,type="PHOTO",mediaId=media.id),media=listOf(media))
        // Robolectric does not implement PdfDocument's native document handle on Windows.
        // This exercises the archive boundary; InkDeviceTest verifies the real PDF bytes.
        val pdf=File(context.cacheDir,"fixture.pdf").apply { writeText("%PDF-1.4\nfixture") }
        val out=ByteArrayOutputStream(); JournalExport(context).writeArchive(listOf(withPhoto),pdf,out)
        val contents=mutableMapOf<String,ByteArray>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip -> while(true) { val entry=zip.nextEntry ?: break; contents[entry.name]=zip.readBytes() } }
        assertEquals(setOf("journal.pdf","journal.md","media/2026-09-05/${File(media.path).name}"),contents.keys)
        assertTrue(contents["journal.md"]!!.toString(Charsets.UTF_8).contains("media/2026-09-05/"))
        assertArrayEquals(pdf.readBytes(),contents["journal.pdf"])
    }
}
