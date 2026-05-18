package com.thejas.visionaireader.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
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
import com.thejas.visionaireader.AgentStage
import com.thejas.visionaireader.AgentState
import com.thejas.visionaireader.engine.AgentAction
import com.thejas.visionaireader.ui.theme.*
import java.util.Locale
import java.util.concurrent.Executors

@Composable
fun AgentScreen(
    state: AgentState,
    onBack: () -> Unit,
    onCapture: (Bitmap) -> Unit,
    onSpeechFinished: () -> Unit,
    onYesNo: (Boolean) -> Unit,
    onMenuQuestion: (String) -> Unit,
    onMenuAnswerSpoken: () -> Unit,
    onScanAgain: () -> Unit
) {
    val context = LocalContext.current

    // System back → return to Home
    BackHandler { onBack() }

    // ── TTS ──────────────────────────────────────────────────────────
    val ttsRef = remember { mutableStateOf<TextToSpeech?>(null) }
    val ttsReady = remember { mutableStateOf(false) }
    val isSpeaking = remember { mutableStateOf(false) }
    val spokenIdRef = remember { mutableStateOf("") }
    val pendingFinishCb = remember { mutableStateOf<(() -> Unit)?>(null) }

    DisposableEffect(Unit) {
        val t = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsRef.value?.language = Locale.US
                ttsReady.value = true
            }
        }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { isSpeaking.value = true }
            override fun onDone(utteranceId: String?) {
                isSpeaking.value = false
                pendingFinishCb.value?.invoke()
                pendingFinishCb.value = null
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { isSpeaking.value = false }
        })
        ttsRef.value = t
        onDispose { t.stop(); t.shutdown() }
    }

    fun speak(text: String, idTag: String, onDone: (() -> Unit)? = null) {
        if (!ttsReady.value || text.isBlank()) {
            onDone?.invoke(); return
        }
        if (spokenIdRef.value == idTag) return
        spokenIdRef.value = idTag
        pendingFinishCb.value = onDone
        val tts = ttsRef.value ?: return
        // Pre-queue 180ms silence to absorb the TTS cold-start clip that swallows first 1-2 words
        tts.playSilentUtterance(180, TextToSpeech.QUEUE_FLUSH, null)
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, idTag)
    }

    // ── Speak "Processing" once when long inference starts ──
    LaunchedEffect(state.stage, ttsReady.value) {
        val isLongProcessing = state.stage in setOf(
            AgentStage.Thinking,
            AgentStage.Acting
        )
        if (isLongProcessing) speak("Processing", "proc_${state.stage.name}")
    }

    // ── Speak agent's response when stage transitions to Speaking ──
    LaunchedEffect(state.stage, state.spokenText, ttsReady.value) {
        when (state.stage) {
            AgentStage.Speaking -> speak(state.spokenText, "agent_speak_${state.spokenText.hashCode()}") {
                onSpeechFinished()
            }
            AgentStage.AwaitingYesNo -> speak(state.spokenText, "agent_ask_${state.spokenText.hashCode()}", null)
            AgentStage.MenuChatSpeaking -> speak(state.menuAnswer, "menu_answer_${state.menuAnswer.hashCode()}") {
                onMenuAnswerSpoken()
            }
            else -> Unit
        }
    }

    // ── Calendar runtime permission ──
    // Request JIT when user confirms a calendar/reminder action.
    val pendingYesAfterPerm = remember { mutableStateOf(false) }
    val calendarPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val ok = granted[Manifest.permission.WRITE_CALENDAR] == true
        if (ok && pendingYesAfterPerm.value) {
            pendingYesAfterPerm.value = false
            onYesNo(true)
        } else if (!ok) {
            pendingYesAfterPerm.value = false
            // Permission denied — treat as "no" so user is unblocked
            onYesNo(false)
        }
    }
    fun handleYesWithCalendarCheck() {
        val needsCalendar = state.proposedAction == AgentAction.CALENDAR ||
                            state.proposedAction == AgentAction.REMINDER
        if (!needsCalendar) { onYesNo(true); return }
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            onYesNo(true)
        } else {
            pendingYesAfterPerm.value = true
            calendarPermLauncher.launch(arrayOf(
                Manifest.permission.WRITE_CALENDAR,
                Manifest.permission.READ_CALENDAR
            ))
        }
    }

    // ── Speech recognition launchers ──
    val yesNoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val raw = matches?.firstOrNull()?.lowercase()?.trim().orEmpty()
            val isYes = raw.startsWith("yes") || raw.contains("sure") || raw.contains("ok") || raw.contains("yeah")
            if (isYes) handleYesWithCalendarCheck() else onYesNo(false)
        }
    }
    val menuQLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val text = matches?.firstOrNull()?.trim().orEmpty()
            if (text.isNotBlank()) onMenuQuestion(text)
        }
    }
    fun launchYesNo() {
        ttsRef.value?.stop()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Yes or no?")
        }
        try { yesNoLauncher.launch(intent) } catch (e: Exception) { e.printStackTrace() }
    }
    fun launchMenuQuestion() {
        ttsRef.value?.stop()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask anything about the menu")
        }
        try { menuQLauncher.launch(intent) } catch (e: Exception) { e.printStackTrace() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .statusBarsPadding()
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { ttsRef.value?.stop(); onBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Ink)
            }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontFamily = InstrumentSerif, fontStyle = FontStyle.Italic, fontSize = 24.sp, letterSpacing = (-0.5).sp)) { append("Personal ") }
                        withStyle(SpanStyle(fontFamily = InterTight, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, letterSpacing = (-0.3).sp)) { append("Agent") }
                    },
                    color = Ink
                )
                Spacer(Modifier.height(2.dp))
                Text(stageCaption(state.stage), style = MaterialTheme.typography.labelSmall, color = if (state.stage == AgentStage.Done) Moss else InkSecondary)
            }
        }

        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Hairline))

        // Tool-call chip row (visible whenever any tool fired)
        if (state.toolCalls.isNotEmpty()) {
            ToolCallChips(state.toolCalls)
        }

        // Body
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (state.stage) {
                AgentStage.Capturing -> CameraView(onCapture = onCapture)
                AgentStage.Ocr -> ProcessingState("Reading the document…")
                AgentStage.Thinking -> ProcessingState("Understanding what to do…")
                AgentStage.Speaking -> SpeakingState(state.spokenText, isSpeaking.value)
                AgentStage.AwaitingYesNo -> AskState(
                    text = state.spokenText,
                    isSpeaking = isSpeaking.value,
                    onYes = { handleYesWithCalendarCheck() },
                    onNo = { onYesNo(false) },
                    onMic = { launchYesNo() }
                )
                AgentStage.Acting -> ProcessingState("Acting…")
                AgentStage.Done -> DoneState(
                    spoken = state.spokenText,
                    result = state.resultMessage,
                    onScanAgain = onScanAgain
                )
                AgentStage.MenuChatIdle -> MenuChatState(
                    spokenText = state.spokenText,
                    lastQ = state.menuQuestion,
                    lastA = state.menuAnswer,
                    isThinking = false,
                    isSpeaking = isSpeaking.value,
                    onAsk = { launchMenuQuestion() },
                    onScanAgain = onScanAgain
                )
                AgentStage.MenuChatThinking -> MenuChatState(
                    spokenText = state.spokenText,
                    lastQ = state.menuQuestion,
                    lastA = state.menuAnswer,
                    isThinking = true,
                    isSpeaking = false,
                    onAsk = { launchMenuQuestion() },
                    onScanAgain = onScanAgain
                )
                AgentStage.MenuChatSpeaking -> MenuChatState(
                    spokenText = state.spokenText,
                    lastQ = state.menuQuestion,
                    lastA = state.menuAnswer,
                    isThinking = false,
                    isSpeaking = isSpeaking.value,
                    onAsk = { launchMenuQuestion() },
                    onScanAgain = onScanAgain
                )
                AgentStage.Error -> ErrorState(state.errorMessage, onScanAgain)
                AgentStage.Idle -> CameraView(onCapture = onCapture)
            }
        }
    }
}

private fun stageCaption(stage: AgentStage): String = when (stage) {
    AgentStage.Capturing -> "POINT AT A DOCUMENT  ·  TAP TO SCAN"
    AgentStage.Ocr -> "READING THE TEXT"
    AgentStage.Thinking -> "UNDERSTANDING"
    AgentStage.Speaking -> "SPEAKING"
    AgentStage.AwaitingYesNo -> "WAITING FOR YOUR ANSWER"
    AgentStage.Acting -> "ACTING"
    AgentStage.Done -> "DONE"
    AgentStage.MenuChatIdle -> "MENU LOADED  ·  TAP TO ASK"
    AgentStage.MenuChatThinking -> "THINKING"
    AgentStage.MenuChatSpeaking -> "SPEAKING"
    AgentStage.Error -> "SOMETHING WENT WRONG"
    else -> ""
}

// ── Tool call chip strip ──
@Composable
private fun ToolCallChips(calls: List<String>) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(calls) { call -> ToolChip(call) }
    }
}

@Composable
private fun ToolChip(name: String) {
    val (icon, label) = when (name) {
        "addCalendarEvent"  -> Pair(Icons.Default.Event, "calendar")
        "setBillReminder"   -> Pair(Icons.Default.Alarm, "reminder")
        "warnUser"          -> Pair(Icons.Default.Warning, "warn")
        "loadMenuContext"   -> Pair(Icons.Default.MenuBook, "menu loaded")
        "menuAnswer"        -> Pair(Icons.Default.AutoAwesome, "menu Q&A")
        "saveDocument"      -> Pair(Icons.Default.Save, "save")
        "ocr-only mode"     -> Pair(Icons.Default.Info, "OCR only")
        else                -> Pair(Icons.Default.Bolt, name)
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Ink)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Amber, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(6.dp))
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Paper, fontSize = 10.sp)
    }
}

// ── Camera view ──
@Composable
private fun CameraView(onCapture: (Bitmap) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> hasPermission = granted }
    LaunchedEffect(Unit) { if (!hasPermission) permLauncher.launch(Manifest.permission.CAMERA) }

    if (!hasPermission) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Camera access needed", color = Ink)
        }
        return
    }

    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val executor = remember { Executors.newSingleThreadExecutor() }
    var isCapturing by remember { mutableStateOf(false) }

    fun capture() {
        if (isCapturing) return
        isCapturing = true
        imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = image.toBitmap().rotate(image.imageInfo.rotationDegrees.toFloat())
                image.close()
                onCapture(bitmap)
            }
            override fun onError(e: ImageCaptureException) {
                isCapturing = false
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
                    try {
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                    } catch (e: Exception) { e.printStackTrace() }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Hint pill
        Box(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Color.Black.copy(alpha = 0.7f))
                .padding(horizontal = 22.dp, vertical = 12.dp)
        ) {
            Text("Hold a bill, invitation, or label", color = Color.White, fontSize = 14.sp)
        }

        // Capture button
        Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier.size(86.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier.size(72.dp).clip(CircleShape).background(Amber)
                        .border(BorderStroke(2.dp, Color.White), CircleShape)
                        .clickable(enabled = !isCapturing) { capture() }
                )
            }
        }
    }
}

// ── Status states ──
@Composable
private fun ProcessingState(label: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontFamily = InstrumentSerif, fontStyle = FontStyle.Italic, fontSize = 44.sp)) { append(label) }
            },
            color = Ink,
            lineHeight = 48.sp
        )
        Spacer(Modifier.height(18.dp))
        Equalizer(active = true, color = Amber, modifier = Modifier.size(width = 40.dp, height = 24.dp))
    }
}

@Composable
private fun SpeakingState(text: String, isSpeaking: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("AGENT SAYS", style = MaterialTheme.typography.labelSmall, color = InkSecondary)
        Spacer(Modifier.height(12.dp))
        Text(
            text,
            fontFamily = InstrumentSerif,
            fontStyle = FontStyle.Italic,
            fontSize = 26.sp,
            lineHeight = 34.sp,
            color = Ink
        )
        if (isSpeaking) {
            Spacer(Modifier.height(20.dp))
            Equalizer(active = true, color = Amber, modifier = Modifier.size(width = 32.dp, height = 18.dp))
        }
    }
}

@Composable
private fun AskState(
    text: String,
    isSpeaking: Boolean,
    onYes: () -> Unit,
    onNo: () -> Unit,
    onMic: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("AGENT ASKS", style = MaterialTheme.typography.labelSmall, color = InkSecondary)
        Spacer(Modifier.height(12.dp))
        Text(
            text,
            fontFamily = InstrumentSerif,
            fontStyle = FontStyle.Italic,
            fontSize = 28.sp,
            lineHeight = 36.sp,
            color = Ink
        )

        Spacer(Modifier.height(24.dp))

        if (isSpeaking) {
            Equalizer(active = true, color = Amber, modifier = Modifier.size(width = 32.dp, height = 18.dp))
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f).height(56.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Amber)
                        .clickable(onClick = onYes),
                    contentAlignment = Alignment.Center
                ) { Text("Yes", style = MaterialTheme.typography.titleMedium, color = Ink) }

                Box(
                    modifier = Modifier
                        .weight(1f).height(56.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .border(BorderStroke(1.5.dp, Hairline), RoundedCornerShape(20.dp))
                        .clickable(onClick = onNo),
                    contentAlignment = Alignment.Center
                ) { Text("No", style = MaterialTheme.typography.titleMedium, color = Ink) }
            }
            Spacer(Modifier.height(14.dp))
            Box(
                modifier = Modifier.fillMaxWidth().height(48.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Ink)
                    .clickable(onClick = onMic),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Mic, contentDescription = null, tint = Paper, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Or speak your answer", color = Paper, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun DoneState(spoken: String, result: String, onScanAgain: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("DONE", style = MaterialTheme.typography.labelSmall, color = Moss)
        Spacer(Modifier.height(12.dp))
        if (result.isNotBlank()) {
            Text(
                result,
                fontFamily = InstrumentSerif,
                fontStyle = FontStyle.Italic,
                fontSize = 28.sp,
                lineHeight = 36.sp,
                color = Ink
            )
        } else if (spoken.isNotBlank()) {
            Text(
                spoken,
                fontFamily = InstrumentSerif,
                fontStyle = FontStyle.Italic,
                fontSize = 24.sp,
                lineHeight = 32.sp,
                color = Ink
            )
        }
        Spacer(Modifier.height(28.dp))
        Box(
            modifier = Modifier.fillMaxWidth().height(56.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Amber)
                .clickable(onClick = onScanAgain),
            contentAlignment = Alignment.Center
        ) { Text("Scan another", style = MaterialTheme.typography.titleMedium, color = Ink) }
    }
}

@Composable
private fun MenuChatState(
    spokenText: String,
    lastQ: String,
    lastA: String,
    isThinking: Boolean,
    isSpeaking: Boolean,
    onAsk: () -> Unit,
    onScanAgain: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Text("MENU LOADED", style = MaterialTheme.typography.labelSmall, color = Moss)
        Spacer(Modifier.height(12.dp))
        Text(
            spokenText,
            fontFamily = InstrumentSerif,
            fontStyle = FontStyle.Italic,
            fontSize = 22.sp,
            lineHeight = 30.sp,
            color = Ink
        )

        Spacer(Modifier.height(24.dp))

        if (lastQ.isNotBlank()) {
            Text("YOU ASKED", style = MaterialTheme.typography.labelSmall, color = InkSecondary)
            Spacer(Modifier.height(6.dp))
            Text(lastQ, style = MaterialTheme.typography.bodyLarge, color = Ink)

            Spacer(Modifier.height(16.dp))

            Text("AGENT ANSWERED", style = MaterialTheme.typography.labelSmall, color = InkSecondary)
            Spacer(Modifier.height(6.dp))
            if (isThinking) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Equalizer(active = true, color = Amber, modifier = Modifier.size(width = 28.dp, height = 16.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Thinking…", color = InkSecondary)
                }
            } else {
                Text(
                    lastA,
                    fontFamily = InstrumentSerif,
                    fontStyle = FontStyle.Italic,
                    fontSize = 22.sp,
                    lineHeight = 30.sp,
                    color = Ink
                )
                if (isSpeaking) {
                    Spacer(Modifier.height(8.dp))
                    Equalizer(active = true, color = Amber, modifier = Modifier.size(width = 28.dp, height = 16.dp))
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // Mic + scan another row
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .weight(1f).height(56.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Amber)
                    .clickable(enabled = !isThinking, onClick = onAsk),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Mic, contentDescription = null, tint = Ink, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Ask about menu", style = MaterialTheme.typography.titleMedium, color = Ink)
                }
            }
            Box(
                modifier = Modifier
                    .height(56.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .border(BorderStroke(1.5.dp, Hairline), RoundedCornerShape(20.dp))
                    .clickable(onClick = onScanAgain)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) { Text("New scan", style = MaterialTheme.typography.titleMedium, color = Ink) }
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("SOMETHING WENT WRONG", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Text(
            message.ifBlank { "Couldn't process the scan." },
            fontFamily = InstrumentSerif,
            fontStyle = FontStyle.Italic,
            fontSize = 24.sp,
            lineHeight = 32.sp,
            color = Ink
        )
        Spacer(Modifier.height(24.dp))
        Box(
            modifier = Modifier.fillMaxWidth().height(56.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Ink)
                .clickable(onClick = onRetry),
            contentAlignment = Alignment.Center
        ) { Text("Try again", style = MaterialTheme.typography.titleMedium, color = Paper) }
    }
}

private fun Bitmap.rotate(degrees: Float): Bitmap {
    if (degrees == 0f) return this
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}
