package com.nsfr.myjournal

import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

@Composable internal fun AnchoredText(id: String,section: String,text: String,question: Boolean,editable: Boolean,geometry: PageGeometry,onText:(String)->Unit,onFocus:(String,Int)->Unit,showPlaceholder: Boolean=false) {
    var field by remember(id) { mutableStateOf(TextFieldValue(text)) }
    if(field.text!=text) field=field.copy(text=text,selection=androidx.compose.ui.text.TextRange(field.selection.end.coerceAtMost(text.length)))
    val node=remember(id) { GeometryNode(section,text) }
    node.text=text
    DisposableEffect(id) { geometry.nodes[id]=node; onDispose { geometry.remove(id) } }
    val modifier=Modifier.fillMaxWidth().onGloballyPositioned { node.coordinates=it; geometry.changed(node) }
    val style=TextStyle(color=MaterialTheme.colorScheme.onSurface,fontSize=if(question) 20.sp else 16.sp,lineHeight=if(question) 28.sp else 24.sp,fontWeight=if(question) FontWeight.SemiBold else FontWeight.Normal,fontFamily=if(question) FontFamily.Serif else FontFamily.SansSerif)
    if(question) Text(text,modifier,style=style,onTextLayout={ node.layout=it; geometry.changed(node) })
    else BasicTextField(value=field,onValueChange={ next -> field=next; onFocus(id,next.selection.end); if(next.text!=text) onText(next.text) },readOnly=!editable,
        modifier=modifier.heightIn(min=64.dp).testTag("answer:$id"),textStyle=style,cursorBrush=SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.Sentences),
        onTextLayout={ node.layout=it; geometry.changed(node) },decorationBox={ inner -> Box { if(showPlaceholder && text.isEmpty() && editable) Text("Можно начать с пары слов…",style=style.copy(color=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.55f))); inner() } })
}

@Composable internal fun EditorPageViewport(modifier: Modifier=Modifier,content: @Composable BoxScope.()->Unit) {
    Box(modifier.fillMaxWidth().clipToBounds().testTag("editorViewport"),content=content)
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
    val geometry=remember(page.entry.id,density) { PageGeometry(density) }
    val painter=remember { InkPainter() }
    var zoom by remember { mutableFloatStateOf(1f) }; var panX by remember { mutableFloatStateOf(0f) }; var panY by remember { mutableFloatStateOf(0f) }
    var viewportWidth by remember { mutableIntStateOf(0) }; var viewportHeight by remember { mutableIntStateOf(0) }
    var pageHeight by remember { mutableIntStateOf(0) }
    val library by model.library.collectAsStateWithLifecycle()
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if(uri!=null) model.addPhoto(uri,focusBlock,cursor) }
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
        Row(Modifier.fillMaxWidth().zIndex(1f).background(MaterialTheme.colorScheme.background).testTag("editorHeader").padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick={ model.closePage() }) { Text("Назад") }
            Column(Modifier.weight(1f).padding(horizontal=6.dp)) {
                Text(dateLabel(page.entry.date),fontFamily=FontFamily.Serif,fontSize=19.sp)
                Text(if(readOnly) "Запечатано · только просмотр" else "Черновик",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(!readOnly) TextButton(onClick={ focus.clearFocus(); keyboard?.hide(); save=true },enabled=page.hasContent()) { Text("Сохранить") }
        }
        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f))
        EditorPageViewport(Modifier.weight(1f).onGloballyPositioned { viewportWidth=it.size.width; viewportHeight=it.size.height }) {
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
                    val sectionBlocks=page.blocks.filter { it.sectionId==section.id }.sortedBy { it.position }
                    val firstTextId=sectionBlocks.firstOrNull { it.type=="TEXT" }?.id
                    sectionBlocks.forEach { block -> key(block.id) {
                        if(block.type=="TEXT") AnchoredText(block.id,section.id,block.text,false,!readOnly && !pen,geometry,{ text ->
                            model.text(block.id,text,InkEngine.detachOverlapping(page.ink,block.id,block.text,text,geometry.measure()))
                        },{ id,position -> focusBlock=id; cursor=position },showPlaceholder=block.id==firstTextId)
                        else page.media.find { it.id==block.mediaId }?.let { asset ->
                            val node=remember(block.id) { GeometryNode(section.id,"") }
                            DisposableEffect(block.id) { geometry.nodes[block.id]=node; onDispose { geometry.remove(block.id) } }
                            LocalPhoto(File(context.filesDir,asset.path).absolutePath,"Фотография · ${block.widthPercent}%",Modifier.fillMaxWidth(block.widthPercent/100f).aspectRatio(asset.width.toFloat()/asset.height)
                                .onGloballyPositioned { node.coordinates=it; geometry.changed(node) }.then(if(!pen) Modifier.clickable { if(readOnly) fullPhoto=asset else photoMenu=block } else Modifier),
                                nearViewport={
                                    geometry.revision
                                    node.cached?.bounds?.let { bounds -> photoNearViewport(
                                        bounds.top*density*zoom+panY-scroll.value,
                                        bounds.bottom*density*zoom+panY-scroll.value,viewportHeight) } ?: false
                                },zoom={ zoom })
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
                } }
                view.onTransform={ scale,dx,dy,cx,cy ->
                    val next=(zoom*scale).coerceIn(1f,3f); val ratio=next/zoom
                    panX=(cx-(cx-panX)*ratio+dx).coerceIn((viewportWidth*(1-next)).coerceAtMost(0f),0f)
                    panY=(cy+scroll.value-(cy+scroll.value-panY)*ratio+dy).coerceIn((viewportHeight-pageHeight*next+scroll.value).coerceAtMost(scroll.value.toFloat()),scroll.value.toFloat())
                    zoom=next
                }
            })
        }
        if(!readOnly) Surface(shadowElevation=3.dp,color=MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=6.dp)) {
                if(pen) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                        Row { listOf("Основной","Оранжевый","Синий").forEachIndexed { i,label ->
                            Box(Modifier.size(48.dp).padding(5.dp).then(if(prefs.color==i) Modifier.border(2.dp,MaterialTheme.colorScheme.primary,CircleShape) else Modifier).padding(5.dp).background(Color(inkColor(i,dark)),CircleShape).clickable { model.setPreferences(color=i) }.semantics { contentDescription="$label цвет" })
                        } }
                        IconButton(onClick={ model.undo() },enabled=page.ink.any { it.active },modifier=Modifier.size(48.dp)) { Icon(painterResource(R.drawable.ic_undo),"Отменить") }
                        IconButton(onClick={ model.redo() },enabled=page.ink.any { !it.active },modifier=Modifier.size(48.dp)) { Icon(painterResource(R.drawable.ic_redo),"Вернуть") }
                    }
                    Row(verticalAlignment=Alignment.CenterVertically) { Text("${prefs.thickness.toInt()} dp",style=MaterialTheme.typography.labelSmall); Slider(value=prefs.thickness,onValueChange={ model.setPreferences(thickness=it) },valueRange=2f..8f,modifier=Modifier.weight(1f).semantics { contentDescription="Толщина ручки от 2 до 8" }) }
                }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                    Row {
                        FilterChip(selected=!pen,onClick={ textMode() },modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp),label={ Icon(painterResource(R.drawable.ic_text),"Текст",Modifier.size(24.dp)) })
                        Spacer(Modifier.width(8.dp))
                        FilterChip(selected=pen,onClick={ focus.clearFocus(); keyboard?.hide(); editQuestions=false; pen=true },modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp),label={ Icon(painterResource(R.drawable.ic_pen),"Ручка",Modifier.size(24.dp)) })
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
