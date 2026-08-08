package com.designprototype.workshop.report

import android.content.Context
import com.designprototype.workshop.R

/**
 * The Android end of [BoundaryAssetSource] — the ONLY file in `report/` that knows what a `Context` is
 * besides [ReportExport] itself.
 *
 * [ReportMap] is deliberately free of `android.*`: it is arithmetic over a byte array, and keeping it
 * that way is what lets the identical source compile and run on a bare JVM against the Python module
 * used as an oracle, which is the only way a port of that size can be checked rather than eyeballed.
 * The cost of that is one indirection — the geometry has to be handed in — and this file is it.
 *
 * WHERE EACH ASSET COMES FROM, AND WHY THEY ARE NOT ALL IN ONE PLACE:
 *
 * `india_outline.bin` and `district_borders.bin` are read from `res/raw`, where the map screen already
 * keeps them (see `ui/IndiaOutline.kt` for their provenance). The server reads the SAME
 * `india_outline.bin` — `report_map._asset_dirs` lists `android/app/src/main/res/raw` among the
 * directories it searches — so the coastline the report draws is byte-identical on the two surfaces
 * with nothing shipped twice.
 *
 * `state-borders.txt` (21,189 bytes) and `district-borders.txt` (69,846 bytes) are read from
 * `assets/boundaries/`, and are the only files this feature added to the APK — 91,035 bytes, against
 * the 104,869 of `assets/design-workshop-schema.json` that was already there. `res/raw` holds the same
 * polylines and reading those would have shipped nothing at all, but they are a DIFFERENT QUANTISATION
 * of them — a uint16 grid against the text format's thousandth-of-a-degree deltas — and the server
 * reads the text files, from `frontend/public/boundaries/`. Recovered coordinates differ by up to
 * ~2e-4 degrees, far too little to see and just enough to make a few hundred anti-aliased pixels
 * differ between the .docx the office downloaded and the .docx the designer generated in the field.
 * That is the price of "the same picture by construction" instead of an argument about how small a
 * difference is small enough. The copies are checked byte-for-byte by the parity harness, so a
 * regenerated boundary set that updated only one of them fails there rather than in a report.
 *
 * The `.bin` arms below are therefore a FALLBACK, reached only if an asset is ever dropped from the
 * APK: a map drawn from slightly different numbers is still a good map, and losing the figure would be
 * worse than the hundredth of a pixel.
 *
 * A MISSING ASSET IS NOT AN ERROR. Every path here returns null on failure; [BoundaryAssets] caches
 * the miss, [renderMapPng] returns null, and the writers drop the figure and record a warning exactly
 * as they drop a photograph whose bytes did not arrive. A report that refused to generate because a
 * decorative map was unavailable would be a far worse outcome for the designer waiting on it.
 */
fun reportBoundaryAssets(context: Context): BoundaryAssetSource {
    // The APPLICATION context, never the one that was passed in. This source is installed into a
    // process-lifetime object; holding an Activity there would leak the whole view hierarchy for as
    // long as the app runs, and on a device that rotates during an export it would leak one per turn.
    val app = context.applicationContext
    return BoundaryAssetSource { name ->
        runCatching {
            when (name) {
                "india_outline.bin" -> app.resources.openRawResource(R.raw.india_outline).use { it.readBytes() }
                "district_borders.bin" ->
                    app.resources.openRawResource(R.raw.district_borders).use { it.readBytes() }
                "state_borders.bin" -> app.resources.openRawResource(R.raw.state_borders).use { it.readBytes() }
                else -> app.assets.open("boundaries/$name").use { it.readBytes() }
            }
        }.getOrNull()
    }
}

@Volatile
private var installedFor: Context? = null

/**
 * Point this process's report map at the APK's boundary geometry.
 *
 * Called from both export entry points rather than from `Application.onCreate`, on purpose. Nothing
 * outside a report export needs this — the map screen has its own loader — and installing it here
 * means an export cannot run against an uninstalled source no matter which screen started it, which
 * is the failure that would show up as a report that has a locator map on one device and not on
 * another.
 *
 * THE SECOND CALL MUST DO NOTHING, which is what the guard is for. [BoundaryAssets.install] clears
 * the decode cache — it has to, so a test can repoint it — so exporting the .docx and then the .pdf of
 * the same workshop would otherwise re-decode 11,649 outline points and 6,350 border points between
 * the two, on the phone, inside an export the designer is watching a progress bar for.
 */
fun installReportBoundaryAssets(context: Context) {
    val app = context.applicationContext
    if (installedFor === app) return
    BoundaryAssets.install(reportBoundaryAssets(app))
    installedFor = app
}
