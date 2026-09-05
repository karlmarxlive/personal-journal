package com.nsfr.myjournal

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

val Russian=Locale.forLanguageTag("ru-RU")
fun dateLabel(date: String)=LocalDate.parse(date).format(DateTimeFormatter.ofPattern("d MMMM yyyy",Russian))

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun JournalUi(model: JournalModel) {
    val prefs by model.prefs.collectAsStateWithLifecycle()
    val dark=when(prefs.theme) { "DARK" -> true; "LIGHT" -> false; else -> isSystemInDarkTheme() }
    val scheme=if(dark) darkColorScheme(primary=Color(0xFFBFCBA4),onPrimary=Color(0xFF27301B),secondaryContainer=Color(0xFF3C4531),onSecondaryContainer=Color(0xFFE0E8CE),background=Color(0xFF191B18),surface=Color(0xFF22251F),onSurface=Color(0xFFF2EFE8))
        else lightColorScheme(primary=Color(0xFF626E49),onPrimary=Color.White,background=Color(0xFFFAF7EF),surface=Color(0xFFFAF7EF),onSurface=Color(0xFF202124),secondaryContainer=Color(0xFFE9EBDD),onSecondaryContainer=Color(0xFF29311E))
    MaterialTheme(colorScheme=scheme) {
        val page by model.page.collectAsStateWithLifecycle()
        val entries by model.entries.collectAsStateWithLifecycle()
        val busy by model.busy.collectAsStateWithLifecycle()
        val error by model.error.collectAsStateWithLifecycle()
        val notice by model.notice.collectAsStateWithLifecycle()
        var screen by rememberSaveable { mutableStateOf("calendar") }
        var create by remember { mutableStateOf(false) }
        val snack=remember { SnackbarHostState() }
        LaunchedEffect(notice) { notice?.let { snack.showSnackbar(it); model.notice.value=null } }
        BackHandler(enabled=page!=null || screen!="calendar") { if(page!=null) model.closePage() else screen=if(screen=="template") "settings" else "calendar" }
        Scaffold(containerColor=scheme.background,snackbarHost={ SnackbarHost(snack) },topBar={
            if(page==null) TopAppBar(title={ Text(if(screen=="calendar") "Мой Журнал" else if(screen=="template") "Вопросы" else "Настройки",fontFamily=FontFamily.Serif,fontWeight=FontWeight.Medium) },
                navigationIcon={ if(screen!="calendar") TextButton(onClick={ screen=if(screen=="template") "settings" else "calendar" }) { Text("Назад") } },
                actions={ if(screen=="calendar") TextButton(onClick={ screen="settings" }) { Text("Настройки") } },colors=TopAppBarDefaults.topAppBarColors(containerColor=scheme.background))
        }) { insets ->
            Box(Modifier.fillMaxSize().padding(insets)) {
                if(page!=null) EditorScreen(model,page!!,prefs,dark)
                else when(screen) {
                    "settings" -> SettingsScreen(model,prefs,onTemplate={ screen="template" })
                    "template" -> TemplateScreen(model)
                    else -> CalendarScreen(entries,onDate={ model.open(it) },onCreate={ create=true },onContinue={ model.open(it) })
                }
                if(busy) Box(Modifier.fillMaxSize().background(scheme.surface.copy(alpha=.65f)).clickable(enabled=true,onClick={}),contentAlignment=Alignment.Center) { CircularProgressIndicator(Modifier.semantics { contentDescription="Выполняется действие" }) }
            }
        }
        if(create) AlertDialog(onDismissRequest={ create=false },title={ Text("Новая запись") },text={ Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Выберите дату. После создания её нельзя изменить.")
            listOf("Сегодня" to LocalDate.now(),"Вчера" to LocalDate.now().minusDays(1)).forEach { (label,date) ->
                val occupied=entries.any { it.date==date.toString() }
                OutlinedButton(onClick={ create=false; model.create(date) },enabled=!occupied,modifier=Modifier.fillMaxWidth()) { Text("$label · ${date.format(DateTimeFormatter.ofPattern("d MMMM",Russian))}"+if(occupied) " · уже есть запись" else "") }
            }
        } },confirmButton={},dismissButton={ TextButton(onClick={ create=false }) { Text("Отмена") } })
        if(error!=null) AlertDialog(onDismissRequest={ model.error.value=null },title={ Text("Не удалось выполнить действие") },text={ Text(error!!) },confirmButton={ TextButton(onClick={ model.error.value=null }) { Text("Понятно") } })
    }
}

@Composable fun CalendarScreen(entries: List<JournalEntry>,onDate:(String)->Unit,onCreate:()->Unit,onContinue:(String)->Unit) {
    var monthText by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    val month=YearMonth.parse(monthText)
    val draft=entries.find { it.status=="DRAFT" }
    Column(Modifier.fillMaxSize().padding(horizontal=24.dp),verticalArrangement=Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(24.dp))
            Text("Немного внимания\nк своей жизни",fontSize=31.sp,lineHeight=37.sp,fontFamily=FontFamily.Serif)
            Spacer(Modifier.height(28.dp))
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                TextButton(onClick={ monthText=month.minusMonths(1).toString() },modifier=Modifier.semantics { contentDescription="Предыдущий месяц" }) { Text("‹",fontSize=30.sp) }
                Text(month.format(DateTimeFormatter.ofPattern("LLLL yyyy",Russian)).replaceFirstChar { it.titlecase(Russian) },style=MaterialTheme.typography.titleMedium)
                TextButton(onClick={ monthText=month.plusMonths(1).toString() },modifier=Modifier.semantics { contentDescription="Следующий месяц" }) { Text("›",fontSize=30.sp) }
            }
            Row(Modifier.fillMaxWidth()) { listOf("Пн","Вт","Ср","Чт","Пт","Сб","Вс").forEach { day -> Box(Modifier.weight(1f).height(36.dp),contentAlignment=Alignment.Center) { Text(day,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) } } }
            var drag by remember { mutableFloatStateOf(0f) }
            Column(Modifier.testTag("calendar").pointerInput(monthText) { detectHorizontalDragGestures(onDragStart={ drag=0f },onHorizontalDrag={ change, amount -> change.consume(); drag+=amount },onDragEnd={ if(kotlin.math.abs(drag)>80) monthText=(if(drag<0) month.plusMonths(1) else month.minusMonths(1)).toString() }) }) {
                val offset=month.atDay(1).dayOfWeek.value-1
                repeat((offset+month.lengthOfMonth()+6)/7) { week -> Row(Modifier.fillMaxWidth()) {
                    repeat(7) { day ->
                        val n=week*7+day-offset+1; val date=if(n in 1..month.lengthOfMonth()) month.atDay(n) else null
                        val entry=entries.find { it.date==date?.toString() }
                        Box(Modifier.weight(1f).height(55.dp),contentAlignment=Alignment.Center) {
                            if(date!=null) Column(Modifier.size(46.dp).then(if(date==LocalDate.now()) Modifier.border(1.dp,MaterialTheme.colorScheme.primary,CircleShape) else Modifier)
                                .then(if(entry!=null) Modifier.clickable { onDate(entry.id) } else Modifier)
                                .semantics { contentDescription="${dateLabel(date.toString())}, ${when(entry?.status) { "SEALED" -> "сохранённая запись"; "DRAFT" -> "черновик"; else -> "нет записи" }}" },horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                                Text(n.toString(),fontWeight=if(entry!=null) FontWeight.Bold else FontWeight.Normal)
                                Spacer(Modifier.height(3.dp))
                                Box(Modifier.size(6.dp).then(when(entry?.status) { "SEALED" -> Modifier.background(MaterialTheme.colorScheme.primary,CircleShape); "DRAFT" -> Modifier.border(1.dp,MaterialTheme.colorScheme.primary,CircleShape); else -> Modifier }))
                            }
                        }
                    }
                } }
            }
            Spacer(Modifier.height(18.dp))
            Text("● Сохранено     ○ Черновик",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))
        }
        Button(onClick={ if(draft==null) onCreate() else onContinue(draft.id) },modifier=Modifier.fillMaxWidth().padding(bottom=20.dp).heightIn(min=58.dp),shape=RoundedCornerShape(18.dp)) {
            Text(if(draft==null) "Создать новую запись" else "Продолжить запись за ${LocalDate.parse(draft.date).format(DateTimeFormatter.ofPattern("d MMMM",Russian))}")
        }
    }
}

@Composable fun SettingsScreen(model: JournalModel,prefs: Preferences,onTemplate:()->Unit) {
    var warning by remember { mutableStateOf(false) }; var clear by remember { mutableStateOf(false) }; var phrase by remember { mutableStateOf("") }
    val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let(model::export) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        Text("Оформление",style=MaterialTheme.typography.titleMedium)
        listOf("SYSTEM" to "Системная","LIGHT" to "Светлая","DARK" to "Тёмная").forEach { (key,label) ->
            Row(Modifier.fillMaxWidth().clickable { model.setPreferences(theme=key) },verticalAlignment=Alignment.CenterVertically) { RadioButton(selected=prefs.theme==key,onClick={ model.setPreferences(theme=key) }); Text(label) }
        }
        HorizontalDivider()
        TextButton(onClick=onTemplate) { Text("Текущий шаблон и архив вопросов") }
        TextButton(onClick={ exporter.launch("MyJournal-export-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))}.zip") }) { Text("Экспортировать все записи") }
        HorizontalDivider()
        TextButton(onClick={ warning=true },colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)) { Text("Удалить все данные") }
        Text("Версия ${BuildConfig.VERSION_NAME}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if(warning) AlertDialog(onDismissRequest={ warning=false },title={ Text("Удалить все данные?") },text={ Text("Все записи, черновик, фотографии и настройки будут удалены без возможности восстановления. Рекомендуем сначала экспортировать записи.") },confirmButton={ TextButton(onClick={ warning=false; clear=true; phrase="" }) { Text("Продолжить") } },dismissButton={ TextButton(onClick={ warning=false }) { Text("Отмена") } })
    if(clear) AlertDialog(onDismissRequest={ clear=false },title={ Text("Подтвердите полную очистку") },text={ Column { Text("Введите УДАЛИТЬ ВСЁ"); OutlinedTextField(value=phrase,onValueChange={ phrase=it },singleLine=true,label={ Text("Фраза подтверждения") }) } },confirmButton={ TextButton(onClick={ clear=false; model.clearAll(phrase) },enabled=phrase=="УДАЛИТЬ ВСЁ") { Text("Удалить всё") } },dismissButton={ TextButton(onClick={ clear=false }) { Text("Отмена") } })
}

@Composable fun TemplateScreen(model: JournalModel) {
    val library by model.library.collectAsStateWithLifecycle(); val entries by model.entries.collectAsStateWithLifecycle()
    val draft=entries.find { it.status=="DRAFT" }
    var questions by remember(library) { mutableStateOf(library.filter { it.active }.map { it.id as String? to it.question }) }
    var editing by remember { mutableStateOf<Int?>(null) }; var value by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(draft!=null) { Text("Пока есть черновик, меняйте вопросы внутри него."); OutlinedButton(onClick={ model.open(draft.id) }) { Text("Открыть черновик") } }
        Text("Текущий шаблон",style=MaterialTheme.typography.titleLarge)
        questions.forEachIndexed { index,item ->
            Text("${index+1}. ${item.second}",style=MaterialTheme.typography.bodyLarge)
            if(draft==null) Row {
                TextButton(onClick={ editing=index; value=item.second }) { Text("Изменить") }
                TextButton(onClick={ questions=questions.toMutableList().apply { add(index-1,removeAt(index)) } },enabled=index>0) { Text("↑") }
                TextButton(onClick={ questions=questions.toMutableList().apply { add(index+1,removeAt(index)) } },enabled=index<questions.lastIndex) { Text("↓") }
                TextButton(onClick={ questions=questions.filterIndexed { i,_ -> i!=index } },enabled=questions.size>1) { Text("Убрать") }
            }
            HorizontalDivider()
        }
        if(draft==null) {
            OutlinedButton(onClick={ editing=-1; value="" }) { Text("Добавить вопрос") }
            Button(onClick={ model.editTemplate(questions) }) { Text("Сохранить шаблон") }
        }
        Spacer(Modifier.height(16.dp)); Text("Архив",style=MaterialTheme.typography.titleLarge)
        val archived=library.filter { !it.active }
        if(archived.isEmpty()) Text("Архив пока пуст",color=MaterialTheme.colorScheme.onSurfaceVariant)
        archived.forEach { q -> Text(q.question); if(draft==null) TextButton(onClick={ questions=questions+(q.id to q.question) },enabled=questions.none { it.first==q.id }) { Text("Вернуть вопрос") } }
    }
    if(editing!=null) AlertDialog(onDismissRequest={ editing=null },title={ Text(if(editing==-1) "Новый вопрос" else "Формулировка вопроса") },text={ OutlinedTextField(value=value,onValueChange={ value=it },label={ Text("Вопрос") }) },confirmButton={ TextButton(onClick={ if(editing==-1) questions=questions+(null to value.trim()) else questions=questions.mapIndexed { i,q -> if(i==editing) q.first to value.trim() else q }; editing=null },enabled=value.isNotBlank()) { Text("Готово") } },dismissButton={ TextButton(onClick={ editing=null }) { Text("Отмена") } })
}
