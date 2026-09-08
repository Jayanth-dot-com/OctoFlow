package com.octoflow.core.orchestrator

import com.octoflow.core.contract.*
import com.octoflow.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Main task orchestrator that runs the perception-reasoning-action loop
 */
class TaskOrchestratorImpl(
    private val reasoningEngine: ReasoningEngine,
    private val accessibilityProvider: AccessibilityProvider,
    private val visualFeedback: VisualFeedback?,
    private val hapticFeedback: HapticFeedback?,
    private val config: AgentConfig
) : TaskOrchestrator {
    
    private val _activeTask = MutableStateFlow<Task?>(null)
    override val activeTask: Task? get() = _activeTask.value
    override val taskUpdates: Flow<Task> = _activeTask.filterNotNull()
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentJob: Job? = null
    private val stepChannel = Channel<ReasoningStep>(Channel.UNLIMITED)
    
    override suspend fun executeTask(task: Task): Task {
        val updatedTask = task.copy(
            status = TaskStatus.RUNNING,
            createdAt = System.currentTimeMillis()
        )
        _activeTask.value = updatedTask
        
        currentJob = scope.launch {
            runExecutionLoop(updatedTask)
        }
        
        currentJob?.join()
        return _activeTask.value ?: updatedTask
    }
    
    private suspend fun runExecutionLoop(task: Task) {
        var currentTask = task
        var stepCount = 0
        val history = mutableListOf<ReasoningStep>()
        
        try {
            while (stepCount < config.maxSteps && currentTask.status == TaskStatus.RUNNING) {
                // Check for cancellation
                if (currentJob?.isCancelled == true) {
                    currentTask = currentTask.copy(status = TaskStatus.CANCELLED)
                    break
                }
                
                // Perception: Get current UI state
                visualFeedback?.showThinking("Observing screen...")
                val snapshot = accessibilityProvider.getCurrentSnapshot()
                
                // Reasoning: Get next action from LLM
                visualFeedback?.showThinking("Thinking...")
                val reasoningStep = reasoningEngine.reason(currentTask, snapshot, history)
                
                // Validate confidence
                if (reasoningStep.confidence < config.confidenceThreshold) {
                    visualFeedback?.showError("Low confidence: ${reasoningStep.thought}")
                    hapticFeedback?.error()
                    
                    // Try alternative actions
                    val alternative = reasoningStep.alternativeActions.firstOrNull()
                    if (alternative != null) {
                        // Use alternative
                    } else {
                        currentTask = currentTask.copy(
                            status = TaskStatus.FAILED,
                            error = "Low confidence: ${reasoningStep.thought}",
                            completedAt = System.currentTimeMillis()
                        )
                        _activeTask.value = currentTask
                        break
                    }
                }
                
                // Visual feedback for action
                when (reasoningStep.action) {
                    is Action.Click -> visualFeedback?.showActionIndicator(reasoningStep.action.elementId, reasoningStep.action)
                    is Action.SetText -> visualFeedback?.showActionIndicator(reasoningStep.action.elementId, reasoningStep.action)
                    is Action.Swipe -> visualFeedback?.showActionIndicator(reasoningStep.action.elementId, reasoningStep.action)
                    else -> {}
                }
                
                hapticFeedback?.light()
                
                // Action: Execute the action
                val actionResult = accessibilityProvider.executeAction(reasoningStep.action)
                
                // Record step
                history.add(reasoningStep)
                stepCount++
                
                currentTask = currentTask.copy(steps = history.toList())
                _activeTask.value = currentTask
                
                // Check if done
                if (reasoningStep.action is Action.Done) {
                    currentTask = currentTask.copy(
                        status = TaskStatus.COMPLETED,
                        completedAt = System.currentTimeMillis()
                    )
                    _activeTask.value = currentTask
                    hapticFeedback?.success()
                    visualFeedback?.hide()
                    break
                }
                
                // Check for action failure
                if (!actionResult.success) {
                    val errorMsg = actionResult.errorMessage ?: "Unknown error"
                    visualFeedback?.showError("Action failed: $errorMsg")
                    hapticFeedback?.error()
                    
                    // Decide whether to retry or fail
                    if (stepCount >= config.maxSteps) {
                        currentTask = currentTask.copy(
                            status = TaskStatus.FAILED,
                            error = "Max steps reached: $errorMsg",
                            completedAt = System.currentTimeMillis()
                        )
                        _activeTask.value = currentTask
                        break
                    }
                    // Continue to next iteration (retry with new UI state)
                }
                
                // Small delay between steps
                delay(200)
            }
            
            // Max steps reached
            if (currentTask.status == TaskStatus.RUNNING) {
                currentTask = currentTask.copy(
                    status = TaskStatus.FAILED,
                    error = "Max steps (${config.maxSteps}) reached",
                    completedAt = System.currentTimeMillis()
                )
                _activeTask.value = currentTask
            }
            
        } catch (e: Exception) {
            currentTask = currentTask.copy(
                status = TaskStatus.FAILED,
                error = "Execution error: ${e.message}",
                completedAt = System.currentTimeMillis()
            )
            _activeTask.value = currentTask
            visualFeedback?.showError("Error: ${e.message}")
            hapticFeedback?.error()
        } finally {
            currentJob = null
        }
    }
    
    override suspend fun cancelTask(taskId: String) {
        currentJob?.cancel()
        val task = _activeTask.value
        if (task != null && task.id == taskId) {
            _activeTask.value = task.copy(
                status = TaskStatus.CANCELLED,
                completedAt = System.currentTimeMillis()
            )
        }
    }
    
    fun shutdown() {
        scope.cancel()
        currentJob?.cancel()
    }
}