package com.designprototype.workshop.ui.designworkshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DwModelViewerTest {

    @Test
    fun `a model is known by its extension first, whatever the type says`() {
        assertEquals("glb", dwModelFormatOf("chair.GLB", "application/octet-stream"))
        assertEquals("ply", dwModelFormatOf("scan.ply", null))
        assertEquals("3mf", dwModelFormatOf("print.3mf", ""))
        assertEquals("usdz", dwModelFormatOf("iphone-scan.usdz", null))
        assertNull(dwModelFormatOf("sheet.pdf", "application/pdf"))
        assertNull(dwModelFormatOf(null, null))
    }

    @Test
    fun `a model type is honoured when the name says nothing`() {
        assertEquals("glb", dwModelFormatOf("upload", "model/gltf-binary"))
        assertEquals("stl", dwModelFormatOf(null, "model/stl"))
    }

    @Test
    fun `the formats and their labels are the web's`() {
        assertEquals(listOf("glb", "gltf", "stl", "obj", "ply", "3mf", "fbx", "usdz"), DW_MODEL_FORMATS)
        assertEquals("glTF", dwModelFormatLabel("gltf"))
        assertEquals("3MF", dwModelFormatLabel("3mf"))
    }

    @Test
    fun `the page is opened from the app's own origin with the one model path and the format`() {
        assertEquals(
            "https://appassets.androidplatform.net/assets/model-viewer/index.html?src=%2Fmodel%2Fcurrent&format=stl",
            dwModelViewerPageUrl("stl"),
        )
    }
}
