package com.nsfr.myjournal

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import java.time.LocalDate

/** Runs only against the separate .debug package. Never clears the release diary. */
@RunWith(AndroidJUnit4::class)
class JournalUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val repository get()=(compose.activity.application as JournalApp).repository
    @Before fun reset() {
        runBlocking { repository.reset("УДАЛИТЬ ВСЁ") }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Создать новую запись").fetchSemanticsNodes().isNotEmpty() }
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
}
