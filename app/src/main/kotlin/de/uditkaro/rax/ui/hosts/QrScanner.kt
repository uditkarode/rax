package de.uditkaro.rax.ui.hosts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import de.uditkaro.rax.R
import de.uditkaro.rax.theme.RaxType
import de.uditkaro.rax.ui.components.IconAction
import de.uditkaro.rax.ui.components.PrimaryButton
import java.util.concurrent.Executors
import kotlinx.coroutines.delay

/**
 * The camera, full screen, until it sees a QR code. [accept] returns false for a code that is not
 * for Rax, which the screen says while it keeps looking. [askCamera] asks Android for the camera.
 */
@Composable
fun QrScanner(accept: (String) -> Boolean, askCamera: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(hasCamera(context)) }
    // Android's prompt pauses the screen, so its answer is read when the screen resumes.
    LifecycleResumeEffect(Unit) {
        allowed = hasCamera(context)
        onPauseOrDispose {}
    }
    LaunchedEffect(Unit) { if (!allowed) askCamera() }

    var wrongCode by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(wrongCode) {
        if (wrongCode != null) {
            delay(3_000)
            wrongCode = null
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (allowed) {
                CameraPreview(onCode = { text -> if (text != wrongCode && !accept(text)) wrongCode = text })
            }
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                IconAction(R.drawable.ic_close, stringResource(R.string.close), onClose, Modifier.padding(8.dp), tint = Color.White)
                Column(
                    Modifier.align(if (allowed) Alignment.BottomCenter else Alignment.Center).fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val message = when {
                        !allowed -> stringResource(R.string.scan_camera_off)
                        wrongCode != null -> stringResource(R.string.scan_not_rex)
                        else -> stringResource(R.string.scan_title)
                    }
                    Text(
                        message,
                        Modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 14.dp, vertical = 10.dp),
                        style = RaxType.body,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                    if (!allowed) PrimaryButton(stringResource(R.string.scan_allow_camera), askCamera, Modifier.padding(top = 16.dp))
                }
            }
        }
    }
}

private fun hasCamera(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/** The back camera's picture, with each frame searched for a QR code. [onCode] runs on the main thread. */
@Composable
private fun CameraPreview(onCode: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val currentOnCode by rememberUpdatedState(onCode)
    val view = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // A SurfaceView, the default, shows nothing in a dialog's window, which sits above the activity's.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    AndroidView({ view }, Modifier.fillMaxSize())

    DisposableEffect(owner) {
        val analyzer = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // A code on a laptop screen is small in the picture, so frames need more pixels than the default.
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build(),
            )
            .build()
        val reader = QrReader()
        analysis.setAnalyzer(analyzer) { image -> reader.read(image)?.let { text -> main.execute { currentOnCode(text) } } }
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var disposed = false
        future.addListener({
            if (disposed) return@addListener
            provider = future.get().also { it.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) }
        }, main)
        onDispose {
            disposed = true
            provider?.unbind(preview, analysis)
            analysis.clearAnalyzer()
            analyzer.shutdown()
        }
    }
}

/** Finds a QR code in camera frames with ZXing. */
internal class QrReader {
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                // Rex draws its code in a terminal, which often has light modules on a dark background.
                DecodeHintType.ALSO_INVERTED to true,
            ),
        )
    }

    // A frame's brightness, kept from frame to frame so that each frame does not allocate its own.
    private var frame = ByteArray(0)

    fun read(image: ImageProxy): String? = image.use {
        val plane = it.planes[0]
        val width = it.width
        val height = it.height
        if (frame.size != width * height) frame = ByteArray(width * height)
        // Rows can end in padding, so each row is copied on its own.
        for (row in 0 until height) {
            plane.buffer.position(row * plane.rowStride)
            plane.buffer.get(frame, row * width, width)
        }
        read(frame, width, height, width)
    }

    /** Reads a frame's brightness, one byte per pixel in rows of [rowStride] bytes. */
    fun read(luminance: ByteArray, width: Int, height: Int, rowStride: Int): String? {
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: ReaderException) {
            null
        } finally {
            reader.reset()
        }
    }
}
