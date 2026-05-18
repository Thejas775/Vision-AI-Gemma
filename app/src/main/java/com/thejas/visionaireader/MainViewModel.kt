package com.thejas.visionaireader

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thejas.visionaireader.engine.AgentAction
import com.thejas.visionaireader.engine.AgentParser
import com.thejas.visionaireader.engine.AgentTools
import com.thejas.visionaireader.engine.InferenceEngine
import com.thejas.visionaireader.engine.OcrEngine
import com.thejas.visionaireader.engine.PdfTextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val MODEL_URL =
    "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
private const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state

    private val inferenceEngine = InferenceEngine(application)
    private val ocrEngine = OcrEngine()
    private val agentTools = AgentTools(application)
    private val pdfExtractor = PdfTextExtractor(application, ocrEngine)

    // LiteRT-LM only ships arm64-v8a / x86_64. 32-bit processes can't load it.
    private val canRunLiteRt: Boolean =
        System.getProperty("os.arch")?.let { it.contains("aarch64") || it.contains("x86_64") || it.contains("amd64") } ?: false

    /* ──────────────────────────────────────────────────────────────────────
     * QUALCOMM CHIPSET DETECTION (disabled — kept for reference / demo)
     *
     * On Qualcomm Snapdragon devices we'd take the direct vision path:
     *   image -> Gemma 4 vision encoder -> HTML in a single inference call.
     * On MediaTek (and as a universal fallback) we use:
     *   image -> ML Kit OCR -> text -> Gemma 4 text-only -> HTML.
     *
     * The vision path crashes on Dimensity SoCs in LiteRT-LM 0.10.0 (Issue #1849),
     * so we ship the universal ML Kit path. Detection lives below in case
     * vision becomes stable later.
     *
     *  private val isQualcomm: Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
     *      Build.SOC_MANUFACTURER.equals("Qualcomm", ignoreCase = true)
     *  } else {
     *      Build.HARDWARE.contains("qcom", ignoreCase = true)
     *  }
     *
     *  // In loadModel() we would branch:
     *  //   if (isQualcomm) inferenceEngine.initializeWithVision(modelFile.absolutePath)
     *  //   else            inferenceEngine.initialize(modelFile.absolutePath)
     *
     *  // In runInference() we would branch:
     *  //   if (isQualcomm) {
     *  //       val html = inferenceEngine.readImageDirect(imagePath)   // vision
     *  //   } else {
     *  //       val ocrText = ocrEngine.extractText(bitmap)             // ML Kit
     *  //       val html = inferenceEngine.formatAsHtml(ocrText)         // text-only
     *  //   }
     * ────────────────────────────────────────────────────────────────────── */

    private var engineReady = false

    val modelFile: File
        get() = File(getApplication<Application>().getExternalFilesDir(null), MODEL_FILE_NAME)

    init { checkAndLoadModel() }

    fun checkAndLoadModel() {
        viewModelScope.launch {
            if (!canRunLiteRt) {
                // 32-bit device — OCR-only mode, skip model entirely
                _state.value = _state.value.copy(modelStatus = ModelStatus.Ready, currentScreen = Screen.Home)
                return@launch
            }

            _state.value = _state.value.copy(modelStatus = ModelStatus.Checking)

            if (!modelFile.exists() || modelFile.length() < 100_000_000L) {
                if (modelFile.exists()) modelFile.delete()
                _state.value = _state.value.copy(modelStatus = ModelStatus.NotFound, currentScreen = Screen.ModelLoading)
                return@launch
            }

            loadModel()
        }
    }

    fun goToHome() {
        _state.value = _state.value.copy(currentScreen = Screen.Home)
    }

    fun goToCameraScreen() {
        if (engineReady) inferenceEngine.resetReading()
        _state.value = _state.value.copy(
            currentScreen = Screen.Camera,
            inferenceState = InferenceState.Idle,
            generatedHtml = "",
            rawOcrText = "",
            featureState = FeatureState.Idle,
            featureResult = ""
        )
    }

    /** External PDF received via ACTION_VIEW / ACTION_SEND — extract text and route to Reading. */
    fun onExternalPdf(uri: android.net.Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                currentScreen = Screen.Reading,
                inferenceState = InferenceState.Running,
                generatedHtml = "",
                rawOcrText = "",
                errorMessage = ""
            )
            try {
                Log.d("VisionAgent", "External PDF intent: $uri")
                val text = pdfExtractor.extractText(uri)
                Log.d("VisionAgent", "PDF extracted text length=${text.length}")
                if (text.isBlank()) {
                    _state.value = _state.value.copy(
                        inferenceState = InferenceState.Error,
                        errorMessage = "Couldn't read any text from this PDF."
                    )
                    return@launch
                }
                val html = if (engineReady) inferenceEngine.formatAsHtml(text) else ocrToBasicHtml(text)
                _state.value = _state.value.copy(
                    generatedHtml = html,
                    rawOcrText = text,
                    inferenceState = InferenceState.Done
                )
            } catch (e: Exception) {
                Log.e("VisionAgent", "PDF processing failed", e)
                _state.value = _state.value.copy(
                    inferenceState = InferenceState.Error,
                    errorMessage = e.message ?: "PDF processing failed"
                )
            }
        }
    }

    fun goToChatScreen() {
        if (engineReady) inferenceEngine.resetChat()
        _state.value = _state.value.copy(
            currentScreen = Screen.Chat,
            chatMessages = emptyList(),
            isAiThinking = false,
            isMenuChat = false
        )
    }

    fun goToAgentScreen() {
        _state.value = _state.value.copy(
            currentScreen = Screen.Agent,
            agent = AgentState(stage = AgentStage.Capturing)
        )
    }

    // ── Agent state machine ─────────────────────────────────────────────

    /** User tapped capture in the agent camera. Bitmap → OCR → Gemma analyze. */
    fun onAgentImageCaptured(bitmap: android.graphics.Bitmap) {
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(agent = _state.value.agent.copy(stage = AgentStage.Ocr))
                val ocrText = ocrEngine.extractText(bitmap)
                withContext(Dispatchers.IO) { bitmap.recycle() }
                Log.d("VisionAgent", "─── OCR OUTPUT ───────────────────────────")
                Log.d("VisionAgent", ocrText.ifBlank { "<empty>" })
                Log.d("VisionAgent", "──────────────────────────────────────────")

                if (ocrText.isBlank()) {
                    _state.value = _state.value.copy(agent = _state.value.agent.copy(
                        stage = AgentStage.Error,
                        errorMessage = "No text detected. Try again with better light."
                    ))
                    return@launch
                }

                _state.value = _state.value.copy(agent = _state.value.agent.copy(
                    stage = AgentStage.Thinking,
                    ocrText = ocrText
                ))

                if (!engineReady) {
                    // OCR-only fallback for 32-bit devices
                    _state.value = _state.value.copy(agent = _state.value.agent.copy(
                        stage = AgentStage.Speaking,
                        spokenText = ocrText.take(500),
                        proposedAction = AgentAction.NONE,
                        toolCalls = listOf("ocr-only mode")
                    ))
                    return@launch
                }

                val raw = inferenceEngine.agentAnalyze(ocrText)
                Log.d("VisionAgent", "─── GEMMA RAW RESPONSE ──────────────────")
                Log.d("VisionAgent", raw)
                Log.d("VisionAgent", "──────────────────────────────────────────")

                val decision = AgentParser.parse(raw)
                Log.d("VisionAgent", "Decision: action=${decision.action} title='${decision.title}' date='${decision.dateIso}' ask=${decision.ask}")
                Log.d("VisionAgent", "Speak: '${decision.speak}'")

                val nextStage = when {
                    decision.action == AgentAction.WARN -> AgentStage.Speaking
                    decision.action == AgentAction.NONE -> AgentStage.Speaking
                    decision.action == AgentAction.MENU -> AgentStage.Speaking  // speak overview, then auto-route to chat
                    decision.ask -> AgentStage.AwaitingYesNo
                    else -> AgentStage.Speaking
                }

                _state.value = _state.value.copy(agent = _state.value.agent.copy(
                    stage = nextStage,
                    spokenText = decision.speak,
                    proposedAction = decision.action,
                    proposedTitle = decision.title,
                    proposedDateIso = decision.dateIso
                ))
            } catch (e: Exception) {
                _state.value = _state.value.copy(agent = _state.value.agent.copy(
                    stage = AgentStage.Error,
                    errorMessage = e.message ?: "Agent failed"
                ))
            }
        }
    }

    /** UI just finished speaking the question — caller transitions stage to AwaitingYesNo. */
    fun onAgentFinishedSpeaking() {
        val s = _state.value.agent
        if (s.stage != AgentStage.Speaking) return

        // Menu scan → hand off to Chat with the menu loaded as context
        if (s.proposedAction == AgentAction.MENU) {
            if (engineReady) inferenceEngine.primeChatWithMenu(s.ocrText)
            _state.value = _state.value.copy(
                currentScreen = Screen.Chat,
                chatMessages = emptyList(),
                isAiThinking = false,
                isMenuChat = true,
                agent = AgentState() // reset agent state so re-entry starts fresh
            )
            return
        }

        // Single-shot (warn / none) completes after speaking
        val toolCall = when (s.proposedAction) {
            AgentAction.WARN -> "warnUser"
            else -> ""
        }
        _state.value = _state.value.copy(agent = s.copy(
            stage = AgentStage.Done,
            toolCalls = if (toolCall.isNotBlank()) s.toolCalls + toolCall else s.toolCalls
        ))
    }

    /** User answered the yes/no question. */
    fun onAgentYesNoAnswer(yes: Boolean) {
        val s = _state.value.agent
        if (s.stage != AgentStage.AwaitingYesNo) return
        if (!yes) {
            _state.value = _state.value.copy(agent = s.copy(
                stage = AgentStage.Done,
                resultMessage = "Okay, no action taken."
            ))
            return
        }

        // For menu — enter Q&A mode instead of firing a one-shot tool
        if (s.proposedAction == AgentAction.MENU) {
            _state.value = _state.value.copy(agent = s.copy(
                stage = AgentStage.MenuChatIdle,
                toolCalls = s.toolCalls + "loadMenuContext"
            ))
            return
        }

        // Otherwise execute the proposed tool
        viewModelScope.launch {
            _state.value = _state.value.copy(agent = s.copy(stage = AgentStage.Acting))
            val title = s.proposedTitle.ifBlank { "Untitled" }
            Log.d("VisionAgent", "Firing tool=${s.proposedAction} title='$title' date='${s.proposedDateIso}'")
            val result = withContext(Dispatchers.IO) {
                when (s.proposedAction) {
                    AgentAction.CALENDAR -> {
                        val r = agentTools.addCalendarEvent(title, s.proposedDateIso)
                        Pair("addCalendarEvent", r)
                    }
                    AgentAction.REMINDER -> {
                        val r = agentTools.setBillReminder(title, s.proposedDateIso)
                        Pair("setBillReminder", r)
                    }
                    else -> Pair("noop", com.thejas.visionaireader.engine.ToolResult(true, ""))
                }
            }
            Log.d("VisionAgent", "Tool result: success=${result.second.success} msg='${result.second.message}'")
            _state.value = _state.value.copy(agent = _state.value.agent.copy(
                stage = AgentStage.Done,
                toolCalls = _state.value.agent.toolCalls + result.first,
                resultMessage = result.second.message
            ))
        }
    }

    /** Menu mode: user asked a follow-up question. */
    fun onMenuQuestionAsked(question: String) {
        val s = _state.value.agent
        if (s.ocrText.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(agent = s.copy(
                stage = AgentStage.MenuChatThinking,
                menuQuestion = question,
                menuAnswer = ""
            ))
            try {
                val answer = inferenceEngine.menuAnswer(question, s.ocrText)
                _state.value = _state.value.copy(agent = _state.value.agent.copy(
                    stage = AgentStage.MenuChatSpeaking,
                    menuAnswer = answer,
                    toolCalls = _state.value.agent.toolCalls + "menuAnswer"
                ))
            } catch (e: Exception) {
                _state.value = _state.value.copy(agent = _state.value.agent.copy(
                    stage = AgentStage.MenuChatIdle,
                    menuAnswer = "Sorry, I couldn't answer that.",
                    errorMessage = e.message ?: ""
                ))
            }
        }
    }

    /** Called by AgentScreen after TTS finishes speaking the menu answer. */
    fun onMenuAnswerSpoken() {
        val s = _state.value.agent
        if (s.stage == AgentStage.MenuChatSpeaking) {
            _state.value = _state.value.copy(agent = s.copy(stage = AgentStage.MenuChatIdle))
        }
    }

    /** Reset agent to start a new scan. */
    fun resetAgent() {
        _state.value = _state.value.copy(agent = AgentState(stage = AgentStage.Capturing))
    }

    fun sendChatMessage(text: String) {
        if (text.isBlank()) return
        val userMsg = ChatMessage(text = text, isUser = true)
        _state.value = _state.value.copy(
            chatMessages = _state.value.chatMessages + userMsg,
            isAiThinking = true
        )

        if (!engineReady) {
            val aiMsg = ChatMessage(text = "AI model not loaded on this device.", isUser = false)
            _state.value = _state.value.copy(
                chatMessages = _state.value.chatMessages + aiMsg,
                isAiThinking = false
            )
            return
        }

        viewModelScope.launch {
            var aiMsgId = -1L
            inferenceEngine.chatStream(text)
                .catch { e ->
                    val aiMsg = ChatMessage(text = "Sorry, something went wrong. ${e.message ?: ""}", isUser = false)
                    _state.value = _state.value.copy(
                        chatMessages = _state.value.chatMessages + aiMsg,
                        isAiThinking = false
                    )
                }
                .onCompletion {
                    // Stream done — flip isAiThinking so the ChatScreen TTS effect speaks the final reply.
                    _state.value = _state.value.copy(isAiThinking = false)
                }
                .collect { chunk ->
                    if (chunk.isEmpty()) return@collect
                    val current = _state.value.chatMessages
                    if (aiMsgId == -1L) {
                        val newAi = ChatMessage(text = chunk, isUser = false)
                        aiMsgId = newAi.id
                        _state.value = _state.value.copy(chatMessages = current + newAi)
                    } else {
                        val updated = current.map {
                            if (it.id == aiMsgId) it.copy(text = it.text + chunk) else it
                        }
                        _state.value = _state.value.copy(chatMessages = updated)
                    }
                }
        }
    }

    fun startDownload() {
        viewModelScope.launch {
            _state.value = _state.value.copy(modelStatus = ModelStatus.Downloading, downloadProgress = 0, downloadedMb = 0f, totalMb = 0f)

            val success = withContext(Dispatchers.IO) {
                downloadModel { downloaded, total ->
                    val pct = if (total > 0) ((downloaded * 100) / total).toInt() else 0
                    _state.value = _state.value.copy(
                        downloadProgress = pct,
                        downloadedMb = downloaded / 1_048_576f,
                        totalMb = total / 1_048_576f
                    )
                }
            }

            if (success) {
                loadModel()
            } else {
                if (modelFile.exists()) modelFile.delete()
                _state.value = _state.value.copy(
                    modelStatus = ModelStatus.Error,
                    errorMessage = "Download failed. Check your connection and try again."
                )
            }
        }
    }

    private suspend fun loadModel() {
        _state.value = _state.value.copy(modelStatus = ModelStatus.Loading)
        try {
            inferenceEngine.initialize(modelFile.absolutePath)
            engineReady = true
            _state.value = _state.value.copy(modelStatus = ModelStatus.Ready, currentScreen = Screen.Home)
        } catch (t: Throwable) {
            _state.value = _state.value.copy(modelStatus = ModelStatus.Error, errorMessage = t.message ?: "Failed to load model")
        }
    }

    private fun downloadModel(onProgress: (Long, Long) -> Unit): Boolean {
        val tmpFile = File(modelFile.parent, "$MODEL_FILE_NAME.tmp")
        return try {
            val conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "VisionAIReader/1.0")
            }
            conn.connect()
            if (conn.responseCode !in 200..299) { conn.disconnect(); return false }

            val total = conn.contentLengthLong
            var downloaded = 0L
            conn.inputStream.use { input ->
                FileOutputStream(tmpFile).use { out ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                        downloaded += n
                        onProgress(downloaded, total)
                    }
                }
            }
            conn.disconnect()
            if (tmpFile.length() > 1_000_000_000L) { tmpFile.renameTo(modelFile); true }
            else { tmpFile.delete(); false }
        } catch (e: Exception) { tmpFile.delete(); false }
    }

    fun onImageCaptured(bitmap: Bitmap) {
        viewModelScope.launch {
            val imagePath = saveBitmapToCache(bitmap)
            _state.value = _state.value.copy(
                inferenceState = InferenceState.Running,
                generatedHtml = "",
                rawOcrText = "",
                currentScreen = Screen.Reading
            )
            runInference(imagePath)
        }
    }

    private suspend fun runInference(imagePath: String) {
        try {
            val bitmap = withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(imagePath) ?: error("Failed to decode image")
            }
            val ocrText = ocrEngine.extractText(bitmap)
            withContext(Dispatchers.IO) { bitmap.recycle() }

            val html = when {
                ocrText.isBlank() -> "<p>No text found in the image.</p>"
                engineReady -> inferenceEngine.formatAsHtml(ocrText)
                else -> ocrToBasicHtml(ocrText)
            }

            _state.value = _state.value.copy(
                generatedHtml = html,
                rawOcrText = ocrText,
                inferenceState = InferenceState.Done
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(inferenceState = InferenceState.Error, errorMessage = e.message ?: "Inference failed")
        }
    }

    fun summarize() {
        val text = _state.value.rawOcrText.ifBlank { return }
        viewModelScope.launch {
            _state.value = _state.value.copy(featureState = FeatureState.Loading, featureResult = "")
            try {
                val result = if (engineReady) inferenceEngine.summarize(text) else "Gemma model not loaded."
                _state.value = _state.value.copy(featureState = FeatureState.Done, featureResult = result)
            } catch (e: Exception) {
                _state.value = _state.value.copy(featureState = FeatureState.Error, featureResult = e.message ?: "Summarize failed")
            }
        }
    }

    fun translate(targetLanguage: String) {
        val text = _state.value.rawOcrText.ifBlank { return }
        viewModelScope.launch {
            _state.value = _state.value.copy(featureState = FeatureState.Loading, featureResult = "")
            try {
                val result = if (engineReady) inferenceEngine.translate(text, targetLanguage) else "Gemma model not loaded."
                _state.value = _state.value.copy(featureState = FeatureState.Done, featureResult = result)
            } catch (e: Exception) {
                _state.value = _state.value.copy(featureState = FeatureState.Error, featureResult = e.message ?: "Translate failed")
            }
        }
    }

    fun askQuestion(question: String) {
        val text = _state.value.rawOcrText.ifBlank { return }
        viewModelScope.launch {
            _state.value = _state.value.copy(featureState = FeatureState.Loading, featureResult = "")
            try {
                val result = if (engineReady) inferenceEngine.askQuestion(question, text) else "Gemma model not loaded."
                _state.value = _state.value.copy(featureState = FeatureState.Done, featureResult = result)
            } catch (e: Exception) {
                _state.value = _state.value.copy(featureState = FeatureState.Error, featureResult = e.message ?: "Ask AI failed")
            }
        }
    }

    fun saveAsTxt(): Boolean {
        val text = _state.value.rawOcrText.ifBlank { return false }
        return try {
            val name = "VisionAI_${System.currentTimeMillis()}.txt"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/VisionAIReader/")
            }
            val uri = getApplication<Application>().contentResolver
                .insert(MediaStore.Files.getContentUri("external"), values) ?: return false
            getApplication<Application>().contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            true
        } catch (e: Exception) { false }
    }

    fun saveAsPdf(): Boolean {
        val text = _state.value.rawOcrText.ifBlank { return false }
        return try {
            val doc = PdfDocument()
            val paint = Paint().apply { textSize = 14f; isAntiAlias = true }
            val pageWidth = 595; val pageHeight = 842
            val margin = 40f; val lineHeight = 20f
            val maxWidth = pageWidth - margin * 2

            val words = text.split(" ")
            val lines = mutableListOf<String>()
            var current = StringBuilder()
            for (word in words) {
                val test = if (current.isEmpty()) word else "$current $word"
                if (paint.measureText(test) <= maxWidth) {
                    current = StringBuilder(test)
                } else {
                    if (current.isNotEmpty()) lines.add(current.toString())
                    current = StringBuilder(word)
                }
            }
            if (current.isNotEmpty()) lines.add(current.toString())

            var pageNum = 1
            var y = margin + lineHeight
            var page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())

            for (line in lines) {
                if (y + lineHeight > pageHeight - margin) {
                    doc.finishPage(page)
                    pageNum++
                    y = margin + lineHeight
                    page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
                }
                page.canvas.drawText(line, margin, y, paint)
                y += lineHeight
            }
            doc.finishPage(page)

            val name = "VisionAI_${System.currentTimeMillis()}.pdf"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/VisionAIReader/")
            }
            val uri = getApplication<Application>().contentResolver
                .insert(MediaStore.Files.getContentUri("external"), values) ?: return false
            getApplication<Application>().contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) }
            doc.close()
            true
        } catch (e: Exception) { false }
    }

    fun clearFeatureResult() {
        _state.value = _state.value.copy(featureState = FeatureState.Idle, featureResult = "")
    }

    private fun ocrToBasicHtml(text: String): String = buildString {
        text.trim().split("\n").filter { it.isNotBlank() }.forEach { append("<p>${it.trim()}</p>\n") }
    }

    private suspend fun saveBitmapToCache(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val maxDim = 1024
        val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
            val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else bitmap
        val file = File(getApplication<Application>().cacheDir, "scan_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (scaled !== bitmap) scaled.recycle()
        file.absolutePath
    }

    override fun onCleared() {
        super.onCleared()
        inferenceEngine.close()
        ocrEngine.close()
    }
}
