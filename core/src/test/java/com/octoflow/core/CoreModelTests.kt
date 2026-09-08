package com.octoflow.core.model

import com.octoflow.accessibility.model.ElementResolver
import com.octoflow.accessibility.model.UiTreeParser
import com.octoflow.core.safety.DefaultSafetyEngine
import com.octoflow.core.safety.ElementSelector
import com.octoflow.core.safety.SafetyContext
import com.octoflow.core.safety.SafetyPolicy
import com.octoflow.core.safety.SafetyResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for core domain models and utilities
 */
class CoreModelTests {
    
    @Test
    fun testRectCalculations() {
        val rect = Rect(left = 100, top = 200, right = 300, bottom = 400)
        assertEquals(200, rect.centerX)
        assertEquals(300, rect.centerY)
        assertEquals(200, rect.width)
        assertEquals(200, rect.height)
    }
    
    @Test
    fun testUiElementSerialization() {
        val element = UiElement(
            id = 1,
            className = "android.widget.Button",
            text = "Click me",
            contentDescription = "Submit button",
            bounds = Rect(100, 100, 200, 150),
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            isFocused = false,
            isEnabled = true,
            isVisible = true,
            hint = null,
            inputType = 0
        )
        
        val json = kotlinx.serialization.json.Json.encodeToString(element)
        val decoded = kotlinx.serialization.json.Json.decodeFromString<UiElement>(json)
        
        assertEquals(element.id, decoded.id)
        assertEquals(element.text, decoded.text)
        assertEquals(element.bounds.left, decoded.bounds.left)
    }
    
    @Test
    fun testActionSerialization() {
        val actions = listOf<Action>(
            Action.Click(42),
            Action.SetText(123, "Hello world", true),
            Action.Swipe(456, SwipeDirection.UP, 300),
            Action.Back(),
            Action.Done("Task completed")
        )
        
        actions.forEach { action ->
            val json = kotlinx.serialization.json.Json.encodeToString(action)
            val decoded = kotlinx.serialization.json.Json.decodeFromString<Action>(json)
            assertEquals(action, decoded)
        }
    }
    
    @Test
    fun testTaskStatusTransitions() {
        val task = Task(
            id = "test-1",
            userCommand = "Open Chrome",
            status = TaskStatus.PENDING
        )
        
        var running = task.copy(status = TaskStatus.RUNNING)
        assertEquals(TaskStatus.RUNNING, running.status)
        
        var completed = running.copy(status = TaskStatus.COMPLETED, completedAt = System.currentTimeMillis())
        assertEquals(TaskStatus.COMPLETED, completed.status)
        assertNotNull(completed.completedAt)
        
        var failed = task.copy(status = TaskStatus.FAILED, error = "Something went wrong")
        assertEquals(TaskStatus.FAILED, failed.status)
        assertEquals("Something went wrong", failed.error)
    }
}

/**
 * Tests for ElementResolver
 */
class ElementResolverTests {
    
    private val parser = UiTreeParser()
    private val resolver = ElementResolver()
    
    @Test
    fun testCreateSelector() {
        // Build a simple UI tree
        val root = UiElement(
            id = 1,
            className = "android.widget.FrameLayout",
            text = null,
            contentDescription = null,
            bounds = Rect(0, 0, 1080, 1920),
            isClickable = false,
            isEditable = false,
            isScrollable = false,
            isFocused = false,
            isEnabled = true,
            isVisible = true,
            hint = null,
            inputType = 0,
            children = listOf(
                UiElement(
                    id = 2,
                    className = "android.widget.Button",
                    text = "Submit",
                    contentDescription = "Submit form",
                    bounds = Rect(400, 800, 680, 900),
                    isClickable = true,
                    isEditable = false,
                    isScrollable = false,
                    isFocused = false,
                    isEnabled = true,
                    isVisible = true,
                    hint = null,
                    inputType = 0
                )
            )
        )
        
        val button = root.children[0]
        val selector = resolver.createSelector(button, root)
        
        assertEquals("Submit", selector.text)
        assertEquals("Submit form", selector.contentDesc)
        assertEquals("Button", selector.className)
    }
    
    @Test
    fun testResolveElementByText() {
        val root = buildTestTree()
        val selector = ElementSelector(text = "Login")
        
        val found = resolver.resolveElement(selector, root)
        
        assertNotNull(found)
        assertEquals("Login", found?.text)
    }
    
    @Test
    fun testResolveElementByContentDesc() {
        val root = buildTestTree()
        val selector = ElementSelector(contentDesc = "Search button")
        
        val found = resolver.resolveElement(selector, root)
        
        assertNotNull(found)
        assertEquals("Search button", found?.contentDescription)
    }
    
    @Test
    fun testFindAllElements() {
        val root = buildTestTree()
        val selector = ElementSelector(className = "Button")
        
        val found = resolver.findAllElements(selector, root)
        
        assertTrue(found.size >= 2)
        found.forEach { assertEquals("Button", it.className.substringAfterLast(".")) }
    }
    
    private fun buildTestTree(): UiElement {
        return UiElement(
            id = 1,
            className = "android.widget.FrameLayout",
            text = null,
            contentDescription = null,
            bounds = Rect(0, 0, 1080, 1920),
            isClickable = false,
            isEditable = false,
            isScrollable = false,
            isFocused = false,
            isEnabled = true,
            isVisible = true,
            hint = null,
            inputType = 0,
            children = listOf(
                UiElement(
                    id = 2,
                    className = "android.widget.Button",
                    text = "Login",
                    contentDescription = "Login button",
                    bounds = Rect(400, 800, 680, 900),
                    isClickable = true,
                    isEditable = false,
                    isScrollable = false,
                    isFocused = false,
                    isEnabled = true,
                    isVisible = true,
                    hint = null,
                    inputType = 0
                ),
                UiElement(
                    id = 3,
                    className = "android.widget.Button",
                    text = "Cancel",
                    contentDescription = "Cancel button",
                    bounds = Rect(400, 950, 680, 1050),
                    isClickable = true,
                    isEditable = false,
                    isScrollable = false,
                    isFocused = false,
                    isEnabled = true,
                    isVisible = true,
                    hint = null,
                    inputType = 0
                ),
                UiElement(
                    id = 4,
                    className = "android.widget.EditText",
                    text = null,
                    contentDescription = "Username field",
                    bounds = Rect(100, 500, 980, 600),
                    isClickable = false,
                    isEditable = true,
                    isScrollable = false,
                    isFocused = false,
                    isEnabled = true,
                    isVisible = true,
                    hint = "Enter username",
                    inputType = 1
                )
            )
        )
    }
}

/**
 * Tests for SafetyEngine
 */
class SafetyEngineTests {
    
    private val safetyEngine = DefaultSafetyEngine()
    
    @Test
    fun testBlockSensitiveField() = runBlocking {
        val context = SafetyContext(
            currentTask = "Login to bank",
            currentApp = "com.bank.app",
            currentActivity = "LoginActivity",
            stepNumber = 1,
            recentActions = emptyList(),
            uiSnapshot = ""
        )
        
        // Try to enter password-like text
        val action = Action.SetText(42, "mySecretPassword123", true)
        val result = safetyEngine.checkAction(action, context)
        
        assertTrue(result is SafetyResult.Deny)
        assertTrue(result.reason.contains("sensitive"))
    }
    
    @Test
    fun testAllowNormalText() = runBlocking {
        val context = SafetyContext(
            currentTask = "Send message",
            currentApp = "com.whatsapp",
            currentActivity = "ChatActivity",
            stepNumber = 1,
            recentActions = emptyList(),
            uiSnapshot = ""
        )
        
        val action = Action.SetText(42, "Hello, how are you?", true)
        val result = safetyEngine.checkAction(action, context)
        
        assertTrue(result is SafetyResult.Allow)
    }
    
    @Test
    fun testRateLimiting() = runBlocking {
        val context = SafetyContext(
            currentTask = "Test",
            currentApp = "com.test.app",
            currentActivity = "MainActivity",
            stepNumber = 1,
            recentActions = emptyList(),
            uiSnapshot = ""
        )
        
        // Fire many actions rapidly
        repeat(65) {
            safetyEngine.checkAction(Action.Click(it), context)
        }
        
        val result = safetyEngine.checkAction(Action.Click(999), context)
        assertTrue(result is SafetyResult.Deny)
        assertTrue(result.reason.contains("Rate limit"))
    }
    
    @Test
    fun testBlockedElements() = runBlocking {
        val context = SafetyContext(
            currentTask = "Delete account",
            currentApp = "com.social.app",
            currentActivity = "SettingsActivity",
            stepNumber = 1,
            recentActions = emptyList(),
            uiSnapshot = ""
        )
        
        // The safety engine checks element selectors - this is a unit test
        // In practice, the element would be resolved from the snapshot
        // For now, we verify the policy exists
        val policies = safetyEngine.activePolicies
        val hasBlockedElement = policies.any { it is SafetyPolicy.BlockedElement }
        assertTrue(hasBlockedElement)
    }
}

/**
 * Tests for UiTreeParser minification
 */
class UiTreeParserTests {
    
    private val parser = UiTreeParser()
    
    @Test
    fun testMinifiedSnapshot() {
        val root = UiElement(
            id = 1,
            className = "android.widget.FrameLayout",
            text = null,
            contentDescription = null,
            bounds = Rect(0, 0, 1080, 1920),
            isClickable = false,
            isEditable = false,
            isScrollable = false,
            isFocused = false,
            isEnabled = true,
            isVisible = true,
            hint = null,
            inputType = 0,
            children = listOf(
                UiElement(
                    id = 2,
                    className = "android.widget.Button",
                    text = "Click me",
                    contentDescription = "Action button",
                    bounds = Rect(100, 100, 300, 200),
                    isClickable = true,
                    isEditable = false,
                    isScrollable = false,
                    isFocused = false,
                    isEnabled = true,
                    isVisible = true,
                    hint = null,
                    inputType = 0
                ),
                UiElement(
                    id = 3,
                    className = "android.view.View",
                    text = null,
                    contentDescription = null,
                    bounds = Rect(0, 0, 0, 0), // Invisible
                    isClickable = false,
                    isEditable = false,
                    isScrollable = false,
                    isFocused = false,
                    isEnabled = true,
                    isVisible = false,
                    hint = null,
                    inputType = 0
                )
            )
        )
        
        val minified = parser.createMinifiedSnapshot(root)
        
        assertTrue(minified.contains("Button"))
        assertTrue(minified.contains("Click me"))
        assertTrue(minified.contains("Action button"))
        assertTrue(minified.contains("clickable"))
        assertFalse(minified.contains("View")) // Invisible elements filtered
    }
}