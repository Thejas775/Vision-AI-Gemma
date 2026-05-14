package com.thejas.visionaireader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thejas.visionaireader.ModelStatus
import com.thejas.visionaireader.ui.theme.*

@Composable
fun ModelLoadingScreen(
    status: ModelStatus,
    downloadProgress: Int,
    downloadedMb: Float,
    totalMb: Float,
    errorMessage: String = "",
    onDownload: () -> Unit,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp)
    ) {
        Spacer(Modifier.height(32.dp))

        Text(
            "VISION AI READER",
            style = MaterialTheme.typography.labelSmall,
            color = InkSecondary
        )

        Spacer(Modifier.weight(1f))

        when (status) {
            ModelStatus.Checking -> {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontFamily = InstrumentSerif, fontStyle = FontStyle.Italic, fontSize = 44.sp)) { append("Just ") }
                        withStyle(SpanStyle(fontFamily = InterTight, fontWeight = FontWeight.SemiBold, fontSize = 38.sp, letterSpacing = (-0.6).sp)) { append("a moment.") }
                    },
                    color = Ink,
                    lineHeight = 46.sp
                )
                Spacer(Modifier.height(16.dp))
                Text("Checking your device for the AI model.", style = MaterialTheme.typography.bodyLarge, color = InkSecondary)
                Spacer(Modifier.height(20.dp))
                CircularProgressIndicator(color = Ink, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            }

            ModelStatus.NotFound -> {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontFamily = InstrumentSerif, fontStyle = FontStyle.Italic, fontSize = 52.sp, letterSpacing = (-1.2).sp)) { append("First ") }
                        withStyle(SpanStyle(fontFamily = InterTight, fontWeight = FontWeight.SemiBold, fontSize = 44.sp, letterSpacing = (-0.8).sp)) { append("things\nfirst.") }
                    },
                    color = Ink,
                    lineHeight = 56.sp
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Download the AI model once. After that, the app works fully offline — forever.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = InkSecondary
                )
                Spacer(Modifier.height(24.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .border(BorderStroke(1.dp, Hairline), RoundedCornerShape(20.dp))
                        .padding(18.dp)
                ) {
                    Column {
                        Text("MODEL", style = MaterialTheme.typography.labelSmall, color = InkSecondary)
                        Spacer(Modifier.height(6.dp))
                        Text("Gemma 4 E2B", style = MaterialTheme.typography.titleMedium, color = Ink)
                        Spacer(Modifier.height(2.dp))
                        Text("~2.6 GB  ·  one-time download", style = MaterialTheme.typography.bodyMedium, color = InkSecondary, fontSize = 13.sp)
                    }
                }

                Spacer(Modifier.height(20.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Amber)
                        .clickable(onClick = onDownload),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Download model", style = MaterialTheme.typography.titleMedium, color = Ink)
                }
            }

            ModelStatus.Downloading -> {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontFamily = InstrumentSerif, fontStyle = FontStyle.Italic, fontSize = 44.sp)) { append("Downloading ") }
                        withStyle(SpanStyle(fontFamily = InterTight, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, letterSpacing = (-0.6).sp)) { append("the\nmodel.") }
                    },
                    color = Ink,
                    lineHeight = 48.sp
                )
                Spacer(Modifier.height(24.dp))
                LinearProgressIndicator(
                    progress = { downloadProgress / 100f },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = Amber,
                    trackColor = PaperDim
                )
                Spacer(Modifier.height(10.dp))
                val totalText = if (totalMb > 0f) "/ %.0f MB".format(totalMb) else ""
                Text(
                    "%.0f MB %s  ·  $downloadProgress%%".format(downloadedMb, totalText),
                    style = MaterialTheme.typography.labelSmall,
                    color = InkSecondary
                )
                Spacer(Modifier.height(20.dp))
                Text("Keep the app open. This is a one-time download.", style = MaterialTheme.typography.bodyMedium, color = InkSecondary)
            }

            ModelStatus.Loading -> {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontFamily = InstrumentSerif, fontStyle = FontStyle.Italic, fontSize = 44.sp)) { append("Waking ") }
                        withStyle(SpanStyle(fontFamily = InterTight, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, letterSpacing = (-0.6).sp)) { append("the\nmodel.") }
                    },
                    color = Ink,
                    lineHeight = 48.sp
                )
                Spacer(Modifier.height(16.dp))
                Text("This usually takes up to 10 seconds.", style = MaterialTheme.typography.bodyLarge, color = InkSecondary)
                Spacer(Modifier.height(20.dp))
                CircularProgressIndicator(color = Ink, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            }

            ModelStatus.Error -> {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontFamily = InstrumentSerif, fontStyle = FontStyle.Italic, fontSize = 44.sp)) { append("Something ") }
                        withStyle(SpanStyle(fontFamily = InterTight, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, letterSpacing = (-0.6).sp)) { append("went\nwrong.") }
                    },
                    color = Ink,
                    lineHeight = 48.sp
                )
                if (errorMessage.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(errorMessage, style = MaterialTheme.typography.bodyMedium, color = InkSecondary)
                }
                Spacer(Modifier.height(24.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Ink)
                        .clickable(onClick = onRetry),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Try again", style = MaterialTheme.typography.titleMedium, color = Paper)
                }
            }

            ModelStatus.Ready -> Unit
        }

        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(32.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 28.dp)) {
            Box(modifier = Modifier.size(6.dp).background(Moss, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text("RUNS FULLY ON YOUR DEVICE", style = MaterialTheme.typography.labelSmall, color = InkSecondary)
        }
    }
}
