package com.codex.casiomj12d

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.widget.EditText
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(15, 15, 17)
        window.navigationBarColor = Color.rgb(15, 15, 17)
        setContentView(CalculatorView(this))
    }
}

private class CalculatorView(context: Activity) : View(context) {
    private enum class Kind { NUM, OP, LIGHT, MAROON, SILVER }
    private data class Key(
        val label: String,
        val action: String,
        val row: Int,
        val col: Int,
        val colSpan: Int = 1,
        val rowSpan: Int = 1,
        val kind: Kind = Kind.OP,
        val small: Boolean = false,
        var rect: RectF = RectF()
    )
    private data class HistoryStep(var label: String, var value: Double, val type: String)

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val keys = listOf(
        Key("AUTO\nREVIEW", "AUTO", 0, 0, kind = Kind.OP, small = true),
        Key("CORRECT", "CORRECT", 0, 1, kind = Kind.OP, small = true),
        Key("DISP", "DISP", 2, 0, kind = Kind.LIGHT), Key("▶", "BACK", 2, 1, kind = Kind.LIGHT),
        Key("+/-", "SIGN", 2, 2, kind = Kind.LIGHT), Key("√", "SQRT", 2, 3, kind = Kind.LIGHT),
        Key("MU", "MU", 2, 4, kind = Kind.LIGHT), Key("GT", "GT", 2, 5, kind = Kind.LIGHT),
        Key("7", "7", 3, 0, kind = Kind.NUM), Key("8", "8", 3, 1, kind = Kind.NUM), Key("9", "9", 3, 2, kind = Kind.NUM),
        Key("%", "%", 3, 3), Key("C", "C", 3, 4, kind = Kind.MAROON), Key("AC\nON", "AC", 3, 5, kind = Kind.MAROON),
        Key("4", "4", 4, 0, kind = Kind.NUM), Key("5", "5", 4, 1, kind = Kind.NUM), Key("6", "6", 4, 2, kind = Kind.NUM),
        Key("×", "*", 4, 3), Key("÷", "/", 4, 4), Key("MRC", "MRC", 4, 5, kind = Kind.LIGHT),
        Key("1", "1", 5, 0, kind = Kind.NUM), Key("2", "2", 5, 1, kind = Kind.NUM), Key("3", "3", 5, 2, kind = Kind.NUM),
        Key("-", "-", 5, 3), Key("+", "+", 5, 4, rowSpan = 2), Key("M-", "M-", 5, 5, kind = Kind.LIGHT),
        Key("0", "0", 6, 0, kind = Kind.NUM), Key("00", "00", 6, 1, kind = Kind.NUM), Key(".", ".", 6, 2, kind = Kind.NUM),
        Key("=", "=", 6, 3), Key("M+", "M+", 6, 5, kind = Kind.LIGHT)
    )

    private var current = "0"
    private var storedValue: Double? = null
    private var pendingOp: String? = null
    private var memory = 0.0
    private var lastOperand: Double? = null
    private var lastOp: String? = null
    private var justEvaluated = false
    private var freshOperand = false
    private var grandTotal = 0.0
    private var decimalPlaces: Int? = null
    private val history = ArrayList<HistoryStep>()
    private var reviewIndex = -1
    private var reviewing = false
    private var mrcArmed = false
    private var autoReviewRunning = false
    private var pressedKey: Key? = null

    private val autoReviewTick = object : Runnable {
        override fun run() {
            if (!autoReviewRunning || history.isEmpty()) return
            reviewIndex++
            if (reviewIndex > history.lastIndex) {
                autoReviewRunning = false
                exitReview()
                return
            }
            reviewing = true
            invalidate()
            postDelayed(this, 700L)
        }
    }

    init {
        isFocusable = true
        isSoundEffectsEnabled = true
        contentDescription = "Calculator"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(15, 15, 17))
        val dp = resources.displayMetrics.density
        val calcW = min(width - 24f * dp, 410f * dp)
        val calcH = calcW * 1.72f
        val left = (width - calcW) / 2f
        val top = max(12f * dp, (height - calcH) / 2f)
        val body = RectF(left, top, left + calcW, top + calcH)
        bodyPaint.shader = LinearGradient(0f, body.top, 0f, body.bottom, Color.rgb(43, 43, 46), Color.rgb(31, 31, 34), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(body, 26f * dp, 30f * dp, bodyPaint)
        bodyPaint.shader = null

        drawTopBar(canvas, body, dp)
        val displayBottom = drawDisplay(canvas, body, dp)
        drawKeys(canvas, body, displayBottom, dp)
    }

    private fun drawTopBar(canvas: Canvas, body: RectF, dp: Float) {
        textPaint.color = Color.rgb(242, 241, 238)
        textPaint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD_ITALIC)
        textPaint.textSize = 21f * dp
        textPaint.textAlign = Paint.Align.LEFT
        val solar = RectF(body.left + body.width() * .36f, body.top + 14f * dp, body.left + body.width() * .72f, body.top + 40f * dp)
        bodyPaint.color = Color.rgb(10, 10, 12)
        canvas.drawRoundRect(solar, 3f * dp, 3f * dp, bodyPaint)
        strokePaint.color = Color.BLACK
        strokePaint.strokeWidth = 1f * dp
        canvas.drawRoundRect(solar, 3f * dp, 3f * dp, strokePaint)
        strokePaint.strokeWidth = .7f * dp
        strokePaint.color = Color.rgb(34, 34, 36)
        var x = solar.left + 18f * dp
        while (x < solar.right) {
            canvas.drawLine(x, solar.top, x, solar.bottom, strokePaint)
            x += 19f * dp
        }
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        textPaint.textSize = 9f * dp
    }

    private fun drawDisplay(canvas: Canvas, body: RectF, dp: Float): Float {
        val wrap = RectF(body.left + 14f * dp, body.top + 64f * dp, body.right - 14f * dp, body.top + 152f * dp)
        bodyPaint.color = Color.rgb(16, 16, 18)
        canvas.drawRoundRect(wrap, 4f * dp, 4f * dp, bodyPaint)
        val display = RectF(wrap.left + 10f * dp, wrap.top + 9f * dp, wrap.right - 10f * dp, wrap.bottom - 10f * dp)
        bodyPaint.shader = LinearGradient(0f, display.top, 0f, display.bottom, Color.rgb(223, 231, 226), Color.rgb(205, 214, 208), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(display, 2f * dp, 2f * dp, bodyPaint)
        bodyPaint.shader = null
        textPaint.color = Color.rgb(43, 58, 54)
        textPaint.typeface = android.graphics.Typeface.MONOSPACE
        textPaint.textSize = 12f * dp
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(reviewStepText(), display.left + 10f * dp, display.top + 18f * dp, textPaint)
        if (reviewing) {
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("REV", display.centerX(), display.top + 18f * dp, textPaint)
        }
        if (memory != 0.0) {
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("□", display.right - 10f * dp, display.top + 18f * dp, textPaint)
        }
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        textPaint.textSize = fitText(displayText(), display.width() - 20f * dp, 34f * dp, 18f * dp)
        canvas.drawText(displayText(), display.right - 10f * dp, display.bottom - 10f * dp, textPaint)
        return wrap.bottom
    }

    private fun drawKeys(canvas: Canvas, body: RectF, displayBottom: Float, dp: Float) {
        val padL = body.left + 18f * dp
        val padR = body.right - 18f * dp
        val gap = 8f * dp
        val keyH = 48f * dp
        val stepH = 40f * dp
        val top = displayBottom + 12f * dp
        val colW = (padR - padL - gap * 5f) / 6f
        keys.forEach { key ->
            val rowTop = when (key.row) {
                0 -> top
                1 -> top + stepH + gap
                else -> top + stepH + gap + 34f * dp + (key.row - 2) * (keyH + gap)
            }
            val height = if (key.row == 0) stepH else keyH * key.rowSpan + gap * (key.rowSpan - 1)
            key.rect = RectF(
                padL + key.col * (colW + gap),
                rowTop,
                padL + key.col * (colW + gap) + colW * key.colSpan + gap * (key.colSpan - 1),
                rowTop + height
            )
        }
        val label = RectF(padL + 2f * (colW + gap), top, padL + 3f * colW + 2f * gap, top + 18f * dp)
        bodyPaint.color = Color.rgb(189, 189, 192)
        canvas.drawRoundRect(label, 6f * dp, 6f * dp, bodyPaint)
        textPaint.color = Color.rgb(50, 50, 52)
        textPaint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        textPaint.textSize = 8.5f * dp
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("150 STEPS CHECK", label.centerX(), label.top + 12f * dp, textPaint)
        val stepRow = RectF(label.left, label.bottom, label.right, label.bottom + 34f * dp)
        bodyPaint.color = Color.rgb(189, 189, 192)
        canvas.drawRoundRect(stepRow, 4f * dp, 4f * dp, bodyPaint)
        drawMiniStep(canvas, RectF(stepRow.left + 5f * dp, stepRow.top + 6f * dp, stepRow.centerX() - 2f * dp, stepRow.bottom - 5f * dp), "▲", "UP", dp)
        drawMiniStep(canvas, RectF(stepRow.centerX() + 2f * dp, stepRow.top + 6f * dp, stepRow.right - 5f * dp, stepRow.bottom - 5f * dp), "▼", "DOWN", dp)
        keys.forEach { drawKey(canvas, it, dp) }
    }

    private fun drawMiniStep(canvas: Canvas, rect: RectF, label: String, action: String, dp: Float) {
        val pressed = pressedKey?.action == action
        drawButtonShape(canvas, rect, Kind.SILVER, pressed, dp)
        textPaint.color = Color.rgb(34, 34, 34)
        textPaint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        textPaint.textSize = 14f * dp
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(label, rect.centerX(), rect.centerY() + 5f * dp, textPaint)
    }

    private fun drawKey(canvas: Canvas, key: Key, dp: Float) {
        drawButtonShape(canvas, key.rect, key.kind, pressedKey == key, dp)
        val lines = key.label.split("\n")
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        textPaint.color = if (key.kind == Kind.SILVER) Color.rgb(34, 34, 34) else Color.rgb(236, 234, 230)
        val baseSize = when {
            key.small -> 11f * dp
            key.kind == Kind.NUM -> 18f * dp
            else -> 16f * dp
        }
        textPaint.textSize = baseSize
        val total = (lines.size - 1) * baseSize * 0.95f
        lines.forEachIndexed { index, line ->
            val y = key.rect.centerY() - total / 2f + index * baseSize * 0.95f + baseSize * .35f
            if (line == "ON") {
                textPaint.textSize = 8f * dp
                canvas.drawText(line, key.rect.right - 12f * dp, key.rect.top + 10f * dp, textPaint)
                textPaint.textSize = baseSize
            } else {
                canvas.drawText(line, key.rect.centerX(), y, textPaint)
            }
        }
    }

    private fun drawButtonShape(canvas: Canvas, rect: RectF, kind: Kind, pressed: Boolean, dp: Float) {
        val topColor: Int
        val bottomColor: Int
        when (kind) {
            Kind.LIGHT -> { topColor = Color.rgb(141, 141, 146); bottomColor = Color.rgb(116, 116, 122) }
            Kind.MAROON -> { topColor = Color.rgb(156, 52, 87); bottomColor = Color.rgb(122, 38, 64) }
            Kind.SILVER -> { topColor = Color.rgb(212, 211, 207); bottomColor = Color.rgb(185, 184, 180) }
            else -> { topColor = Color.rgb(107, 107, 112); bottomColor = Color.rgb(85, 85, 90) }
        }
        val drawRect = RectF(rect)
        if (pressed) drawRect.offset(0f, 2f * dp)
        bodyPaint.shader = LinearGradient(0f, drawRect.top, 0f, drawRect.bottom, topColor, bottomColor, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(drawRect, 8f * dp, 8f * dp, bodyPaint)
        bodyPaint.shader = null
        if (pressed) {
            bodyPaint.color = Color.argb(96, 255, 255, 255)
            canvas.drawRoundRect(drawRect, 8f * dp, 8f * dp, bodyPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedKey = hitTest(event.x, event.y)
                if (pressedKey != null) playSoundEffect(SoundEffectConstants.CLICK)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val key = pressedKey
                pressedKey = null
                invalidate()
                if (key != null && key.action == hitTest(event.x, event.y)?.action) {
                    performClick()
                    handleAction(key.action)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedKey = null
                invalidate()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun hitTest(x: Float, y: Float): Key? {
        keys.firstOrNull { it.rect.contains(x, y) }?.let { return it }
        if (keys.size > 10) {
            val left = keys[10].rect.left
            val right = keys[10].rect.right
            val top = keys[0].rect.bottom
            val bottom = keys[2].rect.top
            if (y > top && y < bottom && x > left && x < right) {
                return if (x < (left + right) / 2f) Key("▲", "UP", 1, 2) else Key("▼", "DOWN", 1, 2)
            }
        }
        return null
    }

    private fun handleAction(action: String) {
        when (action) {
            "0", "00", "1", "2", "3", "4", "5", "6", "7", "8", "9" -> inputDigit(action)
            "." -> inputDot()
            "+", "-", "*", "/" -> setOp(action)
            "=" -> equals()
            "%" -> percent()
            "C" -> clearEntry()
            "AC" -> clearAll()
            "SIGN" -> toggleSign()
            "BACK" -> backspace()
            "SQRT" -> squareRoot()
            "MU" -> markup()
            "GT" -> grandTotalShow()
            "M+" -> memPlus()
            "M-" -> memMinus()
            "MRC" -> mrc()
            "DISP" -> dispCycle()
            "UP" -> stepUp()
            "DOWN" -> stepDown()
            "AUTO" -> autoReview()
            "CORRECT" -> correctStep()
        }
        invalidate()
    }

    private fun fmt(n: Double): String {
        if (!n.isFinite()) return "Error"
        val fixed = decimalPlaces
        var s = if (fixed != null) "% .${fixed}f".replace(" ", "").format(n) else "%.12g".format(n)
        if (s.contains('E')) s = s.replace('E', 'e')
        if (s.length > 15) s = "%.5e".format(n)
        return s
    }

    private fun displayText(): String {
        if (reviewing && reviewIndex in history.indices) {
            val h = history[reviewIndex]
            return "${h.label} ${fmt(h.value)}"
        }
        val v = if (current.isEmpty()) "0" else current
        return if (!v.contains('.') && !v.contains('e') && v != "Error") "${v}." else v
    }

    private fun reviewStepText(): String = if (reviewing && reviewIndex in history.indices) (reviewIndex + 1).toString() else history.size.toString()

    private fun fitText(text: String, maxWidth: Float, start: Float, minSize: Float): Float {
        var size = start
        textPaint.textSize = size
        while (size > minSize && textPaint.measureText(text) > maxWidth) {
            size -= 1f
            textPaint.textSize = size
        }
        return size
    }

    private fun numberValue() = current.toDoubleOrNull() ?: 0.0

    private fun pushHistory(label: String, value: Double, type: String) {
        history.add(HistoryStep(label, value, type))
        if (history.size > 150) history.removeAt(0)
    }

    private fun clearEntry() { current = "0" }

    private fun clearAll() {
        autoReviewRunning = false
        current = "0"
        storedValue = null
        pendingOp = null
        lastOperand = null
        lastOp = null
        justEvaluated = false
        reviewing = false
        reviewIndex = -1
        freshOperand = false
        memory = 0.0
        grandTotal = 0.0
        history.clear()
        mrcArmed = false
    }

    private fun inputDigit(d: String) {
        if (reviewing) exitReview()
        if (justEvaluated || freshOperand) { current = "0"; justEvaluated = false; freshOperand = false }
        val digits = current.replace("-", "").replace(".", "").length
        if (digits >= 12) return
        current = if (current == "0" && d != "00") d else if (current == "0" && d == "00") "0" else current + d
    }

    private fun inputDot() {
        if (reviewing) exitReview()
        if (justEvaluated || freshOperand) { current = "0"; justEvaluated = false; freshOperand = false }
        if (!current.contains('.')) current += "."
    }

    private fun toggleSign() {
        if (current == "0" || current == "Error") return
        current = if (current.startsWith('-')) current.drop(1) else "-$current"
    }

    private fun backspace() {
        if (reviewing) exitReview()
        current = if (current.length <= 1 || (current.length == 2 && current.startsWith('-'))) "0" else current.dropLast(1)
    }

    private fun applyPending(nextNumber: Double): Double {
        val a = storedValue ?: 0.0
        return when (pendingOp) {
            "+" -> a + nextNumber
            "-" -> a - nextNumber
            "*" -> a * nextNumber
            "/" -> if (nextNumber == 0.0) Double.NaN else a / nextNumber
            else -> nextNumber
        }
    }

    private fun setOp(op: String) {
        if (reviewing) exitReview()
        val num = numberValue()
        if (pendingOp != null && !justEvaluated) {
            val result = applyPending(num)
            storedValue = result
            pushHistory(symbolFor(pendingOp!!), num, "op")
            current = fmt(result)
        } else {
            storedValue = num
        }
        pendingOp = op
        justEvaluated = false
        freshOperand = true
    }

    private fun symbolFor(op: String) = when (op) { "*" -> "×"; "/" -> "÷"; else -> op }

    private fun equals() {
        if (reviewing) exitReview()
        val num = numberValue()
        val result = if (pendingOp != null) {
            val r = applyPending(num)
            pushHistory(symbolFor(pendingOp!!), num, "op")
            lastOp = pendingOp
            lastOperand = num
            r
        } else if (lastOp != null && justEvaluated && lastOperand != null) {
            val a = numberValue()
            when (lastOp) {
                "+" -> a + lastOperand!!
                "-" -> a - lastOperand!!
                "*" -> a * lastOperand!!
                "/" -> if (lastOperand == 0.0) Double.NaN else a / lastOperand!!
                else -> a
            }
        } else num
        pushHistory("=", result, "eq")
        if (result.isFinite()) grandTotal += result
        current = fmt(result)
        storedValue = result
        pendingOp = null
        justEvaluated = true
    }

    private fun percent() {
        if (reviewing) exitReview()
        val num = numberValue()
        if (pendingOp != null && storedValue != null) {
            val base = storedValue!!
            val result = when (pendingOp) {
                "+" -> base + base * num / 100.0
                "-" -> base - base * num / 100.0
                "*" -> base * (num / 100.0)
                "/" -> if (num == 0.0) Double.NaN else base / (num / 100.0)
                else -> num / 100.0
            }
            pushHistory("%", num, "op")
            current = fmt(result)
            storedValue = result
            pendingOp = null
        } else {
            current = fmt(num / 100.0)
        }
        justEvaluated = true
    }

    private fun squareRoot() {
        if (reviewing) exitReview()
        val num = numberValue()
        current = if (num < 0.0) "Error" else fmt(sqrt(num))
        justEvaluated = true
    }

    private fun markup() {
        if (reviewing) exitReview()
        val num = numberValue()
        if (storedValue != null) {
            val sell = storedValue!! / (1.0 - num / 100.0)
            pushHistory("MU", num, "op")
            current = fmt(sell)
            storedValue = sell
            justEvaluated = true
        } else {
            storedValue = num
            current = "0"
        }
    }

    private fun grandTotalShow() {
        if (reviewing) exitReview()
        current = fmt(grandTotal)
        justEvaluated = true
    }

    private fun memPlus() { memory += numberValue(); justEvaluated = true }
    private fun memMinus() { memory -= numberValue(); justEvaluated = true }

    private fun mrc() {
        if (mrcArmed) {
            memory = 0.0
            mrcArmed = false
        } else {
            current = fmt(memory)
            justEvaluated = true
            mrcArmed = true
            postDelayed({ mrcArmed = false }, 1500L)
        }
    }

    private fun dispCycle() {
        decimalPlaces = when (decimalPlaces) { null -> 0; 0 -> 2; 2 -> 4; else -> null }
        current = fmt(numberValue())
    }

    private fun enterReview() {
        if (history.isEmpty()) return
        reviewing = true
        if (reviewIndex == -1) reviewIndex = history.lastIndex
    }

    private fun exitReview() {
        reviewing = false
        reviewIndex = -1
        autoReviewRunning = false
    }

    private fun stepUp() {
        enterReview()
        reviewIndex = max(0, reviewIndex - 1)
    }

    private fun stepDown() {
        enterReview()
        reviewIndex = min(history.lastIndex, reviewIndex + 1)
    }

    private fun autoReview() {
        if (history.isEmpty()) return
        if (autoReviewRunning) {
            exitReview()
            return
        }
        autoReviewRunning = true
        reviewing = true
        reviewIndex = 0
        removeCallbacks(autoReviewTick)
        postDelayed(autoReviewTick, 700L)
    }

    private fun correctStep() {
        if (!reviewing || reviewIndex !in history.indices) {
            clearEntry()
            return
        }
        val step = history[reviewIndex]
        val input = EditText(context).apply {
            setText(step.value.toString())
            selectAll()
        }
        AlertDialog.Builder(context)
            .setTitle("Correct step ${reviewIndex + 1} (${step.label})")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                input.text.toString().toDoubleOrNull()?.let {
                    step.value = it
                    invalidate()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}