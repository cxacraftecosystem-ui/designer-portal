package com.designprototype.workshop.data

import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

/**
 * The Android half of [DwSketchRectify]: a decoded photograph in, a PNG plate out.
 *
 * This is the ONLY file in the feature that knows what a Bitmap is — the same split
 * [DwImageDecode] has from [DwImageQuality], and for the same reason rather than for tidiness. There
 * is no Robolectric in this module (app/build.gradle.kts declares JUnit 4 and nothing else), so
 * anything touching `android.graphics` is by construction code no unit test can reach. Everything
 * worth pinning therefore lives in [DwSketchRectify], and this file is kept small enough to read.
 *
 * ── IT WRITES, AND THAT IS NOT A CONTRADICTION OF docs/MEDIA_PIPELINE.md §5 ───────────────────
 *
 * §5 refuses to RE-ENCODE field media: this is a heritage archive, the original file IS the artifact,
 * and a decode-and-re-save round trip destroys full resolution and strips the EXIF
 * [WorkshopDraftStore.importMedia] captures on purpose. That rule is about the original, and nothing
 * here can reach one: [platePng] takes a [GreyPlane] — arithmetic, with no file behind it — and
 * writes it to a NEW file at a path the caller supplies. There is no path in this file that opens the
 * source photograph, and the panel above attaches the result to a DIFFERENT registry field
 * (`sketch.lineArtFile`) so that `sketch.image` still points at the untouched photograph. A derived
 * plate is a second artifact that cites the first; it is never a corrected version of it.
 *
 * ── WHY THE PLATE IS MADE FROM THE WORKING COPY THE DESIGNER MARKED ON ────────────────────────
 *
 * [greyPlaneOf] reads the very bitmap [DwImageDecode.decodeForDisplay] put on the screen, so the
 * marks and the pixels being resampled are in ONE coordinate system. The alternative — decode the
 * original a second time at full resolution and scale the four marks into it — is a second decode of
 * a 12 MP frame on a 2 GB handset AND a coordinate conversion whose failure mode is a plate cropped
 * to somewhere near the sheet. What it would buy is resolution the plate does not use:
 * [DwSketchRectify.RECTIFY_MAX_EDGE_PX] caps a plate at 1600 px and
 * [DwImageDecode.DISPLAY_EDGE_PX] already delivers 2400, so for any sheet occupying more than about
 * two thirds of the frame the cap binds first and the extra decode changes nothing at all.
 *
 * THE ONE HONEST COST is that [DwImageDecode.decodeForDisplay] decodes RGB_565, so each channel is
 * quantised to 5 or 6 bits — about ±4 luma counts of banding. That is harmless HERE and would not be
 * in [DwImageDecode.measure], where it would change a blur score and make a verdict depend on the
 * handset. Sauvola compares a pixel with the mean of its own neighbourhood: on blank paper the
 * banding raises the local standard deviation by a couple of counts, and the `s/R` term drives the
 * threshold to roughly `0.8 · mean` there regardless, which is far below every paper pixel. On a
 * pencil stroke the contrast being detected is tens of counts. Four counts of banding does not reach
 * either decision.
 */
object DwSketchPlate {

    /**
     * The luma plane of a decoded photograph, read a row at a time.
     *
     * ROW BY ROW AND NOT ONE `getPixels` OVER THE WHOLE FRAME. A 2400x1800 working copy is 4.3M
     * pixels, so a full-frame `IntArray` is 17 MB — allocated on top of the bitmap itself, on a
     * handset that is also running the camera. One row is 9.6 KB and is reused, so the peak is the
     * bitmap plus the plane and nothing else.
     *
     * The conversion goes through [DwImageQuality.greyPlaneFromArgb] rather than three inlined
     * multiplications, because that function's own header requires it: every measurement in this app
     * has to agree on what grey means, and a second Rec.601 written out here would be a second
     * opinion that drifts. The per-row [GreyPlane] it returns is a few kilobytes and dies immediately.
     */
    fun greyPlaneOf(bitmap: Bitmap): GreyPlane? {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 1 || height < 1) return null
        val data = ByteArray(width * height)
        val row = IntArray(width)
        for (y in 0 until height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            val converted = DwImageQuality.greyPlaneFromArgb(row, width, 1)
            System.arraycopy(converted.data, 0, data, y * width, width)
        }
        return GreyPlane(data, width, height)
    }

    /**
     * An opaque ARGB_8888 bitmap of a grey plane, for showing the plate on screen and for encoding it.
     *
     * ONE BITMAP SERVES BOTH. The preview a designer judges the plate by and the bytes that get
     * written must be the same pixels, or the thing they approved is not the thing that was attached —
     * which is a class of defect nobody discovers until the report prints.
     *
     * Returns null rather than throwing on OutOfMemoryError: a 1600x1200 ARGB bitmap is 7.7 MB and
     * this runs on handsets that are sometimes at the edge. A plate that could not be built is a
     * sentence on screen; it is never a crash in the middle of an unsaved stage.
     */
    fun bitmapOf(plane: GreyPlane): Bitmap? {
        if (plane.width < 1 || plane.height < 1) return null
        val bitmap = runCatching {
            Bitmap.createBitmap(plane.width, plane.height, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null
        val row = IntArray(plane.width)
        for (y in 0 until plane.height) {
            val offset = y * plane.width
            for (x in 0 until plane.width) {
                val value = plane.at(offset + x)
                // Opaque grey. Alpha is always 255: a plate with a transparent background prints as
                // whatever the document's page colour happens to be, which for a scanned sheet is a
                // silent change to what the sketch says.
                row[x] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
            }
            bitmap.setPixels(row, 0, plane.width, 0, y, plane.width, 1)
        }
        return bitmap
    }

    /**
     * Write [bitmap] to [file] as a PNG. True when the bytes are on the flash.
     *
     * PNG AND NEVER JPEG, and this is not a preference. Line art is very nearly bilevel, and JPEG's
     * discrete cosine transform reconstructs a hard black-to-white edge as a ringing overshoot — a
     * halo of grey and a fringe of lighter-than-white running along both sides of every stroke. On a
     * plate whose entire content is thin strokes that artifact is a significant fraction of the image
     * and it survives into the printed report. PNG is lossless, and a two-tone image compresses so
     * well that the file is typically smaller than the JPEG would have been.
     *
     * `fd.sync()` before returning, the same discipline [WorkshopDraftStore.importMedia] and
     * `ReportExport.publish` use: without it the bytes are only in the page cache, and the import that
     * follows would copy a file the directory says is 400 KB and the disk says is zeros.
     */
    fun platePng(bitmap: Bitmap, file: File): Boolean = runCatching {
        FileOutputStream(file).use { out ->
            // The quality argument is ignored for PNG — the format is lossless — and is passed as 100
            // only because the signature demands a number.
            val ok = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.flush()
            out.fd.sync()
            ok
        }
    }.getOrDefault(false)
}
