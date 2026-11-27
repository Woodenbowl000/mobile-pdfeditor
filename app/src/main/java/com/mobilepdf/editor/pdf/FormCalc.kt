package com.mobilepdf.editor.pdf

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/**
 * What a calculated field computes. [op] is SUM, AVG, PRD, MIN or MAX over [fields] (Acrobat's
 * AFSimple_Calculate), or EXPR for a "simplified field notation" formula in [expr] (e.g. `Qty * Price`).
 */
data class Calc(val op: String, val fields: List<String> = emptyList(), val expr: String? = null)

/** AFNumber_Format / AFPercent_Format: decimals, separator style (0-3), currency text, negatives in brackets. */
data class NumFormat(
    val decimals: Int, val sepStyle: Int = 0, val currency: String = "", val prepend: Boolean = true,
    val parens: Boolean = false, val percent: Boolean = false,
)

/**
 * Form calculations without a JavaScript engine (FEATURES.md: "only the common built-ins"): the scripts
 * almost every calculated form uses are recognised and run natively. Anything else is left alone.
 */
object FormCalc {

    // ---------------------------------------------------------------------------------------- parsing

    fun parseCalc(js: String?): Calc? {
        if (js.isNullOrBlank()) return null
        Regex("BVCALC(.*?)EVCALC", RegexOption.DOT_MATCHES_ALL).find(js)?.let { m ->
            val e = m.groupValues[1].trim()
            if (e.isNotEmpty()) return Calc("EXPR", expr = e)
        }
        val m = Regex("""AFSimple_Calculate\s*\(\s*["'](\w+)["']\s*,\s*(.+?)\)\s*;?\s*$""", RegexOption.DOT_MATCHES_ALL).find(js.trim()) ?: return null
        val op = m.groupValues[1].uppercase()
        if (op !in setOf("SUM", "AVG", "PRD", "MIN", "MAX")) return null
        val arg = m.groupValues[2]
        val quoted = Regex("""["']([^"']+)["']""").findAll(arg).map { it.groupValues[1] }.toList()
        // a list in one string ("A, B, C") or one string per field
        val names = (if (quoted.size == 1) quoted[0].split(',') else quoted).map { it.trim() }.filter { it.isNotEmpty() }
        return if (names.isEmpty()) null else Calc(op, names)
    }

    fun parseFormat(js: String?): NumFormat? {
        if (js.isNullOrBlank()) return null
        Regex("""AFNumber_Format\s*\(([^)]*)\)""").find(js)?.let { m ->
            val a = args(m.groupValues[1])
            return NumFormat(
                decimals = a.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 10) ?: 2,
                sepStyle = a.getOrNull(1)?.toIntOrNull() ?: 0,
                parens = (a.getOrNull(2)?.toIntOrNull() ?: 0) >= 2,
                currency = a.getOrNull(4)?.trim('"', '\'') ?: "",
                prepend = a.getOrNull(5)?.trim()?.let { it == "true" || it == "1" } ?: true,
            )
        }
        Regex("""AFPercent_Format\s*\(([^)]*)\)""").find(js)?.let { m ->
            val a = args(m.groupValues[1])
            return NumFormat(decimals = a.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 10) ?: 0, sepStyle = a.getOrNull(1)?.toIntOrNull() ?: 0, percent = true)
        }
        return null
    }

    private fun args(s: String) = s.split(',').map { it.trim() }

    // ---------------------------------------------------------------------------------------- numbers

    /** A field's value as a number: "$1,234.50", "1.234,50 €", "(12)", "15%" … Empty or text = 0 (as Acrobat). */
    fun number(s: String?, f: NumFormat? = null): Double {
        if (s.isNullOrBlank()) return 0.0
        var t = s.trim()
        val neg = t.startsWith("(") && t.endsWith(")") || t.startsWith("-") || t.endsWith("-")
        val pct = t.endsWith("%")
        t = t.filter { it.isDigit() || it == '.' || it == ',' }
        if (t.isEmpty()) return 0.0
        val commaDecimal = f?.sepStyle == 2 || f?.sepStyle == 3 ||
            (f == null && t.lastIndexOf(',') > t.lastIndexOf('.') && t.length - t.lastIndexOf(',') - 1 in 1..2)
        t = if (commaDecimal) t.replace(".", "").replace(',', '.') else t.replace(",", "")
        val v = t.toDoubleOrNull() ?: return 0.0
        return (if (neg) -v else v) / (if (pct && f?.percent != true) 100.0 else 1.0)
    }

    fun format(v: Double, f: NumFormat?): String {
        if (f == null) {
            // no format: a plain number, no trailing ".0"
            val r = BigDecimal(v).setScale(10, RoundingMode.HALF_UP).stripTrailingZeros()
            return if (r.compareTo(BigDecimal.ZERO) == 0) "0" else r.toPlainString()
        }
        val x = if (f.percent) v * 100 else v
        val r = BigDecimal(abs(x)).setScale(f.decimals, RoundingMode.HALF_UP).toPlainString()
        val (intPart, frac) = r.split('.').let { it[0] to it.getOrNull(1) }
        val group = when (f.sepStyle) { 0 -> ","; 2 -> "."; else -> "" }
        val dec = if (f.sepStyle == 2 || f.sepStyle == 3) "," else "."
        val grouped = if (group.isEmpty()) intPart else intPart.reversed().chunked(3).joinToString(group).reversed()
        var body = grouped + (frac?.let { dec + it } ?: "")
        if (f.percent) body += "%"
        if (f.currency.isNotEmpty()) body = if (f.prepend) f.currency + body else body + f.currency
        return when {
            x < 0 && BigDecimal(abs(x)).setScale(f.decimals, RoundingMode.HALF_UP).signum() != 0 -> if (f.parens) "($body)" else "-$body"
            else -> body
        }
    }

    // ---------------------------------------------------------------------------------------- formulas

    /** Evaluates `A * (B + 2) / C` with field names (spaces inside names escaped as `\ `). */
    fun eval(expr: String, get: (String) -> Double): Double {
        var i = 0
        fun ws() { while (i < expr.length && expr[i].isWhitespace()) i++ }
        lateinit var sum: () -> Double
        fun atom(): Double {
            ws()
            if (i >= expr.length) throw IllegalArgumentException("formula ends early")
            val c = expr[i]
            return when {
                c == '(' -> { i++; val v = sum(); ws(); if (i < expr.length && expr[i] == ')') i++; v }
                c == '-' -> { i++; -atom() }
                c == '+' -> { i++; atom() }
                c.isDigit() || c == '.' -> {
                    val s = i; while (i < expr.length && (expr[i].isDigit() || expr[i] == '.')) i++
                    expr.substring(s, i).toDouble()
                }
                else -> {
                    val sb = StringBuilder()
                    while (i < expr.length) {
                        val ch = expr[i]
                        if (ch == '\\' && i + 1 < expr.length) { sb.append(expr[i + 1]); i += 2; continue }
                        if (ch.isLetterOrDigit() || ch == '_' || ch == '.' || ch == '#' || ch == '[' || ch == ']') { sb.append(ch); i++ } else break
                    }
                    if (sb.isEmpty()) throw IllegalArgumentException("unexpected '$c'")
                    get(sb.toString())
                }
            }
        }
        fun product(): Double {
            var v = atom()
            while (true) {
                ws()
                if (i < expr.length && (expr[i] == '*' || expr[i] == '/')) {
                    val op = expr[i++]; val r = atom()
                    v = if (op == '*') v * r else if (r == 0.0) 0.0 else v / r
                } else return v
            }
        }
        sum = {
            var v = product()
            while (true) {
                ws()
                if (i < expr.length && (expr[i] == '+' || expr[i] == '-')) {
                    val op = expr[i++]; val r = product()
                    v = if (op == '+') v + r else v - r
                } else break
            }
            v
        }
        return sum()
    }

    /**
     * Runs every calculation, in the form's calculation order, each seeing the results of the earlier ones.
     * Returns the new (formatted) values of the calculated fields.
     */
    fun recompute(fields: List<FormField>, valueOf: (FormField) -> String): Map<String, String> {
        val values = HashMap<String, String>()
        fields.forEach { values[it.name] = valueOf(it) }
        val byName = fields.associateBy { it.name }
        fun fieldsNamed(n: String) = fields.filter { it.name == n || it.name.startsWith("$n.") }
        fun num(n: String): Double = fieldsNamed(n).sumOf { f -> number(values[f.name], f.format) }
        val out = LinkedHashMap<String, String>()
        for (f in fields.filter { it.calc != null }.sortedBy { it.calcOrder }) {
            val c = f.calc ?: continue
            val v = runCatching {
                when (c.op) {
                    "EXPR" -> eval(c.expr ?: return@runCatching null, ::num)
                    else -> {
                        val nums = c.fields.flatMap { n -> fieldsNamed(n).map { number(values[it.name], it.format) } }
                        when (c.op) {
                            "SUM" -> nums.sum()
                            "AVG" -> if (nums.isEmpty()) 0.0 else nums.average()
                            "PRD" -> if (nums.isEmpty()) 0.0 else nums.fold(1.0) { a, b -> a * b }
                            "MIN" -> nums.minOrNull() ?: 0.0
                            else -> nums.maxOrNull() ?: 0.0
                        }
                    }
                }
            }.getOrNull() ?: continue
            val s = format(v, f.format)
            values[f.name] = s
            if (byName[f.name] != null) out[f.name] = s
        }
        return out
    }
}
