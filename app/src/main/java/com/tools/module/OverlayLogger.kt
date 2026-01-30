package com.tools.module

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import java.util.ArrayDeque

object OverlayLogger {
    private const val MAX_LINES = 3
    private val lines = ArrayDeque<String>(MAX_LINES)
    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var windowManager: WindowManager? = null
    private var textView: TextView? = null
    private var viewAdded = false
    private var canOverlay = false

    fun init(ctx: Context) {
        if (appContext != null) return
        val baseCtx = ctx.applicationContext ?: ctx
        appContext = baseCtx
        windowManager = baseCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        canOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(baseCtx)
        } else {
            true
        }
    }

    fun append(message: String) {
        val ctx = appContext ?: return
        handler.post {
            if (canOverlay) {
                ensureView(ctx)
                updateLines(message)
            } else {
                showToast(ctx, message)
            }
        }
    }

    private fun ensureView(ctx: Context) {
        if (viewAdded) return
        val tv = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xAA000000.toInt())
            setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6))
            textSize = 12f
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(ctx, 16)
        }
        try {
            windowManager?.addView(tv, params)
            textView = tv
            viewAdded = true
        } catch (_: Throwable) {
            canOverlay = false
        }
    }

    private fun updateLines(message: String) {
        while (lines.size >= MAX_LINES) {
            lines.removeFirst()
        }
        lines.addLast(message)
        textView?.text = lines.joinToString(separator = "\n")
    }

    private fun showToast(ctx: Context, message: String) {
        Toast.makeText(ctx, message, Toast.LENGTH_SHORT).apply {
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, dp(ctx, 24))
            show()
        }
    }

    private fun dp(ctx: Context, value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            ctx.resources.displayMetrics
        ).toInt()
    }
}
