package com.liaoyi.tindertranslator.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.liaoyi.tindertranslator.data.AppLanguage
import com.liaoyi.tindertranslator.data.AppStrings
import com.liaoyi.tindertranslator.data.SettingsStore
import com.liaoyi.tindertranslator.data.TranslationResult
import com.liaoyi.tindertranslator.overlay.DisplayItem
import com.liaoyi.tindertranslator.overlay.FloatingTranslationController
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

    private var activeTargetPackage: String? = null
    private var lastLanguage: AppLanguage? = null
    private var lastDisplayMessages: List<ChatMessage> = emptyList()
    private var lastContextMessages: List<ChatMessage> = emptyList()
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
            onOpenSettingsClick = ::openAppSettings
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
        inFlightIds.clear()
        lastDisplayMessages = emptyList()
        lastContextMessages = emptyList()
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
            if (forceExpand) {
                overlayController.showExpanded(
                    items = emptyList(),
                    language = settings.language,
                    overlaySize = settings.overlaySize,
                    autoPopup = settings.autoPopup,
                    isLoading = false
                )
            } else {
                overlayController.showCollapsedPill(settings.language, hasUnread = false)
            }
            return
        }

        val shouldShowExpanded = forceExpand || decision.shouldAutoPopup || !triggerTracker.isCollapsedByUser

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
            overlaySize = settings.overlaySize,
            autoPopup = settings.autoPopup,
            isLoading = isLoading,
            errorMessage = errorMessage,
            showSettingsAction = showSettingsAction
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
