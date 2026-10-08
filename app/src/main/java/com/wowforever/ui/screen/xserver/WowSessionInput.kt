package com.wowforever.ui.screen.xserver

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.content.res.Resources
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager as WindowManagerLayout
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.winlator.container.Container
import com.winlator.inputcontrols.ExternalController
import com.winlator.renderer.TextFocusProbe
import com.winlator.renderer.VulkanRenderer
import com.winlator.xserver.Property
import com.winlator.xserver.Window
import com.winlator.xserver.WindowManager
import com.winlator.xserver.XServer
import com.wowforever.PluviaApp
import com.wowforever.wow.Wow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** WoW session input: auto soft keyboard on text focus and touch controls without a gamepad. Lives while [root] is attached. */
object WowSessionInput {
    const val VIRTUAL_GAMEPAD_PROFILE = "Virtual Gamepad"
    private const val TAG = "WowInput"

    // Battle.net login window: every substring of one entry must be in WM_NAME (case-insensitive).
    private val BNET_LOGIN_TITLES = listOf(
        listOf("battle.net", "login"),
        listOf("battle.net", "log in"),
        listOf("battle.net", "sign in"),
    )

    // Battle.net windows (name or class); a narrow one counts as the login dialog when the title doesn't match.
    private val BNET_HINTS = listOf("battle.net")
    private const val BNET_LOGIN_MIN_W = 300
    private const val BNET_LOGIN_MAX_W = 700
    private const val BNET_LOGIN_MIN_H = 400
    private const val BNET_LOGIN_MAX_H = 1000

    // WoW game window (name or class).
    private val WOW_HINTS = listOf("wowb-arm64", "wow-arm64", "world of warcraft")

    // WoW glue (login) screen counts as text focus once WoW has presented this long without the cyan marker.
    private const val GLUE_DELAY_MS = 2000L

    @Volatile
    private var activeKeyboard: AutoKeyboard? = null

    fun install(
        root: View,
        container: Container,
        showControls: () -> Unit,
        hideControls: () -> Unit,
        hideImeReceiver: () -> Unit,
    ) {
        val keyboard = AutoKeyboard(root, container, hideImeReceiver)
        val imeShift = ImeShift(root)
        val controls = GamepadWatcher(root.context) { hasGamepad -> if (hasGamepad) hideControls() else showControls() }
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                // Posted so it runs after XServerScreen's own initial show/hide of the controls.
                v.post { if (v.isAttachedToWindow) controls.start() }
                keyboard.start()
                PluviaApp.touchpadView?.setTouchObserver { onTouchEvent(it) }
                imeShift.start()
            }

            override fun onViewDetachedFromWindow(v: View) {
                controls.stop()
                keyboard.stop()
                PluviaApp.touchpadView?.setTouchObserver(null)
                imeShift.stop()
            }
        }
        root.addOnAttachStateChangeListener(listener)
        if (root.isAttachedToWindow) listener.onViewAttachedToWindow(root)
    }

    /** Called for every key event sent to the game; Enter on WoW's login screen closes the keyboard. */
    fun onKeyEvent(event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_UP) return
        if (event.keyCode != KeyEvent.KEYCODE_ENTER && event.keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER) return
        activeKeyboard?.onEnter()
    }

    /** Hides the soft keyboard now (drawer "Keyboard" toggle). */
    fun hideKeyboard() {
        activeKeyboard?.let { k -> k.root.post { k.hideIme() } }
    }

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var tapCandidate = false

    /** Called (via TouchpadView) for every touch that reaches the game; a plain tap may re-open the keyboard (see AutoKeyboard.onTap). */
    fun onTouchEvent(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTime = event.eventTime
                tapCandidate = !isOnTouchControl(event.x, event.y)
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> tapCandidate = false
            MotionEvent.ACTION_MOVE -> {
                val slop = 16 * Resources.getSystem().displayMetrics.density
                if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) tapCandidate = false
            }
            MotionEvent.ACTION_UP -> {
                if (tapCandidate && event.eventTime - downTime < 500) activeKeyboard?.onTap()
                tapCandidate = false
            }
        }
    }

    private fun isOnTouchControl(x: Float, y: Float): Boolean {
        val icView = PluviaApp.inputControlsView ?: return false
        if (icView.visibility != View.VISIBLE || !icView.isShowTouchscreenControls) return false
        return icView.profile?.elements?.any { it.containsPoint(x, y) } == true
    }

    private fun String.hasAny(hints: List<String>) = hints.any { contains(it, ignoreCase = true) }

    /** Reports physical gamepad presence on start and whenever it changes. */
    private class GamepadWatcher(context: Context, private val onChange: (Boolean) -> Unit) : InputManager.InputDeviceListener {
        private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        private val handler = Handler(Looper.getMainLooper())
        private var last: Boolean? = null
        private var running = false

        fun start() {
            if (running) return
            running = true
            last = null
            inputManager.registerInputDeviceListener(this, handler)
            evaluate()
        }

        fun stop() {
            if (!running) return
            running = false
            inputManager.unregisterInputDeviceListener(this)
            handler.removeCallbacksAndMessages(null)
        }

        // Delayed so XServerScreen's own device handling runs first.
        private fun schedule() {
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({ if (running) evaluate() }, 150)
        }

        private fun evaluate() {
            val has = InputDevice.getDeviceIds().any { ExternalController.isGameController(InputDevice.getDevice(it)) }
            if (has == last) return
            last = has
            Timber.tag(TAG).i("Physical gamepad: %b", has)
            onChange(has)
        }

        override fun onInputDeviceAdded(deviceId: Int) = schedule()
        override fun onInputDeviceRemoved(deviceId: Int) = schedule()
        override fun onInputDeviceChanged(deviceId: Int) = schedule()
    }

    /** Tracks mapped X windows (on the X server thread): Battle.net login on top, WoW window id. */
    private class WindowWatcher : WindowManager.OnWindowModificationListener {
        @Volatile var bnetLoginOnTop = false
        @Volatile var wowWindowId = 0
        private var lastLog = ""

        fun refresh(wm: WindowManager) {
            val mapped = ArrayList<Window>()
            collect(wm.rootWindow, mapped)
            val log = mapped.joinToString(" | ") { describe(it) } + " focus=#${wm.focusedWindow?.id ?: 0}"
            if (log != lastLog) {
                lastLog = log
                Timber.tag(TAG).i("Mapped windows (bottom->top): %s", log.ifEmpty { "none" })
            }
            // Tooltips and other no-input popups never count as the top window.
            val top = mapped.lastOrNull { !it.className.contains("explorer.exe", ignoreCase = true) && it.acceptsFocus() }
            bnetLoginOnTop = top != null && isBnetLogin(top)
            wowWindowId = mapped.lastOrNull { isWow(it) }?.id ?: 0
        }

        private fun describe(w: Window): String {
            val tr = w.getProperty(com.winlator.xserver.Atom.getId("WM_TRANSIENT_FOR"))?.getInt(0) ?: 0
            val type = w.getProperty(com.winlator.xserver.Atom.getId("_NET_WM_WINDOW_TYPE"))?.let { com.winlator.xserver.Atom.getName(it.getInt(0)) } ?: ""
            return "#${w.id} '${w.name}' class='${w.className}' ${w.width}x${w.height}+${w.x}+${w.y} or=${w.attributes.isOverrideRedirect} tr=#$tr type=$type hwnd=${java.lang.Long.toHexString(w.handle)}" +
                (if (w.width < 200) " props{" + w.serializeProperties().lines().filter { !it.startsWith("_NET_WM_ICON") }.joinToString(";").take(800) + "}" else "")
        }

        // Mapped application windows in stacking order (children above their parent).
        private fun collect(w: Window, out: MutableList<Window>) {
            for (child in w.children) {
                if (!child.attributes.isMapped()) continue
                if (child.isApplicationWindow()) out += child
                collect(child, out)
            }
        }

        private fun isBnetLogin(w: Window): Boolean {
            val name = w.name
            if (BNET_LOGIN_TITLES.any { parts -> parts.all { name.contains(it, ignoreCase = true) } }) return true
            if (!name.hasAny(BNET_HINTS) && !w.className.hasAny(BNET_HINTS)) return false
            return w.width in BNET_LOGIN_MIN_W..BNET_LOGIN_MAX_W && w.height in BNET_LOGIN_MIN_H..BNET_LOGIN_MAX_H
        }

        private fun isWow(w: Window): Boolean {
            val name = w.name
            if (name.hasAny(BNET_HINTS)) return false
            return name.hasAny(WOW_HINTS) || w.className.hasAny(WOW_HINTS)
        }

        private fun wm() = PluviaApp.xServerView?.getxServer()?.windowManager
        override fun onMapWindow(window: Window) { wm()?.let(::refresh) }
        override fun onUnmapWindow(window: Window) { wm()?.let(::refresh) }
        override fun onChangeWindowZOrder(window: Window) { wm()?.let(::refresh) }
        override fun onUpdateWindowGeometry(window: Window, resized: Boolean) { if (resized) wm()?.let(::refresh) }
        override fun onModifyWindowProperty(window: Window, property: Property) { wm()?.let(::refresh) }
        override fun onDestroyWindow(window: Window) { wm()?.let(::refresh) }
    }

    /** Fits the game picture above the soft keyboard (letterboxed, top-aligned); touches follow via TouchpadView. */
    private class ImeShift(private val root: View) {
        private val handler = Handler(Looper.getMainLooper())
        private var shift = 0
        private var prevSoftInputMode: Int? = null
        private val tick = object : Runnable {
            override fun run() {
                update()
                handler.postDelayed(this, 50)
            }
        }

        fun start() {
            // adjustNothing: the IME never resizes the surface; API 30+ still reports IME insets.
            if (Build.VERSION.SDK_INT >= 30 && prevSoftInputMode == null) {
                activity()?.window?.let { w ->
                    val prev = w.attributes.softInputMode
                    prevSoftInputMode = prev
                    w.setSoftInputMode(
                        (prev and WindowManagerLayout.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or
                            WindowManagerLayout.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,
                    )
                }
            }
            handler.removeCallbacks(tick)
            handler.post(tick)
        }

        fun stop() {
            handler.removeCallbacks(tick)
            apply(0)
            prevSoftInputMode?.let { mode -> activity()?.window?.setSoftInputMode(mode) }
            prevSoftInputMode = null
        }

        private fun activity(): Activity? {
            var c: Context? = root.context
            while (c is ContextWrapper) {
                if (c is Activity) return c
                c = c.baseContext
            }
            return null
        }

        private fun update() {
            if (!root.isAttachedToWindow) return
            val insets = ViewCompat.getRootWindowInsets(root)
            val ime = if (insets?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            } else {
                0
            }
            // Only the part of the IME that overlaps the game root counts.
            val loc = IntArray(2)
            root.getLocationInWindow(loc)
            val belowRoot = root.rootView.height - (loc[1] + root.height)
            val target = (ime - belowRoot).coerceAtLeast(0)
            if (target != shift) apply(target)
        }

        private fun apply(target: Int) {
            shift = target
            (PluviaApp.xServerView?.getRenderer() as? VulkanRenderer)?.setContentInsetBottom(target)
            PluviaApp.touchpadView?.setContentInsetBottom(target)
        }
    }

    /**
     * Text focus = Battle.net caret file OR WoW magenta marker OR Battle.net login on top OR WoW glue screen.
     * Shows/hides the IME on focus edges only.
     */
    private class AutoKeyboard(
        val root: View,
        container: Container,
        private val hideImeReceiver: () -> Unit,
    ) {
        private val focusFile = Wow.winToHost(container, Wow.TEXTFOCUS_FILE)
        // Battle.net launches skip WoW's password screen, so the glue rule only applies to a direct PLAY.
        private val glueRuleEnabled = container.getExtra(Wow.EXTRA_LAUNCH_TARGET, "PLAY") == "PLAY"
        private val windows = WindowWatcher()
        private var xServer: XServer? = null
        private var scope: CoroutineScope? = null

        @Volatile private var glueActive = false
        @Volatile private var glueSuppressedFor = 0

        fun start() {
            if (scope != null) return
            TextFocusProbe.setEnabled(true)
            glueSuppressedFor = 0
            activeKeyboard = this
            PluviaApp.xServerView?.getxServer()?.let { xs ->
                xServer = xs
                xs.windowManager.addOnWindowModificationListener(windows)
                xs.lock(XServer.Lockable.WINDOW_MANAGER).use { windows.refresh(xs.windowManager) }
            }
            val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope = s
            s.launch {
                var focused = false
                var streak = 0
                while (isActive) {
                    val now = computeFocus()
                    // Two equal samples in a row before acting (filters single odd frames).
                    streak = if (now != focused) streak + 1 else 0
                    if (streak >= 2) {
                        focused = now
                        streak = 0
                        withContext(Dispatchers.Main) { onFocusChanged(now) }
                    }
                    delay(100)
                }
            }
        }

        fun stop() {
            scope?.cancel()
            scope = null
            xServer?.windowManager?.removeOnWindowModificationListener(windows)
            xServer = null
            if (activeKeyboard === this) activeKeyboard = null
            TextFocusProbe.setEnabled(false)
        }

        /** WoW is up but its in-game UI (cyan marker) never appeared: login / realm / character screens. */
        private fun onGlueScreen(): Boolean {
            val wowId = windows.wowWindowId
            return glueRuleEnabled && wowId != 0 && !TextFocusProbe.cyanSeen(wowId) && TextFocusProbe.presentingMs(wowId) > 0
        }

        private var lastShowMs = 0L

        /** Shows the IME unless it is up or was just requested (the show path toggles). */
        private fun showKeyboard() {
            val now = SystemClock.uptimeMillis()
            if (imeVisible() || now - lastShowMs < 1000) return
            lastShowMs = now
            PluviaApp.touchpadView?.requestShowKeyboard()
        }

        /** A plain tap: re-open a hidden keyboard on login screens or while a text field has focus. */
        fun onTap() {
            if (!root.isAttachedToWindow || imeVisible()) return
            val reason = when {
                windows.bnetLoginOnTop -> "bnet-login"
                onGlueScreen() -> "wow-login"
                TextFocusProbe.isMarkerVisible() -> "wow-editbox"
                readFocusFile() -> "caret"
                else -> return
            }
            Timber.tag(TAG).d("Tap -> keyboard (%s)", reason)
            root.postDelayed({ if (root.isAttachedToWindow) showKeyboard() }, 150)
        }

        /** Enter on the glue screen: hide and ignore the glue rule until in-game or WoW's window changes. */
        fun onEnter() {
            if (!glueActive && !onGlueScreen()) return
            glueSuppressedFor = windows.wowWindowId
            glueActive = false
            Timber.tag(TAG).d("Enter on WoW login screen: keyboard closed")
            root.post { hideIme() }
        }

        private fun computeFocus(): Boolean {
            val wowId = windows.wowWindowId
            if (glueSuppressedFor != 0 && glueSuppressedFor != wowId) glueSuppressedFor = 0
            glueActive = glueRuleEnabled && wowId != 0 && glueSuppressedFor == 0 && !TextFocusProbe.cyanSeen(wowId) &&
                TextFocusProbe.presentingMs(wowId) >= GLUE_DELAY_MS
            return glueActive || windows.bnetLoginOnTop || TextFocusProbe.isMarkerVisible() || readFocusFile()
        }

        private fun readFocusFile(): Boolean = try {
            focusFile.isFile && focusFile.readText().startsWith("1")
        } catch (_: Exception) {
            false
        }

        private fun imeVisible(): Boolean =
            ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == true

        fun hideIme() {
            hideImeReceiver()
            if (Build.VERSION.SDK_INT >= 30) {
                root.windowInsetsController?.hide(WindowInsets.Type.ime())
            } else {
                val imm = root.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                root.windowToken?.let { imm.hideSoftInputFromWindow(it, 0) }
            }
        }

        private fun onFocusChanged(focused: Boolean) {
            if (!root.isAttachedToWindow) return
            Timber.tag(TAG).d("Text focus: %b (bnetLogin=%b, glue=%b)", focused, windows.bnetLoginOnTop, glueActive)
            if (focused) {
                // Toggle-based path: skip if the IME is already up so it isn't closed.
                showKeyboard()
            } else {
                hideIme()
            }
        }
    }
}
