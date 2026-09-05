package com.nsfr.myjournal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.ink.authoring.*
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.*
import androidx.ink.storage.encode
import androidx.ink.storage.decode
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.*

fun inkColor(logical: Int, dark: Boolean=false, pdf: Boolean=false): Int = when(logical) {
    1 -> if(dark && !pdf) 0xFFFFB15A.toInt() else 0xFFE76F00.toInt()
    2 -> if(dark && !pdf) 0xFF82B1FF.toInt() else 0xFF2563EB.toInt()
    else -> if(pdf) 0xFF000000.toInt() else if(dark) 0xFFF2EFE8.toInt() else 0xFF202124.toInt()
}
object InkEngine {
    fun input(x: Float,y: Float,time: Long)=StrokeInput().apply { update(x,y,time,InputToolType.TOUCH) }
    fun brush(color: Int, thickness: Float, dark: Boolean=false, pdf: Boolean=false) = Brush.createWithColorIntArgb(StockBrushes.marker(StockBrushes.MarkerVersion.V1),inkColor(color,dark,pdf),thickness,.1f)
    fun encode(points: List<Point>): ByteArray {
        val inputs=MutableStrokeInputBatch()
        points.forEachIndexed { i,p -> inputs.add(input(p.x,p.y,p.time.coerceAtLeast(i.toLong()))) }
        return ByteArrayOutputStream().use { inputs.encode(it); it.toByteArray() }
    }
    fun points(bytes: ByteArray): List<Point> {
        val batch=StrokeInputBatch.decode(ByteArrayInputStream(bytes))
        return (0 until batch.size).map { i -> batch[i].let { Point(it.x,it.y,it.elapsedTimeMillis) } }
    }
    fun geometry(glyphs: List<Glyph>) = glyphs.joinToString(";") { "${it.offset},${it.box.left},${it.box.top},${it.box.right},${it.box.bottom},${it.baseline}" }
    private fun decodeGeometry(value: String) = if(value.isEmpty()) emptyList() else value.split(';').map { s -> val v=s.split(','); Glyph(v[0].toInt(),Bounds(v[1].toFloat(),v[2].toFloat(),v[3].toFloat(),v[4].toFloat()),v[5].toFloat()) }
    fun capture(points: List<Point>, page: EntryPage, geometry: List<TextGeometry>, color: Int, thickness: Float): InkStroke {
        val recognition=Magnet.recognize(points,geometry)
        val centerY=points.map { it.y }.average().toFloat()
        val nearest=recognition?.block ?: geometry.minBy { g -> abs(g.bounds.centerY-centerY) }
        return InkStroke(entryId=page.entry.id,sectionId=nearest.sectionId,serialized=encode(points),position=page.ink.size,color=color,thickness=thickness,
            type=recognition?.type ?: "FREE",anchor=nearest.id,start=recognition?.start ?: 0,end=recognition?.end ?: 0,
            originX=nearest.bounds.left,originY=nearest.bounds.top,referenceWidth=nearest.bounds.width,referenceHeight=nearest.bounds.height,
            anchorGeometry=recognition?.let { geometry(it.block.glyphs.filter { g -> g.offset in it.start until it.end }) } ?: "")
    }
    fun projected(stroke: InkStroke, geometry: List<TextGeometry>): List<List<Point>> {
        return projectedPoints(stroke,geometry,points(stroke.serialized))
    }
    internal fun projectedPoints(stroke: InkStroke, geometry: List<TextGeometry>, points: List<Point>): List<List<Point>> {
        val block=geometry.find { it.id==stroke.anchor } ?: return listOf(points)
        val old=decodeGeometry(stroke.anchorGeometry)
        val current=block.glyphs.filter { it.offset in stroke.start until stroke.end }
        if(stroke.type=="FREE" || old.isEmpty() || current.isEmpty()) return listOf(points.map { it.copy(x=block.bounds.left+(it.x-stroke.originX),y=block.bounds.top+(it.y-stroke.originY)) })
        if(stroke.type=="CIRCLE") {
            fun bounds(gs: List<Glyph>)=Bounds(gs.minOf { it.box.left },gs.minOf { it.box.top },gs.maxOf { it.box.right },gs.maxOf { it.box.bottom })
            val a=bounds(old); val b=bounds(current)
            return listOf(points.map { it.copy(x=b.left+(it.x-a.left)*b.width/a.width,y=b.top+(it.y-a.top)*b.height/a.height) })
        }
        val groups=mutableListOf<MutableList<Point>>(); var previousLine: Float?=null
        points.forEach { p ->
            val index=old.indices.minBy { i -> val g=old[i]; abs((g.box.left+g.box.right)/2-p.x)+abs(g.box.centerY-p.y) }
            val a=old[index]; val b=current[index.coerceAtMost(current.lastIndex)]
            if(previousLine==null || abs(previousLine-b.baseline)>1f) groups += mutableListOf<Point>()
            groups.last() += p.copy(x=b.box.left+(p.x-a.box.left)*b.box.width/a.box.width,y=b.baseline+(p.y-a.baseline))
            previousLine=b.baseline
        }
        return groups
    }
    fun detachOverlapping(ink: List<InkStroke>, anchor: String, old: String, new: String, geometry: List<TextGeometry>): List<InkStroke> = ink.map { s ->
        if(s.anchor!=anchor || s.type=="FREE") s else {
            val range=Magnet.remapRange(old,new,s.start,s.end)
            if(range!=null) s.copy(start=range.first,end=range.last+1)
            else {
                val g=geometry.find { it.id==anchor }
                val flattened=projected(s,geometry).flatten()
                s.copy(type="FREE",serialized=encode(flattened),anchorGeometry="",start=0,end=0,originX=g?.bounds?.left ?: s.originX,originY=g?.bounds?.top ?: s.originY)
            }
        }
    }
}

class InkPainter {
    private val renderer by lazy { CanvasStrokeRenderer.create() }
    private val cache=android.util.LruCache<String,List<Stroke>>(160)
    fun draw(canvas: Canvas, strokes: List<InkStroke>, geometry: List<TextGeometry>, transform: Matrix, dark: Boolean=false,pdf: Boolean=false) {
        strokes.filter { it.active }.forEach { s ->
            val key="${s.id}:${s.type}:${s.start}:${s.end}:${s.serialized.contentHashCode()}:$dark:$pdf:${geometry.find { it.id==s.anchor }.hashCode()}"
            val rendered=cache[key] ?: InkEngine.projected(s,geometry).filter { it.isNotEmpty() }.map { points ->
                val batch=MutableStrokeInputBatch(); points.forEachIndexed { i,p -> batch.add(InkEngine.input(p.x,p.y,p.time.coerceAtLeast(i.toLong()))) }
                Stroke(InkEngine.brush(s.color,s.thickness,dark,pdf),batch)
            }.also { cache.put(key,it) }
            rendered.forEach { stroke -> renderer.draw(canvas,stroke,transform) }
        }
    }
}

/** Compose hosts this native Ink authoring surface; it is only allocated in pen mode. */
class InkInputView(context: Context) : FrameLayout(context) {
    private val wet=InProgressStrokesView(context)
    var viewToPage=Matrix()
    var pageToView=Matrix()
    var logicalColor=0
    var thickness=3f
    var dark=false
    var onFinished: (List<Point>)->Unit = {}
    var onTransform: (Float,Float,Float,Float,Float)->Unit = { _,_,_,_,_ -> }
    private var id: InProgressStrokeId?=null
    private val points=mutableListOf<Point>()
    private var startTime=0L
    private var multi=false
    private var span=0f; private var centerX=0f; private var centerY=0f
    init {
        addView(wet,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT))
        wet.addFinishedStrokesListener(object: InProgressStrokesFinishedListener {
            override fun onStrokesFinished(strokes: Map<InProgressStrokeId,Stroke>) { wet.removeFinishedStrokes(strokes.keys) }
        })
        contentDescription="Поле рисования. Один палец рисует, два пальца перемещают и масштабируют страницу"
        isFocusable=true
    }
    private fun point(event: MotionEvent): Point {
        val xy=floatArrayOf(event.x,event.y); viewToPage.mapPoints(xy)
        return Point(xy[0],xy[1],event.eventTime-startTime)
    }
    override fun onInterceptTouchEvent(ev: MotionEvent)=true
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true); multi=false; points.clear(); startTime=event.eventTime
                val p=point(event); points+=p
                id=wet.startStroke(InkEngine.input(p.x,p.y,0),InkEngine.brush(logicalColor,thickness,dark),pageToView)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                id?.let { wet.cancelStroke(it) }; id=null; points.clear(); multi=true
                centerX=(event.getX(0)+event.getX(1))/2; centerY=(event.getY(0)+event.getY(1))/2
                span=hypot(event.getX(0)-event.getX(1),event.getY(0)-event.getY(1))
            }
            MotionEvent.ACTION_MOVE -> if(multi && event.pointerCount>=2) {
                val cx=(event.getX(0)+event.getX(1))/2; val cy=(event.getY(0)+event.getY(1))/2
                val next=hypot(event.getX(0)-event.getX(1),event.getY(0)-event.getY(1))
                onTransform(if(span>0) next/span else 1f,cx-centerX,cy-centerY,cx,cy)
                span=next; centerX=cx; centerY=cy
            } else if(!multi) id?.let { stroke ->
                val batch=MutableStrokeInputBatch()
                for(i in 0 until event.historySize) {
                    val xy=floatArrayOf(event.getHistoricalX(i),event.getHistoricalY(i)); viewToPage.mapPoints(xy)
                    val p=Point(xy[0],xy[1],event.getHistoricalEventTime(i)-startTime)
                    if(p.time>points.last().time) { points+=p; batch.add(InkEngine.input(p.x,p.y,p.time)) }
                }
                val p=point(event)
                if(p.time>points.last().time) { points+=p; batch.add(InkEngine.input(p.x,p.y,p.time)) }
                if(!batch.isEmpty()) wet.addToStroke(batch,stroke)
            }
            MotionEvent.ACTION_UP -> {
                id?.let { stroke -> val p=point(event); if(p.time>points.last().time) points+=p; wet.finishStroke(InkEngine.input(p.x,p.y,p.time),stroke); onFinished(points.toList()) }
                id=null; points.clear(); performClick(); parent.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> { id?.let { wet.cancelStroke(it) }; id=null; points.clear(); multi=false; parent.requestDisallowInterceptTouchEvent(false) }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDetachedFromWindow() { id?.let { wet.cancelStroke(it) }; id=null; super.onDetachedFromWindow() }
}
