package io.github.jtsang4.aterm.core.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class AtermTerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TerminalColorPalette.Default.foregroundArgb
        typeface = Typeface.MONOSPACE
    }
    private val previewLineHeight: Float
        get() = max(previewPaint.fontSpacing, previewPaint.textSize * 1.2f)
    private val previewBaselineOffset: Float
        get() = -previewPaint.ascent()

    private var previewSnapshot: TerminalSnapshot = TerminalBuffer().snapshot()
    private var liveSession: AuthoritativeTerminalSession? = null
    private var terminalColorPalette: TerminalColorPalette = TerminalColorPalette.Default
    private val liveListener = object : AuthoritativeTerminalSession.Listener {
        override fun onTerminalSnapshotChanged(snapshot: TerminalSnapshot) {
            post {
                previewSnapshot = snapshot
                invalidate()
            }
        }

        override fun onTerminalTextChanged(text: String) {
            post {
                contentDescription = text.ifBlank { "No terminal transcript yet." }
                invalidate()
            }
        }
    }

    init {
        setBackgroundColor(TerminalColorPalette.Default.backgroundArgb)
        isFocusable = false
        setTerminalFontScale(1f)
    }

    internal fun attachLiveSession(session: AuthoritativeTerminalSession) {
        if (liveSession === session) {
            session.updateColorPalette(terminalColorPalette)
            return
        }
        liveSession?.removeListener(liveListener)
        liveSession = session
        session.updateColorPalette(terminalColorPalette)
        session.addListener(liveListener)
        invalidate()
    }

    internal fun clearLiveSession() {
        liveSession?.removeListener(liveListener)
        liveSession = null
        invalidate()
    }

    internal fun updateLiveAccessibilityText(text: String) {
        contentDescription = text.ifBlank { "No terminal transcript yet." }
        invalidate()
    }

    fun setTerminalFontScale(scale: Float) {
        val normalized = scale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
        previewPaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            DEFAULT_TEXT_SIZE_SP * normalized,
            resources.displayMetrics,
        )
        invalidate()
    }

    fun setTerminalColorPalette(palette: TerminalColorPalette) {
        if (terminalColorPalette == palette) {
            return
        }
        terminalColorPalette = palette
        previewPaint.color = palette.foregroundArgb
        setBackgroundColor(palette.backgroundArgb)
        liveSession?.updateColorPalette(palette)
        invalidate()
    }

    fun updatePreviewSnapshot(snapshot: TerminalSnapshot) {
        previewSnapshot = snapshot
        if (liveSession == null) {
            contentDescription = snapshot.completeText.ifBlank { "No terminal transcript yet." }
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val session = liveSession
        if (session != null) {
            session.renderInto(canvas)
            return
        }

        var baseline = previewBaselineOffset + previewPaint.textSize * 0.35f
        previewSnapshot.visibleLines.forEach { line ->
            canvas.drawText(line.trimEnd().ifEmpty { " " }, 0f, baseline, previewPaint)
            baseline += previewLineHeight
            if (baseline > height + previewLineHeight) {
                return
            }
        }
    }

    private companion object {
        private const val DEFAULT_TEXT_SIZE_SP = 14f
        private const val MIN_FONT_SCALE = 0.75f
        private const val MAX_FONT_SCALE = 2f
    }
}
