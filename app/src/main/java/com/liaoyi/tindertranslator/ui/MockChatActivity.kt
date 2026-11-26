package com.liaoyi.tindertranslator.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.liaoyi.tindertranslator.R
import com.liaoyi.tindertranslator.data.AppLanguage
import com.liaoyi.tindertranslator.data.AppStrings
import com.liaoyi.tindertranslator.data.SettingsStore
import com.liaoyi.tindertranslator.service.TinderAccessibilityService

class MockChatActivity : AppCompatActivity() {

    private lateinit var chatContainer: LinearLayout
    private lateinit var scrollChat: ScrollView
    private lateinit var etMessageInput: EditText

    private lateinit var sampleIncomingMessages: List<String>
    private var sampleIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mock_chat)

        setupEdgeToEdgeInsets()

        val settings = SettingsStore(this).loadSettings()
        val uiText = AppStrings.of(settings.language)

        chatContainer = findViewById(R.id.chatMessagesContainer)
        scrollChat = findViewById(R.id.scrollChat)
        etMessageInput = findViewById(R.id.etMessageInput)

        val btnBack = findViewById<MaterialButton>(R.id.btnBack)
        val tvTitle = findViewById<TextView>(R.id.tvMockChatTitle)
        val btnSimulate = findViewById<MaterialButton>(R.id.btnSimulateIncoming)
        val btnSend = findViewById<MaterialButton>(R.id.btnSendMock)

        btnBack.text = uiText.mockBack
        tvTitle.text = uiText.mockTitle
        btnSimulate.text = uiText.mockSimulateBtn
        btnSend.text = uiText.mockSendBtn

        sampleIncomingMessages = samplesFor(settings.language)

        btnBack.setOnClickListener { finish() }

        btnSimulate.setOnClickListener {
            val msg = sampleIncomingMessages[sampleIndex % sampleIncomingMessages.size]
            sampleIndex++
            addBubble(msg, isIncoming = true)
        }

        btnSend.setOnClickListener {
            val text = etMessageInput.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                addBubble(text, isIncoming = false)
                etMessageInput.setText("")
            }
        }

        addSystemNotice("You matched with Emma today")
        addBubble(sampleIncomingMessages[0], isIncoming = true)
        sampleIndex = 1
    }

    private fun setupEdgeToEdgeInsets() {
        val root = findViewById<View>(R.id.mockChatRoot)
        val header = findViewById<View>(R.id.mockHeaderBar)
        val composer = findViewById<View>(R.id.mockComposerBar)
        val headerPadH = dp(10)
        val headerPadV = dp(6)
        val composerPadH = dp(10)
        val composerPadV = dp(8)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val topBars = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val bottomBars = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.ime()
            )
            header.setPadding(
                headerPadH + topBars.left,
                headerPadV + topBars.top,
                headerPadH + topBars.right,
                headerPadV
            )
            composer.setPadding(
                composerPadH + bottomBars.left,
                composerPadV,
                composerPadH + bottomBars.right,
                composerPadV + bottomBars.bottom
            )
            insets
        }
    }

    private fun samplesFor(language: AppLanguage): List<String> {
        return when (language) {
            AppLanguage.TH -> listOf(
                "你好呀！看你照片很喜欢旅行，周末有空一起喝杯咖啡吗？",
                "Hey! Your dog in that second pic is literally stealing the show haha",
                "最近工作有点忙，但周五晚上有空，你一般喜欢吃什么？",
                "Wait are u down to grab drinks or coffee sometime this weekend?"
            )
            AppLanguage.EN -> listOf(
                "สวัสดีค่ะ ชอบรูปที่ไปเที่ยวมากเลย สุดสัปดาห์นี้ว่างไปกินกาแฟไหมคะ?",
                "你好呀！你第二张照片里的小狗太可爱了吧哈哈",
                "Oye, me encanta tu estilo! ¿Vives cerca del centro?",
                "今週末ひま？おいしいラーメン屋さん見つけたんだけど！"
            )
            else -> listOf(
                "Hey! Your dog in that second pic is literally stealing the show haha",
                "สวัสดีค่ะ วันศุกร์นี้ว่างไปหาอะไรดื่มด้วยกันไหมคะ?",
                "Wait are u down to grab drinks or coffee sometime this weekend?",
                "Lowkey tired of small talk tbh, what's the most spontaneous thing you've done lately?",
                "Oye, me encanta tu estilo! ¿Vives cerca del centro?",
                "今週末ひま？おいしいラーメン屋さん見つけたんだけど！"
            )
        }
    }

    override fun onStart() {
        super.onStart()
        isForeground = true
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        TinderAccessibilityService.notifyMockChatUpdated()
    }

    override fun onPause() {
        isForeground = false
        TinderAccessibilityService.notifyMockChatUpdated()
        super.onPause()
    }

    private fun addSystemNotice(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#8A909E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(10)
            }
        }
        chatContainer.addView(tv)
    }

    private fun addBubble(text: String, isIncoming: Boolean) {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (isIncoming) Gravity.START else Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
                if (isIncoming) {
                    marginEnd = dp(52)
                } else {
                    marginStart = dp(52)
                }
            }
        }

        val bubble = TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            setTextColor(if (isIncoming) Color.parseColor("#181A20") else Color.WHITE)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(
                    if (isIncoming) Color.parseColor("#EAECEF") else Color.parseColor("#2B86F5")
                )
            }
        }

        wrapper.addView(bubble)
        chatContainer.addView(wrapper)
        scrollChat.post {
            scrollChat.fullScroll(View.FOCUS_DOWN)
            TinderAccessibilityService.notifyMockChatUpdated()
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    companion object {
        @Volatile
        var isForeground: Boolean = false
            private set
    }
}
