package com.almi.ai.ui.body

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.almi.ai.data.preferences.BodyMeasurePoint
import com.almi.ai.data.preferences.BodyProfile
import com.almi.ai.data.preferences.BodyProfileStore
import com.almi.ai.data.preferences.BodySideMeasurement
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private const val PRO_CM_PER_INCH = 2.54f
private const val PRO_KG_PER_POUND = 0.45359237f
private const val PRO_HEADER = 0xFF163E69.toInt()
private const val PRO_PANEL = 0xF51B4775.toInt()
private const val PRO_PANEL_SOFT = 0xF21A416D.toInt()
private const val PRO_BLUE = 0xFF79BEFF.toInt()
private const val PRO_RED = 0xFFFF4050.toInt()
private const val PRO_TEXT_SOFT = 0xFFD9EAFB.toInt()

/**
 * High-fidelity tailoring measurement surface.
 *
 * The important architectural difference from the previous screen is that every visible annotation
 * is drawn by one Canvas overlay. There are no 15 independent hotspot Views re-laying out every
 * frame. A single live Filament skeleton projection drives the dots, labels and guides, which keeps
 * navigation smooth while the model rotates, zooms and morphs.
 */
@AndroidEntryPoint
class TailorProMeasurementActivity : ComponentActivity() {
    @Inject lateinit var bodyProfileStore: BodyProfileStore

    private lateinit var runtime: PersistentFilamentRuntime
    private lateinit var surfaceView: SurfaceView
    private lateinit var overlay: TailorOverlay
    private lateinit var topBar: View
    private lateinit var weightDock: View
    private lateinit var countView: TextView
    private lateinit var progressView: ProProgressView
    private lateinit var weightInput: EditText
    private lateinit var editor: LinearLayout
    private lateinit var editorTitle: TextView
    private lateinit var editorInput: EditText
    private lateinit var editorSecondaryInput: EditText
    private lateinit var editorPrimaryLabel: TextView
    private lateinit var editorSecondaryLabel: TextView
    private lateinit var editorSecondaryRow: LinearLayout

    private var profile = BodyProfile()
    private var language = "ar"
    private var projection: BodyScreenProjection? = null
    private var selectedTarget: ProTailorTarget? = null
    private var introCompleted = false

    private val layoutScale: Float by lazy {
        val w = resources.configuration.screenWidthDp
        val h = resources.configuration.screenHeightDp
        when {
            w < 350 || h < 650 -> .84f
            w < 380 || h < 720 -> .90f
            w < 420 || h < 800 -> .95f
            else -> 1f
        }
    }

    private val typeScale: Float by lazy {
        val w = resources.configuration.screenWidthDp
        when {
            w < 350 -> .90f
            w < 390 -> .95f
            else -> 1f
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        language = BodyMeasurementContract.language(intent)
        profile = BodyMeasurementContract.readProfile(intent)

        window.statusBarColor = PRO_HEADER
        window.navigationBarColor = PRO_HEADER
        window.isNavigationBarContrastEnforced = false
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF1D507E.toInt()) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        surfaceView = SurfaceView(this).apply {
            keepScreenOn = true
            setZOrderOnTop(false)
            background = null
        }
        root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))

        overlay = TailorOverlay()
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        editor = buildEditor().apply {
            visibility = View.GONE
            elevation = dp(12).toFloat()
        }
        root.addView(
            editor,
            FrameLayout.LayoutParams(dp(146), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(150)
                rightMargin = dp(8)
            },
        )

        topBar = buildTopBar().apply {
            visibility = View.INVISIBLE
            alpha = 0f
        }
        root.addView(
            topBar,
            FrameLayout.LayoutParams(-1, dp(118)).apply { gravity = Gravity.TOP },
        )

        weightDock = buildWeightDock().apply {
            visibility = View.INVISIBLE
            alpha = 0f
        }
        root.addView(
            weightDock,
            FrameLayout.LayoutParams(-1, dp(74)).apply {
                gravity = Gravity.BOTTOM
                leftMargin = dp(10)
                rightMargin = dp(10)
                bottomMargin = dp(5)
            },
        )

        setContentView(root)
        WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        ViewCompat.requestApplyInsets(root)

        runtime = PersistentFilamentRuntime(
            context = this,
            surfaceView = surfaceView,
            onStateChanged = { state -> runOnUiThread { renderState(state) } },
            onProjectionChanged = { value ->
                projection = value
                runOnUiThread { overlay.invalidate() }
            },
        )
        runtime.initialize()
        applyShape()
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        if (::runtime.isInitialized) runtime.start()
    }

    override fun onPause() {
        if (::runtime.isInitialized) runtime.stop()
        super.onPause()
    }

    private fun buildTopBar(): View {
        val bar = FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(PRO_HEADER) }
            setPadding(dp(12), dp(6), dp(12), dp(5))
        }

        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        titleBlock.addView(text("ALMI / FILAMENT", 12f, 0xFFA8D3FF.toInt(), true).apply { gravity = Gravity.CENTER })
        titleBlock.addView(
            text(if (language == "ar") "قياسات جسمك" else "Your measurements", 24f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(1), 0, 0)
            },
        )
        bar.addView(
            titleBlock,
            FrameLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(5)
            },
        )

        val done = text(if (language == "ar") "✓  تم" else "✓  Done", 14f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = rounded(0xD9275685.toInt(), 99f, 0x668FBCE6)
            setOnClickListener {
                persistSideMeasurements()
                setResult(Activity.RESULT_OK, BodyMeasurementContract.resultIntent(profile))
                finish()
            }
        }
        bar.addView(
            done,
            FrameLayout.LayoutParams(dp(69), dp(37)).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(4)
            },
        )

        val progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        countView = text("0/${ProTailorTarget.entries.size}", 12f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = rounded(0xE92B5A88.toInt(), 12f)
        }
        progressRow.addView(countView, LinearLayout.LayoutParams(dp(54), dp(30)))
        progressView = ProProgressView()
        progressRow.addView(progressView, LinearLayout.LayoutParams(dp(118), dp(13)).apply { marginStart = dp(9) })
        bar.addView(
            progressRow,
            FrameLayout.LayoutParams(-2, dp(31)).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(1)
            },
        )
        return bar
    }

    private fun buildWeightDock(): View {
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rounded(PRO_PANEL_SOFT, 18f, 0x708FB5D8)
            elevation = dp(6).toFloat()
        }

        val label = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        label.addView(text(if (language == "ar") "الوزن" else "Weight", 15f, Color.WHITE, true))
        label.addView(text(if (language == "ar") "يتفاعل المجسم مباشرة" else "Twin reacts immediately", 8.5f, PRO_TEXT_SOFT, false))
        dock.addView(label, LinearLayout.LayoutParams(0, -2, 1f))

        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(0xE0183C65.toInt(), 11f, 0x7089B3D8)
        }
        weightInput = EditText(this).apply {
            textSize = scaledText(18.5f)
            setTextColor(Color.WHITE)
            setHintTextColor(0xFFABC5DF.toInt())
            hint = "80"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = EditorInfo.IME_ACTION_DONE
            gravity = Gravity.CENTER
            setSingleLine(true)
            setPadding(dp(3), 0, 0, 0)
            background = null
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_DONE) {
                    commitWeight()
                    true
                } else false
            }
        }
        shell.addView(weightInput, LinearLayout.LayoutParams(dp(52), dp(40)))
        shell.addView(text("kg", 9f, Color.WHITE, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(26), dp(40)))
        dock.addView(shell, LinearLayout.LayoutParams(dp(78), dp(40)).apply { marginStart = dp(6) })

        val cancel = text("×", 17f, Color.WHITE, false).apply {
            gravity = Gravity.CENTER
            background = rounded(0xD01A416B.toInt(), 11f, 0x6385ACD0)
            setOnClickListener { cancelWeightEdit() }
        }
        dock.addView(cancel, LinearLayout.LayoutParams(dp(35), dp(40)).apply { marginStart = dp(5) })

        val confirm = text("✓", 17f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = rounded(PRO_BLUE, 11f)
            setOnClickListener { commitWeight() }
        }
        dock.addView(confirm, LinearLayout.LayoutParams(dp(38), dp(40)).apply { marginStart = dp(5) })
        return dock
    }

    private fun buildEditor(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(7), dp(8), dp(7))
        background = rounded(PRO_PANEL, 14f, 0x728FB5D8)

        editorTitle = text("", 11.5f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        addView(editorTitle, LinearLayout.LayoutParams(-1, dp(20)))

        val primary = editorRow().also { row ->
            editorPrimaryLabel = text("", 8.5f, PRO_TEXT_SOFT, true).apply { gravity = Gravity.CENTER }
            row.addView(editorPrimaryLabel, LinearLayout.LayoutParams(dp(31), dp(37)))
            editorInput = editorInput()
            row.addView(editorInput, LinearLayout.LayoutParams(0, dp(37), 1f))
            row.addView(text("cm", 9f, Color.WHITE, false).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(27), dp(37)))
        }
        addView(primary, LinearLayout.LayoutParams(-1, dp(37)).apply { topMargin = dp(5) })

        editorSecondaryRow = editorRow().apply { visibility = View.GONE }
        editorSecondaryLabel = text("", 8.5f, PRO_TEXT_SOFT, true).apply { gravity = Gravity.CENTER }
        editorSecondaryRow.addView(editorSecondaryLabel, LinearLayout.LayoutParams(dp(31), dp(37)))
        editorSecondaryInput = editorInput()
        editorSecondaryRow.addView(editorSecondaryInput, LinearLayout.LayoutParams(0, dp(37), 1f))
        editorSecondaryRow.addView(text("cm", 9f, Color.WHITE, false).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(27), dp(37)))
        addView(editorSecondaryRow, LinearLayout.LayoutParams(-1, dp(37)).apply { topMargin = dp(4) })

        val actions = LinearLayout(this@TailorProMeasurementActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val cancel = text("×", 18f, Color.WHITE, false).apply {
            gravity = Gravity.CENTER
            background = rounded(0xD01A416B.toInt(), 10f, 0x6284AAD0)
            setOnClickListener { closeEditor() }
        }
        actions.addView(cancel, LinearLayout.LayoutParams(0, dp(32), 1f))
        val ok = text("✓", 18f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = rounded(PRO_BLUE, 10f)
            setOnClickListener { saveMeasurement() }
        }
        actions.addView(ok, LinearLayout.LayoutParams(0, dp(32), 1f).apply { marginStart = dp(5) })
        addView(actions, LinearLayout.LayoutParams(-1, dp(32)).apply { topMargin = dp(5) })
    }

    private fun editorRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(0xE01A416B.toInt(), 10f, 0x5F84A9CE)
    }

    private fun editorInput() = EditText(this).apply {
        textSize = scaledText(15.5f)
        setTextColor(Color.WHITE)
        setHintTextColor(0xFF9EB7D1.toInt())
        hint = "0"
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        imeOptions = EditorInfo.IME_ACTION_DONE
        gravity = Gravity.CENTER
        setSingleLine(true)
        setPadding(dp(2), 0, dp(2), 0)
        background = null
        setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) {
                saveMeasurement()
                true
            } else false
        }
    }

    private fun renderState(state: BodyRendererState) {
        when (state) {
            BodyRendererState.LOADING -> Unit
            BodyRendererState.READY -> if (!introCompleted) {
                runtime.playIntroSpin(2_250L) { revealUi() }
            }
            BodyRendererState.ERROR -> Toast.makeText(
                this,
                if (language == "ar") "تعذر عرض المجسم ثلاثي الأبعاد" else "Unable to render the 3D body",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun revealUi() {
        if (introCompleted || isFinishing) return
        introCompleted = true
        topBar.visibility = View.VISIBLE
        weightDock.visibility = View.VISIBLE
        topBar.animate().alpha(1f).setDuration(220L).start()
        weightDock.animate().alpha(1f).setDuration(220L).start()
        overlay.bodyReady = true
        overlay.revealedCount = 0
        ProTailorTarget.entries.forEachIndexed { index, _ ->
            overlay.postDelayed({
                if (!isFinishing) {
                    overlay.revealedCount = index + 1
                    overlay.invalidate()
                }
            }, 170L + index * 82L)
        }
    }

    private fun openEditor(target: ProTailorTarget) {
        if (!introCompleted) return
        selectedTarget = target
        overlay.selectedTarget = target
        editorTitle.text = target.title(language)
        val sides = target.sideKeys()
        if (sides != null) {
            editorPrimaryLabel.visibility = View.VISIBLE
            editorSecondaryRow.visibility = View.VISIBLE
            editorPrimaryLabel.text = if (language == "ar") "يمين" else "R"
            editorSecondaryLabel.text = if (language == "ar") "يسار" else "L"
            val fallback = target.valueCm(profile)
            editorInput.setText(profile.sideMeasurementsInches[sides.first]?.times(PRO_CM_PER_INCH)?.let(::formatNumber) ?: fallback?.let(::formatNumber).orEmpty())
            editorSecondaryInput.setText(profile.sideMeasurementsInches[sides.second]?.times(PRO_CM_PER_INCH)?.let(::formatNumber) ?: fallback?.let(::formatNumber).orEmpty())
        } else {
            editorPrimaryLabel.visibility = View.GONE
            editorSecondaryRow.visibility = View.GONE
            editorInput.setText(target.valueCm(profile)?.let(::formatNumber).orEmpty())
        }
        editor.visibility = View.VISIBLE
        runtime.focusOn(target.focusY, target.focusDistance)
        positionEditor(target)
    }

    private fun positionEditor(target: ProTailorTarget) {
        editor.post {
            val anchor = anchorFor(target)
            val h = if (target.sideKeys() != null) dp(150) else dp(108)
            val minTop = dp(124)
            val maxTop = (overlay.height - dp(84) - h).coerceAtLeast(minTop)
            val desired = (overlay.height * anchor.y - h * .38f).roundToInt().coerceIn(minTop, maxTop)
            val lp = editor.layoutParams as FrameLayout.LayoutParams
            lp.gravity = Gravity.TOP or if (anchor.x > .53f) Gravity.START else Gravity.END
            lp.leftMargin = dp(8)
            lp.rightMargin = dp(8)
            lp.topMargin = desired
            editor.layoutParams = lp
        }
    }

    private fun closeEditor() {
        selectedTarget = null
        overlay.selectedTarget = null
        editor.visibility = View.GONE
        editorInput.clearFocus()
        editorSecondaryInput.clearFocus()
        hideKeyboard()
        runtime.resetFocus()
        overlay.invalidate()
    }

    private fun saveMeasurement() {
        val target = selectedTarget ?: return
        val sides = target.sideKeys()
        if (sides != null) {
            val right = editorInput.text.toString().toFloatOrNull()?.takeIf { it in 1f..300f } ?: return
            val left = editorSecondaryInput.text.toString().toFloatOrNull()?.takeIf { it in 1f..300f } ?: return
            val point = target.point ?: return
            profile = profile.copy(
                measurementsInches = profile.measurementsInches + (point to ((right + left) / 2f / PRO_CM_PER_INCH)),
                sideMeasurementsInches = profile.sideMeasurementsInches + mapOf(
                    sides.first to right / PRO_CM_PER_INCH,
                    sides.second to left / PRO_CM_PER_INCH,
                ),
            )
        } else {
            val cm = editorInput.text.toString().toFloatOrNull()?.takeIf { it in 1f..300f } ?: return
            profile = if (target == ProTailorTarget.HEIGHT) {
                profile.copy(heightInches = cm / PRO_CM_PER_INCH, hasExplicitHeight = true)
            } else {
                val point = target.point ?: return
                profile.copy(measurementsInches = profile.measurementsInches + (point to cm / PRO_CM_PER_INCH))
            }
        }
        applyShape()
        refreshUi()
        closeEditor()
    }

    private fun commitWeight() {
        val kg = weightInput.text.toString().toFloatOrNull()?.takeIf { it in 20f..320f } ?: return
        profile = profile.copy(weightPounds = kg / PRO_KG_PER_POUND, hasExplicitWeight = true)
        weightInput.clearFocus()
        hideKeyboard()
        applyShape()
        refreshUi()
        runtime.resetFocus()
    }

    private fun cancelWeightEdit() {
        if (profile.hasExplicitWeight) weightInput.setText(formatNumber(profile.weightPounds * PRO_KG_PER_POUND)) else weightInput.text?.clear()
        weightInput.clearFocus()
        hideKeyboard()
    }

    private fun applyShape() {
        if (!::runtime.isInitialized) return
        val shape = BodyShapeSolver.solve(profile)
        runtime.updateBodyShape(shape.widthScale, shape.heightScale, shape.depthScale)
        runtime.updateProfile(profile)
    }

    private fun refreshUi() {
        if (!::countView.isInitialized) return
        val total = ProTailorTarget.entries.size
        val completed = ProTailorTarget.entries.count { it.valueCm(profile) != null }
        countView.text = "$completed/$total"
        progressView.progress = completed.toFloat() / total.toFloat()
        if (profile.hasExplicitWeight && !weightInput.hasFocus()) weightInput.setText(formatNumber(profile.weightPounds * PRO_KG_PER_POUND))
        overlay.invalidate()
    }

    private fun persistSideMeasurements() {
        val previous = bodyProfileStore.profile.value.sideMeasurementsInches
        BodySideMeasurement.entries.forEach { point ->
            val current = profile.sideMeasurementsInches[point]
            when {
                current != null && current != previous[point] -> bodyProfileStore.setSideMeasurement(point, current)
                current == null && previous[point] != null -> bodyProfileStore.clearSideMeasurement(point)
            }
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    // -------------------- Anatomical projection ------------------------------------------------

    private fun p(name: String): BodyScreenPoint? = projection?.get(name)?.takeIf { it.visible }

    private fun mix(a: BodyScreenPoint?, b: BodyScreenPoint?, t: Float): BodyScreenPoint? {
        if (a == null) return b
        if (b == null) return a
        return BodyScreenPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.visible || b.visible)
    }

    private fun extrapolate(a: BodyScreenPoint?, b: BodyScreenPoint?, t: Float): BodyScreenPoint? {
        if (a == null || b == null) return b ?: a
        return BodyScreenPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.visible || b.visible)
    }

    private fun frontVisibility(): Float {
        val yaw = projection?.yawRadians ?: 0.0
        val normalized = ((yaw + PI) % (PI * 2.0)) - PI
        return ((cos(normalized) - .10) / .72).coerceIn(0.0, 1.0).toFloat()
    }

    private fun frame(): AnatomicalFrame {
        val leftShoulder = mix(p("LeftShoulder"), p("LeftUpperArm"), .18f) ?: BodyScreenPoint(.37f, .25f)
        val rightShoulder = mix(p("RightShoulder"), p("RightUpperArm"), .18f) ?: BodyScreenPoint(.63f, .25f)
        val neck = p("Neck") ?: BodyScreenPoint(.50f, .20f)
        val head = p("Head") ?: BodyScreenPoint(.50f, .13f)
        val spine = p("Spine") ?: BodyScreenPoint(.50f, .46f)
        val spine1 = p("Spine1") ?: BodyScreenPoint(.50f, .39f)
        val spine2 = p("Spine2") ?: BodyScreenPoint(.50f, .31f)
        val hips = p("Hips") ?: BodyScreenPoint(.50f, .53f)
        val rightUpperArm = p("RightUpperArm") ?: BodyScreenPoint(.69f, .29f)
        val rightForeArm = p("RightForeArm") ?: BodyScreenPoint(.75f, .39f)
        val rightHand = p("RightHand") ?: BodyScreenPoint(.79f, .48f)
        val leftFoot = p("LeftFoot") ?: BodyScreenPoint(.45f, .90f)
        val rightFoot = p("RightFoot") ?: BodyScreenPoint(.55f, .90f)

        val centerX = (leftShoulder.x + rightShoulder.x) * .5f
        val span = abs(rightShoulder.x - leftShoulder.x).coerceAtLeast(.13f)
        val torso = abs(hips.y - neck.y).coerceAtLeast(.28f)
        val crown = extrapolate(neck, head, 1.43f) ?: BodyScreenPoint(centerX, .07f)
        val chest = BodyScreenPoint(centerX, mix(spine2, spine1, .30f)?.y ?: .32f)
        val underBust = BodyScreenPoint(centerX, mix(spine2, spine1, .57f)?.y ?: .355f)
        val waist = BodyScreenPoint(centerX, mix(spine1, spine, .50f)?.y ?: .425f)
        val abdomen = BodyScreenPoint(centerX, mix(spine, hips, .32f)?.y ?: .47f)
        val hip = BodyScreenPoint(centerX, hips.y)
        val bustR = BodyScreenPoint(centerX + span * .18f, chest.y)
        val bustL = BodyScreenPoint(centerX - span * .18f, chest.y)
        val upperArm = mix(rightUpperArm, rightForeArm, .43f) ?: rightUpperArm
        val wrist = mix(rightForeArm, rightHand, .80f) ?: rightHand
        val armMid = mix(rightShoulder, wrist, .52f) ?: rightForeArm
        val highLeftShoulder = mix(neck, leftShoulder, .72f) ?: leftShoulder
        val feet = mix(leftFoot, rightFoot, .5f) ?: BodyScreenPoint(centerX, .90f)
        return AnatomicalFrame(
            leftShoulder, rightShoulder, neck, crown, chest, underBust, waist, abdomen, hip,
            bustL, bustR, upperArm, wrist, armMid, highLeftShoulder, feet, centerX, span, torso,
        )
    }

    private fun anchorFor(target: ProTailorTarget): BodyScreenPoint {
        val f = frame()
        fun side(level: BodyScreenPoint, fraction: Float) = BodyScreenPoint(f.centerX + f.span * fraction, level.y)
        fun sideLeft(level: BodyScreenPoint, fraction: Float) = BodyScreenPoint(f.centerX - f.span * fraction, level.y)
        val raw = when (target) {
            ProTailorTarget.HEIGHT -> f.crown
            ProTailorTarget.NECK -> side(f.neck, .10f)
            ProTailorTarget.SHOULDERS -> f.rightShoulder
            ProTailorTarget.SHOULDER_LENGTH -> f.leftShoulder
            ProTailorTarget.CHEST -> side(f.chest, .35f)
            ProTailorTarget.UNDERBUST -> sideLeft(f.underBust, .31f)
            ProTailorTarget.BUST_HEIGHT -> f.bustRight
            ProTailorTarget.BUST_POINT_DISTANCE -> f.bustLeft
            ProTailorTarget.WAIST -> side(f.waist, .27f)
            ProTailorTarget.ABDOMEN -> sideLeft(f.abdomen, .30f)
            ProTailorTarget.HIPS -> side(f.hip, .36f)
            ProTailorTarget.DRESS_LENGTH -> f.highLeftShoulder
            ProTailorTarget.ARM_LENGTH -> f.armMid
            ProTailorTarget.UPPER_ARM -> f.upperArm
            ProTailorTarget.WRIST -> f.wrist
        }
        return raw.copy(
            x = raw.x.coerceIn(.04f, .96f),
            y = raw.y.coerceIn(.055f, .93f),
            visible = raw.visible && frontVisibility() > .08f,
        )
    }

    private fun guideFor(target: ProTailorTarget): GuideGeometry {
        val f = frame()
        fun oval(c: BodyScreenPoint, w: Float, h: Float) = GuideGeometry(
            GuideShape.OVAL,
            BodyScreenPoint(c.x - w / 2f, c.y - h / 2f),
            BodyScreenPoint(c.x + w / 2f, c.y + h / 2f),
        )
        return when (target) {
            ProTailorTarget.HEIGHT -> GuideGeometry(
                GuideShape.LINE,
                BodyScreenPoint(f.centerX - f.span * .67f, f.crown.y),
                BodyScreenPoint(f.centerX - f.span * .67f, f.feet.y + .018f),
            )
            ProTailorTarget.NECK -> oval(f.neck.copy(x = f.centerX), f.span * .23f, f.torso * .042f)
            ProTailorTarget.SHOULDERS -> GuideGeometry(GuideShape.LINE, f.leftShoulder, f.rightShoulder)
            ProTailorTarget.SHOULDER_LENGTH -> GuideGeometry(GuideShape.LINE, mix(f.neck, f.leftShoulder, .15f) ?: f.neck, f.leftShoulder)
            ProTailorTarget.CHEST -> oval(f.chest, f.span * .74f, f.torso * .060f)
            ProTailorTarget.UNDERBUST -> oval(f.underBust, f.span * .66f, f.torso * .052f)
            ProTailorTarget.BUST_HEIGHT -> GuideGeometry(GuideShape.LINE, mix(f.neck, f.rightShoulder, .38f) ?: f.neck, f.bustRight)
            ProTailorTarget.BUST_POINT_DISTANCE -> GuideGeometry(GuideShape.LINE, f.bustLeft, f.bustRight)
            ProTailorTarget.WAIST -> oval(f.waist, f.span * .54f, f.torso * .047f)
            ProTailorTarget.ABDOMEN -> oval(f.abdomen, f.span * .60f, f.torso * .050f)
            ProTailorTarget.HIPS -> oval(f.hip, f.span * .73f, f.torso * .062f)
            ProTailorTarget.DRESS_LENGTH -> GuideGeometry(GuideShape.LINE, f.highLeftShoulder, BodyScreenPoint(f.highLeftShoulder.x, f.feet.y + .010f))
            ProTailorTarget.ARM_LENGTH -> GuideGeometry(GuideShape.LINE, f.rightShoulder, f.wrist)
            ProTailorTarget.UPPER_ARM -> oval(f.upperArm, f.span * .15f, f.torso * .058f)
            ProTailorTarget.WRIST -> oval(f.wrist, f.span * .085f, f.torso * .038f)
        }
    }

    private inner class TailorOverlay : View(this@TailorProMeasurementActivity) {
        var bodyReady = false
        var revealedCount = 0
        var selectedTarget: ProTailorTarget? = null
        private var downX = 0f
        private var downY = 0f

        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PRO_RED; style = Paint.Style.FILL }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFE0E3.toInt()
            style = Paint.Style.STROKE
            strokeWidth = dpF(1.2f)
        }
        private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PRO_BLUE
            style = Paint.Style.STROKE
            strokeWidth = dpF(1.8f)
            strokeCap = Paint.Cap.ROUND
        }
        private val travelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE823527D.toInt(); style = Paint.Style.FILL }
        private val chipStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x668FC7F7
            style = Paint.Style.STROKE
            strokeWidth = dpF(1f)
        }
        private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = spF(12.5f)
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!bodyReady) return
            val visibility = frontVisibility()
            selectedTarget?.let { if (visibility > .08f) drawGuide(canvas, guideFor(it)) }

            val phase = ((SystemClock.uptimeMillis() % 1100L).toFloat() / 1100f)
            ProTailorTarget.entries.take(revealedCount).forEach { target ->
                val a = anchorFor(target)
                if (!a.visible) return@forEach
                val cx = width * a.x
                val cy = height * a.y
                val selected = selectedTarget == target
                val alpha = (255 * visibility).roundToInt().coerceIn(25, 255)
                glowPaint.color = if (selected) (0x65FF4050 or (alpha shl 24)) else (0x24FF4050 or ((alpha / 2) shl 24))
                val pulse = if (selected) sin(phase * PI * 2).toFloat() * dpF(1.3f) else 0f
                canvas.drawCircle(cx, cy, dpF(if (selected) 9.5f else 6.3f) + pulse, glowPaint)
                dotPaint.alpha = alpha
                ringPaint.alpha = alpha
                canvas.drawCircle(cx, cy, dpF(if (selected) 4.6f else 3.6f), dotPaint)
                canvas.drawCircle(cx, cy, dpF(if (selected) 5.8f else 4.8f), ringPaint)

                target.valueCm(profile)?.let { drawValueChip(canvas, cx, cy, a.x, it) }
            }
            if (selectedTarget != null) postInvalidateOnAnimation()
        }

        private fun drawValueChip(canvas: Canvas, cx: Float, cy: Float, nx: Float, cm: Float) {
            val label = "${formatNumber(cm)} cm"
            val textW = valuePaint.measureText(label)
            val h = dpF(23f)
            val w = textW + dpF(15f)
            val left = if (nx <= .54f) cx + dpF(9f) else cx - dpF(9f) - w
            val top = cy - h / 2f
            val rect = RectF(left, top, left + w, top + h)
            canvas.drawRoundRect(rect, dpF(8f), dpF(8f), chipPaint)
            canvas.drawRoundRect(rect, dpF(8f), dpF(8f), chipStroke)
            valuePaint.textAlign = Paint.Align.CENTER
            val baseline = rect.centerY() - (valuePaint.ascent() + valuePaint.descent()) / 2f
            canvas.drawText(label, rect.centerX(), baseline, valuePaint)
        }

        private fun drawGuide(canvas: Canvas, g: GuideGeometry) {
            val sx = width * g.start.x
            val sy = height * g.start.y
            val ex = width * g.end.x
            val ey = height * g.end.y
            val phase = ((SystemClock.uptimeMillis() % 950L).toFloat() / 950f)
            if (g.shape == GuideShape.OVAL) {
                val rect = RectF(minOf(sx, ex), minOf(sy, ey), maxOf(sx, ex), maxOf(sy, ey))
                canvas.drawOval(rect, guidePaint)
                val angle = phase * PI.toFloat() * 2f
                val px = rect.centerX() + cos(angle) * rect.width() / 2f
                val py = rect.centerY() + sin(angle) * rect.height() / 2f
                canvas.drawCircle(px, py, dpF(2.4f), travelPaint)
            } else {
                canvas.drawLine(sx, sy, ex, ey, guidePaint)
                val px = sx + (ex - sx) * phase
                val py = sy + (ey - sy) * phase
                canvas.drawCircle(px, py, dpF(2.4f), travelPaint)
                arrow(canvas, sx, sy, ex, ey)
                arrow(canvas, ex, ey, sx, sy)
            }
            postInvalidateOnAnimation()
        }

        private fun arrow(canvas: Canvas, tipX: Float, tipY: Float, fromX: Float, fromY: Float) {
            val angle = atan2((tipY - fromY).toDouble(), (tipX - fromX).toDouble())
            val len = dpF(6.5f)
            val path = Path().apply {
                moveTo(tipX, tipY)
                lineTo((tipX - len * cos(angle - .55)).toFloat(), (tipY - len * sin(angle - .55)).toFloat())
                moveTo(tipX, tipY)
                lineTo((tipX - len * cos(angle + .55)).toFloat(), (tipY - len * sin(angle + .55)).toFloat())
            }
            canvas.drawPath(path, guidePaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!::runtime.isInitialized) return true
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                downX = event.x
                downY = event.y
            }
            runtime.onViewportTouch(event)
            if (event.actionMasked == MotionEvent.ACTION_UP && introCompleted) {
                val travel = hypot(event.x - downX, event.y - downY)
                if (travel <= dpF(16f)) {
                    nearestTarget(event.x, event.y)?.let {
                        openEditor(it)
                        return true
                    }
                }
            }
            return true
        }

        private fun nearestTarget(x: Float, y: Float): ProTailorTarget? {
            val max = dpF(25f)
            return ProTailorTarget.entries.take(revealedCount)
                .mapNotNull { target ->
                    val a = anchorFor(target)
                    if (!a.visible) null else target to hypot(width * a.x - x, height * a.y - y)
                }
                .filter { it.second <= max }
                .minByOrNull { it.second }
                ?.first
        }
    }

    private inner class ProProgressView : View(this@TailorProMeasurementActivity) {
        var progress = 0f
            set(value) { field = value.coerceIn(0f, 1f); invalidate() }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2D5B87.toInt() }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PRO_BLUE }
        override fun onDraw(canvas: Canvas) {
            val r = height / 2f
            canvas.drawRoundRect(0f, height * .30f, width.toFloat(), height * .70f, r, r, track)
            val end = width * progress
            if (end > 0f) canvas.drawRoundRect(0f, height * .30f, end, height * .70f, r, r, fill)
        }
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = value
        textSize = scaledText(size)
        setTextColor(color)
        includeFontPadding = false
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        if (language == "ar") textDirection = View.TEXT_DIRECTION_RTL
    }

    private fun rounded(color: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpF(radius)
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density * layoutScale).roundToInt().coerceAtLeast(1)
    private fun dpF(v: Float): Float = v * resources.displayMetrics.density * layoutScale
    private fun spF(v: Float): Float = v * resources.displayMetrics.scaledDensity * typeScale
    private fun scaledText(v: Float): Float = v * typeScale
    private fun formatNumber(v: Float): String = if (v % 1f == 0f) v.toInt().toString() else String.format(Locale.US, "%.1f", v)
}

private data class AnatomicalFrame(
    val leftShoulder: BodyScreenPoint,
    val rightShoulder: BodyScreenPoint,
    val neck: BodyScreenPoint,
    val crown: BodyScreenPoint,
    val chest: BodyScreenPoint,
    val underBust: BodyScreenPoint,
    val waist: BodyScreenPoint,
    val abdomen: BodyScreenPoint,
    val hip: BodyScreenPoint,
    val bustLeft: BodyScreenPoint,
    val bustRight: BodyScreenPoint,
    val upperArm: BodyScreenPoint,
    val wrist: BodyScreenPoint,
    val armMid: BodyScreenPoint,
    val highLeftShoulder: BodyScreenPoint,
    val feet: BodyScreenPoint,
    val centerX: Float,
    val span: Float,
    val torso: Float,
)

private enum class GuideShape { LINE, OVAL }
private data class GuideGeometry(val shape: GuideShape, val start: BodyScreenPoint, val end: BodyScreenPoint)

private enum class ProTailorTarget(
    val point: BodyMeasurePoint?,
    val focusY: Float,
    val focusDistance: Float,
) {
    HEIGHT(null, .00f, 2.86f),
    NECK(BodyMeasurePoint.NECK, .60f, 2.18f),
    SHOULDERS(BodyMeasurePoint.SHOULDERS, .49f, 2.28f),
    SHOULDER_LENGTH(BodyMeasurePoint.SHOULDER_LENGTH, .50f, 2.18f),
    CHEST(BodyMeasurePoint.CHEST, .33f, 2.10f),
    UNDERBUST(BodyMeasurePoint.UNDERBUST, .25f, 2.08f),
    BUST_HEIGHT(BodyMeasurePoint.BUST_HEIGHT, .34f, 2.02f),
    BUST_POINT_DISTANCE(BodyMeasurePoint.BUST_POINT_DISTANCE, .34f, 2.02f),
    WAIST(BodyMeasurePoint.WAIST, .10f, 2.08f),
    ABDOMEN(BodyMeasurePoint.ABDOMEN, .00f, 2.08f),
    HIPS(BodyMeasurePoint.HIPS, -.12f, 2.12f),
    DRESS_LENGTH(BodyMeasurePoint.DRESS_LENGTH, .00f, 2.64f),
    ARM_LENGTH(BodyMeasurePoint.ARM_LENGTH, .22f, 2.02f),
    UPPER_ARM(BodyMeasurePoint.UPPER_ARM, .34f, 1.94f),
    WRIST(BodyMeasurePoint.WRIST, .05f, 1.90f),
    ;

    fun title(language: String): String = if (language == "ar") when (this) {
        HEIGHT -> "الطول الكامل"
        NECK -> "محيط الرقبة"
        SHOULDERS -> "عرض الكتفين"
        SHOULDER_LENGTH -> "طول الكتف"
        CHEST -> "محيط الصدر"
        UNDERBUST -> "محيط أسفل الصدر"
        BUST_HEIGHT -> "ارتفاع الصدر"
        BUST_POINT_DISTANCE -> "المسافة بين نقطتي الصدر"
        WAIST -> "محيط الخصر"
        ABDOMEN -> "محيط البطن"
        HIPS -> "محيط الأرداف"
        DRESS_LENGTH -> "طول الفستان"
        ARM_LENGTH -> "طول الذراع"
        UPPER_ARM -> "محيط العضد"
        WRIST -> "محيط المعصم"
    } else when (this) {
        HEIGHT -> "Full height"
        NECK -> "Neck circumference"
        SHOULDERS -> "Shoulder width"
        SHOULDER_LENGTH -> "Shoulder length"
        CHEST -> "Bust circumference"
        UNDERBUST -> "Underbust circumference"
        BUST_HEIGHT -> "Bust height"
        BUST_POINT_DISTANCE -> "Bust-point distance"
        WAIST -> "Waist circumference"
        ABDOMEN -> "Abdomen circumference"
        HIPS -> "Hip circumference"
        DRESS_LENGTH -> "Dress length"
        ARM_LENGTH -> "Arm length"
        UPPER_ARM -> "Upper-arm circumference"
        WRIST -> "Wrist circumference"
    }

    fun sideKeys(): Pair<BodySideMeasurement, BodySideMeasurement>? = when (this) {
        ARM_LENGTH -> BodySideMeasurement.RIGHT_ARM_LENGTH to BodySideMeasurement.LEFT_ARM_LENGTH
        UPPER_ARM -> BodySideMeasurement.RIGHT_UPPER_ARM to BodySideMeasurement.LEFT_UPPER_ARM
        WRIST -> BodySideMeasurement.RIGHT_WRIST to BodySideMeasurement.LEFT_WRIST
        else -> null
    }

    fun valueCm(profile: BodyProfile): Float? = when (this) {
        HEIGHT -> profile.heightInches.takeIf { profile.hasExplicitHeight }?.times(PRO_CM_PER_INCH)
        else -> point?.let { profile.measurementsInches[it]?.times(PRO_CM_PER_INCH) }
    }
}
