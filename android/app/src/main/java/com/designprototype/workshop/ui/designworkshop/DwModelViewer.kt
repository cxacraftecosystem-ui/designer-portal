package com.designprototype.workshop.ui.designworkshop

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.net.URLEncoder

/**
 * A stored 3D model, turned on the handset — THE WEB'S OWN VIEWER, in a WebView.
 *
 * `frontend/lib/modelViewerCore.ts` draws a model on the web; `frontend/scripts/
 * build-android-model-viewer.mjs` bundles that same module with three.js into
 * `assets/model-viewer/viewer.js`, and this composable hosts it. One viewer, two clients: a format
 * that draws on a laptop draws here, with the same loaders, the same framing and the same refusals.
 *
 * NOTHING IS DOWNLOADED UNTIL SOMEBODY ASKS, as on the web: the first state is a button. The model is
 * fetched into the document-preview cache by the caller's [fetch] (the same cache a PDF's first page
 * is drawn from), then served to the page by [WebViewAssetLoader] from an https origin inside the
 * app — so the page reads one local file and nothing in the WebView touches the network.
 */

/** The formats this viewer draws. `frontend/lib/modelFormats.ts#MODEL_FORMATS`, ported in order. */
internal val DW_MODEL_FORMATS: List<String> = listOf("glb", "gltf", "stl", "obj", "ply", "3mf", "fbx", "usdz")

/** `modelFormats.ts#MODEL_MIME_TYPES`, ported. */
private val DW_MODEL_MIME_TYPES: Map<String, String> = mapOf(
    "model/gltf-binary" to "glb",
    "model/gltf+json" to "gltf",
    "model/stl" to "stl",
    "model/x.stl-binary" to "stl",
    "model/x.stl-ascii" to "stl",
    "application/sla" to "stl",
    "model/obj" to "obj",
    "model/3mf" to "3mf",
    "application/vnd.ms-package.3dmanufacturing-3dmodel+xml" to "3mf",
    "model/vnd.usdz+zip" to "usdz",
    "model/x-ply" to "ply",
)

/** The format of a stored file, or null when it is not a 3D model. `modelFormatOf`, ported. PURE. */
internal fun dwModelFormatOf(name: String?, mimeType: String?): String? {
    val lower = name?.trim()?.lowercase().orEmpty()
    val dot = lower.lastIndexOf('.')
    if (dot >= 0) {
        val ext = lower.substring(dot + 1)
        if (ext in DW_MODEL_FORMATS) return ext
    }
    return DW_MODEL_MIME_TYPES[mimeType?.trim()?.lowercase().orEmpty()]
}

/** "GLB", "glTF", "3MF". `modelFormatLabel`, ported. */
internal fun dwModelFormatLabel(format: String): String = if (format == "gltf") "glTF" else format.uppercase()

/** The in-app origin the viewer page and the model are served from. */
internal const val DW_MODEL_VIEWER_ORIGIN = "https://appassets.androidplatform.net"

/** The one path the page may read the model from; the handler below answers nothing else. */
internal const val DW_MODEL_VIEWER_MODEL_PATH = "/model/current"

/** The page's address for one format. PURE, so the query the page reads is pinned by a test. */
internal fun dwModelViewerPageUrl(format: String): String =
    "$DW_MODEL_VIEWER_ORIGIN/assets/model-viewer/index.html?src=" +
        URLEncoder.encode(DW_MODEL_VIEWER_MODEL_PATH, "UTF-8") + "&format=" + URLEncoder.encode(format, "UTF-8")

private sealed interface ModelPhase {
    data object Idle : ModelPhase
    data object Fetching : ModelPhase
    data class Showing(val file: File) : ModelPhase
    data class Failed(val message: String) : ModelPhase
}

/** The bridge the page reports through. Called on a WebView thread, so it posts back to the view. */
private class ModelViewerHost(private val view: WebView, private val onStatus: (String, String) -> Unit) {
    @JavascriptInterface
    fun onStatus(state: String, message: String) {
        view.post { onStatus(state, message) }
    }
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
private fun modelWebView(context: Context, model: File, format: String, onStatus: (String, String) -> Unit): WebView {
    val loader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .addPathHandler("/model/") { path ->
            // ONLY the one model this card is showing. Any other path under /model/ answers nothing.
            if ("/model/$path" == DW_MODEL_VIEWER_MODEL_PATH && model.exists()) {
                WebResourceResponse("application/octet-stream", null, FileInputStream(model))
            } else {
                null
            }
        }
        .build()
    return WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.domStorageEnabled = false
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            // The page never navigates; anything that tries is refused rather than opened in here.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
        }
        addJavascriptInterface(ModelViewerHost(this, onStatus), "DwModelViewerHost")
        // A drag on the model turns it rather than scrolling the stage underneath.
        setOnTouchListener { touched, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) touched.parent?.requestDisallowInterceptTouchEvent(true)
            false
        }
        loadUrl(dwModelViewerPageUrl(format))
    }
}

/**
 * The viewer card.
 *
 * @param format one of [DW_MODEL_FORMATS].
 * @param localFile the model's bytes on this handset, when it was just picked here.
 * @param fetch downloads the stored model and answers the local copy, or null when it could not.
 */
@Composable
internal fun DwModelViewer(
    format: String,
    displayName: String,
    localFile: File?,
    fetch: (suspend () -> File?)?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember(format, localFile) { mutableStateOf<ModelPhase>(ModelPhase.Idle) }
    var pageState by remember(format, localFile) { mutableStateOf("loading") }
    var pageMessage by remember(format, localFile) { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }

    fun show() {
        phase = ModelPhase.Fetching
        scope.launch {
            val file = localFile ?: fetch?.invoke()
            phase = if (file == null) {
                ModelPhase.Failed("No connection, so the model could not be fetched to show here. Try again when there is signal.")
            } else {
                pageState = "loading"
                pageMessage = ""
                ModelPhase.Showing(file)
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 320.dp)
                .background(MaterialTheme.field.surface50, RoundedCornerShape(10.dp)),
        ) {
            when (val shown = phase) {
                is ModelPhase.Idle -> Button(onClick = { show() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.ViewInAr, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("Show in 3D · ${dwModelFormatLabel(format)}", modifier = Modifier.padding(start = 6.dp))
                }
                is ModelPhase.Fetching -> CircularProgressIndicator(modifier = Modifier.size(22.dp))
                is ModelPhase.Failed -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(16.dp),
                ) {
                    Text(shown.message, color = MaterialTheme.field.muted, fontSize = 12.sp)
                    OutlinedButton(onClick = { show() }) { Text("Try again") }
                }
                is ModelPhase.Showing -> {
                    AndroidView(
                        factory = { viewContext ->
                            modelWebView(viewContext, shown.file, format) { state, message ->
                                pageState = state
                                pageMessage = message
                            }.also { webView = it }
                        },
                        onRelease = { released ->
                            if (webView === released) webView = null
                            released.destroy()
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp).fillMaxSize(),
                    )
                    if (pageState == "loading") CircularProgressIndicator(modifier = Modifier.size(22.dp))
                }
            }
        }
        if (phase is ModelPhase.Showing) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (pageState == "failed") pageMessage
                    else "$displayName — drag to turn it, pinch to zoom, two fingers to move it.",
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (pageState == "ready") {
                    OutlinedButton(onClick = { webView?.evaluateJavascript("window.dwResetModelView && dwResetModelView()", null) }) {
                        Text("Reset view", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
