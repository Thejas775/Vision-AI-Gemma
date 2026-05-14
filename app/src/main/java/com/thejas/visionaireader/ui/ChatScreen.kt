package com.thejas.visionaireader.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thejas.visionaireader.ChatMessage
import com.thejas.visionaireader.ui.theme.*
import java.util.Locale

@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    isAiThinking: Boolean,
    onBack: () -> Unit,
    onSendMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()

    val ttsRef = remember { mutableStateOf<TextToSpeech?>(null) }
    val ttsReady = remember { mutableStateOf(false) }
    val isAiSpeaking = remember { mutableStateOf(false) }
    val lastSpokenId = remember { mutableLongStateOf(0L) }

    DisposableEffect(Unit) {
        val t = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsRef.value?.language = Locale.US
                ttsReady.value = true
            }
        }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { isAiSpeaking.value = true }
            override fun onDone(utteranceId: String?) { isAiSpeaking.value = false }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { isAiSpeaking.value = false }
        })
        ttsRef.value = t
        onDispose { t.stop(); t.shutdown() }
    }

    // Auto-speak only after streaming has fully completed (isAiThinking flipped to false).
    LaunchedEffect(isAiThinking, ttsReady.value) {
        if (isAiThinking || !ttsReady.value) return@LaunchedEffect
        val lastAi = messages.lastOrNull { !it.isUser } ?: return@LaunchedEffect
        if (lastAi.id != lastSpokenId.longValue && lastAi.text.isNotBlank()) {
            lastSpokenId.longValue = lastAi.id
            ttsRef.value?.speak(lastAi.text, TextToSpeech.QUEUE_FLUSH, null, "msg_${lastAi.id}")
        }
    }

    // Auto-scroll on new message, new chunk, or thinking-state change.
    val streamingText = messages.lastOrNull { !it.isUser }?.text ?: ""
    LaunchedEffect(messages.size, isAiThinking, streamingText) {
        val total = messages.size + if (isAiThinking && messages.lastOrNull()?.isUser == true) 1 else 0
        if (total > 0) listState.animateScrollToItem(total - 1)
    }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val text = matches?.firstOrNull()?.trim() ?: ""
            if (text.isNotBlank()) {
                ttsRef.value?.stop()
                isAiSpeaking.value = false
                onSendMessage(text)
            }
        }
    }

    fun startListening() {
        ttsRef.value?.stop()
        isAiSpeaking.value = false
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now")
        }
        try { speechLauncher.launch(intent) } catch (e: Exception) { e.printStackTrace() }
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { ttsRef.value?.stop(); onBack() }
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Ink)
            }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(
                            fontFamily = InstrumentSerif,
                            fontStyle = FontStyle.Italic,
                            fontSize = 26.sp,
                            letterSpacing = (-0.5).sp
                        )) { append("Chat") }
                        withStyle(SpanStyle(
                            fontFamily = InterTight,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 20.sp,
                            letterSpacing = (-0.3).sp
                        )) { append("  with AI") }
                    },
                    color = Ink
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    when {
                        isAiSpeaking.value -> "SPEAKING…"
                        isAiThinking -> "THINKING…"
                        else -> "VOICE-FIRST  ·  ON-DEVICE"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isAiSpeaking.value || isAiThinking) Amber else InkSecondary
                )
            }
            if (isAiSpeaking.value) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Ink)
                        .clickable { ttsRef.value?.stop(); isAiSpeaking.value = false },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop", tint = Paper, modifier = Modifier.size(18.dp))
                }
            }
        }

        Box(modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Hairline))

        // ── Messages ──
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty() && !isAiThinking) {
                EmptyState(onSuggestion = onSendMessage)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(messages, key = { it.id }) { msg -> Bubble(msg) }
                    // Show typing dots only before the first chunk arrives
                    if (isAiThinking && messages.lastOrNull()?.isUser == true) {
                        item(key = "thinking") { ThinkingBubble() }
                    }
                }
            }
        }

        // ── Mic dock ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 36.dp, top = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            MicButton(onClick = { startListening() })
            Spacer(Modifier.height(10.dp))
            Text(
                "TAP TO SPEAK",
                style = MaterialTheme.typography.labelSmall,
                color = InkSecondary
            )
        }
    }
}

@Composable
private fun Bubble(msg: ChatMessage) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 6 }
    ) {
        if (msg.isUser) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                        .background(Ink)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        msg.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Paper,
                        fontSize = 16.sp,
                        lineHeight = 22.sp
                    )
                }
            }
        } else {
            // AI: italic serif, no bubble — looks like "spoken text"
            Column {
                Text(
                    "AI",
                    style = MaterialTheme.typography.labelSmall,
                    color = InkSecondary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    msg.text,
                    fontFamily = InstrumentSerif,
                    fontStyle = FontStyle.Italic,
                    fontSize = 22.sp,
                    lineHeight = 30.sp,
                    letterSpacing = (-0.3).sp,
                    color = Ink
                )
            }
        }
    }
}

@Composable
private fun ThinkingBubble() {
    Column {
        Text("AI", style = MaterialTheme.typography.labelSmall, color = InkSecondary)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(0); Spacer(Modifier.width(6.dp))
            Dot(180); Spacer(Modifier.width(6.dp))
            Dot(360)
        }
    }
}

@Composable
private fun Dot(delay: Int) {
    val transition = rememberInfiniteTransition(label = "dot")
    val a by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, delayMillis = delay, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot-alpha"
    )
    Box(modifier = Modifier.size(7.dp).background(Ink.copy(alpha = a), CircleShape))
}

@Composable
private fun EmptyState(onSuggestion: (String) -> Unit) {
    val suggestions = listOf(
        "What can you help me with?",
        "Tell me a short joke",
        "Read me a poem",
        "Explain photosynthesis simply"
    )
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(
                    fontFamily = InstrumentSerif, fontStyle = FontStyle.Italic,
                    fontSize = 44.sp, letterSpacing = (-1).sp
                )) { append("Ask ") }
                withStyle(SpanStyle(
                    fontFamily = InterTight, fontWeight = FontWeight.SemiBold,
                    fontSize = 36.sp, letterSpacing = (-0.6).sp
                )) { append("anything.") }
            },
            color = Ink,
            lineHeight = 46.sp
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Tap the mic and speak naturally. Or try one of these:",
            style = MaterialTheme.typography.bodyMedium,
            color = InkSecondary
        )
        Spacer(Modifier.height(20.dp))
        suggestions.forEach { s ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(BorderStroke(1.dp, Hairline), RoundedCornerShape(16.dp))
                    .clickable { onSuggestion(s) }
                    .padding(horizontal = 18.dp, vertical = 14.dp)
            ) {
                Text(s, style = MaterialTheme.typography.bodyLarge, color = Ink, fontSize = 15.sp)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun MicButton(onClick: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "mic").animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "mic-pulse"
    )
    Box(
        modifier = Modifier
            .size(84.dp)
            .scale(pulse)
            .clip(CircleShape)
            .background(Amber)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.Mic,
            contentDescription = "Tap to speak",
            tint = Ink,
            modifier = Modifier.size(34.dp)
        )
    }
}
