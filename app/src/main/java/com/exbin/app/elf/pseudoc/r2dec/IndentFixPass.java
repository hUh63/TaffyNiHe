package com.exbin.app.elf.pseudoc.r2dec;

import java.util.ArrayList;
import java.util.List;

/**
 * v3.5: 缩进重建 pass — 按大括号深度重算每行缩进 (4 空格/级).
 * <p>注释行 / 空行 / 标签行不参与深度计数: 空行原样保留, 注释按当前深度缩进,
 * 标签 (L_XXXX:) 顶格输出. 行首闭括号先退一级再输出, 保证 {@code } else {}
 * 与独立 {@code }} 缩进正确.
 */
public final class IndentFixPass {

    private IndentFixPass() {
    }

    /**
     * @param lines 伪 C 输出行 (可含既有缩进)
     * @return 按大括号深度重建缩进后的行列表
     */
    public static List<String> fix(List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        int depth = 0;
        for (String line : lines) {
            if (line == null) {
                out.add("");
                continue;
            }
            String t = line.trim();
            if (t.isEmpty()) {
                out.add("");
                continue;
            }
            boolean isLabel = t.endsWith(":")
                    && !t.startsWith("case ") && !t.startsWith("default ");
            boolean isComment = t.startsWith("//") || t.startsWith("/*") || t.startsWith("*");
            if (isLabel) {
                out.add(t);
                continue;
            }
            if (isComment) {
                out.add(indent(depth) + t);
                continue;
            }
            int open = countChar(t, '{');
            int close = countChar(t, '}');
            int lineDepth = depth;
            if (close > 0 && t.startsWith("}")) {
                lineDepth = Math.max(0, depth - 1);
            }
            out.add(indent(lineDepth) + t);
            depth = Math.max(0, depth + open - close);
        }
        return out;
    }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) n++;
        }
        return n;
    }

    private static String indent(int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append("    ");
        }
        return sb.toString();
    }
}
