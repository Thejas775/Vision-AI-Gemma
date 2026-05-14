package com.thejas.visionaireader

import com.thejas.visionaireader.engine.AgentAction

sealed class Screen {
    object ModelLoading : Screen()
    object Home : Screen()
    object Camera : Screen()
    object Reading : Screen()
    object Chat : Screen()
    object Agent : Screen()
}

enum class ModelStatus { Checking, NotFound, Downloading, Loading, Ready, Error }
enum class InferenceState { Idle, Running, Done, Error }
enum class FeatureState { Idle, Loading, Done, Error }

/** Stages the Personal Agent moves through after a scan. */
enum class AgentStage {
    Idle,                // No scan in progress — show "tap to scan"
    Capturing,           // Camera open, waiting for shutter tap
    Ocr,                 // Running ML Kit OCR
    Thinking,            // Gemma analyzing the OCR text
    Speaking,            // App is reading the response aloud
    AwaitingYesNo,       // Asked the user a question, listening for yes/no
    Acting,              // Tool fired (calendar, reminder, etc.)
    MenuChatIdle,        // Menu loaded; waiting for user to ask a question
    MenuChatThinking,    // Menu Q&A in progress
    MenuChatSpeaking,    // Speaking the menu answer
    Done,                // Single-shot action complete (chip visible)
    Error
}

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val id: Long = System.currentTimeMillis()
)

data class AgentState(
    val stage: AgentStage = AgentStage.Idle,
    val ocrText: String = "",
    val spokenText: String = "",
    val proposedAction: AgentAction = AgentAction.NONE,
    val proposedTitle: String = "",
    val proposedDateIso: String = "",
    val toolCalls: List<String> = emptyList(),
    val resultMessage: String = "",
    val errorMessage: String = "",
    // Menu-mode follow-up Q&A
    val menuQuestion: String = "",
    val menuAnswer: String = ""
)

data class AppState(
    val currentScreen: Screen = Screen.ModelLoading,
    val modelStatus: ModelStatus = ModelStatus.Checking,
    val downloadProgress: Int = 0,
    val downloadedMb: Float = 0f,
    val totalMb: Float = 0f,
    val inferenceState: InferenceState = InferenceState.Idle,
    val generatedHtml: String = "",
    val rawOcrText: String = "",
    val errorMessage: String = "",
    val featureState: FeatureState = FeatureState.Idle,
    val featureResult: String = "",
    val chatMessages: List<ChatMessage> = emptyList(),
    val isAiThinking: Boolean = false,
    val agent: AgentState = AgentState()
)
