package com.thejas.visionaireader.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.speech.tts.TextToSpeech
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.thejas.visionaireader.engine.DocStatus
import com.thejas.visionaireader.engine.DocumentDetector
import com.thejas.visionaireader.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.Executors

@Composable
fun CameraScreen(onImageCaptured: (Bitmap) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    BackHandler { onBack() }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) { if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA) }

    if (!hasPermission) {
        Box(Modifier.fillMaxSize().background(Paper), contentAlignment = Alignment.Center) {
            Column(modifier = Modifier.padding(32.dp)) {
                Text("Camera access needed.", fontFamily = InstrumentSerif, fontStyle = FontStyle.Italic, fontSize = 32.sp, color = Ink)
                Spacer(Modifier.height(8.dp))
                Text("We never upload your photos. Reading happens entirely on this device.", style = MaterialTheme.typography.bodyLarge, color = InkSecondary)
                Spacer(Modifier.height(20.dp))
                Box(
                    modifier = Modifier.height(52.dp).clip(RoundedCornerShape(20.dp)).background(Amber).clickable { permissionLauncher.launch(Manifest.permission.CAMERA) }.padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Grant access", style = MaterialTheme.typography.titleMedium, color = Ink) }
            }
        }
        return
    }

    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }
    val captureExecutor = remember { Executors.newSingleThreadExecutor() }
    val tfliteExecutor = remember { Executors.newSingleThreadExecutor() }
    val detector = remember { DocumentDetector(context) }

    val tts = remember { mutableStateOf<TextToSpeech?>(null) }
    val ttsReady = remember { mutableStateOf(false) }
    val lastSpoken = remember { mutableStateOf("") }
    val lastSpokenTime = remember { mutableLongStateOf(0L) }

    var guidanceText by remember { mutableStateOf("Point camera at a document") }
    var perfect by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }
    var autoTriggered by remember { mutableStateOf(false) }
    var captureRequested by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val t = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.value?.language = Locale.US
                ttsReady.value = true
            }
        }
        tts.value = t
        onDispose {
            t.stop(); t.shutdown()
            detector.close()
            tfliteExecutor.shutdown()
            captureExecutor.shutdown()
        }
    }

    fun speakGuidance(msg: String) {
        val now = System.currentTimeMillis()
        if (!ttsReady.value) return
        if (msg != lastSpoken.value || now - lastSpokenTime.longValue > 2000L) {
            val t = tts.value ?: return
            t.playSilentUtterance(180, TextToSpeech.QUEUE_FLUSH, null)
            t.speak(msg, TextToSpeech.QUEUE_ADD, null, "guidance")
            lastSpoken.value = msg
            lastSpokenTime.longValue = now
        }
    }

    fun doCapture() {
        if (captureRequested) return
        captureRequested = true
        imageCapture.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = image.toBitmap().rotate(image.imageInfo.rotationDegrees.toFloat())
                image.close()
                tts.value?.stop()
                onImageCaptured(bitmap)
            }
            override fun onError(e: ImageCaptureException) {
                captureRequested = false
                autoTriggered = false
            }
        })
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val analyzer = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    analyzer.setAnalyzer(tfliteExecutor) { proxy ->
                        if (captureRequested) { proxy.close(); return@setAnalyzer }
                        val bmp = proxy.toBitmap()
                        proxy.close()
                        val status = detector.analyze(bmp)

                        val text = when (status) {
                            DocStatus.NO_DOC            -> "No document detected"
                            DocStatus.MOVE_AWAY         -> "Too close, move back"
                            DocStatus.MOVE_CLOSER       -> "Move closer"
                            DocStatus.SHOW_TOP_LEFT     -> "Move to show top left corner"
                            DocStatus.SHOW_TOP_RIGHT    -> "Move to show top right corner"
                            DocStatus.SHOW_BOTTOM_LEFT  -> "Move to show bottom left corner"
                            DocStatus.SHOW_BOTTOM_RIGHT -> "Move to show bottom right corner"
                            DocStatus.PERFECT           -> "Perfect — hold still"
                        }
                        guidanceText = text
                        perfect = status == DocStatus.PERFECT
                        speakGuidance(text)

                        if (status == DocStatus.PERFECT && !autoTriggered) {
                            autoTriggered = true
                            scope.launch {
                                for (i in 2 downTo 1) { countdown = i; delay(1000) }
                                countdown = 0
                                doCapture()
                            }
                        }
                    }

                    try {
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analyzer)
                    } catch (e: Exception) { e.printStackTrace() }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Top gradient scrim
        Box(modifier = Modifier.fillMaxWidth().height(180.dp).align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent))))
        // Bottom gradient scrim
        Box(modifier = Modifier.fillMaxWidth().height(240.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))))

        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)), CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(20.dp)) }

            Spacer(Modifier.weight(1f))

            Text("READING MODE", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))

            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(44.dp))
        }

        // Guidance pill
        Box(
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(top = 88.dp, start = 24.dp, end = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(28.dp))
                    .background(if (perfect) Amber else Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 22.dp, vertical = 14.dp)
            ) {
                if (countdown > 0) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontFamily = InstrumentSerif, fontStyle = FontStyle.Italic, fontSize = 22.sp)) { append("Capturing in ") }
                            withStyle(SpanStyle(fontFamily = InterTight, fontWeight = FontWeight.Bold, fontSize = 22.sp)) { append("$countdown") }
                        },
                        color = Ink
                    )
                } else {
                    Text(
                        guidanceText,
                        fontFamily = InterTight,
                        fontWeight = FontWeight.Medium,
                        fontSize = 16.sp,
                        color = if (perfect) Ink else Color.White
                    )
                }
            }
        }

        // Capture button — amber when perfect, otherwise outlined white
        val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
            initialValue = 1f,
            targetValue = if (perfect || countdown > 0) 1.12f else 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "pulse"
        )
        Box(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = 56.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(86.dp)
                    .scale(pulse)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(if (countdown > 0 || perfect) Amber else Color.White)
                        .border(BorderStroke(2.dp, Color.White), CircleShape)
                        .clickable(enabled = !captureRequested) { doCapture() }
                )
            }
        }

        // Mono caps caption under capture button
        Text(
            if (perfect) "TAP TO CAPTURE OR HOLD STILL" else "TAP TO CAPTURE",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp)
        )
    }
}

private fun Bitmap.rotate(degrees: Float): Bitmap {
    if (degrees == 0f) return this
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}
