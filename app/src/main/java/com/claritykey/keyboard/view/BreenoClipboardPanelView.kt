package com.claritykey.keyboard.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.widget.HorizontalScrollView
import java.io.FileOutputStream
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.claritykey.keyboard.R
import com.claritykey.keyboard.clipboard.ClipEntry
import com.claritykey.keyboard.clipboard.MediaClipboardStorage
import com.claritykey.keyboard.clipboard.MediaStorageStats
import com.claritykey.keyboard.clipboard.OcrVisionResult
import com.claritykey.keyboard.clipboard.SmartEntity
import com.claritykey.keyboard.engine.BreenoClipboardManager
import com.claritykey.keyboard.prefs.BreenoPreferences
import com.claritykey.keyboard.skin.BreenoSkinEngine
import com.claritykey.keyboard.skin.KeyboardSkin
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Breeno-native Smart Clipboard 2.0 Panel (Text + Images):
 * - Preserves original Breeno RU tabs ("Недавние" / "Закреплённые"), palette, and key radii
 * - Adds compact filter strip: "Все" | "Текст" | "Изображения" + "+ Фото" (Android Photo Picker)
 * - Image cards show proportional cached thumbnails (async LruCache, never loads full-res bitmaps in grid),
 *   MIME badge (PNG/JPG/WEBP/GIF), source icon/name, relative time, and pin indicator
 * - Single tap inserts image via InputConnectionCompat.commitContent() (with Share fallback banner if unsupported)
 * - Long press opens context bar: "Просмотреть" | "Закрепить/Открепить" | "Поделиться" | "Удалить"
 * - Full-image viewer opens inside the existing panel bounds without increasing keyboard height
 */
class BreenoClipboardPanelView(
    context: Context,
    private val clipboardManager: BreenoClipboardManager,
    private val prefs: BreenoPreferences
) : LinearLayout(context) {

    interface Listener {
        fun onClipPaste(text: String)
        fun onClipFragmentPaste(fragment: String)
        fun onClipImagePaste(entry: ClipEntry)
        fun onClipImageShare(entry: ClipEntry)
        fun onClipOpenPhotoPicker()
        fun onClipRequestScreenshotAccess()
        fun onClipOpenSettings()
        fun onClipOpenVault()
        fun onClipOpenTemplates()
        fun onClipRedactedImagePaste(filePath: String)
        fun onClipClose()
    }

    var listener: Listener? = null

    private enum class Tab { RECENT, PINNED }
    private enum class MediaFilter { ALL, TEXT, IMAGES, TEMPLATES, VAULT, NOTEPAD }
    private enum class OcrSubTab { OBJECTS, PARAGRAPHS, LINES }

    private data class AppMeta(val label: String, val icon: Drawable?)

    private var skin: KeyboardSkin = BreenoSkinEngine.LIGHT_SKIN
    private var activeTab: Tab = Tab.RECENT
    private var activeFilter: MediaFilter = MediaFilter.ALL
    private var cachedEntries: List<ClipEntry> = emptyList()
    private var mediaStats: MediaStorageStats? = null
    private var searchQuery: String = ""
    private var searchVisible: Boolean = false
    private var contextEntry: ClipEntry? = null
    private var fullViewEntry: ClipEntry? = null
    private var smartFragmentsEntry: ClipEntry? = null
    private var smartFragmentsList: List<SmartEntity> = emptyList()
    private var visionOcrEntry: ClipEntry? = null
    private var visionOcrResult: OcrVisionResult? = null
    private var visionOcrLoading: Boolean = false
    private var visionOcrTab: OcrSubTab = OcrSubTab.OBJECTS
    private var unsupportedBannerEntry: ClipEntry? = null
    private var confirmClearVisible: Boolean = false
    private var pinnedWarningDismissed: Boolean = false
    private var screenshotPromptDismissed: Boolean = false
    private var vaultUnlocked: Boolean = false
    private var redactModeActive: Boolean = false
    private var redactWorkingBitmap: Bitmap? = null

    private val appMetaCache = HashMap<String, AppMeta?>()
    private val thumbCache = object : LruCache<String, Bitmap>(6 * 1024) { // 6 MB thumb cache
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }
    private val thumbExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dateFmt = SimpleDateFormat("dd.MM", Locale.getDefault())
    private val density = context.resources.displayMetrics.density

    private val headerRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val filterRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val searchRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
    }
    private val contextBar = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
    }
    private val infoBannerBar = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
    }
    private val confirmBar = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
    }
    private val fullViewContainer = LinearLayout(context).apply {
        orientation = VERTICAL
        visibility = GONE
    }
    private val scrollView = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
    private val grid = GridLayout(context).apply { columnCount = 2 }

    private lateinit var searchInput: EditText

    init {
        orientation = VERTICAL
        buildSearchRow()
        addView(headerRow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38f)))
        addView(filterRow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30f)))
        addView(searchRow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36f)))
        addView(contextBar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36f)))
        addView(infoBannerBar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(confirmBar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(fullViewContainer, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        scrollView.addView(grid, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(scrollView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun dp(v: Float): Int = (v * density).roundToInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val desiredH = (w * (skin.keyboardRefHeightDp / skin.keyboardRefWidthDp)).roundToInt()
            .coerceIn(dp(212f), dp(296f)) + dp(8f)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(desiredH, MeasureSpec.EXACTLY))
    }

    private fun buildSearchRow() {
        searchRow.setPadding(dp(10f), dp(2f), dp(10f), dp(4f))
        searchInput = EditText(context).apply {
            hint = "Поиск в буфере…"
            setSingleLine(true)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery = s?.toString()?.trim() ?: ""
                    renderGrid()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        val clearSearch = TextView(context).apply {
            text = "✕"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(dp(10f), 0, dp(4f), 0)
            setOnClickListener {
                searchInput.setText("")
                searchQuery = ""
                searchVisible = false
                searchRow.visibility = GONE
                renderGrid()
            }
        }
        searchRow.addView(searchInput, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        searchRow.addView(clearSearch)
    }

    fun applySkin(newSkin: KeyboardSkin) {
        skin = newSkin
        setBackgroundColor(if (skin.isGlassBackground) Color.TRANSPARENT else skin.common.keyboardBackgroundColor)
        searchInput.setTextColor(skin.keyTextColor)
        searchInput.setHintTextColor(skin.keyMarkColor)
        searchInput.background = GradientDrawable().apply {
            cornerRadius = dp(8f).toFloat()
            setColor(skin.normalKeyBg.normalColor)
        }
        renderHeader()
        renderFilters()
        renderContextBar()
        renderInfoBanner()
        renderConfirmBar()
        when {
            visionOcrEntry != null -> renderVisionOcrView()
            smartFragmentsEntry != null -> renderSmartFragmentsView()
            fullViewEntry != null -> renderFullView()
            else -> renderGrid()
        }
    }

    fun refresh() {
        contextEntry = null
        fullViewEntry = null
        smartFragmentsEntry = null
        smartFragmentsList = emptyList()
        visionOcrEntry = null
        visionOcrResult = null
        visionOcrLoading = false
        unsupportedBannerEntry = null
        confirmClearVisible = false
        vaultUnlocked = prefs.isVaultUnlocked()
        contextBar.visibility = GONE
        confirmBar.visibility = GONE
        fullViewContainer.visibility = GONE
        scrollView.visibility = VISIBLE
        filterRow.visibility = VISIBLE
        clipboardManager.loadHistoryAsync { entries ->
            cachedEntries = entries
            renderHeader()
            renderFilters()
            renderGrid()
        }
        clipboardManager.getMediaStorageStatsAsync { stats ->
            mediaStats = stats
            renderInfoBanner()
        }
    }

    fun showUnsupportedEditorBanner(entry: ClipEntry) {
        unsupportedBannerEntry = entry
        renderInfoBanner()
    }

    private fun resolveAppMeta(packageName: String?): AppMeta? {
        if (packageName.isNullOrBlank()) return null
        if (appMetaCache.containsKey(packageName)) return appMetaCache[packageName]
        val meta = try {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(packageName, 0)
            val label = pm.getApplicationLabel(ai)?.toString()?.takeIf { it.isNotBlank() } ?: packageName
            val icon = pm.getApplicationIcon(ai)
            AppMeta(label, icon)
        } catch (_: Exception) {
            null
        }
        appMetaCache[packageName] = meta
        return meta
    }

    private fun formatRelativeTime(timestampMs: Long): String {
        val now = System.currentTimeMillis()
        val diffMs = (now - timestampMs).coerceAtLeast(0L)
        val mins = diffMs / 60_000L
        if (mins < 1L) return "Только что"
        if (mins < 60L) return "$mins мин."
        val hours = mins / 60L
        if (hours < 24L) return "$hours ч."

        val calNow = Calendar.getInstance()
        val calThen = Calendar.getInstance().apply { timeInMillis = timestampMs }
        calNow.add(Calendar.DAY_OF_YEAR, -1)
        val isYesterday = calNow.get(Calendar.YEAR) == calThen.get(Calendar.YEAR) &&
            calNow.get(Calendar.DAY_OF_YEAR) == calThen.get(Calendar.DAY_OF_YEAR)
        if (isYesterday) return "Вчера"

        return dateFmt.format(Date(timestampMs))
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024L) return "$bytes Б"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.0f КБ", kb)
        return String.format(Locale.US, "%.1f МБ", kb / 1024.0)
    }

    private fun renderHeader() {
        headerRow.removeAllViews()
        headerRow.setPadding(dp(6f), dp(3f), dp(6f), dp(1f))

        val backBtn = TextView(context).apply {
            text = "←"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTextColor(skin.keyTextColor)
            gravity = Gravity.CENTER
            setPadding(dp(6f), 0, dp(8f), 0)
            setOnClickListener {
                if (fullViewEntry != null || smartFragmentsEntry != null || visionOcrEntry != null) {
                    closeFullView()
                } else {
                    listener?.onClipClose()
                }
            }
        }
        headerRow.addView(backBtn)

        val recentCount = cachedEntries.count { !it.pinned }
        val pinnedCount = cachedEntries.count { it.pinned }

        headerRow.addView(makeTabPill("Недавние ($recentCount)", activeTab == Tab.RECENT) {
            activeTab = Tab.RECENT
            closeFullView()
            contextEntry = null
            contextBar.visibility = GONE
            renderHeader()
            renderGrid()
        })
        headerRow.addView(makeTabPill("Закреплённые ($pinnedCount)", activeTab == Tab.PINNED) {
            activeTab = Tab.PINNED
            closeFullView()
            contextEntry = null
            contextBar.visibility = GONE
            renderHeader()
            renderGrid()
        })

        headerRow.addView(View(context), LayoutParams(0, 1, 1f))

        val searchBtn = ImageButton(context).apply {
            setImageResource(R.drawable.ic_breeno_action_search)
            contentDescription = "Поиск в буфере обмена"
            setColorFilter(skin.keyTextColor)
            background = null
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(7f), dp(7f), dp(7f), dp(7f))
            layoutParams = LayoutParams(dp(34f), dp(34f))
            setOnClickListener {
                closeFullView()
                searchVisible = !searchVisible
                searchRow.visibility = if (searchVisible) VISIBLE else GONE
                if (!searchVisible) {
                    searchInput.setText("")
                    searchQuery = ""
                    renderGrid()
                }
            }
        }
        headerRow.addView(searchBtn)

        if (recentCount > 0) {
            val clearBtn = ImageButton(context).apply {
                setImageResource(R.drawable.ic_toolbar_trash)
                setColorFilter(skin.keyTextColor)
                contentDescription = "Очистить историю буфера обмена"
                background = null
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(7f), dp(7f), dp(7f), dp(7f))
                layoutParams = LayoutParams(dp(34f), dp(34f))
                setOnClickListener {
                    closeFullView()
                    if (prefs.clipboardConfirmClear) {
                        confirmClearVisible = !confirmClearVisible
                        contextEntry = null
                        contextBar.visibility = GONE
                        renderConfirmBar()
                        confirmBar.visibility = if (confirmClearVisible) VISIBLE else GONE
                    } else {
                        clipboardManager.clearUnpinnedAsync { refresh() }
                    }
                }
            }
            headerRow.addView(clearBtn)
        }
    }

    fun setVaultUnlocked(unlocked: Boolean) {
        vaultUnlocked = unlocked
        if (activeFilter == MediaFilter.VAULT) renderGrid()
    }

    private fun renderFilters() {
        filterRow.removeAllViews()
        filterRow.setPadding(dp(8f), dp(1f), dp(8f), dp(2f))

        val hScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        val chipRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        listOf(
            MediaFilter.ALL to "Все",
            MediaFilter.TEXT to "Текст",
            MediaFilter.IMAGES to "Фото",
            MediaFilter.TEMPLATES to "Шаблоны",
            MediaFilter.VAULT to "🔒 Сейф",
            MediaFilter.NOTEPAD to "📝 Заметка"
        ).forEach { (filter, label) ->
            val selected = activeFilter == filter
            chipRow.addView(TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = if (selected) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.SANS_SERIF
                setTextColor(if (selected) skin.common.candidateTargetColor else skin.keyMarkColor)
                gravity = Gravity.CENTER
                setPadding(dp(8f), dp(2f), dp(8f), dp(2f))
                if (selected) {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10f).toFloat()
                        setColor(skin.normalKeyBg.normalColor)
                    }
                }
                layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(24f)).apply {
                    setMargins(0, 0, dp(3f), 0)
                }
                setOnClickListener {
                    activeFilter = filter
                    closeFullView()
                    renderFilters()
                    renderGrid()
                }
            })
        }
        hScroll.addView(chipRow)
        filterRow.addView(hScroll, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        // Compact Photo Picker trigger (no broad gallery permission required)
        if (prefs.clipboardSaveImages) {
            filterRow.addView(TextView(context).apply {
                text = "+ Фото"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(skin.common.candidateTargetColor)
                gravity = Gravity.CENTER
                setPadding(dp(8f), dp(2f), dp(8f), dp(2f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10f).toFloat()
                    setColor(skin.funcKeyBg.normalColor)
                }
                layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(24f)).apply {
                    leftMargin = dp(4f)
                }
                setOnClickListener { listener?.onClipOpenPhotoPicker() }
            })
        }
    }

    private fun makeTabPill(label: String, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = if (selected) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.SANS_SERIF
            setTextColor(if (selected) skin.common.candidateTargetColor else skin.keyMarkColor)
            gravity = Gravity.CENTER
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            if (selected) {
                background = GradientDrawable().apply {
                    cornerRadius = dp(8f).toFloat()
                    setColor(skin.normalKeyBg.normalColor)
                }
            }
            layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28f)).apply {
                setMargins(dp(2f), 0, dp(2f), 0)
            }
            setOnClickListener { onClick() }
        }

    private fun renderInfoBanner() {
        infoBannerBar.removeAllViews()
        val unsupported = unsupportedBannerEntry
        if (unsupported != null) {
            infoBannerBar.visibility = VISIBLE
            infoBannerBar.setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
            infoBannerBar.setBackgroundColor(skin.funcKeyBg.normalColor)

            infoBannerBar.addView(TextView(context).apply {
                text = "Поле не принимает фото с клавиатуры"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(skin.keyTextColor)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            infoBannerBar.addView(TextView(context).apply {
                text = "Поделиться"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(skin.common.candidateTargetColor)
                setPadding(dp(10f), dp(2f), dp(8f), dp(2f))
                setOnClickListener {
                    unsupportedBannerEntry = null
                    infoBannerBar.visibility = GONE
                    listener?.onClipImageShare(unsupported)
                }
            })

            infoBannerBar.addView(TextView(context).apply {
                text = "✕"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setTextColor(skin.keyMarkColor)
                setPadding(dp(6f), dp(2f), dp(2f), dp(2f))
                setOnClickListener {
                    unsupportedBannerEntry = null
                    renderInfoBanner()
                }
            })
            return
        }

        // Pinned images high-storage warning (>= 50 MB)
        val stats = mediaStats
        val pinnedMb = (stats?.pinnedBytes ?: 0L) / (1024.0 * 1024.0)
        if (!pinnedWarningDismissed && pinnedMb >= 50.0) {
            infoBannerBar.visibility = VISIBLE
            infoBannerBar.setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            infoBannerBar.setBackgroundColor(skin.funcKeyBg.normalColor)
            infoBannerBar.addView(TextView(context).apply {
                text = String.format(Locale.US, "Закреплённые фото занимают %.0f МБ", pinnedMb)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(skin.keyTextColor)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            infoBannerBar.addView(TextView(context).apply {
                text = "Открыть"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(skin.common.candidateTargetColor)
                setPadding(dp(8f), dp(2f), dp(6f), dp(2f))
                setOnClickListener {
                    activeTab = Tab.PINNED
                    activeFilter = MediaFilter.IMAGES
                    renderHeader()
                    renderFilters()
                    renderGrid()
                }
            })
            infoBannerBar.addView(TextView(context).apply {
                text = "✕"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(skin.keyMarkColor)
                setPadding(dp(6f), dp(2f), dp(2f), dp(2f))
                setOnClickListener {
                    pinnedWarningDismissed = true
                    infoBannerBar.visibility = GONE
                }
            })
            return
        }

        // Prompt to enable automatic screenshot capture if off or missing media permission
        val screenshotsActive = prefs.clipboardSuggestScreenshots && clipboardManager.screenshotDetector.hasPermission()
        if (!screenshotsActive && !screenshotPromptDismissed && (activeFilter == MediaFilter.IMAGES || activeFilter == MediaFilter.ALL)) {
            infoBannerBar.visibility = VISIBLE
            infoBannerBar.setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            infoBannerBar.setBackgroundColor(skin.funcKeyBg.normalColor)
            infoBannerBar.addView(TextView(context).apply {
                text = "Автосохранение скриншотов выкл."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(skin.keyTextColor)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            infoBannerBar.addView(TextView(context).apply {
                text = "Включить"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(skin.common.candidateTargetColor)
                setPadding(dp(8f), dp(2f), dp(6f), dp(2f))
                setOnClickListener { listener?.onClipRequestScreenshotAccess() }
            })
            infoBannerBar.addView(TextView(context).apply {
                text = "✕"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(skin.keyMarkColor)
                setPadding(dp(6f), dp(2f), dp(2f), dp(2f))
                setOnClickListener {
                    screenshotPromptDismissed = true
                    infoBannerBar.visibility = GONE
                }
            })
            return
        }

        infoBannerBar.visibility = GONE
    }

    private fun renderContextBar() {
        contextBar.removeAllViews()
        val entry = contextEntry ?: return
        contextBar.setPadding(dp(6f), dp(2f), dp(6f), dp(4f))
        contextBar.setBackgroundColor(skin.funcKeyBg.normalColor)

        if (entry.isImage) {
            val viewBtn = TextView(context).apply {
                text = "Просмотреть"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(skin.keyTextColor)
                gravity = Gravity.CENTER
                setOnClickListener { openFullView(entry) }
            }
            val pinBtn = TextView(context).apply {
                text = if (entry.pinned) "Открепить" else "Закрепить"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(skin.common.candidateTargetColor)
                gravity = Gravity.CENTER
                setOnClickListener { clipboardManager.togglePinAsync(entry.id) { refresh() } }
            }
            val shareBtn = TextView(context).apply {
                text = "Поделиться"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(skin.keyTextColor)
                gravity = Gravity.CENTER
                setOnClickListener {
                    contextEntry = null
                    contextBar.visibility = GONE
                    listener?.onClipImageShare(entry)
                }
            }
            val delBtn = TextView(context).apply {
                text = "Удалить"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(skin.common.deleteSwipeDangerColor)
                gravity = Gravity.CENTER
                setOnClickListener { clipboardManager.deleteEntryAsync(entry.id) { refresh() } }
            }
            val cancelBtn = TextView(context).apply {
                text = "✕"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(skin.keyMarkColor)
                gravity = Gravity.CENTER
                setPadding(dp(8f), 0, dp(6f), 0)
                setOnClickListener {
                    contextEntry = null
                    contextBar.visibility = GONE
                    renderGrid()
                }
            }
            contextBar.addView(viewBtn, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.15f))
            contextBar.addView(pinBtn, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            contextBar.addView(shareBtn, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f))
            contextBar.addView(delBtn, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.85f))
            contextBar.addView(cancelBtn)
            return
        }

        if (prefs.clipboardFullTextEnabled) {
            val fullBtn = TextView(context).apply {
                text = "Полный текст"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(skin.keyTextColor)
                gravity = Gravity.CENTER
                setOnClickListener { openFullView(entry) }
            }
            contextBar.addView(fullBtn, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.2f))
        }

        val pinBtn = TextView(context).apply {
            text = if (entry.pinned) "Открепить" else "Закрепить"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(skin.common.candidateTargetColor)
            gravity = Gravity.CENTER
            setOnClickListener { clipboardManager.togglePinAsync(entry.id) { refresh() } }
        }
        val delBtn = TextView(context).apply {
            text = "Удалить"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(skin.common.deleteSwipeDangerColor)
            gravity = Gravity.CENTER
            setOnClickListener { clipboardManager.deleteEntryAsync(entry.id) { refresh() } }
        }
        val cancelBtn = TextView(context).apply {
            text = "✕"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(skin.keyMarkColor)
            gravity = Gravity.CENTER
            setPadding(dp(10f), 0, dp(10f), 0)
            setOnClickListener {
                contextEntry = null
                contextBar.visibility = GONE
                renderGrid()
            }
        }

        contextBar.addView(pinBtn, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        contextBar.addView(delBtn, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.9f))
        contextBar.addView(cancelBtn)
    }

    private fun openFullView(entry: ClipEntry) {
        fullViewEntry = entry
        contextEntry = null
        contextBar.visibility = GONE
        confirmBar.visibility = GONE
        searchRow.visibility = GONE
        filterRow.visibility = GONE
        scrollView.visibility = GONE
        fullViewContainer.visibility = VISIBLE
        renderFullView()
    }

    private fun closeFullView() {
        fullViewEntry = null
        redactModeActive = false
        redactWorkingBitmap = null
        smartFragmentsEntry = null
        smartFragmentsList = emptyList()
        visionOcrEntry = null
        visionOcrResult = null
        visionOcrLoading = false
        fullViewContainer.visibility = GONE
        filterRow.visibility = VISIBLE
        scrollView.visibility = VISIBLE
    }

    fun openSmartFragmentsForEntry(entry: ClipEntry, entities: List<SmartEntity>) {
        smartFragmentsEntry = entry
        smartFragmentsList = entities
        fullViewEntry = null
        contextEntry = null
        contextBar.visibility = GONE
        confirmBar.visibility = GONE
        searchRow.visibility = GONE
        filterRow.visibility = GONE
        scrollView.visibility = GONE
        fullViewContainer.visibility = VISIBLE
        renderSmartFragmentsView()
    }

    private fun renderSmartFragmentsView() {
        fullViewContainer.removeAllViews()
        val entry = smartFragmentsEntry ?: return
        fullViewContainer.setPadding(dp(10f), dp(4f), dp(10f), dp(8f))

        val titleRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2f), 0, dp(2f), dp(6f))
        }
        titleRow.addView(TextView(context).apply {
            text = "✦ Умная вставка (${smartFragmentsList.size})"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(skin.common.candidateTargetColor)
        }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(TextView(context).apply {
            text = "Весь текст"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(skin.keyMarkColor)
            setPadding(dp(8f), dp(2f), dp(2f), dp(2f))
            setOnClickListener {
                closeFullView()
                listener?.onClipPaste(entry.text)
            }
        })
        fullViewContainer.addView(titleRow)

        val listScroll = ScrollView(context).apply { isVerticalScrollBarEnabled = true }
        val listCol = LinearLayout(context).apply { orientation = VERTICAL }
        val rad = skin.keyRadiusDp * density

        smartFragmentsList.forEach { entity ->
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = rad
                    setColor(skin.normalKeyBg.normalColor)
                    if (skin.isGlassKeys) {
                        setStroke(dp(0.75f).coerceAtLeast(1), skin.glassBorderTopColor)
                    }
                }
                setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
                layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(6f)
                }
                setOnClickListener {
                    closeFullView()
                    listener?.onClipFragmentPaste(entity.rawText)
                }
            }

            row.addView(TextView(context).apply {
                text = entity.type.iconGlyph
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER
                layoutParams = LayoutParams(dp(26f), ViewGroup.LayoutParams.WRAP_CONTENT)
            })

            val textCol = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dp(6f), 0, dp(8f), 0)
            }
            textCol.addView(TextView(context).apply {
                text = entity.type.titleRu
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(skin.keyMarkColor)
                includeFontPadding = false
            })
            textCol.addView(TextView(context).apply {
                text = entity.rawText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setTextColor(skin.keyTextColor)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
            })
            row.addView(textCol, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            row.addView(TextView(context).apply {
                text = "Вставить"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(skin.common.candidateTargetColor)
                setPadding(dp(6f), dp(4f), dp(2f), dp(4f))
            })

            listCol.addView(row)
        }
        listScroll.addView(listCol)
        fullViewContainer.addView(listScroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun openVisionOcrView(entry: ClipEntry) {
        visionOcrEntry = entry
        visionOcrResult = clipboardManager.visionOcr.getCached(entry)
        visionOcrLoading = visionOcrResult == null
        visionOcrTab = OcrSubTab.OBJECTS
        smartFragmentsEntry = null
        fullViewEntry = null
        contextEntry = null
        contextBar.visibility = GONE
        confirmBar.visibility = GONE
        searchRow.visibility = GONE
        filterRow.visibility = GONE
        scrollView.visibility = GONE
        fullViewContainer.visibility = VISIBLE
        renderVisionOcrView()

        if (visionOcrLoading) {
            val targetId = entry.id
            clipboardManager.recognizeImageOcrAsync(entry) { res ->
                if (visionOcrEntry?.id == targetId) {
                    visionOcrLoading = false
                    visionOcrResult = res
                    if (res.entities.isEmpty() && res.paragraphs.isNotEmpty()) {
                        visionOcrTab = OcrSubTab.PARAGRAPHS
                    }
                    renderVisionOcrView()
                }
            }
        }
    }

    private fun renderVisionOcrView() {
        fullViewContainer.removeAllViews()
        val entry = visionOcrEntry ?: return
        fullViewContainer.setPadding(dp(10f), dp(4f), dp(10f), dp(8f))

        val topRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2f), 0, dp(2f), dp(5f))
        }
        topRow.addView(TextView(context).apply {
            text = "Распознанный текст"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(skin.common.candidateTargetColor)
        }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topRow.addView(TextView(context).apply {
            text = "Вставить фото"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(skin.keyMarkColor)
            setPadding(dp(8f), dp(2f), dp(2f), dp(2f))
            setOnClickListener {
                closeFullView()
                listener?.onClipImagePaste(entry)
            }
        })
        fullViewContainer.addView(topRow)

        if (visionOcrLoading) {
            fullViewContainer.addView(TextView(context).apply {
                text = "Распознавание текста на устройстве (RU + EN)…"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(skin.keyMarkColor)
                gravity = Gravity.CENTER
                setPadding(dp(12f), dp(36f), dp(12f), dp(12f))
            }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            return
        }

        val res = visionOcrResult
        if (res == null || res.errorMessage != null || !res.hasText) {
            val msg = res?.errorMessage ?: "Текст на изображении не найден.\nВы можете вставить исходное изображение."
            fullViewContainer.addView(TextView(context).apply {
                text = msg
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(skin.keyMarkColor)
                gravity = Gravity.CENTER
                setPadding(dp(16f), dp(28f), dp(16f), dp(16f))
            }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            return
        }

        // Editable OCR text review & "Вставить весь текст"
        val editRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6f))
        }
        val editableOcr = EditText(context).apply {
            setText(res.fullText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(skin.keyTextColor)
            maxLines = 2
            background = GradientDrawable().apply {
                cornerRadius = dp(8f).toFloat()
                setColor(skin.normalKeyBg.normalColor)
            }
            setPadding(dp(8f), dp(5f), dp(8f), dp(5f))
        }
        val pasteAllBtn = TextView(context).apply {
            text = "Весь текст"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(10f), dp(7f), dp(10f), dp(7f))
            background = GradientDrawable().apply {
                cornerRadius = dp(8f).toFloat()
                setColor(skin.enterActionBg.normalColor)
            }
            layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(6f)
            }
            setOnClickListener {
                val finalTxt = editableOcr.text?.toString() ?: res.fullText
                if (finalTxt.isNotEmpty()) {
                    closeFullView()
                    listener?.onClipFragmentPaste(finalTxt)
                }
            }
        }
        editRow.addView(editableOcr, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        editRow.addView(pasteAllBtn)
        fullViewContainer.addView(editRow)

        // Sub-tabs showing ONLY really available categories (Объекты / Абзацы / Строки)
        val tabRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(5f))
        }
        val availableTabs = ArrayList<Pair<OcrSubTab, String>>()
        if (res.entities.isNotEmpty()) availableTabs.add(OcrSubTab.OBJECTS to "Объекты (${res.entities.size})")
        if (res.paragraphs.isNotEmpty()) availableTabs.add(OcrSubTab.PARAGRAPHS to "Абзацы (${res.paragraphs.size})")
        if (res.lines.isNotEmpty()) availableTabs.add(OcrSubTab.LINES to "Строки (${res.lines.size})")
        if (availableTabs.none { it.first == visionOcrTab } && availableTabs.isNotEmpty()) {
            visionOcrTab = availableTabs[0].first
        }
        availableTabs.forEach { (tab, label) ->
            val sel = visionOcrTab == tab
            tabRow.addView(TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = if (sel) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.SANS_SERIF
                setTextColor(if (sel) skin.common.candidateTargetColor else skin.keyMarkColor)
                setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
                if (sel) {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(8f).toFloat()
                        setColor(skin.normalKeyBg.normalColor)
                    }
                }
                layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = dp(4f)
                }
                setOnClickListener {
                    visionOcrTab = tab
                    renderVisionOcrView()
                }
            })
        }
        fullViewContainer.addView(tabRow)

        // Scrollable fragment rows
        val listScroll = ScrollView(context).apply { isVerticalScrollBarEnabled = true }
        val listCol = LinearLayout(context).apply { orientation = VERTICAL }
        val rad = skin.keyRadiusDp * density

        fun addPickRow(icon: String, subLabel: String, value: String) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = rad
                    setColor(skin.normalKeyBg.normalColor)
                }
                setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
                layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(5f)
                }
                setOnClickListener {
                    closeFullView()
                    listener?.onClipFragmentPaste(value)
                }
            }
            row.addView(TextView(context).apply {
                text = icon
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
                layoutParams = LayoutParams(dp(24f), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            val col = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dp(5f), 0, dp(6f), 0)
            }
            col.addView(TextView(context).apply {
                text = subLabel
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setTextColor(skin.keyMarkColor)
                includeFontPadding = false
            })
            col.addView(TextView(context).apply {
                text = value
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(skin.keyTextColor)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
            })
            row.addView(col, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(context).apply {
                text = "Вставить"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(skin.common.candidateTargetColor)
            })
            listCol.addView(row)
        }

        when (visionOcrTab) {
            OcrSubTab.OBJECTS -> res.entities.forEach { e -> addPickRow(e.type.iconGlyph, e.type.titleRu, e.rawText) }
            OcrSubTab.PARAGRAPHS -> res.paragraphs.forEachIndexed { idx, p -> addPickRow("¶", "Абзац ${idx + 1}", p) }
            OcrSubTab.LINES -> res.lines.forEachIndexed { idx, l -> addPickRow("≡", "Строка ${idx + 1}", l) }
        }

        listScroll.addView(listCol)
        fullViewContainer.addView(listScroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun renderFullView() {
        fullViewContainer.removeAllViews()
        val entry = fullViewEntry ?: return
        fullViewContainer.setPadding(dp(10f), dp(4f), dp(10f), dp(8f))

        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = skin.keyRadiusDp * density
                setColor(skin.normalKeyBg.normalColor)
                if (skin.isGlassKeys) {
                    setStroke(dp(0.75f).coerceAtLeast(1), skin.glassBorderTopColor)
                }
            }
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        }

        if (entry.isImage) {
            val previewIv = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(skin.funcKeyBg.normalColor)
            }
            val brushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FF14161A")
                style = Paint.Style.FILL
            }
            previewIv.setOnTouchListener { v, ev ->
                if (!redactModeActive) return@setOnTouchListener false
                val bmp = redactWorkingBitmap ?: return@setOnTouchListener false
                if (ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_MOVE) {
                    val vw = v.width.coerceAtLeast(1).toFloat()
                    val vh = v.height.coerceAtLeast(1).toFloat()
                    val scale = minOf(vw / bmp.width.toFloat(), vh / bmp.height.toFloat())
                    val dispW = bmp.width * scale
                    val dispH = bmp.height * scale
                    val offX = (vw - dispW) / 2f
                    val offY = (vh - dispH) / 2f
                    val bx = ((ev.x - offX) / scale).coerceIn(0f, bmp.width.toFloat())
                    val by = ((ev.y - offY) / scale).coerceIn(0f, bmp.height.toFloat())
                    val radius = (minOf(bmp.width, bmp.height) * 0.045f).coerceAtLeast(14f)
                    Canvas(bmp).drawCircle(bx, by, radius, brushPaint)
                    previewIv.invalidate()
                }
                true
            }
            card.addView(previewIv, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            val targetId = entry.id
            if (redactWorkingBitmap != null) {
                previewIv.setImageBitmap(redactWorkingBitmap)
            } else {
                thumbExecutor.execute {
                    val bmp = clipboardManager.repository.mediaStorage.decodeBoundedBitmap(entry.filePath, 960)
                    val mutable = bmp?.copy(Bitmap.Config.ARGB_8888, true)
                    mainHandler.post {
                        if (fullViewEntry?.id == targetId && mutable != null) {
                            redactWorkingBitmap = mutable
                            previewIv.setImageBitmap(mutable)
                        }
                    }
                }
            }

            val footer = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8f), 0, 0)
            }
            val badge = MediaClipboardStorage.shortBadgeForMime(entry.mimeType)
            val dims = if (entry.width > 0 && entry.height > 0) "${entry.width}×${entry.height}" else ""
            val infoTv = TextView(context).apply {
                text = if (redactModeActive) "Проведите пальцем по фото" else listOf(badge, dims, formatFileSize(entry.fileSize)).filter { it.isNotEmpty() }.joinToString(" • ")
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setTextColor(if (redactModeActive) skin.common.candidateTargetColor else skin.keyMarkColor)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val redactBtn = TextView(context).apply {
                text = if (redactModeActive) "Готово ✓" else "Замазать"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(if (redactModeActive) Color.WHITE else skin.keyTextColor)
                gravity = Gravity.CENTER
                setPadding(dp(9f), dp(5f), dp(9f), dp(5f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8f).toFloat()
                    setColor(if (redactModeActive) skin.common.candidateTargetColor else skin.funcKeyBg.normalColor)
                }
                layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = dp(5f)
                }
                setOnClickListener {
                    if (!redactModeActive) {
                        redactModeActive = true
                        renderFullView()
                    } else {
                        val bmp = redactWorkingBitmap
                        if (bmp != null) {
                            val mediaDir = File(context.filesDir, "clipboard_media").apply { mkdirs() }
                            val outFile = File(mediaDir, "redacted_${System.currentTimeMillis()}.png")
                            try {
                                FileOutputStream(outFile).use { fos ->
                                    bmp.compress(Bitmap.CompressFormat.PNG, 95, fos)
                                }
                                closeFullView()
                                listener?.onClipRedactedImagePaste(outFile.absolutePath)
                            } catch (_: Exception) {
                                redactModeActive = false
                                renderFullView()
                            }
                        } else {
                            redactModeActive = false
                            renderFullView()
                        }
                    }
                }
            }
            val pasteBtn = TextView(context).apply {
                text = "Вставить"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8f).toFloat()
                    setColor(skin.enterActionBg.normalColor)
                }
                setOnClickListener {
                    closeFullView()
                    listener?.onClipImagePaste(entry)
                }
            }
            val shareBtn = TextView(context).apply {
                text = "Поделиться"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(skin.common.candidateTargetColor)
                setPadding(dp(8f), dp(5f), dp(2f), dp(5f))
                setOnClickListener {
                    closeFullView()
                    listener?.onClipImageShare(entry)
                }
            }
            footer.addView(infoTv, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            footer.addView(redactBtn)
            footer.addView(pasteBtn)
            footer.addView(shareBtn)
            card.addView(footer)
        } else {
            val fullScroll = ScrollView(context).apply { isVerticalScrollBarEnabled = true }
            val fullTv = TextView(context).apply {
                text = if (prefs.clipboardHidePreview) "Предпросмотр скрыт в настройках конфиденциальности." else entry.text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(skin.keyTextColor)
                setLineSpacing(dp(2f).toFloat(), 1f)
            }
            fullScroll.addView(fullTv)
            card.addView(fullScroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

            val footer = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8f), 0, 0)
            }
            val meta = resolveAppMeta(entry.sourcePackage)
            val infoTv = TextView(context).apply {
                val srcName = meta?.label ?: entry.sourceLabel ?: "Источник неизвестен"
                text = "$srcName  •  ${formatRelativeTime(entry.createdAt)}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(skin.keyMarkColor)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val pasteBtn = TextView(context).apply {
                text = "Вставить"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(14f), dp(6f), dp(14f), dp(6f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8f).toFloat()
                    setColor(skin.enterActionBg.normalColor)
                }
                setOnClickListener {
                    closeFullView()
                    listener?.onClipPaste(entry.text)
                }
            }
            val closeBtn = TextView(context).apply {
                text = "Закрыть"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(skin.keyMarkColor)
                setPadding(dp(12f), dp(6f), dp(4f), dp(6f))
                setOnClickListener { closeFullView() }
            }
            footer.addView(infoTv, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            footer.addView(pasteBtn)
            footer.addView(closeBtn)
            card.addView(footer)
        }

        fullViewContainer.addView(card, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun renderConfirmBar() {
        confirmBar.removeAllViews()
        confirmBar.setPadding(dp(12f), dp(2f), dp(12f), dp(4f))
        confirmBar.setBackgroundColor(skin.funcKeyBg.normalColor)

        val prompt = TextView(context).apply {
            text = "Очистить историю буфера обмена? Закреплённые элементы будут сохранены"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(skin.keyTextColor)
            gravity = Gravity.CENTER_VERTICAL
        }
        val yesBtn = TextView(context).apply {
            text = "Очистить"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(skin.common.deleteSwipeDangerColor)
            setPadding(dp(12f), 0, dp(12f), 0)
            gravity = Gravity.CENTER
            setOnClickListener {
                confirmClearVisible = false
                confirmBar.visibility = GONE
                clipboardManager.clearUnpinnedAsync { refresh() }
            }
        }
        val noBtn = TextView(context).apply {
            text = "Отмена"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(skin.keyMarkColor)
            setPadding(dp(8f), 0, dp(4f), 0)
            gravity = Gravity.CENTER
            setOnClickListener {
                confirmClearVisible = false
                confirmBar.visibility = GONE
            }
        }

        confirmBar.addView(prompt, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        confirmBar.addView(yesBtn)
        confirmBar.addView(noBtn)
    }

    private fun renderGrid() {
        grid.removeAllViews()
        grid.setPadding(dp(8f), dp(2f), dp(8f), dp(8f))

        if (activeFilter == MediaFilter.TEMPLATES) {
            renderTemplatesGrid()
            return
        }
        if (activeFilter == MediaFilter.VAULT) {
            renderVaultGrid()
            return
        }
        if (activeFilter == MediaFilter.NOTEPAD) {
            renderNotepadGrid()
            return
        }

        val filtered = cachedEntries.filter { entry ->
            val tabOk = if (activeTab == Tab.PINNED) entry.pinned else !entry.pinned
            val filterOk = when (activeFilter) {
                MediaFilter.ALL -> true
                MediaFilter.TEXT -> !entry.isImage
                MediaFilter.IMAGES -> entry.isImage
                MediaFilter.TEMPLATES, MediaFilter.VAULT, MediaFilter.NOTEPAD -> false
            }
            val queryOk = if (searchQuery.isEmpty()) {
                true
            } else if (entry.isImage) {
                val src = entry.sourceLabel ?: ""
                val badge = MediaClipboardStorage.shortBadgeForMime(entry.mimeType)
                src.contains(searchQuery, ignoreCase = true) || badge.contains(searchQuery, ignoreCase = true)
            } else {
                entry.text.contains(searchQuery, ignoreCase = true)
            }
            tabOk && filterOk && queryOk
        }

        if (filtered.isEmpty()) {
            val emptyMsg = when {
                !prefs.clipboardEnabled && activeTab == Tab.RECENT ->
                    "Автосохранение буфера отключено в настройках."
                searchQuery.isNotEmpty() ->
                    "По запросу «$searchQuery» ничего не найдено."
                activeFilter == MediaFilter.IMAGES && activeTab == Tab.RECENT ->
                    "Нет сохранённых изображений.\nСкопируйте картинку в браузере/галерее или нажмите «+ Фото»."
                activeTab == Tab.PINNED ->
                    "Нет закреплённых записей.\nУдерживайте любую карточку в «Недавних», чтобы закрепить."
                else ->
                    "История буфера пуста.\nСкопируйте текст или изображение — они появятся здесь."
            }
            val empty = TextView(context).apply {
                text = emptyMsg
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(skin.keyMarkColor)
                gravity = Gravity.CENTER
                setPadding(dp(16f), dp(28f), dp(16f), dp(16f))
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    columnSpec = GridLayout.spec(0, 2, 1f)
                }
            }
            grid.addView(empty)
            return
        }

        val radiusPx = skin.keyRadiusDp * density
        val maxLinesPref = prefs.clipboardPreviewLines
        val showMetaRow = prefs.clipboardShowSource || prefs.clipboardShowTime
        val textCardHeightDp = when (maxLinesPref) {
            1 -> if (showMetaRow) 56f else 42f
            3 -> if (showMetaRow) 92f else 74f
            else -> if (showMetaRow) 74f else 56f
        }
        val imageCardHeightDp = if (showMetaRow) 114f else 92f

        filtered.forEach { entry ->
            if (entry.isImage) {
                grid.addView(buildImageCard(entry, radiusPx, imageCardHeightDp, showMetaRow))
            } else {
                grid.addView(buildTextCard(entry, radiusPx, textCardHeightDp, maxLinesPref, showMetaRow))
            }
        }
    }

    private fun buildImageCard(entry: ClipEntry, radiusPx: Float, cardHeightDp: Float, showMetaRow: Boolean): View {
        val isSelectedForAction = contextEntry?.id == entry.id
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = radiusPx
                setColor(skin.normalKeyBg.normalColor)
                if (isSelectedForAction) {
                    setStroke(dp(1.5f), skin.common.candidateTargetColor)
                } else if (skin.isGlassKeys) {
                    setStroke(dp(0.75f).coerceAtLeast(1), skin.glassBorderTopColor)
                }
            }
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(cardHeightDp)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4f), dp(4f), dp(4f), dp(4f))
            }
            setOnClickListener {
                if (contextEntry != null) {
                    contextEntry = null
                    contextBar.visibility = GONE
                    renderGrid()
                } else {
                    listener?.onClipImagePaste(entry)
                }
            }
            setOnLongClickListener {
                confirmClearVisible = false
                confirmBar.visibility = GONE
                contextEntry = entry
                renderContextBar()
                contextBar.visibility = VISIBLE
                renderGrid()
                true
            }
        }

        val thumbFrame = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = (skin.keyRadiusDp - 2f).coerceAtLeast(4f) * density
                setColor(skin.funcKeyBg.normalColor)
            }
            clipToOutline = true
        }

        val thumbIv = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        thumbFrame.addView(thumbIv)

        // Load thumbnail asynchronously via LruCache (never decodes full-res images in grid!)
        val pathToLoad = entry.thumbPath ?: entry.filePath
        if (!pathToLoad.isNullOrBlank()) {
            if (prefs.clipboardHidePreview) {
                thumbIv.scaleType = ImageView.ScaleType.CENTER
                thumbIv.setImageResource(R.drawable.ic_toolbar_clip)
                thumbIv.setColorFilter(skin.keyMarkColor)
            } else {
                val cached = thumbCache.get(pathToLoad)
                if (cached != null && !cached.isRecycled) {
                    thumbIv.setImageBitmap(cached)
                } else {
                    thumbIv.tag = pathToLoad
                    thumbExecutor.execute {
                        val bmp = decodeThumbSafe(pathToLoad)
                        if (bmp != null) {
                            thumbCache.put(pathToLoad, bmp)
                            mainHandler.post {
                                if (thumbIv.tag == pathToLoad) {
                                    thumbIv.setImageBitmap(bmp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Compact MIME badge (GIF / PNG / WEBP / JPG) in top-left corner
        val badgeText = MediaClipboardStorage.shortBadgeForMime(entry.mimeType)
        val badgeTv = TextView(context).apply {
            text = badgeText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            setPadding(dp(5f), dp(1.5f), dp(5f), dp(1.5f))
            background = GradientDrawable().apply {
                cornerRadius = dp(4f).toFloat()
                setColor(Color.parseColor("#99000000"))
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply { setMargins(dp(5f), dp(5f), 0, 0) }
        }
        thumbFrame.addView(badgeTv)

        if (entry.pinned) {
            val pinBadge = TextView(context).apply {
                text = "★"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setTextColor(Color.WHITE)
                setPadding(dp(5f), dp(1f), dp(5f), dp(1f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(4f).toFloat()
                    setColor(skin.common.candidateTargetColor)
                }
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END
                ).apply { setMargins(0, dp(5f), dp(5f), 0) }
            }
            thumbFrame.addView(pinBadge)
        }

        if (prefs.visionOcrEnabled && !prefs.clipboardHidePreview) {
            val cachedOcr = clipboardManager.visionOcr.getCached(entry)
            val ocrLabel = when {
                cachedOcr == null -> "Т Извлечь текст"
                cachedOcr.entities.isNotEmpty() -> "Т Найдено: ${cachedOcr.entities.size}"
                cachedOcr.hasText -> "Т Найден текст"
                else -> "Т Извлечь текст"
            }
            val ocrPill = TextView(context).apply {
                text = ocrLabel
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.WHITE)
                setPadding(dp(6f), dp(2.5f), dp(6f), dp(2.5f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(6f).toFloat()
                    setColor(if (cachedOcr?.hasText == true) skin.common.candidateTargetColor else Color.parseColor("#B3181A1F"))
                }
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.END
                ).apply { setMargins(0, 0, dp(5f), dp(5f)) }
                setOnClickListener { openVisionOcrView(entry) }
            }
            thumbFrame.addView(ocrPill)
        }

        card.addView(thumbFrame, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        if (showMetaRow) {
            card.addView(
                buildMetaRow(entry, includePinStar = false),
                LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
        return card
    }

    private fun buildTextCard(
        entry: ClipEntry,
        radiusPx: Float,
        cardHeightDp: Float,
        maxLinesPref: Int,
        showMetaRow: Boolean
    ): View {
        val isSelectedForAction = contextEntry?.id == entry.id
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = radiusPx
                setColor(skin.normalKeyBg.normalColor)
                if (isSelectedForAction) {
                    setStroke(dp(1.5f), skin.common.candidateTargetColor)
                } else if (skin.isGlassKeys) {
                    setStroke(dp(0.75f).coerceAtLeast(1), skin.glassBorderTopColor)
                }
            }
            setPadding(dp(10f), dp(8f), dp(10f), dp(7f))
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(cardHeightDp)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4f), dp(4f), dp(4f), dp(4f))
            }
            setOnClickListener {
                if (contextEntry != null) {
                    contextEntry = null
                    contextBar.visibility = GONE
                    renderGrid()
                } else {
                    listener?.onClipPaste(entry.text)
                }
            }
            setOnLongClickListener {
                confirmClearVisible = false
                confirmBar.visibility = GONE
                contextEntry = entry
                renderContextBar()
                contextBar.visibility = VISIBLE
                renderGrid()
                true
            }
        }

        val previewText = if (prefs.clipboardHidePreview) {
            "•••••• (${entry.text.length} симв.)"
        } else {
            entry.text.replace("\r\n", " ↵ ").replace("\n", " ↵ ").replace("\r", " ")
        }

        val bodyTv = TextView(context).apply {
            text = previewText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTextColor(skin.keyTextColor)
            maxLines = maxLinesPref
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            breakStrategy = android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE
            setLineSpacing(dp(1.5f).toFloat(), 1f)
        }
        card.addView(bodyTv, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Compact Smart Paste fragment badge (max 2 labels + "+N")
        if (prefs.smartPasteEnabled && prefs.smartPasteShowFragments && !prefs.clipboardHidePreview) {
            val smartPill = TextView(context).apply {
                visibility = GONE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(skin.common.candidateTargetColor)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                setPadding(dp(6f), dp(2f), dp(6f), dp(2f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(6f).toFloat()
                    setColor(skin.funcKeyBg.normalColor)
                }
                layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(4f)
                }
            }
            card.addView(smartPill)
            val entryHash = entry.contentHash
            smartPill.tag = entryHash
            clipboardManager.smartPasteAnalyzer.analyzeAsync(entry) { res ->
                if (smartPill.tag == entryHash && res.entities.isNotEmpty()) {
                    smartPill.text = clipboardManager.smartPasteAnalyzer.formatCompactSummary(res.entities)
                    smartPill.visibility = VISIBLE
                    smartPill.setOnClickListener {
                        openSmartFragmentsForEntry(entry, res.entities)
                    }
                    // Allow card to grow slightly to accommodate the compact badge cleanly
                    val lp = card.layoutParams as? GridLayout.LayoutParams
                    if (lp != null && lp.height < dp(cardHeightDp + 22f)) {
                        lp.height = dp(cardHeightDp + 22f)
                        card.layoutParams = lp
                    }
                }
            }
        }

        if (showMetaRow) {
            card.addView(
                buildMetaRow(entry, includePinStar = true),
                LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
        return card
    }

    private fun buildMetaRow(entry: ClipEntry, includePinStar: Boolean): View {
        val metaRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4f), 0, 0)
        }

        if (prefs.clipboardShowSource) {
            val appMeta = resolveAppMeta(entry.sourcePackage)
            val iconView = ImageView(context).apply {
                layoutParams = LayoutParams(dp(14f), dp(14f))
                scaleType = ImageView.ScaleType.FIT_CENTER
                if (appMeta?.icon != null) {
                    setImageDrawable(appMeta.icon)
                    clearColorFilter()
                } else {
                    setImageResource(R.drawable.ic_toolbar_clip)
                    setColorFilter(skin.keyMarkColor)
                }
            }
            val srcLabel = TextView(context).apply {
                text = appMeta?.label ?: entry.sourceLabel ?: "Источник неизвестен"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setTextColor(skin.keyMarkColor)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                setPadding(dp(5f), 0, dp(5f), 0)
            }
            metaRow.addView(iconView)
            metaRow.addView(srcLabel, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        } else {
            metaRow.addView(View(context), LayoutParams(0, 1, 1f))
        }

        if (prefs.clipboardShowTime || (includePinStar && entry.pinned)) {
            val rightText = buildString {
                if (prefs.clipboardShowTime) append(formatRelativeTime(entry.createdAt))
                if (includePinStar && entry.pinned) {
                    if (isNotEmpty()) append(" • ")
                    append("★")
                }
            }
            val timeTv = TextView(context).apply {
                text = rightText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setTextColor(if (includePinStar && entry.pinned) skin.common.candidateTargetColor else skin.keyMarkColor)
                maxLines = 1
                includeFontPadding = false
            }
            metaRow.addView(timeTv)
        }
        return metaRow
    }

    private fun decodeThumbSafe(path: String): Bitmap? {
        return try {
            val f = File(path)
            if (!f.exists()) return null
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeFile(f.absolutePath, opts)
        } catch (_: Exception) {
            null
        }
    }

    private fun renderTemplatesGrid() {
        val templates = prefs.getReplyTemplates()
        val rad = skin.keyRadiusDp * density
        if (templates.isEmpty()) {
            grid.addView(TextView(context).apply {
                text = "Нет шаблонов ответов.\nНажмите «Настроить шаблоны» ниже."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(skin.keyMarkColor)
                gravity = Gravity.CENTER
                setPadding(dp(12f), dp(18f), dp(12f), dp(10f))
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    columnSpec = GridLayout.spec(0, 2, 1f)
                }
            })
        } else {
            templates.forEach { (folder, body) ->
                val card = LinearLayout(context).apply {
                    orientation = VERTICAL
                    background = GradientDrawable().apply {
                        cornerRadius = rad
                        setColor(skin.normalKeyBg.normalColor)
                    }
                    setPadding(dp(10f), dp(7f), dp(10f), dp(7f))
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = 0
                        columnSpec = GridLayout.spec(0, 2, 1f)
                        setMargins(dp(4f), dp(3f), dp(4f), dp(3f))
                    }
                    setOnClickListener { listener?.onClipPaste(body) }
                }
                card.addView(TextView(context).apply {
                    text = "📁 $folder"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(skin.common.candidateTargetColor)
                })
                card.addView(TextView(context).apply {
                    text = body
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(skin.keyTextColor)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                })
                grid.addView(card)
            }
        }
        grid.addView(TextView(context).apply {
            text = "+ Настроить шаблоны ответов"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(skin.common.candidateTargetColor)
            gravity = Gravity.CENTER
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(0, 2, 1f)
            }
            setOnClickListener { listener?.onClipOpenTemplates() }
        })
    }

    private val vaultExpiryCheck = object : Runnable {
        override fun run() {
            if (activeFilter == MediaFilter.VAULT && vaultUnlocked && !prefs.isVaultUnlocked()) {
                vaultUnlocked = false
                renderGrid()
            }
            if (isAttachedToWindow) mainHandler.postDelayed(this, 250L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        mainHandler.post(vaultExpiryCheck)
    }

    override fun onDetachedFromWindow() {
        mainHandler.removeCallbacks(vaultExpiryCheck)
        super.onDetachedFromWindow()
    }

    private fun renderVaultGrid() {
        vaultUnlocked = prefs.isVaultUnlocked()
        val rad = skin.keyRadiusDp * density
        val items = prefs.getVaultItems()
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = rad
                setColor(skin.normalKeyBg.normalColor)
            }
            setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(0, 2, 1f)
                setMargins(dp(4f), dp(6f), dp(4f), dp(6f))
            }
        }
        if (!vaultUnlocked) {
            card.addView(TextView(context).apply {
                text = "🔒 Защищённый сейф (AES-256-GCM)"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(skin.keyTextColor)
                gravity = Gravity.CENTER
            })
            card.addView(TextView(context).apply {
                text = "Записей в сейфе: ${items.size}. Хранятся локально в Android Keystore."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(skin.keyMarkColor)
                gravity = Gravity.CENTER
                setPadding(0, dp(4f), 0, dp(10f))
            })
            card.addView(TextView(context).apply {
                text = "Открыть сейф по биометрии"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10f).toFloat()
                    setColor(skin.enterActionBg.normalColor)
                }
                setOnClickListener { listener?.onClipOpenVault() }
            })
            grid.addView(card)
            return
        }

        if (items.isEmpty()) {
            card.addView(TextView(context).apply {
                text = "Сейф пуст. Добавьте важные реквизиты или коды в настройках сейфа."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(skin.keyMarkColor)
                gravity = Gravity.CENTER
            })
            grid.addView(card)
        } else {
            items.forEach { secret ->
                val itemCard = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply {
                        cornerRadius = rad
                        setColor(skin.normalKeyBg.normalColor)
                    }
                    setPadding(dp(10f), dp(9f), dp(10f), dp(9f))
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = 0
                        columnSpec = GridLayout.spec(0, 2, 1f)
                        setMargins(dp(4f), dp(3f), dp(4f), dp(3f))
                    }
                    setOnClickListener {
                        if (prefs.isVaultUnlocked()) listener?.onClipPaste(secret) else renderVaultGrid()
                    }
                }
                itemCard.addView(TextView(context).apply {
                    text = "🔑  $secret"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(skin.keyTextColor)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                grid.addView(itemCard)
            }
        }
        grid.addView(TextView(context).apply {
            text = "🔒 Заблокировать сейф"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(skin.common.candidateTargetColor)
            gravity = Gravity.CENTER
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(0, 2, 1f)
            }
            setOnClickListener {
                vaultUnlocked = false
                renderGrid()
            }
        })
    }


    private fun renderNotepadGrid() {
        val rad = skin.keyRadiusDp * density
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = rad
                setColor(skin.normalKeyBg.normalColor)
            }
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(0, 2, 1f)
                setMargins(dp(4f), dp(4f), dp(4f), dp(4f))
            }
        }
        val noteInput = EditText(context).apply {
            hint = "Быстрый черновик / список (сохраняется автоматически)…"
            setText(prefs.quickNoteText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTextColor(skin.keyTextColor)
            setHintTextColor(skin.keyMarkColor)
            minLines = 3
            maxLines = 5
            gravity = Gravity.TOP or Gravity.START
            background = null
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    prefs.quickNoteText = s?.toString() ?: ""
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        card.addView(noteInput, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val btnRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(8f), 0, 0)
        }
        btnRow.addView(TextView(context).apply {
            text = "Очистить"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(skin.keyMarkColor)
            setPadding(dp(10f), dp(6f), dp(12f), dp(6f))
            setOnClickListener {
                prefs.quickNoteText = ""
                noteInput.setText("")
            }
        })
        btnRow.addView(TextView(context).apply {
            text = "Вставить в чат"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            setPadding(dp(14f), dp(6f), dp(14f), dp(6f))
            background = GradientDrawable().apply {
                cornerRadius = dp(8f).toFloat()
                setColor(skin.enterActionBg.normalColor)
            }
            setOnClickListener {
                val txt = prefs.quickNoteText
                if (txt.isNotBlank()) listener?.onClipPaste(txt)
            }
        })
        card.addView(btnRow)
        grid.addView(card)
    }


    fun release() {
        mainHandler.removeCallbacksAndMessages(null)
        if (!thumbExecutor.isShutdown) {
            thumbExecutor.shutdownNow()
        }
    }
}