package hk.uwu.reareye.ui.components.webview

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebView
import kotlin.math.abs

class ScrollWebView : WebView {
    private val touchSlop: Int
    private var gestureStartY = 0f
    private var disallowInterceptRequested = false

    constructor(context: Context) : super(context) {
        touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    ) {
        touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureStartY = event.y
                requestParentDisallowIntercept(true)
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaY = event.y - gestureStartY
                if (abs(deltaY) >= touchSlop) {
                    val fingerMovingDown = deltaY > 0f
                    val canConsumeMove = if (fingerMovingDown)
                        canScrollVertically(-1)
                    else
                        canScrollVertically(1)
                    requestParentDisallowIntercept(canConsumeMove)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                gestureStartY = 0f
                requestParentDisallowIntercept(false)
            }
        }
        return super.onTouchEvent(event)
    }

    private fun requestParentDisallowIntercept(disallowIntercept: Boolean) {
        if (disallowInterceptRequested == disallowIntercept) {
            return
        }
        disallowInterceptRequested = disallowIntercept
        parent?.requestDisallowInterceptTouchEvent(disallowIntercept)
    }
}
