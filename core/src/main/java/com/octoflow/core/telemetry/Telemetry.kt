package com.octoflow.core.telemetry

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.util.UUID

/**
 * Structured telemetry events for local debugging and opt-in analytics
 * All data stays on device unless user explicitly exports
 */
@Serializable
sealed interface TelemetryEvent {
    @Serializable
    data class TaskStarted(
        val eventId: String = UUID.randomUUID().toString(),
        val timestamp: Long = System.currentTimeMillis(),
        val taskId: String,
        val userCommand: String,
        val appPackage: String,
        val appActivity: String,
        val agentConfigHash: String
    ) : TelemetryEvent
    
    @Serializable
    data class StepExecuted(
        val eventId: String = UUID.randomUUID().toString(),
        val timestamp: Long = System.currentTimeMillis(),
        val taskId: String,
        val stepNumber: Int,
        val action: ActionRecord,
        val latencyMs: Long,
        val uiElementCount: Int,
        val interactiveElementCount: Int
    ) : TelemetryEvent
    
    @Serializable
    data class ActionFailed(
        val eventId: String = UUID.randomUUID().toString(),
        val timestamp: Long = System.currentTimeMillis(),
        val taskId: String,
        val stepNumber: Int,
        val action: ActionRecord,
        val error: String,
        val retryCount: Int
    ) : TelemetryEvent
    
    @Serializable
    data class StepRetried(
        val eventId: String = UUID.randomUUID().toString(),
        val timestamp: Long = System.currentTimeMillis(),
        val taskId: String,
        val stepNumber: Int,
        val originalAction: ActionRecord,
        val retryAction: ActionRecord,
        val attempt: Int
    ) : TelemetryEvent
    
    @Serializable
    data class TaskCompleted(
        val eventId: String = UUID.randomUUID().toString(),
        val timestamp: Long = System.currentTimeMillis(),
        val taskId: String,
        val totalSteps: Int,
        val durationMs: Long,
        val success: Boolean,
        val finalError: String?
    ) : TelemetryEvent
    
    @Serializable
    data class SafetyTriggered(
        val eventId: String = UUID.randomUUID().toString(),
        val timestamp: Long = System.currentTimeMillis(),
        val taskId: String,
        val stepNumber: Int,
        val policyType: String,
        val action: ActionRecord,
        val result: String  // DENY, CONFIRM_REQUIRED, MODIFIED
    ) : TelemetryEvent
    
    @Serializable
    data class ModelInference(
        val eventId: String = UUID.randomUUID().toString(),
        val timestamp: Long = System.currentTimeMillis(),
        val taskId: String,
        val stepNumber: Int,
        val modelType: String,
        val promptTokens: Int,
        val completionTokens: Int,
        val latencyMs: Long,
        val confidence: Float,
        val fallbackUsed: Boolean
    ) : TelemetryEvent
    
    @Serializable
    data class UiSnapshotCaptured(
        val eventId: String = UUID.randomUUID().toString(),
        val timestamp: Long = System.currentTimeMillis(),
        val taskId: String,
        val appPackage: String,
        val appActivity: String,
        val totalElements: Int,
        val interactiveElements: Int,
        val snapshotSizeChars: Int
    ) : TelemetryEvent
    
    @Serializable
    data class PermissionRequested(
        val eventId: String = UUID.randomUUID().toString(),
        val timestamp: Long = System.currentTimeMillis(),
        val permission: String,
        val granted: Boolean
    ) : TelemetryEvent
    
    @Serializable
    data class ShortcutUsed(
        val eventId: String = UUID.randomUUID().toString(),
        val timestamp: Long = System.currentTimeMillis(),
        val shortcutId: String,
        val triggerPhrase: String,
        val expandedCommand: String
    ) : TelemetryEvent
}

typealias ActionRecord = com.octoflow.core.memory.ActionRecord

/**
 * Telemetry collector - buffers events and writes to local storage
 */
interface TelemetryCollector {
    suspend fun record(event: TelemetryEvent)
    suspend fun flush()
    suspend fun export(format: ExportFormat): String  // JSON, CSV
    suspend fun clear()
    val isEnabled: Boolean
    fun setEnabled(enabled: Boolean)
}

enum class ExportFormat { JSON, CSV }

/**
 * Room-based local storage for telemetry
 */
@Serializable
data class TelemetryRecord(
    val id: Long = 0,  // Room auto-generates
    val eventType: String,
    val eventJson: String,
    val timestamp: Long,
    val taskId: String?
)

/**
 * Configuration for telemetry
 */
@Serializable
data class TelemetryConfig(
    val enabled: Boolean = true,
    val maxEventsInMemory: Int = 1000,
    val flushIntervalMs: Long = 30_000,
    val maxDatabaseSizeMb: Int = 50,
    val retentionDays: Int = 30,
    val exportOptIn: Boolean = false
)

/**
 * In-memory buffer with periodic flush
 */
class BufferedTelemetryCollector(
    private val storage: TelemetryStorage,
    private val config: TelemetryConfig
) : TelemetryCollector {
    
    private val buffer = mutableListOf<TelemetryEvent>()
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.Job())
    private var _enabled = config.enabled
    
    override val isEnabled: Boolean
        get() = _enabled
    
    override fun setEnabled(enabled: Boolean) {
        _enabled = enabled
    }
    
    override suspend fun record(event: TelemetryEvent) {
        if (!isEnabled) return
        
        buffer.add(event)
        
        if (buffer.size >= config.maxEventsInMemory) {
            flush()
        }
    }
    
    override suspend fun flush() {
        if (buffer.isEmpty()) return
        
        val toWrite = buffer.toList()
        buffer.clear()
        
        storage.writeAll(toWrite)
        cleanupOldEvents()
    }
    
    private suspend fun cleanupOldEvents() {
        val cutoff = System.currentTimeMillis() - (config.retentionDays * 24 * 60 * 60 * 1000L)
        storage.deleteBefore(cutoff)
        
        // Check database size
        val size = storage.getDatabaseSize()
        if (size > config.maxDatabaseSizeMb * 1024 * 1024) {
            storage.vacuum()
        }
    }
    
    override suspend fun export(format: ExportFormat): String {
        val events = storage.readAll()
        return when (format) {
            ExportFormat.JSON -> exportJson(events)
            ExportFormat.CSV -> exportCsv(events)
        }
    }
    
    private fun exportJson(events: List<TelemetryRecord>): String {
        val json = kotlinx.serialization.json.Json { prettyPrint = true }
        val wrapper = events.map { it.eventJson }
        return json.encodeToString(wrapper)
    }
    
    private fun exportCsv(events: List<TelemetryRecord>): String {
        val sb = StringBuilder()
        sb.appendLine("event_id,timestamp,event_type,task_id,event_json")
        for (record in events) {
            sb.appendLine("${record.id},${record.timestamp},${record.eventType},${record.taskId},\"${record.eventJson.replace("\"", "\"\"")}\"")
        }
        return sb.toString()
    }
    
    override suspend fun clear() {
        buffer.clear()
        storage.clear()
    }
}

/**
 * Storage interface for telemetry
 */
interface TelemetryStorage {
    suspend fun writeAll(events: List<TelemetryEvent>)
    suspend fun readAll(): List<TelemetryRecord>
    suspend fun deleteBefore(timestamp: Long)
    suspend fun vacuum()
    suspend fun getDatabaseSize(): Long
    suspend fun clear()
}

/**
 * No-op implementation for when telemetry is disabled
 */
object NoOpTelemetryCollector : TelemetryCollector {
    override suspend fun record(event: TelemetryEvent) {}
    override suspend fun flush() {}
    override suspend fun export(format: ExportFormat): String = "[]"
    override suspend fun clear() {}
    override val isEnabled: Boolean = false
    override fun setEnabled(enabled: Boolean) {}
}