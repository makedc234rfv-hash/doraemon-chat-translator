package com.liaoyi.tindertranslator.ui

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.liaoyi.tindertranslator.R
import com.liaoyi.tindertranslator.data.AiProvider
import com.liaoyi.tindertranslator.data.AppLanguage
import com.liaoyi.tindertranslator.data.AppStrings
import com.liaoyi.tindertranslator.data.ReplyGender
import com.liaoyi.tindertranslator.data.ReplyLanguage
import com.liaoyi.tindertranslator.data.SettingsStore
import com.liaoyi.tindertranslator.data.TranslatorSettings
import com.liaoyi.tindertranslator.service.TinderAccessibilityService
import com.liaoyi.tindertranslator.translator.AiTranslator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var settingsStore: SettingsStore
    private val aiTranslator = AiTranslator()
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var tvAppTitle: TextView
    private lateinit var tvAppSubtitle: TextView
    private lateinit var tvLangLabel: TextView
    private lateinit var chipGroupLanguage: ChipGroup
    private lateinit var tvReplySettingsLabel: TextView
    private lateinit var chipGroupReplyLang: ChipGroup
    private lateinit var chipReplyAuto: Chip
    private lateinit var chipGroupGender: ChipGroup
    private lateinit var chipGenderMale: Chip
    private lateinit var chipGenderFemale: Chip
    private lateinit var chipGenderNeutral: Chip

    private lateinit var tvStep1Title: TextView
    private lateinit var tvServiceBadge: TextView
    private lateinit var tvServiceHint: TextView
    private lateinit var btnOpenAccessibility: MaterialButton
    private lateinit var btnOpenAppInfo: MaterialButton

    private lateinit var tvStep2Title: TextView
    private lateinit var chipGroupProvider: ChipGroup
    private lateinit var chipCustom: Chip
    private lateinit var tilApiKey: TextInputLayout
    private lateinit var etApiKey: TextInputEditText
    private lateinit var tilCustomUrl: TextInputLayout
    private lateinit var etCustomUrl: TextInputEditText
    private lateinit var tilModel: TextInputLayout
    private lateinit var etModel: TextInputEditText
    private lateinit var switchAutoPopup: MaterialSwitch
    private lateinit var switchExplainSlang: MaterialSwitch
    private lateinit var switchShowMine: MaterialSwitch
    private lateinit var btnSaveAndTest: MaterialButton
    private lateinit var btnSaveOnly: MaterialButton
    private lateinit var tvTestResult: TextView

    private lateinit var tvStep3Title: TextView
    private lateinit var tvStep3Desc: TextView
    private lateinit var btnOpenMockChat: MaterialButton
    private lateinit var tvSafetyTitle: TextView
    private lateinit var tvSafetyBody: TextView

    private var selectedLanguage: AppLanguage = AppLanguage.ZH
    private var selectedReplyLanguage: ReplyLanguage = ReplyLanguage.AUTO
    private var selectedGender: ReplyGender = ReplyGender.MALE
    private var selectedProvider: AiProvider = AiProvider.GEMINI
    private var suppressChipListener = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupResponsiveInsets()

        settingsStore = SettingsStore(this)
        bindViews()
        populateFromSettings()
        setupListeners()

        TinderAccessibilityService.onStatusChanged = {
            runOnUiThread { updateServiceStatus() }
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }

    override fun onDestroy() {
        TinderAccessibilityService.onStatusChanged = null
        uiScope.cancel()
        super.onDestroy()
    }

    private fun setupResponsiveInsets() {
        val scrollRoot = findViewById<ScrollView>(R.id.mainScrollRoot)
        val content = findViewById<LinearLayout>(R.id.mainContentContainer)
        val dm = resources.displayMetrics
        val widthDp = dm.widthPixels / dm.density
        val padH = ((if (widthDp < 370f) 10f else 14f) * dm.density).toInt()
        val padTopBase = (10f * dm.density).toInt()
        val padBottomBase = (16f * dm.density).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(scrollRoot) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            content.setPadding(
                padH + bars.left,
                padTopBase + bars.top,
                padH + bars.right,
                padBottomBase + bars.bottom
            )
            insets
        }
    }

    private fun bindViews() {
        tvAppTitle = findViewById(R.id.tvAppTitle)
        tvAppSubtitle = findViewById(R.id.tvAppSubtitle)
        tvLangLabel = findViewById(R.id.tvLangLabel)
        chipGroupLanguage = findViewById(R.id.chipGroupLanguage)
        tvReplySettingsLabel = findViewById(R.id.tvReplySettingsLabel)
        chipGroupReplyLang = findViewById(R.id.chipGroupReplyLang)
        chipReplyAuto = findViewById(R.id.chipReplyAuto)
        chipGroupGender = findViewById(R.id.chipGroupGender)
        chipGenderMale = findViewById(R.id.chipGenderMale)
        chipGenderFemale = findViewById(R.id.chipGenderFemale)
        chipGenderNeutral = findViewById(R.id.chipGenderNeutral)

        tvStep1Title = findViewById(R.id.tvStep1Title)
        tvServiceBadge = findViewById(R.id.tvServiceBadge)
        tvServiceHint = findViewById(R.id.tvServiceHint)
        btnOpenAccessibility = findViewById(R.id.btnOpenAccessibility)
        btnOpenAppInfo = findViewById(R.id.btnOpenAppInfo)

        tvStep2Title = findViewById(R.id.tvStep2Title)
        chipGroupProvider = findViewById(R.id.chipGroupProvider)
        chipCustom = findViewById(R.id.chipCustom)
        tilApiKey = findViewById(R.id.tilApiKey)
        etApiKey = findViewById(R.id.etApiKey)
        tilCustomUrl = findViewById(R.id.tilCustomUrl)
        etCustomUrl = findViewById(R.id.etCustomUrl)
        tilModel = findViewById(R.id.tilModel)
        etModel = findViewById(R.id.etModel)
        switchAutoPopup = findViewById(R.id.switchAutoPopup)
        switchExplainSlang = findViewById(R.id.switchExplainSlang)
        switchShowMine = findViewById(R.id.switchShowMine)
        btnSaveAndTest = findViewById(R.id.btnSaveAndTest)
        btnSaveOnly = findViewById(R.id.btnSaveOnly)
        tvTestResult = findViewById(R.id.tvTestResult)

        tvStep3Title = findViewById(R.id.tvStep3Title)
        tvStep3Desc = findViewById(R.id.tvStep3Desc)
        btnOpenMockChat = findViewById(R.id.btnOpenMockChat)
        tvSafetyTitle = findViewById(R.id.tvSafetyTitle)
        tvSafetyBody = findViewById(R.id.tvSafetyBody)
    }

    private fun populateFromSettings() {
        val s = settingsStore.loadSettings()
        selectedLanguage = s.language
        selectedReplyLanguage = s.replyLanguage
        selectedGender = s.replyGender
        selectedProvider = s.provider

        suppressChipListener = true
        chipGroupLanguage.check(
            when (s.language) {
                AppLanguage.ZH -> R.id.chipLangZh
                AppLanguage.TH -> R.id.chipLangTh
                AppLanguage.EN -> R.id.chipLangEn
                AppLanguage.JA -> R.id.chipLangJa
                AppLanguage.KO -> R.id.chipLangKo
            }
        )
        chipGroupReplyLang.check(
            when (s.replyLanguage) {
                ReplyLanguage.AUTO -> R.id.chipReplyAuto
                ReplyLanguage.TH -> R.id.chipReplyTh
                ReplyLanguage.EN -> R.id.chipReplyEn
                ReplyLanguage.ZH -> R.id.chipReplyZh
                ReplyLanguage.JA -> R.id.chipReplyJa
                ReplyLanguage.KO -> R.id.chipReplyKo
            }
        )
        chipGroupGender.check(
            when (s.replyGender) {
                ReplyGender.MALE -> R.id.chipGenderMale
                ReplyGender.FEMALE -> R.id.chipGenderFemale
                ReplyGender.NEUTRAL -> R.id.chipGenderNeutral
            }
        )
        chipGroupProvider.check(
            when (s.provider) {
                AiProvider.GEMINI -> R.id.chipGemini
                AiProvider.DEEPSEEK -> R.id.chipDeepSeek
                AiProvider.OPENAI -> R.id.chipOpenAi
                AiProvider.CUSTOM -> R.id.chipCustom
            }
        )
        suppressChipListener = false

        etApiKey.setText(s.apiKey)
        etModel.setText(s.model)
        etCustomUrl.setText(s.customBaseUrl)
        switchAutoPopup.isChecked = s.autoPopup
        switchExplainSlang.isChecked = s.explainSlang
        switchShowMine.isChecked = s.showMyMessages

        applyLanguageUi()
        applyProviderUi(s.provider, resetModel = false)
    }

    private fun setupListeners() {
        chipGroupLanguage.setOnCheckedStateChangeListener { _, checkedIds ->
            if (suppressChipListener) return@setOnCheckedStateChangeListener
            val checked = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            selectedLanguage = when (checked) {
                R.id.chipLangZh -> AppLanguage.ZH
                R.id.chipLangTh -> AppLanguage.TH
                R.id.chipLangEn -> AppLanguage.EN
                R.id.chipLangJa -> AppLanguage.JA
                R.id.chipLangKo -> AppLanguage.KO
                else -> AppLanguage.ZH
            }
            saveCurrentForm()
            applyLanguageUi()
            updateServiceStatus()
        }

        chipGroupReplyLang.setOnCheckedStateChangeListener { _, checkedIds ->
            if (suppressChipListener) return@setOnCheckedStateChangeListener
            val checked = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            selectedReplyLanguage = when (checked) {
                R.id.chipReplyTh -> ReplyLanguage.TH
                R.id.chipReplyEn -> ReplyLanguage.EN
                R.id.chipReplyZh -> ReplyLanguage.ZH
                R.id.chipReplyJa -> ReplyLanguage.JA
                R.id.chipReplyKo -> ReplyLanguage.KO
                else -> ReplyLanguage.AUTO
            }
            saveCurrentForm()
        }

        chipGroupGender.setOnCheckedStateChangeListener { _, checkedIds ->
            if (suppressChipListener) return@setOnCheckedStateChangeListener
            val checked = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            selectedGender = when (checked) {
                R.id.chipGenderFemale -> ReplyGender.FEMALE
                R.id.chipGenderNeutral -> ReplyGender.NEUTRAL
                else -> ReplyGender.MALE
            }
            saveCurrentForm()
        }

        btnOpenAccessibility.setOnClickListener {
            runCatching {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }.onFailure {
                Toast.makeText(this, AppStrings.of(selectedLanguage).accessibilityToast, Toast.LENGTH_LONG).show()
            }
        }

        btnOpenAppInfo.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }

        chipGroupProvider.setOnCheckedStateChangeListener { _, checkedIds ->
            if (suppressChipListener) return@setOnCheckedStateChangeListener
            val checked = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val provider = when (checked) {
                R.id.chipGemini -> AiProvider.GEMINI
                R.id.chipDeepSeek -> AiProvider.DEEPSEEK
                R.id.chipOpenAi -> AiProvider.OPENAI
                R.id.chipCustom -> AiProvider.CUSTOM
                else -> AiProvider.GEMINI
            }
            selectedProvider = provider
            applyProviderUi(provider, resetModel = true)
        }

        btnSaveOnly.setOnClickListener {
            saveCurrentForm()
            Toast.makeText(this, AppStrings.of(selectedLanguage).toastSaved, Toast.LENGTH_SHORT).show()
        }

        btnSaveAndTest.setOnClickListener {
            val settings = saveCurrentForm()
            val t = AppStrings.of(selectedLanguage)
            if (!settings.hasApiKey()) {
                tvTestResult.visibility = View.VISIBLE
                tvTestResult.text = t.testNeedKey
                return@setOnClickListener
            }

            btnSaveAndTest.isEnabled = false
            btnSaveAndTest.text = t.btnTesting
            tvTestResult.visibility = View.VISIBLE
            tvTestResult.text = t.testSending(settings.provider.displayName)

            uiScope.launch {
                val res = aiTranslator.testConnection(settings)
                val latest = AppStrings.of(selectedLanguage)
                btnSaveAndTest.isEnabled = true
                btnSaveAndTest.text = latest.btnSaveAndTest
                res.fold(
                    onSuccess = { tr ->
                        tvTestResult.text = latest.testSuccess(tr.original, tr.translated, tr.note)
                    },
                    onFailure = { err ->
                        tvTestResult.text = latest.testFailed(err.message ?: latest.errBadFormat)
                    }
                )
            }
        }

        btnOpenMockChat.setOnClickListener {
            saveCurrentForm()
            startActivity(Intent(this, MockChatActivity::class.java))
        }
    }

    private fun applyLanguageUi() {
        val t = AppStrings.of(selectedLanguage)
        tvAppTitle.text = t.appTitle
        tvAppSubtitle.text = t.appSubtitle
        tvLangLabel.text = t.langLabel
        tvReplySettingsLabel.text = t.replySettingsLabel
        chipReplyAuto.text = t.replyLangAuto
        chipGenderMale.text = t.genderMale
        chipGenderFemale.text = t.genderFemale
        chipGenderNeutral.text = t.genderNeutral
        tvStep1Title.text = t.step1Title
        tvServiceHint.text = t.step1Hint
        btnOpenAccessibility.text = t.btnOpenAccessibility
        btnOpenAppInfo.text = t.btnOpenAppInfo

        tvStep2Title.text = t.step2Title
        chipCustom.text = t.providerCustom
        tilApiKey.hint = t.keyHintFor(selectedProvider)
        tilCustomUrl.hint = t.hintCustomUrl
        tilModel.hint = t.hintModel
        switchAutoPopup.text = t.switchAutoPopup
        switchExplainSlang.text = t.switchExplainSlang
        switchShowMine.text = t.switchShowMine
        btnSaveAndTest.text = t.btnSaveAndTest
        btnSaveOnly.text = t.btnSaveOnly

        tvStep3Title.text = t.step3Title
        tvStep3Desc.text = t.step3Desc
        btnOpenMockChat.text = t.btnOpenMockChat
        tvSafetyTitle.text = t.safetyTitle
        tvSafetyBody.text = t.safetyBody
    }

    private fun applyProviderUi(provider: AiProvider, resetModel: Boolean) {
        tilApiKey.hint = AppStrings.of(selectedLanguage).keyHintFor(provider)
        tilCustomUrl.visibility = if (provider == AiProvider.CUSTOM) View.VISIBLE else View.GONE
        if (resetModel) {
            etModel.setText(provider.defaultModel)
        }
    }

    private fun saveCurrentForm(): TranslatorSettings {
        val existing = settingsStore.loadSettings()
        val settings = TranslatorSettings(
            language = selectedLanguage,
            provider = selectedProvider,
            apiKey = etApiKey.text?.toString().orEmpty().trim(),
            model = etModel.text?.toString().orEmpty().trim().ifEmpty { selectedProvider.defaultModel },
            customBaseUrl = etCustomUrl.text?.toString().orEmpty().trim()
                .ifEmpty { AiProvider.CUSTOM.defaultBaseUrl },
            autoPopup = switchAutoPopup.isChecked,
            explainSlang = switchExplainSlang.isChecked,
            showMyMessages = switchShowMine.isChecked,
            overlaySize = existing.overlaySize,
            replyLanguage = selectedReplyLanguage,
            replyGender = selectedGender,
            maxMessages = existing.maxMessages
        )
        settingsStore.saveSettings(settings)
        return settings
    }

    private fun updateServiceStatus() {
        val running = TinderAccessibilityService.isRunning
        val enabledInSettings = isAccessibilityEnabledInSettings()
        val t = AppStrings.of(selectedLanguage)

        val bgColorRes = if (running) R.color.status_ok_bg else R.color.status_warn_bg
        val textColorRes = if (running) R.color.status_ok_text else R.color.status_warn_text

        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 30f
            setColor(ContextCompat.getColor(this@MainActivity, bgColorRes))
        }
        tvServiceBadge.background = bg
        tvServiceBadge.setTextColor(ContextCompat.getColor(this, textColorRes))

        when {
            running -> {
                tvServiceBadge.text = t.statusOn
                tvServiceHint.text = t.step1Hint
                tvServiceHint.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            }
            enabledInSettings -> {
                tvServiceBadge.text = t.statusStale
                tvServiceHint.text = t.step1StaleHint
                tvServiceHint.setTextColor(ContextCompat.getColor(this, R.color.status_warn_text))
            }
            else -> {
                tvServiceBadge.text = t.statusOff
                tvServiceHint.text = t.step1Hint
                tvServiceHint.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            }
        }
    }

    private fun isAccessibilityEnabledInSettings(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val expected = "$packageName/${TinderAccessibilityService::class.java.name}"
        val shortExpected = "$packageName/.service.TinderAccessibilityService"
        return enabledServices.contains(expected) || enabledServices.contains(shortExpected)
    }
}
