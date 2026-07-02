package com.codex.casiomj12d

import android.app.Activity
import android.app.AlertDialog
import android.media.ToneGenerator
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor  = Color.rgb(15, 15, 17)
        window.navigationBarColor = Color.rgb(15, 15, 17)
        setContentView(CalculatorView(this))
    }
}

private class CalculatorView(context: Activity) : View(context) {

    // ── enums / data ───────────────────────────────────────────────────
    private enum class Kind { NUM, OP, LIGHT, MAROON, SILVER }
    private enum class CurrencyMode { PLAIN, USD, INR }

    private data class Key(
        val label: String,
        val action: String,
        val row: Int,
        val col: Int,
        val colSpan: Int = 1,
        val rowSpan: Int = 1,
        val kind: Kind = Kind.OP,
        var rect: RectF = RectF()
    )
    private data class HistoryStep(var label: String, var value: Double, val type: String)

    // ── paints ─────────────────────────────────────────────────────────
    private val bodyPaint    = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val strokePaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)   // ONLY used for press highlight

    // ── key layout ─────────────────────────────────────────────────────
    private val keys = listOf(
        Key("AUTO\nREVIEW", "AUTO",    0, 0, kind = Kind.OP),
        Key("CORRECT",      "CORRECT", 0, 1, kind = Kind.OP),
        Key("DISP",  "DISP", 2, 0, kind = Kind.LIGHT),
        Key("▶",     "BACK", 2, 1, kind = Kind.LIGHT),
        Key("+/-",   "SIGN", 2, 2, kind = Kind.LIGHT),
        Key("√",     "SQRT", 2, 3, kind = Kind.LIGHT),
        Key("MU",    "MU",   2, 4, kind = Kind.LIGHT),
        Key("GT",    "GT",   2, 5, kind = Kind.LIGHT),
        Key("7", "7", 3, 0, kind = Kind.NUM), Key("8", "8", 3, 1, kind = Kind.NUM), Key("9", "9", 3, 2, kind = Kind.NUM),
        Key("%", "%", 3, 3),
        Key("C",  "C",  3, 4, kind = Kind.MAROON),
        Key("AC", "AC", 3, 5, kind = Kind.MAROON),
        Key("4", "4", 4, 0, kind = Kind.NUM), Key("5", "5", 4, 1, kind = Kind.NUM), Key("6", "6", 4, 2, kind = Kind.NUM),
        Key("×", "*", 4, 3), Key("÷", "/", 4, 4),
        Key("MRC", "MRC", 4, 5, kind = Kind.LIGHT),
        Key("1", "1", 5, 0, kind = Kind.NUM), Key("2", "2", 5, 1, kind = Kind.NUM), Key("3", "3", 5, 2, kind = Kind.NUM),
        Key("-", "-", 5, 3),
        Key("+", "+", 5, 4, rowSpan = 2),
        Key("M-", "M-", 5, 5, kind = Kind.LIGHT),
        Key("0",  "0",  6, 0, kind = Kind.NUM),
        Key("00", "00", 6, 1, kind = Kind.NUM),
        Key(".",  ".",  6, 2, kind = Kind.NUM),
        Key("=",  "=",  6, 3),
        Key("M+", "M+", 6, 5, kind = Kind.LIGHT)
    )

    // ── flash-only actions (highlight 1 sec, no stay) ──────────────────
    private val flashActions = setOf("DISP", "AC", "C", "AUTO", "CORRECT", "GT", "MU", "SQRT", "SIGN", "BACK", "MRC", "M+", "M-")

    // ── calculator state ───────────────────────────────────────────────
    private var current        = "0"
    private var storedValue    : Double? = null
    private var pendingOp      : String? = null
    private var memory         = 0.0          // survives AC
    private var lastOperand    : Double? = null
    private var lastOp         : String? = null
    private var justEvaluated  = false
    private var freshOperand   = false
    private var grandTotal     = 0.0
    private var currencyMode   = CurrencyMode.PLAIN
    private val history        = ArrayList<HistoryStep>()
    private var reviewIndex    = -1
    private var reviewing      = false
    private var mrcArmed       = false
    private var autoReviewRunning = false

    // ── display state ──────────────────────────────────────────────────
    private var activeOp       : String? = null   // shown on display after op pressed
    private var pressedKey     : Key?    = null
    private var pressedMini    : String? = null

    // ── sound ──────────────────────────────────────────────────────────
    private val toneGen = try {
        ToneGenerator(AudioManager.STREAM_SYSTEM, 60)
    } catch (e: Exception) { null }

    private fun playClick() {
        try { toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP, 35) } catch (_: Exception) {}
    }

    // ── handler for flash highlight ────────────────────────────────────
    private val handler = Handler(Looper.getMainLooper())
    private var flashKey  : Key?    = null
    private var flashMini : String? = null

    // ── auto-review ticker ─────────────────────────────────────────────
    private val autoReviewTick = object : Runnable {
        override fun run() {
            if (!autoReviewRunning || history.isEmpty()) return
            reviewIndex++
            if (reviewIndex > history.lastIndex) { autoReviewRunning = false; exitReview(); return }
            reviewing = true; invalidate(); handler.postDelayed(this, 700L)
        }
    }

    init { isFocusable = true; isSoundEffectsEnabled = false }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        toneGen?.release()
    }

    // ── DRAW ───────────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val dp = resources.displayMetrics.density
        canvas.drawColor(Color.rgb(15, 15, 17))

        // responsive sizing – fits any phone or tablet
        val ratio  = 1.70f
        val maxW   = (width  - 12f * dp).coerceAtMost(460f * dp)
        val maxH   = (height - 12f * dp)
        val calcW  = if (maxW * ratio <= maxH) maxW else maxH / ratio
        val calcH  = calcW * ratio
        val left   = (width  - calcW) / 2f
        val top    = (height - calcH) / 2f
        val body   = RectF(left, top, left + calcW, top + calcH)

        // body
        bodyPaint.shader = LinearGradient(0f, body.top, 0f, body.bottom,
            Color.rgb(43,43,46), Color.rgb(31,31,34), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(body, 22f*dp, 28f*dp, bodyPaint)
        bodyPaint.shader = null
        bodyPaint.color  = Color.TRANSPARENT

        val dispBottom = drawTopAndDisplay(canvas, body, dp)
        drawKeys(canvas, body, dispBottom, dp)
    }

    private fun drawTopAndDisplay(canvas: Canvas, body: RectF, dp: Float): Float {
        // CASIO text
        textPaint.color     = Color.rgb(235, 233, 228)
        textPaint.typeface  = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD_ITALIC)
        textPaint.textSize  = 17f * dp
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("CASIO", body.left + 14f*dp, body.top + 34f*dp, textPaint)

        // MJ-12D label
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.typeface  = android.graphics.Typeface.DEFAULT_BOLD
        textPaint.textSize  = 12f*dp
        textPaint.color     = Color.rgb(200,198,194)
        canvas.drawText("MJ-12D", body.right - 12f*dp, body.top + 28f*dp, textPaint)
        textPaint.textSize = 8f*dp
        canvas.drawText("12 DIGITS", body.right - 12f*dp, body.top + 40f*dp, textPaint)

        // currency mode indicator
        val modeLabel = when (currencyMode) { CurrencyMode.USD -> "USD"; CurrencyMode.INR -> "INR"; else -> "" }
        if (modeLabel.isNotEmpty()) {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize  = 9f*dp
            textPaint.color     = Color.rgb(160,220,160)
            canvas.drawText(modeLabel, body.centerX(), body.top + 40f*dp, textPaint)
        }

        // LCD wrapper
        val wrap = RectF(body.left+10f*dp, body.top+50f*dp, body.right-10f*dp, body.top+132f*dp)
        bodyPaint.color = Color.rgb(16,16,18)
        canvas.drawRoundRect(wrap, 4f*dp, 4f*dp, bodyPaint)
        bodyPaint.color = Color.TRANSPARENT

        // LCD screen
        val disp = RectF(wrap.left+8f*dp, wrap.top+7f*dp, wrap.right-8f*dp, wrap.bottom-7f*dp)
        bodyPaint.shader = LinearGradient(0f, disp.top, 0f, disp.bottom,
            Color.rgb(220,230,222), Color.rgb(200,212,202), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(disp, 2f*dp, 2f*dp, bodyPaint)
        bodyPaint.shader = null
        bodyPaint.color  = Color.TRANSPARENT

        val inkColor = Color.rgb(40,55,50)

        // step counter (top-left)
        textPaint.color     = inkColor
        textPaint.typeface  = android.graphics.Typeface.MONOSPACE
        textPaint.textSize  = 10f*dp
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(reviewStepText(), disp.left+7f*dp, disp.top+15f*dp, textPaint)

        // REV indicator
        if (reviewing) {
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("REV", disp.centerX(), disp.top+15f*dp, textPaint)
        }

        // memory indicator
        if (memory != 0.0) {
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("M", disp.right-7f*dp, disp.top+15f*dp, textPaint)
        }

        // main display text
        val mainText = buildDisplayText()
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.typeface  = android.graphics.Typeface.create(
            android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        textPaint.color     = inkColor
        textPaint.textSize  = fitText(mainText, disp.width()-14f*dp, 30f*dp, 14f*dp)
        canvas.drawText(mainText, disp.right-7f*dp, disp.bottom-8f*dp, textPaint)

        return wrap.bottom
    }

    // builds what the LCD shows
    private fun buildDisplayText(): String {
        if (reviewing && reviewIndex in history.indices) {
            val h = history[reviewIndex]
            return "${h.label} ${formatNumber(h.value)}"
        }
        // show pending operator after it's pressed
        if (activeOp != null && freshOperand && !justEvaluated) {
            return "${formatRaw(storedValue ?: 0.0)} ${symFor(activeOp!!)}"
        }
        val v = if (current.isEmpty()) "0" else current
        // apply currency formatting only when not mid-entry
        if (justEvaluated || (!freshOperand && pendingOp == null && storedValue == null)) {
            val num = v.toDoubleOrNull()
            if (num != null) return formatNumber(num)
        }
        return if (!v.contains('.') && !v.contains('e') && v != "Error") "$v." else v
    }

    private fun formatNumber(n: Double): String {
        if (!n.isFinite()) return "Error"
        return when (currencyMode) {
            CurrencyMode.USD -> formatUSD(n)
            CurrencyMode.INR -> formatINR(n)
            CurrencyMode.PLAIN -> formatRaw(n)
        }
    }

    private fun formatRaw(n: Double): String {
        if (!n.isFinite()) return "Error"
        var s = "%.10g".format(n)
        if (s.contains('E')) s = s.replace('E','e')
        if (s.length > 14)   s = "%.4e".format(n)
        // trim trailing zeros after decimal
        if (s.contains('.') && !s.contains('e')) {
            s = s.trimEnd('0').trimEnd('.')
        }
        return if (!s.contains('.') && !s.contains('e') && s != "Error") "$s." else s
    }

    private fun formatUSD(n: Double): String {
        val neg    = n < 0
        val abs    = kotlin.math.abs(n)
        val intPart = abs.toLong()
        val fracPart = abs - intPart
        val intStr  = intPart.toString()
        val grouped = buildString {
            intStr.reversed().forEachIndexed { i, c ->
                if (i > 0 && i % 3 == 0) append(',')
                append(c)
            }
        }.reversed()
        val frac = if (fracPart > 0.000001) {
            val f = "%.2f".format(fracPart).drop(1)  // ".xx"
            f
        } else "."
        return "${if(neg)"-" else ""}$grouped$frac"
    }

    private fun formatINR(n: Double): String {
        val neg    = n < 0
        val abs    = kotlin.math.abs(n)
        val intPart = abs.toLong()
        val fracPart = abs - intPart
        val intStr  = intPart.toString()
        // INR: last 3 digits, then groups of 2
        val grouped = if (intStr.length <= 3) intStr else {
            val last3 = intStr.takeLast(3)
            val rest  = intStr.dropLast(3)
            val restGrouped = buildString {
                rest.reversed().forEachIndexed { i, c ->
                    if (i > 0 && i % 2 == 0) append(',')
                    append(c)
                }
            }.reversed()
            "$restGrouped,$last3"
        }
        val frac = if (fracPart > 0.000001) {
            "%.2f".format(fracPart).drop(1)
        } else "."
        return "${if(neg)"-" else ""}$grouped$frac"
    }

    // ── KEY DRAWING ────────────────────────────────────────────────────
    private fun drawKeys(canvas: Canvas, body: RectF, dispBottom: Float, dp: Float) {
        val padL  = body.left  + 10f*dp
        val padR  = body.right - 10f*dp
        val gap   = 5f*dp
        val colW  = (padR - padL - gap*5f) / 6f

        // available height for 5 key rows + step row + banner
        val availH  = body.bottom - dispBottom - 10f*dp
        val stepBanH = 15f*dp
        val stepBtnH = 26f*dp
        val keyH    = (availH - stepBanH - stepBtnH - gap*2f - gap*5f) / 5.2f
        val startY  = dispBottom + 8f*dp

        // layout keys
        keys.forEach { key ->
            val rowTop = when (key.row) {
                0    -> startY
                1    -> startY + keyH*0.85f + gap
                else -> startY + keyH*0.85f + gap + stepBanH + stepBtnH + (key.row-2)*(keyH+gap)
            }
            val h = if (key.row==0) keyH*0.85f else keyH*key.rowSpan + gap*(key.rowSpan-1)
            key.rect = RectF(
                padL + key.col*(colW+gap),
                rowTop,
                padL + key.col*(colW+gap) + colW*key.colSpan + gap*(key.colSpan-1),
                rowTop + h
            )
        }

        // 150 STEPS banner
        val bannerL = padL + 2f*(colW+gap)
        val bannerR = padL + 3f*colW + 2f*gap
        val bannerTop = startY + keyH*0.85f + gap
        val banner = RectF(bannerL, bannerTop, bannerR, bannerTop + stepBanH)
        bodyPaint.color = Color.rgb(182,182,186)
        canvas.drawRoundRect(banner, 4f*dp, 4f*dp, bodyPaint)
        bodyPaint.color = Color.TRANSPARENT
        textPaint.color = Color.rgb(35,35,37)
        textPaint.typeface  = android.graphics.Typeface.DEFAULT_BOLD
        textPaint.textSize  = 6.8f*dp
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("150 STEPS CHECK", banner.centerX(), banner.top+10f*dp, textPaint)

        // ▲▼ buttons
        val stepRow = RectF(bannerL, banner.bottom, bannerR, banner.bottom + stepBtnH)
        bodyPaint.color = Color.rgb(182,182,186)
        canvas.drawRoundRect(stepRow, 4f*dp, 4f*dp, bodyPaint)
        bodyPaint.color = Color.TRANSPARENT
        val upR   = RectF(stepRow.left+4f*dp,          stepRow.top+3f*dp, stepRow.centerX()-2f*dp, stepRow.bottom-3f*dp)
        val downR = RectF(stepRow.centerX()+2f*dp, stepRow.top+3f*dp, stepRow.right-4f*dp,     stepRow.bottom-3f*dp)
        drawMiniBtn(canvas, upR,   "▲", "UP",   dp)
        drawMiniBtn(canvas, downR, "▼", "DOWN", dp)

        // draw all keys
        keys.forEach { drawKey(canvas, it, dp) }
    }

    private fun drawMiniBtn(canvas: Canvas, rect: RectF, label: String, action: String, dp: Float) {
        val isFlash   = flashMini == action
        val isPressed = pressedMini == action
        drawBtnShape(canvas, rect, Kind.SILVER, isPressed || isFlash, dp)
        textPaint.color     = Color.rgb(28,28,28)
        textPaint.typeface  = android.graphics.Typeface.DEFAULT_BOLD
        textPaint.textSize  = 12f*dp
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(label, rect.centerX(), rect.centerY()+4f*dp, textPaint)
    }

    private fun drawKey(canvas: Canvas, key: Key, dp: Float) {
        val isFlash   = flashKey?.action == key.action
        val isPressed = pressedKey?.action == key.action
        drawBtnShape(canvas, key.rect, key.kind, isPressed || isFlash, dp)

        val lines    = key.label.split("\n")
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface  = android.graphics.Typeface.DEFAULT_BOLD
        textPaint.color     = if (key.kind==Kind.SILVER) Color.rgb(28,28,28) else Color.rgb(234,232,228)

        val baseSize = when {
            key.row == 0              -> 9f*dp
            key.kind == Kind.NUM      -> 16f*dp
            else                      -> 13f*dp
        }
        textPaint.textSize = baseSize
        val totalH = (lines.size-1)*baseSize*0.95f
        lines.forEachIndexed { i, line ->
            val y = key.rect.centerY() - totalH/2f + i*baseSize*0.95f + baseSize*0.35f
            canvas.drawText(line, key.rect.centerX(), y, textPaint)
        }
    }

    private fun drawBtnShape(canvas: Canvas, rect: RectF, kind: Kind, pressed: Boolean, dp: Float) {
        val (topC, botC) = when (kind) {
            Kind.LIGHT  -> Pair(Color.rgb(136,136,142), Color.rgb(110,110,116))
            Kind.MAROON -> Pair(Color.rgb(150,48,82),   Color.rgb(116,34,60))
            Kind.SILVER -> Pair(Color.rgb(208,207,203), Color.rgb(180,179,175))
            else        -> Pair(Color.rgb(102,102,108), Color.rgb(80,80,86))
        }
        val r = RectF(rect)
        if (pressed) r.offset(0f, 1.5f*dp)

        // use bodyPaint only for gradient fill, reset immediately after
        bodyPaint.shader = LinearGradient(0f, r.top, 0f, r.bottom, topC, botC, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(r, 7f*dp, 7f*dp, bodyPaint)
        bodyPaint.shader = null
        bodyPaint.color  = Color.TRANSPARENT   // ← critical: reset so body never picks this up

        if (pressed) {
            overlayPaint.color = when (kind) {
                Kind.MAROON -> Color.argb(85, 210, 110, 145)
                Kind.SILVER -> Color.argb(75, 255, 255, 255)
                Kind.LIGHT  -> Color.argb(75, 195, 195, 210)
                else        -> Color.argb(75, 195, 195, 200)
            }
            canvas.drawRoundRect(r, 7f*dp, 7f*dp, overlayPaint)
        }
    }

    // ── TOUCH ──────────────────────────────────────────────────────────
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val (k, m) = hitTest(event.x, event.y)
                pressedKey  = k; pressedMini = m
                if (k != null || m != null) playClick()
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val k = pressedKey; val m = pressedMini
                pressedKey = null; pressedMini = null
                invalidate()
                val (hk, hm) = hitTest(event.x, event.y)
                if (k != null && hk?.action == k.action) {
                    performClick()
                    triggerFlashIfNeeded(k, null)
                    handleAction(k.action)
                }
                if (m != null && hm == m) {
                    performClick()
                    triggerFlashIfNeeded(null, m)
                    handleAction(m)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedKey = null; pressedMini = null; invalidate(); return true
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun triggerFlashIfNeeded(key: Key?, mini: String?) {
        val action = key?.action ?: mini ?: return
        if (action in flashActions) {
            flashKey  = key
            flashMini = mini
            invalidate()
            handler.postDelayed({
                flashKey  = null
                flashMini = null
                invalidate()
            }, 120L)
        }
    }

    private fun hitTest(x: Float, y: Float): Pair<Key?, String?> {
        keys.firstOrNull { it.rect.contains(x,y) }?.let { return Pair(it, null) }
        // mini step buttons – derive rects from same layout math
        val dp    = resources.displayMetrics.density
        val calcW = min(width-12f*dp, 460f*dp).let {
            val maxH = height-12f*dp
            if (it*1.70f <= maxH) it else maxH/1.70f
        }
        val calcH = calcW*1.70f
        val left  = (width  - calcW)/2f
        val top   = (height - calcH)/2f
        val padL  = left + 10f*dp
        val padR  = left + calcW - 10f*dp
        val gap   = 5f*dp
        val colW  = (padR - padL - gap*5f)/6f
        val dispBottom = top + 132f*dp
        val keyH  = (calcH - (dispBottom-top) - 10f*dp - 15f*dp - 26f*dp - gap*2f - gap*5f)/5.2f
        val startY = dispBottom + 8f*dp
        val bannerL = padL + 2f*(colW+gap)
        val bannerR = padL + 3f*colW + 2f*gap
        val bannerTop = startY + keyH*0.85f + gap
        val stepRowT  = bannerTop + 15f*dp
        val stepRowB  = stepRowT  + 26f*dp
        if (y in stepRowT..stepRowB && x in bannerL..bannerR) {
            return if (x < (bannerL+bannerR)/2f) Pair(null,"UP") else Pair(null,"DOWN")
        }
        return Pair(null, null)
    }

    // ── ACTIONS ────────────────────────────────────────────────────────
    private fun handleAction(action: String) {
        when (action) {
            "0","00","1","2","3","4","5","6","7","8","9" -> inputDigit(action)
            "." -> inputDot()
            "+","-","*","/" -> setOp(action)
            "=" -> equals()
            "%" -> percent()
            "C"  -> clearEntry()
            "AC" -> clearAll()
            "SIGN"    -> toggleSign()
            "BACK"    -> backspace()
            "SQRT"    -> squareRoot()
            "MU"      -> markup()
            "GT"      -> grandTotalShow()
            "M+"      -> memPlus()
            "M-"      -> memMinus()
            "MRC"     -> mrc()
            "DISP"    -> dispCycle()
            "UP"      -> stepUp()
            "DOWN"    -> stepDown()
            "AUTO"    -> autoReview()
            "CORRECT" -> correctStep()
        }
        invalidate()
    }

    // ── DISPLAY HELPERS ────────────────────────────────────────────────
    private fun reviewStepText() =
        if (reviewing && reviewIndex in history.indices) (reviewIndex+1).toString()
        else history.size.toString()

    private fun fitText(text: String, maxW: Float, start: Float, minSz: Float): Float {
        var sz = start; textPaint.textSize = sz
        while (sz > minSz && textPaint.measureText(text) > maxW) { sz -= 0.5f; textPaint.textSize = sz }
        return sz
    }

    private fun symFor(op: String) = when(op){"*"->"×";"/"->"÷"; else->op}

    // ── CALC LOGIC ─────────────────────────────────────────────────────
    private fun numberValue() = current.toDoubleOrNull() ?: 0.0

    private fun pushHistory(label: String, value: Double, type: String) {
        history.add(HistoryStep(label, value, type))
        if (history.size > 150) history.removeAt(0)
    }

    private fun clearEntry() { current = "0"; activeOp = null }

    private fun clearAll() {
        // memory intentionally NOT cleared — survives AC per spec
        autoReviewRunning = false
        current = "0"; storedValue = null; pendingOp = null
        lastOperand = null; lastOp = null
        justEvaluated = false; reviewing = false
        reviewIndex = -1; freshOperand = false
        grandTotal = 0.0; history.clear()
        mrcArmed = false; activeOp = null
    }

    private fun inputDigit(d: String) {
        if (reviewing) exitReview()
        if (justEvaluated || freshOperand) {
            current = "0"; justEvaluated = false; freshOperand = false; activeOp = null
        }
        val digits = current.replace("-","").replace(".","").length
        if (digits >= 12) return
        current = when {
            current=="0" && d!="00" -> d
            current=="0" && d=="00" -> "0"
            else                    -> current+d
        }
    }

    private fun inputDot() {
        if (reviewing) exitReview()
        if (justEvaluated || freshOperand) {
            current = "0"; justEvaluated = false; freshOperand = false; activeOp = null
        }
        if (!current.contains('.')) current += "."
    }

    private fun toggleSign() {
        if (current=="0"||current=="Error") return
        current = if (current.startsWith('-')) current.drop(1) else "-$current"
    }

    private fun backspace() {
        if (reviewing) exitReview()
        current = if (current.length<=1||(current.length==2&&current.startsWith('-'))) "0"
                  else current.dropLast(1)
    }

    private fun applyPending(b: Double): Double {
        val a = storedValue ?: 0.0
        return when (pendingOp) {
            "+" -> a+b; "-" -> a-b; "*" -> a*b
            "/" -> if (b==0.0) Double.NaN else a/b
            else -> b
        }
    }

    private fun setOp(op: String) {
        if (reviewing) exitReview()
        val num = numberValue()
        if (pendingOp!=null && !justEvaluated) {
            val r = applyPending(num)
            storedValue = r
            pushHistory(symFor(pendingOp!!), num, "op")
            current = formatRaw(r)
        } else {
            storedValue = num
        }
        pendingOp     = op
        activeOp      = op    // shown on display
        justEvaluated = false
        freshOperand  = true
    }

    private fun equals() {
        if (reviewing) exitReview()
        val num = numberValue()
        val result = if (pendingOp!=null) {
            val r = applyPending(num)
            pushHistory(symFor(pendingOp!!), num, "op")
            lastOp = pendingOp; lastOperand = num; r
        } else if (lastOp!=null && justEvaluated && lastOperand!=null) {
            val a = numberValue()
            when(lastOp) {
                "+"->a+lastOperand!!; "-"->a-lastOperand!!; "*"->a*lastOperand!!
                else->if(lastOperand==0.0) Double.NaN else a/lastOperand!!
            }
        } else num
        pushHistory("=", result, "eq")
        if (result.isFinite()) grandTotal += result
        current = formatRaw(result)
        storedValue = result; pendingOp = null; activeOp = null; justEvaluated = true
    }

    private fun percent() {
        if (reviewing) exitReview()
        val num = numberValue()
        if (pendingOp!=null && storedValue!=null) {
            val base = storedValue!!
            val r = when(pendingOp) {
                "+"->base+base*num/100.0; "-"->base-base*num/100.0
                "*"->base*(num/100.0)
                else->if(num==0.0) Double.NaN else base/(num/100.0)
            }
            pushHistory("%",num,"op"); current=formatRaw(r); storedValue=r; pendingOp=null
        } else {
            current = formatRaw(num/100.0)
        }
        activeOp=null; justEvaluated=true
    }

    private fun squareRoot() {
        if (reviewing) exitReview()
        val n = numberValue()
        current = if (n<0.0) "Error" else formatRaw(sqrt(n))
        activeOp=null; justEvaluated=true
    }

    private fun markup() {
        if (reviewing) exitReview()
        val num = numberValue()
        if (storedValue!=null) {
            val sell = storedValue!!/(1.0-num/100.0)
            pushHistory("MU",num,"op"); current=formatRaw(sell); storedValue=sell; justEvaluated=true
        } else { storedValue=num; current="0" }
        activeOp=null
    }

    private fun grandTotalShow() {
        if (reviewing) exitReview()
        current = formatRaw(grandTotal); activeOp=null; justEvaluated=true
    }

    private fun memPlus()  { memory += numberValue(); justEvaluated=true }
    private fun memMinus() { memory -= numberValue(); justEvaluated=true }

    private fun mrc() {
        if (mrcArmed) { memory=0.0; mrcArmed=false }
        else { current=formatRaw(memory); justEvaluated=true; mrcArmed=true
               handler.postDelayed({mrcArmed=false},1500L) }
    }

    private fun dispCycle() {
        currencyMode = when(currencyMode) {
            CurrencyMode.PLAIN -> CurrencyMode.USD
            CurrencyMode.USD   -> CurrencyMode.INR
            CurrencyMode.INR   -> CurrencyMode.PLAIN
        }
    }

    private fun enterReview() {
        if (history.isEmpty()) return
        reviewing=true
        if (reviewIndex==-1) reviewIndex=history.lastIndex
    }
    private fun exitReview() { reviewing=false; reviewIndex=-1; autoReviewRunning=false }
    private fun stepUp()   { enterReview(); reviewIndex=max(0,reviewIndex-1) }
    private fun stepDown() { enterReview(); reviewIndex=min(history.lastIndex,reviewIndex+1) }

    private fun autoReview() {
        if (history.isEmpty()) return
        if (autoReviewRunning) { exitReview(); return }
        autoReviewRunning=true; reviewing=true; reviewIndex=0
        handler.removeCallbacks(autoReviewTick); handler.postDelayed(autoReviewTick,700L)
    }

    private fun correctStep() {
        if (!reviewing||reviewIndex !in history.indices) { clearEntry(); return }
        val step = history[reviewIndex]
        val input = EditText(context).apply { setText(step.value.toString()); selectAll() }
        AlertDialog.Builder(context)
            .setTitle("Correct step ${reviewIndex+1} (${step.label})")
            .setView(input)
            .setPositiveButton("OK"){_,_->
                input.text.toString().toDoubleOrNull()?.let{step.value=it;invalidate()}
            }
            .setNegativeButton("Cancel",null).show()
    }
}
