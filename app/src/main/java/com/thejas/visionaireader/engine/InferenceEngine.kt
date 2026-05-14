package com.thejas.visionaireader.engine

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class InferenceEngine(private val context: Context) {

    // LiteRT-LM only supports ONE conversation/session per engine at a time.
    // So we keep a single conversation and reset it when switching task contexts.
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var chatPrimed = false

    suspend fun initialize(modelPath: String) = withContext(Dispatchers.IO) {
        val config = try {
            EngineConfig(modelPath = modelPath, backend = Backend.GPU(), cacheDir = context.cacheDir.path)
        } catch (e: Exception) {
            EngineConfig(modelPath = modelPath, backend = Backend.CPU(), cacheDir = context.cacheDir.path)
        }
        val e = Engine(config)
        e.initialize()
        engine = e
        conversation = e.createConversation()
    }

    /* ──────────────────────────────────────────────────────────────────────
     * QUALCOMM VISION PATH (disabled — kept for reference / hackathon demo)
     *
     * On Qualcomm Snapdragon devices, Gemma 4 E2B can ingest the image directly
     * via its built-in vision encoder. Initialize the engine with `visionBackend`
     * to enable the multimodal pipeline, then send Content.ImageBytes alongside
     * a text prompt. We disabled this for the universal build because:
     *
     *   1. LiteRT-LM 0.10.0 SIGSEGV crashes on MediaTek Dimensity devices
     *      (https://github.com/google-ai-edge/LiteRT-LM/issues/1849)
     *   2. The Kotlin API lacks a custom PromptTemplate field
     *      (https://github.com/google-ai-edge/LiteRT-LM/issues/1874)
     *
     * Falling back to ML Kit OCR → Gemma text-only works on every chipset.
     * The block below is what we'd ship on a Qualcomm-only build.
     *
     *  suspend fun initializeWithVision(modelPath: String) = withContext(Dispatchers.IO) {
     *      val config = try {
     *          EngineConfig(
     *              modelPath = modelPath,
     *              backend = Backend.GPU(),
     *              visionBackend = Backend.GPU(),   // <- enables vision encoder
     *              cacheDir = context.cacheDir.path
     *          )
     *      } catch (e: Exception) {
     *          EngineConfig(
     *              modelPath = modelPath,
     *              backend = Backend.CPU(),
     *              visionBackend = Backend.CPU(),
     *              cacheDir = context.cacheDir.path
     *          )
     *      }
     *      val e = Engine(config); e.initialize()
     *      engine = e
     *      conversation = e.createConversation()
     *  }
     *
     *  suspend fun readImageDirect(imagePath: String): String = withContext(Dispatchers.IO) {
     *      val conv = conversation ?: error("Engine not initialized")
     *      val prompt = "You are a reading assistant for visually impaired users. " +
     *          "The image contains printed or handwritten text. " +
     *          "Transcribe ALL the text and format it as clean, accessible HTML. " +
     *          "Use <h1>, <h2>, <p>, <strong>, <em>, <ul>, <ol>, <li> tags where appropriate. " +
     *          "Return ONLY the HTML content."
     *      val imageBytes = java.io.File(imagePath).readBytes()
     *      val response = conv.sendMessage(
     *          Contents.of(
     *              Content.ImageBytes(imageBytes),
     *              Content.Text(prompt)
     *          )
     *      )
     *      response.toString().replace("```html", "").replace("```", "").trim()
     *  }
     * ────────────────────────────────────────────────────────────────────── */

    /** Disposes the current conversation and creates a fresh one — used between mode switches. */
    private fun resetConversation() {
        val e = engine ?: error("Engine not initialized")
        try {
            (conversation as? AutoCloseable)?.close()
        } catch (t: Throwable) { /* ignore */ }
        conversation = e.createConversation()
        chatPrimed = false
    }

    // ── Chat (long-lived conversation) ──
    suspend fun chat(userMessage: String): String = withContext(Dispatchers.IO) {
        val conv = conversation ?: error("Engine not initialized")
        val prompt = buildChatPrompt(userMessage)
        val response = conv.sendMessage(Contents.of(Content.Text(prompt)))
        response.toString().replace("```", "").trim()
    }

    /** Streaming chat — emits chunks of the AI reply as they're generated. */
    fun chatStream(userMessage: String): Flow<String> {
        val conv = conversation ?: error("Engine not initialized")
        val prompt = buildChatPrompt(userMessage)
        return conv.sendMessageAsync(Contents.of(Content.Text(prompt)))
            .map { msg -> msg.toString().replace("```", "") }
            .flowOn(Dispatchers.IO)
    }

    private fun buildChatPrompt(userMessage: String): String =
        if (!chatPrimed) {
            chatPrimed = true
            "You are a friendly AI assistant for visually impaired users. " +
            "Your responses will be read aloud by text-to-speech, so keep them brief " +
            "(1–3 sentences when possible), conversational, and avoid lists or markdown.\n\n" +
            "User: $userMessage"
        } else {
            userMessage
        }

    /** Called when entering chat mode — gives chat a clean conversation. */
    fun resetChat() {
        resetConversation()
    }

    /** Called when entering reading mode — gives reading features a clean conversation. */
    fun resetReading() {
        resetConversation()
    }

    // ── Reading features (one-shot — each call uses a fresh conversation) ──
    suspend fun formatAsHtml(ocrText: String): String = withContext(Dispatchers.IO) {
        resetConversation()
        sendText(
            "You are a reading assistant for visually impaired users.\n" +
            "The following text was extracted from a document image via OCR.\n" +
            "Format it as clean, accessible HTML using <h1>, <h2>, <p>, <strong>, <em>, <ul>, <ol>, <li> tags.\n" +
            "Return ONLY the HTML — no markdown, no code blocks, no extra commentary.\n\n" +
            "Extracted text:\n$ocrText"
        )
    }

    suspend fun summarize(ocrText: String): String = withContext(Dispatchers.IO) {
        resetConversation()
        sendText(
            "Summarize the following document text in 3–5 clear sentences suitable for a visually impaired user.\n\n" +
            "Text:\n$ocrText"
        )
    }

    suspend fun translate(ocrText: String, targetLanguage: String): String = withContext(Dispatchers.IO) {
        resetConversation()
        sendText(
            "Translate the following text to $targetLanguage. Return only the translated text, nothing else.\n\n" +
            "Text:\n$ocrText"
        )
    }

    suspend fun askQuestion(question: String, ocrText: String): String = withContext(Dispatchers.IO) {
        resetConversation()
        sendText(
            "You are a helpful reading assistant. Answer the user's question based on the document below.\n\n" +
            "Document:\n$ocrText\n\n" +
            "Question: $question"
        )
    }

    private fun sendText(prompt: String): String {
        val conv = conversation ?: error("Engine not initialized")
        val response = conv.sendMessage(Contents.of(Content.Text(prompt)))
        return response.toString().replace("```html", "").replace("```", "").trim()
    }

    fun close() {
        try { (conversation as? AutoCloseable)?.close() } catch (t: Throwable) {}
        conversation = null
        engine = null
    }
}
