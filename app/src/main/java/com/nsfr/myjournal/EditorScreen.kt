package com.nsfr.myjournal

import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

private data class GeometryNode(val section: String,var text: String,var coordinates: LayoutCoordinates?=null,var layout: TextLayoutResult?=null)
private class PageGeometry(val density: Float) {
    var root: LayoutCoordinates?=null
    val nodes=mutableMapOf<String,GeometryNode>()
    var revision by mutableIntStateOf(0)
    private var previous=emptyList<TextGeometry>()
    fun measure(): List<TextGeometry> {
        val parent=root?.takeIf { it.isAttached } ?: return emptyList()
        return nodes.mapNotNull { (id,node) ->
            val coords=node.coordinates?.takeIf { it.isAttached } ?: return@mapNotNull null
            val offset=parent.localPositionOf(coords,Offset.Zero)/density
            val bounds=Bounds(offset.x,offset.y,offset.x+coords.size.width/density,offset.y+coords.size.height/density)
            val layout=node.layout
            val glyphs=if(layout==null) emptyList() else node.text.indices.map { index ->
                val r=layout.getBoundingBox(index); val line=layout.getLineForOffset(index)
                Glyph(index,Bounds(offset.x+r.left/density,offset.y+r.top/density,offset.x+r.right/density,offset.y+r.bottom/density),offset.y+layout.getLineBaseline(line)/density)
            }
            TextGeometry(id,node.section,node.text,bounds,glyphs)
        }
    }
    fun changed() { val next=measure(); if(next!=previous) { previous=next; revision++ } }
}

@Composable private fun AnchoredText(id: String,section: String,text: String,question: Boolean,editable: Boolean,geometry: PageGeometry,onText:(String)->Unit,onFocus:(String,Int)->Unit) {
    var field by remember(id) { mutableStateOf(TextFieldValue(text)) }
    if(field.text!=text) field=field.copy(text=text,selection=androidx.compose.ui.text.TextRange(field.selection.end.coerceAtMost(text.length)))
    val node=remember(id) { GeometryNode(section,text) }
    node.text=text
    DisposableEffect(id) { geometry.nodes[id]=node; onDispose { geometry.nodes.remove(id); geometry.changed() } }
    val modifier=Modifier.fillMaxWidth().onGloballyPositioned { node.coordinates=it; geometry.changed() }
    val style=TextStyle(color=MaterialTheme.colorScheme.onSurface,fontSize=if(question) 19.sp else 16.sp,lineHeight=if(question) 26.sp else 24.sp,fontWeight=if(question) FontWeight.SemiBold else FontWeight.Normal,fontFamily=FontFamily.SansSerif)
    if(question) Text(text,modifier,style=style,onTextLayout={ node.layout=it; geometry.changed() })
    else BasicTextField(value=field,onValueChange={ next -> field=next; onFocus(id,next.selection.end); if(next.text!=text) onText(next.text) },readOnly=!editable,
        modifier=modifier.heightIn(min=64.dp).testTag("answer:$id"),textStyle=style,cursorBrush=SolidColor(MaterialTheme.colorScheme.primary),
        onTextLayout={ node.layout=it; geometry.changed() },decorationBox={ inner -> Box { if(text.isEmpty() && editable) Text("Можно начать с пары слов…",style=style.copy(color=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.55f))); inner() } })
}

@Composable private fun LocalPhoto(path: String,description: String,modifier: Modifier) {
    val bitmap by produceState<android.graphics.Bitmap?>(null,path) { value=withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) } }
    bitmap?.let { Image(it.asImageBitmap(),description,modifier,contentScale=androidx.compose.ui.layout.ContentScale.Fit) }
        ?: Box(modifier,contentAlignment=Alignment.Center) { Text("Загрузка фотографии…") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun EditorScreen(model: JournalModel,page: EntryPage,prefs: Preferences,dark: Boolean) {
    val readOnly=page.entry.status=="SEALED"
    var pen by remember(page.entry.id) { mutableStateOf(false) }
    var editQuestions by remember { mutableStateOf(false) }
    var save by remember { mutableStateOf(false) }; var delete by remember { mutableStateOf(false) }
    var editingQuestion by remember { mutableStateOf<String?>(null) }; var questionText by remember { mutableStateOf("") }
    var archive by remember { mutableStateOf(false) }
    var photoMenu by remember { mutableStateOf<ContentBlock?>(null) }; var fullPhoto by remember { mutableStateOf<MediaAsset?>(null) }
    var focusBlock by remember(page.entry.id) { mutableStateOf(page.blocks.firstOrNull { it.type=="TEXT" }?.id ?: "") }
    var cursor by remember { mutableIntStateOf(0) }
    val context=LocalContext.current; val density=LocalDensity.current.density
    val keyboard=LocalSoftwareKeyboardController.current; val focus=LocalFocusManager.current
    val scroll=rememberScrollState(); val scope=rememberCoroutineScope()
    val geometry=remember(page.entry.id) { PageGeometry(density) }
    val painter=remember { InkPainter() }
    var zoom by remember { mutableFloatStateOf(1f) }; var panX by remember { mutableFloatStateOf(0f) }; var panY by remember { mutableFloatStateOf(0f) }
    var viewportWidth by remember { mutableIntStateOf(0) }; var viewportHeight by remember { mutableIntStateOf(0) }
    var pageHeight by remember { mutableIntStateOf(0) }
    var hint by remember { mutableStateOf<String?>(null) }
    val saving by model.saving.collectAsStateWithLifecycle()
    val library by model.library.collectAsStateWithLifecycle()
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if(uri!=null) model.addPhoto(uri,focusBlock,cursor) }
    LaunchedEffect(hint) { if(hint!=null) { kotlinx.coroutines.delay(2200); hint=null } }
    fun matrix() = Matrix().apply { setScale(density*zoom,density*zoom); postTranslate(panX,panY-scroll.value) }
    fun textMode() {
        pen=false
        val initialZoom=zoom; val initialX=panX; val initialY=panY
        val targetScroll=((scroll.value-panY)/zoom).toInt().coerceIn(0,scroll.maxValue)
        scope.launch {
            val animation=Animatable(0f)
            animation.animateTo(1f,animationSpec=androidx.compose.animation.core.tween(180)) {
                zoom=initialZoom+(1-initialZoom)*value; panX=initialX*(1-value)
                panY=initialY+(scroll.value-targetScroll-initialY)*value
            }
            scroll.scrollTo(targetScroll); zoom=1f; panX=0f; panY=0f
        }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick={ model.closePage() }) { Text("Назад") }
            Column(Modifier.weight(1f).padding(horizontal=6.dp)) {
                Text(dateLabel(page.entry.date),fontFamily=FontFamily.Serif,fontSize=19.sp)
                Text(if(readOnly) "Запечатано · только просмотр" else if(saving) "Сохраняется…" else "Черновик · сохранён на устройстве",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(!readOnly) TextButton(onClick={ focus.clearFocus(); keyboard?.hide(); save=true },enabled=page.hasContent()) { Text("Сохранить") }
        }
        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f))
        Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { viewportWidth=it.size.width; viewportHeight=it.size.height }) {
            Column(Modifier.fillMaxWidth().verticalScroll(scroll,enabled=!pen).graphicsLayer { scaleX=zoom; scaleY=zoom; transformOrigin=TransformOrigin(0f,0f); translationX=panX; translationY=panY }
                .onGloballyPositioned { geometry.root=it; pageHeight=it.size.height; geometry.changed() }.padding(24.dp)) {
                page.sections.sortedBy { it.position }.forEach { section -> key(section.id) {
                    AnchoredText("q:${section.id}",section.id,section.question,true,false,geometry,{}, { _,_ -> })
                    if(editQuestions && !readOnly && !pen) Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(onClick={ editingQuestion=section.id; questionText=section.question }) { Text("Изменить") }
                        TextButton(onClick={ model.moveQuestion(section.id,-1) },enabled=section.position>0) { Text("↑") }
                        TextButton(onClick={ model.moveQuestion(section.id,1) },enabled=section.position<page.sections.lastIndex) { Text("↓") }
                        TextButton(onClick={ model.removeQuestion(section.id) },enabled=page.sections.size>1 && page.sectionEmpty(section.id)) { Text("Убрать") }
                    }
                    Spacer(Modifier.height(12.dp))
                    page.blocks.filter { it.sectionId==section.id }.sortedBy { it.position }.forEach { block -> key(block.id) {
                        if(block.type=="TEXT") AnchoredText(block.id,section.id,block.text,false,!readOnly && !pen,geometry,{ text ->
                            model.text(block.id,text,InkEngine.detachOverlapping(page.ink,block.id,block.text,text,geometry.measure()))
                        },{ id,position -> focusBlock=id; cursor=position })
                        else page.media.find { it.id==block.mediaId }?.let { asset ->
                            val node=remember(block.id) { GeometryNode(section.id,"") }
                            DisposableEffect(block.id) { geometry.nodes[block.id]=node; onDispose { geometry.nodes.remove(block.id) } }
                            LocalPhoto(File(context.filesDir,asset.thumbnail).absolutePath,"Фотография · ${block.widthPercent}%",Modifier.fillMaxWidth(block.widthPercent/100f).aspectRatio(asset.width.toFloat()/asset.height)
                                .onGloballyPositioned { node.coordinates=it; geometry.changed() }.then(if(!pen) Modifier.clickable { if(readOnly) fullPhoto=asset else photoMenu=block } else Modifier))
                        }
                        Spacer(Modifier.height(16.dp))
                    } }
                    Spacer(Modifier.height(24.dp))
                } }
                if(editQuestions && !readOnly && !pen) { OutlinedButton(onClick={ editingQuestion="new"; questionText="" }) { Text("Добавить вопрос") }; TextButton(onClick={ archive=true }) { Text("Вернуть из архива") } }
                Spacer(Modifier.height(140.dp))
            }
            Canvas(Modifier.matchParentSize()) {
                geometry.revision // Invalidates the dry surface after text layout and photo resize.
                val transform=matrix(); val canvas=drawContext.canvas.nativeCanvas
                canvas.save(); canvas.concat(transform)
                if(page.ink.any { it.active }) painter.draw(canvas,page.ink,geometry.measure(),transform,dark=dark)
                canvas.restore()
            }
            if(pen && !readOnly) AndroidView(factory={ InkInputView(it) },modifier=Modifier.matchParentSize().testTag("inkSurface"),update={ view ->
                view.pageToView=matrix(); view.viewToPage=Matrix().also { view.pageToView.invert(it) }
                view.logicalColor=prefs.color; view.thickness=prefs.thickness; view.dark=dark
                view.onFinished={ points -> val metrics=geometry.measure(); if(points.isNotEmpty() && metrics.isNotEmpty()) {
                    val stroke=InkEngine.capture(points,page,metrics,prefs.color,prefs.thickness); model.addInk(stroke)
                    hint=when(stroke.type) { "UNDERLINE" -> "Подчёркивание связано с текстом"; "CIRCLE" -> "Обводка связана с текстом"; "STRIKE" -> "Зачёркивание связано с текстом"; else -> null }
                } }
                view.onTransform={ scale,dx,dy,cx,cy ->
                    val next=(zoom*scale).coerceIn(1f,3f); val ratio=next/zoom
                    panX=(cx-(cx-panX)*ratio+dx).coerceIn((viewportWidth*(1-next)).coerceAtMost(0f),0f)
                    panY=(cy+scroll.value-(cy+scroll.value-panY)*ratio+dy).coerceIn((viewportHeight-pageHeight*next+scroll.value).coerceAtMost(scroll.value.toFloat()),scroll.value.toFloat())
                    zoom=next
                }
            })
            hint?.let { Surface(Modifier.align(Alignment.BottomCenter).padding(12.dp),shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.inverseSurface) { Text(it,Modifier.padding(12.dp),color=MaterialTheme.colorScheme.inverseOnSurface,style=MaterialTheme.typography.bodySmall) } }
        }
        if(!readOnly) Surface(shadowElevation=3.dp,color=MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=6.dp)) {
                if(pen) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                        Row { listOf("Основной","Оранжевый","Синий").forEachIndexed { i,label ->
                            Box(Modifier.size(48.dp).padding(5.dp).then(if(prefs.color==i) Modifier.border(2.dp,MaterialTheme.colorScheme.primary,CircleShape) else Modifier).padding(5.dp).background(Color(inkColor(i,dark)),CircleShape).clickable { model.setPreferences(color=i) }.semantics { contentDescription="$label цвет" })
                        } }
                        TextButton(onClick={ model.undo() },enabled=page.ink.any { it.active }) { Text("Отменить") }
                        TextButton(onClick={ model.redo() },enabled=page.ink.any { !it.active }) { Text("Вернуть") }
                    }
                    Row(verticalAlignment=Alignment.CenterVertically) { Text("${prefs.thickness.toInt()} dp",style=MaterialTheme.typography.labelSmall); Slider(value=prefs.thickness,onValueChange={ model.setPreferences(thickness=it) },valueRange=2f..8f,modifier=Modifier.weight(1f).semantics { contentDescription="Толщина ручки от 2 до 8" }) }
                }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                    Row {
                        FilterChip(selected=!pen,onClick={ textMode() },label={ Text("Текст") })
                        Spacer(Modifier.width(8.dp))
                        FilterChip(selected=pen,onClick={ focus.clearFocus(); keyboard?.hide(); editQuestions=false; pen=true },label={ Text("Ручка") })
                    }
                    if(!pen) {
                        TextButton(onClick={ focus.clearFocus(); picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Фото") }
                        TextButton(onClick={ focus.clearFocus(); editQuestions=!editQuestions }) { Text("Вопросы") }
                    }
                }
                if(editQuestions && !pen) TextButton(onClick={ delete=true },colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)) { Text("Удалить черновик") }
            }
        }
    }
    if(save) AlertDialog(onDismissRequest={ save=false },title={ Text("Сохранить запись?") },text={ Text("После сохранения запись нельзя будет изменить или удалить") },confirmButton={ TextButton(onClick={ save=false; textMode(); editQuestions=false; model.seal() }) { Text("Подтвердить сохранение") } },dismissButton={ TextButton(onClick={ save=false }) { Text("Отмена") } })
    if(delete) AlertDialog(onDismissRequest={ delete=false },title={ Text("Удалить черновик?") },text={ Text("Черновик и его фотографии будут удалены. Текущий шаблон сохранится.") },confirmButton={ TextButton(onClick={ delete=false; model.deleteDraft() }) { Text("Удалить черновик") } },dismissButton={ TextButton(onClick={ delete=false }) { Text("Отмена") } })
    if(editingQuestion!=null) AlertDialog(onDismissRequest={ editingQuestion=null },title={ Text(if(editingQuestion=="new") "Новый вопрос" else "Формулировка вопроса") },text={ OutlinedTextField(value=questionText,onValueChange={ questionText=it },label={ Text("Вопрос") }) },confirmButton={ TextButton(onClick={
        if(editingQuestion=="new") model.addQuestion(questionText) else {
            val section=page.sections.first { it.id==editingQuestion }
            model.question(section.id,questionText,InkEngine.detachOverlapping(page.ink,"q:${section.id}",section.question,questionText.trim(),geometry.measure()))
        }; editingQuestion=null
    },enabled=questionText.isNotBlank()) { Text("Готово") } },dismissButton={ TextButton(onClick={ editingQuestion=null }) { Text("Отмена") } })
    if(archive) AlertDialog(onDismissRequest={ archive=false },title={ Text("Архив вопросов") },text={ Column(Modifier.verticalScroll(rememberScrollState())) {
        val available=library.filter { !it.active && page.sections.none { s -> s.templateId==it.id } }
        if(available.isEmpty()) Text("Архив пока пуст")
        available.forEach { q -> TextButton(onClick={ model.addQuestion(q.question,q.id); archive=false }) { Text(q.question) } }
    } },confirmButton={ TextButton(onClick={ archive=false }) { Text("Закрыть") } })
    photoMenu?.let { block -> AlertDialog(onDismissRequest={ photoMenu=null },title={ Text("Фотография") },text={ Row { listOf(50,75,100).forEach { size -> TextButton(onClick={ model.resizePhoto(block.id,size); photoMenu=null }) { Text("$size%") } } } },confirmButton={ TextButton(onClick={ model.removePhoto(block.id,geometry.measure()); photoMenu=null }) { Text("Удалить фото") } },dismissButton={ TextButton(onClick={ photoMenu=null }) { Text("Закрыть") } }) }
    fullPhoto?.let { asset -> Dialog(onDismissRequest={ fullPhoto=null },properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) { LocalPhoto(File(context.filesDir,asset.path).absolutePath,"Фотография на весь экран",Modifier.fillMaxSize()); TextButton(onClick={ fullPhoto=null },modifier=Modifier.align(Alignment.TopEnd).statusBarsPadding()) { Text("Закрыть",color=Color.White) } }
    } }
}
