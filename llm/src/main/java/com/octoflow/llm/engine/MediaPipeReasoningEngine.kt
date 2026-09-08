package com.octoflow.llm.engine

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.octoflow.core.contract.ReasoningEngine
import com.octoflow.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * MediaPipe LLM Inference implementation for on-device reasoning
 */
class MediaPipeReasoningEngine(private val context: Context) : ReasoningEngine {
    
    private var llmInference: LlmInference? = null
    private var config: AgentConfig? = null
    private val json = Json { ignoreUnknownKeys = true }
    
    override val isReady: Boolean
        get() = llmInference != null
    
    override suspend fun initialize(agentConfig: AgentConfig) {
        config = agentConfig
        withContext(Dispatchers.IO) {
            try {
                val modelPath = getModelPath(agentConfig.modelType)
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(512)
                    .setTemperature(0.1f)
                    .setTopK(40)
                    .build()
                
                llmInference = LlmInference.createFromOptions(context, options)
            } catch (e: Exception) {
                Log.w("MediaPipeReasoning", "MediaPipe LLM initialization failed: ${e.message}")
            }
        }
    }
    
    private fun getModelPath(modelType: ModelType): String {
        return when (modelType) {
            ModelType.GEMMA_2B -> "gemma-2b-it-gpu-int4.task" // Downloaded to assets
            ModelType.OCTOPUS_V2 -> "octopus-v2.task"
            ModelType.CUSTOM -> "custom-model.task"
            ModelType.MOCK -> "mock"
        }
    }
    
    override suspend fun shutdown() {
        llmInference?.close()
        llmInference = null
    }
    
    override suspend fun reason(
        task: Task,
        snapshot: UiSnapshot,
        history: List<ReasoningStep>
    ): ReasoningStep {
        val llm = llmInference ?: return ReasoningStep(
            thought = "MediaPipe LLM model file not found in assets",
            action = Action.Done("LLM model asset missing"),
            confidence = 0f
        )
        
        val prompt = buildPrompt(task, snapshot, history)
        
        return withContext(Dispatchers.IO) {
            try {
                val response = llm.generateResponse(prompt)
                parseResponse(response, task)
            } catch (e: Exception) {
                ReasoningStep(
                    thought = "Error: ${e.message}",
                    action = Action.Wait(1000),
                    confidence = 0f
                )
            }
        }
    }
    
    private fun buildPrompt(task: Task, snapshot: UiSnapshot, history: List<ReasoningStep>): String {
        val minifiedUi = com.octoflow.core.model.UiMinifier.minify(snapshot.rootElement)
        
        val historyStr = if (history.isEmpty()) {
            "None"
        } else {
            history.mapIndexed { i, step ->
                "Step ${i + 1}: ${step.thought} -> ${step.action}"
            }.joinToString("\n")
        }
        
        return """
            You are OctoFlow, an on-device AI agent that controls Android apps via accessibility.
            
            TASK: "${task.userCommand}"
            
            CURRENT UI STATE:
            $minifiedUi
            
            EXECUTION HISTORY:
            $historyStr
            
            AVAILABLE ACTIONS:
            - CLICK elementId
            - LONG_CLICK elementId
            - SWIPE elementId direction(UP/DOWN/LEFT/RIGHT) distance
            - SET_TEXT elementId "text" clearFirst(true/false)
            - SCROLL elementId direction(UP/DOWN/LEFT/RIGHT) amount
            - BACK
            - HOME
            - WAIT milliseconds
            - OPEN_APP packageName
            - DONE "result message"
            
            RULES:
            1. Only interact with elements that exist in the UI state
            2. Use element IDs exactly as shown
            3. One action per step
            4. Prefer CLICK for buttons, SET_TEXT for input fields
            5. If task is complete, use DONE with a summary
            6. If stuck, try BACK or SWIPE to navigate
            
            Respond with JSON only:
            {
              "thought": "your reasoning",
              "action": {"type": "CLICK", "elementId": 123},
              "confidence": 0.9,
              "alternativeActions": []
            }
        """.trimIndent()
    }
    
    private fun parseResponse(response: String, task: Task): ReasoningStep {
        try {
            // Try to parse as JSON
            val parsed = json.decodeFromString<LlmResponse>(response)
            return ReasoningStep(
                thought = parsed.thought,
                action = parsed.action.toAction(),
                confidence = parsed.confidence,
                alternativeActions = parsed.alternativeActions?.map { it.toAction() } ?: emptyList()
            )
        } catch (e: Exception) {
            // Fallback: try to extract action from text
            return parseFallbackResponse(response)
        }
    }
    
    private fun parseFallbackResponse(response: String): ReasoningStep {
        // Simple regex-based fallback parsing
        val clickMatch = """CLICK\s+(\d+)""".toRegex().matchEntire(response)
        val setTextMatch = """SET_TEXT\s+(\d+)\s+"([^"]+)"""".toRegex().matchEntire(response)
        val swipeMatch = """SWIPE\s+(\d+)\s+(UP|DOWN|LEFT|RIGHT)""".toRegex().matchEntire(response)
        val doneMatch = """DONE\s+"([^"]*)"""".toRegex().matchEntire(response)
        val backMatch = "BACK".toRegex().matchEntire(response)
        val homeMatch = "HOME".toRegex().matchEntire(response)
        
        return when {
            clickMatch != null -> ReasoningStep(
                thought = "Clicked element ${clickMatch.groupValues[1]}",
                action = Action.Click(clickMatch.groupValues[1].toInt()),
                confidence = 0.7f
            )
            setTextMatch != null -> ReasoningStep(
                thought = "Set text on element ${setTextMatch.groupValues[1]}",
                action = Action.SetText(setTextMatch.groupValues[1].toInt(), setTextMatch.groupValues[2]),
                confidence = 0.7f
            )
            swipeMatch != null -> ReasoningStep(
                thought = "Swiped element ${swipeMatch.groupValues[1]} ${swipeMatch.groupValues[2]}",
                action = Action.Swipe(
                    swipeMatch.groupValues[1].toInt(),
                    SwipeDirection.valueOf(swipeMatch.groupValues[2])
                ),
                confidence = 0.7f
            )
            doneMatch != null -> ReasoningStep(
                thought = "Task completed",
                action = Action.Done(doneMatch.groupValues[1]),
                confidence = 0.9f
            )
            backMatch != null -> ReasoningStep(
                thought = "Going back",
                action = Action.Back(),
                confidence = 0.8f
            )
            homeMatch != null -> ReasoningStep(
                thought = "Going home",
                action = Action.Home(),
                confidence = 0.8f
            )
            else -> ReasoningStep(
                thought = "Could not parse response: $response",
                action = Action.Wait(1000),
                confidence = 0.1f
            )
        }
    }
    
    @Serializable
    private data class LlmResponse(
        val thought: String,
        val action: LlmAction,
        val confidence: Float,
        val alternativeActions: List<LlmAction>? = null
    )
    
    @Serializable
    private sealed class LlmAction {
        @Serializable
        data class Click(val elementId: Int) : LlmAction()
        @Serializable
        data class LongClick(val elementId: Int) : LlmAction()
        @Serializable
        data class Swipe(val elementId: Int, val direction: String, val distance: Int = 500) : LlmAction()
        @Serializable
        data class SetText(val elementId: Int, val text: String, val clearFirst: Boolean = true) : LlmAction()
        @Serializable
        data class Scroll(val elementId: Int, val direction: String, val amount: Int = 10) : LlmAction()
        @Serializable
        data class Back(val unused: String = "") : LlmAction()
        @Serializable
        data class Home(val unused: String = "") : LlmAction()
        @Serializable
        data class Wait(val millis: Int = 500) : LlmAction()
        @Serializable
        data class OpenApp(val packageName: String) : LlmAction()
        @Serializable
        data class Done(val result: String? = null) : LlmAction()
    }
    
    private fun LlmAction.toAction(): Action = when (this) {
        is LlmAction.Click -> Action.Click(elementId)
        is LlmAction.LongClick -> Action.LongClick(elementId)
        is LlmAction.Swipe -> Action.Swipe(elementId, SwipeDirection.valueOf(direction), distance)
        is LlmAction.SetText -> Action.SetText(elementId, text, clearFirst)
        is LlmAction.Scroll -> Action.Scroll(elementId, ScrollDirection.valueOf(direction), amount)
        is LlmAction.Back -> Action.Back()
        is LlmAction.Home -> Action.Home()
        is LlmAction.Wait -> Action.Wait(millis)
        is LlmAction.OpenApp -> Action.OpenApp(packageName)
        is LlmAction.Done -> Action.Done(result)
    }
}