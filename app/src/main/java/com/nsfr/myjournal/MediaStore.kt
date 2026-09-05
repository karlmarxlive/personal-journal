package com.nsfr.myjournal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import kotlin.math.roundToInt

class PhotoStore(private val context: Context) {
    fun import(uri: Uri, entryId: String): MediaAsset {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = (2560f/maxOf(info.size.width,info.size.height)).coerceAtMost(1f)
            decoder.setTargetSize((info.size.width*scale).roundToInt().coerceAtLeast(1),(info.size.height*scale).roundToInt().coerceAtLeast(1))
        } // ImageDecoder applies EXIF orientation before returning pixels.
        return bitmap.useBitmap { saveBitmap(it,entryId) }
    }
    internal fun saveBitmap(bitmap: Bitmap, entryId: String): MediaAsset {
        require(entryId == java.util.UUID.fromString(entryId).toString())
        val id=uuid(); val alpha=bitmap.hasAlpha()
        val ext=if(alpha) "png" else "jpg"
        val directory=File(context.filesDir,"media/$entryId").apply { mkdirs() }
        val output=File(directory,"$id.$ext"); val thumb=File(directory,"$id-thumb.$ext")
        try {
            val factor=(2560f/maxOf(bitmap.width,bitmap.height)).coerceAtMost(1f)
            val resized=Bitmap.createScaledBitmap(bitmap,(bitmap.width*factor).roundToInt().coerceAtLeast(1),(bitmap.height*factor).roundToInt().coerceAtLeast(1),true)
            val format=if(alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            output.outputStream().use { check(resized.compress(format,90,it)) }
            val scale=(640f/maxOf(resized.width,resized.height)).coerceAtMost(1f)
            val thumbnail=Bitmap.createScaledBitmap(resized,(resized.width*scale).roundToInt().coerceAtLeast(1),(resized.height*scale).roundToInt().coerceAtLeast(1),true)
            thumb.outputStream().use { check(thumbnail.compress(format,90,it)) }
            val result=MediaAsset(id,entryId,"media/$entryId/${output.name}",if(alpha) "image/png" else "image/jpeg",resized.width,resized.height,sha256(output),"media/$entryId/${thumb.name}")
            if(thumbnail !== resized && thumbnail !== bitmap) thumbnail.recycle()
            if(resized !== bitmap) resized.recycle()
            return result
        } catch(e: Exception) { output.delete(); thumb.delete(); throw e }
    }
}
inline fun <T> Bitmap.useBitmap(block: (Bitmap)->T): T = try { block(this) } finally { recycle() }
fun sha256(file: File): String {
    val digest=MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input -> val buf=ByteArray(65536); while(true) { val n=input.read(buf); if(n<0) break; digest.update(buf,0,n) } }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
