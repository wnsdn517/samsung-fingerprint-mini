package io.github.fpmini

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class SensorKind { SIDE, REAR }

enum class GlowMode { IDLE, SCANNING, SUCCESS, FAIL }

/** Process-wide authentication state; the Samsung clients drive it, every glow view reads it. */
object GlowState {
    @Volatile var mode = GlowMode.IDLE
        private set
    @Volatile var since = SystemClock.uptimeMillis()
        private set
    /** uptimeMillis at which the prompt times out; 0 = unknown (no countdown). */
    @Volatile var deadline = 0L
    /** uptimeMillis at which the outro starts (the window goes away EXIT_MS later); 0 = showing. */
    @Volatile var exitAt = 0L

    fun set(m: GlowMode) {
        if (mode == m && m != GlowMode.FAIL && m != GlowMode.SUCCESS) return
        mode = m
        since = SystemClock.uptimeMillis()
    }
}

/**
 * A thin filament of white light on the screen edge at the sensor, wrapped in a tight glow and a
 * fine haze of star-dust that it lights up. Everything stays within a few dozen dp of the edge.
 *  - intro: the filament draws open from the sensor centre while the dust condenses around it,
 *  - scanning: brighter, a highlight sweeps along it and the dust is drawn in,
 *  - success: the glow turns softly green, the dust breathes outwards, a check draws itself,
 *  - fail: a red ripple runs along the filament like a plucked string (nothing moves rigidly),
 *  - timeout: the filament slowly closes towards the centre; outro: it closes and fades.
 *
 * Local space: the screen edge is x = 0, the sensor is centred on y = 0 and +x points into the screen.
 */
class GlowPainter(private val density: Float) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()
    private val checkPath = Path()
    private val segment = Path()
    private val shader by lazy { if (Build.VERSION.SDK_INT >= 33) RuntimeShader(SRC) else null }

    private fun dp(v: Float) = v * density

    private val open = Spring(0f, 160f, 0.68f)    // filament length, 0..1
    private val power = Spring(0f, 120f, 0.9f)
    private val glow = Spring(1f, 110f, 0.85f)    // glow width scale
    private val pull = Spring(0f, 60f, 0.8f)      // dust: >0 drawn in, <0 pushed out
    private val sweep = Spring(0f, 80f, 1f)       // scanning highlight amount
    private val tint = Spring(0f, 90f, 1f)        // 0 = white, 1 = state colour
    private val badge = Spring(0f, 240f, 0.6f)    // check mark scale
    private var tintRgb = GREEN

    private var lastFrame = 0L
    private var bornAt = 0L
    private var lastMode = GlowMode.IDLE
    private var stirAt = 0L    // back to waiting ("try again"): the dust stirs for a moment
    private var stirFlow = 0f

    /** @param centre sensor centre along the edge (px); @param length sensor length (px) */
    fun draw(canvas: Canvas, w: Float, h: Float, kind: SensorKind, centre: Float, length: Float) {
        val now = SystemClock.uptimeMillis()
        // first frame, or the view comes back after being hidden: play the intro again
        val reborn = lastFrame == 0L || now - lastFrame > 500
        val dt = if (reborn) 0.016f else ((now - lastFrame) / 1000f).coerceIn(0.001f, 0.05f)
        lastFrame = now
        if (reborn) {
            bornAt = now
            open.reset(0f); power.reset(0f); glow.reset(0.5f); pull.reset(-0.9f)
            sweep.reset(0f); tint.reset(0f); badge.reset(0f)
        }

        var mode = GlowState.mode
        var age = now - GlowState.since
        if (demo(now)) {
            // debug: cycle through every state so the animations can be reviewed without a finger
            val ms = (now - bornAt) % 10_000L
            val (m, start) = when {
                ms < 2500 -> GlowMode.IDLE to 0L
                ms < 4500 -> GlowMode.SCANNING to 2500L
                ms < 6500 -> GlowMode.SUCCESS to 4500L
                ms < 8000 -> GlowMode.IDLE to 6500L
                else -> GlowMode.FAIL to 8000L
            }
            mode = m; age = ms - start
        }
        if ((mode == GlowMode.FAIL && age > 1100) || (mode == GlowMode.SUCCESS && age > 2000)) {
            mode = GlowMode.IDLE
            age = 0
        }

        if (mode != lastMode) {
            if (mode == GlowMode.IDLE && !reborn) stirAt = now
            lastMode = mode
        }
        val ss = if (stirAt == 0L) 9f else (now - stirAt) / 1000f
        val stir = smooth(0f, 0.12f, ss) * exp(-ss / 0.55f)
        stirFlow = (stirFlow + stir * dp(150f) * dt) % 10_000f

        // timeout: the filament slowly closes over the last seconds
        var drain = 0f
        val dl = GlowState.deadline
        if (dl != 0L && mode != GlowMode.SUCCESS) {
            val remaining = (dl - now) / 1000f
            if (remaining <= FUSE_WINDOW) drain = (1f - max(0f, remaining) / FUSE_WINDOW).coerceIn(0f, 1f)
        }

        val xa = GlowState.exitAt
        val exit = if (xa == 0L || xa < bornAt || now < xa) 0f else ((now - xa) / EXIT_MS).coerceIn(0f, 1f)

        when (mode) {
            GlowMode.IDLE -> { power.target = 0.85f; glow.target = 1f; pull.target = 0f; sweep.target = 0f; tint.target = 0f }
            GlowMode.SCANNING -> { power.target = 1.15f; glow.target = 1.2f; pull.target = 0.45f; sweep.target = 1f; tint.target = 0f }
            GlowMode.SUCCESS -> { power.target = 1.1f; glow.target = 1.3f; pull.target = -0.45f; sweep.target = 0f; tint.target = 1f; tintRgb = GREEN }
            GlowMode.FAIL -> { power.target = 1f; glow.target = 1.05f; pull.target = -0.15f; sweep.target = 0f; tint.target = 1f; tintRgb = RED }
        }
        open.target = 1f - 0.8f * drain
        if (exit > 0f) pull.target = 0.6f
        power.target *= 1f - 0.5f * drain
        badge.target = if (mode == GlowMode.SUCCESS && age > 150 && exit == 0f) 1f else 0f
        for (s in springs) s.step(dt)

        val t = (now - bornAt) / 1000f
        // outro, staggered: the rainbow and dust melt away first while the filament eases shut
        // towards the centre, glowing a touch brighter as it condenses, then the last point dims
        val outer = 1f - smooth(0f, 0.55f, exit)
        val close = 1f - easeInOut(smooth(0f, 0.85f, exit))
        val condense = 1f + 0.35f * sin(PI.toFloat() * smooth(0.15f, 0.8f, exit))
        val fade = 1f - smooth(0.6f, 1f, exit)
        val half = max(length * 0.8f, dp(48f)) * max(0f, open.value) * close
        // fail: a damped travelling ripple along the filament
        val ripple = if (mode == GlowMode.FAIL) dp(4.5f) * exp(-age / 380f) * smooth(0f, 60f, age.toFloat()) else 0f

        canvas.save()
        when (kind) {
            SensorKind.SIDE -> { canvas.translate(w, centre); canvas.scale(-1f, 1f) }
            SensorKind.REAR -> { canvas.translate(centre, h); canvas.rotate(-90f) }
        }
        if (fade > 0.001f) drawLight(canvas, half, t, age / 1000f, ripple, fade, outer, condense, 0.55f + 0.45f * close, stir)
        if (badge.value > 0.01f) drawBadge(canvas, kind, age, fade)
        canvas.restore()
    }

    // ---- drawing --------------------------------------------------------------------------

    private fun drawLight(
        canvas: Canvas, half: Float, t: Float, ageS: Float, ripple: Float,
        alpha: Float, outer: Float, boost: Float, shrink: Float, stir: Float,
    ) {
        val sh = shader
        val gw = dp(6.5f) * max(0.2f, glow.value) * shrink
        val pow = max(0f, power.value) * boost
        val mixT = tint.value.coerceIn(0f, 1f)
        if (sh != null && canvas.isHardwareAccelerated) {
            val ext = dp(220f)
            rect.set(0f, -half - ext, ext, half + ext)
            sh.setFloatUniform("halfLen", max(half, 0.5f))
            sh.setFloatUniform("glowW", gw)
            sh.setFloatUniform("power", pow)
            sh.setFloatUniform("time", t % 600f)
            sh.setFloatUniform("sweepAmt", sweep.value.coerceIn(0f, 1f))
            sh.setFloatUniform("rippleA", ripple)
            sh.setFloatUniform("rippleT", ageS)
            sh.setFloatUniform("pull", pull.value - 0.55f * stir)
            sh.setFloatUniform("stir", stir)
            sh.setFloatUniform("stirFlow", stirFlow)
            sh.setFloatUniform("px", density)
            sh.setFloatUniform("tint", r(tintRgb), g(tintRgb), b(tintRgb))
            sh.setFloatUniform("tintMix", mixT)
            sh.setFloatUniform("alpha", alpha)
            sh.setFloatUniform("outer", outer)
            sh.setFloatUniform("ext", ext)
            paint.shader = sh
            canvas.drawRect(rect, paint)
        } else {
            // fallback without AGSL: a soft glow and a thin white line
            val c = mixRgb(0xE8F0FF, tintRgb, mixT * 0.6f)
            val rr = gw * 5f
            canvas.save()
            canvas.scale(1f, (half + rr) / rr)
            paint.shader = RadialGradient(
                0f, 0f, rr,
                intArrayOf(argb(0.5f * alpha * pow, c), argb(0f, c)), null, Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(0f, 0f, rr, paint)
            canvas.restore()
            paint.shader = LinearGradient(
                0f, -half, 0f, half,
                intArrayOf(argb(0f, 0xFFFFFF), argb(alpha, 0xFFFFFF), argb(0f, 0xFFFFFF)), null, Shader.TileMode.CLAMP,
            )
            rect.set(0f, -half, dp(1.5f), half)
            canvas.drawRect(rect, paint)
        }
        paint.shader = null
    }

    private fun drawBadge(canvas: Canvas, kind: SensorKind, age: Long, fade: Float) {
        val r = dp(15f)
        val s = badge.value
        canvas.save()
        canvas.translate(BADGE_X * density, 0f)
        canvas.rotate(if (kind == SensorKind.REAR) 90f else 0f)
        if (kind == SensorKind.SIDE) canvas.scale(-1f, 1f) // undo the mirror used for the right edge
        canvas.scale(s, s)

        val a = fade * min(1f, s)
        paint.shader = RadialGradient(
            0f, 0f, r * 2.2f,
            intArrayOf(argb(0.35f * a, GREEN), argb(0f, GREEN)), null, Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(0f, 0f, r * 2.2f, paint)
        paint.shader = null
        stroke.strokeWidth = dp(1.2f)
        stroke.color = argb(0.5f * a, 0xFFFFFF)
        canvas.drawCircle(0f, 0f, r, stroke)

        // the check draws itself on once the badge has popped in
        val ramp = ((age - 260) / 300f).coerceIn(0f, 1f)
        if (ramp > 0f) {
            checkPath.reset()
            checkPath.moveTo(-r * 0.40f, r * 0.02f); checkPath.lineTo(-r * 0.11f, r * 0.30f); checkPath.lineTo(r * 0.42f, -r * 0.28f)
            val pm = PathMeasure(checkPath, false)
            segment.reset()
            pm.getSegment(0f, pm.length * easeOut(ramp), segment, true)
            stroke.strokeWidth = dp(2.2f)
            stroke.color = argb(a, 0xFFFFFF)
            canvas.drawPath(segment, stroke)
        }
        canvas.restore()
    }

    // ---- helpers --------------------------------------------------------------------------

    private var demoOn = false
    private var demoChecked = 0L

    /** `setprop debug.fpmini.demo 1`; re-read once a second. */
    private fun demo(now: Long): Boolean {
        if (now - demoChecked > 1000) {
            demoChecked = now
            demoOn = try {
                val sp = Class.forName("android.os.SystemProperties")
                sp.getMethod("get", String::class.java, String::class.java).invoke(null, "debug.fpmini.demo", "") == "1"
            } catch (_: Throwable) { false }
        }
        return demoOn
    }

    /** Damped spring, integrated in small sub-steps so stiff springs stay stable. */
    private class Spring(var value: Float, private val stiffness: Float, var ratio: Float) {
        var target = value
        private var velocity = 0f

        fun reset(v: Float) { value = v; velocity = 0f }

        fun step(dt: Float) {
            val c = 2f * ratio * sqrt(stiffness)
            var left = dt
            while (left > 0f) {
                val h = min(left, 1f / 240f)
                velocity += (stiffness * (target - value) - c * velocity) * h
                value += velocity * h
                left -= h
            }
        }
    }

    private val springs = arrayOf(open, power, glow, pull, sweep, tint, badge)

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val k = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return k * k * (3f - 2f * k)
    }

    private fun easeOut(x: Float) = 1f - (1f - x) * (1f - x) * (1f - x)

    private fun easeInOut(x: Float) = if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).let { it * it * it } / 2f

    private fun r(c: Int) = (c shr 16 and 0xFF) / 255f
    private fun g(c: Int) = (c shr 8 and 0xFF) / 255f
    private fun b(c: Int) = (c and 0xFF) / 255f

    private fun mixRgb(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = ((a shr s and 0xFF) * (1 - t) + (b shr s and 0xFF) * t).toInt()
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun argb(a: Float, rgb: Int) =
        ((a.coerceIn(0f, 1f) * 255).toInt() shl 24) or (rgb and 0xFFFFFF)

    companion object {
        const val EXIT_MS = 650f
        private const val FUSE_WINDOW = 8f // s before the timeout at which the filament starts closing
        private const val BADGE_X = 50f
        private const val GREEN = 0x34C759 // Apple system green
        private const val RED = 0xFF453A   // Apple system red (dark)

        private const val SRC = """
            uniform float halfLen;   // filament half length, px
            uniform float glowW;     // glow falloff, px
            uniform float power;
            uniform float time;      // s
            uniform float sweepAmt;
            uniform float rippleA;   // px
            uniform float rippleT;   // s
            uniform float pull;      // dust: >0 drawn in, <0 pushed out
            uniform float px;        // px per dp
            uniform float3 tint;
            uniform float tintMix;
            uniform float alpha;
            uniform float outer;     // rainbow / dust / wide glow, fades first on the outro
            uniform float ext;       // drawing box margin, px; everything fades out before it
            uniform float stir;      // 0..1, brief dust stir when going back to waiting
            uniform float stirFlow;  // px the dust has streamed along the edge during the stir

            float hash(float2 p) {
                p = fract(p * float2(123.34, 456.21));
                p += dot(p, p + 45.32);
                return fract(p.x * p.y);
            }
            half3 spectral(float x) {
                float3 k = (float3(x) - float3(0.10, 0.48, 0.88)) / 0.22;
                half3 c = half3(exp(-k * k));
                return c / max(c.r, max(c.g, c.b));
            }
            float segDist(float2 p, float h) {
                return length(float2(max(p.x, 0.0), p.y - clamp(p.y, -h, h)));
            }

            half4 main(float2 p) {
                float u = p.y / halfLen;
                float ends = 1.0 - smoothstep(0.45, 1.0, abs(u));

                // fail: a ripple travelling outwards along the filament, strongest at the centre
                float disp = rippleA * sin(abs(p.y) / (px * 9.0) - rippleT * 26.0) * exp(-abs(p.y) / (halfLen * 1.4 + px));
                float x = p.x - disp;
                float d = segDist(float2(x, p.y), halfLen);

                // scanning: a soft highlight gliding back and forth
                float sp = sin(time * 2.4) * 0.8;
                float hl = sweepAmt * exp(-((u - sp) * (u - sp)) / 0.08);

                float core = exp(-(x * x) / (px * px * 0.9)) * ends * (1.0 + 1.2 * hl);
                float g1 = 0.72 * exp(-d / glowW) * (0.55 + 0.45 * ends) * (1.0 + 0.6 * hl);
                float g2 = 0.13 * exp(-d / (glowW * 3.2)) * outer;

                half3 cool = half3(0.90, 0.94, 1.0);
                half3 glowC = mix(cool, half3(tint), half(tintMix * 0.65));
                half3 coreC = mix(half3(1.0), half3(tint), half(tintMix * 0.25));
                half3 rgb = coreC * half(core * 1.75) + glowC * half(g1 + g2);

                // rainbow haze on part of the glow: it lifts off a little away from the filament,
                // then fades out gently the farther it goes; neighbouring hues are blended so the
                // colours melt into one another instead of forming stripes
                float th = atan(p.y, p.x + px * 6.0);
                float arc = 0.5 + 0.5 * sin(th * 2.2 - time * 0.2 + 0.8);
                arc *= arc;
                float rise = smoothstep(px * 5.0, px * 18.0, d);
                float fadeOut = exp(-max(d - px * 18.0, 0.0) / (px * 27.0));
                float hue = 0.12 + 0.55 * smoothstep(px * 10.0, px * 62.0, d) + 0.18 * sin(th * 1.7 + time * 0.3);
                half3 rb = (spectral(fract(hue)) + spectral(fract(hue + 0.16)) + spectral(fract(hue - 0.16))) / 3.0;
                rb = mix(rb, half3(1.0), half(0.3));
                rb = mix(rb, half3(tint), half(tintMix * 0.8));
                rgb += rb * half(rise * fadeOut * arc * 0.5 * outer);

                // star-dust: tiny specks lit by the filament, drawn in or pushed out as a field
                float sx = exp(-pull * 0.9);
                // stir: the dust streams away from the centre along the edge
                float2 fp = float2(p.x * sx, p.y - sign(p.y) * stirFlow);
                float cell = 3.0 * px;
                float2 q = fp / cell;
                float2 id = floor(q);
                float2 f = q - id;
                float r1 = hash(id);
                float r2 = hash(id + 17.13);
                float r3 = hash(id + 41.71);
                float r4 = hash(id + 73.37);
                float r5 = hash(id + 5.55);
                float2 c = float2(0.3 + 0.4 * r1, 0.3 + 0.4 * r2)
                    + 0.12 * float2(sin(time * (0.4 + r3) + r1 * 6.2832), cos(time * (0.3 + r4) + r2 * 6.2832));
                float2 dd = (f - c) * cell / float2(sx, 1.0);
                float rad = px * (0.22 + 0.32 * r3);
                float speck = exp(-dot(dd, dd) / (rad * rad));
                float fd = segDist(fp, halfLen);
                float dens = exp(-fd / (px * (11.0 + 8.0 * stir)));
                float alive = step(r4, dens * 0.8);
                float tw = 0.5 + 0.5 * sin(time * (1.2 + 2.6 * r1) + r2 * 6.2832);
                float lit = exp(-d / (px * 20.0)) * (0.4 + 0.6 * ends);
                // farther specks pick up a faint colour of their own, like starlight
                half3 sc = mix(half3(1.0), spectral(r5), half(smoothstep(px * 6.0, px * 28.0, d) * 0.55));
                sc = mix(sc, half3(tint), half(tintMix * 0.6));
                rgb += sc * half(speck * alive * mix(tw, 1.0, stir) * lit * (1.4 + 2.2 * stir) * outer);

                // soft window so nothing is ever cut off by the edge of the drawing box
                float win = (1.0 - smoothstep(ext * 0.6, ext, p.x))
                    * (1.0 - smoothstep(ext * 0.6, ext, abs(p.y) - halfLen));
                rgb *= half(power * win);
                rgb = half3(1.0) - exp(-rgb * 1.2);
                rgb *= half(alpha);
                float a = clamp(max(rgb.r, max(rgb.g, rgb.b)), 0.0, 1.0);
                float n = fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453) - 0.5;
                rgb = clamp(rgb + half(n / 255.0) * half(a), half3(0.0), half3(a));
                return half4(rgb, a);
            }
        """
    }
}
