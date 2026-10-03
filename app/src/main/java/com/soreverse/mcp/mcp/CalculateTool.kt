package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.CalcEngine
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONObject

/**
 * 塔菲逆核：内置「高精度计算器」MCP 工具（移植自 calculate-mcp）。
 *
 * 单一网关 [taffy_calculate]：`op` 选择运算，其余参数按 op 取用；
 * 也可传 `steps`（[{op, args}]）走链式批量（batch，支持 {"$step":0,"field":"resultHex"} 引用）。
 *
 * 覆盖：算术/统计/三角、任意精度进制转换、位运算、端序、IEEE-754、哈希/CRC/模运算、Base64/Hex/URL。
 * 内置实现（CalcEngine，纯 JVM），不依赖任何外部进程；分析页「计算器」视图同样调用它。
 */
object CalculateTool {

    private val ARG_KEYS = listOf(
        "value", "values", "a", "b", "operation", "bitWidth", "number", "numbers",
        "firstNumber", "secondNumber", "minuend", "subtrahend", "numerator", "denominator",
        "data", "inputFormat", "algorithm", "crcVariant", "modulus",
        "action", "input", "format", "urlSafe", "precision", "widthBytes",
    )

    val calculate: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_calculate",
            "【高精度计算器】确定性数值/数据计算，避免大模型心算幻觉。op 支持：" +
                "add/subtract/multiply/division/sum/modulo/floor/ceiling/round（算术）、" +
                "mean/median/mode/min/max（统计）、sin/cos/tan/arcsin/arccos/arctan/radiansToDegrees/degreesToRadians（三角）、" +
                "int_convert（任意精度↔Hex/Dec/Bin/Oct/8-16-32-64位有/无符号/端序/ASCII）、bitwise（and/or/xor/not/shl/shr/sar/rol/ror，位宽8/16/32/64）、" +
                "endian_swap（大小端）、ieee754_convert（Float32/64 位域分解）、" +
                "crypto_calc（action=hash|crc32|crc16|mod_pow|mod_inverse|gcd）、data_codec（action=to_base64|from_base64|to_hex|from_hex|url_encode|url_decode）。" +
                "链式批量：传 steps=[{op,args}]（args 可引用更早结果 {\"$step\":0,\"field\":\"resultHex\"}）。",
            "Deterministic high-precision calculator that removes LLM mental-math errors. op: arithmetic/stats/trig, " +
                "int_convert (arbitrary precision <-> hex/dec/bin/oct, 8/16/32/64-bit signed/unsigned, endian, ASCII), " +
                "bitwise (and/or/xor/not/shl/shr/sar/rol/ror at 8/16/32/64), endian_swap, ieee754_convert, " +
                "crypto_calc (hash/crc32/crc16/mod_pow/mod_inverse/gcd), data_codec (base64/hex/url). " +
                "Chained batch via steps=[{op,args}] with {\"$step\":0,\"field\":\"resultHex\"} references.",
            "analyze",
            ToolClass.EXTRA,
            heavy = false,
        ) {
            objectSchema(props {
                "op".oneOf(
                    "运算名（见工具描述）。与 steps 二选一。",
                    "add", "subtract", "multiply", "division", "sum", "modulo", "floor", "ceiling", "round",
                    "mean", "median", "mode", "min", "max",
                    "sin", "cos", "tan", "arcsin", "arccos", "arctan", "radiansToDegrees", "degreesToRadians",
                    "int_convert", "bitwise", "endian_swap", "ieee754_convert", "crypto_calc", "data_codec",
                )
                "value" str "int_convert/endian_swap/ieee754_convert 的单值（如 '0x1A'、-42、3.14）"
                "values" arr "int_convert 多值数组，如 [\"0x1A\", 12345]"
                "a" str "bitwise 第一操作数 / crypto_calc 的 a"
                "b" str "bitwise 第二操作数/移位数 / crypto_calc 的 b"
                "operation".oneOf("bitwise 运算", "and", "or", "xor", "not", "shl", "shr", "sar", "rol", "ror")
                "bitWidth" int "bitwise 位宽：8|16|32|64（默认 32）"
                "number" num "单数字（floor/ceiling/round/三角/角度转换）"
                "numbers" arr "数字数组（sum/mean/median/mode/min/max）"
                "firstNumber" num "add/multiply 第一数"
                "secondNumber" num "add/multiply 第二数"
                "minuend" num "subtract 被减数"
                "subtrahend" num "subtract 减数"
                "numerator" num "division/modulo 被除数"
                "denominator" num "division/modulo 除数"
                "action".oneOf("crypto_calc/data_codec 的动作", "hash", "crc32", "crc16", "mod_pow", "mod_inverse", "gcd", "to_base64", "from_base64", "to_hex", "from_hex", "url_encode", "url_decode")
                "data" str "crypto_calc 的输入（hash/crc32/crc16）"
                "inputFormat".oneOf("data 的格式", "text", "hex")
                "algorithm".oneOf("哈希算法", "md5", "sha1", "sha256")
                "crcVariant".oneOf("CRC16 变体", "ccitt", "modbus")
                "modulus" str "crypto_calc 模数（mod_pow/mod_inverse）"
                "input" str "data_codec 的输入字符串"
                "format".oneOf("data_codec 输入/输出格式", "text", "hex")
                "urlSafe" bool "Base64 使用 URL-safe 字母表"
                "precision".oneOf("ieee754 精度", "float32", "float64", "both")
                "widthBytes" int "endian_swap 目标字节长度（2/4/8）"
                "steps" arr "链式批量：数组，每项 {op, args}，args 可用 {\"$step\":i,\"field\":\"...\"} 引用更早结果"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val steps = args.optJSONArray("steps")
            if (steps != null && steps.length() > 0) {
                return try {
                    ok(CalcEngine.runBatch(steps).put("tool", "taffy_calculate").put("mode", "batch"))
                } catch (e: Exception) {
                    err("CALC_ERROR", e.message ?: "batch failed", "steps", "")
                }
            }
            val op = args.str("op").trim()
            if (op.isBlank()) {
                return err("INVALID_ARGUMENT", "缺少 op（或提供 steps 走链式批量）", "op", "")
            }
            if (op !in CalcEngine.OPS) {
                return err("INVALID_ARGUMENT", "未知 op：$op", "op", op)
            }
            val a = JSONObject()
            for (k in ARG_KEYS) if (args.has(k) && !args.isNull(k)) a.put(k, args.get(k))
            return try {
                ok(CalcEngine.runOp(op, a).put("tool", "taffy_calculate").put("op", op))
            } catch (e: Exception) {
                err("CALC_ERROR", e.message ?: "calc failed", "op", op)
            }
        }
    }

    val ALL: List<ToolHandler> = listOf(calculate)
}
