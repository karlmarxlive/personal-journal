package com.nsfr.myjournal

import android.graphics.Bitmap
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoLoaderTest {
    @Test fun decodeMatchesDisplaySizeAndPreservesOriginal() = runBlocking {
        val file=File.createTempFile("journal-photo", ".png")
        try {
            val source=Bitmap.createBitmap(2560,1280,Bitmap.Config.ARGB_8888)
            source.eraseColor(android.graphics.Color.BLUE)
            file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG,100,it) }; source.recycle()
            val hash=sha256(file)
            val loader=PhotoLoader()
            for (width in listOf(512,768,1024)) {
                val request=PhotoRequest(file.path,width,width/2)
                val image=loader.load(request)!!
                assertEquals(width,image.width); assertEquals(width/2,image.height)
                assertEquals(android.graphics.Color.BLUE,image.getPixel(0,0))
                assertSame(image,loader.load(request))
            }
            val full=loader.load(PhotoRequest(file.path,3000,3000))!!
            assertEquals(2560,full.width)
            assertEquals(hash,sha256(file))
            assertNull(loader.load(PhotoRequest(file.path+"-missing",100,100)))
        } finally { file.delete() }
    }

    @Test fun cacheEvictsWithoutRecyclingVisibleBitmaps() = runBlocking {
        var count=0
        val loader=PhotoLoader(800) { count++; Bitmap.createBitmap(10,10,Bitmap.Config.ARGB_8888) }
        val first=loader.load(PhotoRequest("one",10,10))!!
        assertSame(first,loader.load(PhotoRequest("one",10,10)))
        loader.load(PhotoRequest("two",10,10)); loader.load(PhotoRequest("three",10,10))
        assertTrue(loader.cachedBytes<=800); assertFalse(first.isRecycled)
        loader.load(PhotoRequest("one",10,10)); assertEquals(4,count)
        loader.clear(); assertEquals(0,loader.cachedBytes); assertFalse(first.isRecycled)
    }

    @Test fun cancellationDiscardsStaleDecodeAndAtMostTwoJobsDecodeTogether() = runBlocking {
        val entered=CountDownLatch(2); val release=CountDownLatch(1)
        val active=AtomicInteger(); val peak=AtomicInteger(); val count=AtomicInteger()
        val loader=PhotoLoader {
            val n=active.incrementAndGet(); peak.updateAndGet { maxOf(it,n) }; count.incrementAndGet(); entered.countDown()
            check(release.await(10,TimeUnit.SECONDS)); active.decrementAndGet()
            Bitmap.createBitmap(10,10,Bitmap.Config.ARGB_8888)
        }
        val jobs=(0..3).map { async(Dispatchers.Default) { loader.load(PhotoRequest("$it",10,10)) } }
        try {
            assertTrue(entered.await(10,TimeUnit.SECONDS))
            jobs.forEach { it.cancel() }
        } finally { release.countDown() }
        jobs.forEach { it.join() }
        assertEquals(2,peak.get()); assertEquals(2,count.get()); assertEquals(0,loader.cachedBytes)
    }

    @Test fun windowAndResolutionRespectScrollingAndThreeTimesZoom() {
        assertFalse(photoNearViewport(2001f,2100f,1000))
        assertFalse(photoNearViewport(-1100f,-1001f,1000))
        assertTrue(photoNearViewport(-1000f,-900f,1000))
        assertTrue(photoNearViewport(1900f,2001f,1000))
        assertEquals(IntSize(384,256),photoTarget(IntSize(300,200),1f))
        assertEquals(IntSize(1024,640),photoTarget(IntSize(300,200),3f))
    }
}
