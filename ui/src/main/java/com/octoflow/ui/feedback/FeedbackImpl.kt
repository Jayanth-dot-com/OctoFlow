package com.octoflow.ui.feedback

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.PorterDuff
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.octoflow.core.contract.HapticFeedback
import com.octoflow.core.contract.VisualFeedback
import com.octoflow.core.model.Action
import com.octoflow.core.model.UiElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class ErrorWithRetry(
    val element: UiElement,
    val error: String,
    val onRetry: () -> Unit
)

/**
 * System overlay for visual feedback (requires SYSTEM_ALERT_WINDOW permission)
 */
class OverlayVisualFeedback(
    private val context: Context,
    private val windowManager: WindowManager,
    private val accessibilityService: com.octoflow.accessibility.service.OctoAccessibilityService?
) : VisualFeedback {
    
    private var overlayView: OverlayView? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val actionTrail = mutableListOf<ActionTrailPoint>()
    
    override fun showActionIndicator(elementId: Int, action: Action) {
        scope.launch {
            val element = findElement(elementId)
            element?.let { elem ->
                showIndicator(elem, action)
                // Add to action trail
                actionTrail.add(ActionTrailPoint(
                    x = elem.bounds.centerX,
                    y = elem.bounds.centerY,
                    actionType = action::class.simpleName?.removeSuffix("Action") ?: "ACTION",
                    timestamp = System.currentTimeMillis()
                ))
                // Keep only last 10 trail points
                if (actionTrail.size > 10) actionTrail.removeFirst()
                delay(1000)
                hide()
            }
        }
    }
    
    override fun showThinking(text: String) {
        scope.launch {
            overlayView?.setThinkingText(text)
            showOverlay()
        }
    }
    
    override fun hide() {
        scope.launch {
            removeOverlay()
        }
    }
    
    override fun showError(message: String) {
        scope.launch {
            overlayView?.setErrorText(message)
            showOverlay()
            delay(3000)
            hide()
        }
    }
    
    // Enhanced methods
    fun showActionTrail(steps: List<com.octoflow.core.model.ReasoningStep>) {
        scope.launch {
            actionTrail.clear()
            steps.forEach { step ->
                // Would need element lookup - simplified for now
            }
            overlayView?.invalidate()
        }
    }
    
    fun highlightElement(element: UiElement, label: String) {
        scope.launch {
            overlayView?.highlightElement(element, label)
            showOverlay()
            delay(2000)
            overlayView?.clearHighlight()
        }
    }
    
    fun showProgress(current: Int, total: Int) {
        scope.launch {
            overlayView?.setProgress(current, total)
            showOverlay()
        }
    }
    
    fun showThoughtBubble(elementId: Int, thought: String) {
        scope.launch {
            val element = findElement(elementId)
            element?.let { elem ->
                overlayView?.showThoughtBubble(elem, thought)
                showOverlay()
                delay(3000)
                overlayView?.clearThoughtBubble()
            }
        }
    }
    
    fun showErrorWithRetry(elementId: Int, error: String, onRetry: () -> Unit) {
        scope.launch {
            val element = findElement(elementId)
            element?.let { elem ->
                overlayView?.showErrorWithRetry(elem, error, onRetry)
                showOverlay()
            }
        }
    }
    
    private fun showIndicator(element: UiElement, action: Action) {
        val centerX = element.bounds.centerX
        val centerY = element.bounds.centerY
        
        overlayView = OverlayView(context, centerX, centerY, action)
        windowParams = createWindowParams()
        
        try {
            windowManager.addView(overlayView!!, windowParams!!)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun showOverlay() {
        if (overlayView == null) {
            overlayView = OverlayView(context)
            windowParams = createWindowParams()
        }
        try {
            windowManager.addView(overlayView!!, windowParams!!)
        } catch (e: Exception) {
            // Already added
        }
    }
    
    private fun removeOverlay() {
        overlayView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                // Already removed
            }
        }
        overlayView = null
    }
    
    private fun createWindowParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
    }
    
    private fun findElement(elementId: Int): UiElement? {
        val snapshot = accessibilityService?.getLatestSnapshot()
        return snapshot?.rootElement?.let { findElementRecursive(it, elementId) }
    }
    
    private fun findElementRecursive(element: UiElement, targetId: Int): UiElement? {
        if (element.id == targetId) return element
        for (child in element.children) {
            findElementRecursive(child, targetId)?.let { return it }
        }
        return null
    }
    
    data class ActionTrailPoint(
        val x: Int,
        val y: Int,
        val actionType: String,
        val timestamp: Long
    )
    
    inner class OverlayView(
        context: Context,
        private var centerX: Int = 0,
        private var centerY: Int = 0,
        private var action: Action? = null
    ) : View(context) {
        
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 32f
            isFakeBoldText = true
        }
        private val smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 20f
        }
        private var thinkingText: String? = null
        private var errorText: String? = null
        private var pulse = 0f
        private var highlightElement: UiElement? = null
        private var highlightLabel: String? = null
        private var progressCurrent = 0
        private var progressTotal = 0
        private var thoughtBubbleElement: UiElement? = null
        private var thoughtBubbleText: String? = null
        private var errorWithRetry: ErrorWithRetry? = null
        
        init {
            setWillNotDraw(false)
        }
        
        fun setThinkingText(text: String?) {
            thinkingText = text
            errorText = null
            invalidate()
        }
        
        fun setErrorText(text: String?) {
            errorText = text
            thinkingText = null
            invalidate()
        }
        
        fun highlightElement(element: UiElement, label: String) {
            highlightElement = element
            highlightLabel = label
            invalidate()
        }
        
        fun clearHighlight() {
            highlightElement = null
            highlightLabel = null
            invalidate()
        }
        
        fun setProgress(current: Int, total: Int) {
            progressCurrent = current
            progressTotal = total
            invalidate()
        }
        
        fun showThoughtBubble(element: UiElement, thought: String) {
            thoughtBubbleElement = element
            thoughtBubbleText = thought
            invalidate()
        }
        
        fun clearThoughtBubble() {
            thoughtBubbleElement = null
            thoughtBubbleText = null
            invalidate()
        }
        
        fun showErrorWithRetry(element: UiElement, error: String, onRetry: () -> Unit) {
            errorWithRetry = ErrorWithRetry(element, error, onRetry)
            invalidate()
        }
        
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            
            // Clear
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            
            // Draw action trail
            drawActionTrail(canvas)
            
            // Pulse animation for action indicator
            if (action != null) {
                pulse += 0.1f
                val radius = (80 + 20 * Math.sin(pulse.toDouble())).toInt()
                
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 8f
                paint.color = when (action) {
                    is Action.Click -> Color.GREEN
                    is Action.SetText -> Color.BLUE
                    is Action.Swipe -> Color.YELLOW
                    is Action.LongClick -> Color.MAGENTA
                    is Action.Scroll -> Color.CYAN
                    else -> Color.CYAN
                }
                
                canvas.drawCircle(centerX.toFloat(), centerY.toFloat(), radius.toFloat(), paint)
                
                // Center dot
                paint.style = Paint.Style.FILL
                canvas.drawCircle(centerX.toFloat(), centerY.toFloat(), 12f, paint)
                
                // Action label
                val label = when (action) {
                    is Action.Click -> "TAP"
                    is Action.SetText -> "TYPE"
                    is Action.Swipe -> "SWIPE"
                    is Action.LongClick -> "HOLD"
                    is Action.Scroll -> "SCROLL"
                    else -> "ACTION"
                }
                
                val textBounds = android.graphics.Rect()
                textPaint.getTextBounds(label, 0, label.length, textBounds)
                textPaint.color = Color.BLACK
                canvas.drawRect(
                    (centerX - textBounds.width() / 2 - 8).toFloat(),
                    (centerY - radius - textBounds.height() - 16).toFloat(),
                    (centerX + textBounds.width() / 2 + 8).toFloat(),
                    (centerY - radius + 8).toFloat(),
                    paint
                )
                textPaint.color = Color.WHITE
                canvas.drawText(
                    label,
                    (centerX - textBounds.width() / 2f),
                    (centerY - radius - 8).toFloat(),
                    textPaint
                )
            }
            
            // Highlight element
            highlightElement?.let { elem ->
                val bounds = elem.bounds
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 4f
                paint.color = Color.parseColor("#FFD600") // Amber
                canvas.drawRoundRect(
                    bounds.left.toFloat(), bounds.top.toFloat(),
                    bounds.right.toFloat(), bounds.bottom.toFloat(),
                    8f, 8f, paint
                )
                
                // Label
                highlightLabel?.let { label ->
                    val textBounds = android.graphics.Rect()
                    textPaint.getTextBounds(label, 0, label.length, textBounds)
                    paint.style = Paint.Style.FILL
                    paint.color = Color.parseColor("#FFD600")
                    canvas.drawRoundRect(
                        bounds.centerX.toFloat() - textBounds.width() / 2f - 12,
                        bounds.top.toFloat() - textBounds.height() - 24,
                        bounds.centerX.toFloat() + textBounds.width() / 2f + 12,
                        bounds.top.toFloat() - 4,
                        12f, 12f, paint
                    )
                    textPaint.color = Color.BLACK
                    canvas.drawText(
                        label,
                        bounds.centerX.toFloat() - textBounds.width() / 2f,
                        bounds.top.toFloat() - 8,
                        textPaint
                    )
                }
            }
            
            // Progress ring (top center)
            if (progressTotal > 0) {
                val centerX = width / 2f
                val centerY = 60f
                val radius = 40f
                
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 6f
                paint.color = Color.parseColor("#44FFFFFF")
                canvas.drawCircle(centerX, centerY, radius, paint)
                
                paint.color = Color.parseColor("#6750A4")
                paint.strokeCap = Paint.Cap.ROUND
                val sweepAngle = 360f * progressCurrent / progressTotal
                canvas.drawArc(
                    centerX - radius, centerY - radius,
                    centerX + radius, centerY + radius,
                    -90f, sweepAngle, false, paint
                )
                
                // Progress text
                val text = "$progressCurrent/$progressTotal"
                val textBounds = android.graphics.Rect()
                smallTextPaint.getTextBounds(text, 0, text.length, textBounds)
                smallTextPaint.color = Color.WHITE
                canvas.drawText(
                    text,
                    centerX - textBounds.width() / 2f,
                    centerY + textBounds.height() / 2f,
                    smallTextPaint
                )
            }
            
            // Thought bubble
            thoughtBubbleElement?.let { elem ->
                thoughtBubbleText?.let { thought ->
                    val bubbleX = elem.bounds.centerX.toFloat()
                    val bubbleY = elem.bounds.top.toFloat() - 20f
                    
                    val textBounds = android.graphics.Rect()
                    textPaint.getTextBounds(thought, 0, thought.length, textBounds)
                    
                    val padding = 16f
                    val bubbleWidth = (textBounds.width() + padding * 2).coerceAtMost(width * 0.8f)
                    val bubbleHeight = textBounds.height() + padding * 2
                    val bubbleLeft = (bubbleX - bubbleWidth / 2).coerceIn(padding, width - padding - bubbleWidth)
                    val bubbleTop = bubbleY - bubbleHeight
                    
                    // Bubble background
                    paint.style = Paint.Style.FILL
                    paint.color = Color.parseColor("#CC333333")
                    canvas.drawRoundRect(
                        bubbleLeft, bubbleTop,
                        bubbleLeft + bubbleWidth, bubbleTop + bubbleHeight,
                        16f, 16f, paint
                    )
                    
                    // Tail
                    val tailPath = android.graphics.Path()
                    tailPath.moveTo(bubbleX - 12, bubbleY)
                    tailPath.lineTo(bubbleX + 12, bubbleY)
                    tailPath.lineTo(bubbleX, bubbleY + 20)
                    tailPath.close()
                    canvas.drawPath(tailPath, paint)
                    
                    // Text
                    textPaint.color = Color.WHITE
                    canvas.drawText(
                        thought,
                        bubbleLeft + padding,
                        bubbleTop + padding + textBounds.height(),
                        textPaint
                    )
                }
            }
            
            // Error with retry
            errorWithRetry?.let { err ->
                val elem = err.element
                val bubbleX = elem.bounds.centerX.toFloat()
                val bubbleY = elem.bounds.bottom.toFloat() + 20f
                
                val text = "Error: ${err.error}  [Retry]"
                val textBounds = android.graphics.Rect()
                textPaint.getTextBounds(text, 0, text.length, textBounds)
                
                val padding = 16f
                val bubbleWidth = (textBounds.width() + padding * 2).coerceAtMost(width * 0.8f)
                val bubbleHeight = textBounds.height() + padding * 2
                val bubbleLeft = (bubbleX - bubbleWidth / 2).coerceIn(padding, width - padding - bubbleWidth)
                val bubbleTop = bubbleY
                
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#CCFF0000")
                canvas.drawRoundRect(
                    bubbleLeft, bubbleTop,
                    bubbleLeft + bubbleWidth, bubbleTop + bubbleHeight,
                    16f, 16f, paint
                )
                
                textPaint.color = Color.WHITE
                canvas.drawText(
                    text,
                    bubbleLeft + padding,
                    bubbleTop + padding + textBounds.height(),
                    textPaint
                )
            }
            
            // Thinking text overlay
            thinkingText?.let { text ->
                val textBounds = android.graphics.Rect()
                textPaint.getTextBounds(text, 0, text.length, textBounds)
                
                val x = (width - textBounds.width()) / 2f
                val y = height * 0.15f
                
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#CC000000")
                canvas.drawRoundRect(
                    x - 16, y - textBounds.height() - 16,
                    x + textBounds.width() + 16, y + 16,
                    24f, 24f, paint
                )
                
                textPaint.color = Color.WHITE
                canvas.drawText(text, x, y, textPaint)
            }
            
            // Error text overlay
            errorText?.let { text ->
                val textBounds = android.graphics.Rect()
                textPaint.getTextBounds(text, 0, text.length, textBounds)
                
                val x = (width - textBounds.width()) / 2f
                val y = height * 0.85f
                
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#CCFF0000")
                canvas.drawRoundRect(
                    x - 16, y - textBounds.height() - 16,
                    x + textBounds.width() + 16, y + 16,
                    24f, 24f, paint
                )
                
                textPaint.color = Color.WHITE
                canvas.drawText(text, x, y, textPaint)
            }
        }
        
        private fun drawActionTrail(canvas: Canvas) {
            if (actionTrail.size < 2) return
            
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = Color.parseColor("#88FFFFFF")
            
            val path = android.graphics.Path()
            path.moveTo(actionTrail[0].x.toFloat(), actionTrail[0].y.toFloat())
            
            for (i in 1 until actionTrail.size) {
                val point = actionTrail[i]
                path.lineTo(point.x.toFloat(), point.y.toFloat())
                
                // Draw small dot at each point
                val alpha = (255 * i / actionTrail.size).toInt()
                paint.color = Color.argb(alpha, 255, 255, 255)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), 6f, paint)
                paint.style = Paint.Style.STROKE
            }
            
            canvas.drawPath(path, paint)
        }
        
        override fun onTouchEvent(event: MotionEvent): Boolean {
            // Handle retry button tap
            if (event.action == MotionEvent.ACTION_DOWN) {
                errorWithRetry?.let { err ->
                    val elem = err.element
                    val bubbleX = elem.bounds.centerX.toFloat()
                    val bubbleY = elem.bounds.bottom.toFloat() + 20f
                    val text = "Error: ${err.error}  [Retry]"
                    val textBounds = android.graphics.Rect()
                    textPaint.getTextBounds(text, 0, text.length, textBounds)
                    val padding = 16f
                    val bubbleWidth = (textBounds.width() + padding * 2).coerceAtMost(width * 0.8f)
                    val bubbleHeight = textBounds.height() + padding * 2
                    val bubbleLeft = (bubbleX - bubbleWidth / 2).coerceIn(padding, width - padding - bubbleWidth)
                    val bubbleTop = bubbleY
                    
                    // Check if tap is on "Retry" part (right side of bubble)
                    val retryLeft = bubbleLeft + bubbleWidth - textPaint.measureText("  [Retry]") - padding
                    if (event.x >= retryLeft && event.x <= bubbleLeft + bubbleWidth - padding &&
                        event.y >= bubbleTop && event.y <= bubbleTop + bubbleHeight) {
                        err.onRetry()
                        errorWithRetry = null
                        invalidate()
                        return true
                    }
                }
            }
            return false // Pass through touches
        }
    }
    
    fun destroy() {
        scope.cancel()
        removeOverlay()
    }
}

/**
 * Haptic feedback implementation using Vibrator
 */
class AndroidHapticFeedback(private val context: Context) : HapticFeedback {
    
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
    
    override fun light() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator?.vibrate(android.os.VibrationEffect.createOneShot(10, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(10)
            }
        } catch (e: Exception) {
            android.util.Log.w("Haptic", "Vibrate light failed", e)
        }
    }
    
    override fun medium() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator?.vibrate(android.os.VibrationEffect.createOneShot(30, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(30)
            }
        } catch (e: Exception) {
            android.util.Log.w("Haptic", "Vibrate medium failed", e)
        }
    }
    
    override fun heavy() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator?.vibrate(android.os.VibrationEffect.createOneShot(60, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(60)
            }
        } catch (e: Exception) {
            android.util.Log.w("Haptic", "Vibrate heavy failed", e)
        }
    }
    
    override fun success() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator?.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 50, 50, 50), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 50, 50, 50), -1)
            }
        } catch (e: Exception) {
            android.util.Log.w("Haptic", "Vibrate success failed", e)
        }
    }
    
    override fun error() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator?.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 100, 50, 100, 50, 100), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 100, 50, 100, 50, 100), -1)
            }
        } catch (e: Exception) {
            android.util.Log.w("Haptic", "Vibrate error failed", e)
        }
    }
}