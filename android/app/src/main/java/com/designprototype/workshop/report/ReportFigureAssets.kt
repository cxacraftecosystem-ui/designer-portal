package com.designprototype.workshop.report

import android.content.Context
import com.designprototype.workshop.R

fun reportBoundaryAssets(context: Context): BoundaryAssetSource {
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

fun installReportBoundaryAssets(context: Context) {
    val app = context.applicationContext
    if (installedFor === app) return
    BoundaryAssets.install(reportBoundaryAssets(app))
    installedFor = app
}
