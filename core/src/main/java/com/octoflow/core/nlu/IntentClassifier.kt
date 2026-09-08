package com.octoflow.core.nlu

import kotlinx.serialization.Serializable

/**
 * Lightweight intent classification for fast-path command routing
 * Runs before heavy LLM inference for common commands
 */
@Serializable
data class ParsedIntent(
    val intent: IntentType,
    val confidence: Float,
    val targetApp: String?,
    val entities: Map<String, String>,
    val suggestedTemplate: String? = null,
    val requiresFullReasoning: Boolean = true
)

enum class IntentType {
    OPEN_APP,
    SEND_MESSAGE,
    CREATE_ITEM,
    SEARCH,
    EXTRACT_INFO,
    NAVIGATE,
    FILL_FORM,
    CAPTURE_SCREEN,
    SETTINGS_TOGGLE,
    MEDIA_CONTROL,
    UNKNOWN
}

/**
 * Entity types for extraction
 */
@Serializable
data class Entity(
    val type: EntityType,
    val value: String,
    val confidence: Float,
    val startIndex: Int,
    val endIndex: Int
)

enum class EntityType {
    CONTACT_NAME,
    PHONE_NUMBER,
    EMAIL_ADDRESS,
    MESSAGE_TEXT,
    SEARCH_QUERY,
    APP_NAME,
    FILE_NAME,
    DATE_TIME,
    LOCATION,
    URL,
    QUANTITY,
    CUSTOM
}

/**
 * TensorFlow Lite based intent classifier
 * Model: <1MB, runs in <10ms on CPU
 */
class IntentClassifier(private val context: android.content.Context) {
    
    private var interpreter: org.tensorflow.lite.Interpreter? = null
    private var labelMap: List<String> = emptyList()
    private var tokenizer: SimpleTokenizer = SimpleTokenizer()
    
    companion object {
        private const val MODEL_PATH = "intent_classifier.tflite"
        private const val LABELS_PATH = "intent_labels.txt"
        private const val MAX_SEQ_LENGTH = 64
        private const val VOCAB_SIZE = 30000
    }
    
    suspend fun initialize() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val modelBuffer = loadModelFile(MODEL_PATH)
            interpreter = org.tensorflow.lite.Interpreter(modelBuffer)
            labelMap = loadLabels(LABELS_PATH)
        } catch (e: Exception) {
            android.util.Log.w("IntentClassifier", "Failed to load model, using fallback", e)
        }
    }
    
    private fun loadModelFile(name: String): java.nio.ByteBuffer {
        val inputStream = context.assets.open(name)
        val fileSize = inputStream.available()
        val buffer = java.nio.ByteBuffer.allocateDirect(fileSize).order(java.nio.ByteOrder.nativeOrder())
        val bytes = ByteArray(fileSize)
        inputStream.read(bytes)
        buffer.put(bytes)
        buffer.rewind()
        inputStream.close()
        return buffer
    }
    
    private fun loadLabels(name: String): List<String> {
        val inputStream = context.assets.open(name)
        return inputStream.bufferedReader().readLines()
    }
    
    suspend fun classify(command: String): ParsedIntent {
        val normalized = command.lowercase().trim()
        
        // Fast path: exact keyword matching for high-confidence cases
        val fastPath = fastPathClassify(normalized)
        if (fastPath != null) return fastPath
        
        // ML path
        interpreter?.let { interpreter ->
            return try {
                val input = tokenize(normalized)
                val output = Array(1) { FloatArray(labelMap.size) }
                interpreter.run(input, output)
                
                val scores = output[0]
                val maxIndex = scores.indices.maxByOrNull { scores[it] } ?: 0
                val confidence = scores[maxIndex]
                val intent = IntentType.valueOf(labelMap[maxIndex].uppercase().replace("-", "_"))
                
                val entities = extractEntities(normalized, intent)
                
                ParsedIntent(
                    intent = intent,
                    confidence = confidence,
                    targetApp = extractTargetApp(normalized, intent),
                    entities = entities,
                    requiresFullReasoning = confidence < 0.85f || intent == IntentType.UNKNOWN
                )
            } catch (e: Exception) {
                android.util.Log.w("IntentClassifier", "Inference failed, using fallback", e)
                fallbackClassify(normalized)
            }
        }
        
        return fallbackClassify(normalized)
    }
    
    private fun fastPathClassify(command: String): ParsedIntent? {
        val openAppPatterns = listOf("open ", "launch ", "start ", "go to ")
        val sendMsgPatterns = listOf("send ", "text ", "message ", "whatsapp ", "sms ")
        val searchPatterns = listOf("search ", "find ", "look up ", "google ")
        
        return when {
            openAppPatterns.any { command.startsWith(it) } -> {
                val appName = openAppPatterns.map { command.removePrefix(it) }.first()
                ParsedIntent(
                    intent = IntentType.OPEN_APP,
                    confidence = 0.95f,
                    targetApp = resolvePackageName(appName),
                    entities = mapOf("app" to appName),
                    requiresFullReasoning = false
                )
            }
            sendMsgPatterns.any { command.contains(it) } -> {
                ParsedIntent(
                    intent = IntentType.SEND_MESSAGE,
                    confidence = 0.9f,
                    targetApp = detectMessagingApp(command),
                    entities = extractMessageEntities(command),
                    requiresFullReasoning = true
                )
            }
            searchPatterns.any { command.startsWith(it) } -> {
                val query = searchPatterns.map { command.removePrefix(it) }.first()
                ParsedIntent(
                    intent = IntentType.SEARCH,
                    confidence = 0.9f,
                    targetApp = detectSearchApp(command),
                    entities = mapOf("query" to query),
                    requiresFullReasoning = true
                )
            }
            else -> null
        }
    }
    
    private fun fallbackClassify(command: String): ParsedIntent {
        // Rule-based fallback
        return when {
            command.contains("open") || command.contains("launch") -> ParsedIntent(
                intent = IntentType.OPEN_APP,
                confidence = 0.7f,
                targetApp = extractTargetApp(command, IntentType.OPEN_APP),
                entities = mapOf("raw" to command),
                requiresFullReasoning = true
            )
            command.contains("send") || command.contains("message") -> ParsedIntent(
                intent = IntentType.SEND_MESSAGE,
                confidence = 0.7f,
                targetApp = detectMessagingApp(command),
                entities = mapOf("raw" to command),
                requiresFullReasoning = true
            )
            command.contains("search") || command.contains("find") -> ParsedIntent(
                intent = IntentType.SEARCH,
                confidence = 0.7f,
                targetApp = detectSearchApp(command),
                entities = mapOf("raw" to command),
                requiresFullReasoning = true
            )
            else -> ParsedIntent(
                intent = IntentType.UNKNOWN,
                confidence = 0.3f,
                targetApp = null,
                entities = mapOf("raw" to command),
                requiresFullReasoning = true
            )
        }
    }

    private fun extractTargetApp(command: String, intent: IntentType): String? {
        return when (intent) {
            IntentType.OPEN_APP -> {
                val openRegex = "(?i)(?:open|launch)\\s+([A-Za-z0-9\\s]+)".toRegex()
                val match = openRegex.find(command)?.groupValues?.get(1)?.trim()
                if (match != null) resolvePackageName(match) else null
            }
            IntentType.SEND_MESSAGE -> detectMessagingApp(command)
            IntentType.SEARCH -> detectSearchApp(command)
            else -> null
        }
    }
    
    private fun tokenize(text: String): FloatArray {
        val tokens = tokenizer.encode(text)
        val input = FloatArray(1 * MAX_SEQ_LENGTH)
        for (i in tokens.indices.take(MAX_SEQ_LENGTH)) {
            input[i] = tokens[i].toFloat() / VOCAB_SIZE
        }
        return input
    }
    
    private fun extractEntities(command: String, intent: IntentType): Map<String, String> {
        val entities = mutableMapOf<String, String>()
        
        // Contact names (capitalized words)
        val contactRegex = "\\b([A-Z][a-z]+(?:\\s+[A-Z][a-z]+)*)\\b".toRegex()
        contactRegex.findAll(command).forEach { match ->
            if (entities["contact"] == null) entities["contact"] = match.groupValues[1]
        }
        
        // Quoted text as message content
        val quotedRegex = "\"([^\"]+)\"".toRegex()
        quotedRegex.findAll(command).forEach { match ->
            entities["message"] = match.groupValues[1]
        }
        
        // Email
        val emailRegex = "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b".toRegex()
        emailRegex.findAll(command).forEach { match ->
            entities["email"] = match.value
        }
        
        // Phone
        val phoneRegex = "\\b\\d{3}[-.\\s]?\\d{3}[-.\\s]?\\d{4}\\b".toRegex()
        phoneRegex.findAll(command).forEach { match ->
            entities["phone"] = match.value
        }
        
        return entities
    }
    
    private fun extractMessageEntities(command: String): Map<String, String> {
        val entities = mutableMapOf<String, String>()
        
        // "to [contact]" pattern
        val toRegex = "(?i)\\bto\\s+([A-Za-z\\s]+)(?:\\s|\\.|,|$)".toRegex()
        toRegex.find(command)?.groupValues?.get(1)?.let { entities["contact"] = it.trim() }
        
        // "saying" or "that" pattern
        val msgRegex = "(?i)\\b(saying|that|message)\\s+[\"']?([^\"'.]+)[\"']?".toRegex()
        msgRegex.find(command)?.groupValues?.get(2)?.let { entities["message"] = it.trim() }
        
        return entities
    }
    
    private fun resolvePackageName(appName: String): String? {
        val knownApps = mapOf(
            "chrome" to "com.android.chrome",
            "gmail" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "youtube" to "com.google.android.youtube",
            "whatsapp" to "com.whatsapp",
            "telegram" to "org.telegram.messenger",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android",
            "instagram" to "com.instagram.android",
            "facebook" to "com.facebook.katana",
            "linkedin" to "com.linkedin.android",
            "spotify" to "com.spotify.music",
            "settings" to "com.android.settings",
            "camera" to "com.android.camera",
            "photos" to "com.google.android.apps.photos",
            "drive" to "com.google.android.apps.docs",
            "calendar" to "com.google.android.calendar",
            "clock" to "com.google.android.deskclock",
            "calculator" to "com.google.android.calculator"
        )
        
        return knownApps[appName.lowercase().trim()]
    }
    
    private fun detectMessagingApp(command: String): String? {
        val apps = mapOf(
            "whatsapp" to "com.whatsapp",
            "telegram" to "org.telegram.messenger",
            "signal" to "org.thoughtcrime.securesms",
            "messenger" to "com.facebook.orca",
            "messages" to "com.google.android.apps.messaging",
            "sms" to "com.google.android.apps.messaging"
        )
        
        return apps.entries.find { command.contains(it.key) }?.value
    }
    
    private fun detectSearchApp(command: String): String? {
        if (command.contains("chrome") || command.contains("web")) return "com.android.chrome"
        if (command.contains("play store") || command.contains("app")) return "com.android.vending"
        if (command.contains("youtube")) return "com.google.android.youtube"
        if (command.contains("maps")) return "com.google.android.apps.maps"
        return "com.android.chrome"
    }
    
    fun shutdown() {
        interpreter?.close()
        interpreter = null
    }
}

/**
 * Simple character-level tokenizer for fallback
 */
class SimpleTokenizer {
    private val charToIndex = mutableMapOf<Char, Int>()
    
    init {
        // Build vocabulary from common characters
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789 ,.!?'\"@#$%&*()-_=+[]{}|;:<>/\\`~"
        chars.forEachIndexed { index, char ->
            charToIndex[char] = index + 1  // 0 = padding/unknown
        }
    }
    
    fun encode(text: String): IntArray {
        return text.lowercase().map { charToIndex.getOrElse(it) { 0 } }.toIntArray()
    }
}