# OctoFlow - On-Device Offline AI Agent for Android

**Turn any smartphone into an autonomous operator with a single voice command.**

OctoFlow is a fully offline, on-device AI agent that controls your Android phone across apps. Instead of manually switching between apps to copy text from a photo, paste it into Trello, and so on — you speak one command, and OctoFlow executes the entire cross-app workflow for you.

## How It Works

```
Voice Command → Perception → Reasoning → Action → (repeat)
```

### 1. Perception (No Screenshots Required)
OctoFlow taps Android's native **AccessibilityService** to pull a lightweight, text-based map of the UI's clickable elements and their exact coordinates. No battery-draining computer-vision models needed.

### 2. Reasoning (Zero Cloud Dependency)
The minified UI state + your voice command is fed into a **~2B parameter LLM** (Gemma 2B / Octopus v2) running natively on the phone's NPU via **MediaPipe LLM Inference** or **MLC LLM**. The model decides the single next logical step.

### 3. Action
The background service receives the model's output (e.g. `{"action": "CLICK", "id": 123}`) and translates it into a real `dispatchGesture` tap or `ACTION_SET_TEXT` input, driving the UI forward. This loop repeats until the task is complete.

## Architecture

```
OctoFlow/
├── app/               # Main application + UI + foreground service
├── core/              # Domain models, contracts, orchestrator, voice input
├── llm/               # On-device reasoning engines (MediaPipe + MLC)
├── accessibility/     # AccessibilityService + UI tree parser + action executor
└── ui/                # Compose UI, visual/haptic feedback overlays
```

### Module Responsibilities

| Module | Responsibility |
|--------|---------------|
| `core` | Shared domain models (`Action`, `Task`, `UiSnapshot`), contracts/interfaces, the `TaskOrchestrator` execution loop, voice input |
| `llm` | On-device inference via MediaPipe LLM Inference API (with MLC LLM scaffold), prompt building, response parsing |
| `accessibility` | The `AccessibilityService`, UI tree parser (minified snapshot), action dispatch (`dispatchGesture`, `ACTION_SET_TEXT`, clicks, scrolls, swipes) |
| `ui` | Overlay visual feedback (pulsing tap indicators), haptic feedback |
| `app` | Compose UI (main screen, accessibility setup), foreground service, application wiring |

## Key Features

- **Fully offline** — all inference on-device, zero cloud calls
- **AccessibilityService-based perception** — no screenshots, no CV models
- **On-device NPU inference** — MediaPipe LLM Inference API (Gemma 2B / Octopus v2)
- **Safe action dispatch** — native Android gestures and input methods
- **Visual feedback overlay** — animated tap/type/swipe indicators via `SYSTEM_ALERT_WINDOW`
- **Haptic feedback** — distinct vibration patterns for success/error
- **Voice-first UX** — Android `SpeechRecognizer` for hands-free commands
- **Foreground service** — continuous operation with notification controls
- **Minified UI snapshots** — token-efficient tree parsing for fast local inference
- **Confidence thresholds** — fallback to alternative actions on low-confidence reasoning
- **Step history** — full reasoning trace for each task

## Requirements

- **Android 8.0+ (API 26)** — `minSdk 26`
- **Android SDK 34** (compile/target)
- **JDK 17+**
- **On-device NPU** (recommended for reasonable inference speed; falls back to CPU)

## Setup

### 1. Configure Android SDK

```bash
# Linux/macOS
export ANDROID_HOME=~/Android/Sdk

# Windows (PowerShell)
$env:ANDROID_HOME = "C:\Users\<user>\AppData\Local\Android\Sdk"
```

### 2. Add the LLM model

Download the Gemma 2B (or Octopus v2) `.task` model and place it in `app/src/main/assets/`:

```bash
# Example: Gemma 2B int4 for MediaPipe LiteRT
app/src/main/assets/gemma-2b-it-gpu-int4.task
```

The model filename is set in `llm/src/main/java/com/octoflow/llm/engine/MediaPipeReasoningEngine.kt` (`getModelPath`). Adjust it to match the model you download.

### 3. Build

```bash
# Linux/macOS
./build.sh

# Windows
build.bat

# Or directly with Gradle
./gradlew assembleDebug
```

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

### 4. Install & Grant Permissions

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

On first launch, grant:
1. **Accessibility Service** (screen reading + action dispatch)
2. **Display over other apps** (visual feedback overlay)
3. **Microphone** (voice commands)

## Usage

1. Open OctoFlow and grant all permissions
2. Tap the microphone button (or start the foreground service from the notification)
3. Speak a command, e.g. *"Open my last photo, extract the text, and create a Trello card with it"*
4. Watch the overlay show each tap/type/swipe as OctoFlow executes the workflow

## Action Vocabulary

The LLM can command these actions (see `core/src/main/java/com/octoflow/core/model/Types.kt`):

| Action | Description |
|--------|-------------|
| `CLICK <elementId>` | Tap an element |
| `LONG_CLICK <elementId>` | Long-press an element |
| `SWIPE <id> <dir> <distance>` | Swipe across an element |
| `SET_TEXT <id> "text"` | Type into an editable element |
| `SCROLL <id> <dir> <amount>` | Scroll a scrollable element |
| `BACK` / `HOME` | System navigation |
| `OPEN_APP <package>` | Launch an app by package |
| `WAIT <ms>` | Pause |
| `DONE "result"` | Mark task complete |

## Safety & Privacy

- **No data leaves the device** — all inference is local
- **Screen access only during active commands** — the service reads UI only when a task is running
- **No hardcoded secrets** — model paths and config are configurable
- **User must explicitly grant Accessibility and overlay permissions**

## Extending

### Add a different model (MLC LLM)
Uncomment `ai.mlc:mlc-llm-android` in `llm/build.gradle.kts` and complete `MlcReasoningEngine.kt`, then switch `ReasoningEngineFactory` to use it.

### Custom action types
Add a new sealed case to `Action` in `core/.../model/Types.kt`, implement it in `OctoAccessibilityService.executeAction`, and teach the model the new verb in `MediaPipeReasoningEngine.buildPrompt`.

### New feedback channel
Implement the `VisualFeedback` / `HapticFeedback` contracts in `core/.../contract/Contracts.kt`.

## Project Status

Core infrastructure is complete: full multi-module Gradle setup, domain model, accessibility service, action dispatcher, reasoning-engine skeleton (MediaPipe + MLC), task orchestrator loop, voice input, feedback overlays, foreground service, and Compose UI. **Next steps**: resolve Gradle build (add wrapper jar), add the actual `.task` model binary, and test on device.

## License

MIT — see `LICENSE`.