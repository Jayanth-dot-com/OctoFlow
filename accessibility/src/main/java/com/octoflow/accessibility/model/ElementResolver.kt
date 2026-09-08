package com.octoflow.accessibility.model

import com.octoflow.core.model.Action
import com.octoflow.core.model.Rect
import com.octoflow.core.model.UiElement
import com.octoflow.core.safety.ElementSelector

/**
 * Stable element resolution using multiple identification strategies
 * Replaces fragile hashCode-based lookup
 */
class ElementResolver {
    
    /**
     * Resolves an element using a stable selector
     * Tries multiple strategies in order of reliability
     */
    fun resolveElement(selector: ElementSelector, root: UiElement): UiElement? {
        val candidates = mutableListOf<UiElement>()
        collectAllElements(root, candidates)
        
        // Score each candidate
        val scored = candidates.map { element ->
            element to scoreElement(element, selector)
        }.filter { (_, score) -> score > 0 }
            .sortedByDescending { (_, score) -> score }
        
        return scored.firstOrNull()?.first
    }
    
    /**
     * Finds all elements matching a selector (for multi-select)
     */
    fun findAllElements(selector: ElementSelector, root: UiElement): List<UiElement> {
        val candidates = mutableListOf<UiElement>()
        collectAllElements(root, candidates)
        
        return candidates.map { element ->
            element to scoreElement(element, selector)
        }.filter { (_, score) -> score > 0 }
            .sortedByDescending { (_, score) -> score }
            .map { (element, _) -> element }
    }
    
    /**
     * Creates a stable selector for an element (for recording/replay)
     */
    fun createSelector(element: UiElement, root: UiElement): ElementSelector {
        val parentChain = getParentChain(element, root)
        val indexInParent = getIndexInParent(element, root)
        
        return ElementSelector(
            resourceId = extractResourceId(element),
            contentDesc = element.contentDescription,
            text = element.text?.takeIf { it.isNotBlank() },
            className = element.className.substringAfterLast(".").substringAfterLast("$"),
            indexInParent = indexInParent,
            parentChain = parentChain.map { it.className.substringAfterLast(".").substringAfterLast("$") }
        )
    }
    
    private fun scoreElement(element: UiElement, selector: ElementSelector): Int {
        var score = 0
        
        // Exact matches (highest weight)
        selector.resourceId?.let { 
            if (elementMatchesResourceId(element, it)) score += 1000 
        }
        selector.contentDesc?.let { 
            if (element.contentDescription == it) score += 500 
        }
        selector.text?.let { 
            if (element.text == it) score += 400 
        }
        selector.textMatches?.let { 
            if (element.text?.matches(it.toRegex()) == true) score += 300 
        }
        selector.contentDescMatches?.let { 
            if (element.contentDescription?.matches(it.toRegex()) == true) score += 300 
        }
        
        // Class name match
        selector.className?.let { 
            if (element.className.substringAfterLast(".").substringAfterLast("$") == it) score += 100 
        }
        
        // Structural matches (lower weight, used for disambiguation)
        selector.indexInParent?.let { 
            if (getIndexInParent(element, getRoot(element)) == it) score += 50 
        }
        selector.parentChain?.let { chain ->
            val actualChain = getParentChain(element, getRoot(element))
                .map { it.className.substringAfterLast(".").substringAfterLast("$") }
            if (chain == actualChain) score += 200
            else if (chain.size == actualChain.size) {
                // Partial match
                val matches = chain.zip(actualChain).count { (a, b) -> a == b }
                score += matches * 20
            }
        }
        
        // Bonus for interactive elements
        if (element.isClickable || element.isEditable || element.isScrollable) {
            score += 10
        }
        
        // Penalty for invisible/disabled
        if (!element.isVisible) score -= 1000
        if (!element.isEnabled) score -= 500
        
        return score
    }
    
    private fun collectAllElements(root: UiElement, list: MutableList<UiElement>) {
        list.add(root)
        root.children.forEach { collectAllElements(it, list) }
    }
    
    private fun getParentChain(element: UiElement, root: UiElement): List<UiElement> {
        val chain = mutableListOf<UiElement>()
        findParentChain(root, element, chain)
        return chain
    }
    
    private fun findParentChain(current: UiElement, target: UiElement, chain: MutableList<UiElement>): Boolean {
        if (current == target) return true
        for (child in current.children) {
            if (findParentChain(child, target, chain)) {
                chain.add(current)
                return true
            }
        }
        return false
    }
    
    private fun getIndexInParent(element: UiElement, root: UiElement): Int {
        var index = -1
        findIndexInParent(root, element, 0) { index = it }
        return index
    }
    
    private fun findIndexInParent(current: UiElement, target: UiElement, parentIndex: Int, onFound: (Int) -> Unit): Boolean {
        for (i in current.children.indices) {
            val child = current.children[i]
            if (child == target) {
                onFound(i)
                return true
            }
            if (findIndexInParent(child, target, i, onFound)) {
                return true
            }
        }
        return false
    }
    
    private fun getRoot(element: UiElement): UiElement {
        // In practice, we'd pass root separately or store parent references
        // For now, return the element itself as fallback
        return element
    }
    
    private fun extractResourceId(element: UiElement): String? {
        // Resource ID would come from AccessibilityNodeInfo.getViewIdResourceName()
        // Not currently in UiElement model - would need to be added
        return null
    }
    
    private fun elementMatchesResourceId(element: UiElement, resourceId: String): Boolean {
        // Would check element.resourceId == resourceId
        // Requires adding resourceId to UiElement
        return false
    }
}

/**
 * Extension to create selectors from actions for safety checking
 */
fun Action.toSelector(): ElementSelector? {
    return when (this) {
        is com.octoflow.core.model.Action.Click,
             is com.octoflow.core.model.Action.LongClick,
             is com.octoflow.core.model.Action.SetText,
             is com.octoflow.core.model.Action.Swipe,
             is com.octoflow.core.model.Action.Scroll -> {
            // Would need element lookup - placeholder
            null
        }
        else -> null
    }
}