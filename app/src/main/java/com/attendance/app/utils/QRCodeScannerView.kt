package com.attendance.app.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// ─────────────────────────────────────────────
//  QR CODE GENERATOR (ZXing)
// ─────────────────────────────────────────────
object QRCodeGenerator {
    /**
     * Génère un Bitmap QR Code à partir d'un texte
     * @param content Contenu à encoder (qrToken)
     * @param size    Taille en pixels (défaut 512)
     */
    fun generate(content: String, size: Int = 512): Bitmap {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 1
        )
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }
}

// ─────────────────────────────────────────────
//  QR CODE SCANNER VIEW (ML Kit + CameraX)
// ─────────────────────────────────────────────
@Composable
fun QRCodeScannerView(
    modifier: Modifier = Modifier,
    onQRCodeScanned: (String) -> Unit,
    onError: (String) -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    var scannerExecutor: ExecutorService? by remember { mutableStateOf(null) }
    var hasScanned by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        scannerExecutor = Executors.newSingleThreadExecutor()
        onDispose {
            scannerExecutor?.shutdown()
        }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
            startCamera(
                context = ctx,
                lifecycleOwner = lifecycleOwner,
                previewView = previewView,
                executor = scannerExecutor ?: Executors.newSingleThreadExecutor(),
                onQRCodeScanned = { value ->
                    if (!hasScanned) {
                        hasScanned = true
                        onQRCodeScanned(value)
                    }
                },
                onError = onError
            )
            previewView
        },
        modifier = modifier
    )
}

private fun startCamera(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    executor: ExecutorService,
    onQRCodeScanned: (String) -> Unit,
    onError: (String) -> Unit
) {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    cameraProviderFuture.addListener({
        try {
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(android.util.Size(1280, 720))
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(executor, QRCodeAnalyzer(
                        onQRCodeDetected = onQRCodeScanned,
                        onError = onError
                    ))
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalyzer
            )
        } catch (e: Exception) {
            onError("Impossible d'accéder à la caméra : ${e.localizedMessage}")
        }
    }, ContextCompat.getMainExecutor(context))
}

// ─────────────────────────────────────────────
//  ML KIT IMAGE ANALYZER
// ─────────────────────────────────────────────
private class QRCodeAnalyzer(
    private val onQRCodeDetected: (String) -> Unit,
    private val onError: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val scanner = BarcodeScanning.getClient()
    private var isProcessing = false

    @androidx.camera.core.ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        if (isProcessing) {
            imageProxy.close()
            return
        }
        isProcessing = true

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            isProcessing = false
            return
        }

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }
                    ?.rawValue
                    ?.let { onQRCodeDetected(it) }
            }
            .addOnFailureListener { e ->
                onError(e.localizedMessage ?: "Erreur analyse QR")
            }
            .addOnCompleteListener {
                imageProxy.close()
                isProcessing = false
            }
    }
}
