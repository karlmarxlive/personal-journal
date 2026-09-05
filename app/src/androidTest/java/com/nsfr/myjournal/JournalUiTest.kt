package com.nsfr.myjournal

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import java.time.LocalDate
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.geometry.Offset

/** Runs only against the separate .debug package. Never clears the release diary. */
@RunWith(AndroidJUnit4::class)
class JournalUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val repository get()=(compose.activity.application as JournalApp).repository
    private val model get()=ViewModelProvider(compose.activity)[JournalModel::class.java]
    private fun inkView(view: android.view.View): InkInputView? {
        if(view is InkInputView) return view
        if(view is android.view.ViewGroup) for(i in 0 until view.childCount) inkView(view.getChildAt(i))?.let { return it }
        return null
    }
    @Before fun reset() {
        compose.runOnIdle { model.clearAll("УДАЛИТЬ ВСЁ") }
        compose.waitUntil(10000) { !model.busy.value && model.entries.value.isEmpty() && compose.onAllNodesWithText("Создать новую запись").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun coldStartCalendarAndEmptyDateDoesNotCreate() {
        compose.onNodeWithTag("calendar").assertIsDisplayed()
        compose.onNodeWithContentDescription("Предыдущий месяц").performClick()
        compose.onAllNodes(hasContentDescription("нет записи",substring=true))[0].performClick()
        Assert.assertTrue(runBlocking { repository.entries().isEmpty() })
    }
    @Test fun createYesterdayQuestionsStayVisibleSwitchModesAndSealReadOnly() {
        compose.onNodeWithText("Создать новую запись").performClick()
        compose.onNodeWithText("Вчера ·",substring=true).performClick()
        compose.waitUntil(10000) { runBlocking { repository.entries().any { it.status=="DRAFT" } } }
        compose.onNodeWithText(initialQuestions[0]).assertIsDisplayed()
        val p=runBlocking { repository.page(repository.entries().single().id)!! }
        compose.onNodeWithTag("answer:${p.blocks.first().id}").performTextInput("Хороший день\nВстретился с друзьями")
        compose.onNodeWithText(initialQuestions[0]).assertIsDisplayed()
        compose.onNodeWithText("Ручка",useUnmergedTree=true).performClick()
        compose.onNodeWithTag("inkSurface").assertIsDisplayed()
        compose.onNodeWithText("Текст",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Сохранить",useUnmergedTree=true).performClick()
        compose.onNodeWithText("После сохранения запись нельзя будет изменить или удалить").assertIsDisplayed()
        Assert.assertEquals("DRAFT",runBlocking { repository.entries().single().status })
        compose.onNodeWithText("Подтвердить сохранение").performClick()
        compose.waitUntil(10000) { runBlocking { repository.entries().single().status=="SEALED" } }
        compose.onNodeWithText("Запечатано · только просмотр").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("Фото").assertDoesNotExist()
        Assert.assertEquals(LocalDate.now().minusDays(1).toString(),runBlocking { repository.entries().single().date })
    }
    @Test fun autosaveSurvivesActivityRecreation() {
        compose.onNodeWithText("Создать новую запись").performClick()
        compose.onNodeWithText("Сегодня ·",substring=true).performClick()
        compose.waitUntil(10000) { runBlocking { repository.entries().isNotEmpty() } }
        val p=runBlocking { repository.page(repository.entries().single().id)!! }
        compose.onNodeWithTag("answer:${p.blocks.first().id}").performTextInput("Сохранить при уходе в фон")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Назад").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Продолжить запись за",substring=true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Продолжить запись за",substring=true).performClick()
        compose.onNodeWithText("Сохранить при уходе в фон").assertIsDisplayed()
    }
    @Test fun drawingColorsWidthsUndoRedoPinchAndCancel() {
        compose.onNodeWithText("Создать новую запись").performClick()
        compose.onNodeWithText("Сегодня ·",substring=true).performClick()
        compose.waitUntil(10000) { model.page.value!=null && !model.busy.value }
        compose.onNodeWithText("Ручка",useUnmergedTree=true).performClick()
        val surface=compose.onNodeWithTag("inkSurface")
        listOf("Основной","Оранжевый","Синий").forEachIndexed { index,color ->
            compose.onNodeWithContentDescription("$color цвет").performClick()
            compose.runOnIdle { model.setPreferences(thickness=if(index==0) 2f else 8f) }
            compose.waitUntil(10000) { model.prefs.value.color==index && model.prefs.value.thickness==if(index==0) 2f else 8f }
            surface.performTouchInput { swipe(Offset(width*.35f,height*.2f+index*60),Offset(width*.65f,height*.25f+index*60),500) }
            compose.waitUntil(10000) { model.page.value!!.ink.size==index+1 }
        }
        Assert.assertEquals(listOf(0,1,2),model.page.value!!.ink.map { it.color })
        Assert.assertEquals(listOf(2f,8f,8f),model.page.value!!.ink.map { it.thickness })
        surface.performTouchInput {
            pinch(Offset(width*.4f,height*.4f),Offset(width*.6f,height*.6f),Offset(width*.2f,height*.2f),Offset(width*.8f,height*.8f),600)
        }
        var beforePan=0f
        compose.runOnIdle {
            val values=FloatArray(9); inkView(compose.activity.window.decorView)!!.pageToView.getValues(values)
            Assert.assertEquals(3f,values[0]/compose.activity.resources.displayMetrics.density,.05f)
            beforePan=values[5]
        }
        surface.performTouchInput { down(0,Offset(width*.4f,height*.4f)); down(1,Offset(width*.6f,height*.6f)); moveBy(Offset(0f,-100f)); up(0); up(1) }
        compose.runOnIdle {
            val values=FloatArray(9); inkView(compose.activity.window.decorView)!!.pageToView.getValues(values)
            Assert.assertTrue("Two fingers must actually pan the page",values[5]<beforePan)
        }
        Assert.assertEquals(3,model.page.value!!.ink.size)
        surface.performTouchInput { down(Offset(width*.5f,height*.5f)); moveTo(Offset(width*.6f,height*.6f)); cancel() }
        Assert.assertEquals(3,model.page.value!!.ink.size)
        compose.onNodeWithText("Отменить").performClick()
        compose.waitUntil(10000) { model.page.value!!.ink.count { it.active }==2 }
        compose.onNodeWithText("Вернуть").performClick()
        compose.waitUntil(10000) { model.page.value!!.ink.count { it.active }==3 }
        compose.onNodeWithText("Текст",useUnmergedTree=true).performClick()
        compose.onNodeWithTag("inkSurface").assertDoesNotExist()
        compose.runOnIdle { model.flushBackground() }
        Assert.assertEquals(3,runBlocking { repository.page(model.page.value!!.entry.id)!!.ink.size })
    }
    @Test fun photoParagraphSplitSizesTemplateAndRealZip() {
        compose.onNodeWithText("Создать новую запись").performClick()
        compose.onNodeWithText("Вчера ·",substring=true).performClick()
        compose.waitUntil(10000) { model.page.value!=null && !model.busy.value }
        val before=model.page.value!!; val id=before.blocks.first().id
        compose.onNodeWithTag("answer:$id").performTextInput("Первый абзац\nВторой абзац")
        val source=java.io.File(compose.activity.cacheDir,"photo-test.png")
        val bitmap=android.graphics.Bitmap.createBitmap(3000,1500,android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0x802563EB.toInt()); source.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        compose.runOnIdle { model.addPhoto(android.net.Uri.fromFile(source),id,4) }
        compose.waitUntil(15000) { !model.busy.value && model.page.value!!.media.size==1 }
        val p=model.page.value!!; val blocks=p.blocks.filter { it.sectionId==p.sections.first().id }.sortedBy { it.position }
        Assert.assertEquals(listOf("TEXT","PHOTO","TEXT"),blocks.map { it.type })
        Assert.assertEquals("Первый абзац\n",blocks[0].text); Assert.assertEquals("Второй абзац",blocks[2].text)
        Assert.assertEquals(2560,p.media.single().width); Assert.assertEquals("image/png",p.media.single().mime)
        listOf(50,75,100).forEach { size -> compose.runOnIdle { model.resizePhoto(blocks[1].id,size) }; compose.runOnIdle { model.flushBackground() }; Assert.assertEquals(size,runBlocking { repository.page(p.entry.id)!!.blocks.first { it.type=="PHOTO" }.widthPercent }) }
        compose.runOnIdle { model.question(p.sections.first().id,"Что меня сегодня удивило?") }
        compose.onNodeWithText("Сохранить",useUnmergedTree=true).performClick(); compose.onNodeWithText("Подтвердить сохранение").performClick()
        compose.waitUntil(10000) { model.page.value!!.entry.status=="SEALED" }
        val sealed=model.page.value!!
        val archive=java.io.ByteArrayOutputStream(); JournalExport(compose.activity).write(listOf(sealed),archive)
        val names=mutableSetOf<String>(); java.util.zip.ZipInputStream(archive.toByteArray().inputStream()).use { zip -> while(true) { val entry=zip.nextEntry ?: break; names+=entry.name; if(entry.name=="journal.pdf") Assert.assertTrue(zip.readBytes().toString(Charsets.ISO_8859_1).startsWith("%PDF-")) } }
        Assert.assertTrue(names.containsAll(listOf("journal.pdf","journal.md"))); Assert.assertEquals(3,names.size)
        val next=runBlocking { repository.create(LocalDate.now()) }
        Assert.assertEquals("Что меня сегодня удивило?",next.sections.first().question)
        Assert.assertTrue(runBlocking { repository.library().any { !it.active && it.question==initialQuestions.first() } })
        source.delete()
    }
    @Test fun realPointerUnderliningCircleStrikeAndFreeArrow() {
        compose.onNodeWithText("Создать новую запись").performClick()
        compose.onNodeWithText("Сегодня ·",substring=true).performClick()
        compose.waitUntil(10000) { model.page.value!=null && !model.busy.value }
        val id=model.page.value!!.blocks.first().id
        val answer=compose.onNodeWithTag("answer:$id")
        answer.performTextInput("Магнитные чернила")
        compose.onNodeWithText("Ручка",useUnmergedTree=true).performClick()
        val surface=compose.onNodeWithTag("inkSurface")
        val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        answer.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout=layouts.single(); val origin=answer.fetchSemanticsNode().boundsInRoot.topLeft-surface.fetchSemanticsNode().boundsInRoot.topLeft
        val first=layout.getBoundingBox(10); val last=layout.getBoundingBox(16)
        val density=compose.activity.resources.displayMetrics.density
        surface.performTouchInput { swipe(origin+Offset(first.left,layout.getLineBaseline(0)+2*density),origin+Offset(last.right,layout.getLineBaseline(0)+2*density),500) }
        compose.waitUntil(10000) { model.page.value!!.ink.size==1 }
        Assert.assertEquals("UNDERLINE",model.page.value!!.ink.last().type)
        surface.performTouchInput { swipe(origin+Offset(first.left,(first.top+first.bottom)/2),origin+Offset(last.right,(first.top+first.bottom)/2),500) }
        compose.waitUntil(10000) { model.page.value!!.ink.size==2 }
        Assert.assertEquals("STRIKE",model.page.value!!.ink.last().type)
        val cx=(first.left+last.right)/2; val cy=(first.top+first.bottom)/2
        val rx=(last.right-first.left)/2+5*density; val ry=(first.bottom-first.top)/2+5*density
        surface.performTouchInput {
            down(origin+Offset(cx+rx,cy))
            for(i in 1..64) { val angle=i*2*Math.PI/64; moveTo(origin+Offset(cx+rx*kotlin.math.cos(angle).toFloat(),cy+ry*kotlin.math.sin(angle).toFloat())) }
            up()
        }
        compose.waitUntil(10000) { model.page.value!!.ink.size==3 }
        Assert.assertEquals("CIRCLE",model.page.value!!.ink.last().type)
        surface.performTouchInput {
            down(Offset(width*.55f,height*.55f)); moveTo(Offset(width*.7f,height*.65f)); moveTo(Offset(width*.62f,height*.64f)); moveTo(Offset(width*.7f,height*.65f)); moveTo(Offset(width*.69f,height*.6f)); up()
        }
        compose.waitUntil(10000) { model.page.value!!.ink.size==4 }
        Assert.assertEquals("FREE",model.page.value!!.ink.last().type)
    }
}
