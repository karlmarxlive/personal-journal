package com.nsfr.myjournal

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
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
    private fun await(condition: ()->Boolean) { compose.waitUntil(10000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); condition() } }
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
