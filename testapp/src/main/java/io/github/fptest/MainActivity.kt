package io.github.fptest

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply { textSize = 18f; gravity = Gravity.CENTER }
        val button = Button(this).apply {
            text = "지문 인증"
            setOnClickListener { authenticate() }
        }
        var taps = 0
        val tapButton = Button(this).apply {
            text = "터치 테스트 (0)"
            setOnClickListener { text = "터치 테스트 (${++taps})" }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            addView(button, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(tapButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 48 })
            addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 48 })
        })
    }

    private fun authenticate() {
        if (BiometricManager.from(this).canAuthenticate(BIOMETRIC_STRONG) != BiometricManager.BIOMETRIC_SUCCESS) {
            status.text = "지문을 사용할 수 없습니다 (미등록/미지원)"
            return
        }
        val prompt = BiometricPrompt(
            this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    status.text = "인증 성공"
                }

                override fun onAuthenticationFailed() {
                    status.text = "인식 실패 (재시도)"
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    status.text = "오류 $errorCode: $errString"
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("지문 인증")
                .setSubtitle("테스트")
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                .setNegativeButtonText("취소")
                .build(),
        )
    }
}

