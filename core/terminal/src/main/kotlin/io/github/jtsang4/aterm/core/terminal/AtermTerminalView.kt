package io.github.jtsang4.aterm.core.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class AtermTerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD7E3F4.toInt()
        textSize = 28f
        typeface = Typeface.MONOSPACE
    }
    private val previewLineHeight: Float
        get() = max(previewPaint.fontSpacing, previewPaint.textSize * 1.2f)
    private val previewBaselineOffset: Float
        get() = -previewPaint.ascent()

    private var previewSnapshot: TerminalSnapshot = TerminalBuffer().snapshot()
    private var liveSession: AuthoritativeTerminalSession? = null
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
        setBackgroundColor(0xFF101418.toInt())
        isFocusable = false
    }

    internal fun attachLiveSession(session: AuthoritativeTerminalSession) {
        if (liveSession === session) {
            return
        }
        liveSession?.removeListener(liveListener)
        liveSession = session
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
}
