package com.thejas.visionaireader

sealed class Screen {
    object ModelLoading : Screen()
    object Home : Screen()
    object Camera : Screen()
    object Reading : Screen()
    object Chat : Screen()
}

enum class ModelStatus { Checking, NotFound, Downloading, Loading, Ready, Error }
enum class InferenceState { Idle, Running, Done, Error }
enum class FeatureState { Idle, Loading, Done, Error }

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val id: Long = System.currentTimeMillis()
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
    val isAiThinking: Boolean = false
)