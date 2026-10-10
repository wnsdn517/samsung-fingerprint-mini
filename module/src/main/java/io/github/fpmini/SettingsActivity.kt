package io.github.fpmini

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** Options screen. Changes are saved at once and picked up by the next prompt; no restart needed. */
class SettingsActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var list: LinearLayout
    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (p, active) = Settings.open(this)
        prefs = p
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * dp).toInt()
            setPadding(pad, pad, pad, pad)
        }
        // targetSdk 35 is edge-to-edge: drop the overlapping action bar and pad for the system bars
        actionBar?.hide()
        val pad = (20 * dp).toInt()
        list.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
            insets
        }
        setContentView(ScrollView(this).apply { addView(list) })

        list.addView(TextView(this).apply {
            text = "Fingerprint Mini"
            textSize = 26f
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
        })
        if (!active) text("LSPosed에서 모듈이 활성화되지 않았습니다. 설정이 적용되지 않습니다.", Color.rgb(0xD9, 0x30, 0x25))

        header("기기 형식")
        radio(Settings.SENSOR, listOf(
            "auto" to "자동 감지",
            "side" to "SideFPS (측면 전원 버튼)",
            "udfps" to "UDFPS (화면 내장)",
            "rear" to "후면",
        ))

        header("인증창 표시")
        radio(Settings.LAYOUT, listOf(
            "minimal" to "최소 (지문만)",
            "message" to "메시지 + 지문",
            "compact" to "간소화 (카드 유지, 장식 제거)",
            "off" to "끄기 (삼성 기본)",
        ))

        header("동작")
        switch(Settings.ANIM, "지문 애니메이션", "끄면 삼성 기본 표시")
        switch(Settings.TOUCH, "뒤의 앱 터치 허용", "켜면 인증창 버튼(취소 등)은 누를 수 없음")
        switch(Settings.HOLD, "결과 애니메이션 보여주기", "성공/실패 후 잠깐 유지")

        header("SideFPS 위치")
        slider(Settings.SIDE_Y, "센서 높이", 0, 60) { if (it == 0) "자동" else "화면 높이의 $it%" }
        slider(Settings.SIDE_LEN, "센서 길이", 0, 30) { if (it == 0) "자동" else "화면 높이의 $it%" }

        header("기타")
        slider(Settings.TIMEOUT, "시간 초과 표시", 5, 120) { "${it}초" }

        list.addView(Button(this).apply {
            text = "지문 인증 테스트"
            setOnClickListener { testPrompt() }
        })
        list.addView(Button(this).apply {
            text = "기본값으로 초기화"
            setOnClickListener { prefs.edit().clear().apply(); recreate() }
        })
    }

    private fun get(key: String) = prefs.getString(key, null) ?: Settings.DEFAULTS.getValue(key)
    private fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()

    private fun header(t: String) = list.addView(TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(Color.rgb(0x38, 0x7A, 0xF6))
        setPadding(0, (20 * dp).toInt(), 0, (6 * dp).toInt())
    })

    private fun text(t: String, color: Int) = list.addView(TextView(this).apply { text = t; setTextColor(color) })

    private fun radio(key: String, options: List<Pair<String, String>>) {
        val group = RadioGroup(this)
        options.forEachIndexed { i, (value, label) ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                tag = value
                isChecked = get(key) == value
            })
        }
        group.setOnCheckedChangeListener { g, id -> put(key, g.findViewById<View>(id).tag as String) }
        list.addView(group)
    }

    private fun switch(key: String, label: String, hint: String) = list.addView(Switch(this).apply {
        text = "$label\n$hint"
        isChecked = get(key) == "1"
        setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
        setOnCheckedChangeListener { _, on -> put(key, if (on) "1" else "0") }
    })

    private fun slider(key: String, label: String, min: Int, max: Int, fmt: (Int) -> String) {
        val title = TextView(this)
        val bar = SeekBar(this).apply {
            this.min = min
            this.max = max
            progress = get(key).toIntOrNull()?.coerceIn(min, max) ?: min
        }
        title.text = "$label: ${fmt(bar.progress)}"
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, v: Int, fromUser: Boolean) { title.text = "$label: ${fmt(v)}" }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) = put(key, s.progress.toString())
        })
        list.addView(title)
        list.addView(bar)
    }

    private fun testPrompt() {
        BiometricPrompt.Builder(this)
            .setTitle("Fingerprint Mini")
            .setDescription("테스트 인증")
            .setNegativeButton("취소", mainExecutor) { _, _ -> }
            .build()
            .authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {})
    }
}
