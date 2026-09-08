package com.octoflow.agent

import android.app.Application
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import com.octoflow.accessibility.provider.AccessibilityProviderImpl
import com.octoflow.accessibility.service.OctoAccessibilityService
import com.octoflow.core.contract.*
import com.octoflow.core.memory.InMemoryMemoryStore
import com.octoflow.core.memory.MemoryStore
import com.octoflow.core.model.*
import com.octoflow.core.nlu.IntentClassifier
import com.octoflow.core.orchestrator.PipelineTaskOrchestratorImpl
import com.octoflow.core.safety.DefaultSafetyEngine
import com.octoflow.core.safety.SafetyEngine
import com.octoflow.core.telemetry.BufferedTelemetryCollector
import com.octoflow.core.telemetry.NoOpTelemetryCollector
import com.octoflow.core.telemetry.TelemetryCollector
import com.octoflow.core.telemetry.TelemetryConfig
import com.octoflow.core.voice.AndroidVoiceInputProvider
import com.octoflow.llm.provider.ReasoningEngineFactory
import com.octoflow.ui.feedback.AndroidHapticFeedback
import com.octoflow.ui.feedback.OverlayVisualFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Main OctoFlow Agent - ties all components together
 * Enhanced with safety, memory, NLU, telemetry, and pipeline orchestration
 */
class OctoFlowAgentImpl(
    private val context: Context,
    private val config: AgentConfig = AgentConfig()
) : OctoFlowAgent {
    
    private val reasoningEngine = ReasoningEngineFactory.create(context, config)
    private val accessibilityProvider = AccessibilityProviderImpl(context)
    private val voiceInputProvider = AndroidVoiceInputProvider(context)
    private val hapticFeedback = AndroidHapticFeedback(context)
    private var visualFeedback: OverlayVisualFeedback? = null
    private var taskOrchestrator: TaskOrchestrator? = null
    
    // Enhanced components
    private val safetyEngine: SafetyEngine = DefaultSafetyEngine()
    private val memoryStore: MemoryStore = InMemoryMemoryStore()
    private val intentClassifier = IntentClassifier(context)
    private val telemetry: TelemetryCollector = if (config.enableTelemetry) {
        BufferedTelemetryCollector(
            storage = createTelemetryStorage(),
            config = TelemetryConfig()
        )
    } else {
        NoOpTelemetryCollector
    }
    
    private val _agentState = MutableStateFlow<AgentState>(AgentState.IDLE)
    override val agentState = _agentState.asStateFlow()
    private var lastExecutedTask: Task? = null
    override val currentTask: Task? get() = taskOrchestrator?.activeTask ?: lastExecutedTask
    
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var isInitialized = false
    
    override suspend fun start() {
        if (isInitialized) return
        
        _agentState.value = AgentState.IDLE
        
        // Initialize reasoning engine
        reasoningEngine.initialize(config)
        
        // Initialize NLU classifier
        scope.launch {
            intentClassifier.initialize()
        }
        
        // Initialize visual feedback (requires overlay permission)
        if (config.enableVisualFeedback) {
            initVisualFeedback()
        }
        
        // Create enhanced task orchestrator
        taskOrchestrator = PipelineTaskOrchestratorImpl(
            reasoningEngine = reasoningEngine,
            accessibilityProvider = accessibilityProvider,
            visualFeedback = visualFeedback,
            hapticFeedback = if (config.hapticFeedback) hapticFeedback else null,
            config = config,
            safetyEngine = safetyEngine,
            memoryStore = memoryStore,
            intentClassifier = intentClassifier,
            telemetry = telemetry
        )
        
        // Start observing UI updates
        accessibilityProvider.startObserving()
        
        isInitialized = true
    }
    
    private fun initVisualFeedback() {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val accessibilityService = OctoAccessibilityService.getInstance()
        
        visualFeedback = OverlayVisualFeedback(
            context = context,
            windowManager = windowManager,
            accessibilityService = accessibilityService
        )
    }
    
    private fun createTelemetryStorage(): com.octoflow.core.telemetry.TelemetryStorage {
        // TODO: Implement Room-based storage
        return object : com.octoflow.core.telemetry.TelemetryStorage {
            override suspend fun writeAll(events: List<com.octoflow.core.telemetry.TelemetryEvent>) {}
            override suspend fun readAll(): List<com.octoflow.core.telemetry.TelemetryRecord> = emptyList()
            override suspend fun deleteBefore(timestamp: Long) {}
            override suspend fun vacuum() {}
            override suspend fun getDatabaseSize(): Long = 0
            override suspend fun clear() {}
        }
    }
    
    override suspend fun stop() {
        scope.cancel()
        (taskOrchestrator as? PipelineTaskOrchestratorImpl)?.shutdown()
        reasoningEngine.shutdown()
        voiceInputProvider.stopListening()
        intentClassifier.shutdown()
        accessibilityProvider.stopObserving()
        visualFeedback?.destroy()
        telemetry.flush()
        isInitialized = false
        _agentState.value = AgentState.IDLE
    }
    
    override suspend fun processVoiceCommand(command: String): Task {
        if (!isInitialized) {
            start()
        }
        
        _agentState.value = AgentState.PROCESSING
        
        val task = Task(
            id = UUID.randomUUID().toString(),
            userCommand = command
        )
        
        return try {
            val executedTask = taskOrchestrator!!.executeTask(task)
            lastExecutedTask = executedTask
            executedTask
        } finally {
            _agentState.value = AgentState.IDLE
        }
    }
    
    /**
     * Start voice listening and process command
     */
    suspend fun listenAndExecute(): Task {
        _agentState.value = AgentState.LISTENING
        
        val result = voiceInputProvider.startListening()
            .firstOrNull { it.isFinal && it.text.isNotBlank() }
        
        return if (result != null) {
            processVoiceCommand(result.text)
        } else {
            _agentState.value = AgentState.IDLE
            Task(
                id = UUID.randomUUID().toString(),
                userCommand = "",
                status = TaskStatus.FAILED,
                error = "No voice input received"
            )
        }
    }
    
    /**
     * Check if all required permissions are granted
     */
    fun checkPermissions(): PermissionsStatus {
        val accessibilityEnabled = accessibilityProvider.isAccessibilityEnabled()
        val overlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(context)
        } else true
        
        return PermissionsStatus(
            accessibilityEnabled = accessibilityEnabled,
            overlayPermission = overlayPermission,
            microphonePermission = checkMicrophonePermission()
        )
    }
    
    private fun checkMicrophonePermission(): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Request missing permissions
     */
    fun requestPermissions() {
        val status = checkPermissions()
        
        if (!status.accessibilityEnabled) {
            accessibilityProvider.requestAccessibilityPermission()
        }
        
        if (!status.overlayPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                .apply { setData(android.net.Uri.parse("package:${context.packageName}")) }
                .apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
        }
    }
    
    /**
     * Execute a shortcut by trigger phrase
     */
    suspend fun executeShortcut(triggerPhrase: String): Task {
        val shortcut = memoryStore.getShortcut(triggerPhrase)
        return shortcut?.let {
            memoryStore.incrementShortcutUse(it.id)
            telemetry.record(com.octoflow.core.telemetry.TelemetryEvent.ShortcutUsed(
                shortcutId = it.id,
                triggerPhrase = it.triggerPhrase,
                expandedCommand = it.expandedCommand
            ))
            processVoiceCommand(it.expandedCommand)
        } ?: Task(
            id = UUID.randomUUID().toString(),
            userCommand = triggerPhrase,
            status = TaskStatus.FAILED,
            error = "Shortcut not found: $triggerPhrase"
        )
    }
    
    /**
     * Create a new shortcut
     */
    suspend fun createShortcut(
        triggerPhrase: String,
        expandedCommand: String,
        parameters: List<com.octoflow.core.memory.ShortcutParameter> = emptyList()
    ) {
        val shortcut = com.octoflow.core.memory.Shortcut(
            triggerPhrase = triggerPhrase,
            expandedCommand = expandedCommand,
            parameters = parameters,
            origin = com.octoflow.core.memory.ShortcutOrigin.USER_DEFINED
        )
        memoryStore.saveShortcut(shortcut)
    }
    
    /**
     * Get all shortcuts
     */
    suspend fun getAllShortcuts(): List<com.octoflow.core.memory.Shortcut> {
        return memoryStore.getAllShortcuts()
    }
    
    /**
     * Delete a shortcut
     */
    suspend fun deleteShortcut(id: String) {
        memoryStore.deleteShortcut(id)
    }
    
    /**
     * Get recent episodes for debugging/analysis
     */
    suspend fun getRecentEpisodes(limit: Int = 20): List<com.octoflow.core.memory.Episode> {
        return memoryStore.getRecentEpisodes(limit)
    }
    
    /**
     * Export telemetry data
     */
    suspend fun exportTelemetry(format: com.octoflow.core.telemetry.ExportFormat): String {
        return telemetry.export(format)
    }
    
    /**
     * Export memory (episodes, patterns, shortcuts)
     */
    suspend fun exportMemory(): com.octoflow.core.memory.MemoryExport {
        return memoryStore.exportAll()
    }
    
    /**
     * Import memory
     */
    suspend fun importMemory(export: com.octoflow.core.memory.MemoryExport) {
        memoryStore.importAll(export)
    }
    
    /**
     * Add custom safety policy
     */
    fun addSafetyPolicy(policy: com.octoflow.core.safety.SafetyPolicy) {
        safetyEngine.registerPolicy(policy)
    }
    
    /**
     * Remove safety policy
     */
    val agentConfig: AgentConfig get() = config

    suspend fun cancelTask(taskId: String) {
        taskOrchestrator?.cancelTask(taskId)
    }

    data class PermissionsStatus(
        val accessibilityEnabled: Boolean,
        val overlayPermission: Boolean,
        val microphonePermission: Boolean
    ) {
        val allGranted: Boolean
            get() = accessibilityEnabled && overlayPermission && microphonePermission
    }
}

/**
 * Application class for dependency initialization
 */
class OctoFlowApplication : Application() {
    
    private var agent: OctoFlowAgentImpl? = null
    private var config = AgentConfig()
    
    override fun onCreate() {
        super.onCreate()
        agent = OctoFlowAgentImpl(this, config)
    }
    
    fun getAgent(): OctoFlowAgentImpl = agent!!
    
    fun setConfig(newConfig: AgentConfig) {
        config = newConfig
        agent = OctoFlowAgentImpl(this, config)
    }
}