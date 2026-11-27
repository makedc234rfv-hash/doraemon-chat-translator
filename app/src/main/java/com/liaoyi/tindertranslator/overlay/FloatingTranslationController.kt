package com.liaoyi.tindertranslator.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.liaoyi.tindertranslator.data.AppLanguage
import com.liaoyi.tindertranslator.data.AppStrings
import com.liaoyi.tindertranslator.data.OverlaySize
import com.liaoyi.tindertranslator.data.ReplyLanguage
import com.liaoyi.tindertranslator.data.ReplyPolishResult
import com.liaoyi.tindertranslator.data.TranslationResult
import com.liaoyi.tindertranslator.parser.ChatMessage
import kotlin.math.abs

data class DisplayItem(
    val message: ChatMessage,
    val translation: TranslationResult?,
    val isTranslating: Boolean
)

data class PolishUiState(
    val visible: Boolean = false,
    val isLoading: Boolean = false,
    val result: ReplyPolishResult? = null,
    val message: String? = null
)

class FloatingTranslationController(
    private val context: Context,
    private val onToggleAutoPopup: () -> Unit,
    private val onCycleSizeClick: () -> Unit,
    private val onRefreshClick: () -> Unit,
    private val onCollapseClick: () -> Unit,
    private val onExpandClick: () -> Unit,
    private val onOpenSettingsClick: () -> Unit,
    private val onPolishReplyClick: () -> Unit,
    private val onCycleReplyLangClick: () -> Unit,
    private val onClosePolishClick: () -> Unit,
    private val onFillReplyClick: (String) -> Unit,
    private val onCopyReplyClick: (String) -> Unit
) {

    private val overlayContext: Context = createOverlayContext(context)
    private val windowManager: WindowManager =
        overlayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val fallbackWindowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var activeWindowManager: WindowManager = windowManager

    private var cardView: View? = null
    private var cardParams: WindowManager.LayoutParams? = null
    private var pillView: View? = null
    private var pillParams: WindowManager.LayoutParams? = null

    private var titleView: TextView? = null
    private var itemsContainer: LinearLayout? = null
    private var itemsScrollView: MaxHeightScrollView? = null
    private var autoPopupBtn: TextView? = null
    private var sizeBtn: TextView? = null
    private var refreshBtn: TextView? = null
    private var collapseBtn: TextView? = null

    private var polishPanel: LinearLayout? = null
    private var polishTriggerBtn: TextView? = null
    private var replyLangChip: TextView? = null

    private var statusRow: LinearLayout? = null
    private var statusProgress: ProgressBar? = null
    private var statusText: TextView? = null
    private var statusActionBtn: TextView? = null

    private var pillTranslateLabel: TextView? = null
    private var pillPolishLabel: TextView? = null
    private var pillDot: View? = null

    private var savedCardY: Int = -1
    private var savedPillY: Int = -1
    private var autoPopupEnabled: Boolean = true
    private var currentLanguage: AppLanguage = AppLanguage.ZH
    private var currentReplyLanguage: ReplyLanguage = ReplyLanguage.AUTO
    private var currentSize: OverlaySize = OverlaySize.MEDIUM
    private var currentMode: Mode = Mode.HIDDEN

    enum class Mode { HIDDEN, EXPANDED, COLLAPSED }

    val mode: Mode get() = currentMode

    fun isOverlayActuallyAttached(): Boolean {
        return when (currentMode) {
            Mode.EXPANDED -> cardView?.isAttachedToWindow == true
            Mode.COLLAPSED -> pillView?.isAttachedToWindow == true
            Mode.HIDDEN -> false
        }
    }

    fun resetPositionsForConfigChange() {
        savedCardY = -1
        savedPillY = -1
        hideAll()
    }

    fun showExpanded(
        items: List<DisplayItem>,
        language: AppLanguage,
        replyLanguage: ReplyLanguage,
        overlaySize: OverlaySize,
        autoPopup: Boolean,
        isLoading: Boolean,
        errorMessage: String? = null,
        showSettingsAction: Boolean = false,
        polishState: PolishUiState = PolishUiState()
    ) {
        currentLanguage = language
        currentReplyLanguage = replyLanguage
        currentSize = overlaySize
        autoPopupEnabled = autoPopup
        removePill()
        ensureCardAttached()
        currentMode = Mode.EXPANDED

        applyCardSizingAndLabels()
        renderItems(items)
        renderPolishPanel(polishState)
        renderStatus(isLoading, errorMessage, showSettingsAction)
    }

    fun showCollapsedPill(language: AppLanguage, hasUnread: Boolean = false) {
        currentLanguage = language
        removeCard()
        ensurePillAttached()
        currentMode = Mode.COLLAPSED
        val t = AppStrings.of(language)
        pillTranslateLabel?.text = language.pillSymbol
        pillPolishLabel?.text = t.polishPillSymbol
        pillDot?.visibility = if (hasUnread) View.VISIBLE else View.GONE
    }

    fun hideAll() {
        removeCard()
        removePill()
        currentMode = Mode.HIDDEN
    }

    private fun screenScale(): Float {
        val dm = context.resources.displayMetrics
        val widthDp = dm.widthPixels / dm.density
        return (widthDp / 390f).coerceIn(0.84f, 1.05f)
    }

    private fun ensureCardAttached() {
        if (cardView != null && cardView?.isAttachedToWindow == true) return
        if (cardView != null) {
            removeCard()
        }

        val dm = overlayContext.resources.displayMetrics
        val screenWidth = dm.widthPixels
        val screenHeight = dm.heightPixels
        val scale = screenScale()
        val sideMargin = dp((10 * scale).toInt())
        val maxCardWidth = dp(420)
        val cardWidth = (screenWidth - sideMargin * 2).coerceIn(dp(240), maxCardWidth)
        val startX = ((screenWidth - cardWidth) / 2).coerceAtLeast(sideMargin)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(
                Color.parseColor("#F2191C24"),
                dp(16).toFloat(),
                Color.parseColor("#33FFFFFF"),
                dp(1)
            )
            elevation = dp(10).toFloat()
            val padH = dp((10 * scale).toInt())
            val padV = dp((8 * scale).toInt())
            setPadding(padH, padV, padH, padV)
        }

        // Header (draggable vertically)
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(1), dp(2), dp(6))
        }

        val dragDot = View(context).apply {
            background = roundedRect(Color.parseColor("#FE3C72"), dp(4).toFloat())
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply {
                marginEnd = dp(6)
            }
        }
        val title = TextView(context).apply {
            text = "机器猫"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * scale)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleView = title

        val autoBtn = makeHeaderChip("", scale).apply {
            setOnClickListener { onToggleAutoPopup() }
        }
        autoPopupBtn = autoBtn

        val sBtn = makeHeaderChip("", scale).apply {
            setOnClickListener { onCycleSizeClick() }
        }
        sizeBtn = sBtn

        val rBtn = makeHeaderChip("", scale).apply {
            setOnClickListener { onRefreshClick() }
        }
        refreshBtn = rBtn

        val cBtn = makeHeaderChip(
            "",
            scale,
            Color.parseColor("#3DFE3C72"),
            Color.parseColor("#FFFF8FA9")
        ).apply {
            setOnClickListener { onCollapseClick() }
        }
        collapseBtn = cBtn

        header.addView(dragDot)
        header.addView(title)
        header.addView(autoBtn)
        header.addView(sBtn)
        header.addView(rBtn)
        header.addView(cBtn)

        // Scrollable body containing incoming translations + optional reply polish results
        val scrollView = MaxHeightScrollView(context, (screenHeight * currentSize.heightRatio).toInt()).apply {
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val scrollBody = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val polishBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(6)
            }
        }
        polishPanel = polishBox

        val listLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        itemsContainer = listLayout

        scrollBody.addView(polishBox)
        scrollBody.addView(listLayout)

        scrollView.addView(
            scrollBody,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        itemsScrollView = scrollView

        // Quick Reply Polish bar at bottom of card
        val replyBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(6), dp(2), dp(1))
        }

        val polishBtn = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * scale)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = roundedRect(Color.parseColor("#FFFE3C72"), dp(10).toFloat())
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onPolishReplyClick() }
        }
        polishTriggerBtn = polishBtn

        val langBtn = TextView(context).apply {
            setTextColor(Color.parseColor("#FFE5E7EB"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * scale)
            typeface = Typeface.DEFAULT_BOLD
            background = roundedRect(Color.parseColor("#2AFFFFFF"), dp(10).toFloat())
            setPadding(dp(9), dp(6), dp(9), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(6)
            }
            setOnClickListener { onCycleReplyLangClick() }
        }
        replyLangChip = langBtn

        replyBar.addView(polishBtn)
        replyBar.addView(langBtn)

        // Footer status / error row
        val statusBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(5), dp(2), dp(1))
            visibility = View.GONE
        }
        val progress = ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply {
                marginEnd = dp(6)
            }
        }
        val statusMsg = TextView(context).apply {
            setTextColor(Color.parseColor("#FFD1D5DB"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f * scale)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val actionBtn = makeHeaderChip("", scale, Color.parseColor("#FFFE3C72"), Color.WHITE).apply {
            visibility = View.GONE
        }
        statusBar.addView(progress)
        statusBar.addView(statusMsg)
        statusBar.addView(actionBtn)

        statusRow = statusBar
        statusProgress = progress
        statusText = statusMsg
        statusActionBtn = actionBtn

        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(replyBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(statusBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val initialY = if (savedCardY >= 0) savedCardY else getStatusBarHeightPx() + dp(6)
        val params = createOverlayParams(
            width = cardWidth,
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            x = startX,
            y = initialY
        )

        attachVerticalDrag(header, root, params, screenHeight) { newY ->
            savedCardY = newY
        }

        if (addOverlayView(root, params)) {
            cardView = root
            cardParams = params
        } else {
            cardView = null
            cardParams = null
        }
    }

    private fun ensurePillAttached() {
        if (pillView != null && pillView?.isAttachedToWindow == true) return
        if (pillView != null) {
            removePill()
        }

        val dm = overlayContext.resources.displayMetrics
        val screenHeight = dm.heightPixels
        val scale = screenScale()

        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = roundedRect(
                Color.parseColor("#EA191C24"),
                dp(20).toFloat(),
                Color.parseColor("#66FE3C72"),
                dp(1)
            )
            elevation = dp(8).toFloat()
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        // Top button: expand translations (译 / แปล)
        val topBox = FrameLayout(context).apply {
            setPadding(dp((7 * scale).toInt()), dp((5 * scale).toInt()), dp((7 * scale).toInt()), dp((5 * scale).toInt()))
        }
        val tLabel = TextView(context).apply {
            text = currentLanguage.pillSymbol
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * scale)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        pillTranslateLabel = tLabel
        topBox.addView(tLabel)

        val dot = View(context).apply {
            background = roundedRect(Color.parseColor("#FE3C72"), dp(4).toFloat())
            visibility = View.GONE
        }
        topBox.addView(dot, FrameLayout.LayoutParams(dp(7), dp(7), Gravity.TOP or Gravity.END))
        pillDot = dot

        // Divider
        val divider = View(context).apply {
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(1)).apply {
                topMargin = dp(2)
                bottomMargin = dp(2)
            }
        }

        // Bottom button: polish draft reply (润 / เกลา)
        val pLabel = TextView(context).apply {
            text = AppStrings.of(currentLanguage).polishPillSymbol
            setTextColor(Color.parseColor("#FFFF8FA9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f * scale)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp((7 * scale).toInt()), dp((5 * scale).toInt()), dp((7 * scale).toInt()), dp((5 * scale).toInt()))
        }
        pillPolishLabel = pLabel

        outer.addView(topBox)
        outer.addView(divider)
        outer.addView(pLabel)

        val pillY = if (savedPillY >= 0) savedPillY else (screenHeight * 0.28f).toInt()
        val params = createOverlayParams(
            width = WindowManager.LayoutParams.WRAP_CONTENT,
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            x = dp(6),
            y = pillY,
            gravity = Gravity.TOP or Gravity.END
        )

        attachPillDragAndTap(topBox, outer, params, screenHeight, onTap = onExpandClick) { newY ->
            savedPillY = newY
        }
        attachPillDragAndTap(pLabel, outer, params, screenHeight, onTap = onPolishReplyClick) { newY ->
            savedPillY = newY
        }

        if (addOverlayView(outer, params)) {
            pillView = outer
            pillParams = params
        } else {
            pillView = null
            pillParams = null
        }
    }

    private fun applyCardSizingAndLabels() {
        val t = AppStrings.of(currentLanguage)
        val dm = context.resources.displayMetrics
        itemsScrollView?.setMaxHeightPx((dm.heightPixels * currentSize.heightRatio).toInt())

        titleView?.text = t.overlayTitle
        sizeBtn?.text = t.sizeLabel(currentSize)
        refreshBtn?.text = t.overlayRefresh
        collapseBtn?.text = t.overlayCollapse
        polishTriggerBtn?.text = "✨ ${t.polishBtn}"
        replyLangChip?.text = t.replyLangLabel(currentReplyLanguage)

        autoPopupBtn?.let { btn ->
            if (autoPopupEnabled) {
                btn.text = t.overlayAutoOn
                btn.background = roundedRect(Color.parseColor("#3322C55E"), dp(9).toFloat())
                btn.setTextColor(Color.parseColor("#FF86EFAC"))
            } else {
                btn.text = t.overlayAutoOff
                btn.background = roundedRect(Color.parseColor("#26FFFFFF"), dp(9).toFloat())
                btn.setTextColor(Color.parseColor("#FF9CA3AF"))
            }
        }
    }

    private fun renderPolishPanel(state: PolishUiState) {
        val panel = polishPanel ?: return
        panel.removeAllViews()
        if (!state.visible) {
            panel.visibility = View.GONE
            return
        }

        panel.visibility = View.VISIBLE
        val t = AppStrings.of(currentLanguage)
        val scale = screenScale()
        val fontFactor = scale * currentSize.fontScale

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(
                Color.parseColor("#FF2A2233"),
                dp(12).toFloat(),
                Color.parseColor("#66FE3C72"),
                dp(1)
            )
            val pad = dp((9 * scale).toInt())
            setPadding(pad, pad, pad, pad)
        }

        // Panel header
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val langTag = state.result?.targetLanguage?.shortLabel ?: t.replyLangLabel(currentReplyLanguage)
        val hTitle = TextView(context).apply {
            text = "✨ ${t.polishHeaderPrefix} $langTag"
            setTextColor(Color.parseColor("#FFFFA3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f * fontFactor)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = makeHeaderChip(t.polishCloseBtn, scale).apply {
            setOnClickListener { onClosePolishClick() }
        }
        header.addView(hTitle)
        header.addView(closeBtn)
        card.addView(header)

        when {
            state.isLoading -> {
                val loadRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, dp(4))
                }
                val pb = ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
                    isIndeterminate = true
                    layoutParams = LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginEnd = dp(6) }
                }
                val tv = TextView(context).apply {
                    text = t.polishLoading
                    setTextColor(Color.parseColor("#FFE5E7EB"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f * fontFactor)
                }
                loadRow.addView(pb)
                loadRow.addView(tv)
                card.addView(loadRow)
            }
            !state.message.isNullOrBlank() -> {
                val msgTv = TextView(context).apply {
                    text = state.message
                    setTextColor(Color.parseColor("#FFFDE68A"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * fontFactor)
                    setPadding(0, dp(6), 0, dp(2))
                }
                card.addView(msgTv)
            }
            state.result != null -> {
                state.result.options.forEachIndexed { idx, opt ->
                    val optBox = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        background = roundedRect(Color.parseColor("#FF1F1B27"), dp(10).toFloat())
                        setPadding(dp(8), dp(7), dp(8), dp(7))
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            topMargin = if (idx == 0) dp(6) else dp(5)
                        }
                    }

                    val topRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    val styleBadge = TextView(context).apply {
                        text = opt.styleLabel
                        setTextColor(Color.parseColor("#FFFBBF24"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f * fontFactor)
                        typeface = Typeface.DEFAULT_BOLD
                        background = roundedRect(Color.parseColor("#2BF59E0B"), dp(6).toFloat())
                        setPadding(dp(6), dp(2), dp(6), dp(2))
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val fillBtn = makeHeaderChip(
                        t.polishFillBtn,
                        scale,
                        Color.parseColor("#FFFE3C72"),
                        Color.WHITE
                    ).apply {
                        setOnClickListener { onFillReplyClick(opt.text) }
                    }
                    val copyBtn = makeHeaderChip(t.polishCopyBtn, scale).apply {
                        setOnClickListener { onCopyReplyClick(opt.text) }
                    }
                    topRow.addView(styleBadge)
                    topRow.addView(fillBtn)
                    topRow.addView(copyBtn)
                    optBox.addView(topRow)

                    val replyTv = TextView(context).apply {
                        text = opt.text
                        setTextColor(Color.WHITE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * fontFactor)
                        setLineSpacing(dp(2).toFloat(), 1f)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            topMargin = dp(5)
                        }
                    }
                    optBox.addView(replyTv)

                    if (opt.backTranslation.isNotEmpty()) {
                        val backTv = TextView(context).apply {
                            text = "${t.polishBackPrefix}${opt.backTranslation}"
                            setTextColor(Color.parseColor("#FF9CA3AF"))
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f * fontFactor)
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply {
                                topMargin = dp(3)
                            }
                        }
                        optBox.addView(backTv)
                    }

                    card.addView(optBox)
                }
            }
        }

        panel.addView(card)
        itemsScrollView?.post { itemsScrollView?.fullScroll(View.FOCUS_UP) }
    }

    private fun renderItems(items: List<DisplayItem>) {
        val container = itemsContainer ?: return
        val t = AppStrings.of(currentLanguage)
        val fontFactor = screenScale() * currentSize.fontScale
        container.removeAllViews()

        if (items.isEmpty()) {
            val emptyTv = TextView(context).apply {
                text = t.overlayEmpty
                setTextColor(Color.parseColor("#FF9CA3AF"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f * fontFactor)
                setPadding(dp(4), dp(6), dp(4), dp(6))
            }
            container.addView(emptyTv)
            return
        }

        items.forEachIndexed { index, item ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedRect(Color.parseColor("#FF252935"), dp(10).toFloat())
                val padH = dp((9 * screenScale()).toInt())
                val padV = dp((7 * screenScale()).toInt())
                setPadding(padH, padV, padH, padV)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (index > 0) topMargin = dp(5)
                }
            }

            val topLine = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }

            val badge = TextView(context).apply {
                text = if (item.message.isIncoming) t.overlayBadgeIncoming else t.overlayBadgeMine
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f * fontFactor)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(5), dp(2), dp(5), dp(2))
                if (item.message.isIncoming) {
                    background = roundedRect(Color.parseColor("#3DFE3C72"), dp(5).toFloat())
                    setTextColor(Color.parseColor("#FFFF8FA9"))
                } else {
                    background = roundedRect(Color.parseColor("#3360A5FA"), dp(5).toFloat())
                    setTextColor(Color.parseColor("#FF93C5FD"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dp(6)
                    topMargin = dp(2)
                }
            }

            val translatedText = TextView(context).apply {
                text = when {
                    item.translation != null -> item.translation.translated
                    item.isTranslating -> t.overlayTranslatingItem
                    else -> t.overlayWaitingItem
                }
                setTextColor(
                    if (item.translation != null) Color.WHITE else Color.parseColor("#FF9CA3AF")
                )
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * fontFactor)
                setLineSpacing(dp(2).toFloat(), 1f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            topLine.addView(badge)
            topLine.addView(translatedText)
            row.addView(topLine)

            val note = item.translation?.note.orEmpty().trim()
            if (note.isNotEmpty()) {
                val noteTv = TextView(context).apply {
                    text = "💡 $note"
                    setTextColor(Color.parseColor("#FFFBBF24"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f * fontFactor)
                    background = roundedRect(Color.parseColor("#2BF59E0B"), dp(7).toFloat())
                    setPadding(dp(7), dp(3), dp(7), dp(3))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dp(4)
                    }
                }
                row.addView(noteTv)
            }

            val origTv = TextView(context).apply {
                text = item.message.text
                setTextColor(Color.parseColor("#FF9CA3AF"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f * fontFactor)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(3)
                }
            }
            row.addView(origTv)

            container.addView(row)
        }
        if (polishPanel?.visibility != View.VISIBLE) {
            itemsScrollView?.post { itemsScrollView?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun renderStatus(isLoading: Boolean, errorMessage: String?, showSettingsAction: Boolean) {
        val bar = statusRow ?: return
        val progress = statusProgress ?: return
        val msg = statusText ?: return
        val action = statusActionBtn ?: return
        val t = AppStrings.of(currentLanguage)

        when {
            !errorMessage.isNullOrBlank() -> {
                bar.visibility = View.VISIBLE
                progress.visibility = View.GONE
                msg.text = errorMessage
                msg.setTextColor(Color.parseColor("#FFFCA5A5"))
                action.visibility = View.VISIBLE
                if (showSettingsAction) {
                    action.text = t.overlayOpenSettings
                    action.setOnClickListener { onOpenSettingsClick() }
                } else {
                    action.text = t.overlayRetry
                    action.setOnClickListener { onRefreshClick() }
                }
            }
            isLoading -> {
                bar.visibility = View.VISIBLE
                progress.visibility = View.VISIBLE
                msg.text = t.overlayLoadingBanner
                msg.setTextColor(Color.parseColor("#FFD1D5DB"))
                action.visibility = View.GONE
            }
            else -> {
                bar.visibility = View.GONE
            }
        }
    }

    private fun removeCard() {
        val view = cardView ?: return
        runCatching { activeWindowManager.removeViewImmediate(view) }
            .onFailure { runCatching { fallbackWindowManager.removeViewImmediate(view) } }
        cardView = null
        cardParams = null
        titleView = null
        itemsContainer = null
        itemsScrollView = null
        autoPopupBtn = null
        sizeBtn = null
        refreshBtn = null
        collapseBtn = null
        polishPanel = null
        polishTriggerBtn = null
        replyLangChip = null
        statusRow = null
        statusProgress = null
        statusText = null
        statusActionBtn = null
    }

    private fun removePill() {
        val view = pillView ?: return
        runCatching { activeWindowManager.removeViewImmediate(view) }
            .onFailure { runCatching { fallbackWindowManager.removeViewImmediate(view) } }
        pillView = null
        pillParams = null
        pillTranslateLabel = null
        pillPolishLabel = null
        pillDot = null
    }

    private fun addOverlayView(view: View, params: WindowManager.LayoutParams): Boolean {
        return try {
            windowManager.addView(view, params)
            activeWindowManager = windowManager
            true
        } catch (_: Exception) {
            try {
                fallbackWindowManager.addView(view, params)
                activeWindowManager = fallbackWindowManager
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun createOverlayParams(
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        gravity: Int = Gravity.TOP or Gravity.START
    ): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        return WindowManager.LayoutParams(
            width,
            height,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
            this.x = x
            this.y = y
        }
    }

    private fun createOverlayContext(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val dm = base.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            if (display != null) {
                return runCatching {
                    base.createWindowContext(
                        display,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        null
                    )
                }.getOrDefault(base)
            }
        }
        return base
    }

    private fun attachVerticalDrag(
        handle: View,
        root: View,
        params: WindowManager.LayoutParams,
        screenHeight: Int,
        onMoved: (Int) -> Unit
    ) {
        var startY = 0
        var startTouchY = 0f
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = params.y
                    startTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (event.rawY - startTouchY).toInt()
                    val newY = (startY + dy).coerceIn(dp(24), (screenHeight * 0.65f).toInt())
                    params.y = newY
                    runCatching { activeWindowManager.updateViewLayout(root, params) }
                    onMoved(newY)
                    true
                }
                else -> false
            }
        }
    }

    private fun attachPillDragAndTap(
        touchTarget: View,
        pillRoot: View,
        params: WindowManager.LayoutParams,
        screenHeight: Int,
        onTap: () -> Unit,
        onMoved: (Int) -> Unit
    ) {
        var startY = 0
        var startTouchX = 0f
        var startTouchY = 0f
        var dragging = false
        val slop = dp(6)

        touchTarget.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = params.y
                    startTouchX = event.rawX
                    startTouchY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startTouchX
                    val dy = event.rawY - startTouchY
                    if (abs(dx) > slop || abs(dy) > slop) {
                        dragging = true
                    }
                    if (dragging) {
                        val newY = (startY + dy.toInt()).coerceIn(dp(48), (screenHeight * 0.78f).toInt())
                        params.y = newY
                        runCatching { activeWindowManager.updateViewLayout(pillRoot, params) }
                        onMoved(newY)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        onTap()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun makeHeaderChip(
        label: String,
        scale: Float,
        bgColor: Int = Color.parseColor("#26FFFFFF"),
        textColor: Int = Color.parseColor("#FFE5E7EB")
    ): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f * scale)
            typeface = Typeface.DEFAULT_BOLD
            background = roundedRect(bgColor, dp(9).toFloat())
            val padH = dp((7 * scale).toInt())
            val padV = dp((3 * scale).toInt())
            setPadding(padH, padV, padH, padV)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(4)
            }
        }
    }

    private fun roundedRect(
        fillColor: Int,
        radiusPx: Float,
        strokeColor: Int = Color.TRANSPARENT,
        strokeWidth: Int = 0
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(fillColor)
            if (strokeWidth > 0) {
                setStroke(strokeWidth, strokeColor)
            }
        }
    }

    private fun getStatusBarHeightPx(): Int {
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) context.resources.getDimensionPixelSize(resId) else dp(36)
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    private class MaxHeightScrollView(
        context: Context,
        private var maxHeightPx: Int
    ) : ScrollView(context) {
        fun setMaxHeightPx(newMax: Int) {
            if (maxHeightPx != newMax) {
                maxHeightPx = newMax
                requestLayout()
            }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val cappedHeightSpec = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
            super.onMeasure(widthMeasureSpec, cappedHeightSpec)
        }
    }
}
