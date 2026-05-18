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
    private var menuContext: String = ""

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
            if (menuContext.isNotBlank()) {
                val ctx = menuContext
                menuContext = "" // consume — won't apply to follow-up turns (already in conversation history)
                "You are helping a visually impaired user with a restaurant menu they just scanned. " +
                "Answer in ONE short, natural sentence for text-to-speech. " +
                "Speak prices in words (e.g., 'one hundred twenty rupees'). No lists, no markdown.\n\n" +
                "MENU TEXT:\n$ctx\n\n" +
                "User: $userMessage"
            } else {
                "You are a friendly AI assistant for visually impaired users. " +
                "Your responses will be read aloud by text-to-speech, so keep them brief " +
                "(1–3 sentences when possible), conversational, and avoid lists or markdown.\n\n" +
                "User: $userMessage"
            }
        } else {
            userMessage
        }

    /** Called when navigating from Agent menu scan into the Chat screen. */
    fun primeChatWithMenu(menuText: String) {
        resetConversation()
        menuContext = menuText
        chatPrimed = false
    }

    /** Called when entering chat mode — gives chat a clean conversation. */
    fun resetChat() {
        resetConversation()
        menuContext = ""
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

    // ── Agent path ────────────────────────────────────────────────────
    /**
     * Analyzes scanned text and decides what agentic action (if any) to take.
     * Returns a structured response we parse deterministically — more reliable
     * than native tool-calling for Gemma 4 E2B per Google's field eval.
     *
     * Structured format Gemma is asked to emit:
     *   SPEAK: <message to speak to the user>
     *   ACTION: <none | calendar | reminder | warn>
     *   TITLE: <short title for calendar/reminder>
     *   DATE: <ISO 8601 date or empty>
     *   ASK: <yes | no>   ← whether to confirm with user before acting
     */
    /** Follow-up Q&A about a previously-scanned menu. One short answer, no formatting. */
    suspend fun menuAnswer(question: String, menuText: String): String = withContext(Dispatchers.IO) {
        resetConversation()
        sendText(
            "You are answering a visually impaired user's question about a restaurant menu they just scanned.\n" +
            "Answer in ONE short sentence. Speak naturally — this will be read aloud by TTS. " +
            "Use words for prices (e.g., 'one hundred twenty rupees'). No lists, no markdown.\n\n" +
            "MENU:\n$menuText\n\n" +
            "QUESTION: $question"
        )
    }

    suspend fun agentAnalyze(ocrText: String): String = withContext(Dispatchers.IO) {
        resetConversation()
        val prompt = buildString {
            append("You are an assistant for blind users. A document was scanned and OCR'd.\n")
            append("Output EXACTLY these 5 lines, in order, nothing else, no markdown:\n\n")
            append("SPEAK: <a friendly, complete sentence to read aloud — include people's names, venue, amount, date in words>\n")
            append("ACTION: <none | calendar | reminder | warn | menu>\n")
            append("TITLE: <short title for the calendar event or reminder>\n")
            append("DATE: <ISO 8601 like 2026-06-14T18:00, or just a date 2026-06-14, or empty>\n")
            append("ASK: <yes or no>\n\n")
            append("EXAMPLES:\n\n")
            append("Wedding invitation for Priya and Arjun, June 14 2026 at Taj Palace Mumbai →\n")
            append("SPEAK: This is a wedding invitation. Priya and Arjun are getting married on June fourteenth, twenty twenty-six, at the Taj Palace in Mumbai. Want me to add it to your calendar?\n")
            append("ACTION: calendar\n")
            append("TITLE: Priya & Arjun's wedding\n")
            append("DATE: 2026-06-14T18:00\n")
            append("ASK: yes\n\n")
            append("BSNL electricity bill, ₹420, due May 25 2026 →\n")
            append("SPEAK: Four hundred twenty rupees. Due May twenty-fifth. This is your BSNL electricity bill. Want a reminder three days before?\n")
            append("ACTION: reminder\n")
            append("TITLE: BSNL bill ₹420\n")
            append("DATE: 2026-05-25\n")
            append("ASK: yes\n\n")
            append("Yogurt cup, expired May 12 2026 (today is May 15) →\n")
            append("SPEAK: This yogurt expired three days ago, on May twelfth. Do not eat this.\n")
            append("ACTION: warn\n")
            append("TITLE:\n")
            append("DATE:\n")
            append("ASK: no\n\n")
            append("Restaurant menu from Cafe Madras with 12 items →\n")
            append("SPEAK: This is a menu from Cafe Madras with about twelve South Indian items. What would you like to know about it?\n")
            append("ACTION: menu\n")
            append("TITLE: Cafe Madras menu\n")
            append("DATE:\n")
            append("ASK: no\n\n")
            append("RULES:\n")
            append("- ALWAYS include names, places, amounts, and dates in the SPEAK line if they appear in the text.\n")
            append("- Speak numbers and dates in words for TTS clarity.\n")
            append("- For warnings, never ask, just speak the warning clearly.\n")
            append("- For unknown documents, ACTION: none, ASK: no, and SPEAK a brief description.\n\n")
            append("OCR TEXT (analyze this now):\n")
            append(ocrText)
        }
        sendText(prompt)
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

/** Parsed agent decision from the model's structured output. */
data class AgentDecision(
    val speak: String,
    val action: AgentAction,
    val title: String,
    val dateIso: String,
    val ask: Boolean
)

enum class AgentAction { NONE, CALENDAR, REMINDER, WARN, MENU }

object AgentParser {
    fun parse(raw: String): AgentDecision {
        val cleaned = raw.replace("```", "").trim()
        val map = mutableMapOf<String, String>()
        cleaned.lines().forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                val key = line.substring(0, idx).trim().uppercase()
                val value = line.substring(idx + 1).trim()
                map[key] = value
            }
        }
        return AgentDecision(
            speak = map["SPEAK"]?.takeIf { it.isNotBlank() } ?: cleaned.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
            action = when (map["ACTION"]?.lowercase()) {
                "calendar" -> AgentAction.CALENDAR
                "reminder" -> AgentAction.REMINDER
                "warn"     -> AgentAction.WARN
                "menu"     -> AgentAction.MENU
                else        -> AgentAction.NONE
            },
            title = map["TITLE"].orEmpty().trim('"', '\''),
            dateIso = map["DATE"].orEmpty().trim('"', '\''),
            ask = map["ASK"]?.lowercase()?.startsWith("y") ?: false
        )
    }
}
