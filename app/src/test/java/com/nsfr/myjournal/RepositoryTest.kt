package com.nsfr.myjournal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=JournalApp::class)
class RepositoryTest {
    private lateinit var context: Context
    private lateinit var repo: JournalRepository
    private val today=LocalDate.of(2026,9,5)
    @Before fun setup() { context=ApplicationProvider.getApplicationContext(); context.deleteDatabase("journal.db"); repo=JournalRepository(context) }
    @After fun teardown() { repo.database.close(); context.deleteDatabase("journal.db") }
    private suspend fun rejects(block: suspend ()->Unit) { try { block(); fail("Ожидался запрет операции") } catch(e: IllegalArgumentException) { } catch(e: IllegalStateException) { } }
    private fun filled(p: EntryPage)=p.copy(blocks=p.blocks.mapIndexed { i,b -> if(i==0) b.copy(text="Первая строка\nВторая строка") else b })

    @Test fun onlyTodayAndYesterday() = runBlocking {
        rejects { repo.create(today.minusDays(2),today) }; rejects { repo.create(today.plusDays(1),today) }
        val yesterday=repo.create(today.minusDays(1),today); assertEquals("2026-09-04",yesterday.entry.date)
        repo.deleteDraft(yesterday.entry.id); assertEquals(today.toString(),repo.create(today,today).entry.date)
    }
    @Test fun atMostOneDraftEvenAcrossConcurrentCalls() = runBlocking {
        val results=coroutineScope { listOf(today,today.minusDays(1)).map { date -> async(Dispatchers.IO) { runCatching { repo.create(date,today) }.isSuccess } }.awaitAll() }
        assertEquals(1,results.count { it }); assertEquals(1,repo.entries().size)
    }
    @Test fun sealedDateIsUnique() = runBlocking {
        val p=filled(repo.create(today,today)); repo.seal(p)
        rejects { repo.create(today,today) }; assertEquals(1,repo.entries().size)
    }
    @Test fun draftDateCannotBeChanged() = runBlocking {
        val p=repo.create(today,today)
        rejects { repo.save(p.copy(entry=p.entry.copy(date=today.minusDays(1).toString()))) }
        assertEquals(today.toString(),repo.page(p.entry.id)!!.entry.date)
    }
    @Test fun draftSurvivesDatabaseAndRepositoryRestart() = runBlocking {
        val p=filled(repo.create(today,today)); repo.save(p); repo.database.close(); repo=JournalRepository(context)
        assertEquals(p.blocks,repo.page(p.entry.id)!!.blocks)
        assertEquals(p.sections,repo.page(p.entry.id)!!.sections)
    }
    @Test fun oldDraftCanStillBeSealed() = runBlocking {
        val p=filled(repo.create(today.minusDays(100),today.minusDays(100))); repo.seal(p)
        assertEquals("SEALED",repo.page(p.entry.id)!!.entry.status)
    }
    @Test fun sealingEvolvesTemplateAndArchivesOldWording() = runBlocking {
        val p=filled(repo.create(today.minusDays(1),today))
        val changed=p.copy(sections=p.sections.mapIndexed { i,s -> if(i==0) s.copy(question="Что меня удивило?") else s })
        repo.save(changed)
        assertEquals(initialQuestions,repo.library().filter { it.active }.map { it.question })
        repo.seal(changed)
        assertTrue(repo.library().any { !it.active && it.question==initialQuestions[0] })
        val next=repo.create(today,today)
        assertEquals("Что меня удивило?",next.sections.first().question)
        val sealed=repo.page(p.entry.id)!!
        assertNotNull(sealed.entry.sealedAt)
        assertEquals("Что меня удивило?",sealed.sections.first().question)
    }
    @Test fun emptySealLeavesEntryAndTemplateUntouched() = runBlocking {
        val p=repo.create(today,today)
        rejects { repo.seal(p.copy(sections=p.sections.map { it.copy(question="Новый вопрос") })) }
        assertEquals("DRAFT",repo.page(p.entry.id)!!.entry.status)
        assertEquals(initialQuestions,repo.library().filter { it.active }.map { it.question })
    }
    @Test fun transactionRollsBackInvalidChildren() = runBlocking {
        val p=filled(repo.create(today,today)); repo.save(p)
        try { repo.save(p.copy(blocks=p.blocks+p.blocks.first())); fail("Duplicate key must roll back") } catch(_: Exception) { }
        assertEquals(p.blocks,repo.page(p.entry.id)!!.blocks)
    }
    @Test fun cannotRemoveNonemptyOrLastQuestion() = runBlocking {
        val p=filled(repo.create(today,today)); repo.save(p)
        rejects { repo.save(p.copy(sections=p.sections.drop(1),blocks=p.blocks.drop(1))) }
        rejects { repo.save(p.copy(sections=emptyList(),blocks=emptyList())) }
        assertEquals(4,repo.page(p.entry.id)!!.sections.size)
    }
    @Test fun removeEmptyQuestionAndReorder() = runBlocking {
        val p=repo.create(today,today)
        val changed=p.copy(sections=p.sections.drop(1).reversed().mapIndexed { i,s -> s.copy(position=i) },blocks=p.blocks.drop(1))
        repo.save(changed); assertEquals(changed.sections,repo.page(p.entry.id)!!.sections)
    }
    @Test fun sealedEntryRejectsAllRepositoryMutations() = runBlocking {
        val p=filled(repo.create(today,today)); repo.seal(p)
        val sealed=repo.page(p.entry.id)!!
        rejects { repo.save(p) }; rejects { repo.seal(p) }; rejects { repo.deleteDraft(p.entry.id) }
        assertEquals(sealed,repo.page(p.entry.id))
    }
    @Test fun templateEditorLockedWhileDraftExists() = runBlocking {
        val p=repo.create(today,today)
        rejects { repo.editTemplate(listOf(null to "Другой вопрос")) }
        repo.deleteDraft(p.entry.id); repo.editTemplate(listOf(null to "Другой вопрос"))
        assertEquals(listOf("Другой вопрос"),repo.library().filter { it.active }.map { it.question })
    }
    @Test fun deletingDraftRemovesMediaAndPreservesTemplate() = runBlocking {
        val p=repo.create(today,today)
        val file=File(context.filesDir,"media/${p.entry.id}/test.jpg").apply { parentFile!!.mkdirs(); writeText("test") }
        repo.deleteDraft(p.entry.id)
        assertFalse(file.exists()); assertEquals(initialQuestions,repo.library().filter { it.active }.map { it.question })
    }
    @Test fun undoRedoPersistsAcrossRestart() = runBlocking {
        val p=repo.create(today,today)
        val s=InkStroke(entryId=p.entry.id,sectionId=p.sections.first().id,serialized=byteArrayOf(1,2,3),position=0,color=1,thickness=4f,anchor=p.blocks.first().id)
        repo.save(p.copy(ink=Magnet.undo(listOf(s)))); repo.database.close(); repo=JournalRepository(context)
        val restored=repo.page(p.entry.id)!!
        assertFalse(restored.ink.single().active)
        repo.save(restored.copy(ink=Magnet.redo(restored.ink)))
        assertTrue(repo.page(p.entry.id)!!.ink.single().active)
    }
    @Test fun resetRequiresExactPhraseAndRestoresDefaults() = runBlocking {
        val p=filled(repo.create(today,today)); repo.seal(p)
        rejects { repo.reset("удалить всё") }; assertEquals(1,repo.entries().size)
        repo.reset("УДАЛИТЬ ВСЁ"); assertTrue(repo.entries().isEmpty())
        assertEquals(initialQuestions,repo.library().filter { it.active }.map { it.question })
    }
}
