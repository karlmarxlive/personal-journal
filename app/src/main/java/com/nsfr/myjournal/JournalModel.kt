package com.nsfr.myjournal

import android.app.Application
import android.net.Uri
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

val Application.preferences by preferencesDataStore("settings")
class JournalApp : Application() { val repository by lazy { JournalRepository(this) } }
data class Preferences(val theme: String = "SYSTEM",val color: Int = 0,val thickness: Float = 3f)

class JournalModel(application: Application) : AndroidViewModel(application) {
    private val app=application as JournalApp
    val repository=app.repository
    val entries=MutableStateFlow<List<JournalEntry>>(emptyList())
    val library=MutableStateFlow<List<PromptLibraryItem>>(emptyList())
    val page=MutableStateFlow<EntryPage?>(null)
    val prefs=app.preferences.data.map { Preferences(it[stringPreferencesKey("theme")] ?: "SYSTEM",it[intPreferencesKey("color")] ?: 0,it[floatPreferencesKey("thickness")] ?: 3f) }.stateIn(viewModelScope,SharingStarted.Eagerly,Preferences())
    val error=MutableStateFlow<String?>(null)
    val notice=MutableStateFlow<String?>(null)
    val busy=MutableStateFlow(false)
    val saving=MutableStateFlow(false)
    private var pending: Job?=null
    private val writes=Mutex()
    @Volatile private var dirty=false
    init { refresh() }
    private fun action(block: suspend ()->Unit) = viewModelScope.launch {
        busy.value=true
        try { block() } catch(e: Exception) { if(e is CancellationException) throw e; error.value=e.message ?: "Не удалось выполнить действие" }
        finally { busy.value=false }
    }
    fun refresh() = viewModelScope.launch { try { entries.value=repository.entries(); library.value=repository.library() } catch(e: Exception) { error.value=e.message } }
    fun open(id: String) = action { flush(); page.value=repository.page(id) }
    fun create(date: LocalDate) = action { flush(); page.value=repository.create(date); refresh().join() }
    fun change(transform: (EntryPage)->EntryPage) {
        val old=page.value ?: return
        if(old.entry.status != "DRAFT" || busy.value) return
        page.value=transform(old); dirty=true; saving.value=true
        pending?.cancel()
        pending=viewModelScope.launch { delay(300); try { flush() } catch(e: Exception) { if(e !is CancellationException) error.value="Не удалось сохранить черновик: ${e.message}" } }
    }
    suspend fun flush() = withContext(Dispatchers.IO) { writes.withLock {
        if(dirty) {
            val snapshot=page.value ?: return@withLock
            repository.save(snapshot)
            if(page.value === snapshot) { dirty=false; saving.value=false }
        }
    } }
    fun flushBackground() { pending?.cancel(); runBlocking(Dispatchers.IO) { try { flush() } catch(e: Exception) { error.value="Ошибка автосохранения: ${e.message}" } } }
    fun closePage() = action { pending?.cancel(); flush(); page.value=null; refresh().join() }
    fun seal() = action { pending?.cancel(); flush(); val p=page.value ?: return@action; repository.seal(p); dirty=false; page.value=repository.page(p.entry.id); refresh().join() }
    fun deleteDraft() = action { pending?.cancel(); flush(); page.value?.let { repository.deleteDraft(it.entry.id) }; dirty=false; page.value=null; refresh().join() }
    fun text(blockId: String, text: String, detachedInk: List<InkStroke>? = null) = change { p ->
        val old=p.blocks.find { it.id==blockId } ?: return@change p
        p.copy(blocks=p.blocks.map { if(it.id==blockId) it.copy(text=text) else it },ink=detachedInk ?: adjustAnchors(p.ink,blockId,old.text,text))
    }
    fun question(id: String, text: String, detachedInk: List<InkStroke>? = null) = change { p ->
        val old=p.sections.first { it.id==id }
        p.copy(sections=p.sections.map { if(it.id==id) it.copy(question=text.trim()) else it },ink=detachedInk ?: adjustAnchors(p.ink,"q:$id",old.question,text.trim()))
    }
    private fun adjustAnchors(ink: List<InkStroke>, anchor: String, old: String, new: String) = ink.map { s ->
        if(s.anchor != anchor || s.type=="FREE") s else {
            val range=Magnet.remapRange(old,new,s.start,s.end)
            if(range==null) s.copy(type="FREE",start=0,end=0,anchorGeometry="") else s.copy(start=range.first,end=range.last+1)
        }
    }
    fun moveQuestion(id: String, offset: Int) = change { p ->
        val list=p.sections.sortedBy { it.position }.toMutableList(); val from=list.indexOfFirst { it.id==id }; val to=(from+offset).coerceIn(list.indices)
        list.add(to,list.removeAt(from)); p.copy(sections=list.mapIndexed { i,s -> s.copy(position=i) })
    }
    fun removeQuestion(id: String) = action {
        pending?.cancel(); flush()
        val p=page.value ?: return@action
        check(p.entry.status=="DRAFT")
        check(p.sections.size>1 && p.sectionEmpty(id)) { "Можно убрать только пустой вопрос; хотя бы один должен остаться" }
        page.value=p.copy(sections=p.sections.filterNot { it.id==id }.mapIndexed { i,s -> s.copy(position=i) },blocks=p.blocks.filterNot { it.sectionId==id })
        dirty=true; flush()
    }
    fun addQuestion(text: String, templateId: String?=null) = change { p ->
        if(text.isBlank()) p else { val s=JournalSection(entryId=p.entry.id,templateId=templateId,question=text.trim(),position=p.sections.size); p.copy(sections=p.sections+s,blocks=p.blocks+ContentBlock(sectionId=s.id,position=0)) }
    }
    fun addInk(stroke: InkStroke) = change { p -> p.copy(ink=p.ink.filter { it.active } + stroke.copy(position=(p.ink.filter { it.active }.maxOfOrNull { it.position } ?: -1)+1)) }
    fun undo() = change { it.copy(ink=Magnet.undo(it.ink)) }
    fun redo() = change { it.copy(ink=Magnet.redo(it.ink)) }
    fun addPhoto(uri: Uri, blockId: String, cursor: Int) = action {
        flush()
        val p=page.value ?: return@action
        if(p.entry.status!="DRAFT") return@action
        val block=p.blocks.firstOrNull { it.id==blockId } ?: return@action
        val media=withContext(Dispatchers.IO) { PhotoStore(app).import(uri,p.entry.id) }
        // Insertion follows the paragraph containing the cursor, keeping trailing paragraphs editable.
        val newline=block.text.indexOf('\n',cursor.coerceIn(0,block.text.length))
        val split=if(newline<0) block.text.length else newline+1
        val after=ContentBlock(sectionId=block.sectionId,position=block.position+2,text=block.text.substring(split))
        val photo=ContentBlock(sectionId=block.sectionId,position=block.position+1,type="PHOTO",mediaId=media.id)
        val ink=p.ink.map { s -> if(s.anchor==block.id && s.start>=split && s.type!="FREE") s.copy(anchor=after.id,start=s.start-split,end=s.end-split) else if(s.anchor==block.id && s.end>split && s.type!="FREE") s.copy(type="FREE",anchorGeometry="") else s }
        page.value=p.copy(blocks=p.blocks.map { if(it.id==block.id) it.copy(text=it.text.substring(0,split)) else if(it.sectionId==block.sectionId && it.position>block.position) it.copy(position=it.position+2) else it }+photo+after,media=p.media+media,ink=ink)
        dirty=true; flush()
    }
    fun resizePhoto(id: String, size: Int) = change { p -> p.copy(blocks=p.blocks.map { if(it.id==id) it.copy(widthPercent=size) else it }) }
    fun removePhoto(id: String, geometry: List<TextGeometry> = emptyList()) = action {
        pending?.cancel(); flush()
        val p=page.value ?: return@action
        if(p.entry.status!="DRAFT") return@action
        val block=p.blocks.first { it.id==id }; val asset=p.media.first { it.id==block.mediaId }
        val fallback="q:${block.sectionId}"
        val target=geometry.find { it.id==fallback }
        page.value=p.copy(blocks=p.blocks.filterNot { it.id==id },media=p.media.filterNot { it.id==asset.id },ink=p.ink.map {
            if(it.anchor==id && target!=null) it.copy(anchor=fallback,serialized=InkEngine.encode(InkEngine.projected(it,geometry).flatten()),originX=target.bounds.left,originY=target.bounds.top)
            else it
        })
        dirty=true; flush()
        withContext(Dispatchers.IO) { java.io.File(app.filesDir,asset.path).delete(); java.io.File(app.filesDir,asset.thumbnail).delete() }
    }
    fun setPreferences(theme: String=prefs.value.theme,color: Int=prefs.value.color,thickness: Float=prefs.value.thickness) = viewModelScope.launch {
        app.preferences.edit { it[stringPreferencesKey("theme")]=theme; it[intPreferencesKey("color")]=color; it[floatPreferencesKey("thickness")]=thickness }
    }
    fun editTemplate(questions: List<Pair<String?,String>>) = action { repository.editTemplate(questions); refresh().join() }
    fun clearAll(phrase: String) = action { pending?.cancel(); writes.withLock { repository.reset(phrase); dirty=false; page.value=null; app.preferences.edit { it.clear() } }; refresh().join() }
    fun export(uri: Uri) = action {
        withContext(Dispatchers.IO) {
            val pages=repository.entries().filter { it.status=="SEALED" }.mapNotNull { repository.page(it.id) }
            app.contentResolver.openOutputStream(uri,"wt")!!.use { JournalExport(app).write(pages,it) }
        }
        notice.value="Экспорт сохранён"
    }
}
