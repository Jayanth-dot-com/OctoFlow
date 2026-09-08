# OctoFlow - Enhancement & Scaling Roadmap

> **Version:** 2.0 (Planning)  
> **Status:** Core infrastructure complete — ready for advanced features

---

## Executive Summary

OctoFlow has a solid multi-module foundation (5 modules, 50+ files). The architecture is clean: **Perception → Reasoning → Action** loop with proper contracts. Now we can scale from "working prototype" to "production-grade autonomous agent."

---

## 🎯 Priority Matrix

| Priority | Category | Effort | Impact |
|----------|----------|--------|--------|
| 🔴 P0 | Reliability & Safety | Low | Critical |
| 🟠 P1 | Intelligence & Reasoning | Medium | High |
| 🟡 P2 | User Experience | Medium | High |
| 🟢 P3 | Ecosystem & Extensibility | High | Strategic |
| 🔵 P4 | Platform & Performance | High | Differentiation |

---

## 🔴 P0 — Reliability & Safety (Do First)

### 1. Action Verification & Retry Logic
**Location:** `TaskOrchestratorImpl.kt` + `OctoAccessibilityService.kt`

```kotlin
// Add to ActionResult
@Serializable
data class ActionResult(
    val success: Boolean,
    val errorMessage: String? = null,
    val newSnapshot: UiSnapshot? = null,
    val verificationSnapshot: UiSnapshot? = null,  // NEW: post-action verification
    val retryCount: Int = 0                        // NEW
)
```

**Features:**
- Verify UI actually changed after action (compare before/after snapshots)
- Auto-retry with exponential backoff (max 3)
- Fallback to alternative actions from LLM
- "Stuck detection" — if 3 consecutive actions produce no UI change, escalate

### 2. Safety Guardrails
**New module:** `core/safety`

```kotlin
// Safety policies
sealed interface SafetyPolicy {
    data class BlockPackage(val packageName: String, val reason: String) : SafetyPolicy
    data class RequireConfirmation(val action: Action, val reason: String) : SafetyPolicy
    data class MaxActionsPerMinute(val limit: Int) : SafetyPolicy
    data class BlockedElement(val elementId: Int, val reason: String) : SafetyPolicy
}

// Runtime safety checker
class SafetyEngine {
    fun checkAction(action: Action, context: TaskContext): SafetyResult
    fun registerPolicy(policy: SafetyPolicy)
}
```

**Protections:**
- Never click "Delete account", "Factory reset", "Purchase" without explicit confirmation
- Block system settings modification unless user says "settings"
- Rate-limit actions (prevent runaway loops)
- Sensitive field detection (password, credit card, OTP) — mask in snapshots, never type

### 3. Robust Element Resolution
**Location:** `OctoAccessibilityService.kt` (replace hashCode-based lookup)

```kotlin
// Stable element identifiers
@Serializable
data class ElementSelector(
    val id: Int?,                    // hashCode (fallback)
    val resourceId: String?,         // android:id/@id/...
    val contentDesc: String?,        // accessibility label
    val text: String?,               // visible text
    val className: String?,          // android.widget.Button
    val bounds: Rect?,               // approximate location
    val indexInParent: Int?,         // nth child
    val parentChain: List<String>    // hierarchy path
)

// Resolution with scoring
fun resolveElement(selector: ElementSelector, root: UiElement): UiElement?
```

**Why:** `hashCode()` changes across UI refreshes. Stable selectors enable reliable multi-step tasks.

### 4. Structured Logging & Observability
**New module:** `core/telemetry`

```kotlin
// Structured events for debugging & analytics (local only)
sealed interface TelemetryEvent {
    data class TaskStarted(val taskId: String, val command: String) : TelemetryEvent
    data class StepExecuted(val taskId: String, val step: Int, val action: Action, val latencyMs: Long) : TelemetryEvent
    data class ActionFailed(val taskId: String, val action: Action, val error: String) : TelemetryEvent
    data class TaskCompleted(val taskId: String, val steps: Int, val durationMs: Long, val success: Boolean) : TelemetryEvent
    data class SafetyTriggered(val policy: SafetyPolicy, val action: Action) : TelemetryEvent
}
```

**Export:** Local encrypted SQLite + optional user-opt-in CSV export for debugging.

---

## 🟠 P1 — Intelligence & Reasoning

### 5. Multi-Model Reasoning Pipeline
**Location:** `llm/` + new `llm/pipeline`

```kotlin
// Pipeline stages
interface ReasoningStage {
    suspend fun process(input: ReasoningInput): ReasoningOutput
}

// Stages:
// 1. Planner (high-level) → breaks task into subgoals
// 2. Grounder → maps subgoals to current UI elements
// 3. Actor (current) → single action
// 4. Verifier → checks if action achieved subgoal
// 5. Replanner → adjusts on failure
```

**Models per stage:**
- Planner: Gemma 2B (or 7B if NPU allows) — "What are the steps?"
- Grounder: Smaller model (500M) — "Which element matches 'login button'?"
- Actor: Current 2B — "Click element 42"
- Verifier: Tiny classifier — "Did login succeed?"

### 6. Memory & Context Persistence
**New module:** `core/memory`

```kotlin
// Episodic memory
@Serializable
data class Episode(
    val id: String,
    val taskCommand: String,
    val steps: List<ReasoningStep>,
    val outcome: TaskStatus,
    val appPackage: String,
    val uiPatterns: List<UiPattern>,
    val timestamp: Long
)

// Semantic memory (learned shortcuts)
@Serializable
data class UiPattern(
    val appPackage: String,
    val activityName: String,
    val goal: String,              // "login"
    val actionSequence: List<Action>,
    val successRate: Float,
    val lastUsed: Long
)

// API
interface MemoryStore {
    suspend fun recordEpisode(episode: Episode)
    suspend fun findSimilarPatterns(command: String, currentApp: String): List<UiPattern>
    suspend fun getSuccessRate(app: String, goal: String): Float
}
```

**Benefits:**
- Learns app-specific workflows (e.g., "how to post on Twitter")
- Few-shot prompting with successful examples
- Cold-start reduction for new apps

### 7. Dynamic Prompt Optimization
**Location:** `MediaPipeReasoningEngine.kt`

```kotlin
class PromptOptimizer {
    // Compress UI tree to fit context window
    fun compressUiTree(snapshot: UiSnapshot, maxTokens: Int): String {
        // 1. Remove invisible/disabled elements
        // 2. Collapse deep hierarchies
        // 3. Prioritize interactive elements near focus
        // 4. Use abbreviations for common classes
    }
    
    // Few-shot examples from memory
    fun injectExamples(prompt: String, patterns: List<UiPattern>): String
    
    // Chain-of-thought prompting
    fun buildCoTPrompt(task: Task, snapshot: UiSnapshot): String
}
```

### 8. Vision-Augmented Perception (Optional)
**New module:** `perception/vision`

```kotlin
// For elements accessibility misses: icons, game UIs, canvas apps
class VisionPerception {
    // Lightweight: run only when accessibility returns empty/low-confidence
    // Model: MobileNetV3 / YOLO-NAS-S (quantized, <5MB)
    // Input: screenshot (downscaled 320x240)
    // Output: bounding boxes + class labels → merge with accessibility tree
    
    suspend fun augmentSnapshot(accessibilityTree: UiSnapshot): UiSnapshot
}
```

**Trigger:** Only when `interactiveElementCount < threshold` or confidence low.

---

## 🟡 P2 — User Experience

### 9. Natural Language Understanding (Pre-LLM)
**New module:** `core/nlu`

```kotlin
// Lightweight intent classification before heavy LLM
@Serializable
data class ParsedIntent(
    val action: IntentType,
    val targetApp: String?,
    val entities: Map<String, String>,  // "contact": "Mom", "message": "Hi"
    val confidence: Float
)

enum class IntentType {
    OPEN_APP, SEND_MESSAGE, CREATE_ITEM, SEARCH, EXTRACT_INFO,
    NAVIGATE, FILL_FORM, CAPTURE_SCREEN, SETTINGS_TOGGLE
}

// TensorFlow Lite / MediaPipe text classifier (<1MB)
class IntentClassifier {
    suspend fun classify(command: String): ParsedIntent
}
```

**Benefits:**
- Fast path for common commands (skip full LLM)
- Better entity extraction for `SET_TEXT` actions
- Can pre-fetch app-specific context

### 10. Proactive Suggestions & Shortcuts
**New module:** `ui/suggestions`

```kotlin
// Learned shortcuts surfaced in UI
@Serializable
data class Shortcut(
    val id: String,
    val triggerPhrase: String,      // "email john"
    val expandedCommand: String,    // "Open Gmail, compose new, to john@..., subject..., body..."
    val usageCount: Int,
    val lastUsed: Long,
    val createdFrom: ShortcutOrigin // USER_DEFINED, LEARNED, IMPORTED
)

// UI: Long-press mic button → shows shortcuts
// Voice: "Create shortcut 'email john' for this"
```

### 11. Task Templates & Macro Recording
**New module:** `core/templates`

```kotlin
// Parameterized workflows
@Serializable
data class TaskTemplate(
    val id: String,
    val name: String,
    val description: String,
    val parameters: List<TemplateParam>,
    val stepTemplate: List<TemplateStep>
)

@Serializable
data class TemplateParam(
    val name: String,
    val type: ParamType, // TEXT, CONTACT, APP, NUMBER, DATE
    val description: String,
    val defaultValue: String?
)

// Usage: "Run 'daily-standup' with project=OctoFlow, date=today"
```

**Macro recorder:** User demonstrates once → OctoFlow generalizes → saves as template.

### 12. Rich Visual Feedback Overlay
**Location:** `ui/feedback/FeedbackImpl.kt`

```kotlin
// Enhanced overlay features
class AdvancedOverlayFeedback : VisualFeedback {
    // Action trail: fading breadcrumbs of past taps
    fun showActionTrail(steps: List<ReasoningStep>)
    
    // Element highlight with label
    fun highlightElement(element: UiElement, label: String)
    
    // Progress ring around mic button
    fun showProgress(current: Int, total: Int)
    
    // Speech bubble with LLM "thought"
    fun showThoughtBubble(elementId: Int, thought: String)
    
    // Error callout with retry button
    fun showErrorWithRetry(elementId: Int, error: String, onRetry: () -> Unit)
}
```

### 13. Multi-Modal Input
**New module:** `core/input`

```kotlin
sealed interface InputMode {
    object Voice : InputMode
    object Text : InputMode          // Type command in app
    object Gesture : InputMode       // Draw gesture on overlay
    data class Hybrid(val primary: InputMode, val fallback: InputMode) : InputMode
}

// Gesture input: user draws "L" → "go back", circle → "refresh", etc.
class GestureRecognizer {
    suspend fun recognize(path: List<Point>): GestureCommand
}
```

---

## 🟢 P3 — Ecosystem & Extensibility

### 14. Plugin / Skill System
**New module:** `core/plugins`

```kotlin
// App-specific skills (like ECC skills but for apps)
interface AppSkill {
    val targetPackage: String
    val supportedIntents: List<String>
    
    // Custom UI parsing for this app
    fun enhanceSnapshot(snapshot: UiSnapshot): UiSnapshot
    
    // Custom actions
    fun getCustomActions(): List<CustomAction>
    
    // Heuristics for this app
    fun suggestNextAction(snapshot: UiSnapshot, goal: String): Action?
}

// Examples:
// - WhatsAppSkill: "open chat with X", "send voice note"
// - ChromeSkill: "open tab", "bookmark", "find on page"
// - BankingSkill: "check balance", "transfer" (with safety)
// - SlackSkill: "post in #channel", "react with :thumbsup:"
```

**Distribution:** Local skill store + community repository (GitHub-based, like ECC skills).

### 15. Cross-Device Orchestration
**New module:** `core/multi-device`

```kotlin
// Control multiple devices from one "controller" phone
interface DeviceMesh {
    fun discoverPeers(): List<DeviceNode>
    fun delegateTask(task: Task, target: DeviceNode): TaskHandle
    fun syncState()
}

// Use case: "Send this photo from tablet to phone, then post to Instagram"
// Tablet: extract photo → Phone: post to Instagram
```

### 16. Developer SDK & APIs
**New module:** `sdk/`

```kotlin
// For other apps to integrate OctoFlow
@JvmStatic
fun OctoFlow.sendIntent(command: String, callback: (Result) -> Unit)

@JvmStatic
fun OctoFlow.registerCustomAction(name: String, handler: (Params) -> ActionResult)

// Intent API for Tasker/Automate/Termux integration
// "octoflow://run?cmd=open+chrome+and+search+kotlin"
```

### 17. Community Skill Marketplace
**External:** GitHub repo + in-app browser

```kotlin
// Skill manifest
@Serializable
data class SkillManifest(
    val id: String,
    val name: String,
    val version: String,
    val targetApps: List<String>,
    val intents: List<String>,
    val author: String,
    val sourceUrl: String,      // GitHub raw URL
    val minOctoFlowVersion: String,
    val permissions: List<SkillPermission>
)

// In-app: "Browse Skills" → one-tap install → auto-update
```

---

## 🔵 P4 — Platform & Performance

### 18. NPU/GPU Acceleration Optimization
**Location:** `llm/`

```kotlin
// Hardware abstraction
sealed interface InferenceBackend {
    object MediaPipeGPU : InferenceBackend
    object MediaPipeNPU : InferenceBackend
    object MLCGPU : InferenceBackend
    object MLCNPU : InferenceBackend
    object CPU : InferenceBackend
}

class BackendSelector {
    fun selectOptimalBackend(model: ModelType): InferenceBackend {
        // Benchmark each backend on first run
        // Cache result
        // Fallback chain: NPU → GPU → CPU
    }
}

// Model quantization pipeline
// FP16 → INT8 → INT4 with accuracy validation
```

### 19. Background Execution & Scheduling
**Location:** `app/service/OctoFlowForegroundService.kt`

```kotlin
// Scheduled / deferred tasks
@Serializable
data class ScheduledTask(
    val id: String,
    val command: String,
    val trigger: Trigger,
    val createdAt: Long
)

sealed interface Trigger {
    @Serializable data class AtTime(val timestamp: Long) : Trigger
    @Serializable data class Recurring(val cron: String) : Trigger  // "0 9 * * 1-5"
    @Serializable data class OnEvent(val event: SystemEvent) : Trigger // "wifi_connected", "app_opened:com.twitter"
    @Serializable data class OnCondition(val condition: String) : Trigger // "battery > 80%"
}

// WorkManager integration for reliable background execution
```

### 20. Accessibility Service Hardening
**Location:** `accessibility/`

```kotlin
// Resilience features
class AccessibilityWatchdog {
    // Auto-restart on crash
    // Detect stale snapshots (no events > 5s)
    // Handle Android 14+ restriction changes
    // Graceful degradation when service killed
}

// Multi-window / split-screen support
// Picture-in-picture awareness
// Foldable/dual-screen layout handling
```

### 21. Battery & Resource Optimization
```kotlin
class ResourceManager {
    // Dynamic inference config based on battery/thermal
    suspend fun getOptimalConfig(): InferenceConfig {
        return when {
            isCharging() && !isThermalThrottled() -> InferenceConfig(maxTokens=1024, temp=0.3f)
            batteryLevel > 50 -> InferenceConfig(maxTokens=512, temp=0.1f)
            else -> InferenceConfig(maxTokens=256, temp=0.05f) // Conservative
        }
    }
    
    // Batch UI snapshots (reduce accessibility event spam)
    // Sleep between tasks
    // Release wakelocks aggressively
}
```

---

## 📦 Suggested Module Structure (Post-Expansion)

```
OctoFlow/
├── app/                      # Main app (unchanged)
├── core/
│   ├── model/                # Domain models (enhanced)
│   ├── contract/             # Interfaces (enhanced)
│   ├── orchestrator/         # TaskOrchestrator + PipelineOrchestrator
│   ├── voice/                # Voice input
│   ├── nlu/                  # NEW: Intent classification
│   ├── memory/               # NEW: Episodic + semantic memory
│   ├── safety/               # NEW: Guardrails
│   ├── telemetry/            # NEW: Structured logging
│   ├── plugins/              # NEW: Skill system
│   ├── multi-device/         # NEW: Device mesh
│   ├── templates/            # NEW: Parameterized workflows
│   └── input/                # NEW: Multi-modal input
├── llm/
│   ├── engine/               # MediaPipe, MLC engines
│   ├── pipeline/             # NEW: Multi-stage reasoning
│   ├── optimizer/            # NEW: Prompt compression, few-shot
│   └── backend/              # NEW: Hardware abstraction
├── accessibility/
│   ├── service/              # OctoAccessibilityService (hardened)
│   ├── model/                # UiTreeParser + ElementSelector
│   ├── provider/             # AccessibilityProviderImpl
│   └── watchdog/             # NEW: Self-healing
├── perception/
│   └── vision/               # NEW: Optional vision augmentation
├── ui/
│   ├── feedback/             # Enhanced overlays
│   ├── suggestions/          # NEW: Proactive shortcuts
│   ├── main/                 # MainActivity
│   └── settings/             # Settings + skill browser
├── sdk/                      # NEW: Developer APIs
└── build-logic/              # NEW: Convention plugins
```

---

## 🚀 Quick Wins (Week 1-2)

| Task | File | Effort |
|------|------|--------|
| Add `ElementSelector` stable resolution | `OctoAccessibilityService.kt` | 4h |
| Safety allowlist/blocklist | New `core/safety` | 6h |
| Structured telemetry events | New `core/telemetry` | 4h |
| Few-shot prompt injection | `MediaPipeReasoningEngine.kt` | 3h |
| Shortcut suggestions UI | `MainActivity.kt` + new `ui/suggestions` | 8h |
| Action verification (snapshot diff) | `TaskOrchestratorImpl.kt` | 4h |

---

## 📊 Success Metrics

| Metric | Current | Target (v2.0) |
|--------|---------|---------------|
| Task success rate (top 20 apps) | ~60% | >90% |
| Avg steps per task | ~15 | <8 |
| Cold-start latency (voice → first action) | ~3s | <1.5s |
| Battery drain (continuous) | ~8%/hr | <3%/hr |
| Apps with custom skills | 0 | 20+ |
| User-defined shortcuts | 0 | 50+/user |

---

## 🔗 Dependencies to Add

```kotlin
// build.gradle.kts additions
dependencies {
    // P0
    implementation("androidx.room:room-runtime:2.6.1")           // Memory store
    implementation("androidx.datastore:datastore-preferences:1.1.1") // Settings
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")     // JSON (alt)
    
    // P1
    implementation("org.tensorflow:tensorflow-lite-task-text:0.4.4") // NLU
    implementation("androidx.work:work-runtime-ktx:2.9.0")       // Background
    
    // P2
    implementation("androidx.compose.material3:material3-window-size-class:1.2.1")
    
    // P3
    implementation("com.github.kittinunf.fuel:fuel:2.3.1")       // Skill fetch
    
    // P4
    implementation("androidx.benchmark:benchmark-macro-junit4:1.2.2")
}
```

---

## 🎓 Learning Resources

- **Android AccessibilityService deep dive:** Google I/O 2023 "What's new in accessibility"
- **MediaPipe LLM Inference:** https://developers.google.com/mediapipe/solutions/genai/llm_inference
- **MLC LLM Android:** https://github.com/mlc-ai/mlc-llm/tree/main/android
- **On-device ML best practices:** https://developer.android.com/topic/performance/ml
- **WorkManager for foreground:** https://developer.android.com/topic/libraries/architecture/workmanager

---

## Next Steps

1. **Pick 3 P0 items** → implement in parallel branches
2. **Add integration tests** for each module (currently 0 tests)
3. **Set up CI** (GitHub Actions: lint + unit + instrumented)
4. **Dogfood daily** — use OctoFlow for real tasks, file issues
5. **Publish skill template** → invite 3 developers to build skills

---

*This roadmap is a living document. Update as we learn from real usage.*