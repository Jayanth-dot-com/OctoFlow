package com.octoflow.core.model

import kotlinx.serialization.Serializable

/**
 * Represents a single UI element from the accessibility tree
 */
@Serializable
data class UiElement(
    val id: Int,
    val className: String,
    val text: String?,
    val contentDescription: String?,
    val bounds: Rect,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isFocused: Boolean,
    val isEnabled: Boolean,
    val isVisible: Boolean = true,
    val hint: String? = null,
    val inputType: Int = 0,
    val resourceId: String? = null,  // android:id/@id/... for stable identification
    val children: List<UiElement> = emptyList()
)

@Serializable
data class Rect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Complete UI state snapshot from AccessibilityService
 */
@Serializable
data class UiSnapshot(
    val timestamp: Long,
    val packageName: String,
    val activityName: String,
    val rootElement: UiElement,
    val activeElementIds: List<Int> = emptyList()
)

/**
 * Action types the LLM can command
 */
@Serializable
sealed interface Action {
    @Serializable
    data class Click(val elementId: Int) : Action
    
    @Serializable
    data class LongClick(val elementId: Int) : Action
    
    @Serializable
    data class Swipe(
        val elementId: Int,
        val direction: SwipeDirection,
        val distance: Int = 500
    ) : Action
    
    @Serializable
    data class SetText(
        val elementId: Int,
        val text: String,
        val clearFirst: Boolean = true
    ) : Action
    
    @Serializable
    data class Scroll(
        val elementId: Int,
        val direction: ScrollDirection,
        val amount: Int = 10
    ) : Action
    
    @Serializable
    data class Back(val unused: String = "") : Action
    
    @Serializable
    data class Home(val unused: String = "") : Action
    
    @Serializable
    data class Wait(val millis: Int = 500) : Action
    
    @Serializable
    data class Done(val result: String? = null) : Action
    
    @Serializable
    data class OpenApp(val packageName: String) : Action
}

enum class SwipeDirection { UP, DOWN, LEFT, RIGHT }
enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

/**
 * LLM reasoning output
 */
@Serializable
data class ReasoningStep(
    val thought: String,
    val action: Action,
    val confidence: Float,
    val alternativeActions: List<Action> = emptyList()
)

/**
 * Task definition
 */
@Serializable
data class Task(
    val id: String,
    val userCommand: String,
    val status: TaskStatus = TaskStatus.PENDING,
    val steps: List<ReasoningStep> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val error: String? = null
)

enum class TaskStatus { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED }

/**
 * Configuration for the agent
 */
@Serializable
data class AgentConfig(
    val maxSteps: Int = 50,
    val stepTimeoutMs: Int = 10000,
    val confidenceThreshold: Float = 0.6f,
    val enableVisualFeedback: Boolean = true,
    val hapticFeedback: Boolean = true,
    val enableTelemetry: Boolean = true,
    val modelType: ModelType = ModelType.MOCK,
    val language: String = "en"
)

enum class ModelType { GEMMA_2B, OCTOPUS_V2, CUSTOM, MOCK }