package com.rawwave.designsystem

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max

/** Rounded surface used by every consumer card. */
class RwCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    init {
        orientation = VERTICAL
        val c = RwTheme.colors(context)
        setPadding(dp(RwTokens.SPACE_4), dp(RwTokens.SPACE_4), dp(RwTokens.SPACE_4), dp(RwTokens.SPACE_4))
        background = rwGradient(
            start = c.surface,
            end = c.surfaceVariant,
            radiusPx = dp(RwTokens.CARD_RADIUS),
            strokeColor = c.outlineVariant,
            strokePx = dp(1),
        )
    }

    private fun dp(v: Int): Int = RwTheme.dp(context, v)
}

/** Filled primary action. One per screen. */
class RwPrimaryButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextView(context, attrs) {

    init {
        val c = RwTheme.colors(context)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        minHeight = dp(RwTokens.BUTTON_HEIGHT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = RwTheme.boldTypeface()
        setTextColor(Color.WHITE)
        letterSpacing = 0.01f
        setPadding(dp(RwTokens.SPACE_5), dp(RwTokens.SPACE_3), dp(RwTokens.SPACE_5), dp(RwTokens.SPACE_3))
        background = rwRippleGradient(
            context = context,
            start = c.primaryGradientStart,
            end = c.primaryGradientEnd,
            radiusPx = dp(RwTokens.BUTTON_RADIUS),
            strokeColor = null,
            strokePx = 0,
        )
        stateListAnimator = null
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.42f
    }

    private fun dp(v: Int): Int = RwTheme.dp(context, v)
}

/** Filled secondary action: the same green language, one step quieter. */
class RwSecondaryButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextView(context, attrs) {

    init {
        val c = RwTheme.colors(context)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        minHeight = dp(RwTokens.TOUCH_MIN)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = RwTheme.boldTypeface()
        setTextColor(c.onSecondaryGradient)
        letterSpacing = 0.01f
        setPadding(dp(RwTokens.SPACE_4), dp(RwTokens.SPACE_3), dp(RwTokens.SPACE_4), dp(RwTokens.SPACE_3))
        background = rwRippleGradient(
            context = context,
            start = c.secondaryGradientStart,
            end = c.secondaryGradientEnd,
            radiusPx = dp(RwTokens.BUTTON_RADIUS),
            strokeColor = c.outlineVariant,
            strokePx = dp(1),
        )
        stateListAnimator = null
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.42f
    }

    private fun dp(v: Int): Int = RwTheme.dp(context, v)
}

/** Full-width menu action with a chevron; used by every "更多" sheet. */
class RwListItemButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val label = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = RwTheme.boldTypeface()
        setTextColor(Color.WHITE)
    }
    private val chevron = TextView(context).apply {
        text = "›"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        typeface = RwTheme.boldTypeface()
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(10), 0, 0, 0)
    }

    var text: String
        get() = label.text.toString()
        set(value) { label.text = value }

    init {
        val c = RwTheme.colors(context)
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        minimumHeight = dp(54)
        setPadding(dp(RwTokens.SPACE_4), dp(RwTokens.SPACE_2), dp(RwTokens.SPACE_3), dp(RwTokens.SPACE_2))
        background = rwRippleGradient(
            context = context,
            start = c.secondaryGradientStart,
            end = c.secondaryGradientEnd,
            radiusPx = dp(RwTokens.BUTTON_RADIUS),
            strokeColor = c.outlineVariant,
            strokePx = dp(1),
        )
        addView(label, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(chevron, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun dp(v: Int): Int = RwTheme.dp(context, v)
}

/** Bold check row with the product green tint. */
class RwCheckBox @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : CheckBox(context, attrs) {

    init {
        val c = RwTheme.colors(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = RwTheme.boldTypeface()
        setTextColor(c.onSurface)
        setLineSpacing(dp(2).toFloat(), 1f)
        minHeight = dp(44)
        setPadding(0, dp(RwTokens.SPACE_1), 0, dp(RwTokens.SPACE_1))
        buttonTintList = ColorStateList(
            arrayOf(
                intArrayOf(-android.R.attr.state_checked),
                intArrayOf(android.R.attr.state_checked),
            ),
            intArrayOf(c.outline, c.primary),
        )
    }

    private fun dp(v: Int): Int = RwTheme.dp(context, v)
}

class RwRadioButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : RadioButton(context, attrs) {

    init {
        val c = RwTheme.colors(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = RwTheme.boldTypeface()
        setTextColor(c.onSurface)
        minHeight = dp(44)
        buttonTintList = ColorStateList(
            arrayOf(
                intArrayOf(-android.R.attr.state_checked),
                intArrayOf(android.R.attr.state_checked),
            ),
            intArrayOf(c.outline, c.primary),
        )
    }

    private fun dp(v: Int): Int = RwTheme.dp(context, v)
}

/** Rounded text field; the watch numeric keypad still owns entry. */
class RwInputField @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : EditText(context, attrs) {

    init {
        val c = RwTheme.colors(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = RwTheme.boldTypeface()
        setTextColor(c.onSurface)
        setHintTextColor(c.onSurfaceVariant)
        minHeight = dp(RwTokens.TOUCH_MIN)
        setPadding(dp(RwTokens.SPACE_3), dp(RwTokens.SPACE_2), dp(RwTokens.SPACE_3), dp(RwTokens.SPACE_2))
        background = rwRounded(
            color = c.surfaceVariant,
            radiusPx = dp(14),
            strokeColor = c.outlineVariant,
            strokePx = dp(1),
        )
    }

    private fun dp(v: Int): Int = RwTheme.dp(context, v)
}

/** Compact gradient key used by the watch number pad and presets. */
class RwKeypadButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextView(context, attrs) {

    init {
        val c = RwTheme.colors(context)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        minHeight = dp(44)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = RwTheme.boldTypeface()
        setTextColor(Color.WHITE)
        setPadding(dp(2), dp(2), dp(2), dp(2))
        background = rwRippleGradient(
            context = context,
            start = c.secondaryGradientStart,
            end = c.secondaryGradientEnd,
            radiusPx = dp(14),
            strokeColor = c.outlineVariant,
            strokePx = dp(1),
        )
    }

    private fun dp(v: Int): Int = RwTheme.dp(context, v)
}

/** Large green-gradient text for the watch timer and page titles. */
class RwGradientTitle @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextView(context, attrs) {

    private val gradientStart = RwTheme.colors(context).titleGradientStart
    private val gradientEnd = RwTheme.colors(context).titleGradientEnd

    init {
        typeface = RwTheme.displayTypeface()
        setTextColor(Color.WHITE)
        includeFontPadding = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyShader(w)
    }

    private fun applyShader(w: Int) {
        if (w <= 0) return
        paint.shader = LinearGradient(
            0f,
            0f,
            w.toFloat(),
            0f,
            gradientStart,
            gradientEnd,
            Shader.TileMode.CLAMP,
        )
        invalidate()
    }
}

/** Compact status chip. Text and color together, never color alone. */
class RwStatusPill @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextView(context, attrs) {

    init {
        val c = RwTheme.colors(context)
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = RwTheme.boldTypeface()
        setTextColor(c.onPrimaryContainer)
        setPadding(dp(RwTokens.SPACE_3), dp(RwTokens.SPACE_1), dp(RwTokens.SPACE_3), dp(RwTokens.SPACE_1))
        background = rwRounded(c.primaryContainer, dp(20), null, 0)
    }

    fun setTone(backgroundColor: Int, textColor: Int) {
        setTextColor(textColor)
        background = rwRounded(backgroundColor, dp(20), null, 0)
    }

    private fun dp(v: Int): Int = RwTheme.dp(context, v)
}

class RwSectionHeader @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextView(context, attrs) {

    init {
        val c = RwTheme.colors(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        typeface = RwTheme.displayTypeface()
        setTextColor(c.onSurface)
        setPadding(0, dp(RwTokens.SPACE_5), 0, dp(RwTokens.SPACE_2))
    }

    private fun dp(v: Int): Int = RwTheme.dp(context, v)
}

class RwBodyText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextView(context, attrs) {

    init {
        val c = RwTheme.colors(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = RwTheme.boldTypeface()
        setTextColor(c.onSurfaceVariant)
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun dp(v: Int): Int = RwTheme.dp(context, v)
}

class RwMetricRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val label = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = RwTheme.boldTypeface()
        setTextColor(RwTheme.colors(context).onSurfaceVariant)
    }
    private val value = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = RwTheme.boldTypeface()
        setTextColor(RwTheme.colors(context).onSurface)
        gravity = Gravity.END
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(RwTokens.SPACE_1), 0, dp(RwTokens.SPACE_1))
        addView(label, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(value, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun set(labelText: String, valueText: String) {
        label.text = labelText
        value.text = valueText
    }

    private fun dp(v: Int): Int = RwTheme.dp(context, v)
}

class RwDivider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    init {
        setBackgroundColor(RwTheme.colors(context).outlineVariant)
    }
}

/** A scrollable consumer screen with consistent background and system-bar padding. */
class RwScreen @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {

    val content = LinearLayout(context)

    init {
        isFillViewport = true
        clipToPadding = false
        setBackgroundColor(RwTheme.colors(context).background)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(
            dp(RwTokens.SCREEN_PADDING),
            dp(RwTokens.SPACE_4),
            dp(RwTokens.SCREEN_PADDING),
            dp(RwTokens.SPACE_6),
        )
        addView(
            content,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            content.setPadding(
                dp(RwTokens.SCREEN_PADDING),
                dp(RwTokens.SPACE_4) + bars.top,
                dp(RwTokens.SCREEN_PADDING),
                dp(RwTokens.SPACE_6) + bars.bottom,
            )
            insets
        }
    }

    private fun dp(v: Int): Int = RwTheme.dp(context, v)
}

/** Phone top bar: a real back button on the left and a large title. */
class RwTopBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val back = RwSecondaryButton(context).apply {
        text = "‹ 返回"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        minWidth = dp(78)
        setPadding(dp(12), dp(4), dp(12), dp(4))
    }
    private val title = RwGradientTitle(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        setPadding(dp(12), 0, 0, 0)
        maxLines = 1
    }
    private var onBack: (() -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(4), 0, dp(10))
        back.setOnClickListener { onBack?.invoke() }
        addView(
            back,
            LayoutParams(LayoutParams.WRAP_CONTENT, dp(44)),
        )
        addView(title, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    fun setTitle(value: String) {
        title.text = value
    }

    fun setOnBackClickListener(listener: () -> Unit) {
        onBack = listener
    }

    fun setBackEnabled(enabled: Boolean) {
        back.isEnabled = enabled
    }

    private fun dp(v: Int): Int = RwTheme.dp(context, v)
}

/** A simple three-item bottom navigation for the phone home. */
class RwBottomNav @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val items = ArrayList<TextView>()
    var onSelected: ((Int) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(RwTheme.colors(context).surface)
        setPadding(0, dp(RwTokens.SPACE_2), 0, dp(RwTokens.SPACE_2))
    }

    fun setItems(labels: List<String>, selected: Int) {
        removeAllViews()
        items.clear()
        for ((index, label) in labels.withIndex()) {
            val item = TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = RwTheme.boldTypeface()
                isClickable = true
                isFocusable = true
                minHeight = dp(RwTokens.TOUCH_MIN)
                setOnClickListener {
                    select(index)
                    onSelected?.invoke(index)
                }
            }
            items += item
            addView(item, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        select(selected)
    }

    private fun select(index: Int) {
        val c = RwTheme.colors(context)
        for ((i, item) in items.withIndex()) {
            item.setTextColor(if (i == index) c.primary else c.onSurfaceVariant)
        }
    }

    private fun dp(v: Int): Int = RwTheme.dp(context, v)
}

/** Round status badge used by the watch home. */
class RwWatchStatus @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val title = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        typeface = RwTheme.boldTypeface()
        setTextColor(Color.WHITE)
    }
    private val phase = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        typeface = RwTheme.boldTypeface()
        setTextColor(Color.WHITE)
    }

    init {
        val c = RwTheme.colors(context)
        background = rwGradient(
            start = c.primaryGradientStart,
            end = c.primaryGradientEnd,
            radiusPx = dp(200),
            strokeColor = c.primary,
            strokePx = dp(2),
        ).apply { shape = GradientDrawable.OVAL }
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(title)
                addView(phase)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER),
        )
    }

    fun setStatus(titleText: String, phaseText: String) {
        title.text = titleText
        phase.text = phaseText
        phase.visibility = if (phaseText.isBlank()) GONE else VISIBLE
    }

    private fun dp(v: Int): Int = RwTheme.dp(context, v)
}

internal fun rwDp(context: Context, value: Int): Int = RwTheme.dp(context, value)

internal fun rwRounded(
    color: Int,
    radiusPx: Int,
    strokeColor: Int?,
    strokePx: Int,
): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = radiusPx.toFloat()
    setColor(color)
    if (strokeColor != null && strokePx > 0) setStroke(strokePx, strokeColor)
}

internal fun rwGradient(
    start: Int,
    end: Int,
    radiusPx: Int,
    strokeColor: Int?,
    strokePx: Int,
): GradientDrawable = GradientDrawable(
    GradientDrawable.Orientation.TL_BR,
    intArrayOf(start, end),
).apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = radiusPx.toFloat()
    if (strokeColor != null && strokePx > 0) setStroke(strokePx, strokeColor)
}

internal fun rwRippleGradient(
    context: Context,
    start: Int,
    end: Int,
    radiusPx: Int,
    strokeColor: Int?,
    strokePx: Int,
): RippleDrawable {
    val content = RwPillDrawable(start, end, radiusPx.toFloat(), strokeColor, strokePx.toFloat())
    val mask = rwRounded(Color.WHITE, radiusPx, null, 0)
    val color = ColorStateList.valueOf(RwTheme.colors(context).ripple)
    return RippleDrawable(color, content, mask)
}

/**
 * A center-lit pill. The middle is slightly brighter than the edges, so it reads as a physical
 * button instead of a flat diagonal gradient. White text stays readable on every green token.
 */
internal class RwPillDrawable(
    private val centerColor: Int,
    private val edgeColor: Int,
    private val radiusPx: Float,
    private val strokeColor: Int?,
    private val strokePx: Float,
) : Drawable() {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        rect.set(b)
        val radius = max(b.width(), b.height()) * 0.62f
        fill.shader = RadialGradient(
            b.exactCenterX(),
            b.top + b.height() * 0.36f,
            radius,
            intArrayOf(centerColor, edgeColor),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(rect, radiusPx, radiusPx, fill)
        fill.shader = null
        if (strokeColor != null && strokePx > 0f) {
            stroke.color = strokeColor
            stroke.strokeWidth = strokePx
        } else {
            // A faint top edge keeps the button from disappearing into a dark background.
            stroke.color = Color.argb(42, 255, 255, 255)
            stroke.strokeWidth = 1f
        }
        canvas.drawRoundRect(rect, radiusPx, radiusPx, stroke)
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
        stroke.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        fill.colorFilter = colorFilter
        stroke.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

internal fun rwRippleSolid(
    context: Context,
    color: Int,
    radiusPx: Int,
    strokeColor: Int?,
    strokePx: Int,
): RippleDrawable {
    val content = rwRounded(color, radiusPx, strokeColor, strokePx)
    val mask = rwRounded(Color.WHITE, radiusPx, null, 0)
    val ripple = ColorStateList.valueOf(RwTheme.colors(context).ripple)
    return RippleDrawable(ripple, content, mask)
}
