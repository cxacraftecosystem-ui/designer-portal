package com.designprototype.workshop.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

interface IdentityCardRecognizer {

    suspend fun scan(context: Context, source: Uri): List<IdentityCardText.RecognisedLine>
}

object MlKitIdentityCardRecognizer : IdentityCardRecognizer {

    private const val MAX_EDGE_PX = 2200

    override suspend fun scan(
        context: Context,
        source: Uri,
    ): List<IdentityCardText.RecognisedLine> = withContext(Dispatchers.IO) {
        val bitmap = decodeBounded(context, source)
            ?: throw IllegalStateException("Couldn't open that photo.")
        val image = InputImage.fromBitmap(bitmap, rotationDegrees(context, source))
        val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val text = awaitText(client, image)
            text.textBlocks.flatMap { block -> block.lines }.map { line ->
                val box = line.boundingBox
                IdentityCardText.RecognisedLine(
                    text = line.text,
                    top = box?.top ?: 0,
                    bottom = box?.bottom ?: 0,
                )
            }
        } finally {
            runCatching { client.close() }
            runCatching { bitmap.recycle() }
        }
    }

    private suspend fun awaitText(
        client: com.google.mlkit.vision.text.TextRecognizer,
        image: InputImage,
    ): com.google.mlkit.vision.text.Text = suspendCancellableCoroutine { continuation ->
        client.process(image)
            .addOnSuccessListener { result -> if (continuation.isActive) continuation.resume(result) }
            .addOnFailureListener { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }
    }

    private fun decodeBounded(context: Context, source: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val header = context.contentResolver.openInputStream(source) ?: return null
        header.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE_PX) sample *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    private fun rotationDegrees(context: Context, source: Uri): Int = runCatching {
        context.contentResolver.openInputStream(source)?.use { stream ->
            when (
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
    }.getOrDefault(0)
}
