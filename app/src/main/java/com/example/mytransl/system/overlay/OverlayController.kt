package com.example.mytransl.system.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import android.widget.ProgressBar
import androidx.core.graphics.toColorInt

class OverlayController(
    private val context: Context
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var floatingView: View? = null
    private var floatingParams: WindowManager.LayoutParams? = null
    private var floatingLabel: TextView? = null
    private var floatingProgress: ProgressBar? = null
    private var floatingPulseAnimator: ObjectAnimator? = null
    private var floatingLoading: Boolean = false

    private var textContainer: View? = null
    private var textView: TextView? = null
    private var textParams: WindowManager.LayoutParams? = null
    private var textHandleContainer: View? = null
    private var textHandleParams: WindowManager.LayoutParams? = null
    
    // 独立浮窗关闭回调
    var onTextOverlayClosed: (() -> Unit)? = null

    private var statusContainer: View? = null
    private var statusTextView: TextView? = null
    private var statusParams: WindowManager.LayoutParams? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var statusHideRunnable: Runnable? = null

    private var selectionContainer: View? = null
    private var selectionView: RegionSelectionView? = null
    private var selectionParams: WindowManager.LayoutParams? = null

    private var contentContainer: View? = null
    private var contentView: ContentOverlayView? = null
    private var contentParams: WindowManager.LayoutParams? = null
    private var contentCloseView: View? = null
    private var contentCloseParams: WindowManager.LayoutParams? = null

    private var autoControlView: View? = null
    private var autoControlParams: WindowManager.LayoutParams? = null
    private var autoControlButton: TextView? = null
    private var autoVisibilityButton: TextView? = null
    private var currentSelectedRect: android.graphics.RectF? = null

    private fun overlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    fun showFloatingBall(onClick: () -> Unit) {
        if (floatingView != null) return

        val size = dp(52f)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor("#1976D2".toColorInt())
        }
        val label = TextView(context).apply {
            text = "译"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, 0, 0, 0)
            gravity = Gravity.CENTER
        }
        val progress = ProgressBar(context).apply {
            isIndeterminate = true
            visibility = View.GONE
            indeterminateDrawable?.setTint(Color.WHITE)
        }
        val view = object : FrameLayout(context) {
            override fun performClick(): Boolean {
                return super.performClick()
            }
        }.apply {
            background = bg
            addView(
                label,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                progress,
                FrameLayout.LayoutParams(
                    dp(22f),
                    dp(22f),
                    Gravity.CENTER
                )
            )
            setOnClickListener { onClick() }
            elevation = dp(6f).toFloat()
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            x = 0
            y = dp(180f)
            gravity = Gravity.TOP or Gravity.END
        }

        view.setOnTouchListener(DraggableTouchListener(params, windowManager))

        runCatching {
            windowManager.addView(view, params)
        }.onSuccess {
            floatingView = view
            floatingParams = params
            floatingLabel = label
            floatingProgress = progress
            setFloatingBallLoading(false)
        }
    }

    fun setFloatingBallVisible(visible: Boolean) {
        floatingView?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    fun setFloatingBallLoading(loading: Boolean) {
        if (floatingLoading == loading) return
        floatingLoading = loading
        val view = floatingView ?: return
        val label = floatingLabel ?: return
        val progress = floatingProgress ?: return

        mainHandler.post {
            if (loading) {
                progress.visibility = View.GONE
                label.visibility = View.VISIBLE
                (view.background as? GradientDrawable)?.setColor("#FB8C00".toColorInt())
                floatingPulseAnimator?.cancel()
                view.alpha = 1f
            } else {
                progress.visibility = View.GONE
                label.visibility = View.VISIBLE
                (view.background as? GradientDrawable)?.setColor("#1976D2".toColorInt())
                floatingPulseAnimator?.cancel()
                view.alpha = 1f
            }
        }
    }

    fun showTextOverlay() {
        if (textContainer != null) return

        // 1. Common Dimensions
        val headerHeight = dp(16f)
        val handleWidth = dp(32f)
        val handleHeight = dp(3f)
        val initialX = dp(60f)
        val initialY = dp(120f)

        // 2. Create the TEXT CONTENT window (Transparent to touch)
        val tv = TextView(context).apply {
            setTextColor("#F8FAFC".toColorInt())
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setShadowLayer(6f, 0f, 3f, "#A0000000".toColorInt())
            maxWidth = context.resources.displayMetrics.widthPixels - dp(48f)
        }

        val textBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            val r = dp(16f).toFloat()
            cornerRadii = floatArrayOf(0f,0f, 0f,0f, r,r, r,r) // Bottom rounded only
            setColor("#E61E293B".toColorInt())
            setStroke(dp(1f), "#20E2E8F0".toColorInt())
        }

        val textLayout = FrameLayout(context).apply {
            background = textBg
            setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            addView(tv)
        }

        val textWinParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY + headerHeight // Positioned below the handle
        }

        // 3. Create the HANDLE window (Draggable)
        val handleIndicator = View(context).apply {
            background = GradientDrawable().apply {
                setColor("#CC94A3B8".toColorInt())
                cornerRadius = handleHeight / 2f
            }
        }

        val close = TextView(context).apply {
            text = "✕"
            setTextColor("#E2E8F0".toColorInt())
            textSize = 14f
            gravity = Gravity.CENTER
            setOnClickListener { 
                hideTextOverlay()
                // 通知 TranslationService 更新状态
                onTextOverlayClosed?.invoke()
            }
            setPadding(dp(8f), 0, dp(10f), 0)
        }

        val handleLayout = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor("#E61E293B".toColorInt())
                val r = dp(16f).toFloat()
                cornerRadii = floatArrayOf(r,r, r,r, 0f,0f, 0f,0f) // Top rounded only
                setStroke(dp(1f), "#20E2E8F0".toColorInt())
            }
            addView(handleIndicator, FrameLayout.LayoutParams(handleWidth, handleHeight).apply {
                gravity = Gravity.CENTER
            })
            addView(close, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.END
            })
        }

        val handleWinParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, // Now dynamic!
            headerHeight,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }

        // Strictly sync handle window with text content window
        textLayout.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val w = textLayout.width
            if (w > 0) {
                handleWinParams.width = w
                handleWinParams.x = textWinParams.x
                handleWinParams.y = textWinParams.y - headerHeight
                runCatching { windowManager.updateViewLayout(handleLayout, handleWinParams) }
            }
        }

        // Sync logic: Dragging handle moves text window
        handleLayout.setOnTouchListener(object : View.OnTouchListener {
            private var lastX = 0f
            private var lastY = 0f
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        lastX = event.rawX
                        lastY = event.rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - lastX
                        val dy = event.rawY - lastY
                        lastX = event.rawX
                        lastY = event.rawY

                        // Move the CONTENT window primarily
                        textWinParams.x += dx.toInt()
                        textWinParams.y += dy.toInt()
                        runCatching { windowManager.updateViewLayout(textLayout, textWinParams) }
                        
                        // Handler will follow via OnLayoutChangeListener
                    }
                }
                return true
            }
        })

        runCatching {
            windowManager.addView(textLayout, textWinParams)
            windowManager.addView(handleLayout, handleWinParams)
        }.onSuccess {
            textContainer = textLayout
            textView = tv
            textParams = textWinParams
            textHandleContainer = handleLayout
            textHandleParams = handleWinParams
        }
    }

    fun setTextOverlayVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        textContainer?.visibility = v
        textHandleContainer?.visibility = v
    }

    fun updateTextOverlay(text: String) {
        if (textView == null) showTextOverlay()
        textView?.text = text
    }

    fun moveTextOverlayTo(x: Int, y: Int) {
        val hp = textHandleParams ?: return
        val tp = textParams ?: return
        val hView = textHandleContainer ?: return
        val tView = textContainer ?: return
        
        hp.x = x
        hp.y = y
        tp.x = x
        tp.y = y + hp.height
        
        runCatching { windowManager.updateViewLayout(hView, hp) }
        runCatching { windowManager.updateViewLayout(tView, tp) }
    }

    fun ensureTextOverlayAwayFromRegion(region: android.graphics.RectF) {
        val window = getTextOverlayBounds() ?: return
        if (!android.graphics.RectF.intersects(window, region)) return
        
        // Overlap detected! Find a new Y position
        val metrics = context.resources.displayMetrics
        val screenHeight = metrics.heightPixels
        val headerHeight = textHandleParams?.height ?: dp(32f)
        
        // Try placing above the region first
        var targetY = (region.top - window.height() - dp(20f)).toInt()
        
        // If not enough space above, try below the region
        if (targetY < dp(40f)) {
            targetY = (region.bottom + dp(20f)).toInt()
        }
        
        // Final safety check to keep it on screen
        val maxY = screenHeight - window.height().toInt() - dp(40f)
        targetY = targetY.coerceIn(dp(40f), maxY)
        
        moveTextOverlayTo(window.left.toInt(), targetY)
    }
    fun getTextOverlayBounds(): android.graphics.RectF? {
        val handle = textHandleContainer ?: return null
        val content = textContainer ?: return null
        
        val locH = IntArray(2)
        handle.getLocationOnScreen(locH)
        val rectH = android.graphics.RectF(locH[0].toFloat(), locH[1].toFloat(), 
                                        (locH[0] + handle.width).toFloat(), (locH[1] + handle.height).toFloat())
        
        val locC = IntArray(2)
        content.getLocationOnScreen(locC)
        val rectC = android.graphics.RectF(locC[0].toFloat(), locC[1].toFloat(), 
                                        (locC[0] + content.width).toFloat(), (locC[1] + content.height).toFloat())
        
        val union = android.graphics.RectF(rectH)
        union.union(rectC)
        return union
    }

    fun hideTextOverlay() {
        textContainer?.let { runCatching { windowManager.removeView(it) } }
        textHandleContainer?.let { runCatching { windowManager.removeView(it) } }
        textContainer = null
        textHandleContainer = null
        textView = null
        textParams = null
        textHandleParams = null
    }

    fun showStatusOverlay(text: String, durationMs: Long = 2000) {
        val tv = statusTextView ?: TextView(context).apply {
            setTextColor("#F8FAFC".toColorInt())
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 3f, "#A0000000".toColorInt())
        }.also { statusTextView = it }

        tv.text = text

        val container = statusContainer ?: FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(999f).toFloat()
                setColor("#B312141B".toColorInt())
            }
            elevation = dp(10f).toFloat()
            addView(
                tv,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }.also { statusContainer = it }

        val params = statusParams ?: WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = dp(88f)
        }.also { statusParams = it }

        if (container.parent == null) {
            runCatching { windowManager.addView(container, params) }
        } else {
            runCatching { windowManager.updateViewLayout(container, params) }
        }

        statusHideRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { hideStatusOverlay() }
        statusHideRunnable = r
        mainHandler.postDelayed(r, durationMs)
    }

    fun hideStatusOverlay() {
        statusHideRunnable?.let { mainHandler.removeCallbacks(it) }
        statusHideRunnable = null
        statusContainer?.let { runCatching { windowManager.removeView(it) } }
        statusContainer = null
        statusTextView = null
        statusParams = null
    }

    private var lastKnownOffsetY: Int = 0

    init {
        // Try to get status bar height as initial offset
        val res = context.resources
        val id = res.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) {
            lastKnownOffsetY = res.getDimensionPixelSize(id)
            android.util.Log.d("OverlayController", "Initialized lastKnownOffsetY from resource: $lastKnownOffsetY")
        }
    }

    fun showSelectionOverlay(
        onConfirm: (RectF, Int, Int) -> Unit,
        onCancel: () -> Unit
    ) {
        if (selectionContainer != null) return

        lateinit var regionView: RegionSelectionView
        regionView = RegionSelectionView(
            context = context,
            onConfirm = { rect ->
                val w = regionView.width
                val h = regionView.height
                
                // Get the view's position on screen to convert from view coordinates to screen coordinates
                val location = IntArray(2)
                regionView.getLocationOnScreen(location)
                val offsetX = location[0].toFloat()
                val offsetY = location[1].toFloat()
                
                // Update last known offset
                if (offsetY > 0) {
                    lastKnownOffsetY = offsetY.toInt()
                }
                
                // Adjust rect to screen coordinates
                val screenRect = RectF(
                    rect.left + offsetX,
                    rect.top + offsetY,
                    rect.right + offsetX,
                    rect.bottom + offsetY
                )
                
                android.util.Log.d("OverlayController", "RegionSelectionView size: ${w}x${h}")
                android.util.Log.d("OverlayController", "View offset on screen: ($offsetX, $offsetY)")
                android.util.Log.d("OverlayController", "Selected rect from view: $rect")
                android.util.Log.d("OverlayController", "Adjusted to screen coords: $screenRect")
                
                hideSelectionOverlay()
                onConfirm(screenRect, w, h)
            },
            onCancel = {
                hideSelectionOverlay()
                onCancel()
            }
        )

        val container = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(
                regionView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            x = 0
            y = 0
        }

        runCatching {
            windowManager.addView(container, params)
        }.onSuccess {
            selectionContainer = container
            selectionView = regionView
            selectionParams = params
        }
    }

    fun hideSelectionOverlay() {
        selectionContainer?.let { runCatching { windowManager.removeView(it) } }
        selectionContainer = null
        selectionView = null
        selectionParams = null
    }

    data class ContentOverlayItem(
        val bounds: RectF,
        val text: String,
        val isVertical: Boolean = false
    )

    fun updateContentOverlay(items: List<ContentOverlayItem>) {
        // We might want to show the overlay even if items are empty (e.g. for region outline)
        if (contentView == null && (items.isNotEmpty() || currentSelectedRect != null)) {
            showContentOverlay()
        }
        
        contentView?.setItems(items)
        
        if (items.isEmpty() && currentSelectedRect == null) {
            hideContentOverlay()
            hideContentClose()
        } else {
            // Keep it visible or handle sub-view visibility
            if (autoControlView == null && items.isNotEmpty()) {
                showContentClose()
            }
        }
    }

    fun getContentOverlaySize(): Pair<Int, Int>? {
        val view = contentView ?: return null
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return null
        return w to h
    }

    fun getContentOverlayOffset(): Pair<Int, Int>? {
        val view = contentView ?: return null
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return location[0] to location[1]
    }

    fun setContentOverlaySelectedRect(rect: android.graphics.RectF?) {
        currentSelectedRect = rect
        contentView?.setSelectedRect(rect)
    }

    fun setContentOverlayVisible(visible: Boolean) {
        contentContainer?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        if (!visible) {
            hideContentClose()
        } else if (contentView != null) {
            // Avoid showing the separate Close button if AutoControl (the pill) is active
            if (autoControlView == null) {
                showContentClose()
            } else {
                hideContentClose()
            }
        }
    }

    fun hideContentOverlay() {
        contentContainer?.let { runCatching { windowManager.removeView(it) } }
        contentContainer = null
        contentView = null
        contentParams = null
        hideContentClose()
    }

    private fun showContentOverlay() {
        if (contentContainer != null) return

        val overlayView = ContentOverlayView(context).apply {
            setSelectedRect(currentSelectedRect)
        }
        val container = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(
                overlayView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, // 强制全屏，无视状态栏限制
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        runCatching { windowManager.addView(container, params) }.onSuccess {
            contentContainer = container
            contentView = overlayView
            contentParams = params
        }
    }

    fun hideAll() {
        hideSelectionOverlay()
        hideStatusOverlay()
        hideContentOverlay()
        hideTextOverlay()
        hideAutoControl()
        floatingPulseAnimator?.cancel()
        floatingPulseAnimator = null
        floatingView?.let { runCatching { windowManager.removeView(it) } }
        floatingView = null
        floatingParams = null
        floatingLabel = null
        floatingProgress = null
        floatingLoading = false
    }

    private fun showContentClose() {
        if (contentCloseView != null || contentContainer == null) return

        val size = dp(32f)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor("#66000000".toColorInt())
        }
        val label = TextView(context).apply {
            text = "×"
            setTextColor("#E2E8F0".toColorInt())
            textSize = 18f
            gravity = Gravity.CENTER
        }
        val view = FrameLayout(context).apply {
            background = bg
            elevation = dp(10f).toFloat()
            addView(
                label,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            setOnClickListener {
                hideContentOverlay()
            }
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12f)
            y = dp(20f)
        }

        runCatching { windowManager.addView(view, params) }.onSuccess {
            contentCloseView = view
            contentCloseParams = params
        }
    }

    private fun hideContentClose() {
        contentCloseView?.let { runCatching { windowManager.removeView(it) } }
        contentCloseView = null
        contentCloseParams = null
    }

    fun showAutoControl(onToggle: () -> Unit, onVisibility: () -> Unit, onStop: () -> Unit) {
        if (autoControlView != null) return
        hideContentClose() // Use the pill instead of the circular X

        val container = FrameLayout(context)
        val bg = GradientDrawable().apply {
            setColor("#801E293B".toColorInt()) // More transparent for "frosted" effect
            cornerRadius = dp(20f).toFloat()
            setStroke(dp(1f), "#20E2E8F0".toColorInt()) // Subtle border
        }

        // 1. Play/Pause Button
        val playPauseBtn = TextView(context).apply {
            text = "||" // Using flat symbols to avoid emoji colorization
            setTextColor("#F8FAFC".toColorInt())
            typeface = Typeface.MONOSPACE
            textSize = 14f
            setPadding(dp(16f), dp(8f), dp(10f), dp(8f))
            gravity = Gravity.CENTER
            setOnClickListener { onToggle() }
        }.also { autoControlButton = it }

        // 2. Visibility Toggle (Eye-ish)
        val visibilityBtn = TextView(context).apply {
            text = "⊙" // Neutral symbol
            setTextColor("#F8FAFC".toColorInt())
            textSize = 18f
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            gravity = Gravity.CENTER
            setOnClickListener { onVisibility() }
        }.also { autoVisibilityButton = it }

        // 3. Stop Button
        val stopBtn = TextView(context).apply {
            text = "×"
            setTextColor("#94A3B8".toColorInt())
            textSize = 20f
            setPadding(dp(10f), dp(8f), dp(16f), dp(8f))
            gravity = Gravity.CENTER
            setOnClickListener { onStop() }
        }

        val row = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg
            addView(playPauseBtn)
            addView(visibilityBtn)
            addView(stopBtn)
        }
        container.addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(16f)
            y = dp(70f)
        }

        runCatching { windowManager.addView(container, params) }.onSuccess {
            autoControlView = container
            autoControlParams = params
            autoControlButton = playPauseBtn
            autoVisibilityButton = visibilityBtn
        }
    }

    fun updateAutoControl(isRunning: Boolean) {
        autoControlButton?.text = if (isRunning) "||" else "|>"
        autoControlButton?.setTextColor(if (isRunning) "#F8FAFC".toColorInt() else "#94A3B8".toColorInt())
    }

    fun updateAutoVisibility(isVisible: Boolean) {
        autoVisibilityButton?.text = if (isVisible) "⊙" else "·"
        autoVisibilityButton?.alpha = if (isVisible) 1.0f else 0.5f
    }

    fun hideAutoControl() {
        autoControlView?.let { runCatching { windowManager.removeView(it) } }
        autoControlView = null
        autoControlButton = null
        autoVisibilityButton = null
        autoControlParams = null
    }

    private fun dp(value: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            context.resources.displayMetrics
        ).toInt()
    }
}

private class ContentOverlayView(
    context: Context
) : View(context) {
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = "#B312141B".toColorInt()
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f).toFloat()
        color = "#33FFFFFF".toColorInt()
    }
    private val regionBoundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f).toFloat()
        color = "#EA580C".toColorInt() // Orange-600
        alpha = 180
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#F8FAFC".toColorInt()
        typeface = Typeface.DEFAULT_BOLD
    }
    private val tmpRect = RectF()
    private var selectedRegion: RectF? = null

    @Volatile
    private var items: List<OverlayController.ContentOverlayItem> = emptyList()

    fun setItems(newItems: List<OverlayController.ContentOverlayItem>) {
        items = newItems
        postInvalidateOnAnimation()
    }

    fun setSelectedRect(rect: RectF?) {
        selectedRegion = rect
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val list = items
        val region = selectedRegion
        if (list.isEmpty() && region == null) return

        val location = IntArray(2)
        getLocationOnScreen(location)
        val dx = -location[0].toFloat()
        val dy = -location[1].toFloat()

        canvas.save()
        canvas.translate(dx, dy)

        // Draw selection region outline if present
        selectedRegion?.let {
            canvas.drawRect(it, regionBoundPaint)
        }

        // Draw everything in absolute screen coordinates
        for (item in list) {
            tmpRect.set(item.bounds)
            if (tmpRect.width() < 6f || tmpRect.height() < 6f) continue

            // Padding logic (keep it simple)
            val pad = dp(3f).toFloat()
            var left = tmpRect.left + pad
            var top = tmpRect.top + pad
            var right = tmpRect.right - pad
            var bottom = tmpRect.bottom - pad
            
            if (right <= left || bottom <= top) continue

            val innerHPad = dp(4f).toFloat()
            val innerVPad = dp(2f).toFloat()
            val radius = dp(8f).toFloat()

            if (item.isVertical) {
                // --- Vertical Logic ---
                val minH = dp(72f).toFloat()
                if (bottom - top < minH) {
                    val cy = (top + bottom) / 2f
                    top = cy - minH / 2f
                    bottom = cy + minH / 2f
                }

                val availW = (right - left - 2 * innerHPad).toInt().coerceAtLeast(1)
                val availH = (bottom - top - 2 * innerVPad).coerceAtLeast(1f)

                // Vertical text simulation: "A\nB\nC"
                val sb = StringBuilder()
                var i = 0
                while (i < item.text.length) {
                    val cp = item.text.codePointAt(i)
                    sb.append(String(Character.toChars(cp)))
                    i += Character.charCount(cp)
                    if (i < item.text.length) sb.append('\n')
                }
                val vText = sb.toString()
                
                // Size based on width (column width)
                val baseSize = (availW.toFloat() * 0.80f).coerceIn(sp(10f), sp(22f))
                textPaint.textSize = baseSize

                val layout = StaticLayout.Builder
                    .obtain(vText, 0, vText.length, textPaint, maxOf(availW, baseSize.toInt() + 10))
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setIncludePad(false)
                    .setLineSpacing(0f, 1f)
                    .build()

                // Expand WIDTH to fit text
                val neededW = layout.width.toFloat()
                val availableInnerW = (right - left - 2 * innerHPad).coerceAtLeast(1f)
                val expansion = (neededW - availableInnerW) / 2f
                left -= expansion
                right += expansion
                
                // Recalculate startX
                val finalInnerW = right - left - 2 * innerHPad
                val startX = left + innerHPad + (finalInnerW - neededW) / 2f
                
                // Recalculate startY (center vertically in available height)
                val neededH = layout.height.toFloat()
                val finalInnerH = bottom - top - 2 * innerVPad
                // If text is taller than box, expand box height too
                if (neededH > finalInnerH) {
                     val hExp = (neededH - finalInnerH) / 2f
                     top -= hExp
                     bottom += hExp
                }
                val startY = top + innerVPad + (bottom - top - 2 * innerVPad - neededH) / 2f

                // Draw Background
                tmpRect.set(left, top, right, bottom)
                backgroundPaint.color = Color.BLACK
                backgroundPaint.alpha = 40
                canvas.drawRoundRect(left + 2, top + 2, right + 2, bottom + 2, radius, radius, backgroundPaint)

                backgroundPaint.color = "#E61E293B".toColorInt()
                backgroundPaint.alpha = 230
                canvas.drawRoundRect(tmpRect, radius, radius, backgroundPaint)
                canvas.drawRoundRect(tmpRect, radius, radius, borderPaint)

                // Draw Text
                canvas.save()
                canvas.translate(left, startY)  // 去掉 innerHPad，让 StaticLayout 自己水平居中
                layout.draw(canvas)
                canvas.restore()

            } else {
                // --- Horizontal / Adaptive Logic ---
                // 设置最小框体宽度，确保文字可读
                val minBoxWidth = sp(12f)  // 最小框体宽度，约能显示 1 个中文字符
                
                val originalAvailW = (right - left - 2 * innerHPad).toInt().coerceAtLeast(1)
                val availH = (bottom - top - 2 * innerVPad).coerceAtLeast(1f)
                
                // 智能字号：取宽高较小值作为基准，确保文字能放入框内
                val minDim = if (originalAvailW.toFloat() < availH) originalAvailW.toFloat() else availH
                val baseSize = (minDim * 0.80f).coerceIn(sp(10f), sp(22f))
                textPaint.textSize = baseSize

                // 基于原始 OCR 宽度计算最大扩展倍数（限制为 1.8 倍，减少遮挡）
                val textWidth = textPaint.measureText(item.text)
                val maxExpandedW = (originalAvailW * 1.8f).toInt()
                
                val minTextW = sp(11f).toInt()
                val targetW = kotlin.math.ceil(textWidth).toInt().coerceAtLeast(minTextW)
                
                // 计算实际需要的布局宽度
                val layoutW = if (targetW > originalAvailW) {
                    kotlin.math.min(targetW, maxExpandedW)
                } else {
                    originalAvailW
                }
                
                // 应用最小宽度限制（确保框体至少有 minBoxWidth 宽）
                val finalLayoutW = layoutW.coerceAtLeast(minBoxWidth.toInt())

                val layout = StaticLayout.Builder
                    .obtain(item.text, 0, item.text.length, textPaint, finalLayoutW)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setIncludePad(false)
                    .setLineSpacing(0f, 1f)
                    .build()

                // 根据最终布局宽度扩展框体
                if (finalLayoutW > originalAvailW) {
                    val expansionW = (finalLayoutW - originalAvailW) / 2f
                    left -= expansionW
                    right += expansionW
                }
    
                val neededH = layout.height.toFloat()
                val availableInnerH = (bottom - top - 2 * innerVPad).coerceAtLeast(1f)
                
                // 移除高度膨胀限制，确保文字完整显示（特别是当 OCR 框体过小时）
                val expansion = (neededH - availableInnerH) / 2f
                top -= expansion
                bottom += expansion
                
                val finalInnerH = bottom - top - 2 * innerVPad
                var startY = top + innerVPad + (finalInnerH - neededH) / 2f
                
                tmpRect.set(left, top, right, bottom)
                backgroundPaint.color = Color.BLACK
                backgroundPaint.alpha = 40
                canvas.drawRoundRect(left + 2, top + 2, right + 2, bottom + 2, radius, radius, backgroundPaint)
    
                // 降低背景透明度，减少遮挡时的视觉冲突
                backgroundPaint.color = "#B31E293B".toColorInt()  // alpha = 179，约 70% 不透明
                backgroundPaint.alpha = 179
                canvas.drawRoundRect(tmpRect, radius, radius, backgroundPaint)
                canvas.drawRoundRect(tmpRect, radius, radius, borderPaint)
    
                canvas.save()
                canvas.clipRect(left, top, right, bottom)
                canvas.translate(left, startY)  // 去掉 innerHPad，让 StaticLayout 自己水平居中
                layout.draw(canvas)
                canvas.restore()
            }
        }
        canvas.restore()
    }

    private fun dp(value: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            context.resources.displayMetrics
        ).toInt()
    }

    private fun sp(value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            value,
            context.resources.displayMetrics
        )
    }
}

private class DraggableTouchListener(
    private val params: WindowManager.LayoutParams,
    private val windowManager: WindowManager
) : View.OnTouchListener {
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var touchSlop = -1

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (touchSlop < 0) {
                    touchSlop = ViewConfiguration.get(v.context).scaledTouchSlop
                }
                lastX = event.rawX
                lastY = event.rawY
                downX = event.rawX
                downY = event.rawY
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastX
                val dy = event.rawY - lastY
                val diffX = event.rawX - downX
                val diffY = event.rawY - downY
                if (!dragging && diffX * diffX + diffY * diffY > touchSlop * touchSlop) {
                    dragging = true
                }
                lastX = event.rawX
                lastY = event.rawY
                if (dragging) {
                    params.x = (params.x - dx).toInt()
                    params.y = (params.y + dy).toInt()
                    runCatching { windowManager.updateViewLayout(v, params) }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) {
                    v.performClick()
                }
                dragging = false
                return true
            }
        }
        return true
    }
}

private class OverlayDragTouchListener(
    private val params: WindowManager.LayoutParams,
    private val windowManager: WindowManager,
    private val targetView: View
) : View.OnTouchListener {
    private var lastX = 0f
    private var lastY = 0f

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.rawX
                lastY = event.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastX
                val dy = event.rawY - lastY
                lastX = event.rawX
                lastY = event.rawY
                params.x = (params.x + dx).toInt()
                params.y = (params.y + dy).toInt()
                runCatching { windowManager.updateViewLayout(targetView, params) }
                return true
            }
        }
        return false
    }
}

private class OverlayDragTouchListenerHeaderRegionExcludeView(
    private val params: WindowManager.LayoutParams,
    private val windowManager: WindowManager,
    private val targetView: View,
    private val headerHeightPx: Int,
    private val excludeView: View
) : View.OnTouchListener {
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private val rect = Rect()

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.y > headerHeightPx) {
                    dragging = false
                    return false
                }
                excludeView.getHitRect(rect)
                val inExclude = rect.contains(event.x.toInt(), event.y.toInt())
                if (inExclude) {
                    dragging = false
                    return false
                }
                dragging = true
                lastX = event.rawX
                lastY = event.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                val dx = event.rawX - lastX
                val dy = event.rawY - lastY
                lastX = event.rawX
                lastY = event.rawY
                params.x = (params.x + dx).toInt()
                params.y = (params.y + dy).toInt()
                runCatching { windowManager.updateViewLayout(targetView, params) }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return false
            }
        }
        return false
    }
}

private class RegionSelectionView(
    context: Context,
    private val onConfirm: (RectF) -> Unit,
    private val onCancel: () -> Unit
) : View(context) {
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = "#88000000".toColorInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f).toFloat()
        color = "#3B82F6".toColorInt()
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = "#2563EB".toColorInt()
    }
    private val actionConfirmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = "#22C55E".toColorInt()
    }
    private val actionCancelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = "#EF4444".toColorInt()
    }
    private val actionIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(18f)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#F8FAFC".toColorInt()
        textSize = sp(18f)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#E2E8F0".toColorInt()
        textSize = sp(12f)
        textAlign = Paint.Align.CENTER
    }

    private val rect = RectF()
    private val minSize = dp(24f).toFloat()
    private val handleRadius = dp(7f).toFloat()
    private val handleHitRadius = dp(22f).toFloat()
    private val actionRadius = dp(18f).toFloat()
    private val actionHitRadius = dp(24f).toFloat()
    private val actionGap = dp(10f).toFloat()
    private val actionCornerOffset = dp(8f).toFloat()
    private val minConfirmSize = dp(20f).toFloat()

    private var mode: Int = 0
    private var activeHandle: Int = 0
    private var lastX = 0f
    private var lastY = 0f
    private var anchorX = 0f
    private var anchorY = 0f
    private var activeAction: Int = 0

    fun currentRect(): RectF = RectF(rect)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        android.util.Log.d("RegionSelectionView", "onSizeChanged: ${w}x${h}")
        if (rect.isEmpty) {
            val left = w * 0.08f
            val right = w * 0.92f
            val top = h * 0.20f
            val bottom = h * 0.70f
            rect.set(left, top, right, bottom)
            android.util.Log.d("RegionSelectionView", "Initial rect: $rect")
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        canvas.drawRect(0f, 0f, w, rect.top, scrimPaint)
        canvas.drawRect(0f, rect.bottom, w, h, scrimPaint)
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, scrimPaint)
        canvas.drawRect(rect.right, rect.top, w, rect.bottom, scrimPaint)

        canvas.drawRect(rect, borderPaint)

        for ((x, y) in handlePoints()) {
            canvas.drawCircle(x, y, handleRadius, handlePaint)
        }

        val (confirmCx, confirmCy, cancelCx, cancelCy, cancelOnLeft) = actionButtonCenters()
        canvas.drawCircle(confirmCx, confirmCy, actionRadius, actionConfirmPaint)
        canvas.drawCircle(cancelCx, cancelCy, actionRadius, actionCancelPaint)

        val iconYAdjust = (actionIconPaint.descent() + actionIconPaint.ascent()) / 2f
        canvas.drawText("✓", confirmCx, confirmCy - iconYAdjust, actionIconPaint)
        canvas.drawText("×", cancelCx, cancelCy - iconYAdjust, actionIconPaint)

        val title = "选择翻译区域"
        val note = "注意：避免将翻译结果覆盖在此区域"
        val titleY = rect.centerY() - dp(6f)
        val noteY = rect.centerY() + dp(16f)
        canvas.drawText(title, rect.centerX(), titleY, titlePaint)
        canvas.drawText(note, rect.centerX(), noteY, notePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y

                val action = findActionButton(event.x, event.y)
                if (action != 0) {
                    mode = 4
                    activeAction = action
                    return true
                }

                val handle = findHandle(event.x, event.y)
                if (handle != 0) {
                    mode = 2
                    activeHandle = handle
                    return true
                }

                if (rect.contains(event.x, event.y)) {
                    mode = 1
                    return true
                }

                mode = 3
                activeHandle = 4
                anchorX = event.x
                anchorY = event.y
                rect.set(anchorX, anchorY, anchorX, anchorY)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x
                lastY = event.y

                when (mode) {
                    1 -> moveBy(dx, dy)
                    2 -> resizeTo(event.x, event.y)
                    3 -> resizeTo(event.x, event.y)
                    4 -> Unit
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == 4) {
                    val action = activeAction
                    val stillIn = findActionButton(event.x, event.y)
                    if (action != 0 && action == stillIn) {
                        if (action == 1) {
                            val r = currentRect()
                            if (r.width() >= minConfirmSize && r.height() >= minConfirmSize) {
                                onConfirm(r)
                            }
                        } else if (action == 2) {
                            onCancel()
                        }
                    }
                }
                mode = 0
                activeHandle = 0
                activeAction = 0
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun moveBy(dx: Float, dy: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        val rw = rect.width()
        val rh = rect.height()
        var left = rect.left + dx
        var top = rect.top + dy
        left = left.coerceIn(0f, w - rw)
        top = top.coerceIn(0f, h - rh)
        rect.set(left, top, left + rw, top + rh)
    }

    private fun resizeTo(x: Float, y: Float) {
        val w = width.toFloat()
        val h = height.toFloat()

        var left = rect.left
        var top = rect.top
        var right = rect.right
        var bottom = rect.bottom

        when (activeHandle) {
            1 -> { left = x; top = y }
            2 -> { top = y }
            3 -> { right = x; top = y }
            4 -> { right = x }
            5 -> { right = x; bottom = y }
            6 -> { bottom = y }
            7 -> { left = x; bottom = y }
            8 -> { left = x }
        }

        if (mode == 3) {
            left = minOf(anchorX, x)
            right = maxOf(anchorX, x)
            top = minOf(anchorY, y)
            bottom = maxOf(anchorY, y)
        }

        left = left.coerceIn(0f, w)
        right = right.coerceIn(0f, w)
        top = top.coerceIn(0f, h)
        bottom = bottom.coerceIn(0f, h)

        if (right - left < minSize) {
            val mid = (left + right) / 2f
            left = (mid - minSize / 2f).coerceIn(0f, w - minSize)
            right = left + minSize
        }
        if (bottom - top < minSize) {
            val mid = (top + bottom) / 2f
            top = (mid - minSize / 2f).coerceIn(0f, h - minSize)
            bottom = top + minSize
        }

        rect.set(left, top, right, bottom)
    }

    private fun handlePoints(): List<Pair<Float, Float>> {
        val cx = rect.centerX()
        val cy = rect.centerY()
        return listOf(
            rect.left to rect.top,
            cx to rect.top,
            rect.right to rect.top,
            rect.right to cy,
            rect.right to rect.bottom,
            cx to rect.bottom,
            rect.left to rect.bottom,
            rect.left to cy
        )
    }

    private fun findHandle(x: Float, y: Float): Int {
        val points = handlePoints()
        for (i in points.indices) {
            val (px, py) = points[i]
            val dx = x - px
            val dy = y - py
            if (dx * dx + dy * dy <= handleHitRadius * handleHitRadius) return i + 1
        }
        return 0
    }

    private data class ActionCenters(
        val confirmCx: Float,
        val confirmCy: Float,
        val cancelCx: Float,
        val cancelCy: Float,
        val cancelOnLeft: Boolean
    )

    private fun actionButtonCenters(): ActionCenters {
        val w = width.toFloat()
        val h = height.toFloat()

        val anchorX = rect.right
        val anchorY = rect.top

        var baseY = anchorY - actionCornerOffset - actionRadius
        baseY = baseY.coerceIn(actionRadius + actionGap, h - actionRadius - actionGap)

        val preferCancelOnRight = true
        val confirmFirstCx = anchorX - actionCornerOffset - actionRadius
        val cancelRightCx = confirmFirstCx + actionRadius * 2f + actionGap
        val cancelLeftCx = confirmFirstCx - actionRadius * 2f - actionGap

        val canPlaceRight = cancelRightCx <= w - actionRadius - actionGap
        val canPlaceLeft = cancelLeftCx >= actionRadius + actionGap

        val useRight = if (preferCancelOnRight) canPlaceRight || !canPlaceLeft else !canPlaceLeft
        val confirmCx: Float
        val cancelCx: Float
        val cancelOnLeft: Boolean
        if (useRight) {
            confirmCx = confirmFirstCx.coerceIn(actionRadius + actionGap, w - actionRadius - actionGap)
            cancelCx = (confirmCx + actionRadius * 2f + actionGap).coerceIn(actionRadius + actionGap, w - actionRadius - actionGap)
            cancelOnLeft = false
        } else {
            confirmCx = confirmFirstCx.coerceIn(actionRadius + actionGap, w - actionRadius - actionGap)
            cancelCx = (confirmCx - actionRadius * 2f - actionGap).coerceIn(actionRadius + actionGap, w - actionRadius - actionGap)
            cancelOnLeft = true
        }

        return ActionCenters(
            confirmCx = confirmCx,
            confirmCy = baseY,
            cancelCx = cancelCx,
            cancelCy = baseY,
            cancelOnLeft = cancelOnLeft
        )
    }

    private fun findActionButton(x: Float, y: Float): Int {
        val c = actionButtonCenters()
        val dx1 = x - c.confirmCx
        val dy1 = y - c.confirmCy
        if (dx1 * dx1 + dy1 * dy1 <= actionHitRadius * actionHitRadius) return 1

        val dx2 = x - c.cancelCx
        val dy2 = y - c.cancelCy
        if (dx2 * dx2 + dy2 * dy2 <= actionHitRadius * actionHitRadius) return 2

        return 0
    }

    private fun dp(value: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics
        ).toInt()
    }

    private fun sp(value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            value,
            resources.displayMetrics
        )
    }
}
