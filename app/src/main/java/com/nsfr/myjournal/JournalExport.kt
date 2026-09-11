package com.nsfr.myjournal

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.ceil
import kotlin.math.max

object Markdown {
    private fun escape(text: String) = text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
        .replace("\\","\\\\").replace("*","\\*").replace("_","\\_").replace("[","\\[").replace("]","\\]").replace("#","\\#").replace("~","\\~").replace("`","\\`")
    fun annotated(text: String, strokes: List<InkStroke>): String {
        val marks=strokes.filter { it.active && it.type!="FREE" && it.start>=0 && it.end<=text.length && it.end>it.start }
        val cuts=(listOf(0,text.length)+marks.flatMap { listOf(it.start,it.end) }).distinct().sorted()
        return cuts.zipWithNext().joinToString("") { (start,end) ->
            var content=escape(text.substring(start,end))
            val types=marks.filter { it.start<=start && it.end>=end }.map { it.type }.toSet()
            if("STRIKE" in types) content="~~$content~~"
            if("UNDERLINE" in types) content="<u>$content</u>"
            if("CIRCLE" in types) content="<span data-ink=\"circled\">$content</span>"
            content
        }
    }
    fun document(pages: List<EntryPage>) = buildString {
        append("# Мой Журнал\n\nОбозначения: <u>подчёркивание</u>, ~~зачёркивание~~, <span data-ink=\"circled\">обводка</span>. Визуальная форма пометок сохранена в PDF.\n\n")
        pages.filter { it.entry.status=="SEALED" }.sortedBy { it.entry.date }.forEach { p ->
            append("## ${p.entry.date}\n\n")
            p.sections.sortedBy { it.position }.forEach { s ->
                append("### ${annotated(s.question,p.ink.filter { it.anchor=="q:${s.id}" })}\n\n")
                p.blocks.filter { it.sectionId==s.id }.sortedBy { it.position }.forEach { b ->
                    if(b.type=="TEXT") append(annotated(b.text,p.ink.filter { it.anchor==b.id })).append("\n\n")
                    else p.media.find { it.id==b.mediaId }?.let { append("![Фотография](media/${p.entry.date}/${File(it.path).name})\n\n") }
                }
            }
            if(p.ink.any { it.active && it.type=="FREE" }) append("> Свободные рисунки и визуальные пометки доступны в PDF.\n\n")
        }
    }
}
data class PdfElement(val top: Float, val layout: StaticLayout?=null, val asset: MediaAsset?=null, val width: Float=0f, val height: Float=0f)
data class PdfPlan(val elements: List<PdfElement>,val geometry: List<TextGeometry>,val height: Float) {
    val pageHeight get() = (842f-80f-34f)/(515f/360f)
    val pageCount get() = ceil(height/pageHeight).toInt().coerceAtLeast(1)
}
class JournalExport(private val context: Context) {
    fun plan(page: EntryPage): PdfPlan {
        var y=24f
        val elements=mutableListOf<PdfElement>(); val geometry=mutableListOf<TextGeometry>()
        fun text(id: String, sectionId: String,value: String,question: Boolean) {
            val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; textSize=if(question) 20f else 16f; typeface=if(question) Typeface.create(Typeface.create("serif",Typeface.NORMAL),600,false) else Typeface.create("sans-serif",Typeface.NORMAL) }
            val lineSpacing=if(question) 28f-(paint.fontMetrics.descent-paint.fontMetrics.ascent) else 5f
            val layout=StaticLayout.Builder.obtain(value,0,value.length,paint,312).setIncludePad(false).setLineSpacing(lineSpacing,1f).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
            val glyphs=value.indices.map { i -> val line=layout.getLineForOffset(i); val x=layout.getPrimaryHorizontal(i); val next=layout.getPrimaryHorizontal(i+1); Glyph(i,Bounds(24+minOf(x,next),y+layout.getLineTop(line),24+maxOf(x,next),y+layout.getLineBottom(line)),y+layout.getLineBaseline(line)) }
            geometry+=TextGeometry(id,sectionId,value,Bounds(24f,y,336f,y+layout.height),glyphs)
            elements+=PdfElement(y,layout=layout)
            y+=max(layout.height.toFloat(),if(question) 24f else 40f)+if(question) 12f else 16f
        }
        page.sections.sortedBy { it.position }.forEach { s ->
            text("q:${s.id}",s.id,s.question,true)
            page.blocks.filter { it.sectionId==s.id }.sortedBy { it.position }.forEach { b ->
                if(b.type=="TEXT") text(b.id,s.id,b.text,false)
                else page.media.find { it.id==b.mediaId }?.let { asset ->
                    val w=312f*b.widthPercent/100f; val h=w*asset.height/asset.width
                    elements+=PdfElement(y,asset=asset,width=w,height=h)
                    geometry+=TextGeometry(b.id,s.id,"",Bounds(24f,y,24+w,y+h),emptyList())
                    y+=h+16f
                }
            }
            y+=24f
        }
        if(page.ink.any { it.active }) y=max(y,page.ink.filter { it.active }.flatMap { InkEngine.projected(it,geometry).flatten() }.maxOfOrNull { it.y+12 } ?: y)
        return PdfPlan(elements,geometry,y+24)
    }
    fun writePdf(pages: List<EntryPage>, output: OutputStream): Int {
        var number=0
        val document=PdfDocument()
        try {
            val painter=InkPainter()
            pages.filter { it.entry.status=="SEALED" }.sortedBy { it.entry.date }.forEach { p ->
                val plan=plan(p)
                repeat(plan.pageCount) { index ->
                    val sheet=document.startPage(PdfDocument.PageInfo.Builder(595,842,++number).create())
                    val c=sheet.canvas; c.drawColor(Color.WHITE)
                    val title=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; textSize=15f; typeface=Typeface.create("sans-serif",Typeface.BOLD) }
                    c.drawText(p.entry.date+if(index>0) " · продолжение" else "",40f,55f,title)
                    val top=index*plan.pageHeight; val bottom=top+plan.pageHeight
                    c.save(); c.clipRect(40f,74f,555f,802f)
                    val transform=Matrix().apply { setScale(515f/360f,515f/360f); postTranslate(40f,74f-top*515f/360f) }
                    c.concat(transform)
                    plan.elements.forEach { element ->
                        val h=element.layout?.height?.toFloat() ?: element.height
                        if(element.top+h>=top && element.top<=bottom) {
                            if(element.layout!=null) { c.save(); c.translate(24f,element.top); element.layout.draw(c); c.restore() }
                            else element.asset?.let { asset -> BitmapFactory.decodeFile(File(context.filesDir,asset.path).absolutePath)?.useBitmap { bitmap -> c.drawBitmap(bitmap,null,RectF(24f,element.top,24f+element.width,element.top+element.height),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)) } }
                        }
                    }
                    if(p.ink.any { it.active }) painter.draw(c,p.ink,plan.geometry,transform,pdf=true)
                    c.restore(); title.textSize=9f; c.drawText("Мой Журнал · $number",40f,822f,title)
                    document.finishPage(sheet)
                }
            }
            if(number==0) {
                val sheet=document.startPage(PdfDocument.PageInfo.Builder(595,842,1).create())
                sheet.canvas.drawColor(Color.WHITE)
                sheet.canvas.drawText("Мой Журнал — пока нет сохранённых записей",40f,65f,Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize=15f })
                document.finishPage(sheet); number=1
            }
            document.writeTo(output)
        } finally { document.close() }
        return number
    }
    fun write(pages: List<EntryPage>, output: OutputStream) {
        val sealed=pages.filter { it.entry.status=="SEALED" }.sortedBy { it.entry.date }
        val pdf=File.createTempFile("journal-export-",".pdf",context.cacheDir)
        try {
            pdf.outputStream().use { writePdf(sealed,it) }
            writeArchive(sealed,pdf,output)
        } finally { pdf.delete() }
    }
    internal fun writeArchive(pages: List<EntryPage>, pdf: File, output: OutputStream) {
        val sealed=pages.filter { it.entry.status=="SEALED" }.sortedBy { it.entry.date }
        ZipOutputStream(output.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("journal.pdf")); pdf.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                zip.putNextEntry(ZipEntry("journal.md")); zip.write(Markdown.document(sealed).toByteArray(Charsets.UTF_8)); zip.closeEntry()
                sealed.forEach { p -> p.media.filter { asset -> p.blocks.any { it.mediaId==asset.id } }.forEach { asset ->
                    zip.putNextEntry(ZipEntry("media/${p.entry.date}/${File(asset.path).name}")); File(context.filesDir,asset.path).inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                } }
        }
    }
}
