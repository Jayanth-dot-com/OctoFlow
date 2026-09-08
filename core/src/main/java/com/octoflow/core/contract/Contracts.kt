package com.octoflow.core.contract

import com.octoflow.core.model.Action
import com.octoflow.core.model.AgentConfig
import com.octoflow.core.model.ReasoningStep
import com.octoflow.core.model.Task
import com.octoflow.core.model.UiSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Interface for the LLM reasoning engine
 */
interface ReasoningEngine {
    suspend fun reason(
        task: Task,
        snapshot: UiSnapshot,
        history: List<ReasoningStep>
    ): ReasoningStep
    
    suspend fun initialize(config: AgentConfig)
    suspend fun shutdown()
    val isReady: Boolean
}

/**
 * Interface for the accessibility service
 */
interface AccessibilityProvider {
    suspend fun getCurrentSnapshot(): UiSnapshot
    suspend fun executeAction(action: Action): ActionResult
    val uiUpdates: Flow<UiSnapshot>
    
    fun requestAccessibilityPermission(): Boolean
    fun isAccessibilityEnabled(): Boolean
}

@Serializable
data class ActionResult(
    val success: Boolean,
    val errorMessage: String? = null,
    val newSnapshot: UiSnapshot? = null
)

/**
 * Interface for voice input
 */
interface VoiceInputProvider {
    suspend fun startListening(): Flow<VoiceResult>
    suspend fun stopListening()
    val isListening: Boolean
}

@Serializable
data class VoiceResult(
    val text: String,
    val confidence: Float,
    val isFinal: Boolean
)

/**
 * Interface for task orchestration
 */
interface TaskOrchestrator {
    suspend fun executeTask(task: Task): Task
    suspend fun cancelTask(taskId: String)
    val activeTask: Task?
    val taskUpdates: Flow<Task>
}

/**
 * Interface for visual feedback overlay
 */
interface VisualFeedback {
    fun showActionIndicator(elementId: Int, action: Action)
    fun showThinking(text: String)
    fun hide()
    fun showError(message: String)
}

/**
 * Interface for haptic feedback
 */
interface HapticFeedback {
    fun light()
    fun medium()
    fun heavy()
    fun success()
    fun error()
}

/**
 * Main agent coordinator interface
 */
interface OctoFlowAgent {
    suspend fun start()
    suspend fun stop()
    suspend fun processVoiceCommand(command: String): Task
    val currentTask: Task?
    val agentState: Flow<AgentState>
}

enum class AgentState {
    IDLE,
    LISTENING,
    PROCESSING,
    EXECUTING,
    WAITING_FOR_PERMISSION,
    ERROR
}