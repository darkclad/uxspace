package com.uxspace.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A relative-motion touchpad. One finger dragging reports cursor deltas as a fraction of the
 * pad's width; a touch that neither moves far nor lingers is a tap. Two fingers dragging
 * reports a vertical scroll delta as a fraction of the pad's height.
 *
 * The user looks at the glasses, not at this — it is a blind trackpad, like a laptop's.
 */
class TrackpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Called on a one-finger drag with the movement since the last event, fraction of width. */
    var onMove: ((dxFraction: Float, dyFraction: Float) -> Unit)? = null

    /** Called when a one-finger touch ends without having become a drag. */
    var onTap: (() -> Unit)? = null

    /**
     * Called on a two-finger drag with the centroid movement since the last event —
     * horizontal as a fraction of pad width, vertical as a fraction of pad height. The
     * controller routes this to either an in-window scroll (most cases) or a workspace
     * pan (PINNED + zoomed). The auto-flick tick also calls this with dx=0 so the same
     * routing applies to continued momentum.
     */
    var onTwoFingerDrag: ((dxFraction: Float, dyFraction: Float) -> Unit)? = null

    /**
     * Called on a two-finger pinch with the ratio of current to previous finger spread —
     * 1.0 means no zoom, > 1 spreads apart (zoom in), < 1 pinches together (zoom out).
     */
    var onZoom: ((scaleFactor: Float) -> Unit)? = null

    /** Called when a press-and-hold turns the touch into a drag — used to grab a window. */
    var onDragStart: (() -> Unit)? = null

    /** Called when a drag ends cleanly (the user lifts the finger). */
    var onDragEnd: (() -> Unit)? = null

    /** Called when an in-flight drag is interrupted by a second finger landing. */
    var onDragCancel: (() -> Unit)? = null

    /**
     * Called when a one-finger touch stays down without moving past the tap slop for
     * `WorkspaceController.longPressMs` — distinct from the tap-and-drag gesture (which needs a prior
     * tap). Used by callers (e.g. the drawer) that want a "press-and-hold to pick up"
     * affordance without giving up the normal tap-launches semantics.
     */
    var onLongPress: (() -> Unit)? = null

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var movedFar = false

    /**
     * Cursor-movement buffered during the touch's "warmup". A two-finger gesture often
     * lands its second finger a few ms after the first; if we treat that gap as a
     * one-finger drag the cursor jumps before the gesture is recognised, which then sends
     * the auto-scroll to wherever the cursor jumped to. We buffer the first ~30 ms / 8 px
     * of one-finger movement and discard it if `POINTER_DOWN` arrives during the window.
     */
    private var warmupActive = false
    private var warmupDx = 0f
    private var warmupDy = 0f

    /** Total cursor delta emitted by *this* touch — undone on `POINTER_DOWN`. */
    private var cursorEmittedDx = 0f
    private var cursorEmittedDy = 0f

    /** Latched true once a second finger lands; cleared when all fingers lift. */
    private var scrolling = false
    private var lastScrollX = 0f
    private var lastScrollY = 0f
    private var lastSpread = 0f

    /**
     * Latched true once a multi-finger gesture has had a `POINTER_UP` — the gesture is
     * done, but the last finger may still be lifting (and sliding with leftover flick
     * momentum). Suppresses all `ACTION_MOVE` handling until `ACTION_UP`, so that
     * residual single-finger motion can't be misread as a new cursor drag or scroll.
     */
    private var gestureConsumed = false

    /**
     * Two-finger flick → continuous scroll. A second finger lands, the user moves to
     * indicate direction and speed, lifts, and the scroll runs at that velocity until the
     * next two-finger touch stops it. The first POINTER_DOWN of each two-finger gesture
     * either cancels an active auto-scroll (acting as a stop) or starts a new gesture; the
     * matching UP either kicks the captured velocity into auto-scroll or — if this was the
     * stop gesture — does nothing.
     */
    private var autoScrollActive = false
    private var autoScrollFractionPerMs = 0f
    private var twoFingerDownTime = 0L
    private var twoFingerDownY = 0f
    private var twoFingerLastY = 0f
    private var twoFingerLastTime = 0L
    private var twoFingerStartedDuringAutoScroll = false

    private val autoScrollTick = object : Runnable {
        override fun run() {
            if (!autoScrollActive) return
            onTwoFingerDrag?.invoke(0f, autoScrollFractionPerMs * AUTO_SCROLL_TICK_MS)
            postDelayed(this, AUTO_SCROLL_TICK_MS.toLong())
        }
    }

    private fun cancelAutoScroll() {
        if (!autoScrollActive) return
        autoScrollActive = false
        removeCallbacks(autoScrollTick)
    }

    private fun maybeStartAutoScroll() {
        val dt = twoFingerLastTime - twoFingerDownTime
        val dy = twoFingerLastY - twoFingerDownY
        if (dt <= 0 || abs(dy) < MIN_FLICK_PX || height <= 0) {
            Log.d(TAG, "maybeStartAutoScroll skip dt=${dt}ms dy=${dy.toInt()} height=$height (need |dy|>=$MIN_FLICK_PX)")
            return
        }
        val fractionPerMs = (dy / dt) / height
        val cap = com.uxspace.spatial.WorkspaceController.flickSensitivity
        autoScrollFractionPerMs = fractionPerMs.coerceIn(-cap, cap)
        autoScrollActive = true
        Log.d(TAG, "maybeStartAutoScroll start dt=${dt}ms dy=${dy.toInt()} fractionPerMs=${"%.6f".format(autoScrollFractionPerMs)}")
        post(autoScrollTick)
    }

    override fun onDetachedFromWindow() {
        cancelAutoScroll()
        removeCallbacks(longPressTimeout)
        super.onDetachedFromWindow()
    }

    /**
     * Tap-and-drag state machine, modelled on the libinput public spec
     * (https://wayland.freedesktop.org/libinput/doc/latest/tapping.html). A first
     * touch + quick lift = a tap (a click in whatever the cursor is over). If a
     * SECOND touch lands within [DRAG_LOCK_MS] of that lift and is held down, the
     * gesture becomes a drag — touch-injection into the app under the cursor, or
     * a window-drag for a chrome target.
     *
     * [tapPending] mirrors the libinput "tap with finger lifted, drag-lock window
     * open" state. [dragging] is true while an actual drag is in flight.
     */
    private var tapPending = false
    private var dragging = false

    /** Posted on the lift of a confirmed first tap; reverts to idle if the user
     *  doesn't land a second touch within [DRAG_LOCK_MS]. */
    private val dragLockTimeout = Runnable { tapPending = false }

    /**
     * Long-press detection. Posted on every fresh ACTION_DOWN; cancelled if the
     * touch becomes a drag, tap, scroll, or pinch first. When it fires the
     * caller's onLongPress runs and [longPressFired] suppresses the upcoming
     * ACTION_UP's tap so a long-press doesn't also act as a click.
     */
    private var longPressFired = false
    private val longPressTimeout = Runnable {
        Log.i(
            TAG,
            "longPressTimeout fires: scrolling=$scrolling dragging=$dragging movedFar=$movedFar tapPending=$tapPending listenerSet=${onLongPress != null}",
        )
        if (!scrolling && !dragging && !movedFar) {
            longPressFired = true
            onLongPress?.invoke() ?: Log.w(TAG, "longPress conditions met but no onLongPress listener wired")
        } else {
            Log.i(TAG, "longPress suppressed (conditions failed)")
        }
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF9AA0AC")
        textAlign = Paint.Align.CENTER
        textSize = 38f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                downX = event.x
                downY = event.y
                downTime = event.eventTime
                movedFar = false
                scrolling = false
                gestureConsumed = false
                warmupActive = true
                warmupDx = 0f
                warmupDy = 0f
                cursorEmittedDx = 0f
                cursorEmittedDy = 0f
                longPressFired = false
                removeCallbacks(longPressTimeout)
                val longPressDelay = com.uxspace.spatial.WorkspaceController.longPressMs
                postDelayed(longPressTimeout, longPressDelay)
                Log.i(TAG, "DOWN scheduled longPressTimeout in ${longPressDelay}ms")
                // Any touch stops an active auto-scroll, matching how every
                // touchscreen behaves — finger on the surface means "stop".
                cancelAutoScroll()
                if (tapPending) {
                    // libinput tap-and-drag: a second touch within the drag-lock
                    // window after a confirmed tap = enter drag mode immediately.
                    removeCallbacks(dragLockTimeout)
                    tapPending = false
                    dragging = true
                    Log.d(TAG, "DOWN -> tap-and-drag DRAGGING at (${event.x.toInt()},${event.y.toInt()})")
                    onDragStart?.invoke()
                } else {
                    dragging = false
                    Log.d(TAG, "DOWN at (${event.x.toInt()},${event.y.toInt()}) t=${event.eventTime} autoScroll=$autoScrollActive")
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val sinceDown = event.eventTime - downTime
                Log.d(TAG, "POINTER_DOWN ptrs=${event.pointerCount} sinceDown=${sinceDown}ms warmupActive=$warmupActive warmupDx=${warmupDx.toInt()} warmupDy=${warmupDy.toInt()} cursorEmitted=(${"%.4f".format(cursorEmittedDx)},${"%.4f".format(cursorEmittedDy)})")
                // A second finger — switch from cursor-move to two-finger gestures. If
                // auto-scroll is running, this touch *stops* it (and the matching UP will
                // not start a new one).
                scrolling = true
                movedFar = true
                removeCallbacks(longPressTimeout)
                // A pending tap or in-flight drag is cancelled by the second finger.
                removeCallbacks(dragLockTimeout)
                tapPending = false
                if (dragging) {
                    dragging = false
                    onDragCancel?.invoke()
                }
                // Discard any one-finger movement buffered while we were waiting to see if
                // a second finger would arrive, and undo any cursor delta that already
                // emitted (warmup may have flushed early). Both keep the cursor where it
                // was when the gesture really began so auto-scroll can target the right
                // window.
                warmupActive = false
                warmupDx = 0f
                warmupDy = 0f
                if (cursorEmittedDx != 0f || cursorEmittedDy != 0f) {
                    Log.d(TAG, "POINTER_DOWN undoing cursor delta (${"%.4f".format(-cursorEmittedDx)},${"%.4f".format(-cursorEmittedDy)})")
                    onMove?.invoke(-cursorEmittedDx, -cursorEmittedDy)
                    cursorEmittedDx = 0f
                    cursorEmittedDy = 0f
                }
                twoFingerStartedDuringAutoScroll = autoScrollActive
                cancelAutoScroll()
                twoFingerDownTime = event.eventTime
                twoFingerDownY = averageY(event)
                twoFingerLastY = twoFingerDownY
                twoFingerLastTime = twoFingerDownTime
                lastScrollX = averageX(event)
                lastScrollY = twoFingerDownY
                lastSpread = pointerSpread(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (gestureConsumed) {
                    // Last finger is still lifting after a multi-finger gesture — its
                    // motion is not a new cursor drag, ignore until ACTION_UP.
                    return true
                }
                if (scrolling) {
                    val x = averageX(event)
                    val y = averageY(event)
                    val spread = pointerSpread(event)
                    val dSpread = spread - lastSpread
                    val dx = x - lastScrollX
                    val dy = y - lastScrollY
                    // Spread change dominating centroid translation → it's a pinch.
                    // Otherwise emit a two-finger drag; the controller routes it (in-window
                    // scroll, or PINNED-zoomed pan). The same vertical motion also feeds
                    // the flick velocity for auto-scroll on lift.
                    if (
                        spread > MIN_PINCH_SPREAD_PX && lastSpread > MIN_PINCH_SPREAD_PX &&
                        abs(dSpread) > abs(dy) * PINCH_BIAS
                    ) {
                        Log.d(TAG, "MOVE pinch spread=${spread.toInt()} dSpread=${dSpread.toInt()} dy=${dy.toInt()}")
                        onZoom?.invoke(spread / lastSpread)
                    } else if (width > 0 && height > 0) {
                        Log.d(TAG, "MOVE drag dx=${dx.toInt()} dy=${dy.toInt()}")
                        onTwoFingerDrag?.invoke(dx / width, dy / height)
                    }
                    twoFingerLastY = y
                    twoFingerLastTime = event.eventTime
                    lastScrollX = x
                    lastScrollY = y
                    lastSpread = spread
                } else {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    lastX = event.x
                    lastY = event.y
                    if (abs(event.x - downX) > TAP_SLOP_PX ||
                        abs(event.y - downY) > TAP_SLOP_PX
                    ) {
                        if (!movedFar) {
                            Log.i(
                                TAG,
                                "MOVE past slop dx=${(event.x - downX).toInt()} dy=${(event.y - downY).toInt()} — cancelling longPressTimeout",
                            )
                        }
                        movedFar = true
                        // Moved past slop → no long-press; release the pending timer.
                        removeCallbacks(longPressTimeout)
                    }
                    // Tap-and-drag model: ACTION_MOVE during the first touch is just
                    // cursor positioning. A pending tap is invalidated if we move past
                    // slop (movedFar) — the user is hovering, not tapping. If we're
                    // already DRAGGING (second-tap-and-hold), motion is captured by the
                    // renderer's per-frame handleDrag via the current cursor position.
                    if (warmupActive) {
                        warmupDx += dx
                        warmupDy += dy
                        val warmupOver =
                            event.eventTime - downTime > WARMUP_MS ||
                                abs(warmupDx) > WARMUP_DISTANCE_PX ||
                                abs(warmupDy) > WARMUP_DISTANCE_PX
                        if (warmupOver) {
                            val reason = when {
                                event.eventTime - downTime > WARMUP_MS -> "time(${event.eventTime - downTime}ms)"
                                abs(warmupDx) > WARMUP_DISTANCE_PX -> "dx(${warmupDx.toInt()}px)"
                                else -> "dy(${warmupDy.toInt()}px)"
                            }
                            Log.d(TAG, "WARMUP flush reason=$reason warmupDx=${warmupDx.toInt()} warmupDy=${warmupDy.toInt()} -> cursor move")
                            if (width > 0) {
                                val fx = warmupDx / width
                                val fy = warmupDy / width
                                onMove?.invoke(fx, fy)
                                cursorEmittedDx += fx
                                cursorEmittedDy += fy
                            }
                            warmupActive = false
                            warmupDx = 0f
                            warmupDy = 0f
                        }
                    } else if (width > 0) {
                        val fx = dx / width
                        val fy = dy / width
                        onMove?.invoke(fx, fy)
                        cursorEmittedDx += fx
                        cursorEmittedDy += fy
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val twoFingerDuration = event.eventTime - twoFingerDownTime
                val twoFingerDy = twoFingerLastY - twoFingerDownY
                Log.d(TAG, "POINTER_UP duration=${twoFingerDuration}ms dy=${twoFingerDy.toInt()} startedDuringAuto=$twoFingerStartedDuringAutoScroll")
                // Going from 2 fingers back down to 1 — finalize the two-finger gesture.
                if (scrolling && !twoFingerStartedDuringAutoScroll) {
                    maybeStartAutoScroll()
                }
                // Re-average over the fingers that remain, so the next move does not jump.
                lastScrollX = averageX(event, lifting = event.actionIndex)
                lastScrollY = averageY(event, lifting = event.actionIndex)
                lastSpread = pointerSpread(event, lifting = event.actionIndex)
                scrolling = false
                // Lock the rest of this touch — residual single-finger motion from the
                // lifting finger is not a new cursor drag.
                gestureConsumed = true
            }
            MotionEvent.ACTION_UP -> {
                val duration = event.eventTime - downTime
                Log.d(TAG, "UP duration=${duration}ms scrolling=$scrolling dragging=$dragging tapPending=$tapPending movedFar=$movedFar longPressFired=$longPressFired autoScroll=$autoScrollActive")
                removeCallbacks(longPressTimeout)
                if (dragging) {
                    // End of a drag — clean lift. The renderer's finalizePress emits
                    // ACTION_UP (or the window-drag release).
                    endDragIfActive()
                    // After a drag ends, no new tap-and-drag window opens — a fresh
                    // gesture is needed to start the next one.
                    tapPending = false
                } else if (longPressFired) {
                    // Long-press already triggered its own action — don't double-fire
                    // a tap on lift, and don't open a tap-and-drag window.
                    tapPending = false
                } else if (!scrolling && !movedFar &&
                    event.eventTime - downTime < TAP_TIMEOUT_MS
                ) {
                    Log.d(TAG, "UP -> tap (drag-lock window opens for ${DRAG_LOCK_MS}ms)")
                    onTap?.invoke()
                    // Open the libinput-style drag-lock window — a second touch within
                    // this interval becomes a drag instead of a second tap.
                    tapPending = true
                    removeCallbacks(dragLockTimeout)
                    postDelayed(dragLockTimeout, DRAG_LOCK_MS)
                } else {
                    // Slow lift or moved past slop — neither a tap nor a drag. No
                    // drag-lock window opens.
                    tapPending = false
                }
                scrolling = false
            }
            MotionEvent.ACTION_CANCEL -> {
                Log.d(TAG, "CANCEL")
                removeCallbacks(dragLockTimeout)
                removeCallbacks(longPressTimeout)
                tapPending = false
                if (dragging) {
                    dragging = false
                    onDragCancel?.invoke()
                }
                scrolling = false
            }
        }
        return true
    }

    private fun endDragIfActive() {
        if (dragging) {
            dragging = false
            onDragEnd?.invoke()
        }
    }

    /** Mean X of the active pointers, optionally excluding one that is lifting. */
    private fun averageX(event: MotionEvent, lifting: Int = -1): Float {
        var sum = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (i == lifting) continue
            sum += event.getX(i)
            count++
        }
        return if (count > 0) sum / count else lastScrollX
    }

    /** Mean Y of the active pointers, optionally excluding one that is lifting. */
    private fun averageY(event: MotionEvent, lifting: Int = -1): Float {
        var sum = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (i == lifting) continue
            sum += event.getY(i)
            count++
        }
        return if (count > 0) sum / count else lastScrollY
    }

    /** Distance between the first two active pointers; 0 if only one pointer remains. */
    private fun pointerSpread(event: MotionEvent, lifting: Int = -1): Float {
        val indices = (0 until event.pointerCount).filter { it != lifting }
        if (indices.size < 2) return 0f
        val a = indices[0]; val b = indices[1]
        val dx = event.getX(a) - event.getX(b)
        val dy = event.getY(a) - event.getY(b)
        return sqrt(dx * dx + dy * dy)
    }

    /** Centred hint text; one line per entry. */
    var hint: List<String> =
        listOf("Touchpad — drag to move · tap to click · hold to grab a window · two fingers scroll")
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val lineHeight = labelPaint.textSize * HINT_LINE_SPACING
        var y = height / 2f - lineHeight * (hint.size - 1) / 2f
        for (line in hint) {
            canvas.drawText(line, width / 2f, y, labelPaint)
            y += lineHeight
        }
    }

    private companion object {
        const val TAG = "TrackpadView"
        const val TAP_SLOP_PX = 24f
        const val TAP_TIMEOUT_MS = 300L

        /** libinput tap-and-drag window — a second touch within this interval after a
         *  confirmed tap's lift opens a drag instead of being a separate second tap. */
        const val DRAG_LOCK_MS = 250L

        /** Below this finger spread (pixels) a pinch reading is too jittery — fall back to scroll. */
        const val MIN_PINCH_SPREAD_PX = 50f

        /** Spread-change must outpace centroid-change by this factor to register as a pinch. */
        const val PINCH_BIAS = 1.2f

        /** Auto-scroll tick interval — 60 Hz, matches typical display refresh. */
        const val AUTO_SCROLL_TICK_MS = 16

        /** Hold one-finger cursor moves for this long before flushing — swallows the brief
         *  one-finger window before a two-finger gesture's second finger lands. */
        const val WARMUP_MS = 30L

        /** ...unless the finger has moved this far first, in which case flush early. */
        const val WARMUP_DISTANCE_PX = 8f

        /** Below this total vertical travel during the gesture, no auto-scroll starts. */
        const val MIN_FLICK_PX = 20

        /** Hint line pitch, in multiples of the label text size. */
        const val HINT_LINE_SPACING = 1.6f
    }
}
