package com.nsfr.myjournal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PhotoDeviceTest {
    @Test fun pickerImportRotatesExifStripsMetadataAndCreatesThumbnail() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val source=File(context.cacheDir,"rotated-photo-fixture.jpg")
        val bitmap=Bitmap.createBitmap(3000,1500,Bitmap.Config.RGB_565)
        bitmap.eraseColor(0xFFE76F00.toInt())
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,90,it) }; bitmap.recycle()
        ExifInterface(source.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_MAKE,"Private fixture metadata")
            saveAttributes()
        }
        val asset=PhotoStore(context).import(Uri.fromFile(source),uuid())
        try {
            assertEquals(1280,asset.width); assertEquals(2560,asset.height)
            assertEquals("image/jpeg",asset.mime)
            val output=File(context.filesDir,asset.path)
            assertEquals(sha256(output),asset.sha256)
            val exif=ExifInterface(output.path)
            assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
            // Re-encoded pixels already include rotation; no transform must remain in EXIF.
            val orientation=exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_UNDEFINED)
            assertTrue(orientation==ExifInterface.ORIENTATION_UNDEFINED || orientation==ExifInterface.ORIENTATION_NORMAL)
            BitmapFactory.decodeFile(File(context.filesDir,asset.thumbnail).path).useBitmap { thumb -> assertEquals(320,thumb.width); assertEquals(640,thumb.height) }
        } finally { source.delete(); File(context.filesDir,asset.path).delete(); File(context.filesDir,asset.thumbnail).delete() }
    }
}
