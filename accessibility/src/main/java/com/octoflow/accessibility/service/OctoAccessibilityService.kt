package com.octoflow.accessibility.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.octoflow.accessibility.model.AccessibilityEventCallback
import com.octoflow.accessibility.model.UiTreeParser
import com.octoflow.core.contract.ActionResult
import com.octoflow.core.model.Action
import com.octoflow.core.model.UiElement
import com.octoflow.core.model.UiSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Main AccessibilityService that captures UI state and executes actions
 */
class OctoAccessibilityService : AccessibilityService() {
    
    companion object {
        private const val TAG = "OctoAccessibilityService"
        private var INSTANCE: OctoAccessibilityService? = null
        
        fun getInstance(): OctoAccessibilityService? = INSTANCE
        
        fun isServiceRunning(): Boolean = INSTANCE != null
    }
    
    private val parser = UiTreeParser()
    private val eventCallbacks = mutableListOf<AccessibilityEventCallback>()
    private val snapshotChannel = Channel<UiSnapshot>(Channel.UNLIMITED)
    private val actionResultChannel = Channel<ActionResult>(Channel.UNLIMITED)
    private var scope = CoroutineScope(Dispatchers.Main + Job())
    private val currentSnapshot = AtomicReference<UiSnapshot?>(null)
    private val pendingActions = mutableMapOf<Int, Action>()
    private var actionIdCounter = 0
    
    override fun onCreate() {
        super.onCreate()
        INSTANCE = this
        Log.i(TAG, "OctoAccessibilityService created")
    }
    
    override fun onDestroy() {
        INSTANCE = null
        scope.cancel()
        snapshotChannel.close()
        actionResultChannel.close()
        Log.i(TAG, "OctoAccessibilityService destroyed")
        super.onDestroy()
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let { evt ->
            // Update UI tree on relevant events
            if (shouldProcessEvent(evt)) {
                scope.launch {
                    val snapshot = captureSnapshot()
                    currentSnapshot.set(snapshot)
                    snapshotChannel.trySend(snapshot)
                    eventCallbacks.forEach { it.onUiChanged(snapshot) }
                }
            }
            
            // Notify callbacks
            eventCallbacks.forEach { it.onAccessibilityEvent(evt) }
        }
    }
    
    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
        scope.launch {
            snapshotChannel.close()
            actionResultChannel.close()
        }
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility service connected")
        scope.launch {
            val snapshot = captureSnapshot()
            currentSnapshot.set(snapshot)
            snapshotChannel.trySend(snapshot)
        }
    }
    
    private fun shouldProcessEvent(event: AccessibilityEvent): Boolean {
        return when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> true
            else -> false
        }
    }
    
    private fun captureSnapshot(): UiSnapshot {
        val rootNode = rootInActiveWindow
        val (packageName, activityName) = getCurrentPackageAndActivity()
        
        return UiSnapshot(
            timestamp = System.currentTimeMillis(),
            packageName = packageName,
            activityName = activityName,
            rootElement = parser.parseNode(rootNode) ?: createEmptyRootElement(packageName),
            activeElementIds = getActiveElementIds(rootNode)
        )
    }
    
    private fun createEmptyRootElement(pkg: String) = UiElement(
        id = 0,
        className = "android.view.View",
        text = null,
        contentDescription = null,
        bounds = com.octoflow.core.model.Rect(0, 0, 0, 0),
        isClickable = false,
        isEditable = false,
        isScrollable = false,
        isFocused = false,
        isEnabled = true,
        isVisible = false
    )
    
    private fun getCurrentPackageAndActivity(): Pair<String, String> {
        val rootNode = rootInActiveWindow
        val packageName = rootNode?.packageName?.toString() ?: "unknown"
        val activityName = getCurrentActivityName(rootNode) ?: "unknown"
        return packageName to activityName
    }
    
    private fun getCurrentActivityName(node: AccessibilityNodeInfo?): String? {
        var current = node
        while (current != null) {
            if (current.className.toString().endsWith("Activity")) {
                return current.className.toString()
            }
            current = current.parent
        }
        return null
    }
    
    private fun getActiveElementIds(node: AccessibilityNodeInfo?): List<Int> {
        val ids = mutableListOf<Int>()
        collectFocusedIds(node, ids)
        return ids
    }
    
    private fun collectFocusedIds(node: AccessibilityNodeInfo?, ids: MutableList<Int>) {
        node?.let {
            if (it.isFocused || it.isSelected) {
                ids.add(it.hashCode())
            }
            for (i in 0 until it.childCount) {
                collectFocusedIds(it.getChild(i), ids)
            }
        }
    }
    
    // Public API
    
    fun registerCallback(callback: AccessibilityEventCallback) {
        eventCallbacks.add(callback)
    }
    
    fun unregisterCallback(callback: AccessibilityEventCallback) {
        eventCallbacks.remove(callback)
    }
    
    fun getSnapshotChannel(): Channel<UiSnapshot> = snapshotChannel
    
    fun getLatestSnapshot(): UiSnapshot? = currentSnapshot.get()
    
    suspend fun executeAction(action: Action): ActionResult {
        return try {
            val actionId = actionIdCounter++
            pendingActions[actionId] = action
            
            val result = when (action) {
                is Action.Click -> performClick(action.elementId)
                is Action.LongClick -> performLongClick(action.elementId)
                is Action.Swipe -> performSwipe(action.elementId, action.direction, action.distance)
                is Action.SetText -> performSetText(action.elementId, action.text, action.clearFirst)
                is Action.Scroll -> performScroll(action.elementId, action.direction, action.amount)
                is Action.Back -> performBack()
                is Action.Home -> performHome()
                is Action.Wait -> { Thread.sleep(action.millis.toLong()); ActionResult(true) }
                is Action.OpenApp -> performOpenApp(action.packageName)
                is Action.Done -> ActionResult(true, null, null)
            }
            
            pendingActions.remove(actionId)
            
            // Capture new snapshot after action
            val newSnapshot = captureSnapshot()
            currentSnapshot.set(newSnapshot)
            snapshotChannel.trySend(newSnapshot)
            
            result.copy(newSnapshot = newSnapshot)
        } catch (e: Exception) {
            Log.e(TAG, "Action execution failed", e)
            ActionResult(false, e.message)
        }
    }
    
    private fun performClick(elementId: Int): ActionResult {
        findNodeById(elementId)?.let { node ->
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return ActionResult(true)
            }
        }
        return ActionResult(false, "Element not clickable or not found: $elementId")
    }
    
    private fun performLongClick(elementId: Int): ActionResult {
        findNodeById(elementId)?.let { node ->
            if (node.isLongClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                return ActionResult(true)
            }
        }
        return ActionResult(false, "Element not long-clickable or not found: $elementId")
    }
    
    private fun performSwipe(
        elementId: Int,
        direction: com.octoflow.core.model.SwipeDirection,
        distance: Int
    ): ActionResult {
        findNodeById(elementId)?.let { node ->
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            
            val startX = bounds.centerX()
            val startY = bounds.centerY()
            val (endX, endY) = when (direction) {
                com.octoflow.core.model.SwipeDirection.UP -> startX to (startY - distance)
                com.octoflow.core.model.SwipeDirection.DOWN -> startX to (startY + distance)
                com.octoflow.core.model.SwipeDirection.LEFT -> (startX - distance) to startY
                com.octoflow.core.model.SwipeDirection.RIGHT -> (startX + distance) to startY
            }
            
            val path = Path().apply {
                moveTo(startX.toFloat(), startY.toFloat())
                lineTo(endX.toFloat(), endY.toFloat())
            }
            
            val gesture = GestureDescription.StrokeDescription(path, 0, 300)
            val gestureDescription = GestureDescription.Builder().addStroke(gesture).build()
            
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    actionResultChannel.trySend(ActionResult(true))
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    actionResultChannel.trySend(ActionResult(false, "Gesture cancelled"))
                }
            }
            
            dispatchGesture(gestureDescription, callback, Handler(Looper.getMainLooper()))
            return ActionResult(true) // Async result via channel
        }
        return ActionResult(false, "Element not found for swipe: $elementId")
    }
    
    private fun performSetText(elementId: Int, text: String, clearFirst: Boolean): ActionResult {
        findNodeById(elementId)?.let { node ->
            if (node.isEditable) {
                if (clearFirst) {
                    val clearArgs = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "") }
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
                    Thread.sleep(50)
                }
                val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                return ActionResult(true)
            }
        }
        return ActionResult(false, "Element not editable or not found: $elementId")
    }
    
    private fun performScroll(elementId: Int, direction: com.octoflow.core.model.ScrollDirection, amount: Int): ActionResult {
        findNodeById(elementId)?.let { node ->
            if (node.isScrollable) {
                val action = when (direction) {
                    com.octoflow.core.model.ScrollDirection.UP -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id
                    com.octoflow.core.model.ScrollDirection.DOWN -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id
                    com.octoflow.core.model.ScrollDirection.LEFT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
                    com.octoflow.core.model.ScrollDirection.RIGHT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
                }
                repeat(amount) { node.performAction(action) }
                return ActionResult(true)
            }
        }
        return ActionResult(false, "Element not scrollable or not found: $elementId")
    }
    
    private fun performBack(): ActionResult {
        performGlobalAction(GLOBAL_ACTION_BACK)
        return ActionResult(true)
    }
    
    private fun performHome(): ActionResult {
        performGlobalAction(GLOBAL_ACTION_HOME)
        return ActionResult(true)
    }
    
    private fun performOpenApp(packageName: String): ActionResult {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent?.let { startActivity(it) }
            return ActionResult(true)
        } catch (e: Exception) {
            return ActionResult(false, "Failed to open app: ${e.message}")
        }
    }
    
    private fun findNodeById(elementId: Int): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findNodeByHashCode(root, elementId)
    }
    
    private fun findNodeByHashCode(node: AccessibilityNodeInfo, targetHash: Int): AccessibilityNodeInfo? {
        if (node.hashCode() == targetHash) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                findNodeByHashCode(child, targetHash)?.let { return it }
            }
        }
        return null
    }
    
    // Extension for external access
    fun getActionResultChannel(): Channel<ActionResult> = actionResultChannel
}