package com.nsfr.myjournal

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=JournalApp::class,qualifiers="w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorPerformanceHostTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()

    @Test fun replacedPhotoRequestCannotShowOldImageAndFailureKeepsItsSize() {
        val started=CountDownLatch(1); val release=CountDownLatch(1)
        val path=mutableStateOf("old")
        val loader=PhotoLoader { request ->
            if(request.path=="old") { started.countDown(); check(release.await(15,TimeUnit.SECONDS)) }
            if(request.path=="missing") null else Bitmap.createBitmap(20,20,Bitmap.Config.ARGB_8888).apply {
                eraseColor(if(request.path=="old") android.graphics.Color.RED else android.graphics.Color.BLUE)
            }
        }
        compose.setContent { LocalPhoto(path.value,"Test photo",Modifier.size(200.dp).testTag("frame"),loader=loader) }
        try {
            assertTrue(started.await(10,TimeUnit.SECONDS))
            compose.runOnIdle { path.value="new" }
            compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("Test photo").fetchSemanticsNodes().isNotEmpty() }
            release.countDown()
            compose.waitForIdle()
            val bounds=compose.onNodeWithTag("frame").fetchSemanticsNode().boundsInRoot
            compose.runOnIdle {
                val view=compose.activity.window.decorView
                val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(image))
                assertEquals(android.graphics.Color.BLUE,image.getPixel(bounds.center.x.toInt(),bounds.center.y.toInt()))
                image.recycle()
                path.value="missing"
            }
            compose.waitUntil(10000) { compose.onAllNodesWithText("Не удалось загрузить фото").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(bounds,compose.onNodeWithTag("frame").fetchSemanticsNode().boundsInRoot)
        } finally { release.countDown(); loader.clear() }
    }

    @Test fun scrollingReusesGlyphsAndEditingRebuildsOnlyChangedText() {
        lateinit var geometry: PageGeometry
        val answer=mutableStateOf("Длинный ответ. ".repeat(100))
        compose.setContent {
            val density=LocalDensity.current.density
            geometry=remember { PageGeometry(density) }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .onGloballyPositioned { geometry.root=it; geometry.changed() }) {
                repeat(8) { index ->
                    AnchoredText("$index","s",if(index==0) answer.value else "Другой текст. ".repeat(100),false,true,geometry,{}, { _,_ -> })
                }
            }
        }
        compose.waitForIdle()
        lateinit var initial: List<TextGeometry>
        lateinit var builds: Map<String,Int>
        compose.runOnIdle { initial=geometry.measure(); builds=geometry.nodes.mapValues { it.value.glyphBuilds } }
        for(index in listOf(7,3,0,7,0)) compose.onNodeWithTag("answer:$index").performScrollTo()
        compose.runOnIdle {
            assertSame("Scroll must keep the page geometry snapshot",initial,geometry.measure())
            assertEquals(builds,geometry.nodes.mapValues { it.value.glyphBuilds })
            answer.value="Изменённый ответ. ".repeat(120)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(geometry.nodes.getValue("0").glyphBuilds>builds.getValue("0"))
            for(i in 1..7) assertEquals(builds.getValue("$i"),geometry.nodes.getValue("$i").glyphBuilds)
            assertEquals(answer.value,geometry.measure().first().text)
            assertNotEquals(initial[1].bounds.top,geometry.measure()[1].bounds.top)
        }
    }

    @Test fun thirtyPhotosStayWindowedInDraftAndSealedEditor() {
        val app=ApplicationProvider.getApplicationContext<JournalApp>()
        val model=JournalModel(app)
        val entry=JournalEntry(date="2026-09-14")
        val section=JournalSection(entryId=entry.id,question="Длинная запись",position=0)
        val source=Bitmap.createBitmap(1280,640,Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        val assets=(0 until 30).map { PhotoStore(app).saveBitmap(source,entry.id) }
        source.recycle()
        val blocks=assets.flatMapIndexed { i,a -> listOf(
            ContentBlock(id="text-$i",sectionId=section.id,position=i*2,text="Ответ номер $i. ".repeat(20)),
            ContentBlock(sectionId=section.id,position=i*2+1,type="PHOTO",mediaId=a.id)) }
        val page=mutableStateOf(EntryPage(entry,listOf(section),blocks,assets,emptyList()))
        try {
            compose.setContent { EditorScreen(model,page.value,Preferences(),false) }
            for(sealed in listOf(false,true)) {
                compose.runOnIdle { page.value=page.value.copy(entry=entry.copy(status=if(sealed) "SEALED" else "DRAFT")) }
                for(index in listOf(0,15,29,0,29,0)) {
                    compose.onNodeWithTag("answer:text-$index").performScrollTo()
                    compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("Фотография · 100%").fetchSemanticsNodes().isNotEmpty() }
                    compose.waitForIdle()
                    val loaded=compose.onAllNodesWithContentDescription("Фотография · 100%").fetchSemanticsNodes().size
                    assertTrue("Only nearby photos should retain bitmap state: $loaded",loaded in 1..8)
                    assertTrue(SharedPhotos.loader.cachedBytes<=SharedPhotos.loader.budgetBytes)
                }
                if(sealed) {
                    compose.onAllNodesWithContentDescription("Фотография · 100%")[0].performClick()
                    compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("Фотография на весь экран").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithText("Закрыть").performClick()
                } else {
                    val fullWidth=compose.onAllNodesWithContentDescription("Фотография · 100%")[0].fetchSemanticsNode().boundsInRoot.width
                    for(percent in listOf(50,75,100)) {
                        compose.runOnIdle { page.value=page.value.copy(blocks=blocks.map { if(it.type=="PHOTO") it.copy(widthPercent=percent) else it }) }
                        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("Фотография · $percent%").fetchSemanticsNodes().isNotEmpty() }
                        val width=compose.onAllNodesWithContentDescription("Фотография · $percent%")[0].fetchSemanticsNode().boundsInRoot.width
                        assertEquals(fullWidth*percent/100f,width,2f)
                    }
                }
            }
        } finally {
            SharedPhotos.loader.clear()
            assets.forEach { File(app.filesDir,it.path).delete(); File(app.filesDir,it.thumbnail).delete() }
        }
    }
}
