package com.octoflow.core.orchestrator

import com.octoflow.core.contract.*
import com.octoflow.core.memory.MemoryStore
import com.octoflow.core.memory.PatternMatcher
import com.octoflow.core.model.*
import com.octoflow.core.nlu.IntentClassifier
import com.octoflow.core.nlu.ParsedIntent
import com.octoflow.core.safety.DefaultSafetyEngine
import com.octoflow.core.safety.SafetyContext
import com.octoflow.core.safety.SafetyEngine
import com.octoflow.core.safety.SafetyResult
import com.octoflow.core.telemetry.ActionRecord
import com.octoflow.core.telemetry.TelemetryCollector
import com.octoflow.core.telemetry.TelemetryEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Enhanced task orchestrator with:
 * - Safety engine integration
 * - NLU fast-path
 * - Memory/pattern matching
 * - Telemetry
 * - Action verification & retry
 * - Multi-stage reasoning pipeline support
 */
class PipelineTaskOrchestratorImpl(
    private val reasoningEngine: ReasoningEngine,
    private val accessibilityProvider: AccessibilityProvider,
    private val visualFeedback: VisualFeedback?,
    private val hapticFeedback: HapticFeedback?,
    private val config: AgentConfig,
    private val safetyEngine: SafetyEngine = DefaultSafetyEngine(),
    private val memoryStore: MemoryStore? = null,
    private val intentClassifier: IntentClassifier? = null,
    private val telemetry: TelemetryCollector = com.octoflow.core.telemetry.NoOpTelemetryCollector
) : TaskOrchestrator {
    
    private val _activeTask = MutableStateFlow<Task?>(null)
    override val activeTask: Task? get() = _activeTask.value
    override val taskUpdates: Flow<Task> = _activeTask.filterNotNull()
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentJob: Job? = null
    private val patternMatcher = memoryStore?.let { PatternMatcher(it) }
    
    override suspend fun executeTask(task: Task): Task {
        val updatedTask = task.copy(
            status = TaskStatus.RUNNING,
            createdAt = System.currentTimeMillis()
        )
        _activeTask.value = updatedTask
        
        // Record telemetry
        telemetry.record(TelemetryEvent.TaskStarted(
            taskId = updatedTask.id,
            userCommand = updatedTask.userCommand,
            appPackage = "unknown", // Will be updated from first snapshot
            appActivity = "unknown",
            agentConfigHash = config.hashCode().toString()
        ))
        
        currentJob = scope.launch {
            runPipelineExecutionLoop(updatedTask)
        }
        
        currentJob?.join()
        return _activeTask.value ?: updatedTask
    }
    
    private suspend fun runPipelineExecutionLoop(task: Task) {
        var currentTask = task
        var stepCount = 0
        val history = mutableListOf<ReasoningStep>()
        var consecutiveFailures = 0
        var lastSnapshotHash = 0
        
        try {
            // Phase 0: NLU Fast-path
            val parsedIntent = intentClassifier?.classify(task.userCommand)
            if (parsedIntent != null && parsedIntent.confidence > 0.9 && !parsedIntent.requiresFullReasoning) {
                // Could execute template/shortcut directly
                telemetry.record(TelemetryEvent.StepExecuted(
                    taskId = currentTask.id,
                    stepNumber = 0,
                    action = ActionRecord("NLU_FAST_PATH", null, null, null, mapOf("intent" to parsedIntent.intent.name)),
                    latencyMs = 0,
                    uiElementCount = 0,
                    interactiveElementCount = 0
                ))
            }
            
            // Phase 1: Pattern matching from memory
            var suggestedPattern: com.octoflow.core.memory.UiPattern? = null
            if (memoryStore != null && patternMatcher != null) {
                val initialSnapshot = accessibilityProvider.getCurrentSnapshot()
                suggestedPattern = patternMatcher.findBestPattern(
                    task.userCommand,
                    initialSnapshot.packageName,
                    initialSnapshot.activityName,
                    initialSnapshot
                )
                suggestedPattern?.let { pattern ->
                    memoryStore.incrementPatternUse(pattern.id)
                    telemetry.record(TelemetryEvent.StepExecuted(
                        taskId = currentTask.id,
                        stepNumber = 0,
                        action = ActionRecord("PATTERN_MATCH", null, null, null, 
                            mapOf("patternId" to pattern.id, "goal" to pattern.goal)),
                        latencyMs = 0,
                        uiElementCount = 0,
                        interactiveElementCount = 0
                    ))
                }
            }
            
            while (stepCount < config.maxSteps && currentTask.status == TaskStatus.RUNNING) {
                // Check for cancellation
                if (currentJob?.isCancelled == true) {
                    currentTask = currentTask.copy(status = TaskStatus.CANCELLED)
                    break
                }
                
                // Perception: Get current UI state
                visualFeedback?.showThinking("Observing screen...")
                val perceptionStart = System.currentTimeMillis()
                val snapshot = accessibilityProvider.getCurrentSnapshot()
                val perceptionLatency = System.currentTimeMillis() - perceptionStart
                
                // Update task with current app context
                if (currentTask.userCommand != snapshot.packageName) {
                    currentTask = currentTask.copy(
                        userCommand = currentTask.userCommand // Keep original, but we have app context now
                    )
                }
                
                // Telemetry: UI snapshot
                telemetry.record(TelemetryEvent.UiSnapshotCaptured(
                    taskId = currentTask.id,
                    appPackage = snapshot.packageName,
                    appActivity = snapshot.activityName,
                    totalElements = countElements(snapshot.rootElement),
                    interactiveElements = countInteractive(snapshot.rootElement),
                    snapshotSizeChars = snapshot.rootElement.toString().length
                ))
                
                // Check for stuck detection (no UI change)
                val snapshotHash = snapshot.rootElement.hashCode()
                if (snapshotHash == lastSnapshotHash && stepCount > 0) {
                    consecutiveFailures++
                    if (consecutiveFailures >= 3) {
                        visualFeedback?.showError("Stuck: no UI change after 3 actions")
                        hapticFeedback?.error()
                        currentTask = currentTask.copy(
                            status = TaskStatus.FAILED,
                            error = "Stuck detection: UI unchanged for 3 consecutive steps",
                            completedAt = System.currentTimeMillis()
                        )
                        _activeTask.value = currentTask
                        break
                    }
                } else {
                    consecutiveFailures = 0
                    lastSnapshotHash = snapshotHash
                }
                
                // Reasoning: Get next action
                visualFeedback?.showThinking("Thinking...")
                val reasoningStart = System.currentTimeMillis()
                
                var reasoningStep: ReasoningStep
                
                // Use pattern if available and matches current state
                if (suggestedPattern != null && stepCount < suggestedPattern.actionSequence.size) {
                    val patternAction = suggestedPattern.actionSequence[stepCount]
                    reasoningStep = ReasoningStep(
                        thought = "Following learned pattern: ${suggestedPattern.goal}",
                        action = patternAction.toAction(),
                        confidence = suggestedPattern.successRate
                    )
                    suggestedPattern = null // Use once
                } else {
                    reasoningStep = reasoningEngine.reason(currentTask, snapshot, history)
                }
                
                val reasoningLatency = System.currentTimeMillis() - reasoningStart
                
                // Telemetry: Model inference
                telemetry.record(TelemetryEvent.ModelInference(
                    taskId = currentTask.id,
                    stepNumber = stepCount + 1,
                    modelType = config.modelType.name,
                    promptTokens = estimateTokens(buildPromptForTelemetry(currentTask, snapshot, history)),
                    completionTokens = estimateTokens(reasoningStep.thought),
                    latencyMs = reasoningLatency,
                    confidence = reasoningStep.confidence,
                    fallbackUsed = reasoningStep.confidence < 0.5f
                ))
                
                // Validate confidence
                if (reasoningStep.confidence < config.confidenceThreshold) {
                    visualFeedback?.showError("Low confidence: ${reasoningStep.thought}")
                    hapticFeedback?.error()
                    
                    // Try alternative actions
                    val alternative = reasoningStep.alternativeActions.firstOrNull()
                    if (alternative != null) {
                        // Would need to create new ReasoningStep with alternative
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
                
                // Safety check
                val safetyContext = SafetyContext(
                    currentTask = currentTask.userCommand,
                    currentApp = snapshot.packageName,
                    currentActivity = snapshot.activityName,
                    stepNumber = stepCount + 1,
                    recentActions = history.map { it.action },
                    uiSnapshot = com.octoflow.core.model.UiMinifier.minify(snapshot.rootElement)
                )
                
                val safetyResult = safetyEngine.checkAction(reasoningStep.action, safetyContext)
                
                when (safetyResult) {
                    is SafetyResult.Deny -> {
                        telemetry.record(TelemetryEvent.SafetyTriggered(
                            taskId = currentTask.id,
                            stepNumber = stepCount + 1,
                            policyType = safetyResult.policy::class.simpleName ?: "SafetyPolicy",
                            action = reasoningStep.action.toActionRecord(),
                            result = "DENY"
                        ))
                        visualFeedback?.showError("Safety: ${safetyResult.reason}")
                        hapticFeedback?.error()
                        currentTask = currentTask.copy(
                            status = TaskStatus.FAILED,
                            error = "Safety violation: ${safetyResult.reason}",
                            completedAt = System.currentTimeMillis()
                        )
                        _activeTask.value = currentTask
                        break
                    }
                    is SafetyResult.RequireUserConfirmation -> {
                        telemetry.record(TelemetryEvent.SafetyTriggered(
                            taskId = currentTask.id,
                            stepNumber = stepCount + 1,
                            policyType = "RequireUserConfirmation",
                            action = reasoningStep.action.toActionRecord(),
                            result = "CONFIRM_REQUIRED"
                        ))
                        visualFeedback?.showError("Confirm: ${safetyResult.message}")
                        // In real implementation, would show confirmation dialog
                        hapticFeedback?.heavy()
                        // For now, deny
                        currentTask = currentTask.copy(
                            status = TaskStatus.FAILED,
                            error = "Requires confirmation: ${safetyResult.message}",
                            completedAt = System.currentTimeMillis()
                        )
                        _activeTask.value = currentTask
                        break
                    }
                    is SafetyResult.ModifyAndAllow -> {
                        reasoningStep = reasoningStep.copy(action = safetyResult.modifiedAction)
                    }
                    is SafetyResult.Allow -> {}
                }
                
                // Visual feedback for action
                val actionToExecute = reasoningStep.action
                when (actionToExecute) {
                    is Action.Click -> visualFeedback?.showActionIndicator(actionToExecute.elementId, actionToExecute)
                    is Action.SetText -> visualFeedback?.showActionIndicator(actionToExecute.elementId, actionToExecute)
                    is Action.Swipe -> visualFeedback?.showActionIndicator(actionToExecute.elementId, actionToExecute)
                    else -> {}
                }
                
                hapticFeedback?.light()
                
                // Action: Execute with verification
                val actionStart = System.currentTimeMillis()
                var actionResult = accessibilityProvider.executeAction(reasoningStep.action)
                val actionLatency = System.currentTimeMillis() - actionStart
                
                // Verify action had effect
                var retryCount = 0
                while (!actionResult.success && retryCount < 3) {
                    retryCount++
                    telemetry.record(TelemetryEvent.ActionFailed(
                        taskId = currentTask.id,
                        stepNumber = stepCount + 1,
                        action = reasoningStep.action.toActionRecord(),
                        error = actionResult.errorMessage ?: "Unknown error",
                        retryCount = retryCount
                    ))
                    
                    visualFeedback?.showError("Retry $retryCount: ${actionResult.errorMessage}")
                    delay(500L * retryCount) // Exponential backoff
                    
                    // Try alternative if available
                    val alternative = reasoningStep.alternativeActions.firstOrNull()
                    if (alternative != null) {
                        telemetry.record(TelemetryEvent.StepRetried(
                            taskId = currentTask.id,
                            stepNumber = stepCount + 1,
                            originalAction = reasoningStep.action.toActionRecord(),
                            retryAction = alternative.toActionRecord(),
                            attempt = retryCount
                        ))
                        reasoningStep = reasoningStep.copy(action = alternative)
                        actionResult = accessibilityProvider.executeAction(alternative)
                    } else {
                        actionResult = accessibilityProvider.executeAction(reasoningStep.action)
                    }
                }
                
                // Record step
                history.add(reasoningStep)
                stepCount++
                
                telemetry.record(TelemetryEvent.StepExecuted(
                    taskId = currentTask.id,
                    stepNumber = stepCount,
                    action = reasoningStep.action.toActionRecord(),
                    latencyMs = actionLatency,
                    uiElementCount = countElements(snapshot.rootElement),
                    interactiveElementCount = countInteractive(snapshot.rootElement)
                ))
                
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
                    
                    // Record successful pattern
                    if (memoryStore != null && history.size >= 2) {
                        val pattern = buildPatternFromHistory(currentTask, history, snapshot)
                        memoryStore.recordPattern(pattern)
                    }
                    
                    break
                }
                
                // Check for action failure after retries
                if (!actionResult.success) {
                    val errorMsg = actionResult.errorMessage ?: "Unknown error"
                    visualFeedback?.showError("Action failed: $errorMsg")
                    hapticFeedback?.error()
                    
                    if (stepCount >= config.maxSteps) {
                        currentTask = currentTask.copy(
                            status = TaskStatus.FAILED,
                            error = "Max steps reached: $errorMsg",
                            completedAt = System.currentTimeMillis()
                        )
                        _activeTask.value = currentTask
                        break
                    }
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
            
            // Final telemetry
            telemetry.record(TelemetryEvent.TaskCompleted(
                taskId = currentTask.id,
                totalSteps = stepCount,
                durationMs = System.currentTimeMillis() - currentTask.createdAt,
                success = currentTask.status == TaskStatus.COMPLETED,
                finalError = currentTask.error
            ))
            
        } catch (e: Exception) {
            currentTask = currentTask.copy(
                status = TaskStatus.FAILED,
                error = "Execution error: ${e.message}",
                completedAt = System.currentTimeMillis()
            )
            _activeTask.value = currentTask
            visualFeedback?.showError("Error: ${e.message}")
            hapticFeedback?.error()
            
            telemetry.record(TelemetryEvent.TaskCompleted(
                taskId = currentTask.id,
                totalSteps = stepCount,
                durationMs = System.currentTimeMillis() - currentTask.createdAt,
                success = false,
                finalError = e.message
            ))
        } finally {
            currentJob = null
            telemetry.flush()
        }
    }
    
    private fun buildPatternFromHistory(task: Task, history: List<ReasoningStep>, finalSnapshot: UiSnapshot): com.octoflow.core.memory.UiPattern {
        // Extract goal from command (simple heuristic)
        val goal = when {
            task.userCommand.contains("login", ignoreCase = true) -> "login"
            task.userCommand.contains("send", ignoreCase = true) && task.userCommand.contains("message", ignoreCase = true) -> "send_message"
            task.userCommand.contains("post", ignoreCase = true) -> "post_content"
            task.userCommand.contains("search", ignoreCase = true) -> "search"
            task.userCommand.contains("open", ignoreCase = true) -> "open_app"
            else -> "custom_${task.id.take(8)}"
        }
        
        return com.octoflow.core.memory.UiPattern(
            appPackage = finalSnapshot.packageName,
            activityName = finalSnapshot.activityName,
            goal = goal,
            triggerPhrases = listOf(task.userCommand),
            actionSequence = history.map { it.action.toActionRecord() },
            preconditions = extractPreconditions(finalSnapshot),
            successRate = if (task.status == TaskStatus.COMPLETED) 1.0f else 0.0f,
            avgSteps = history.size,
            avgDurationMs = System.currentTimeMillis() - task.createdAt
        )
    }
    
    private fun extractPreconditions(snapshot: UiSnapshot): List<com.octoflow.core.memory.PatternPrecondition> {
        val preconditions = mutableListOf<com.octoflow.core.memory.PatternPrecondition>()
        
        // Add key elements as preconditions
        val keyElements = findKeyElements(snapshot.rootElement)
        for (element in keyElements.take(3)) {
            element.text?.let { text ->
                if (text.isNotBlank()) {
                    preconditions.add(com.octoflow.core.memory.PatternPrecondition(
                        type = com.octoflow.core.memory.PreconditionType.ELEMENT_EXISTS,
                        value = text
                    ))
                }
            }
            element.contentDescription?.let { desc ->
                if (desc.isNotBlank()) {
                    preconditions.add(com.octoflow.core.memory.PatternPrecondition(
                        type = com.octoflow.core.memory.PreconditionType.ELEMENT_EXISTS,
                        value = desc
                    ))
                }
            }
        }
        
        return preconditions
    }
    
    private fun findKeyElements(root: UiElement): List<UiElement> {
        val keyElements = mutableListOf<UiElement>()
        collectKeyElements(root, keyElements)
        return keyElements
    }
    
    private fun collectKeyElements(element: UiElement, list: MutableList<UiElement>) {
        if (element.isClickable || element.isEditable || element.isScrollable) {
            list.add(element)
        }
        element.children.forEach { collectKeyElements(it, list) }
    }
    
    private fun countElements(element: UiElement?): Int {
        return element?.let { 1 + it.children.sumOf { countElements(it) } } ?: 0
    }
    
    private fun countInteractive(element: UiElement?): Int {
        return element?.let {
            val self = if (it.isClickable || it.isEditable || it.isScrollable) 1 else 0
            self + it.children.sumOf { countInteractive(it) }
        } ?: 0
    }
    
    private fun buildPromptForTelemetry(task: Task, snapshot: UiSnapshot, history: List<ReasoningStep>): String {
        return com.octoflow.core.model.UiMinifier.minify(snapshot.rootElement)
    }
    
    private fun estimateTokens(text: String): Int {
        return (text.length / 4).coerceAtLeast(1) // Rough estimate
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
        kotlinx.coroutines.runBlocking {
            runCatching { telemetry.flush() }
        }
    }
}

/**
 * Extension to convert Action to ActionRecord
 */
fun com.octoflow.core.model.Action.toActionRecord(): com.octoflow.core.telemetry.ActionRecord {
    return com.octoflow.core.telemetry.ActionRecord(
        type = this::class.simpleName?.removeSuffix("Action") ?: "UNKNOWN",
        elementId = when (this) {
            is com.octoflow.core.model.Action.Click -> elementId
            is com.octoflow.core.model.Action.LongClick -> elementId
            is com.octoflow.core.model.Action.Swipe -> elementId
            is com.octoflow.core.model.Action.SetText -> elementId
            is com.octoflow.core.model.Action.Scroll -> elementId
            else -> null
        },
        elementText = null,
        elementClass = null,
        parameters = when (this) {
            is com.octoflow.core.model.Action.SetText -> mapOf("text" to text, "clearFirst" to clearFirst.toString())
            is com.octoflow.core.model.Action.Swipe -> mapOf("direction" to direction.name, "distance" to distance.toString())
            is com.octoflow.core.model.Action.Scroll -> mapOf("direction" to direction.name, "amount" to amount.toString())
            is com.octoflow.core.model.Action.OpenApp -> mapOf("packageName" to packageName)
            is com.octoflow.core.model.Action.Wait -> mapOf("millis" to millis.toString())
            is com.octoflow.core.model.Action.Done -> mapOf("result" to (result ?: ""))
            else -> emptyMap()
        }
    )
}

fun com.octoflow.core.telemetry.ActionRecord.toAction(): com.octoflow.core.model.Action {
    return when (type) {
        "Click" -> com.octoflow.core.model.Action.Click(elementId ?: 0)
        "LongClick" -> com.octoflow.core.model.Action.LongClick(elementId ?: 0)
        "Swipe" -> com.octoflow.core.model.Action.Swipe(
            elementId ?: 0,
            com.octoflow.core.model.SwipeDirection.valueOf(parameters["direction"] ?: "DOWN"),
            parameters["distance"]?.toIntOrNull() ?: 500
        )
        "SetText" -> com.octoflow.core.model.Action.SetText(
            elementId ?: 0,
            parameters["text"] ?: "",
            parameters["clearFirst"]?.toBooleanStrictOrNull() ?: true
        )
        "Scroll" -> com.octoflow.core.model.Action.Scroll(
            elementId ?: 0,
            com.octoflow.core.model.ScrollDirection.valueOf(parameters["direction"] ?: "DOWN"),
            parameters["amount"]?.toIntOrNull() ?: 10
        )
        "OpenApp" -> com.octoflow.core.model.Action.OpenApp(parameters["packageName"] ?: "")
        "Wait" -> com.octoflow.core.model.Action.Wait(parameters["millis"]?.toIntOrNull() ?: 500)
        "Back" -> com.octoflow.core.model.Action.Back()
        "Home" -> com.octoflow.core.model.Action.Home()
        "Done" -> com.octoflow.core.model.Action.Done(parameters["result"])
        else -> com.octoflow.core.model.Action.Wait(1000)
    }
}