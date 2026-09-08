package com.octoflow.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.octoflow.agent.OctoFlowAgentImpl
import com.octoflow.agent.OctoFlowApplication
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    fun showSettings() {
        // Navigate to settings screen
    }
    
    fun onPermissionsResult(permissions: Map<String, Boolean>) {
        // Handle permission results
        val agent = getApplication<OctoFlowApplication>().getAgent()
        // Agent will re-check permissions on next action
    }
}