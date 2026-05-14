package com.thejas.visionaireader.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thejas.visionaireader.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    onReadBook: () -> Unit,
    onChat: () -> Unit,
    onAgent: () -> Unit
) {
    val now = remember { Date() }
    val timestamp = remember(now) {
        SimpleDateFormat("HH:mm  ·  EEE, d MMM", Locale.getDefault()).format(now).uppercase()
    }
    val greeting = remember(now) {
        val hour = SimpleDateFormat("H", Locale.getDefault()).format(now).toInt()
        when (hour) {
            in 5..11 -> "Good\nmorning."
            in 12..16 -> "Good\nafternoon."
            in 17..20 -> "Good\nevening."
            else -> "Hello\nthere."
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp)
    ) {
        Spacer(Modifier.height(24.dp))

        // Mono caps timestamp
        Text(
            text = timestamp,
            style = MaterialTheme.typography.labelSmall,
            color = InkSecondary
        )

        Spacer(Modifier.height(20.dp))

        // Hero serif greeting — ragged-right
        Text(
            text = greeting,
            style = MaterialTheme.typography.displayLarge,
            color = Ink
        )

        Spacer(Modifier.height(14.dp))

        Text(
            text = "What would you like to do?",
            style = MaterialTheme.typography.bodyLarge,
            color = InkSecondary
        )

        Spacer(Modifier.height(28.dp))

        // ── Primary action: amber Read card ──
        ReadCard(onClick = onReadBook)

        Spacer(Modifier.height(12.dp))

        // ── Personal Agent: ink card with amber accent ──
        AgentCard(onClick = onAgent)

        Spacer(Modifier.height(12.dp))

        // ── Chat with AI ──
        ChatCard(onClick = onChat)

        Spacer(Modifier.weight(1f))

        // Mono caps footer status
        Row(
            modifier = Modifier.padding(bottom = 28.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(6.dp).background(Moss, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(
                "ON-DEVICE  ·  OFFLINE READY",
                style = MaterialTheme.typography.labelSmall,
                color = InkSecondary
            )
        }
    }
}

@Composable
private fun ReadCard(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Amber)
            .clickable(onClick = onClick)
    ) {
        // Paper-corner page-turn decoration (bottom right)
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val r = 48.dp.toPx()
            drawArc(
                color = Paper,
                startAngle = 0f,
                sweepAngle = 90f,
                useCenter = true,
                topLeft = Offset(size.width - r * 2, size.height - r * 2),
                size = Size(r * 2, r * 2)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(22.dp)
        ) {
            Text(
                "PRIMARY",
                style = MaterialTheme.typography.labelSmall,
                color = Ink.copy(alpha = 0.55f)
            )

            Spacer(Modifier.weight(1f))

            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(
                        fontFamily = InstrumentSerif,
                        fontStyle = FontStyle.Italic,
                        fontSize = 44.sp,
                        letterSpacing = (-1.2).sp
                    )) { append("Read ") }
                    withStyle(SpanStyle(
                        fontFamily = InterTight,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 30.sp,
                        letterSpacing = (-0.5).sp
                    )) { append("a book.") }
                },
                color = Ink,
                lineHeight = 44.sp
            )

            Spacer(Modifier.height(6.dp))

            Text(
                "Long documents — read aloud, paragraph by paragraph.",
                style = MaterialTheme.typography.bodyMedium,
                color = Ink.copy(alpha = 0.75f),
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun AgentCard(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Ink)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 18.dp)
    ) {
        CompositionLocalProvider(LocalContentColor provides Paper) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).background(Amber, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "AGENT  ·  SCAN & ACT",
                        style = MaterialTheme.typography.labelSmall,
                        color = Paper.copy(alpha = 0.55f)
                    )
                }

                Spacer(Modifier.weight(1f))

                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(
                            fontFamily = InstrumentSerif,
                            fontStyle = FontStyle.Italic,
                            fontSize = 34.sp,
                            letterSpacing = (-0.8).sp
                        )) { append("Personal ") }
                        withStyle(SpanStyle(
                            fontFamily = InterTight,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 26.sp,
                            letterSpacing = (-0.4).sp
                        )) { append("Agent.") }
                    },
                    color = Paper,
                    lineHeight = 36.sp
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    "Bills, invitations, labels, menus — I'll read it AND tell you what to do.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Paper.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                    lineHeight = 17.sp
                )
            }
        }
    }
}

@Composable
private fun ChatCard(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(98.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Ink)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 18.dp)
    ) {
        CompositionLocalProvider(LocalContentColor provides Paper) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "SECONDARY  ·  VOICE",
                        style = MaterialTheme.typography.labelSmall,
                        color = Paper.copy(alpha = 0.55f)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(
                                fontFamily = InstrumentSerif,
                                fontStyle = FontStyle.Italic,
                                fontSize = 30.sp,
                                letterSpacing = (-0.6).sp
                            )) { append("Chat ") }
                            withStyle(SpanStyle(
                                fontFamily = InterTight,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 24.sp,
                                letterSpacing = (-0.3).sp
                            )) { append("with AI.") }
                        },
                        color = Paper,
                        lineHeight = 32.sp
                    )
                }

                Spacer(Modifier.width(12.dp))

                // Three-bar equalizer
                Equalizer(active = false, color = Paper, modifier = Modifier.size(width = 32.dp, height = 36.dp))
            }
        }
    }
}

@Composable
fun Equalizer(active: Boolean, color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "eq")
    // Three bars with offset phases
    val phases = listOf(0, 140, 280)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        phases.forEach { delay ->
            val anim by transition.animateFloat(
                initialValue = if (active) 0.25f else 0.55f,
                targetValue = if (active) 1.0f else 0.85f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = if (active) 420 else 1100, delayMillis = delay, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bar$delay"
            )
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .fillMaxHeight(anim)
                    .background(color, RoundedCornerShape(2.dp))
            )
        }
    }
}
