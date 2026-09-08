package com.octoflow.accessibility.provider

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.octoflow.accessibility.service.OctoAccessibilityService
import com.octoflow.core.contract.AccessibilityProvider
import com.octoflow.core.contract.ActionResult
import com.octoflow.core.model.Action
import com.octoflow.core.model.UiElement
import com.octoflow.core.model.UiSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Implementation of AccessibilityProvider that connects to the AccessibilityService
 */
class AccessibilityProviderImpl(private val context: Context) : AccessibilityProvider {
    
    private val service get() = OctoAccessibilityService.getInstance()
    private val _uiUpdates = MutableStateFlow<UiSnapshot?>(null)
    override val uiUpdates: Flow<UiSnapshot> = _uiUpdates.filterNotNull()
    
    override suspend fun getCurrentSnapshot(): UiSnapshot {
        return service?.getLatestSnapshot() 
            ?: UiSnapshot(
                timestamp = System.currentTimeMillis(),
                packageName = "unknown",
                activityName = "unknown",
                rootElement = com.octoflow.core.model.UiElement(
                    id = 0,
                    className = "Root",
                    text = null,
                    contentDescription = null,
                    bounds = com.octoflow.core.model.Rect(0, 0, 0, 0),
                    isClickable = false,
                    isEditable = false,
                    isScrollable = false,
                    isFocused = false,
                    isEnabled = true,
                    isVisible = true
                )
            )
    }
    
    override suspend fun executeAction(action: Action): ActionResult {
        return service?.executeAction(action) 
            ?: ActionResult(false, "Accessibility service not available")
    }
    
    override fun requestAccessibilityPermission(): Boolean {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
        return true
    }
    
    override fun isAccessibilityEnabled(): Boolean {
        return OctoAccessibilityService.isServiceRunning()
    }
    
    /**
     * Start observing UI updates
     */
    fun startObserving() {
        service?.let { s ->
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                s.getSnapshotChannel().consumeEach { snapshot ->
                    _uiUpdates.value = snapshot
                }
            }
        }
    }
    
    /**
     * Stop observing UI updates
     */
    fun stopObserving() {
        // Channel will be closed when service is destroyed
    }
}