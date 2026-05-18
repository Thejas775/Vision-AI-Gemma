package com.thejas.visionaireader.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.thejas.visionaireader.FeatureState
import com.thejas.visionaireader.InferenceState
import com.thejas.visionaireader.ui.theme.*
import java.util.Locale

@Composable
fun ReadingScreen(
    inferenceState: InferenceState,
    htmlContent: String,
    rawOcrText: String,
    errorMessage: String,
    featureState: FeatureState,
    featureResult: String,
    onBack: () -> Unit,
    onSummarize: () -> Unit,
    onTranslate: (String) -> Unit,
    onAskQuestion: (String) -> Unit,
    onSaveTxt: () -> Boolean,
    onSavePdf: () -> Boolean,
    onClearFeature: () -> Unit
) {
    val context = LocalContext.current

    BackHandler { onBack() }

    val ttsRef = remember { mutableStateOf<TextToSpeech?>(null) }
    val ttsReady = remember { mutableStateOf(false) }
    val isSpeaking = remember { mutableStateOf(false) }
    val lineIndex = remember { mutableIntStateOf(0) }
    val mode = remember { mutableStateOf("content") }
    val webViewRef = remember { mutableStateOf<android.webkit.WebView?>(null) }
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

    val lines = remember(htmlContent) {
        if (htmlContent.isBlank()) emptyList()
        else android.text.Html.fromHtml(htmlContent, android.text.Html.FROM_HTML_MODE_LEGACY)
            .toString().lines().filter { it.isNotBlank() }
    }

    // Per-paragraph word ranges: list of (charStart, charEnd) for each word in each paragraph.
    // TTS onRangeStart returns char positions inside the spoken paragraph; we map to word index.
    val paragraphWords: List<List<IntRange>> = remember(lines) {
        lines.map { line ->
            "\\S+".toRegex().findAll(line).map { it.range }.toList()
        }
    }

    // HTML rebuilt with each word wrapped in a span we can highlight from JS.
    val highlightableHtml = remember(lines) {
        buildString {
            append("<html><head><meta name='viewport' content='width=device-width, initial-scale=1'>")
            append("<style>")
            append("body{font-family:Georgia,'Times New Roman',serif;font-size:20px;line-height:1.75;color:#0E0E0C;padding:24px;margin:0;background:transparent;}")
            append("p{margin:16px 0;}")
            append(".w{transition:background-color 0.12s ease;border-radius:3px;padding:0 2px;}")
            append(".w.a{background:#FFD27A;color:#0E0E0C;}")
            append("</style></head><body>")
            lines.forEachIndexed { pIdx, line ->
                val words = paragraphWords[pIdx]
                append("<p id='p$pIdx'>")
                words.forEachIndexed { wIdx, range ->
                    val word = line.substring(range.first, range.last + 1)
                    append("<span class='w' id='p${pIdx}w${wIdx}'>")
                    append(escapeHtml(word))
                    append("</span>")
                    if (wIdx < words.size - 1) append(" ")
                }
                append("</p>")
            }
            append("<script>")
            append("var __cur=null;")
            append("function hl(p,w){")
            append("  if(__cur){__cur.classList.remove('a');}")
            append("  var el=document.getElementById('p'+p+'w'+w);")
            append("  if(el){el.classList.add('a');el.scrollIntoView({behavior:'smooth',block:'center'});__cur=el;}")
            append("}")
            append("</script></body></html>")
        }
    }

    fun speakLine(tts: TextToSpeech, index: Int) {
        if (index < lines.size) {
            tts.playSilentUtterance(180, TextToSpeech.QUEUE_FLUSH, null)
            tts.speak(lines[index], TextToSpeech.QUEUE_ADD, null, "para_$index")
        }
    }

    DisposableEffect(Unit) {
        val t = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsRef.value?.language = Locale.US
                ttsReady.value = true
            }
        }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (mode.value == "content" && isSpeaking.value && lineIndex.intValue < lines.lastIndex) {
                    lineIndex.intValue++
                    ttsRef.value?.let { speakLine(it, lineIndex.intValue) }
                } else {
                    isSpeaking.value = false
                }
            }
            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                if (mode.value != "content") return
                val pIdx = utteranceId?.removePrefix("para_")?.toIntOrNull() ?: return
                val words = paragraphWords.getOrNull(pIdx) ?: return
                val wIdx = words.indexOfFirst { start in it.first..(it.last + 1) }
                if (wIdx >= 0) {
                    mainHandler.post {
                        webViewRef.value?.evaluateJavascript("hl($pIdx,$wIdx)", null)
                    }
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { isSpeaking.value = false }
        })
        ttsRef.value = t
        onDispose { t.stop(); t.shutdown() }
    }

    LaunchedEffect(inferenceState, ttsReady.value) {
        if (inferenceState == InferenceState.Done && ttsReady.value && lines.isNotEmpty()) {
            mode.value = "content"
            lineIndex.intValue = 0
            isSpeaking.value = true
            ttsRef.value?.let { speakLine(it, 0) }
        }
    }

    LaunchedEffect(featureState, featureResult) {
        if (featureState == FeatureState.Done && featureResult.isNotBlank() && ttsReady.value) {
            mode.value = "feature"
            isSpeaking.value = false
            val plain = android.text.Html.fromHtml(featureResult, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
            val tts = ttsRef.value ?: return@LaunchedEffect
            tts.playSilentUtterance(180, TextToSpeech.QUEUE_FLUSH, null)
            tts.speak(plain, TextToSpeech.QUEUE_ADD, null, "feature_${System.currentTimeMillis()}")
        }
    }

    var showTranslateDialog by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf("") }

    val askLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val text = matches?.firstOrNull()?.trim() ?: ""
            if (text.isNotBlank()) {
                ttsRef.value?.stop()
                isSpeaking.value = false
                onAskQuestion(text)
            }
        }
    }

    fun launchAskAi() {
        ttsRef.value?.stop()
        isSpeaking.value = false
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask anything about this document")
        }
        try { askLauncher.launch(intent) } catch (e: Exception) { e.printStackTrace() }
    }

    if (featureState == FeatureState.Loading ||
        (featureState == FeatureState.Done && featureResult.isNotBlank()) ||
        featureState == FeatureState.Error
    ) {
        FeatureResultSheet(
            state = featureState,
            result = featureResult,
            isSpeaking = isSpeaking.value && mode.value == "feature",
            onReplay = {
                val plain = android.text.Html.fromHtml(featureResult, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
                mode.value = "feature"
                ttsRef.value?.let { t ->
                    t.playSilentUtterance(180, TextToSpeech.QUEUE_FLUSH, null)
                    t.speak(plain, TextToSpeech.QUEUE_ADD, null, "feature_replay_${System.currentTimeMillis()}")
                }
            },
            onStopSpeaking = { ttsRef.value?.stop(); isSpeaking.value = false },
            onDismiss = { ttsRef.value?.stop(); isSpeaking.value = false; onClearFeature() }
        )
    }

    if (showTranslateDialog) {
        TranslateDialog(
            onDismiss = { showTranslateDialog = false },
            onTranslate = { lang -> showTranslateDialog = false; onTranslate(lang) }
        )
    }

    if (showSaveDialog) {
        SaveDialog(
            message = saveMessage,
            onTxt = { saveMessage = if (onSaveTxt()) "Saved as TXT to Documents/VisionAIReader/" else "Save failed" },
            onPdf = { saveMessage = if (onSavePdf()) "Saved as PDF to Documents/VisionAIReader/" else "Save failed" },
            onDismiss = { showSaveDialog = false; saveMessage = "" }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // ── Header ──
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { ttsRef.value?.stop(); isSpeaking.value = false; onBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Ink)
            }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontFamily = InstrumentSerif, fontStyle = FontStyle.Italic, fontSize = 24.sp, letterSpacing = (-0.5).sp)) { append("Reading") }
                    },
                    color = Ink
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    when {
                        isSpeaking.value && mode.value == "content" -> "PLAYING  ·  ${lineIndex.intValue + 1} OF ${lines.size}"
                        inferenceState == InferenceState.Running -> "ANALYZING…"
                        else -> "ON-DEVICE  ·  OFFLINE"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSpeaking.value) Amber else InkSecondary
                )
            }
        }

        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Hairline))

        // ── Content ──
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (inferenceState) {
                InferenceState.Running -> RunningState()
                InferenceState.Done -> {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                settings.javaScriptEnabled = true
                                webViewRef.value = this
                            }
                        },
                        update = {
                            it.loadDataWithBaseURL(null, highlightableHtml, "text/html", "UTF-8", null)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                InferenceState.Error -> {
                    Column(
                        Modifier.fillMaxSize().padding(28.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(fontFamily = InstrumentSerif, fontStyle = FontStyle.Italic, fontSize = 44.sp)) { append("Couldn't ") }
                                withStyle(SpanStyle(fontFamily = InterTight, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, letterSpacing = (-0.6).sp)) { append("read\nthat.") }
                            },
                            color = Ink,
                            lineHeight = 46.sp
                        )
                        if (errorMessage.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text(errorMessage, style = MaterialTheme.typography.bodyMedium, color = InkSecondary)
                        }
                    }
                }
                else -> Unit
            }
        }

        // ── Controls ──
        if (inferenceState == InferenceState.Done) {
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Hairline))
            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconChip(Icons.Default.SkipPrevious, "Previous", enabled = lines.isNotEmpty() && lineIndex.intValue > 0) {
                    if (lineIndex.intValue > 0) {
                        mode.value = "content"
                        lineIndex.intValue--
                        isSpeaking.value = true
                        ttsRef.value?.let { speakLine(it, lineIndex.intValue) }
                    }
                }

                PlayButton(
                    isPlaying = isSpeaking.value && mode.value == "content",
                    enabled = lines.isNotEmpty()
                ) {
                    val tts = ttsRef.value ?: return@PlayButton
                    if (isSpeaking.value) {
                        tts.stop(); isSpeaking.value = false
                    } else {
                        mode.value = "content"
                        isSpeaking.value = true
                        speakLine(tts, lineIndex.intValue)
                    }
                }

                IconChip(Icons.Default.SkipNext, "Next", enabled = lines.isNotEmpty() && lineIndex.intValue < lines.lastIndex) {
                    if (lineIndex.intValue < lines.lastIndex) {
                        mode.value = "content"
                        lineIndex.intValue++
                        isSpeaking.value = true
                        ttsRef.value?.let { speakLine(it, lineIndex.intValue) }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // Feature row — outlined pills with mono caption
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                FeaturePill(Icons.Default.AutoAwesome, "ASK") { launchAskAi() }
                FeaturePill(Icons.Default.Summarize, "SUMMARY") { onSummarize() }
                FeaturePill(Icons.Default.Translate, "TRANSLATE") { showTranslateDialog = true }
                FeaturePill(Icons.Default.SaveAlt, "SAVE") { showSaveDialog = true }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RunningState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontFamily = InstrumentSerif, fontStyle = FontStyle.Italic, fontSize = 44.sp)) { append("Reading ") }
                withStyle(SpanStyle(fontFamily = InterTight, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, letterSpacing = (-0.6).sp)) { append("your\ndocument.") }
            },
            color = Ink,
            lineHeight = 48.sp
        )
        Spacer(Modifier.height(18.dp))
        Text("Working fully on-device — no network call.", style = MaterialTheme.typography.bodyLarge, color = InkSecondary)
        Spacer(Modifier.height(20.dp))
        Equalizer(active = true, color = Amber, modifier = Modifier.size(width = 40.dp, height = 24.dp))
    }
}

@Composable
private fun PlayButton(isPlaying: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "play").animateFloat(
        initialValue = 1f,
        targetValue = if (isPlaying) 1.05f else 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "play-scale"
    )
    Box(
        modifier = Modifier
            .size(72.dp)
            .scale(if (enabled) pulse else 1f)
            .clip(CircleShape)
            .background(if (enabled) Amber else PaperDim)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            tint = Ink,
            modifier = Modifier.size(32.dp)
        )
    }
}

@Composable
private fun IconChip(icon: ImageVector, contentDescription: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .border(BorderStroke(1.dp, if (enabled) Hairline else Hairline.copy(alpha = 0.3f)), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = if (enabled) Ink else InkSecondary.copy(alpha = 0.4f), modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun FeaturePill(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .border(BorderStroke(1.dp, Hairline), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = Ink, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = InkSecondary)
    }
}

@Composable
private fun FeatureResultSheet(
    state: FeatureState,
    result: String,
    isSpeaking: Boolean,
    onReplay: () -> Unit,
    onStopSpeaking: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Paper)
                .border(BorderStroke(1.dp, Hairline), RoundedCornerShape(24.dp))
                .padding(24.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("AI RESPONSE", style = MaterialTheme.typography.labelSmall, color = InkSecondary, modifier = Modifier.weight(1f))
                    if (isSpeaking) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Ink)
                                .clickable(onClick = onStopSpeaking),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Default.Stop, contentDescription = "Stop", tint = Paper, modifier = Modifier.size(16.dp)) }
                    }
                }
                Spacer(Modifier.height(14.dp))

                if (state == FeatureState.Loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Equalizer(active = true, color = Amber, modifier = Modifier.size(width = 28.dp, height = 16.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(fontFamily = InstrumentSerif, fontStyle = FontStyle.Italic, fontSize = 22.sp)) { append("Thinking…") }
                            },
                            color = Ink
                        )
                    }
                } else {
                    Text(
                        result,
                        fontFamily = InstrumentSerif,
                        fontStyle = FontStyle.Italic,
                        fontSize = 21.sp,
                        lineHeight = 30.sp,
                        letterSpacing = (-0.2).sp,
                        color = Ink,
                        modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 380.dp)
                    )
                    Spacer(Modifier.height(20.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .border(BorderStroke(1.dp, Hairline), RoundedCornerShape(50))
                                .clickable(onClick = onReplay)
                                .padding(horizontal = 18.dp, vertical = 10.dp)
                        ) { Text("Replay", style = MaterialTheme.typography.titleMedium, color = Ink, fontSize = 14.sp) }
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Ink)
                                .clickable(onClick = onDismiss)
                                .padding(horizontal = 18.dp, vertical = 10.dp)
                        ) { Text("Close", style = MaterialTheme.typography.titleMedium, color = Paper, fontSize = 14.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TranslateDialog(onDismiss: () -> Unit, onTranslate: (String) -> Unit) {
    val languages = listOf("English", "Hindi", "Spanish", "French", "German", "Arabic", "Chinese", "Japanese", "Portuguese", "Russian")
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(8.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Paper)
                .border(BorderStroke(1.dp, Hairline), RoundedCornerShape(24.dp))
                .padding(20.dp)
        ) {
            Column {
                Text("TRANSLATE TO", style = MaterialTheme.typography.labelSmall, color = InkSecondary)
                Spacer(Modifier.height(12.dp))
                Column(modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 360.dp)) {
                    languages.forEach { lang ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { onTranslate(lang) }
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        ) { Text(lang, style = MaterialTheme.typography.bodyLarge, color = Ink) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box(modifier = Modifier.align(Alignment.End).clickable(onClick = onDismiss).padding(8.dp)) {
                    Text("Cancel", style = MaterialTheme.typography.bodyLarge, color = InkSecondary)
                }
            }
        }
    }
}

@Composable
private fun SaveDialog(message: String, onTxt: () -> Unit, onPdf: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(8.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Paper)
                .border(BorderStroke(1.dp, Hairline), RoundedCornerShape(24.dp))
                .padding(20.dp)
        ) {
            Column {
                Text("SAVE DOCUMENT", style = MaterialTheme.typography.labelSmall, color = InkSecondary)
                Spacer(Modifier.height(8.dp))
                if (message.isNotBlank()) {
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = Moss)
                    Spacer(Modifier.height(12.dp))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .border(BorderStroke(1.dp, Hairline), RoundedCornerShape(16.dp))
                            .clickable(onClick = onTxt)
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("TXT", style = MaterialTheme.typography.titleMedium, color = Ink) }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Ink)
                            .clickable(onClick = onPdf)
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("PDF", style = MaterialTheme.typography.titleMedium, color = Paper) }
                }
                Spacer(Modifier.height(8.dp))
                Box(modifier = Modifier.align(Alignment.End).clickable(onClick = onDismiss).padding(8.dp)) {
                    Text("Close", style = MaterialTheme.typography.bodyLarge, color = InkSecondary)
                }
            }
        }
    }
}

private fun escapeHtml(s: String): String =
    s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
