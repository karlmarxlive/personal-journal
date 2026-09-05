package com.nsfr.myjournal

import android.content.Context
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.util.UUID

fun uuid(): String = UUID.randomUUID().toString()
val initialQuestions = listOf(
    "Что сегодня было хорошего?", "За что я сегодня благодарен?",
    "Где мне сегодня НЕ понравилось то, что я делал? Что я хочу изменить?",
    "Где мне сегодня ПОНРАВИЛОСЬ то, что я делал? Что я хочу оставить или усилить?"
)

@Entity(indices = [Index(value = ["date"], unique = true)])
data class JournalEntry(@PrimaryKey val id: String = uuid(), val date: String, val status: String = "DRAFT",
    val createdAt: Long = System.currentTimeMillis(), val modifiedAt: Long = createdAt, val sealedAt: Long? = null)

@Entity
data class PromptLibraryItem(@PrimaryKey val id: String = uuid(), val question: String, val active: Boolean = true, val position: Int)

@Entity(foreignKeys = [ForeignKey(entity = JournalEntry::class, parentColumns = ["id"], childColumns = ["entryId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = PromptLibraryItem::class, parentColumns = ["id"], childColumns = ["templateId"])], indices = [Index("entryId"), Index("templateId")])
data class JournalSection(@PrimaryKey val id: String = uuid(), val entryId: String, val templateId: String? = null, val question: String, val position: Int)

@Entity(foreignKeys = [ForeignKey(entity = JournalEntry::class, parentColumns = ["id"], childColumns = ["entryId"], onDelete = ForeignKey.CASCADE)], indices = [Index("entryId")])
data class MediaAsset(@PrimaryKey val id: String = uuid(), val entryId: String, val path: String, val mime: String, val width: Int, val height: Int, val sha256: String, val thumbnail: String)

@Entity(foreignKeys = [ForeignKey(entity = JournalSection::class, parentColumns = ["id"], childColumns = ["sectionId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = MediaAsset::class, parentColumns = ["id"], childColumns = ["mediaId"])], indices = [Index("sectionId"), Index("mediaId")])
data class ContentBlock(@PrimaryKey val id: String = uuid(), val sectionId: String, val position: Int, val type: String = "TEXT", val text: String = "", val mediaId: String? = null, val widthPercent: Int = 100)

@Entity(foreignKeys = [ForeignKey(entity = JournalEntry::class, parentColumns = ["id"], childColumns = ["entryId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = JournalSection::class, parentColumns = ["id"], childColumns = ["sectionId"], onDelete = ForeignKey.CASCADE)], indices = [Index("entryId"), Index("sectionId")])
data class InkStroke(@PrimaryKey val id: String = uuid(), val entryId: String, val sectionId: String,
    val serialized: ByteArray, val position: Int, val color: Int, val thickness: Float,
    val type: String = "FREE", val anchor: String, val start: Int = 0, val end: Int = 0,
    val originX: Float = 0f, val originY: Float = 0f, val referenceWidth: Float = 1f,
    val referenceHeight: Float = 1f, val active: Boolean = true, val anchorGeometry: String = "")

data class EntryPage(val entry: JournalEntry, val sections: List<JournalSection>, val blocks: List<ContentBlock>, val media: List<MediaAsset>, val ink: List<InkStroke>) {
    fun hasContent() = blocks.any { it.type == "PHOTO" || it.text.isNotBlank() } || ink.any { it.active }
    fun sectionEmpty(id: String) = blocks.none { it.sectionId == id && (it.type == "PHOTO" || it.text.isNotBlank()) } && ink.none { it.sectionId == id }
}

@Dao
abstract class JournalDao {
    @Query("SELECT * FROM JournalEntry ORDER BY date") abstract suspend fun entries(): List<JournalEntry>
    @Query("SELECT * FROM JournalEntry WHERE id=:id") abstract suspend fun entry(id: String): JournalEntry?
    @Query("SELECT * FROM JournalSection WHERE entryId=:id ORDER BY position") abstract suspend fun sections(id: String): List<JournalSection>
    @Query("SELECT ContentBlock.* FROM ContentBlock JOIN JournalSection ON ContentBlock.sectionId=JournalSection.id WHERE JournalSection.entryId=:id ORDER BY JournalSection.position, ContentBlock.position") abstract suspend fun blocks(id: String): List<ContentBlock>
    @Query("SELECT * FROM MediaAsset WHERE entryId=:id") abstract suspend fun media(id: String): List<MediaAsset>
    @Query("SELECT * FROM InkStroke WHERE entryId=:id ORDER BY position") abstract suspend fun ink(id: String): List<InkStroke>
    @Query("SELECT * FROM PromptLibraryItem ORDER BY position, question") abstract suspend fun library(): List<PromptLibraryItem>
    @Insert protected abstract suspend fun insertEntry(entry: JournalEntry)
    @Insert protected abstract suspend fun insertSections(rows: List<JournalSection>)
    @Insert protected abstract suspend fun insertBlocks(rows: List<ContentBlock>)
    @Insert protected abstract suspend fun insertMedia(rows: List<MediaAsset>)
    @Insert protected abstract suspend fun insertInk(rows: List<InkStroke>)
    @Insert protected abstract suspend fun insertPrompt(row: PromptLibraryItem)
    @Query("UPDATE JournalEntry SET modifiedAt=:time WHERE id=:id AND status='DRAFT'") protected abstract suspend fun touch(id: String, time: Long): Int
    @Query("DELETE FROM JournalSection WHERE entryId=:id AND EXISTS(SELECT 1 FROM JournalEntry WHERE id=:id AND status='DRAFT')") protected abstract suspend fun deleteSections(id: String)
    @Query("DELETE FROM MediaAsset WHERE entryId=:id AND EXISTS(SELECT 1 FROM JournalEntry WHERE id=:id AND status='DRAFT')") protected abstract suspend fun deleteMedia(id: String)
    @Query("DELETE FROM JournalEntry WHERE id=:id AND status='DRAFT'") abstract suspend fun deleteDraft(id: String): Int
    @Query("UPDATE JournalEntry SET status='SEALED', sealedAt=:time, modifiedAt=:time WHERE id=:id AND status='DRAFT'") protected abstract suspend fun sealRow(id: String, time: Long): Int
    @Query("UPDATE PromptLibraryItem SET active=0") protected abstract suspend fun archiveTemplate()
    @Query("UPDATE PromptLibraryItem SET active=1, position=:position WHERE id=:id") protected abstract suspend fun activatePrompt(id: String, position: Int)
    @Query("UPDATE JournalSection SET templateId=:templateId WHERE id=:id AND entryId IN (SELECT id FROM JournalEntry WHERE status='DRAFT')") protected abstract suspend fun linkPrompt(id: String, templateId: String)

    @Transaction open suspend fun page(id: String): EntryPage? = entry(id)?.let { EntryPage(it, sections(id), blocks(id), media(id), ink(id)) }
    @Transaction open suspend fun initialize() { if (library().isEmpty()) initialQuestions.forEachIndexed { i, q -> insertPrompt(PromptLibraryItem(question=q, position=i)) } }

    @Transaction open suspend fun create(date: LocalDate, today: LocalDate): EntryPage {
        require(date == today || date == today.minusDays(1)) { "Можно выбрать только сегодня или вчера" }
        val current = entries()
        check(current.none { it.status == "DRAFT" }) { "Уже существует черновик" }
        check(current.none { it.date == date.toString() }) { "За эту дату уже есть запись" }
        initialize()
        val entry = JournalEntry(date=date.toString())
        insertEntry(entry)
        val sections = library().filter { it.active }.mapIndexed { i, q -> JournalSection(entryId=entry.id, templateId=q.id, question=q.question, position=i) }
        insertSections(sections)
        insertBlocks(sections.map { ContentBlock(sectionId=it.id, position=0) })
        return page(entry.id)!!
    }

    @Transaction open suspend fun saveDraft(page: EntryPage) {
        val old = page(page.entry.id) ?: error("Запись не найдена")
        check(old.entry.status == "DRAFT") { "Запечатанная запись неизменяема" }
        require(page.entry.date == old.entry.date && page.entry.status == "DRAFT") { "Дата и статус черновика неизменяемы" }
        require(page.sections.isNotEmpty() && page.sections.all { it.entryId == old.entry.id && it.question.isNotBlank() })
        val sectionIds = page.sections.map { it.id }.toSet()
        require(sectionIds.size == page.sections.size)
        require(page.blocks.all { it.sectionId in sectionIds && it.type in listOf("TEXT", "PHOTO") && it.widthPercent in listOf(50,75,100) })
        require(page.media.all { it.entryId == old.entry.id })
        require(page.ink.all { it.entryId == old.entry.id && it.sectionId in sectionIds })
        old.sections.filter { it.id !in sectionIds }.forEach { check(old.sectionEmpty(it.id)) { "Нельзя убрать непустой вопрос" } }
        check(touch(old.entry.id, System.currentTimeMillis()) == 1)
        deleteSections(old.entry.id) // Cascades only the draft's blocks and ink.
        deleteMedia(old.entry.id)
        insertSections(page.sections)
        insertMedia(page.media)
        insertBlocks(page.blocks)
        insertInk(page.ink)
    }

    private suspend fun applyTemplate(questions: List<Pair<String?, String>>): List<String> {
        require(questions.isNotEmpty() && questions.all { it.second.isNotBlank() })
        val existing = library()
        archiveTemplate()
        val used = mutableSetOf<String>()
        return questions.mapIndexed { index, (id, text) ->
            val found = existing.find { it.id == id && it.question == text && it.id !in used }
            val key = if (found != null) { activatePrompt(found.id, index); found.id }
                else PromptLibraryItem(question=text.trim(), position=index).also { insertPrompt(it) }.id
            used += key
            key
        }
    }
    @Transaction open suspend fun seal(page: EntryPage) {
        check(page.hasContent()) { "Добавьте текст, фотографию или штрих" }
        saveDraft(page)
        val ids = applyTemplate(page.sections.sortedBy { it.position }.map { it.templateId to it.question })
        page.sections.sortedBy { it.position }.forEachIndexed { i, s -> linkPrompt(s.id, ids[i]) }
        check(sealRow(page.entry.id, System.currentTimeMillis()) == 1)
    }
    @Transaction open suspend fun editTemplate(questions: List<Pair<String?, String>>) {
        check(entries().none { it.status == "DRAFT" }) { "Меняйте вопросы внутри черновика" }
        applyTemplate(questions)
    }
}

@Database(entities=[JournalEntry::class, JournalSection::class, ContentBlock::class, MediaAsset::class, InkStroke::class, PromptLibraryItem::class], version=1, exportSchema=true)
abstract class JournalDatabase : RoomDatabase() {
    abstract fun dao(): JournalDao
    companion object {
        fun open(context: Context) = Room.databaseBuilder(context, JournalDatabase::class.java, "journal.db")
            .addCallback(object : Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE UNIQUE INDEX one_draft ON JournalEntry(status) WHERE status='DRAFT'")
                    db.execSQL("CREATE TRIGGER immutable_date BEFORE UPDATE OF date ON JournalEntry BEGIN SELECT RAISE(ABORT, 'Дата неизменяема'); END")
                    db.execSQL("CREATE TRIGGER immutable_sealed BEFORE UPDATE ON JournalEntry WHEN OLD.status='SEALED' BEGIN SELECT RAISE(ABORT, 'Запись запечатана'); END")
                }
            }).build()
    }
}

class JournalRepository(private val context: Context, internal var database: JournalDatabase = JournalDatabase.open(context)) {
    private val mutex = Mutex()
    suspend fun entries() = mutex.withLock { database.dao().initialize(); database.dao().entries() }
    suspend fun library() = mutex.withLock { database.dao().initialize(); database.dao().library() }
    suspend fun page(id: String) = mutex.withLock { database.dao().page(id) }
    suspend fun create(date: LocalDate, today: LocalDate = LocalDate.now()) = mutex.withLock { database.dao().create(date, today) }
    suspend fun save(page: EntryPage) = mutex.withLock { database.dao().saveDraft(page) }
    suspend fun seal(page: EntryPage) = mutex.withLock { database.dao().seal(page); cleanupMedia(page.entry.id) }
    suspend fun deleteDraft(id: String) = mutex.withLock {
        check(database.dao().deleteDraft(id) == 1) { "Можно удалить только черновик" }
        java.io.File(context.filesDir, "media/$id").deleteRecursively()
    }
    suspend fun editTemplate(questions: List<Pair<String?,String>>) = mutex.withLock { database.dao().editTemplate(questions) }
    private suspend fun cleanupMedia(id: String) {
        val keep = database.dao().media(id).flatMap { listOf(it.path, it.thumbnail) }.toSet()
        java.io.File(context.filesDir, "media/$id").listFiles()?.filter { "media/$id/${it.name}" !in keep }?.forEach { it.delete() }
    }
    suspend fun reset(phrase: String) = mutex.withLock {
        require(phrase == "УДАЛИТЬ ВСЁ")
        database.close()
        if(context.getDatabasePath("journal.db").exists() && !context.deleteDatabase("journal.db")) {
            database = JournalDatabase.open(context)
            error("Не удалось удалить базу данных")
        }
        java.io.File(context.filesDir, "media").deleteRecursively()
        context.cacheDir.listFiles()?.filter { it.name.startsWith("journal-export-") }?.forEach { it.delete() }
        database = JournalDatabase.open(context)
        database.dao().initialize()
    }
}
