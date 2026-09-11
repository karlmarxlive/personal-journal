package com.nsfr.myjournal

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=JournalApp::class,qualifiers="w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiHostTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var model: JournalModel
    @Before fun setup() {
        val app=ApplicationProvider.getApplicationContext<JournalApp>()
        runBlocking { app.repository.reset("УДАЛИТЬ ВСЁ") }
        runBlocking { app.preferences.edit { it.clear(); it[stringPreferencesKey("theme")]="LIGHT" } }
        model=JournalModel(app)
        compose.setContent { JournalUi(model) }
        compose.waitUntil(10000) { compose.onAllNodesWithText("Создать новую запись").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun preview(name: String) {
        compose.runOnIdle {
            val view=compose.activity.window.decorView
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val target=File("build/host-previews/$name.png").apply { parentFile!!.mkdirs() }
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        }
    }
    private fun captureNode(node: SemanticsNodeInteraction): Bitmap {
        val bounds=node.fetchSemanticsNode().boundsInRoot
        return compose.runOnIdle {
            val view=compose.activity.window.decorView
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val crop=Bitmap.createBitmap(bitmap,bounds.left.toInt(),bounds.top.toInt(),bounds.width.toInt(),bounds.height.toInt())
            if(crop !== bitmap) bitmap.recycle()
            crop
        }
    }
    private fun await(condition: ()->Boolean) { compose.waitUntil(10000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); condition() } }
    private fun createDraft(): EntryPage {
        compose.onNodeWithText("Создать новую запись").performClick()
        compose.onNodeWithText("Сегодня ·",substring=true).performClick()
        await { model.page.value!=null && !model.busy.value }
        return model.page.value!!
    }
    @Test fun answerRequestsSentenceCapitalizationFromKeyboard() {
        val p=createDraft()
        compose.onNodeWithTag("answer:${p.blocks.first().id}").performClick()
        compose.runOnIdle {
            fun editor(view: android.view.View): android.view.View? {
                if(view.onCheckIsTextEditor()) return view
                if(view is android.view.ViewGroup) for(i in 0 until view.childCount) {
                    editor(view.getChildAt(i))?.let { return it }
                }
                return null
            }
            val info=android.view.inputmethod.EditorInfo()
            val connection=editor(compose.activity.window.decorView)?.onCreateInputConnection(info)
            Assert.assertNotNull("Focused answer must expose a keyboard connection",connection)
            Assert.assertTrue(info.inputType and android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0)
            connection?.closeConnection()
        }
    }
    @Test fun photoUsesMainFileAndOnlyFirstAnswerHasPlaceholderAfterRemoval() {
        val p=createDraft()
        val app=ApplicationProvider.getApplicationContext<JournalApp>()
        val source=Bitmap.createBitmap(1280,640,Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        val asset=PhotoStore(app).saveBitmap(source,p.entry.id)
        source.recycle()
        // Distinct thumbnail pixels detect accidental thumbnail rendering, even on a small display.
        val thumbnail=Bitmap.createBitmap(640,320,Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA) }
        File(app.filesDir,asset.thumbnail).outputStream().use { thumbnail.compress(Bitmap.CompressFormat.PNG,100,it) }
        thumbnail.recycle()
        val first=p.blocks.first { it.sectionId==p.sections.first().id }
        val photo=ContentBlock(sectionId=first.sectionId,position=1,type="PHOTO",mediaId=asset.id)
        val after=ContentBlock(sectionId=first.sectionId,position=2)
        compose.runOnIdle { model.change { it.copy(blocks=it.blocks+photo+after,media=it.media+asset) }; model.flushBackground() }
        await { !model.saving.value }
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("Фотография · 100%").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Можно начать с пары слов…",useUnmergedTree=true).assertCountEquals(p.sections.size)
        val pixels=captureNode(compose.onNodeWithContentDescription("Фотография · 100%"))
        Assert.assertEquals(android.graphics.Color.BLUE,pixels.getPixel(pixels.width/2,pixels.height/2))
        compose.onNodeWithContentDescription("Текст").assertIsSelected().assertHasClickAction()
        compose.onNodeWithContentDescription("Ручка").assertIsNotSelected().assertHasClickAction()
        compose.onNodeWithText("Черновик").assertIsDisplayed()
        preview("editor-photo-light")
        compose.runOnIdle { model.setPreferences(theme="DARK") }
        await { model.prefs.value.theme=="DARK" }
        preview("editor-photo-dark")
        compose.onNodeWithContentDescription("Фотография · 100%").performClick()
        compose.onNodeWithText("Удалить фото").performClick()
        await { model.page.value!!.media.isEmpty() && !model.busy.value }
        compose.onAllNodesWithText("Можно начать с пары слов…",useUnmergedTree=true).assertCountEquals(p.sections.size)
        compose.onNodeWithTag("answer:${after.id}").performTextInput("Текст после удалённого фото.")
        await { !model.saving.value }
        Assert.assertEquals("Текст после удалённого фото.",model.page.value!!.blocks.first { it.id==after.id }.text)
        compose.onNodeWithText("Черновик").assertIsDisplayed()
        compose.onNodeWithText("Сохраняется…").assertDoesNotExist()
        compose.onNodeWithText("Черновик · сохранён на устройстве").assertDoesNotExist()
        preview("editor-photo-removed-dark")
    }

    @Test fun calendarCreationQuestionsAutosaveConfirmationAndReadOnly() {
        compose.onNodeWithTag("calendar").assertIsDisplayed(); preview("calendar")
        compose.onNodeWithText("Создать новую запись").performClick()
        compose.onNodeWithText("Вчера ·",substring=true).performClick()
        await { model.page.value!=null && !model.busy.value }
        val p=model.page.value!!
        compose.onNodeWithText(initialQuestions[0]).assertIsDisplayed()
        compose.onNodeWithTag("answer:${p.blocks.first().id}").performTextInput("Сегодня я нашёл время для прогулки.\nИ для себя.")
        compose.onNodeWithText(initialQuestions[0]).assertIsDisplayed()
        await { !model.saving.value }
        Assert.assertTrue(runBlocking { model.repository.page(p.entry.id)!!.blocks.first().text.contains("прогулки") })
        preview("editor")
        compose.onNodeWithText("Сохранить",useUnmergedTree=true).performClick()
        compose.onNodeWithText("После сохранения запись нельзя будет изменить или удалить").assertIsDisplayed()
        Assert.assertEquals("DRAFT",model.page.value!!.entry.status)
        compose.onNodeWithText("Подтвердить сохранение").performClick()
        await { model.page.value?.entry?.status=="SEALED" }
        compose.onNodeWithText("Запечатано · только просмотр").assertIsDisplayed()
        compose.onNodeWithText("Фото").assertDoesNotExist()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }
    @Test fun themeSelectionAndTemplateEditor() {
        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithText("Тёмная").performClick()
        await { model.prefs.value.theme=="DARK" }
        preview("settings-dark")
        compose.onNodeWithText("Текущий шаблон и архив вопросов").performClick()
        compose.onNodeWithText("Текущий шаблон").assertIsDisplayed()
    }
    @Test fun backgroundFlushPersistsLatestInputBeforeDebounce() {
        compose.onNodeWithText("Создать новую запись").performClick()
        compose.onNodeWithText("Сегодня ·",substring=true).performClick()
        await { model.page.value!=null && !model.busy.value }
        val p=model.page.value!!
        compose.onNodeWithTag("answer:${p.blocks.first().id}").performTextInput("Последние слова перед уходом в фон")
        compose.runOnIdle { model.flushBackground() }
        model.repository.database.close()
        val reopened=JournalRepository(ApplicationProvider.getApplicationContext())
        val restored=runBlocking { reopened.page(p.entry.id)!! }
        Assert.assertEquals("Последние слова перед уходом в фон",restored.blocks.first().text)
        reopened.database.close()
    }
}
