package com.khayom.cocai

import android.app.Activity
import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var gameStateText: TextView
    private lateinit var summaryText: TextView
    private lateinit var resultText: TextView
    private lateinit var keyEdit: EditText
    private var sessionToggleButton: TextView? = null
    private var receiverRegistered = false

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            resultText.text = intent?.getStringExtra(CaptureOverlayService.EXTRA_RESULT)
                ?: "Натиҷаи нав дастрас шуд."
            refreshDashboard()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshDashboard()
        if (!receiverRegistered) {
            val filter = IntentFilter(CaptureOverlayService.ACTION_RESULT)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(resultReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(resultReceiver, filter)
            }
            receiverRegistered = true
        }
    }

    override fun onPause() {
        if (receiverRegistered) {
            unregisterReceiver(resultReceiver)
            receiverRegistered = false
        }
        super.onPause()
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            setPadding(0, 0, 0, dp(22))
            setBackgroundColor(BG)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(18))
            setBackgroundColor(BG)
        }
        scroll.addView(root)
        setContentView(scroll)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val mark = TextView(this).apply {
            text = "⚔"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(GREEN_DARK, 16)
            layoutParams = LinearLayout.LayoutParams(dp(54), dp(54))
        }
        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        titleBox.addView(label("COC AI AGENT", 21f, TEXT, true))
        titleBox.addView(label("Achievement intelligence", 12f, MUTED, false))
        header.addView(mark)
        header.addView(titleBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(label("v0.3.1", 11f, MUTED, true))
        root.addView(header)
        root.addView(space(18))

        statusText = label("", 12f, TEXT, false).apply {
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = rounded(CARD_ALT, 14, STROKE)
        }
        root.addView(statusText, matchWrap())
        root.addView(space(18))

        root.addView(sectionHeading("БОЗӢ", "Танҳо Clash of Clans-и расмӣ"))
        val gameCard = card()
        val gameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val gameIcon = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            try {
                setImageDrawable(packageManager.getApplicationIcon(CaptureOverlayService.ALLOWED_PACKAGE))
            } catch (_: Exception) {
                setImageResource(android.R.drawable.sym_def_app_icon)
            }
            layoutParams = LinearLayout.LayoutParams(dp(58), dp(58))
        }
        val gameInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        gameInfo.addView(label("Clash of Clans", 16f, TEXT, true))
        gameStateText = label("", 12f, MUTED, false)
        gameInfo.addView(gameStateText)
        gameRow.addView(gameIcon)
        gameRow.addView(gameInfo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        gameCard.addView(gameRow, matchWrap())
        gameCard.addView(actionButton("КУШОДАНИ БОЗӢ", BLUE, { openClashOfClans() }))
        root.addView(gameCard, matchWrap())
        root.addView(space(16))

        root.addView(sectionHeading("ИДОРАКУНӢ", "Маълумот нигоҳ дошта мешавад; STOP онро пок намекунад"))
        val sessionCard = card()
        val toggleButton = actionButton(
            "▶  START", GREEN, { toggleCaptureSession() },
            textSize = 15f,
            params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)
            )
        )
        sessionToggleButton = toggleButton
        sessionCard.addView(toggleButton)
        sessionCard.addView(space(10))
        sessionCard.addView(label(
            "START: бозиро мекушояд, профилро меёбад ва рӯйхати Achievement-ро худкор скролл мекунад. STOP: танҳо сессияро таваққуф медиҳад; базаи ҷамъшуда пок намешавад.\n\nБарои тап ва скролл Accessibility як бор фаъол карда мешавад. Android барои сабти экран дар ҳар START иҷозат мепурсад. Ҷанг ва анҷом додани Achievement-ҳо ҳанӯз автоматӣ нестанд.",
            12f, MUTED, false
        ))
        root.addView(sessionCard, matchWrap())
        root.addView(space(16))

        root.addView(sectionHeading("ХОТИРАИ ACHIEVEMENT", "Пешрафт байни STOP ва START боқӣ мемонад"))
        val memoryCard = card()
        summaryText = label("", 13f, TEXT, false).apply { setLineSpacing(dp(3).toFloat(), 1f) }
        memoryCard.addView(summaryText)
        memoryCard.addView(space(10))
        memoryCard.addView(actionButton("ПОК КАРДАНИ ТАЪРИХ", CARD_BUTTON, {
            AchievementStore.clear(this)
            resultText.text = "Таърихи Achievement пок карда шуд."
            refreshDashboard()
            toast("Таърихи Achievement пок карда шуд.")
        }, textColor = MUTED))
        root.addView(memoryCard, matchWrap())
        root.addView(space(16))

        root.addView(sectionHeading("НАТИҶАИ ОХИРИН", "Таҳлил ва хулосаи DeepSeek"))
        val resultsCard = card()
        resultText = label("Ҳоло натиҷаи скан мавҷуд нест.", 13f, TEXT, false).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        resultsCard.addView(resultText)
        root.addView(resultsCard, matchWrap())
        root.addView(space(16))

        root.addView(sectionHeading("DEEPSEEK API", "Калид дар дастгоҳ рамзгузорӣ мешавад; тасвири буридашуда ба DeepSeek фиристода мешавад"))
        val apiCard = card()
        keyEdit = EditText(this).apply {
            hint = "DeepSeek API key"
            setSingleLine(true)
            textSize = 14f
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            setPadding(dp(12), dp(11), dp(12), dp(11))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            background = rounded(CARD_ALT, 10, STROKE)
        }
        apiCard.addView(keyEdit, matchWrap())
        apiCard.addView(space(8))
        val apiButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        apiButtons.addView(actionButton("ЗАХИРА КАРДАН", BLUE, {
            val value = keyEdit.text?.toString().orEmpty().trim()
            if (value.isBlank()) {
                toast("API Key холӣ аст.")
            } else {
                try {
                    SecureKeyStore.save(this, value)
                    keyEdit.setText("")
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                        .hideSoftInputFromWindow(keyEdit.windowToken, 0)
                    toast("API Key рамзгузорӣ шуд.")
                    refreshDashboard()
                } catch (e: Exception) {
                    toast("Хатои нигоҳдории калид: ${e.message}")
                }
            }
        }, textSize = 11f), LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(5) })
        apiButtons.addView(actionButton("ҲАЗФИ KEY", CARD_BUTTON, {
            SecureKeyStore.delete(this)
            keyEdit.setText("")
            refreshDashboard()
            toast("API Key ҳазф шуд.")
        }, textColor = MUTED, textSize = 11f,
            params = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(5) }))
        apiCard.addView(apiButtons)
        apiCard.addView(space(8))
        apiCard.addView(actionButton("САНҶИШИ ПАЙВАСТШАВӢ", CARD_BUTTON, {
            val typedKey = keyEdit.text?.toString().orEmpty().trim()
            val apiKey = typedKey.ifBlank { SecureKeyStore.read(this).orEmpty() }
            if (apiKey.isBlank()) {
                toast("Аввал DeepSeek API Key-ро ворид ва захира кун.")
            } else {
                statusText.text = "DeepSeek: санҷиши DNS, API Key ва модели Vision..."
                Thread {
                    val outcome = try {
                        DeepSeekClient.testConnection(apiKey)
                    } catch (e: Exception) {
                        "Санҷиш ноком шуд: ${e.message ?: "хатои номаълум"}"
                    }
                    runOnUiThread {
                        statusText.text = outcome
                        resultText.text = outcome
                    }
                }.start()
            }
        }, textColor = BLUE_TEXT, textSize = 11f))
        root.addView(apiCard, matchWrap())
        root.addView(space(16))

        val safety = label(
            "🔒  GEMS LOCK: ON    •    READ-ONLY    •    30 FPS\nКристаллҳо танҳо ҳамчун мукофот хонда мешаванд. Харҷ, Upgrade ва пахши тугмаҳои бозӣ вуҷуд надорад.",
            12f, GREEN_TEXT, true
        ).apply {
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = rounded(GREEN_DARK, 13)
        }
        root.addView(safety, matchWrap())
    }

    private fun refreshDashboard() {
        if (!::statusText.isInitialized) return
        val installed = isClashInstalled()
        val usage = hasUsageAccess()
        val overlay = Settings.canDrawOverlays(this)
        val accessibility = GameAutomationAccessibilityService.isEnabled(this)
        val key = SecureKeyStore.hasKey(this)
        val sessionPrefs = getSharedPreferences(CaptureOverlayService.SESSION_PREFS, MODE_PRIVATE)
        val running = sessionPrefs.getBoolean(CaptureOverlayService.KEY_CAPTURE_ACTIVE, false)

        statusText.text = buildString {
            append(if (running) "●  СКАНИ ФАЪОЛ" else "●  СКАНИ ТАВАҚҚУФЁФТА")
            appendLine()
            appendLine("Clash of Clans: ${if (installed) "насб шудааст" else "насб нест"}")
            appendLine("Screen / Overlay: ${if (usage && overlay) "иҷозат дода шудааст" else "иҷозат лозим аст"}")
            appendLine("Auto tap / scroll: ${if (accessibility) "фаъол" else "Accessibility бояд фаъол шавад"}")
            append("DeepSeek: ${if (key) "API Key захира шудааст" else "API Key ворид нашудааст"}")
        }
        gameStateText.text = if (installed) "Насб шудааст • бастаи ягонаи иҷозатдодашуда"
            else "Дар телефон ёфт нашуд"
        if (::summaryText.isInitialized) summaryText.text = AchievementStore.dashboardSummary(this)
        if (::resultText.isInitialized) {
            resultText.text = getSharedPreferences("coc_ai_results", MODE_PRIVATE)
                .getString("last_analysis", "Ҳоло натиҷаи скан мавҷуд нест.")
                ?: "Ҳоло натиҷаи скан мавҷуд нест."
        }
        sessionToggleButton?.apply {
            text = if (running) "■  STOP" else "▶  START"
            background = rounded(if (running) RED else GREEN, 11)
        }
    }

    private fun startCaptureFlow() {
        if (!SecureKeyStore.hasKey(this)) {
            toast("Аввал DeepSeek API Key-ро дар барнома ворид ва захира кун.")
            return
        }
        if (!isClashInstalled()) {
            toast("Clash of Clans насб нест.")
            return
        }
        if (!hasUsageAccess()) {
            toast("Барои маҳдуд кардани скан ба Clash of Clans, Usage Access-ро фаъол кун.")
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("Барои тугмаи шинокунанда Display over other apps-ро иҷозат деҳ.")
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
            return
        }
        if (!GameAutomationAccessibilityService.isEnabled(this)) {
            toast("Барои тап ва скролли автоматӣ COC AI Agent-ро дар Accessibility фаъол кун.")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    private fun toggleCaptureSession() {
        val running = getSharedPreferences(CaptureOverlayService.SESSION_PREFS, MODE_PRIVATE)
            .getBoolean(CaptureOverlayService.KEY_CAPTURE_ACTIVE, false)
        if (running) stopCaptureOnly() else startCaptureFlow()
    }

    private fun stopCaptureOnly() {
        val prefs = getSharedPreferences(CaptureOverlayService.SESSION_PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(CaptureOverlayService.KEY_CAPTURE_ACTIVE, false)) {
            toast("Скан аллакай таваққуф ёфтааст. Маълумоти қаблӣ нигоҳ дошта шудааст.")
            return
        }
        startService(Intent(this, CaptureOverlayService::class.java)
            .setAction(CaptureOverlayService.ACTION_STOP_CAPTURE))
        prefs.edit().putBoolean(CaptureOverlayService.KEY_CAPTURE_ACTIVE, false).apply()
        toast("Скан таваққуф ёфт. Achievement-ҳо дар хотира монданд.")
        sessionToggleButton?.apply {
            text = "▶  START"
            background = rounded(GREEN, 11)
        }
        statusText.text = statusText.text.toString().replace("●  СКАНИ ФАЪОЛ", "●  СКАНИ ТАВАҚҚУФЁФТА")
    }

    private fun openClashOfClans() {
        if (!isClashInstalled()) {
            toast("Clash of Clans-и расмӣ дар телефон насб нест.")
            return
        }
        val launch = packageManager.getLaunchIntentForPackage(CaptureOverlayService.ALLOWED_PACKAGE)
        if (launch == null) toast("Бозӣ кушода нашуд.") else startActivity(launch)
    }

    private fun isClashInstalled(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(
                CaptureOverlayService.ALLOWED_PACKAGE,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(CaptureOverlayService.ALLOWED_PACKAGE, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        return appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    @Deprecated("MediaProjection requires the system consent result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            toast("Иҷозати сабти экран дода нашуд. Маълумоти қаблӣ бетағйир монд.")
            return
        }
        val service = Intent(this, CaptureOverlayService::class.java).apply {
            action = CaptureOverlayService.ACTION_START_CAPTURE
            putExtra(CaptureOverlayService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureOverlayService.EXTRA_PROJECTION_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)

        // Open the allowlisted game after the user grants screen-capture consent.
        val gameIntent = packageManager.getLaunchIntentForPackage(CaptureOverlayService.ALLOWED_PACKAGE)
        if (gameIntent != null) startActivity(gameIntent)
        finish()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_START_FROM_OVERLAY) startCaptureFlow()
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(15), dp(15), dp(15), dp(15))
        background = rounded(CARD, 16, STROKE)
    }

    private fun sectionHeading(title: String, subtitle: String): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(label(title, 12f, BLUE_TEXT, true))
        box.addView(label(subtitle, 11f, MUTED, false))
        return box.also { it.layoutParams = matchWrap() }
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun actionButton(
        textValue: String,
        color: Int,
        action: () -> Unit,
        textColor: Int = Color.WHITE,
        textSize: Float = 13f,
        params: LinearLayout.LayoutParams? = null
    ): TextView = TextView(this).apply {
        text = textValue
        this.textSize = textSize
        setTextColor(textColor)
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(10), dp(11), dp(10), dp(11))
        background = rounded(color, 11)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
        layoutParams = params ?: matchWrap()
    }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun space(heightDp: Int): View =
        View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(heightDp)) }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(7) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        const val REQUEST_CAPTURE = 4001
        const val ACTION_START_FROM_OVERLAY = "com.khayom.cocai.START_FROM_OVERLAY"

        private val BG = Color.rgb(10, 17, 29)
        private val CARD = Color.rgb(20, 31, 48)
        private val CARD_ALT = Color.rgb(28, 42, 61)
        private val CARD_BUTTON = Color.rgb(42, 57, 75)
        private val STROKE = Color.rgb(47, 67, 91)
        private val TEXT = Color.rgb(242, 246, 252)
        private val MUTED = Color.rgb(165, 180, 198)
        private val BLUE = Color.rgb(39, 106, 181)
        private val BLUE_TEXT = Color.rgb(127, 183, 245)
        private val GREEN = Color.rgb(24, 139, 76)
        private val GREEN_DARK = Color.rgb(19, 67, 47)
        private val GREEN_TEXT = Color.rgb(156, 238, 190)
        private val RED = Color.rgb(170, 55, 55)
    }
}
