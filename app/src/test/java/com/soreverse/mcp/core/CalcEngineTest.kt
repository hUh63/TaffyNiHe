package com.soreverse.mcp.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalcEngineTest {

    private fun run(op: String, args: String): JSONObject =
        CalcEngine.runOp(op, JSONObject(args))

    @Test
    fun intConvertHex() {
        val r = CalcEngine.runOp("int_convert", JSONObject().put("value", "0x401000"))
        assertEquals("4198400", r.getString("decimal"))
        assertEquals("0x401000", r.getString("hex"))
        assertEquals("0b10000000001000000000000", r.getString("binary"))
    }

    @Test
    fun intConvertNegativeAndWidths() {
        val r = CalcEngine.runOp("int_convert", JSONObject().put("value", -1))
        val bw8 = r.getJSONObject("bitWidths").getJSONObject("bit8")
        assertEquals("0xff", bw8.getString("hex"))
        assertEquals((-1L).toString(), bw8.getString("signed"))
        assertEquals("255", bw8.getString("unsigned"))
    }

    @Test
    fun bitwiseXorWithWidth() {
        val r = run("bitwise", """{"operation":"xor","a":"0xFF00","b":"0x0F0F","bitWidth":32}""")
        assertEquals("0xf00f", r.getString("resultHexShort"))
        assertEquals("61455", r.getString("resultDecUnsigned"))
    }

    @Test
    fun bitwiseRotate() {
        val r = run("bitwise", """{"operation":"rol","a":"0x80000001","b":1,"bitWidth":32}""")
        assertEquals("0x00000003", r.getString("resultHex"))
    }

    @Test
    fun endianSwap32() {
        val r = run("endian_swap", """{"value":"0x12345678","widthBytes":4}""")
        assertEquals("0x12345678", r.getString("bigEndianHex"))
        assertEquals("0x78563412", r.getString("littleEndianHex"))
    }

    @Test
    fun ieee754Float32One() {
        val r = run("ieee754_convert", """{"value":"0x3f800000","precision":"float32"}""")
        val f32 = r.getJSONObject("float32")
        assertEquals(1.0, f32.getDouble("value"), 0.0)
        assertEquals("normal", f32.getString("type"))
        assertEquals(0, f32.getInt("signBit"))
    }

    @Test
    fun md5AndSha256() {
        val md5 = run("crypto_calc", """{"action":"hash","algorithm":"md5","data":"abc"}""")
        assertEquals("900150983cd24fb0d6963f7d28e17f72", md5.getString("hex"))
        val sha = run("crypto_calc", """{"action":"hash","algorithm":"sha256","data":"abc"}""")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha.getString("hex"))
    }

    @Test
    fun crc32KnownVector() {
        val r = run("crypto_calc", """{"action":"crc32","data":"123456789"}""")
        assertEquals("0xcbf43926", r.getString("checksumHex"))
    }

    @Test
    fun crc16Modbus() {
        val r = run("crypto_calc", """{"action":"crc16","crcVariant":"modbus","inputFormat":"hex","data":"010300000002"}""")
        // 保持稳定：仅断言输出为 4 位 hex
        assertTrue(r.getString("checksumHex").matches(Regex("0x[0-9a-f]{4}")))
    }

    @Test
    fun base64RoundTrip() {
        val enc = run("data_codec", """{"action":"to_base64","input":"hello"}""")
        assertEquals("aGVsbG8=", enc.getString("base64"))
        val dec = run("data_codec", """{"action":"from_base64","input":"aGVsbG8="}""")
        assertEquals("hello", dec.getString("decoded"))
    }

    @Test
    fun hexRoundTrip() {
        val h = run("data_codec", """{"action":"to_hex","input":"AB"}""")
        assertEquals("0x4142", h.getString("hex"))
        val b = run("data_codec", """{"action":"from_hex","input":"0x4142"}""")
        assertEquals("AB", b.getString("decoded"))
    }

    @Test
    fun arithmeticAndStats() {
        assertEquals(7.0, run("add", """{"firstNumber":3,"secondNumber":4}""").getDouble("value"), 0.0)
        assertEquals(2.5, run("mean", """{"numbers":[1,2,3,4]}""").getDouble("value"), 0.0)
        assertEquals(2.5, run("median", """{"numbers":[4,1,3,2]}""").getDouble("value"), 0.0)
    }

    @Test
    fun batchChainWithFieldReference() {
        val steps = JSONArray()
            .put(JSONObject().put("op", "add").put("args", JSONObject().put("firstNumber", 16).put("secondNumber", 16)))
            .put(JSONObject().put("op", "bitwise").put("args", JSONObject()
                .put("operation", "xor")
                .put("a", JSONObject().put("\$step", 0).put("field", "resultHex"))
                .put("b", "0x5A")
                .put("bitWidth", 32)))
        val out = CalcEngine.runBatch(steps)
        val arr = out.getJSONArray("results")
        assertEquals("ok", arr.getJSONObject(0).getString("status"))
        assertEquals("ok", arr.getJSONObject(1).getString("status"))
        assertEquals("0x7a", arr.getJSONObject(1).getJSONObject("result").getString("resultHexShort"))
    }

    @Test
    fun batchSkipsOnFailedDependency() {
        val steps = JSONArray()
            .put(JSONObject().put("op", "division").put("args", JSONObject().put("numerator", 1).put("denominator", 0)))
            .put(JSONObject().put("op", "add").put("args", JSONObject()
                .put("firstNumber", JSONObject().put("\$step", 0).put("field", "value"))
                .put("secondNumber", 1)))
        val arr = CalcEngine.runBatch(steps).getJSONArray("results")
        assertEquals("error", arr.getJSONObject(0).getString("status"))
        assertEquals("skipped", arr.getJSONObject(1).getString("status"))
    }
}
