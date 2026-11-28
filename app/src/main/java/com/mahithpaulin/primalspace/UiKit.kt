package com.mahithpaulin.primalspace

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat

object Ui {
    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    fun paintSystemBars(activity: AppCompatActivity, color: Int) {
        activity.window.statusBarColor = color
        activity.window.navigationBarColor = Color.rgb(8, 12, 10)
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
    }

    fun initials(value: String, fallback: String = "?"): String {
        val words = value.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return fallback.take(2).uppercase()
        return words.take(2).joinToString("") { it.take(1) }.uppercase().take(2)
    }

    fun avatarColor(seed: String): Int {
        val palette = intArrayOf(
            Color.rgb(38, 74, 58),
            Color.rgb(48, 58, 91),
            Color.rgb(82, 54, 72),
            Color.rgb(70, 62, 38),
            Color.rgb(42, 70, 80),
            Color.rgb(67, 48, 92),
        )
        return palette[Math.floorMod(seed.hashCode(), palette.size)]
    }

    fun rounded(
        context: Context,
        color: Int,
        radiusDp: Float,
        strokeColor: Int? = null,
        strokeDp: Float = 1f,
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            setCornerRadius(dp(context, radiusDp.toInt()).toFloat())
            if (strokeColor != null) setStroke(dp(context, strokeDp.toInt()).coerceAtLeast(1), strokeColor)
        }
    }

    fun oval(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            setShape(GradientDrawable.OVAL)
            setColor(color)
        }
    }

    fun bubble(context: Context, mine: Boolean, credit: Boolean): Drawable {
        if (credit) {
            return rounded(context, Color.rgb(58, 46, 20), 18f, Color.rgb(138, 106, 42))
        }
        val radius = dp(context, 18).toFloat()
        val tight = dp(context, 4).toFloat()
        val radii = if (mine) {
            floatArrayOf(radius, radius, tight, tight, radius, radius, radius, radius)
        } else {
            floatArrayOf(tight, tight, radius, radius, radius, radius, radius, radius)
        }
        return GradientDrawable().apply {
            setColor(if (mine) Color.rgb(0, 92, 75) else Color.rgb(32, 44, 51))
            setCornerRadii(radii)
        }
    }

    fun ripple(context: Context, normalColor: Int, radiusDp: Float): Drawable {
        return RippleDrawable(
            ColorStateList.valueOf(Color.argb(38, 255, 255, 255)),
            rounded(context, normalColor, radiusDp),
            null,
        )
    }
}
