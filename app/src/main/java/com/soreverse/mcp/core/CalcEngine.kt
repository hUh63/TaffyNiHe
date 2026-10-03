package com.soreverse.mcp.core

import java.math.BigInteger
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * 塔菲逆核：内置高精度计算引擎（移植自 calculate-mcp 的语义）。
 *
 * 纯 JVM/Kotlin 实现（BigInteger + MessageDigest + Base64），零外部依赖、可在单测中直接跑。
 * 覆盖：基础算术/统计/三角、任意精度进制转换、位运算、端序转换、IEEE-754 浮点分解、
 * 哈希/CRC/模运算、Base64/Hex/URL 编解码，以及链式批量执行（batch_calc）。
 *
 * 大语言模型心算浮点/大数/位运算/Base64/哈希时极易产生幻觉与精度偏差，
 * 因此凡涉及确定性数值计算与底层数据转换，一律走本引擎。
 */
object CalcEngine {

    val OPS: List<String> = listOf(
        // 基础算术
        "add", "subtract", "multiply", "division", "sum", "modulo", "floor", "ceiling", "round",
        // 统计
        "mean", "median", "mode", "min", "max",
        // 三角/角度
        "sin", "cos", "tan", "arcsin", "arccos", "arctan", "radiansToDegrees", "degreesToRadians",
        // 逆向底层
        "int_convert", "bitwise", "endian_swap", "ieee754_convert", "crypto_calc", "data_codec",
    )

    // ────────────────────────── 入口 ──────────────────────────

    /** 单步执行；任何非法输入抛 IllegalArgumentException，调用方转成 error。 */
    fun runOp(op: String, args: JSONObject): JSONObject {
        return when (op) {
            "add" -> num(num(args, "firstNumber").add(num(args, "secondNumber")))
            "subtract" -> num(num(args, "minuend").subtract(num(args, "subtrahend")))
            "multiply" -> num(num(args, "firstNumber").multiply(num(args, "secondNumber")))
            "division" -> {
                val d = num(args, "denominator")
                require(d.signum() != 0) { "denominator must not be zero" }
                JSONObject().put("value", num(args, "numerator").toDouble() / d.toDouble())
                    .put("decimal", divideDec(num(args, "numerator"), d))
            }
            "sum" -> {
                val arr = args.optJSONArray("numbers") ?: throw IllegalArgumentException("numbers[] required")
                var s = BigInteger.ZERO
                for (i in 0 until arr.length()) s = s.add(toBig(arr.get(i)))
                num(s)
            }
            "modulo" -> {
                val d = num(args, "denominator")
                require(d.signum() != 0) { "denominator must not be zero" }
                val r = num(args, "numerator").mod(d) // BigInteger.mod -> always non-negative
                num(r)
            }
            "floor" -> num(Math.floor(num(args, "number").toDouble()).toLong())
            "ceiling" -> num(Math.ceil(num(args, "number").toDouble()).toLong())
            "round" -> num(Math.round(num(args, "number").toDouble()))
            "mean" -> {
                val arr = nums(args, "numbers"); require(arr.isNotEmpty())
                JSONObject().put("value", arr.average()).put("decimal", arr.average().toString())
            }
            "median" -> {
                val arr = nums(args, "numbers").sorted(); require(arr.isNotEmpty())
                val m = arr.size / 2
                val v = if (arr.size % 2 != 0) arr[m] else (arr[m - 1] + arr[m]) / 2.0
                JSONObject().put("value", v).put("decimal", v.toString())
            }
            "mode" -> {
                val arr = nums(args, "numbers")
                val freq = LinkedHashMap<Double, Int>()
                arr.forEach { freq[it] = (freq[it] ?: 0) + 1 }
                val maxF = freq.values.maxOrNull() ?: 0
                val modes = freq.filterValues { it == maxF }.keys.toList()
                JSONObject().put("modeResult", JSONArray(modes)).put("maxFrequency", maxF)
            }
            "min" -> num(nums(args, "numbers").minOrNull()?.let { Math.floor(it).toLong() } ?: throw IllegalArgumentException("numbers[] required"))
            "max" -> num(nums(args, "numbers").maxOrNull()?.let { Math.ceil(it).toLong() } ?: throw IllegalArgumentException("numbers[] required"))
            "sin" -> real(Math.sin(dbl(args, "number")))
            "cos" -> real(Math.cos(dbl(args, "number")))
            "tan" -> real(Math.tan(dbl(args, "number")))
            "arcsin" -> real(Math.asin(dbl(args, "number")))
            "arccos" -> real(Math.acos(dbl(args, "number")))
            "arctan" -> real(Math.atan(dbl(args, "number")))
            "radiansToDegrees" -> real(Math.toDegrees(dbl(args, "number")))
            "degreesToRadians" -> real(Math.toRadians(dbl(args, "number")))
            "int_convert" -> intConvert(args)
            "bitwise" -> bitwise(args)
            "endian_swap" -> endianSwap(args)
            "ieee754_convert" -> ieee754Convert(args)
            "crypto_calc" -> cryptoCalc(args)
            "data_codec" -> dataCodec(args)
            else -> throw IllegalArgumentException("unknown op: $op")
        }
    }

    // ────────────────────────── 基础算术辅助 ──────────────────────────

    private fun num(v: BigInteger): JSONObject = JSONObject()
        .put("value", v.toLongSafe())
        .put("decimal", v.toString())
        .put("hex", if (v.signum() < 0) "-0x" + v.abs().toString(16) else "0x" + v.toString(16))
        .put("resultHex", if (v.signum() < 0) "-0x" + v.abs().toString(16) else "0x" + v.toString(16))

    private fun num(v: Long): JSONObject = num(BigInteger.valueOf(v))
    private fun real(v: Double): JSONObject = JSONObject().put("value", v).put("decimal", v.toString())

    private fun BigInteger.toLongSafe(): Any =
        try { this.toLong() } catch (e: ArithmeticException) { this.toString() }

    private fun divideDec(a: BigInteger, b: BigInteger): String =
        a.toDouble().div(b.toDouble()).toString()

    private fun num(args: JSONObject, key: String): BigInteger {
        if (!args.has(key) || args.isNull(key)) throw IllegalArgumentException("missing arg '$key'")
        return toBig(args.get(key))
    }

    private fun dbl(args: JSONObject, key: String): Double {
        if (!args.has(key) || args.isNull(key)) throw IllegalArgumentException("missing arg '$key'")
        val v = args.get(key)
        return when (v) {
            is Number -> v.toDouble()
            else -> v.toString().trim().toDoubleOrNull() ?: throw IllegalArgumentException("bad number '$v'")
        }
    }

    private fun nums(args: JSONObject, key: String): List<Double> {
        val arr = args.optJSONArray(key) ?: throw IllegalArgumentException("$key[] required")
        return (0 until arr.length()).map { i ->
            val v = arr.get(i)
            if (v is Number) v.toDouble() else v.toString().toDoubleOrNull() ?: throw IllegalArgumentException("bad number '$v' in $key[]")
        }
    }

    // ────────────────────────── int_convert ──────────────────────────

    fun toBig(raw: Any?): BigInteger {
        if (raw == null) throw IllegalArgumentException("null integer input")
        if (raw is Number) return BigInteger.valueOf(raw.toLong())
        val s = raw.toString().trim()
        require(s.isNotEmpty()) { "empty integer input" }
        val neg = s.startsWith("-")
        val body = if (neg || s.startsWith("+")) s.substring(1) else s
        val v = try {
            when {
                body.startsWith("0x", true) -> BigInteger(body.substring(2), 16)
                body.startsWith("0b", true) -> BigInteger(body.substring(2), 2)
                body.startsWith("0o", true) -> BigInteger(body.substring(2), 8)
                else -> BigInteger(body, 10)
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("failed to parse integer: \"$raw\"")
        }
        return if (neg) v.negate() else v
    }

    private fun asUintN(bits: Int, v: BigInteger): BigInteger = v.and(BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE))
    private fun asIntN(bits: Int, v: BigInteger): BigInteger {
        val u = asUintN(bits, v)
        return if (u.testBit(bits - 1)) u.subtract(BigInteger.ONE.shiftLeft(bits)) else u
    }

    private fun pad(n: String, len: Int): String = if (n.length >= len) n else "0".repeat(len - n.length) + n

    private fun swapEndianHex(hexNoPrefix: String, byteLen: Int): String {
        val padded = pad(hexNoPrefix, byteLen * 2)
        val bytes = (0 until byteLen).map { padded.substring(it * 2, it * 2 + 2) }
        return "0x" + bytes.reversed().joinToString("")
    }

    private fun formatBinary(binNoPrefix: String): String {
        val rem = binNoPrefix.length % 4
        val padded = if (rem == 0) binNoPrefix else "0".repeat(4 - rem) + binNoPrefix
        return "0b" + padded.chunked(4).joinToString(" ")
    }

    private fun intConvert(args: JSONObject): JSONObject {
        val inputs = ArrayList<Any>()
        if (args.has("value") && !args.isNull("value")) inputs.add(args.get("value"))
        args.optJSONArray("values")?.let { for (i in 0 until it.length()) inputs.add(it.get(i)) }
        require(inputs.isNotEmpty()) { "must provide 'value' or 'values'" }
        val results = inputs.map { convertOne(it) }
        if (results.size == 1) return results[0]
        return JSONObject().put("results", JSONArray(results))
    }

    private fun convertOne(input: Any): JSONObject {
        val v = toBig(input)
        val neg = v.signum() < 0
        val abs = v.abs()

        fun bw(bits: Int): JSONObject {
            val u = asUintN(bits, v)
            val s = asIntN(bits, v)
            val hexRaw = pad(u.toString(16), bits / 4)
            return JSONObject()
                .put("unsigned", if (bits <= 32) u.longValueExact() else u.toString())
                .put("signed", if (bits <= 32) s.longValueExact() else s.toString())
                .put("hex", "0x" + hexRaw)
                .put("littleEndianHex", swapEndianHex(hexRaw, bits / 8))
        }

        val ascii = if (v >= BigInteger.valueOf(32) && v <= BigInteger.valueOf(126)) v.toInt().toChar().toString() else null
        return JSONObject()
            .put("input", input.toString())
            .put("decimal", v.toString())
            .put("hex", (if (neg) "-0x" else "0x") + abs.toString(16))
            .put("hexUpper", (if (neg) "-0x" else "0x") + abs.toString(16).uppercase())
            .put("binary", (if (neg) "-0b" else "0b") + abs.toString(2))
            .put("binaryFormatted", (if (neg) "-0b" else "0b") + formatBinary(abs.toString(2)).removePrefix("0b"))
            .put("octal", (if (neg) "-0o" else "0o") + abs.toString(8))
            .apply { if (ascii != null) put("ascii", ascii) }
            .put("bitWidths", JSONObject().put("bit8", bw(8)).put("bit16", bw(16)).put("bit32", bw(32)).put("bit64", bw(64)))
    }

    // ────────────────────────── bitwise ──────────────────────────

    private fun bitwise(args: JSONObject): JSONObject {
        val op = args.optString("operation").lowercase()
        require(op in setOf("and", "or", "xor", "not", "shl", "shr", "sar", "rol", "ror")) { "bad operation: $op" }
        val width = args.optInt("bitWidth", 32).let { if (it in setOf(8, 16, 32, 64)) it else 32 }
        val aRaw = num(args, "a")
        val a = asUintN(width, aRaw)
        val bigW = BigInteger.valueOf(width.toLong())
        val mask = BigInteger.ONE.shiftLeft(width).subtract(BigInteger.ONE)
        var b = BigInteger.ZERO
        if (op != "not") {
            if (!args.has("b") || args.isNull("b")) throw IllegalArgumentException("operation \"$op\" requires 'b'")
            b = toBig(args.get("b"))
        }
        val res: BigInteger = when (op) {
            "and" -> a.and(asUintN(width, b)).and(mask)
            "or" -> a.or(asUintN(width, b)).and(mask)
            "xor" -> a.xor(asUintN(width, b)).and(mask)
            "not" -> a.not().and(mask)
            "shl" -> a.shiftLeft(b.mod(bigW).toInt()).and(mask)
            "shr" -> a.shiftRight(b.mod(bigW).toInt()).and(mask)
            "sar" -> asUintN(width, asIntN(width, a).shiftRight(b.mod(bigW).toInt()))
            "rol" -> {
                val s = b.mod(bigW).toInt()
                if (s == 0) a else a.shiftLeft(s).or(a.shiftRight(width - s)).and(mask)
            }
            "ror" -> {
                val s = b.mod(bigW).toInt()
                if (s == 0) a else a.shiftRight(s).or(a.shiftLeft(width - s)).and(mask)
            }
            else -> throw IllegalArgumentException("unsupported: $op")
        }
        val signed = asIntN(width, res)
        return JSONObject()
            .put("operation", op)
            .put("bitWidth", width)
            .put("operandA", aRaw.toString())
            .apply { if (op != "not") put("operandB", b.toString()) }
            .put("resultHex", "0x" + pad(res.toString(16), width / 4))
            .put("resultDecSigned", signed.toString())
            .put("resultDecUnsigned", res.toString())
            .put("resultBin", "0b" + pad(res.toString(2), width))
            .put("resultHexShort", "0x" + res.toString(16))
    }

    // ────────────────────────── endian_swap ──────────────────────────

    private fun toBytes(input: Any, widthBytes: Int?): List<Int> {
        val isIntText = input is Number ||
            (input is String && !input.trim().startsWith("0x", true) && Regex("^-?\\d+$").matches(input.trim()))
        if (isIntText) {
            val v = toBig(input)
            val auto = widthBytes ?: when {
                v > BigInteger("ffffffff", 16) || v < BigInteger("-80000000", 16) -> 8
                v > BigInteger("ffff", 16) || v < BigInteger("-8000", 16) -> 4
                v > BigInteger("ff", 16) || v < BigInteger("-80", 16) -> 2
                else -> 1
            }
            var val2 = asUintN(auto * 8, v)
            val bytes = ArrayList<Int>()
            for (i in 0 until auto) {
                bytes.add(0, val2.and(BigInteger.valueOf(0xff)).toInt())
                val2 = val2.shiftRight(8)
            }
            return bytes
        }
        var clean = input.toString().trim().removePrefix("0x").removePrefix("0X").replace(Regex("[\\s_]"), "")
        if (clean.length % 2 != 0) clean = "0$clean"
        val bytes = ArrayList<Int>()
        var i = 0
        while (i < clean.length) { bytes.add(Integer.parseInt(clean.substring(i, i + 2), 16)); i += 2 }
        if (widthBytes != null && bytes.size < widthBytes) {
            while (bytes.size < widthBytes) bytes.add(0, 0)
        }
        return bytes
    }

    private fun endianSwap(args: JSONObject): JSONObject {
        require(args.has("value")) { "value required" }
        val widthBytes = if (args.has("widthBytes") && !args.isNull("widthBytes")) args.optInt("widthBytes") else null
        val be = toBytes(args.get("value"), widthBytes)
        val le = be.reversed()
        fun hexOf(b: List<Int>) = "0x" + b.joinToString("") { pad(it.toString(16), 2) }
        return JSONObject()
            .put("input", args.get("value").toString())
            .put("byteCount", be.size)
            .put("bigEndianHex", hexOf(be))
            .put("littleEndianHex", hexOf(le))
            .put("byteArray", JSONArray(be))
            .put("reversedByteArray", JSONArray(le))
            .put("hexFormatted", be.joinToString(" ") { pad(it.toString(16), 2) })
    }

    // ────────────────────────── ieee754 ──────────────────────────

    private fun f32(bits: Long): JSONObject {
        val v = Float.fromBits(bits.toInt()).toDouble()
        val sign = (bits ushr 31) and 1
        val rawExp = ((bits ushr 23) and 0xff).toInt()
        val mant = bits and 0x7fffff
        val type = when {
            rawExp == 0 -> if (mant == 0L) "zero" else "subnormal"
            rawExp == 0xff -> if (mant == 0L) "infinity" else "nan"
            else -> "normal"
        }
        return JSONObject()
            .put("value", v)
            .put("hex", "0x" + pad(bits.toString(16), 8))
            .put("binary", "0b" + pad(bits.toString(2), 32))
            .put("signBit", sign)
            .put("sign", if (sign == 1L) "-" else "+")
            .put("rawExponentHex", "0x" + pad(rawExp.toString(16), 2))
            .put("rawExponentDec", rawExp)
            .put("biasedExponent", if (rawExp == 0) -126 else rawExp - 127)
            .put("mantissaHex", "0x" + pad(mant.toString(16), 6))
            .put("mantissaFraction", mant.toDouble() / Math.pow(2.0, 23.0))
            .put("type", type)
    }

    private fun f64(bits: Long): JSONObject {
        val v = java.lang.Double.longBitsToDouble(bits)
        val sign = (bits ushr 63) and 1
        val rawExp = ((bits ushr 52) and 0x7ff).toInt()
        val mant = bits and 0xfffffffffffffL
        val type = when {
            rawExp == 0 -> if (mant == 0L) "zero" else "subnormal"
            rawExp == 0x7ff -> if (mant == 0L) "infinity" else "nan"
            else -> "normal"
        }
        return JSONObject()
            .put("value", v)
            .put("hex", "0x" + pad(bits.toString(16), 16))
            .put("binary", "0b" + pad(bits.toString(2), 64))
            .put("signBit", sign)
            .put("sign", if (sign == 1L) "-" else "+")
            .put("rawExponentHex", "0x" + pad(rawExp.toString(16), 3))
            .put("rawExponentDec", rawExp)
            .put("biasedExponent", if (rawExp == 0) -1022 else rawExp - 1023)
            .put("mantissaHex", "0x" + pad(mant.toString(16), 13))
            .put("mantissaFraction", mant.toDouble() / Math.pow(2.0, 52.0))
            .put("type", type)
    }

    private fun ieee754Convert(args: JSONObject): JSONObject {
        require(args.has("value")) { "value required" }
        val precision = args.optString("precision", "both").ifBlank { "both" }
        val raw = args.get("value")
        val isHex = raw is String && (raw.startsWith("0x", true) || Regex("^[0-9a-fA-F]+$").matches(raw))
        if (isHex) {
            var clean = raw.toString().replace(Regex("^0x", RegexOption.IGNORE_CASE), "")
            val byteLen = if (clean.length <= 8) 4 else 8
            clean = pad(clean, byteLen * 2)
            return if (byteLen == 4) {
                val bits = clean.toLong(16)
                val out = JSONObject().put("input", raw.toString())
                if (precision != "float64") out.put("float32", f32(bits))
                if (precision != "float32") out.put("float64", f64(java.lang.Double.doubleToRawLongBits(java.lang.Float.intBitsToFloat(bits.toInt()).toDouble())))
                out
            } else {
                val bits = parseUnsignedHex64(clean)
                val out = JSONObject().put("input", raw.toString())
                if (precision != "float32") out.put("float64", f64(bits))
                if (precision != "float64") out.put("float32", f32(java.lang.Float.floatToRawIntBits(Double.fromBits(bits).toFloat()).toLong() and 0xffffffffL))
                out
            }
        }
        val d = when (raw) { is Number -> raw.toDouble(); else -> raw.toString().toDoubleOrNull() ?: throw IllegalArgumentException("bad float '$raw'") }
        val out = JSONObject().put("input", raw.toString())
        if (precision != "float64") out.put("float32", f32(java.lang.Float.floatToRawIntBits(d.toFloat()).toLong() and 0xffffffffL))
        if (precision != "float32") out.put("float64", f64(java.lang.Double.doubleToRawLongBits(d)))
        return out
    }

    private fun parseUnsignedHex64(hex: String): Long {
        var v = 0L
        for (c in hex) v = (v shl 4) or c.digitToInt(16).toLong()
        return v
    }

    // ────────────────────────── crypto ──────────────────────────

    private fun inputBytes(data: String, fmt: String): ByteArray {
        val clean = data.replace(Regex("^0x", RegexOption.IGNORE_CASE), "").replace(Regex("[\\s_]"), "")
        return if (fmt == "hex") {
            val h = if (clean.length % 2 != 0) "0$clean" else clean
            ByteArray(h.length / 2) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } else data.toByteArray(Charsets.UTF_8)
    }

    private fun hash(algorithm: String, data: String, fmt: String): JSONObject {
        val alg = when (algorithm.lowercase()) { "md5" -> "MD5"; "sha1" -> "SHA-1"; "sha256" -> "SHA-256"; else -> throw IllegalArgumentException("algorithm must be md5|sha1|sha256") }
        val digest = MessageDigest.getInstance(alg).digest(inputBytes(data, fmt))
        val hex = digest.joinToString("") { pad((it.toInt() and 0xff).toString(16), 2) }
        return JSONObject().put("algorithm", algorithm.lowercase()).put("input", data)
            .put("hex", hex).put("base64", Base64.getEncoder().encodeToString(digest))
    }

    private fun crc32(data: String, fmt: String): JSONObject {
        val buf = inputBytes(data, fmt)
        var crc = -1
        for (b in buf) {
            var byte = b.toInt() and 0xff
            for (j in 0 until 8) {
                val bit = (byte xor crc) and 1
                crc = crc ushr 1
                if (bit != 0) crc = crc xor 0xedb88320.toInt()
                byte = byte ushr 1
            }
        }
        val res = (crc.inv()).toLong() and 0xffffffffL
        return JSONObject().put("checksumHex", "0x" + pad(res.toString(16), 8)).put("checksumDec", res)
    }

    private fun crc16(data: String, variant: String, fmt: String): JSONObject {
        val buf = inputBytes(data, fmt)
        var crc = if (variant == "modbus") 0xffff else 0x0000
        val poly = if (variant == "modbus") 0xa001 else 0x1021
        if (variant == "modbus") {
            for (b in buf) {
                crc = crc xor (b.toInt() and 0xff)
                for (j in 0 until 8) {
                    crc = if ((crc and 1) != 0) ((crc ushr 1) xor poly) and 0xffff else crc ushr 1
                }
            }
        } else {
            for (b in buf) {
                crc = crc xor ((b.toInt() and 0xff) shl 8)
                for (j in 0 until 8) {
                    crc = if ((crc and 0x8000) != 0) ((crc shl 1) xor poly) and 0xffff else (crc shl 1) and 0xffff
                }
            }
        }
        return JSONObject().put("variant", variant).put("checksumHex", "0x" + pad(crc.toString(16), 4)).put("checksumDec", crc)
    }

    private fun modPow(a: BigInteger, b: BigInteger, m: BigInteger): JSONObject {
        require(m.signum() > 0) { "modulus must be positive" }
        require(b.signum() >= 0) { "negative exponent not supported; use mod_inverse first" }
        val r = a.mod(m).modPow(b, m)
        val hex = "0x" + r.toString(16)
        return JSONObject().put("base", a.toString()).put("exponent", b.toString()).put("modulus", m.toString())
            .put("resultDec", r.toString()).put("resultHex", hex).put("result", hex)
    }

    private fun modInverse(a: BigInteger, m: BigInteger): JSONObject {
        require(m.signum() > 0) { "modulus must be positive" }
        val inv = try { a.modInverse(m) } catch (e: ArithmeticException) { throw IllegalArgumentException("modular inverse does not exist for a=$a mod $m") }
        return JSONObject().put("a", a.toString()).put("modulus", m.toString())
            .put("resultDec", inv.toString()).put("resultHex", "0x" + inv.toString(16)).put("result", "0x" + inv.toString(16))
    }

    private fun gcd(a: BigInteger, b: BigInteger): JSONObject {
        val g = a.abs().gcd(b.abs())
        return JSONObject().put("gcdDec", g.toString()).put("gcdHex", "0x" + g.toString(16)).put("result", g.toString())
    }

    private fun cryptoCalc(args: JSONObject): JSONObject {
        val action = args.optString("action")
        val fmt = args.optString("inputFormat", "text").ifBlank { "text" }
        return when (action) {
            "hash" -> hash(args.optString("algorithm", "md5").ifBlank { "md5" }, requiredStr(args, "data"), fmt)
            "crc32" -> crc32(requiredStr(args, "data"), fmt)
            "crc16" -> crc16(requiredStr(args, "data"), args.optString("crcVariant", "modbus").ifBlank { "modbus" }, fmt)
            "mod_pow" -> modPow(num(args, "a"), num(args, "b"), num(args, "modulus"))
            "mod_inverse" -> modInverse(num(args, "a"), num(args, "modulus"))
            "gcd" -> gcd(num(args, "a"), num(args, "b"))
            else -> throw IllegalArgumentException("bad action: $action (hash|crc32|crc16|mod_pow|mod_inverse|gcd)")
        }
    }

    private fun requiredStr(args: JSONObject, key: String): String {
        if (!args.has(key) || args.isNull(key)) throw IllegalArgumentException("missing arg '$key'")
        return args.get(key).toString()
    }

    // ────────────────────────── codec ──────────────────────────

    private fun dataCodec(args: JSONObject): JSONObject {
        val action = args.optString("action")
        val input = requiredStr(args, "input")
        val fmt = args.optString("format", "text").ifBlank { "text" }
        val urlSafe = args.optBoolean("urlSafe", false)
        return when (action) {
            "to_base64" -> {
                val bytes = inputBytes(input, fmt)
                var b64 = Base64.getEncoder().encodeToString(bytes)
                if (urlSafe) b64 = b64.replace("+", "-").replace("/", "_").replace(Regex("=+$"), "")
                JSONObject().put("base64", b64).put("result", b64)
            }
            "from_base64" -> {
                var clean = input.trim().replace("-", "+").replace("_", "/")
                while (clean.length % 4 != 0) clean += "="
                val bytes = Base64.getDecoder().decode(clean)
                val out = if (fmt == "hex") "0x" + bytes.joinToString("") { pad((it.toInt() and 0xff).toString(16), 2) } else String(bytes, Charsets.UTF_8)
                JSONObject().put("decoded", out).put("result", out)
            }
            "to_hex" -> {
                val bytes = input.toByteArray(Charsets.UTF_8)
                val hex = bytes.joinToString("") { pad((it.toInt() and 0xff).toString(16), 2) }
                JSONObject().put("hex", "0x" + hex).put("hexFormatted", bytes.joinToString(" ") { pad((it.toInt() and 0xff).toString(16), 2) })
                    .put("length", bytes.size).put("result", "0x" + hex)
            }
            "from_hex" -> {
                val clean = input.replace(Regex("^0x", RegexOption.IGNORE_CASE), "").replace(Regex("[\\s_]"), "")
                val h = if (clean.length % 2 != 0) "0$clean" else clean
                val bytes = ByteArray(h.length / 2) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
                JSONObject().put("decoded", String(bytes, Charsets.UTF_8)).put("result", String(bytes, Charsets.UTF_8))
            }
            "url_encode" -> JSONObject().put("encoded", URLEncoder.encode(input, "UTF-8")).put("result", URLEncoder.encode(input, "UTF-8"))
            "url_decode" -> JSONObject().put("decoded", URLDecoder.decode(input, "UTF-8")).put("result", URLDecoder.decode(input, "UTF-8"))
            else -> throw IllegalArgumentException("bad action: $action")
        }
    }

    // ────────────────────────── batch_calc ──────────────────────────

    /**
     * 链式批量执行。steps = [{op, args}], args 中的值可为引用
     * {"$step": i} 或 {"$step": i, "field": "resultHex"}。
     * 与 calculate-mcp 一致：只允许引用更早的步骤；依赖失败的步骤标记 skipped，其余照常执行。
     */
    fun runBatch(steps: JSONArray): JSONObject {
        val n = steps.length()
        require(n >= 1) { "steps must not be empty" }
        val deps = Array(n) { HashSet<Int>() }
        for (i in 0 until n) {
            val step = steps.getJSONObject(i)
            collectDeps(step.optJSONObject("args") ?: JSONObject(), i, deps[i])
        }
        val results = JSONArray()
        val outcomes = arrayOfNulls<JSONObject>(n)
        for (i in 0 until n) {
            val step = steps.getJSONObject(i)
            val op = step.optString("op")
            val failedDep = deps[i].firstOrNull { outcomes[it]?.optString("status") != "ok" }
            if (failedDep != null) {
                results.put(JSONObject().put("step", i).put("op", op).put("status", "skipped").put("reason", "dependency step $failedDep did not succeed"))
                continue
            }
            try {
                val rawArgs = step.optJSONObject("args") ?: JSONObject()
                val args = resolveRefs(rawArgs, outcomes) as JSONObject
                val r = runOp(op, args)
                outcomes[i] = JSONObject().put("status", "ok")
                results.put(JSONObject().put("step", i).put("op", op).put("status", "ok").put("result", r))
            } catch (e: Exception) {
                outcomes[i] = JSONObject().put("status", "error")
                results.put(JSONObject().put("step", i).put("op", op).put("status", "error").put("error", e.message ?: e.toString()))
            }
        }
        return JSONObject().put("results", results)
    }

    private fun collectDeps(value: Any?, cur: Int, deps: MutableSet<Int>) {
        when (value) {
            is JSONObject -> {
                if (value.has("\$step")) {
                    val t = value.optInt("\$step", -1)
                    if (t < 0 || t >= cur) throw IllegalArgumentException(if (cur == 0) "step 0 cannot reference an earlier step" else "step $cur: \$step must reference an earlier step (0..${cur - 1})")
                    deps.add(t)
                    return
                }
                for (k in value.keys()) collectDeps(value.get(k), cur, deps)
            }
            is JSONArray -> for (i in 0 until value.length()) collectDeps(value.get(i), cur, deps)
            else -> {}
        }
    }

    private fun resolveRefs(value: Any?, outcomes: Array<JSONObject?>): Any? {
        return when (value) {
            is JSONObject -> {
                if (value.has("\$step")) {
                    val idx = value.optInt("\$step", -1)
                    val ok = outcomes.getOrNull(idx)
                    val res = ok?.optJSONObject("result") ?: throw IllegalArgumentException("step $idx result unavailable")
                    if (!value.has("field")) res
                    else {
                        val f = value.optString("field")
                        if (!res.has(f)) throw IllegalArgumentException("step $idx result has no field \"$f\"")
                        res.get(f)
                    }
                } else {
                    val o = JSONObject()
                    for (k in value.keys()) o.put(k, resolveRefs(value.get(k), outcomes))
                    o
                }
            }
            is JSONArray -> {
                val a = JSONArray()
                for (i in 0 until value.length()) a.put(resolveRefs(value.get(i), outcomes))
                a
            }
            else -> value
        }
    }
}
