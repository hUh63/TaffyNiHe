package com.soreverse.mcp.engine.standalone;

/**
 * Hex dump utility — ported from SO逆向分析工具.
 * Produces classic hex+ASCII dump output.
 */
public class HexDump {

    public static String dump(byte[] data, long baseOffset, int from, int length) {
        if (data == null || from < 0) return "";
        int end = Math.min(from + length, data.length);
        StringBuilder sb = new StringBuilder();

        for (int i = from; i < end; i += 16) {
            // Address column
            sb.append(String.format("%08x  ", i + baseOffset));

            // Hex column
            for (int j = 0; j < 16; j++) {
                int idx = i + j;
                if (idx < end) {
                    sb.append(String.format("%02x ", data[idx] & 0xFF));
                } else {
                    sb.append("   ");
                }
                if (j == 7) sb.append(' ');
            }

            // ASCII column
            sb.append(" |");
            for (int j = 0; j < 16 && i + j < end; j++) {
                int b = data[i + j] & 0xFF;
                sb.append((char) (b >= 0x20 && b < 0x7F ? b : '.'));
            }
            sb.append("|\n");
        }

        return sb.toString();
    }
}
