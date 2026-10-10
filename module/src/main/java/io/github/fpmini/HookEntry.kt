package io.github.fpmini

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.WeakHashMap

/**
 * Hooks Samsung's biometric UI (com.samsung.android.biometrics.app.setting):
 *  - collapses the prompt card so only the fingerprint sensor indicator remains and touches pass through,
 *  - draws a state-aware glow (idle / scanning / success / fail) at the physical sensor of devices
 *    without an under-display sensor, in the prompt, the keyguard side-FPS window and enrollment.
 */
class HookEntry : IXposedHookLoadPackage {

    private class Sensor(val kind: SensorKind, val centre: Float?, val length: Float?, val samsungDraws: Boolean)

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != PKG) return
        val cl = lpparam.classLoader

        hookCollapse()
        hookSensorInfo(cl)
        hookGlowEvents(cl)
        hookSideFpsIndicator(cl)
        hookUdfpsIcon(cl)
        hookEnrollment(cl)
    }

    // ---- prompt collapse ------------------------------------------------------------------

    private fun hookCollapse() {
        // The prompt re-applies visibility at runtime, so also pin the hidden views to GONE.
        XposedBridge.hookAllMethods(View::class.java, "setVisibility", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (isTarget(param.thisObject as View)) param.args[0] = View.GONE
            }
        })
        XposedBridge.hookAllMethods(LayoutInflater::class.java, "inflate", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                (param.result as? View)?.let { hide(it) }
            }
        })

        // The prompt window is full-screen: drop its dim/blur and let touches fall through.
        val wmg = Class.forName("android.view.WindowManagerGlobal")
        val winHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.args[0] as? View ?: return
                val lp = param.args[1] as? WindowManager.LayoutParams ?: return
                if (lp.type != TYPE_BIOMETRIC_PROMPT) return
                if (param.method.name == "addView") Config.refresh(force = true)
                if (Config.layout != "off") {
                    lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
                    lp.dimAmount = 0f
                    lp.blurBehindRadius = 0
                }
                if (Config.bool(Settings.TOUCH)) {
                    lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    // Untrusted full-screen overlays make InputDispatcher drop touches for the windows
                    // below even when NOT_TOUCHABLE; mark it trusted.
                    try {
                        val f = WindowManager.LayoutParams::class.java.getDeclaredField("privateFlags")
                        f.isAccessible = true
                        f.setInt(lp, f.getInt(lp) or PRIVATE_FLAG_TRUSTED_OVERLAY)
                    } catch (t: Throwable) {
                        Log.e(TAG, "privateFlags", t)
                    }
                }
                if (param.method.name == "addView") addPromptGlow(view)
            }
        }
        XposedBridge.hookAllMethods(wmg, "addView", winHook)
        XposedBridge.hookAllMethods(wmg, "updateViewLayout", winHook)
        hookDeferredRemoval(wmg)
    }

    private fun addPromptGlow(root: View) {
        GlowState.set(GlowMode.IDLE)
        GlowState.exitAt = 0L
        GlowState.deadline = SystemClock.uptimeMillis() + Config.int(Settings.TIMEOUT) * 1000L
        if (Config.layout == "message") (root as? ViewGroup)?.let { MessagePill.attach(it, PKG) }
        if (!Config.bool(Settings.ANIM)) return
        val sensor = detect(root.context) ?: return
        if (sensor.samsungDraws) return // Samsung's own side-FPS window is re-skinned instead
        attachGlow(root as? ViewGroup ?: return, sensor)
        if (Config.bool(Settings.HOLD)) glowingWindows[root] = true
    }

    private val glowingWindows = WeakHashMap<View, Boolean>()
    private val removal = WeakHashMap<View, Int>() // 1 = deferred (swallow repeats), 2 = releasing

    /** The prompt window is removed instantly; keep it up so the result animation and the outro are seen. */
    private fun hookDeferredRemoval(wmg: Class<*>) {
        XposedBridge.hookAllMethods(wmg, "removeView", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.args[0] as? View ?: return
                if (glowingWindows[view] != true) return
                val state = removal[view]
                Log.i(TAG, "removeView mode=${GlowState.mode} state=$state")
                if (state == 2) return
                if (state == 1) { param.result = null; return }
                param.result = null
                removal[view] = 1
                val target = param.thisObject
                val m = param.method as java.lang.reflect.Method
                val args = param.args.clone()
                // success holds the result animation first; the outro plays at the end either way
                val hold = if (GlowState.mode == GlowMode.SUCCESS) 1900L else GlowPainter.EXIT_MS.toLong()
                GlowState.exitAt = SystemClock.uptimeMillis() + hold - GlowPainter.EXIT_MS.toLong()
                main.postDelayed({
                    removal[view] = 2
                    try { m.invoke(target, *args) } catch (t: Throwable) { Log.e(TAG, "deferred removeView", t) }
                }, hold)
            }
        })
    }

    private fun attachGlow(group: ViewGroup, sensor: Sensor) {
        val h = group.resources.displayMetrics.heightPixels
        val centre = if (sensor.kind == SensorKind.SIDE) sideCentre(h, sensor.centre) else sensor.centre
        val length = sideLength(h, sensor.length)
        group.addView(
            GlowView(group.context, sensor.kind, centre, length),
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        Log.i(TAG, "glow ${sensor.kind} centre=$centre length=$length")
    }

    /** Side sensor centre: the user's setting, else the real one, else 19% of the screen height. */
    private fun sideCentre(h: Int, real: Float?): Float {
        val y = Config.int(Settings.SIDE_Y)
        return if (y > 0) h * y / 100f else real ?: h * 0.19f
    }

    private fun sideLength(h: Int, real: Float?): Float? {
        val l = Config.int(Settings.SIDE_LEN)
        return if (l > 0) h * l / 100f else real
    }

    /** Samsung's floating feature; "capacitive_powerkey_phone" on power-key sensor phones. */
    private val powerKeySensor by lazy {
        try {
            val c = Class.forName("com.samsung.android.feature.SemFloatingFeature")
            val f = c.getMethod("getInstance").invoke(null)
            val v = c.getMethod("getString", String::class.java)
                .invoke(f, "SEC_FLOATING_FEATURE_BIOAUTH_CONFIG_FINGERPRINT_FEATURES") as? String ?: ""
            Log.i(TAG, "fingerprint features: $v")
            v.contains("powerkey", true) || v.contains("side", true)
        } catch (t: Throwable) {
            Log.w(TAG, "floating feature", t)
            false
        }
    }

    // ---- sensor detection -----------------------------------------------------------------

    /** Samsung's FingerprintSensorInfo, captured as the system service builds it. */
    @Volatile private var sensorInfo: Any? = null

    private fun hookSensorInfo(cl: ClassLoader) {
        val c = cl.loadOrNull("$PKG.fingerprint.FingerprintSensorInfo") ?: return
        XposedBridge.hookAllConstructors(c, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                sensorInfo = param.thisObject
            }
        })
    }

    /**
     * Returns null for UDFPS (the system already draws an icon) or when the type is unknown.
     * Ported ROMs can report UDFPS for a power-key sensor; the floating feature catches that.
     */
    private fun detect(ctx: Context): Sensor? {
        try {
            when (Config.sensor) {
                "side" -> return Sensor(SensorKind.SIDE, null, null, false)
                "rear" -> return Sensor(SensorKind.REAR, null, null, false)
                "udfps" -> return null
            }
            val misreported = Sensor(SensorKind.SIDE, null, null, false)
            sensorInfo?.let { info ->
                fun bool(n: String) = info.javaClass.getField(n).getBoolean(info)
                fun int(n: String) = info.javaClass.getField(n).getInt(info)
                return when {
                    bool("mIsAnyUdfps") -> if (powerKeySensor) misreported else null
                    bool("mIsAnySideFps") -> {
                        val len = int("mSidefpsHeight").toFloat()
                        Sensor(SensorKind.SIDE, int("mSidefpsTopMargin") + len / 2f, len, true)
                    }
                    else -> Sensor(SensorKind.REAR, null, null, false)
                }
            }
            // Fallback: sensorType 1 = rear, 2/3 = UDFPS, 4 = power button (side)
            val fm = ctx.getSystemService(Context.FINGERPRINT_SERVICE) ?: return null
            val props = fm.javaClass.getMethod("getSensorPropertiesInternal").invoke(fm) as? List<*> ?: return null
            val types = props.mapNotNull { it?.javaClass?.getField("sensorType")?.getInt(it) }
            return when {
                types.any { it == 2 || it == 3 } -> if (powerKeySensor) misreported else null
                types.any { it == 4 } -> Sensor(SensorKind.SIDE, null, null, false)
                types.any { it == 1 } -> Sensor(SensorKind.REAR, null, null, false)
                else -> null
            }
        } catch (t: Throwable) {
            Log.e(TAG, "detect", t)
            return null
        }
    }

    // ---- authentication events ------------------------------------------------------------

    private val main by lazy { Handler(Looper.getMainLooper()) }

    private fun hookGlowEvents(cl: ClassLoader) {
        fun on(cls: String, method: String, early: Boolean = false, run: (MethodHookParam) -> Unit) {
            val c = cl.loadOrNull(cls) ?: return
            XposedBridge.hookAllMethods(c, method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { if (early) run(param) }
                override fun afterHookedMethod(param: MethodHookParam) { if (!early) run(param) }
            })
        }
        for (cls in listOf("$PKG.prompt.BiometricPromptClient", "$PKG.fingerprint.SidefpsClient")) {
            on(cls, "onCaptureStart") { GlowState.set(GlowMode.SCANNING) }
            on(cls, "onFingerUp") { if (GlowState.mode == GlowMode.SCANNING) GlowState.set(GlowMode.IDLE) }
        }
        val prompt = "$PKG.prompt.BiometricPromptClient"
        on(prompt, "onAuthenticationSucceeded", early = true) { GlowState.set(GlowMode.SUCCESS) }
        on("$PKG.SysUiManager", "handleAuthenticationSucceeded", early = true) { GlowState.set(GlowMode.SUCCESS) }
        on(prompt, "onAuthenticationFailed") { GlowState.set(GlowMode.FAIL) }
        on(prompt, "onAuthenticationError") { GlowState.set(GlowMode.FAIL) }
    }

    // ---- UDFPS icon (hidden when the sensor is set or detected as side / rear) -------------

    private fun notUdfps(): Boolean {
        Config.refresh()
        return when (Config.sensor) {
            "side", "rear" -> true
            "auto" -> powerKeySensor
            else -> false
        }
    }

    private fun hookUdfpsIcon(cl: ClassLoader) {
        val c = cl.loadOrNull("$PKG.fingerprint.UdfpsSensorWindow") ?: return
        val hide = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args.isNotEmpty() && param.args[0] == View.VISIBLE && notUdfps()) param.args[0] = View.INVISIBLE
            }
        }
        XposedBridge.hookAllMethods(c, "setVisibility", hide)
        XposedBridge.hookAllMethods(c, "setSensorVisibility", hide)
    }

    // ---- Samsung's own side-FPS indicator -------------------------------------------------

    private val painters = WeakHashMap<View, GlowPainter>()

    private fun hookSideFpsIndicator(cl: ClassLoader) {
        val c = cl.loadOrNull("$PKG.fingerprint.SideFpsIndicatorView") ?: return
        XposedBridge.hookAllMethods(c, "onDraw", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    Config.refresh()
                    if (!Config.bool(Settings.ANIM)) return
                    val v = param.thisObject as View
                    val canvas = param.args[0] as Canvas
                    val pos = v.javaClass.getField("mSensorPos").get(v) as PointF
                    val size = v.javaClass.getField("mSensorSize").get(v) as PointF
                    val p = painters.getOrPut(v) { GlowPainter(v.resources.displayMetrics.density) }
                    val h = v.resources.displayMetrics.heightPixels
                    val centre = sideCentre(h, pos.y + size.y / 2f)
                    p.draw(canvas, v.width.toFloat(), v.height.toFloat(), SensorKind.SIDE, centre, sideLength(h, size.y) ?: size.y)
                    v.postInvalidateOnAnimation()
                    param.result = null // replaces the thin Samsung bar
                } catch (t: Throwable) {
                    Log.e(TAG, "sideFps onDraw", t)
                }
            }
        })
    }

    // ---- enrollment -----------------------------------------------------------------------

    private fun hookEnrollment(cl: ClassLoader) {
        val act = cl.loadOrNull("$PKG.fingerprint.enroll.FingerprintEnrollActivity")
        if (act != null) {
            XposedBridge.hookAllMethods(act, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val a = param.thisObject as Activity
                    Config.refresh(force = true)
                    if (!Config.bool(Settings.ANIM)) return
                    val sensor = detect(a) ?: return
                    GlowState.set(GlowMode.IDLE)
                    GlowState.exitAt = 0L
                    (a.window.decorView as? ViewGroup)?.let { attachGlow(it, sensor) }
                }
            })
        }
        val cb = cl.loadOrNull("$PKG.fingerprint.enroll.FingerprintEnrollActivity\$4") ?: return
        XposedBridge.hookAllMethods(cb, "onEnrollmentProgress", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val remaining = param.args[0] as? Int ?: return
                GlowState.set(if (remaining == 0) GlowMode.SUCCESS else GlowMode.SCANNING)
                if (remaining != 0) main.postDelayed({ if (GlowState.mode == GlowMode.SCANNING) GlowState.set(GlowMode.IDLE) }, 700)
            }
        })
        val fail = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) = GlowState.set(GlowMode.FAIL)
        }
        XposedBridge.hookAllMethods(cb, "onEnrollmentHelp", fail)
        XposedBridge.hookAllMethods(cb, "onEnrollmentError", fail)
    }

    // ---- helpers --------------------------------------------------------------------------

    private fun ClassLoader.loadOrNull(name: String): Class<*>? = try {
        loadClass(name)
    } catch (_: Throwable) {
        Log.w(TAG, "class not found: $name")
        null
    }

    private fun isTarget(v: View): Boolean {
        if (v.id == View.NO_ID) return false
        Config.refresh()
        val hidden = HIDDEN[Config.layout] ?: return false
        return try {
            v.resources.getResourceEntryName(v.id) in hidden
        } catch (_: Throwable) {
            false
        }
    }

    private fun hide(root: View) {
        if (isTarget(root)) {
            root.visibility = View.GONE
            return
        }
        (root as? ViewGroup)?.let { g ->
            for (i in 0 until g.childCount) hide(g.getChildAt(i))
        }
    }

    private companion object {
        const val TAG = "FpMini"
        const val TYPE_BIOMETRIC_PROMPT = 2618
        const val PRIVATE_FLAG_TRUSTED_OVERLAY = 0x20000000
        const val PKG = "com.samsung.android.biometrics.app.setting"
        private val CARD = setOf("id_prompt_dialog_full", "id_prompt_background", "id_prompt_knox_image")
        /** Views hidden per layout option; "off" hides nothing. */
        val HIDDEN = mapOf(
            "minimal" to CARD,
            "message" to CARD,
            "compact" to setOf("id_prompt_background", "id_prompt_knox_image", "id_prompt_logo_layout", "id_prompt_description_image"),
        )
    }
}

private typealias MethodHookParam = XC_MethodHook.MethodHookParam

