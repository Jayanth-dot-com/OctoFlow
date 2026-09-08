package com.octoflow.core.memory

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Episodic memory - records of completed tasks
 */
@Serializable
data class Episode(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val userCommand: String,
    val appPackage: String,
    val appActivity: String,
    val steps: List<StepRecord>,
    val outcome: EpisodeOutcome,
    val totalDurationMs: Long,
    val agentVersion: String = "1.0.0"
)

@Serializable
data class StepRecord(
    val stepNumber: Int,
    val thought: String,
    val action: ActionRecord,
    val confidence: Float,
    val latencyMs: Long,
    val success: Boolean,
    val error: String?
)

@Serializable
data class ActionRecord(
    val type: String,
    val elementId: Int?,
    val elementText: String?,
    val elementClass: String?,
    val parameters: Map<String, String>
)

enum class EpisodeOutcome {
    SUCCESS, FAILED, CANCELLED, PARTIAL
}

/**
 * Semantic memory - learned patterns for app-specific workflows
 */
@Serializable
data class UiPattern(
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsed: Long = System.currentTimeMillis(),
    val useCount: Int = 1,
    val appPackage: String,
    val activityName: String,
    val goal: String,                    // "login", "compose_email", "post_tweet"
    val triggerPhrases: List<String>,    // User phrases that map to this pattern
    val actionSequence: List<ActionRecord>,
    val preconditions: List<PatternPrecondition>,
    val successRate: Float = 1.0f,
    val avgSteps: Int = 0,
    val avgDurationMs: Long = 0
)

@Serializable
data class PatternPrecondition(
    val type: PreconditionType,
    val value: String
)

enum class PreconditionType {
    ELEMENT_EXISTS,      // Element with text/desc must exist
    ELEMENT_ABSENT,      // Element must NOT exist
    APP_STATE,           // App must be in specific state
    SCREEN_CONTAINS      // Screen must contain text
}

/**
 * User-defined shortcuts
 */
@Serializable
data class Shortcut(
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsed: Long = 0,
    val useCount: Int = 0,
    val triggerPhrase: String,
    val expandedCommand: String,
    val parameters: List<ShortcutParameter> = emptyList(),
    val origin: ShortcutOrigin = ShortcutOrigin.USER_DEFINED
)

@Serializable
data class ShortcutParameter(
    val name: String,
    val type: ParameterType,
    val description: String,
    val defaultValue: String?
)

enum class ParameterType { TEXT, NUMBER, CONTACT, APP, DATE, BOOLEAN }

enum class ShortcutOrigin { USER_DEFINED, LEARNED, IMPORTED, TEMPLATE }

/**
 * Memory store interface
 */
interface MemoryStore {
    // Episodes
    suspend fun recordEpisode(episode: Episode)
    suspend fun getRecentEpisodes(limit: Int): List<Episode>
    suspend fun getEpisodesForApp(appPackage: String, limit: Int): List<Episode>
    suspend fun getEpisodesForGoal(goal: String, limit: Int): List<Episode>
    suspend fun searchEpisodes(query: String, limit: Int): List<Episode>
    
    // Patterns
    suspend fun recordPattern(pattern: UiPattern)
    suspend fun updatePattern(pattern: UiPattern)
    suspend fun getPatternsForApp(appPackage: String): List<UiPattern>
    suspend fun getPatternsForGoal(goal: String, appPackage: String?): List<UiPattern>
    suspend fun findMatchingPatterns(command: String, currentApp: String, currentActivity: String): List<UiPattern>
    suspend fun incrementPatternUse(patternId: String)
    
    // Shortcuts
    suspend fun saveShortcut(shortcut: Shortcut)
    suspend fun getShortcut(triggerPhrase: String): Shortcut?
    suspend fun getAllShortcuts(): List<Shortcut>
    suspend fun deleteShortcut(id: String)
    suspend fun incrementShortcutUse(id: String)
    
    // Maintenance
    suspend fun pruneOldEpisodes(olderThan: Long, maxToKeep: Int)
    suspend fun mergeSimilarPatterns()
    suspend fun exportAll(): MemoryExport
    suspend fun importAll(export: MemoryExport)
}

@Serializable
data class MemoryExport(
    val exportedAt: Long = System.currentTimeMillis(),
    val version: Int = 1,
    val episodes: List<Episode>,
    val patterns: List<UiPattern>,
    val shortcuts: List<Shortcut>
)

/**
 * In-memory implementation with persistence delegate
 */
class InMemoryMemoryStore(
    private val persistence: PersistenceDelegate? = null
) : MemoryStore {
    
    private val episodes = mutableListOf<Episode>()
    private val patterns = mutableMapOf<String, UiPattern>()
    private val shortcuts = mutableMapOf<String, Shortcut>()
    
    // Episodes
    override suspend fun recordEpisode(episode: Episode) {
        episodes.add(0, episode) // Most recent first
        // Keep only recent episodes in memory
        if (episodes.size > 1000) {
            episodes.removeLast()
        }
        persistence?.saveEpisode(episode)
    }
    
    override suspend fun getRecentEpisodes(limit: Int): List<Episode> {
        return episodes.take(limit)
    }
    
    override suspend fun getEpisodesForApp(appPackage: String, limit: Int): List<Episode> {
        return episodes.filter { it.appPackage == appPackage }.take(limit)
    }
    
    override suspend fun getEpisodesForGoal(goal: String, limit: Int): List<Episode> {
        // Simple text search in userCommand
        return episodes.filter { it.userCommand.contains(goal, ignoreCase = true) }.take(limit)
    }
    
    override suspend fun searchEpisodes(query: String, limit: Int): List<Episode> {
        val lowerQuery = query.lowercase()
        return episodes.filter { 
            it.userCommand.lowercase().contains(lowerQuery) ||
            it.steps.any { it.thought.lowercase().contains(lowerQuery) }
        }.take(limit)
    }
    
    // Patterns
    override suspend fun recordPattern(pattern: UiPattern) {
        val key = patternKey(pattern)
        val existing = patterns[key]
        val updated = existing?.copy(
            useCount = existing.useCount + 1,
            lastUsed = System.currentTimeMillis(),
            successRate = (existing.successRate * existing.useCount + (if (pattern.successRate > 0.5f) 1.0f else 0.0f)) / (existing.useCount + 1),
            avgSteps = (existing.avgSteps * existing.useCount + pattern.avgSteps) / (existing.useCount + 1),
            avgDurationMs = (existing.avgDurationMs * existing.useCount + pattern.avgDurationMs) / (existing.useCount + 1),
            triggerPhrases = (existing.triggerPhrases + pattern.triggerPhrases).distinct()
        ) ?: pattern
        
        patterns[key] = updated
        persistence?.savePattern(updated)
    }
    
    override suspend fun updatePattern(pattern: UiPattern) {
        patterns[patternKey(pattern)] = pattern
        persistence?.savePattern(pattern)
    }
    
    private fun patternKey(pattern: UiPattern): String {
        return "${pattern.appPackage}|${pattern.activityName}|${pattern.goal}"
    }
    
    override suspend fun getPatternsForApp(appPackage: String): List<UiPattern> {
        return patterns.values.filter { it.appPackage == appPackage }
            .sortedWith(compareByDescending<UiPattern> { it.successRate }.thenByDescending { it.useCount })
    }
    
    override suspend fun getPatternsForGoal(goal: String, appPackage: String?): List<UiPattern> {
        return patterns.values.filter { pattern ->
            pattern.goal == goal && (appPackage == null || pattern.appPackage == appPackage)
        }.sortedByDescending { it.successRate }
    }
    
    override suspend fun findMatchingPatterns(command: String, currentApp: String, currentActivity: String): List<UiPattern> {
        val lowerCommand = command.lowercase()
        
        return patterns.values.filter { pattern ->
            (pattern.appPackage == currentApp || pattern.triggerPhrases.any { lowerCommand.contains(it.lowercase()) }) &&
            (pattern.activityName == currentActivity || pattern.activityName.isBlank())
        }.sortedWith(compareByDescending<UiPattern> { it.successRate }.thenByDescending { it.useCount })
    }
    
    override suspend fun incrementPatternUse(patternId: String) {
        patterns.values.find { it.id == patternId }?.let { pattern ->
            patterns[patternId] = pattern.copy(
                useCount = pattern.useCount + 1,
                lastUsed = System.currentTimeMillis()
            )
        }
    }
    
    // Shortcuts
    override suspend fun saveShortcut(shortcut: Shortcut) {
        shortcuts[shortcut.id] = shortcut
        persistence?.saveShortcut(shortcut)
    }
    
    override suspend fun getShortcut(triggerPhrase: String): Shortcut? {
        return shortcuts.values.find { it.triggerPhrase.lowercase() == triggerPhrase.lowercase() }
    }
    
    override suspend fun getAllShortcuts(): List<Shortcut> {
        return shortcuts.values.toList().sortedByDescending { it.useCount }
    }
    
    override suspend fun deleteShortcut(id: String) {
        shortcuts.remove(id)
        persistence?.deleteShortcut(id)
    }
    
    override suspend fun incrementShortcutUse(id: String) {
        shortcuts[id]?.let { shortcut ->
            shortcuts[id] = shortcut.copy(
                useCount = shortcut.useCount + 1,
                lastUsed = System.currentTimeMillis()
            )
        }
    }
    
    // Maintenance
    override suspend fun pruneOldEpisodes(olderThan: Long, maxToKeep: Int) {
        val toKeep = episodes.filter { it.timestamp > olderThan }.take(maxToKeep)
        val removed = episodes.filter { it !in toKeep }
        episodes.clear()
        episodes.addAll(toKeep)
        removed.forEach { persistence?.deleteEpisode(it.id) }
    }
    
    override suspend fun mergeSimilarPatterns() {
        // Group by app+goal and merge similar action sequences
        val groups = patterns.values.groupBy { "${it.appPackage}|${it.goal}" }
        
        for ((_, group) in groups) {
            if (group.size > 1) {
                // Find most successful as canonical
                val canonical = group.maxByOrNull { it.successRate } ?: group.first()
                val others = group.filter { it != canonical }
                
                var currentMerged = canonical
                for (other in others) {
                    // Merge trigger phrases
                    currentMerged = currentMerged.copy(
                        triggerPhrases = (currentMerged.triggerPhrases + other.triggerPhrases).distinct(),
                        useCount = currentMerged.useCount + other.useCount
                    )
                    patterns[canonical.id] = currentMerged
                    patterns.remove(other.id)
                    persistence?.deletePattern(other.id)
                }
                persistence?.savePattern(currentMerged)
            }
        }
    }
    
    override suspend fun exportAll(): MemoryExport {
        return MemoryExport(
            episodes = episodes,
            patterns = patterns.values.toList(),
            shortcuts = shortcuts.values.toList()
        )
    }
    
    override suspend fun importAll(export: MemoryExport) {
        episodes.clear()
        episodes.addAll(export.episodes)
        patterns.clear()
        export.patterns.forEach { patterns[patternKey(it)] = it }
        shortcuts.clear()
        export.shortcuts.forEach { shortcuts[it.id] = it }
        persistence?.importAll(export)
    }
}

/**
 * Persistence delegate for Room/database storage
 */
interface PersistenceDelegate {
    suspend fun saveEpisode(episode: Episode)
    suspend fun deleteEpisode(id: String)
    suspend fun savePattern(pattern: UiPattern)
    suspend fun deletePattern(id: String)
    suspend fun saveShortcut(shortcut: Shortcut)
    suspend fun deleteShortcut(id: String)
    suspend fun importAll(export: MemoryExport)
}

/**
 * Pattern matcher for finding relevant patterns during execution
 */
class PatternMatcher(private val memoryStore: MemoryStore) {
    
    suspend fun findBestPattern(
        command: String,
        currentApp: String,
        currentActivity: String,
        currentSnapshot: com.octoflow.core.model.UiSnapshot
    ): UiPattern? {
        val candidates = memoryStore.findMatchingPatterns(command, currentApp, currentActivity)
        
        return candidates.firstOrNull { pattern ->
            // Check preconditions
            pattern.preconditions.all { precondition ->
                checkPrecondition(precondition, currentSnapshot)
            }
        }
    }
    
    private fun checkPrecondition(precondition: PatternPrecondition, snapshot: com.octoflow.core.model.UiSnapshot): Boolean {
        return when (precondition.type) {
            PreconditionType.ELEMENT_EXISTS -> {
                snapshot.rootElement?.let { root ->
                    findElementRecursive(root, precondition.value) != null
                } ?: false
            }
            PreconditionType.ELEMENT_ABSENT -> {
                snapshot.rootElement?.let { root ->
                    findElementRecursive(root, precondition.value) == null
                } ?: true
            }
            PreconditionType.SCREEN_CONTAINS -> {
                snapshot.rootElement?.let { root ->
                    containsTextRecursive(root, precondition.value)
                } ?: false
            }
            PreconditionType.APP_STATE -> {
                // Would check app-specific state
                true
            }
        }
    }
    
    private fun findElementRecursive(element: com.octoflow.core.model.UiElement, query: String): com.octoflow.core.model.UiElement? {
        if (element.text?.contains(query, ignoreCase = true) == true ||
            element.contentDescription?.contains(query, ignoreCase = true) == true) {
            return element
        }
        for (child in element.children) {
            findElementRecursive(child, query)?.let { return it }
        }
        return null
    }
    
    private fun containsTextRecursive(element: com.octoflow.core.model.UiElement, query: String): Boolean {
        if (element.text?.contains(query, ignoreCase = true) == true ||
            element.contentDescription?.contains(query, ignoreCase = true) == true) {
            return true
        }
        return element.children.any { containsTextRecursive(it, query) }
    }
}

/**
 * Extension to convert ReasoningStep to ActionRecord
 */
fun com.octoflow.core.model.ReasoningStep.toActionRecord(): ActionRecord {
    return ActionRecord(
        type = action::class.simpleName?.removeSuffix("Action") ?: "UNKNOWN",
        elementId = when (action) {
            is com.octoflow.core.model.Action.Click -> action.elementId
            is com.octoflow.core.model.Action.LongClick -> action.elementId
            is com.octoflow.core.model.Action.Swipe -> action.elementId
            is com.octoflow.core.model.Action.SetText -> action.elementId
            is com.octoflow.core.model.Action.Scroll -> action.elementId
            else -> null
        },
        elementText = null, // Would need element lookup
        elementClass = null,
        parameters = when (action) {
            is com.octoflow.core.model.Action.SetText -> mapOf("text" to action.text, "clearFirst" to action.clearFirst.toString())
            is com.octoflow.core.model.Action.Swipe -> mapOf("direction" to action.direction.name, "distance" to action.distance.toString())
            is com.octoflow.core.model.Action.Scroll -> mapOf("direction" to action.direction.name, "amount" to action.amount.toString())
            is com.octoflow.core.model.Action.OpenApp -> mapOf("packageName" to action.packageName)
            is com.octoflow.core.model.Action.Wait -> mapOf("millis" to action.millis.toString())
            is com.octoflow.core.model.Action.Done -> mapOf("result" to (action.result ?: ""))
            else -> emptyMap()
        }
    )
}