package com.liaoyi.tindertranslator.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import com.liaoyi.tindertranslator.data.AppLanguage
import com.liaoyi.tindertranslator.data.AppStrings
import com.liaoyi.tindertranslator.data.SettingsStore
import com.liaoyi.tindertranslator.data.TranslationResult
import com.liaoyi.tindertranslator.overlay.DisplayItem
import com.liaoyi.tindertranslator.overlay.FloatingTranslationController
import com.liaoyi.tindertranslator.overlay.PolishUiState
import com.liaoyi.tindertranslator.parser.AutoTriggerTracker
import com.liaoyi.tindertranslator.parser.ChatMessage
import com.liaoyi.tindertranslator.parser.ChatMessageParser
import com.liaoyi.tindertranslator.parser.ScreenNode
import com.liaoyi.tindertranslator.translator.AiTranslator
import com.liaoyi.tindertranslator.ui.MainActivity
import com.liaoyi.tindertranslator.ui.MockChatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TinderAccessibilityService : AccessibilityService() {

    private lateinit var settingsStore: SettingsStore
    private lateinit var overlayController: FloatingTranslationController
    private val aiTranslator = AiTranslator()
    private val triggerTracker = AutoTriggerTracker()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var debounceJob: Job? = null
    private var translateJob: Job? = null
    private var polishJob: Job? = null

    private var activeTargetPackage: String? = null
    private var lastLanguage: AppLanguage? = null
    private var lastDisplayMessages: List<ChatMessage> = emptyList()
    private var lastContextMessages: List<ChatMessage> = emptyList()
    private var polishState: PolishUiState = PolishUiState()
    private val inFlightIds = mutableSetOf<String>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        settingsStore = SettingsStore(this)
        overlayController = FloatingTranslationController(
            context = this,
            onToggleAutoPopup = ::handleToggleAutoPopup,
            onCycleSizeClick = ::handleCycleSizeClick,
            onRefreshClick = { triggerScan(forceExpand = true, ignoreCache = true) },
            onCollapseClick = ::handleCollapseClick,
            onExpandClick = ::handleExpandClick,
            onOpenSettingsClick = ::openAppSettings,
            onPolishReplyClick = ::handlePolishReplyClick,
            onCycleReplyLangClick = ::handleCycleReplyLangClick,
            onClosePolishClick = ::handleClosePolishClick,
            onFillReplyClick = ::handleFillReplyClick,
            onCopyReplyClick = ::handleCopyReplyClick
        )
        isRunning = true
        instance = this
        onStatusChanged?.invoke(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val eventPkg = event.packageName?.toString().orEmpty()

        if (eventPkg in IGNORED_PACKAGES && event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }

        val currentTargetPkg = detectActiveTargetPackage(eventPkg)
        if (currentTargetPkg == null) {
            if (activeTargetPackage != null) {
                leaveTargetApp()
            }
            return
        }

        if (activeTargetPackage != currentTargetPkg) {
            activeTargetPackage = currentTargetPkg
            triggerTracker.resetSession()
        }

        scheduleDebouncedScan()
    }

    override fun onInterrupt() {
        debounceJob?.cancel()
        translateJob?.cancel()
        polishJob?.cancel()
        if (::overlayController.isInitialized) {
            overlayController.hideAll()
        }
    }

    override fun onDestroy() {
        isRunning = false
        instance = null
        onStatusChanged?.invoke(false)
        debounceJob?.cancel()
        translateJob?.cancel()
        polishJob?.cancel()
        if (::overlayController.isInitialized) {
            overlayController.hideAll()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun detectActiveTargetPackage(fallbackEventPkg: String): String? {
        val interactiveWindows = runCatching { windows }.getOrNull().orEmpty()
        var foundOtherApp = false

        for (win in interactiveWindows) {
            if (win.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = runCatching { win.root }.getOrNull() ?: continue
            val pkg = root.packageName?.toString().orEmpty()
            if (isTargetPackage(pkg)) {
                return pkg
            }
            if (pkg.isNotEmpty() && pkg !in IGNORED_PACKAGES) {
                foundOtherApp = true
            }
        }

        if (foundOtherApp) return null

        val activePkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull().orEmpty()
        if (isTargetPackage(activePkg)) return activePkg
        if (activePkg.isNotEmpty() && activePkg !in IGNORED_PACKAGES) return null

        if (isTargetPackage(fallbackEventPkg)) return fallbackEventPkg
        if (fallbackEventPkg.isNotEmpty() && fallbackEventPkg !in IGNORED_PACKAGES) return null

        return activeTargetPackage
    }

    private fun isTargetPackage(pkg: String): Boolean {
        if (pkg == TINDER_PACKAGE) return true
        if (pkg == packageName && MockChatActivity.isForeground) return true
        return false
    }

    private fun leaveTargetApp() {
        activeTargetPackage = null
        debounceJob?.cancel()
        translateJob?.cancel()
        polishJob?.cancel()
        inFlightIds.clear()
        lastDisplayMessages = emptyList()
        lastContextMessages = emptyList()
        polishState = PolishUiState()
        triggerTracker.resetSession()
        overlayController.hideAll()
    }

    private fun scheduleDebouncedScan() {
        debounceJob?.cancel()
        debounceJob = serviceScope.launch {
            delay(SCAN_DEBOUNCE_MS)
            triggerScan(forceExpand = false, ignoreCache = false)
        }
    }

    private fun triggerScan(forceExpand: Boolean, ignoreCache: Boolean) {
        val targetPkg = activeTargetPackage ?: return
        val targetRoot = findTargetRootNode(targetPkg) ?: return

        val dm = resources.displayMetrics
        val screenWidth = dm.widthPixels
        val screenHeight = dm.heightPixels
        val nodes = collectScreenNodes(targetRoot)

        if (!ChatMessageParser.isChatScreen(nodes, screenWidth, screenHeight)) {
            overlayController.hideAll()
            return
        }

        val settings = settingsStore.loadSettings()
        if (lastLanguage != settings.language) {
            lastLanguage = settings.language
            triggerTracker.resetSession()
        }

        val allMessages = ChatMessageParser.extractMessages(
            nodes = nodes,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            maxMessages = (settings.maxMessages + 4).coerceAtMost(12)
        )

        val decision = triggerTracker.evaluate(
            allMessages = allMessages,
            showMyMessages = settings.showMyMessages,
            autoPopupEnabled = settings.autoPopup,
            maxDisplayCount = settings.maxMessages,
            targetLanguage = settings.language
        )

        lastContextMessages = allMessages
        lastDisplayMessages = decision.displayMessages

        if (decision.displayMessages.isEmpty()) {
            if (forceExpand || polishState.visible) {
                renderCurrentCard(isLoading = false)
            } else {
                overlayController.showCollapsedPill(settings.language, hasUnread = false)
            }
            return
        }

        val shouldShowExpanded = forceExpand || polishState.visible || decision.shouldAutoPopup || !triggerTracker.isCollapsedByUser

        val missingIds = decision.displayMessages
            .filter { msg ->
                ignoreCache || settingsStore.getCachedTranslation(
                    msg.text,
                    settings.language,
                    settings.explainSlang
                ) == null
            }
            .map { it.id }
            .toSet()

        if (!shouldShowExpanded) {
            overlayController.showCollapsedPill(settings.language, hasUnread = decision.newIncomingCount > 0)
            return
        }

        if (missingIds.isEmpty()) {
            renderCurrentCard(isLoading = false)
            return
        }

        val uiStrings = AppStrings.of(settings.language)
        if (!settings.hasApiKey()) {
            renderCurrentCard(
                isLoading = false,
                errorMessage = uiStrings.overlayMissingKey,
                showSettingsAction = true
            )
            return
        }

        if (ignoreCache) {
            inFlightIds.clear()
        }
        val toRequest = if (ignoreCache) missingIds else (missingIds - inFlightIds)
        if (toRequest.isEmpty()) {
            renderCurrentCard(isLoading = true)
            return
        }

        inFlightIds.addAll(toRequest)
        renderCurrentCard(isLoading = true)

        translateJob?.cancel()
        translateJob = serviceScope.launch {
            val result = aiTranslator.translateBatch(
                contextMessages = allMessages,
                targetIds = toRequest,
                settings = settings
            )
            inFlightIds.removeAll(toRequest)

            if (activeTargetPackage == null) return@launch

            result.fold(
                onSuccess = { translatedMap ->
                    for ((_, tr) in translatedMap) {
                        settingsStore.putCachedTranslation(tr, settings.language, settings.explainSlang)
                    }
                    if (!triggerTracker.isCollapsedByUser || overlayController.mode == FloatingTranslationController.Mode.EXPANDED) {
                        renderCurrentCard(isLoading = false)
                    } else {
                        overlayController.showCollapsedPill(settings.language, hasUnread = true)
                    }
                },
                onFailure = { error ->
                    val needsKey = !settings.hasApiKey() ||
                        (error is AiTranslator.ApiException && (error.httpCode == 401 || error.httpCode == 403))
                    renderCurrentCard(
                        isLoading = false,
                        errorMessage = error.message ?: uiStrings.errBadFormat,
                        showSettingsAction = needsKey
                    )
                }
            )
        }
    }

    private fun handlePolishReplyClick() {
        val targetPkg = activeTargetPackage ?: return
        val settings = settingsStore.loadSettings()
        val uiStrings = AppStrings.of(settings.language)
        triggerTracker.markExpandedByUser()

        if (!settings.hasApiKey()) {
            polishState = PolishUiState(visible = true, message = uiStrings.overlayMissingKey)
            renderCurrentCard(isLoading = false, errorMessage = uiStrings.overlayMissingKey, showSettingsAction = true)
            return
        }

        val targetRoot = findTargetRootNode(targetPkg)
        val screenHeight = resources.displayMetrics.heightPixels
        val composer = targetRoot?.let { findComposerNode(it, screenHeight) }
        val draft = composer?.let { readComposerDraftText(it) }.orEmpty()

        if (draft.isEmpty()) {
            polishState = PolishUiState(visible = true, message = uiStrings.polishEmptyInput)
            renderCurrentCard(isLoading = inFlightIds.isNotEmpty())
            return
        }

        polishState = PolishUiState(visible = true, isLoading = true)
        renderCurrentCard(isLoading = inFlightIds.isNotEmpty())

        polishJob?.cancel()
        polishJob = serviceScope.launch {
            val result = aiTranslator.polishReply(
                draftText = draft,
                contextMessages = lastContextMessages,
                settings = settings
            )
            if (activeTargetPackage == null) return@launch

            result.fold(
                onSuccess = { polished ->
                    polishState = PolishUiState(visible = true, isLoading = false, result = polished)
                    renderCurrentCard(isLoading = inFlightIds.isNotEmpty())
                },
                onFailure = { err ->
                    polishState = PolishUiState(
                        visible = true,
                        isLoading = false,
                        message = err.message ?: uiStrings.errBadFormat
                    )
                    renderCurrentCard(isLoading = inFlightIds.isNotEmpty())
                }
            )
        }
    }

    private fun handleCycleReplyLangClick() {
        val settings = settingsStore.loadSettings()
        settingsStore.setReplyLanguage(settings.replyLanguage.next())
        renderCurrentCard(isLoading = inFlightIds.isNotEmpty())
    }

    private fun handleClosePolishClick() {
        polishJob?.cancel()
        polishState = PolishUiState(visible = false)
        renderCurrentCard(isLoading = inFlightIds.isNotEmpty())
    }

    private fun handleFillReplyClick(text: String) {
        val settings = settingsStore.loadSettings()
        val uiStrings = AppStrings.of(settings.language)
        val targetPkg = activeTargetPackage
        val targetRoot = targetPkg?.let { findTargetRootNode(it) }
        val composer = targetRoot?.let { findComposerNode(it, resources.displayMetrics.heightPixels) }

        var filled = false
        if (composer != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            filled = runCatching {
                composer.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }.getOrDefault(false)
        }

        if (filled) {
            polishState = PolishUiState(visible = false)
            triggerTracker.markCollapsedByUser()
            overlayController.showCollapsedPill(settings.language, hasUnread = false)
            Toast.makeText(this, uiStrings.polishFilledToast, Toast.LENGTH_SHORT).show()
        } else {
            copyToClipboard(text)
            Toast.makeText(this, uiStrings.polishCopiedToast, Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleCopyReplyClick(text: String) {
        val settings = settingsStore.loadSettings()
        val uiStrings = AppStrings.of(settings.language)
        copyToClipboard(text)
        Toast.makeText(this, uiStrings.polishCopiedToast, Toast.LENGTH_SHORT).show()
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("DoraemonReply", text))
    }

    private fun renderCurrentCard(
        isLoading: Boolean,
        errorMessage: String? = null,
        showSettingsAction: Boolean = false
    ) {
        val settings = settingsStore.loadSettings()
        val displayItems = lastDisplayMessages.map { msg ->
            val cached: TranslationResult? = settingsStore.getCachedTranslation(
                msg.text,
                settings.language,
                settings.explainSlang
            )
            DisplayItem(
                message = msg,
                translation = cached,
                isTranslating = isLoading && cached == null
            )
        }
        overlayController.showExpanded(
            items = displayItems,
            language = settings.language,
            replyLanguage = settings.replyLanguage,
            overlaySize = settings.overlaySize,
            autoPopup = settings.autoPopup,
            isLoading = isLoading,
            errorMessage = errorMessage,
            showSettingsAction = showSettingsAction,
            polishState = polishState
        )
    }

    private fun handleToggleAutoPopup() {
        val settings = settingsStore.loadSettings()
        settingsStore.setAutoPopup(!settings.autoPopup)
        renderCurrentCard(isLoading = inFlightIds.isNotEmpty())
    }

    private fun handleCycleSizeClick() {
        val settings = settingsStore.loadSettings()
        settingsStore.setOverlaySize(settings.overlaySize.next())
        renderCurrentCard(isLoading = inFlightIds.isNotEmpty())
    }

    private fun handleCollapseClick() {
        val settings = settingsStore.loadSettings()
        polishState = PolishUiState(visible = false)
        triggerTracker.markCollapsedByUser()
        overlayController.showCollapsedPill(settings.language, hasUnread = false)
    }

    private fun handleExpandClick() {
        triggerTracker.markExpandedByUser()
        triggerScan(forceExpand = true, ignoreCache = false)
    }

    private fun openAppSettings() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    private fun findTargetRootNode(targetPkg: String): AccessibilityNodeInfo? {
        val interactiveWindows = runCatching { windows }.getOrNull().orEmpty()
        for (win in interactiveWindows) {
            if (win.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = runCatching { win.root }.getOrNull() ?: continue
            if (root.packageName?.toString() == targetPkg) {
                return root
            }
        }
        val active = runCatching { rootInActiveWindow }.getOrNull()
        return if (active?.packageName?.toString() == targetPkg) active else null
    }

    private fun findComposerNode(root: AccessibilityNodeInfo, screenHeight: Int): AccessibilityNodeInfo? {
        val minTop = (screenHeight * 0.22f).toInt()
        val rect = Rect()
        var bestNode: AccessibilityNodeInfo? = null
        var bestTop = -1

        fun search(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 30) return
            if (!node.isVisibleToUser) return

            val cls = node.className?.toString().orEmpty()
            if (node.isEditable || cls.endsWith("EditText")) {
                node.getBoundsInScreen(rect)
                if (rect.top >= minTop && rect.height() > 0 && rect.top >= bestTop) {
                    bestNode = node
                    bestTop = rect.top
                }
            }

            val count = node.childCount
            for (i in 0 until count) {
                val child = runCatching { node.getChild(i) }.getOrNull()
                if (child != null) search(child, depth + 1)
            }
        }

        search(root, 0)
        return bestNode
    }

    private fun readComposerDraftText(node: AccessibilityNodeInfo): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText) {
            return ""
        }
        val text = node.text?.toString().orEmpty().trim()
        val lower = text.lowercase()
        if (lower in PLACEHOLDER_STRINGS) return ""
        return text
    }

    private fun collectScreenNodes(root: AccessibilityNodeInfo): List<ScreenNode> {
        val result = ArrayList<ScreenNode>(64)
        val rect = Rect()

        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 32) return
            if (!node.isVisibleToUser) return

            node.getBoundsInScreen(rect)
            val text = node.text?.toString().orEmpty()
            val cls = node.className?.toString().orEmpty()
            val fallbackText = when {
                text.isNotEmpty() -> text
                node.isEditable -> node.hintText?.toString().orEmpty()
                cls.endsWith("TextView") -> node.contentDescription?.toString().orEmpty()
                else -> ""
            }

            if (fallbackText.isNotEmpty() || node.isEditable) {
                result.add(
                    ScreenNode(
                        text = fallbackText,
                        className = cls,
                        viewId = node.viewIdResourceName.orEmpty(),
                        left = rect.left,
                        top = rect.top,
                        right = rect.right,
                        bottom = rect.bottom,
                        isEditable = node.isEditable,
                        isClickable = node.isClickable
                    )
                )
            }

            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = runCatching { node.getChild(i) }.getOrNull()
                if (child != null) {
                    visit(child, depth + 1)
                }
            }
        }

        visit(root, 0)
        return result
    }

    companion object {
        const val TINDER_PACKAGE = "com.tinder"
        private const val SCAN_DEBOUNCE_MS = 380L

        private val PLACEHOLDER_STRINGS = setOf(
            "type a message", "type a message...", "say something nice",
            "say something nice...", "输入消息", "输入消息...", "พิมพ์ข้อความ..."
        )

        private val IGNORED_PACKAGES = setOf(
            "com.android.systemui",
            "com.google.android.inputmethod.latin",
            "com.samsung.android.honeyboard",
            "com.sohu.inputmethod.sogou",
            "com.baidu.input",
            "com.iflytek.inputmethod",
            "android"
        )

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        private var instance: TinderAccessibilityService? = null

        var onStatusChanged: ((Boolean) -> Unit)? = null

        fun notifyMockChatUpdated() {
            val svc = instance ?: return
            svc.serviceScope.launch {
                if (MockChatActivity.isForeground) {
                    svc.activeTargetPackage = svc.packageName
                    svc.scheduleDebouncedScan()
                } else if (svc.activeTargetPackage == svc.packageName) {
                    svc.leaveTargetApp()
                }
            }
        }
    }
}
