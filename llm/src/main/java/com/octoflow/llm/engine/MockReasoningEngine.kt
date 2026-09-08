package com.octoflow.llm.engine

import android.content.Context
import com.octoflow.core.contract.ReasoningEngine
import com.octoflow.core.model.*

/**
 * Lightweight rule-based reasoning engine for testing perception and action loops
 * without requiring heavy on-device model weights.
 */
class MockReasoningEngine(private val context: Context) : ReasoningEngine {

    private var ready = false

    override val isReady: Boolean
        get() = ready

    override suspend fun initialize(config: AgentConfig) {
        ready = true
    }

    override suspend fun shutdown() {
        ready = false
    }

    override suspend fun reason(
        task: Task,
        snapshot: UiSnapshot,
        history: List<ReasoningStep>
    ): ReasoningStep {
        val command = task.userCommand.trim().lowercase()

        // If we already performed an action and the task is a simple 1-step command, complete it
        if (history.isNotEmpty() && history.last().action !is Action.Wait) {
            return ReasoningStep(
                thought = "Command executed successfully.",
                action = Action.Done("Finished: ${task.userCommand}"),
                confidence = 1.0f
            )
        }

        // 1. Navigation commands
        if (command == "back" || command == "go back") {
            return ReasoningStep(
                thought = "Navigating back as requested.",
                action = Action.Back(),
                confidence = 0.95f
            )
        }

        if (command == "home" || command == "go home") {
            return ReasoningStep(
                thought = "Returning to home screen.",
                action = Action.Home(),
                confidence = 0.95f
            )
        }

        // 2. Open App commands
        if (command.startsWith("open ") || command.startsWith("launch ")) {
            val target = command.substringAfter(" ").trim()
            val targetPkg = resolvePackage(target)
            return ReasoningStep(
                thought = "Opening application: $targetPkg",
                action = Action.OpenApp(targetPkg),
                confidence = 0.9f
            )
        }

        // 3. Click / Tap commands
        if (command.startsWith("click ") || command.startsWith("tap ")) {
            val target = command.substringAfter(" ").trim()
            val matchedElement = findElementByText(snapshot.rootElement, target)
            if (matchedElement != null) {
                return ReasoningStep(
                    thought = "Found element matching '$target' at ID [${matchedElement.id}].",
                    action = Action.Click(matchedElement.id),
                    confidence = 0.9f
                )
            }
        }

        // 4. Scroll commands
        if (command.contains("scroll down")) {
            return ReasoningStep(
                thought = "Scrolling down screen.",
                action = Action.Scroll(snapshot.rootElement.id, ScrollDirection.DOWN, 500),
                confidence = 0.85f
            )
        }
        if (command.contains("scroll up")) {
            return ReasoningStep(
                thought = "Scrolling up screen.",
                action = Action.Scroll(snapshot.rootElement.id, ScrollDirection.UP, 500),
                confidence = 0.85f
            )
        }

        // 5. General search for any clickable element matching words in command
        val clickable = findClickableElements(snapshot.rootElement)
        val bestMatch = clickable.firstOrNull { elem ->
            val text = (elem.text ?: elem.contentDescription ?: "").lowercase()
            text.isNotBlank() && command.contains(text)
        }

        if (bestMatch != null) {
            return ReasoningStep(
                thought = "Found relevant button: '${bestMatch.text ?: bestMatch.contentDescription}'",
                action = Action.Click(bestMatch.id),
                confidence = 0.8f
            )
        }

        // Default: Finish with helpful message
        return ReasoningStep(
            thought = "Perceived ${clickable.size} interactive elements on current screen. Ready for command.",
            action = Action.Done("Screen inspected: ${snapshot.packageName}"),
            confidence = 0.75f
        )
    }

    private fun findElementByText(root: UiElement, query: String): UiElement? {
        val text = (root.text ?: root.contentDescription ?: "").lowercase()
        if (text.contains(query)) {
            return root
        }
        for (child in root.children) {
            val match = findElementByText(child, query)
            if (match != null) return match
        }
        return null
    }

    private fun findClickableElements(root: UiElement): List<UiElement> {
        val list = mutableListOf<UiElement>()
        if (root.isClickable && root.isVisible) {
            list.add(root)
        }
        for (child in root.children) {
            list.addAll(findClickableElements(child))
        }
        return list
    }

    private fun resolvePackage(appName: String): String {
        return when (appName) {
            "settings" -> "com.android.settings"
            "camera" -> "com.sec.android.app.camera"
            "calculator" -> "com.sec.android.app.popupcalculator"
            "youtube" -> "com.google.android.youtube"
            "chrome" -> "com.android.chrome"
            "messages" -> "com.google.android.apps.messaging"
            "whatsapp" -> "com.whatsapp"
            else -> "com.android.settings"
        }
    }
}
