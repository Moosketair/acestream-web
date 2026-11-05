package fr.hobbitdecheval.acestream

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Native Android design system matching AceStream Web's dark, violet dashboard.
 * All paddings and corner radii are expressed in dp; no WebView is used.
 */
object AceDesign {
    val bg = Color.rgb(9, 14, 27)            // #090e1b
    val sidebar = Color.rgb(11, 17, 33)      // #0b1121
    val panel = Color.rgb(18, 27, 44)        // #121b2c
    val panel2 = Color.rgb(22, 34, 55)       // #162237
    val border = Color.rgb(38, 54, 78)       // #26364e
    val text = Color.rgb(241, 245, 255)      // #f1f5ff
    val muted = Color.rgb(153, 170, 198)     // #99aac6
    val purple = Color.rgb(141, 113, 245)    // #8d71f5
    val purple2 = Color.rgb(102, 73, 203)    // #6649cb
    val green = Color.rgb(63, 224, 162)
    val amber = Color.rgb(255, 189, 92)
    val red = Color.rgb(255, 105, 133)

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + .5f).toInt()

    fun fill(color: Int, radius: Int = 16, borderColor: Int? = null, context: Context): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(context, radius).toFloat()
            if (borderColor != null) setStroke(dp(context, 1), borderColor)
        }

    fun gradient(context: Context, vararg colors: Int, radius: Int = 13): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            cornerRadius = dp(context, radius).toFloat()
        }

    fun label(context: Context, value: String, size: Float = 14f,
              color: Int = text, bold: Boolean = false): TextView =
        TextView(context).apply {
            this.text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = true
        }

    fun card(context: Context, accent: Int? = null, inset: Int = 16): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, inset), dp(context, inset), dp(context, inset), dp(context, inset))
            background = fill(panel, 17, accent ?: border, context)
        }

    fun action(context: Context, label: String, primary: Boolean = true,
               compact: Boolean = false, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextColor(AceDesign.text)
            textSize = if (compact) 12.5f else 14f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            minHeight = dp(context, if (compact) 38 else 47)
            setPadding(dp(context, if (compact) 9 else 13), dp(context, 7),
                       dp(context, if (compact) 9 else 13), dp(context, 7))
            background = if (primary) gradient(context, Color.rgb(134, 104, 237), Color.rgb(104, 72, 209), radius = 10)
                         else fill(Color.rgb(32, 44, 65), 10, Color.rgb(55, 71, 99), context)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    fun input(context: Context, hint: String, initial: String = ""): EditText =
        EditText(context).apply {
            setText(initial)
            this.hint = hint
            setTextColor(AceDesign.text)
            setHintTextColor(Color.rgb(130, 148, 179))
            textSize = 14f
            setSingleLine(true)
            setSelectAllOnFocus(false)
            minHeight = dp(context, 51)
            setPadding(dp(context, 14), dp(context, 9), dp(context, 14), dp(context, 9))
            background = fill(Color.rgb(13, 22, 39), 11, Color.rgb(55, 72, 102), context)
        }

    fun inputSection(context: Context, label: String, editText: EditText): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(AceDesign.label(context, label, 12f, muted, true),
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(context, 7) })
            addView(editText, LinearLayout.LayoutParams(-1, -2))
        }

    fun chip(context: Context, value: String, foreground: Int = green, surface: Int = Color.rgb(22, 55, 47)): TextView =
        label(context, value, 11f, foreground, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(context, 10), dp(context, 5), dp(context, 10), dp(context, 5))
            background = fill(surface, 60, foreground, context)
        }

    fun row(context: Context): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

    fun margin(context: Context, bottom: Int = 13): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(context, bottom) }

    fun caption(context: Context, value: String) = label(context, value, 12.5f, muted)
}
