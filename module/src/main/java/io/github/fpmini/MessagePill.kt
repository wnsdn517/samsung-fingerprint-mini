package io.github.fpmini

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.TextView

/** "message" layout: the prompt's title and description in a small pill near the top of the screen. */
object MessagePill {

    // the feedback text ("try again") sits outside the card and stays visible on its own
    private val SOURCES = listOf("title", "subtitle", "description")

    fun attach(root: ViewGroup, pkg: String) {
        val res = root.resources
        val d = res.displayMetrics.density
        val pill = TextView(root.context).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setPadding((18 * d).toInt(), (10 * d).toInt(), (18 * d).toInt(), (10 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 24 * d
                setColor(0xE0202124.toInt())
            }
            isClickable = false
            isFocusable = false
        }
        // own frame, so the gravity works whatever the root's layout is
        val frame = FrameLayout(root.context)
        frame.addView(
            pill,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = (64 * d).toInt()
                leftMargin = (24 * d).toInt()
                rightMargin = (24 * d).toInt()
            },
        )
        root.addView(frame, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val ids = SOURCES.map { res.getIdentifier(it, "id", pkg) }.filter { it != 0 }
        // texts are filled in (and the feedback changes) after the window is added
        root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!pill.isAttachedToWindow) return true
                val text = ids.mapNotNull { (root.findViewById<android.view.View>(it) as? TextView)?.text?.toString() }
                    .map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n")
                if (text != pill.text.toString()) pill.text = text
                pill.visibility = if (text.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
                return true
            }
        })
    }
}
