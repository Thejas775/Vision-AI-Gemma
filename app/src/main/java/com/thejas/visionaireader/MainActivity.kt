package com.thejas.visionaireader

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
}
