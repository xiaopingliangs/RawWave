package com.rawwave.designsystem

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Themed sheets and confirmations.
 *
 * The platform AlertDialog is deliberately not used for consumer actions: its list rows look
 * like plain text on both the phone and the watch. Every menu here is a stack of real buttons.
 */
object RwDialogs {

    data class Action(
        val label: String,
        val primary: Boolean = false,
        val onClick: () -> Unit,
    )

    fun actions(
        activity: Activity,
        title: String,
        subtitle: String? = null,
        actions: List<Action>,
        cancelLabel: String? = "关闭",
    ): Dialog {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val sheet = newSheet(activity)
        addHeader(activity, sheet, title, subtitle)
        actions.forEach { action ->
            val view: View = if (action.primary) {
                RwPrimaryButton(activity).apply {
                    text = action.label
                    setOnClickListener {
                        dialog.dismiss()
                        action.onClick()
                    }
                }
            } else {
                RwListItemButton(activity).apply {
                    text = action.label
                    setOnClickListener {
                        dialog.dismiss()
                        action.onClick()
                    }
                }
            }
            sheet.addView(
                view,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = rwDp(activity, 8) },
            )
        }
        addCancel(activity, sheet, dialog, cancelLabel)
        show(activity, dialog, sheet)
        return dialog
    }

    fun message(
        activity: Activity,
        title: String,
        message: String,
        confirmLabel: String = "知道了",
        onConfirm: (() -> Unit)? = null,
    ): Dialog {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val sheet = newSheet(activity)
        addHeader(activity, sheet, title, null)
        sheet.addView(
            bodyText(activity, message),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = rwDp(activity, 12) },
        )
        sheet.addView(
            RwPrimaryButton(activity).apply {
                text = confirmLabel
                setOnClickListener {
                    dialog.dismiss()
                    onConfirm?.invoke()
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = rwDp(activity, 18) },
        )
        show(activity, dialog, sheet)
        return dialog
    }

    fun confirm(
        activity: Activity,
        title: String,
        message: String,
        confirmLabel: String,
        cancelLabel: String = "取消",
        onConfirm: () -> Unit,
    ): Dialog {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val sheet = newSheet(activity)
        addHeader(activity, sheet, title, null)
        sheet.addView(
            bodyText(activity, message),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = rwDp(activity, 12) },
        )
        if (activity.resources.configuration.isScreenRound) {
            // On a 480x480 watch two stacked 48 dp buttons plus header and body exceed the
            // 0.88 screen-height budget, so the safe "cancel" choice was pushed off screen and
            // only the destructive button stayed visible. One row keeps both reachable at full
            // touch-target size.
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            fun compact(view: TextView, action: () -> Unit) {
                view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                view.setPadding(
                    rwDp(activity, 8),
                    rwDp(activity, 6),
                    rwDp(activity, 8),
                    rwDp(activity, 6),
                )
                view.setOnClickListener { action() }
            }
            val cancel = RwSecondaryButton(activity).apply {
                text = cancelLabel
                compact(this) { dialog.dismiss() }
            }
            val confirm = RwPrimaryButton(activity).apply {
                text = confirmLabel
                compact(this) {
                    dialog.dismiss()
                    onConfirm()
                }
            }
            row.addView(cancel, LinearLayout.LayoutParams(0, rwDp(activity, 48), 1f))
            row.addView(
                confirm,
                LinearLayout.LayoutParams(0, rwDp(activity, 48), 1f).apply {
                    leftMargin = rwDp(activity, 6)
                },
            )
            sheet.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = rwDp(activity, 12) },
            )
        } else {
            sheet.addView(
                RwPrimaryButton(activity).apply {
                    text = confirmLabel
                    setOnClickListener {
                        dialog.dismiss()
                        onConfirm()
                    }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = rwDp(activity, 18) },
            )
            sheet.addView(
                RwSecondaryButton(activity).apply {
                    text = cancelLabel
                    setOnClickListener { dialog.dismiss() }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = rwDp(activity, 8) },
            )
        }
        show(activity, dialog, sheet)
        return dialog
    }

    fun checklist(
        activity: Activity,
        title: String,
        subtitle: String? = null,
        labels: Array<String>,
        checked: BooleanArray,
        confirmLabel: String,
        cancelLabel: String = "取消",
        onConfirm: (BooleanArray) -> Unit,
    ): Dialog {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val sheet = newSheet(activity)
        addHeader(activity, sheet, title, subtitle)
        val state = BooleanArray(labels.size) { checked.getOrElse(it) { false } }
        labels.forEachIndexed { index, label ->
            sheet.addView(
                RwCheckBox(activity).apply {
                    text = label
                    isChecked = state[index]
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                    setOnCheckedChangeListener { _, value -> state[index] = value }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = rwDp(activity, if (index == 0) 8 else 2) },
            )
        }
        sheet.addView(
            RwPrimaryButton(activity).apply {
                text = confirmLabel
                setOnClickListener {
                    dialog.dismiss()
                    onConfirm(state.copyOf())
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = rwDp(activity, 16) },
        )
        sheet.addView(
            RwSecondaryButton(activity).apply {
                text = cancelLabel
                setOnClickListener { dialog.dismiss() }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = rwDp(activity, 8) },
        )
        show(activity, dialog, sheet)
        return dialog
    }

    /**
     * Hours + minutes duration editor with real numeric fields.
     *
     * NumberPicker looked like blank space in the light sheet, so users could not see or type a
     * value. Two small text fields make the current value visible and editable; the value is
     * clamped on save (hours 0-23, minutes 0-59, 0:00 = never).
     */
    fun duration(
        activity: Activity,
        title: String,
        subtitle: String?,
        hours: Int,
        minutes: Int,
        confirmLabel: String = "保存",
        cancelLabel: String = "取消",
        onConfirm: (Int, Int) -> Unit,
    ): Dialog {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val sheet = newSheet(activity)
        addHeader(activity, sheet, title, subtitle)

        val colors = RwTheme.colors(activity)
        fun numberField(initial: Int, max: Int): EditText = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf<InputFilter>(
                InputFilter.LengthFilter(2),
                InputFilter { source, start, end, dest, dstart, dend ->
                    val candidate = dest.toString().substring(0, dstart) +
                        source.toString().substring(start, end) +
                        dest.toString().substring(dend)
                    if (candidate.isEmpty()) null
                    else if ((candidate.toIntOrNull() ?: (max + 1)) > max) "" else null
                },
            )
            setText(String.format(java.util.Locale.ROOT, "%02d", initial.coerceIn(0, max)))
            setSelectAllOnFocus(true)
            setSingleLine(true)
            gravity = Gravity.CENTER
            setPadding(0, rwDp(activity, 6), 0, rwDp(activity, 6))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 28f)
            setTextColor(colors.onSurface)
            setHintTextColor(colors.onSurfaceVariant)
            background = rwRounded(
                colors.surfaceVariant,
                rwDp(activity, 16),
                colors.outlineVariant,
                rwDp(activity, 1),
            )
        }
        val hourField = numberField(hours.coerceIn(0, 23), 23)
        val minuteField = numberField(minutes.coerceIn(0, 59), 59)
        fun unit(text: String) = RwBodyText(activity).apply {
            this.text = text
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(rwDp(activity, 8), 0, rwDp(activity, 12), 0)
        }
        val fields = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fields.addView(hourField, LinearLayout.LayoutParams(0, rwDp(activity, 64), 1f))
        fields.addView(unit("小时"))
        fields.addView(minuteField, LinearLayout.LayoutParams(0, rwDp(activity, 64), 1f))
        fields.addView(unit("分钟"))
        sheet.addView(
            fields,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = rwDp(activity, 12) },
        )
        val zeroNote = RwBodyText(activity).apply {
            text = "可直接输入数字；0 小时 0 分钟表示不自动退出。"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        sheet.addView(
            zeroNote,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = rwDp(activity, 6) },
        )
        sheet.addView(
            RwPrimaryButton(activity).apply {
                text = confirmLabel
                setOnClickListener {
                    val chosenHours = hourField.text.toString().toIntOrNull()?.coerceIn(0, 23) ?: 0
                    val chosenMinutes = minuteField.text.toString().toIntOrNull()?.coerceIn(0, 59) ?: 0
                    dialog.dismiss()
                    onConfirm(chosenHours, chosenMinutes)
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = rwDp(activity, 14) },
        )
        sheet.addView(
            RwSecondaryButton(activity).apply {
                text = cancelLabel
                setOnClickListener { dialog.dismiss() }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = rwDp(activity, 8) },
        )
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        show(activity, dialog, sheet)
        return dialog
    }

    private fun newSheet(activity: Activity): LinearLayout {
        val colors = RwTheme.colors(activity)
        val round = activity.resources.configuration.isScreenRound
        val horizontal = if (round) {
            (activity.resources.displayMetrics.widthPixels * RwTokens.WATCH_SAFE_FRACTION).toInt()
        } else {
            rwDp(activity, 18)
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(horizontal, rwDp(activity, 18), horizontal, rwDp(activity, 18))
            background = rwRounded(
                colors.surface,
                rwDp(activity, 28),
                colors.outlineVariant,
                rwDp(activity, 1),
            )
        }
    }

    private fun addHeader(
        activity: Activity,
        sheet: LinearLayout,
        title: String,
        subtitle: String?,
    ) {
        val round = activity.resources.configuration.isScreenRound
        if (!round) {
            sheet.addView(
                View(activity).apply {
                    setBackgroundColor(RwTheme.colors(activity).outlineVariant)
                },
                LinearLayout.LayoutParams(rwDp(activity, 42), rwDp(activity, 4)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = rwDp(activity, 16)
                },
            )
        }
        val titleView = RwGradientTitle(activity).apply {
            text = title
            setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_SP,
                if (round) 20f else 25f,
            )
            gravity = Gravity.START
        }
        sheet.addView(
            titleView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        if (!subtitle.isNullOrBlank()) {
            sheet.addView(
                RwBodyText(activity).apply {
                    text = subtitle
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = rwDp(activity, 6) },
            )
        }
    }

    private fun addCancel(
        activity: Activity,
        sheet: LinearLayout,
        dialog: Dialog,
        cancelLabel: String?,
    ) {
        if (cancelLabel.isNullOrBlank()) return
        sheet.addView(
            RwSecondaryButton(activity).apply {
                text = cancelLabel
                setOnClickListener { dialog.dismiss() }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = rwDp(activity, 12) },
        )
    }

    private fun bodyText(activity: Activity, message: String): TextView =
        RwBodyText(activity).apply {
            text = message
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(rwDp(activity, 3).toFloat(), 1f)
        }

    private fun show(activity: Activity, dialog: Dialog, sheet: LinearLayout) {
        val round = activity.resources.configuration.isScreenRound
        val scroll = ScrollView(activity).apply {
            isFillViewport = false
            clipToPadding = false
            addView(
                sheet,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        dialog.setContentView(scroll)
        dialog.setOnShowListener {
            val window = dialog.window ?: return@setOnShowListener
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setDimAmount(0.66f)
            window.setGravity(if (round) Gravity.CENTER else Gravity.BOTTOM)
            val metrics = activity.resources.displayMetrics
            val width = if (round) metrics.widthPixels else metrics.widthPixels - rwDp(activity, 20)
            val maxHeight = (metrics.heightPixels * if (round) 0.88f else 0.84f).toInt()
            // Measure the sheet before the window is laid out. A long action list on a watch
            // must get a fixed height so its ScrollView actually scrolls instead of clipping.
            sheet.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val height = if (sheet.measuredHeight > maxHeight) maxHeight
            else WindowManager.LayoutParams.WRAP_CONTENT
            window.setLayout(width, height)
        }
        dialog.show()
    }
}
