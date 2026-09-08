package com.octoflow.llm.provider

import android.content.Context
import com.octoflow.core.contract.ReasoningEngine
import com.octoflow.core.model.AgentConfig
import com.octoflow.core.model.ModelType
import com.octoflow.llm.engine.MediaPipeReasoningEngine
import com.octoflow.llm.engine.MockReasoningEngine

/**
 * Factory for creating reasoning engines
 */
object ReasoningEngineFactory {
    
    fun create(context: Context, config: AgentConfig): ReasoningEngine {
        return when (config.modelType) {
            ModelType.MOCK -> MockReasoningEngine(context)
            ModelType.GEMMA_2B, ModelType.OCTOPUS_V2, ModelType.CUSTOM -> {
                MediaPipeReasoningEngine(context)
            }
        }
    }
}