package com.nsfr.myjournal

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorViewportHostTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()

    @Test fun viewportClipsDrawingAcrossBothEdgesAfterPanAndZoom() {
        val transformed=mutableStateOf(false)
        compose.setContent {
            Column(Modifier.fillMaxSize().background(Color.White)) {
                Box(Modifier.fillMaxWidth().height(48.dp).background(Color.White).testTag("header"))
                EditorPageViewport(Modifier.weight(1f)) {
                    // Exercise the production clipping boundary with a host-renderable stroke.
                    // AndroidX Ink's native input/renderer is verified separately on devices.
                    Canvas(Modifier.matchParentSize().graphicsLayer {
                        scaleX=if(transformed.value) 2f else 1f
                        scaleY=if(transformed.value) 3f else 1f
                        translationY=if(transformed.value) -180f else 0f
                    }) {
                        drawLine(Color.Blue,Offset(size.width/2,-size.height),Offset(size.width/2,size.height*2),12f)
                    }
                }
                Box(Modifier.fillMaxWidth().height(48.dp).background(Color.White).testTag("footer"))
            }
        }
        fun verify(name: String) {
            val header=compose.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot
            val footer=compose.onNodeWithTag("footer").fetchSemanticsNode().boundsInRoot
            val viewport=compose.onNodeWithTag("editorViewport").fetchSemanticsNode().boundsInRoot
            compose.runOnIdle {
                val view=compose.activity.window.decorView
                val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                for(rect in listOf(header,footer)) for(y in rect.top.toInt() until rect.bottom.toInt()) {
                    assertEquals("Drawing must stay out of the panels",android.graphics.Color.WHITE,bitmap.getPixel(rect.center.x.toInt(),y))
                }
                assertEquals("Stroke remains visible inside viewport",android.graphics.Color.BLUE,bitmap.getPixel(viewport.center.x.toInt(),viewport.center.y.toInt()))
                File("build/host-previews/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                bitmap.recycle()
            }
        }
        verify("viewport-clipping")
        compose.runOnIdle { transformed.value=true }
        verify("viewport-clipping-pan-zoom")
    }
}
