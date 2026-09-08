package com.octoflow.accessibility.model

import android.view.accessibility.AccessibilityNodeInfo
import com.octoflow.core.model.Rect
import com.octoflow.core.model.UiElement

/**
 * Parses Android AccessibilityNodeInfo into our lightweight UiElement model
 */
class UiTreeParser {
    
    private var idCounter = 0
    private val idMap = mutableMapOf<AccessibilityNodeInfo, Int>()
    
    fun parseNode(root: AccessibilityNodeInfo?): UiElement? {
        root ?: return null
        idCounter = 0
        idMap.clear()
        return parseNodeRecursive(root)
    }
    
    private fun parseNodeRecursive(node: AccessibilityNodeInfo): UiElement {
        val id = getOrAssignId(node)
        
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        
        val children = mutableListOf<UiElement>()
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                children.add(parseNodeRecursive(child))
            }
        }
        
        return UiElement(
            id = id,
            className = node.className.toString(),
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            bounds = Rect(
                left = bounds.left,
                top = bounds.top,
                right = bounds.right,
                bottom = bounds.bottom
            ),
            isClickable = node.isClickable,
            isEditable = node.isEditable,
            isScrollable = node.isScrollable,
            isFocused = node.isFocused,
            isEnabled = node.isEnabled,
            isVisible = isVisible(node),
            hint = node.hintText?.toString(),
            inputType = node.inputType,
            resourceId = node.viewIdResourceName,
            children = children
        )
    }
    
    private fun getOrAssignId(node: AccessibilityNodeInfo): Int {
        return idMap.getOrPut(node) {
            idCounter++
            idCounter
        }
    }
    
    private fun isVisible(node: AccessibilityNodeInfo): Boolean {
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        return bounds.width() > 0 && bounds.height() > 0 && 
               bounds.left >= 0 && bounds.top >= 0
    }
    
    /**
     * Creates a token-efficient minified representation of the UI tree for LLMs
     */
    fun createMinifiedSnapshot(root: UiElement): String = com.octoflow.core.model.UiMinifier.minify(root)
}

/**
 * Callback interface for accessibility events
 */
interface AccessibilityEventCallback {
    fun onUiChanged(snapshot: com.octoflow.core.model.UiSnapshot)
    fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent)
}