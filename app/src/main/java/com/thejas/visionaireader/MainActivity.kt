package com.thejas.visionaireader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.thejas.visionaireader.ui.AgentScreen
import com.thejas.visionaireader.ui.CameraScreen
import com.thejas.visionaireader.ui.ChatScreen
import com.thejas.visionaireader.ui.HomeScreen
import com.thejas.visionaireader.ui.ModelLoadingScreen
import com.thejas.visionaireader.ui.ReadingScreen
import com.thejas.visionaireader.ui.theme.VisionAIReaderTheme

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        handlePdfIntent(intent)

        setContent {
            VisionAIReaderTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by viewModel.state.collectAsState()

                    AnimatedContent(
                        targetState = state.currentScreen,
                        transitionSpec = {
                            fadeIn(tween(280)) togetherWith fadeOut(tween(220))
                        },
                        label = "screen"
                    ) { screen ->
                        when (screen) {
                            is Screen.ModelLoading -> ModelLoadingScreen(
                                status = state.modelStatus,
                                downloadProgress = state.downloadProgress,
                                downloadedMb = state.downloadedMb,
                                totalMb = state.totalMb,
                                errorMessage = state.errorMessage,
                                onDownload = { viewModel.startDownload() },
                                onRetry = { viewModel.checkAndLoadModel() }
                            )
                            is Screen.Home -> HomeScreen(
                                onReadBook = { viewModel.goToCameraScreen() },
                                onChat = { viewModel.goToChatScreen() },
                                onAgent = { viewModel.goToAgentScreen() }
                            )
                            is Screen.Camera -> CameraScreen(
                                onImageCaptured = { bitmap -> viewModel.onImageCaptured(bitmap) },
                                onBack = { viewModel.goToHome() }
                            )
                            is Screen.Reading -> ReadingScreen(
                                inferenceState = state.inferenceState,
                                htmlContent = state.generatedHtml,
                                rawOcrText = state.rawOcrText,
                                errorMessage = state.errorMessage,
                                featureState = state.featureState,
                                featureResult = state.featureResult,
                                onBack = { viewModel.goToHome() },
                                onSummarize = { viewModel.summarize() },
                                onTranslate = { lang -> viewModel.translate(lang) },
                                onAskQuestion = { q -> viewModel.askQuestion(q) },
                                onSaveTxt = { viewModel.saveAsTxt() },
                                onSavePdf = { viewModel.saveAsPdf() },
                                onClearFeature = { viewModel.clearFeatureResult() }
                            )
                            is Screen.Chat -> ChatScreen(
                                messages = state.chatMessages,
                                isAiThinking = state.isAiThinking,
                                isMenuChat = state.isMenuChat,
                                onBack = { viewModel.goToHome() },
                                onSendMessage = { text -> viewModel.sendChatMessage(text) }
                            )
                            is Screen.Agent -> AgentScreen(
                                state = state.agent,
                                onBack = { viewModel.goToHome() },
                                onCapture = { bitmap -> viewModel.onAgentImageCaptured(bitmap) },
                                onSpeechFinished = { viewModel.onAgentFinishedSpeaking() },
                                onYesNo = { yes -> viewModel.onAgentYesNoAnswer(yes) },
                                onMenuQuestion = { q -> viewModel.onMenuQuestionAsked(q) },
                                onMenuAnswerSpoken = { viewModel.onMenuAnswerSpoken() },
                                onScanAgain = { viewModel.resetAgent() }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePdfIntent(intent)
    }

    /** Catch ACTION_VIEW / ACTION_SEND with application/pdf and route into the reading flow. */
    private fun handlePdfIntent(intent: Intent?) {
        if (intent == null) return
        val uri: Uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
            else -> null
        } ?: return

        val type = intent.type ?: contentResolver.getType(uri) ?: ""
        val isPdf = type == "application/pdf" ||
                    uri.toString().lowercase().endsWith(".pdf")
        if (isPdf) {
            // Persist read permission for content:// URIs so we can re-read on rotation
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { /* not all URIs support persistable perms */ }
            viewModel.onExternalPdf(uri)
        }
    }
}
