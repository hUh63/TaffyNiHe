package com.exbin.app.elf.pseudoc.r2dec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v4.9: 输出标签清理 — 消除裸地址标签 (L_1b4c 等), 分三步:
 * <ol>
 *   <li><b>跳转板折叠</b>: {@code if (c) { L_x: goto L_y; }} → {@code if (c) goto L_y;}
 *       (then 内单指令跳板合并), 其它 {@code goto L_x} 引用重定向到 L_y (链式解析)。
 *       只处理 if-then 内的跳转板 — 顺序位置的跳转板可能被 fall-through 进入,
 *       删除会破坏语义, 保守跳过。</li>
 *   <li><b>单前驱尾块内联</b>: {@code L_x: <内容>; goto L_y;} 且仅 1 处 {@code goto L_x}
 *       引用、内容无 {} 嵌套、引用行无 if 前缀 → 内容复制到引用处, 删标签块。</li>
 *   <li><b>语义命名</b>: 剩余必需标签 (循环头/汇合点, 图论上不可消除) 按角色命名 —
 *       向后 goto 引用 (回边) → {@code loop_N}, 否则 → {@code merge_N} (按地址升序)。</li>
 * </ol>
 */
public final class LabelCleanupPass {

    private static final Pattern LABEL = Pattern.compile("^\\s*(L_[0-9a-f]+):\\s*$");
    private static final Pattern GOTO = Pattern.compile("^\\s*goto (L_[0-9a-f]+);\\s*$");
    private static final Pattern GOTO_INLINE = Pattern.compile("goto (L_[0-9a-f]+)");
    private static final Pattern IF_OPEN = Pattern.compile("^(\\s*)if \\((.*)\\) \\{\\s*$");

    private LabelCleanupPass() {
    }

    /** 入口: 折叠 → 内联 → 语义命名. */
    public static List<String> optimize(List<String> in) {
        if (in == null || in.isEmpty()) return in;
        List<String> body = new ArrayList<>(in);
        body = foldJumps(body);
        body = inlineTailBlocks(body);
        body = nameLabels(body);
        return body;
    }

    // ── Step 1: 跳转板折叠 ──

    private static List<String> foldJumps(List<String> in) {
        Map<String, String> redirect = new HashMap<>();
        boolean changed;
        do {
            changed = false;
            List<String> out = new ArrayList<>(in.size());
            for (int i = 0; i < in.size(); i++) {
                String line = in.get(i);
                Matcher mL = LABEL.matcher(line);
                if (!mL.matches()) {
                    out.add(line);
                    continue;
                }
                String labelX = mL.group(1);
                int j = i + 1;
                while (j < in.size() && in.get(j).trim().isEmpty()) j++;
                if (j >= in.size()) { out.add(line); continue; }
                Matcher mG = GOTO.matcher(in.get(j));
                if (!mG.matches()) { out.add(line); continue; }
                String labelY = mG.group(1);
                int closeIdx = j + 1;
                while (closeIdx < in.size() && in.get(closeIdx).trim().isEmpty()) closeIdx++;
                boolean inIfThen = i > 0
                        && IF_OPEN.matcher(in.get(i - 1)).matches()
                        && closeIdx < in.size()
                        && in.get(closeIdx).trim().equals("}");
                // 顺序跳转板: 仅当后继第一个非空行是 L_y: 定义 (fall-through 等价安全)
                Matcher mSeq = closeIdx < in.size() ? LABEL.matcher(in.get(closeIdx)) : null;
                boolean seqSafe = mSeq != null && mSeq.matches() && mSeq.group(1).equals(labelY);
                if (inIfThen) {
                    out.remove(out.size() - 1); // 移除 if 开行
                    String ifLine = in.get(i - 1);
                    Matcher mIf = IF_OPEN.matcher(ifLine);
                    if (!mIf.matches()) { out.add(line); continue; }
                    String cond = mIf.group(2);
                    out.add(mIf.group(1) + "if (" + cond + ") goto " + labelY + ";");
                    redirect.put(labelX, labelY);
                    i = closeIdx; // 循环 i++ 后跳过 } 行
                    changed = true;
                    continue;
                }
                if (seqSafe) {
                    // 删除 L_x 行 + goto 行 (均不加 out), L_y 定义行保留 (i=j, i++ 后正常处理)
                    redirect.put(labelX, labelY);
                    i = j;
                    changed = true;
                    continue;
                }
                out.add(line);
            }
            in = out;
        } while (changed);
        if (!redirect.isEmpty()) {
            in = resolveRedirects(in, redirect);
        }
        return in;
    }

    private static List<String> resolveRedirects(List<String> in, Map<String, String> redirect) {
        List<String> out = new ArrayList<>(in.size());
        for (String line : in) {
            Matcher m = GOTO_INLINE.matcher(line);
            if (!m.find()) { out.add(line); continue; }
            StringBuffer sb = new StringBuffer();
            do {
                String t = resolveTarget(m.group(1), redirect);
                if (t != null) m.appendReplacement(sb, Matcher.quoteReplacement("goto " + t));
            } while (m.find());
            m.appendTail(sb);
            out.add(sb.toString());
        }
        return out;
    }

    /** 链式解析重定向终点, 无环返回 null (异常情况, 原样保留). */
    private static String resolveTarget(String name, Map<String, String> redirect) {
        if (!redirect.containsKey(name)) return null;
        String cur = name;
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (redirect.containsKey(cur)) {
            if (!seen.add(cur)) return null; // 环
            cur = redirect.get(cur);
        }
        return cur;
    }

    // ── Step 2: 单前驱尾块内联 ──

    private static List<String> inlineTailBlocks(List<String> in) {
        Map<String, LabelBlock> blocks = collectLabelBlocks(in);
        if (blocks.isEmpty()) return in;
        Map<String, Integer> refCount = new HashMap<>();
        for (String line : in) {
            Matcher m = GOTO_INLINE.matcher(line);
            while (m.find()) {
                String name = m.group(1);
                refCount.merge(name, 1, Integer::sum);
            }
        }
        List<String> out = new ArrayList<>(in.size());
        for (int i = 0; i < in.size(); i++) {
            String line = in.get(i);
            Matcher mL = LABEL.matcher(line);
            if (!mL.matches()) {
                out.add(line);
                continue;
            }
            String labelX = mL.group(1);
            LabelBlock blk = blocks.get(labelX);
            if (blk == null || !blk.canInline || refCount.getOrDefault(labelX, 0) != 1) {
                out.add(line);
                continue;
            }
            int refIdx = -1;
            for (int k = 0; k < out.size(); k++) {
                Matcher m = GOTO_INLINE.matcher(out.get(k));
                if (m.find() && m.group(1).equals(labelX)) { refIdx = k; break; }
            }
            if (refIdx < 0 || out.get(refIdx).trim().startsWith("if ")) {
                out.add(line); // 引用行带 if 前缀或未找到 → 保守跳过
                continue;
            }
            String indentRef = leadingWhitespace(out.get(refIdx));
            List<String> expanded = new ArrayList<>();
            for (String cl : blk.lines) {
                String content = cl.trim();
                if (content.isEmpty()) continue;
                expanded.add(indentRef + content); // canInline 保证内容平铺无嵌套, 与引用行同层
            }
            out.remove(refIdx);
            out.addAll(refIdx, expanded);
            i += blk.lines.size(); // 跳过标签块本体
        }
        return out;
    }

    /** 收集标签块: 从 L_x: 到下一个标签 / 独立 } / EOF. */
    private static Map<String, LabelBlock> collectLabelBlocks(List<String> in) {
        Map<String, LabelBlock> blocks = new HashMap<>();
        for (int i = 0; i < in.size(); i++) {
            Matcher mL = LABEL.matcher(in.get(i));
            if (!mL.matches()) continue;
            String name = mL.group(1);
            LabelBlock blk = new LabelBlock();
            int j = i + 1;
            boolean anyContent = false;
            while (j < in.size()) {
                String t = in.get(j).trim();
                if (t.isEmpty()) { j++; continue; }
                if (LABEL.matcher(in.get(j)).matches()) break; // 下一个标签
                if (t.equals("}")) break;                       // 外层块结束
                blk.lines.add(in.get(j));
                if (GOTO.matcher(in.get(j)).matches()) blk.endsWithGoto = true;
                if (t.contains("{") || t.contains("}")) blk.hasBrace = true;
                anyContent = true;
                j++;
            }
            blk.canInline = !blk.hasBrace && anyContent && blk.endsWithGoto;
            blocks.put(name, blk);
        }
        return blocks;
    }

    // ── Step 3: 语义命名 ──

    private static List<String> nameLabels(List<String> in) {
        List<LabelDef> defs = new ArrayList<>();
        Map<String, Integer> labelLine = new HashMap<>();
        for (int i = 0; i < in.size(); i++) {
            Matcher mL = LABEL.matcher(in.get(i));
            if (mL.matches()) {
                String name = mL.group(1);
                labelLine.put(name, i);
                long addr;
                try { addr = Long.parseLong(name.substring(2), 16); } catch (NumberFormatException e) { continue; }
                defs.add(new LabelDef(name, addr));
            }
        }
        defs.sort((a, b) -> Long.compare(a.addr, b.addr));
        if (defs.isEmpty()) return in;

        Map<String, String> rename = new HashMap<>();
        int loopN = 0, mergeN = 0;
        for (LabelDef d : defs) {
            boolean backEdge = false;
            for (int i = labelLine.get(d.name) + 1; i < in.size(); i++) {
                Matcher m = GOTO_INLINE.matcher(in.get(i));
                while (m.find()) {
                    if (m.group(1).equals(d.name)) { backEdge = true; break; }
                }
                if (backEdge) break;
            }
            String newName = backEdge ? "loop_" + (++loopN) : "merge_" + (++mergeN);
            rename.put(d.name, newName);
        }
        List<String> out = new ArrayList<>(in.size());
        for (String line : in) {
            out.add(renameLabels(line, rename));
        }
        return out;
    }

    private static String renameLabels(String line, Map<String, String> rename) {
        Matcher mL = LABEL.matcher(line);
        if (mL.matches()) {
            String nn = rename.get(mL.group(1));
            if (nn != null) {
                String ws = line.substring(0, line.length() - line.trim().length());
                return ws + nn + ":";
            }
            return line;
        }
        Matcher m = GOTO_INLINE.matcher(line);
        if (!m.find()) return line;
        StringBuffer sb = new StringBuffer();
        do {
            String nn = rename.get(m.group(1));
            if (nn != null) m.appendReplacement(sb, Matcher.quoteReplacement("goto " + nn));
        } while (m.find());
        m.appendTail(sb);
        return sb.toString();
    }

    // ── helpers ──

    private static String leadingWhitespace(String s) {
        int k = 0;
        while (k < s.length() && s.charAt(k) == ' ') k++;
        return s.substring(0, k);
    }

    private static final class LabelBlock {
        final List<String> lines = new ArrayList<>();
        boolean hasBrace;
        boolean endsWithGoto;
        boolean canInline;
    }

    private static final class LabelDef {
        final String name;
        final long addr;
        LabelDef(String name, long addr) { this.name = name; this.addr = addr; }
    }
}
