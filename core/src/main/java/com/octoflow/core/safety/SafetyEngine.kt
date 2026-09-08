package com.octoflow.core.safety

import com.octoflow.core.model.Action
import kotlinx.serialization.Serializable

/**
 * Safety policies that guard agent actions
 */
@Serializable
sealed interface SafetyPolicy {
    @Serializable
    data class BlockPackage(val packageName: String, val reason: String) : SafetyPolicy
    
    @Serializable
    data class RequireConfirmation(
        val actionType: String,
        val elementText: String?,
        val reason: String
    ) : SafetyPolicy
    
    @Serializable
    data class MaxActionsPerMinute(val limit: Int) : SafetyPolicy
    
    @Serializable
    data class BlockedElement(
        val selector: ElementSelector,
        val reason: String
    ) : SafetyPolicy
    
    @Serializable
    data class SensitiveFieldMask(
        val patterns: List<String>  // regex patterns for password, credit card, OTP, etc.
    ) : SafetyPolicy
    
    @Serializable
    data class AllowedPackageOnly(val packageNames: List<String>) : SafetyPolicy
}

@Serializable
data class ElementSelector(
    val resourceId: String? = null,
    val contentDesc: String? = null,
    val text: String? = null,
    val className: String? = null,
    val textMatches: String? = null,  // regex
    val contentDescMatches: String? = null,
    val indexInParent: Int? = null,
    val parentChain: List<String>? = null
)

@Serializable
sealed interface SafetyResult {
    @Serializable
    data class Allow(val modifiedAction: Action? = null) : SafetyResult
    
    @Serializable
    data class RequireUserConfirmation(
        val message: String,
        val originalAction: Action,
        val onConfirm: (Boolean) -> Unit = {}  // Not serializable, handled at runtime
    ) : SafetyResult
    
    @Serializable
    data class Deny(val reason: String, val policy: SafetyPolicy) : SafetyResult
    
    @Serializable
    data class ModifyAndAllow(val modifiedAction: Action, val reason: String) : SafetyResult
}

interface SafetyEngine {
    suspend fun checkAction(action: Action, context: SafetyContext): SafetyResult
    fun registerPolicy(policy: SafetyPolicy)
    fun unregisterPolicy(policy: SafetyPolicy)
    val activePolicies: List<SafetyPolicy>
}

@Serializable
data class SafetyContext(
    val currentTask: String,
    val currentApp: String,
    val currentActivity: String,
    val stepNumber: Int,
    val recentActions: List<Action>,
    val uiSnapshot: String  // minified
)

/**
 * Default safety engine implementation
 */
class DefaultSafetyEngine : SafetyEngine {
    
    private val policies = mutableListOf<SafetyPolicy>()
    private val actionTimestamps = mutableListOf<Long>()
    private val confirmationCallbacks = mutableMapOf<String, (Boolean) -> Unit>()
    
    init {
        // Built-in safety policies
        registerPolicy(SafetyPolicy.SensitiveFieldMask(
            patterns = listOf(
                "(?i)password",
                "(?i)passcode",
                "(?i)pin",
                "(?i)credit.?card",
                "(?i)cvv",
                "(?i)otp",
                "(?i)one.?time.?password",
                "(?i)security.?code",
                "(?i)secret"
            )
        ))
        
        registerPolicy(SafetyPolicy.MaxActionsPerMinute(limit = 60))
        
        // Block known dangerous actions
        registerPolicy(SafetyPolicy.BlockedElement(
            selector = ElementSelector(textMatches = "(?i)factory.?reset"),
            reason = "Factory reset requires explicit user confirmation"
        ))
        registerPolicy(SafetyPolicy.BlockedElement(
            selector = ElementSelector(textMatches = "(?i)delete.?account"),
            reason = "Account deletion requires explicit user confirmation"
        ))
        registerPolicy(SafetyPolicy.BlockedElement(
            selector = ElementSelector(textMatches = "(?i)erase.?all.?data"),
            reason = "Data erasure requires explicit user confirmation"
        ))
        registerPolicy(SafetyPolicy.BlockedElement(
            selector = ElementSelector(textMatches = "(?i)confirm.?purchase|buy.?now|place.?order"),
            reason = "Purchases require explicit user confirmation"
        ))
    }
    
    override suspend fun checkAction(action: Action, context: SafetyContext): SafetyResult {
        // Rate limiting
        val now = System.currentTimeMillis()
        actionTimestamps.add(now)
        actionTimestamps.removeAll { it < now - 60_000 }
        
        val maxPerMinute = policies
            .filterIsInstance<SafetyPolicy.MaxActionsPerMinute>()
            .maxByOrNull { it.limit }?.limit ?: 60
        
        if (actionTimestamps.size > maxPerMinute) {
            return SafetyResult.Deny(
                reason = "Rate limit exceeded: $maxPerMinute actions/minute",
                policy = SafetyPolicy.MaxActionsPerMinute(maxPerMinute)
            )
        }
        
        // Check blocked elements
        for (policy in policies.filterIsInstance<SafetyPolicy.BlockedElement>()) {
            if (matchesSelector(action, policy.selector)) {
                return SafetyResult.Deny(reason = policy.reason, policy = policy)
            }
        }
        
        // Check sensitive field masking for SetText
        if (action is Action.SetText) {
            for (policy in policies.filterIsInstance<SafetyPolicy.SensitiveFieldMask>()) {
                for (pattern in policy.patterns) {
                    if (action.text.matches(pattern.toRegex())) {
                        return SafetyResult.Deny(
                            reason = "Attempted to enter sensitive data (matched: $pattern)",
                            policy = policy
                        )
                    }
                }
            }
        }
        
        // Check package allowlist
        val allowlist = policies
            .filterIsInstance<SafetyPolicy.AllowedPackageOnly>()
            .flatMap { it.packageNames }
            .toSet()
        
        if (allowlist.isNotEmpty() && context.currentApp !in allowlist) {
            return SafetyResult.Deny(
                reason = "App not in allowlist: ${context.currentApp}",
                policy = SafetyPolicy.AllowedPackageOnly(allowlist.toList())
            )
        }
        
        // Check blocked packages
        for (policy in policies.filterIsInstance<SafetyPolicy.BlockPackage>()) {
            if (context.currentApp == policy.packageName) {
                return SafetyResult.Deny(reason = policy.reason, policy = policy)
            }
        }
        
        // Check confirmation requirements
        for (policy in policies.filterIsInstance<SafetyPolicy.RequireConfirmation>()) {
            if (matchesConfirmationPolicy(action, policy)) {
                return SafetyResult.RequireUserConfirmation(
                    message = "Confirm: ${policy.reason}",
                    originalAction = action
                )
            }
        }
        
        return SafetyResult.Allow()
    }
    
    private fun matchesSelector(action: Action, selector: ElementSelector): Boolean {
        // In practice, this would need the actual element from the snapshot
        // For now, we check action-level heuristics
        return when (action) {
            is Action.Click, is Action.LongClick, is Action.SetText -> {
                // Would need element lookup - placeholder
                false
            }
            else -> false
        }
    }
    
    private fun matchesConfirmationPolicy(action: Action, policy: SafetyPolicy.RequireConfirmation): Boolean {
        return when {
            policy.actionType == "any" -> true
            policy.actionType == "click" && action is Action.Click -> true
            policy.actionType == "set_text" && action is Action.SetText -> true
            policy.actionType == "purchase" && action is Action.Click -> {
                // Would check element text for purchase keywords
                false
            }
            else -> false
        }
    }
    
    override fun registerPolicy(policy: SafetyPolicy) {
        policies.add(policy)
    }
    
    override fun unregisterPolicy(policy: SafetyPolicy) {
        policies.remove(policy)
    }
    
    override val activePolicies: List<SafetyPolicy>
        get() = policies.toList()
}