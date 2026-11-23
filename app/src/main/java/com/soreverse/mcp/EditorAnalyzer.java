package com.soreverse.mcp;

import com.soreverse.mcp.core.EditorLexer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager;
import io.github.rosemoe.sora.lang.styling.CodeBlock;
import io.github.rosemoe.sora.lang.styling.MappedSpans;
import io.github.rosemoe.sora.lang.styling.SpanFactory;
import io.github.rosemoe.sora.lang.styling.Styles;
import io.github.rosemoe.sora.lang.styling.TextStyle;
import io.github.rosemoe.sora.lang.styling.color.ConstColor;
import io.github.rosemoe.sora.lang.styling.line.LineBackground;

/**
 * sora-editor 的着色分析器：把 {@link EditorLexer} 切出的 token 变成 Span，
 * 顺带做两件「分析器才有资格做」的事：
 *
 * 1. 行级改动标记 → 整行底色（{@link LineBackground}）；
 * 2. 代码折叠 → {@link CodeBlock}（库只在拿到 blocks 时才在行号槽画折叠三角）。
 *
 * 为什么用 Java 写这一个类：{@code SimpleAnalyzeManager} 的 {@code analyze} 签名里
 * 带 inner-class 泛型参数（{@code SimpleAnalyzeManager<V>.Delegate<V>}），Java 能精确表达，
 * Kotlin 侧覆盖时容易在 K2 上踩类型不匹配。词法本身仍在 Kotlin（可单测）。
 *
 * 折叠规则（全部按 0 基行号，这是 sora 内部约定，与 {@code Cursor.getLeftLine()} 同空间）：
 * - 括号类语言（C/Java/Kotlin/JSON/Smali/XML 以外）：`{` 与 `}` 配对；
 * - Smali：`.method` 与 `.end method` 配对；
 * - Python：按缩进（只在行尾是 `:` 的行开启块，下一行缩进变大才成立）；
 * - 单行块不产 block —— 折一行没有意义，反而多一个点不动的三角。
 */
public class EditorAnalyzer extends SimpleAnalyzeManager<Integer> {

    /** 自定义颜色槽位，避开 sora 预定义的 1..83。 */
    public static final int CID_KEYWORD = 101;
    public static final int CID_COMMENT = 102;
    public static final int CID_STRING = 103;
    public static final int CID_NUMBER = 104;
    public static final int CID_FUNC = 105;
    public static final int CID_TYPE = 106;
    public static final int CID_CONST = 107;
    public static final int CID_TAG = 108;
    public static final int CID_ATTR = 109;
    public static final int CID_DIRECTIVE = 110;
    public static final int CID_CODE = 111;

    /** 折叠块上限：超过这个数量折叠三角本身就比代码还密，也没有性能意义。 */
    private static final int MAX_BLOCKS = 5000;

    private final EditorLexer.Syntax syntax;
    private final Map<EditorLexer.Kind, Integer> colorIds;

    /** 行级差异底色：key = 0 基行号，value = ARGB。 */
    private volatile Map<Integer, Integer> diffLines = Collections.emptyMap();

    /**
     * 最近一次分析产出的块。给「上一块 / 下一块」导航用 ——
     * 库只在自己的渲染层用 blocks，没有对外暴露，所以这里留一份只读引用。
     */
    private volatile List<CodeBlock> lastBlocks = Collections.emptyList();

    public List<CodeBlock> getLastBlocks() {
        return lastBlocks;
    }

    public EditorAnalyzer(EditorLexer.Syntax syntax, Map<EditorLexer.Kind, Integer> colorIds) {
        this.syntax = syntax;
        this.colorIds = colorIds;
    }

    /**
     * 设置差异行。只是「换底色」也要重跑：sora 的 Styles 由分析器整体产出，
     * 没有增量改 lineStyle 的入口。大文件在调用方被限制成只读，所以这里不会高频重跑。
     */
    public void setDiffLines(Map<Integer, Integer> lines) {
        diffLines = lines == null ? Collections.<Integer, Integer>emptyMap() : lines;
    }

    @Override
    protected Styles analyze(StringBuilder text, SimpleAnalyzeManager<Integer>.Delegate<Integer> delegate) {
        int len = text.length();
        int lineCount = 1;
        for (int i = 0; i < len; i++) {
            if (text.charAt(i) == '\n') lineCount++;
        }

        MappedSpans.Builder builder = new MappedSpans.Builder(lineCount);
        EditorLexer.State state = new EditorLexer.State();

        List<CodeBlock> blocks = new ArrayList<>();
        Deque<int[]> braceStack = new ArrayDeque<>();
        Deque<int[]> pyStack = new ArrayDeque<>();
        Deque<int[]> smaliStack = new ArrayDeque<>();
        boolean pythonFold = syntax == EditorLexer.Syntax.PYTHON;
        boolean smaliFold = syntax == EditorLexer.Syntax.SMALI;
        boolean braceFold = !pythonFold && !smaliFold;

        int line = 0;
        int start = 0;
        while (start <= len) {
            int nl = text.indexOf("\n", start);
            int end = nl < 0 ? len : nl;
            // 只在行内做 substring：整篇 toString 会复制一份完整文本，2 MB 就是 4 MB 的临时对象
            String lineText = text.substring(start, end);
            List<EditorLexer.Token> tokens = EditorLexer.INSTANCE.tokenizeLine(lineText, syntax, state);
            for (int i = 0, n = tokens.size(); i < n; i++) {
                EditorLexer.Token t = tokens.get(i);
                Integer cid = colorIds.get(t.getKind());
                if (cid == null) continue;
                builder.add(line, SpanFactory.obtainNoExt(t.getStart(), TextStyle.makeStyle(cid)));
            }
            if (blocks.size() < MAX_BLOCKS) {
                if (pythonFold) {
                    foldPython(lineText, line, pyStack, blocks);
                } else if (smaliFold) {
                    foldSmali(lineText, line, smaliStack, blocks);
                } else {
                    foldBraces(lineText, tokens, line, braceStack, blocks);
                }
            }
            if (nl < 0) break;
            start = nl + 1;
            line++;
            if (nl + 1 >= len) break;
        }
        // 文件结束还在栈里的块：一直折到文件末尾
        int lastLine = lineCount - 1;
        while (!braceStack.isEmpty()) {
            int[] open = braceStack.pop();
            if (open[0] < lastLine && blocks.size() < MAX_BLOCKS) {
                blocks.add(block(open[0], open[1], lastLine, 0));
            }
        }
        while (!pyStack.isEmpty()) {
            int[] open = pyStack.pop();
            if (open[1] < lastLine && blocks.size() < MAX_BLOCKS) {
                blocks.add(block(open[1], open[0], lastLine, 0));
            }
        }
        while (!smaliStack.isEmpty()) {
            int[] open = smaliStack.pop();
            if (open[0] < lastLine && blocks.size() < MAX_BLOCKS) {
                blocks.add(block(open[0], open[1], lastLine, 0));
            }
        }

        builder.determine(lineCount);
        Styles styles = new Styles(builder.build());

        // 保持「闭合顺序」（内层先、外层后）—— 与库自带 Java 语言分析器的做法一致，
        // 库内部按 startLine/endLine 各建索引，不需要我们预排序。
        for (int i = 0; i < blocks.size(); i++) {
            styles.addCodeBlock(blocks.get(i));
        }
        lastBlocks = Collections.unmodifiableList(blocks);

        Map<Integer, Integer> diff = diffLines;
        if (!diff.isEmpty()) {
            for (Map.Entry<Integer, Integer> e : diff.entrySet()) {
                int ln = e.getKey();
                Integer argb = e.getValue();
                if (ln >= 0 && ln < lineCount && argb != null) {
                    styles.addLineStyle(new LineBackground(ln, new ConstColor(argb)));
                }
            }
        }
        styles.finishBuilding();
        return styles;
    }

    // ────────────────────────── 折叠 ──────────────────────────

    /** `{` 与 `}` 配对；字符串与注释里的括号不算（否则 `"}"` 会把整块提前关掉）。 */
    static void foldBraces(String line, List<EditorLexer.Token> tokens, int lineNo,
                                   Deque<int[]> stack, List<CodeBlock> out) {
        int n = line.length();
        int ti = 0;
        for (int col = 0; col < n; col++) {
            while (ti < tokens.size() && tokens.get(ti).getEnd() <= col) ti++;
            if (ti < tokens.size()) {
                EditorLexer.Token t = tokens.get(ti);
                if (t.getStart() <= col && col < t.getEnd() && isOpaque(t.getKind())) {
                    col = t.getEnd() - 1;
                    continue;
                }
            }
            char c = line.charAt(col);
            if (c == '{') {
                stack.push(new int[]{lineNo, col});
            } else if (c == '}') {
                if (stack.isEmpty()) continue;
                int[] open = stack.pop();
                if (open[0] < lineNo && out.size() < MAX_BLOCKS) {
                    out.add(block(open[0], open[1], lineNo, col));
                }
            }
        }
    }

    /** 字符串 / 注释 / Markdown 代码块内的符号不参与配对。 */
    private static boolean isOpaque(EditorLexer.Kind kind) {
        return kind == EditorLexer.Kind.STRING
                || kind == EditorLexer.Kind.COMMENT
                || kind == EditorLexer.Kind.CODE;
    }

    /** Smali：`.method` … `.end method`。 */
    static void foldSmali(String line, int lineNo, Deque<int[]> stack, List<CodeBlock> out) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
        if (line.startsWith(".method", i)) {
            stack.push(new int[]{lineNo, i});
        } else if (line.startsWith(".end method", i)) {
            if (stack.isEmpty()) return;
            int[] open = stack.pop();
            if (open[0] < lineNo && out.size() < MAX_BLOCKS) {
                out.add(block(open[0], open[1], lineNo, i));
            }
        }
    }

    /**
     * Python：按缩进折叠。
     *
     * 只在「去注释后以 `:` 结尾」的行开启块；真正成立的条件是下一行缩进更大 ——
     * 所以这里先压栈，若下一行缩进没有变大，下面那个 while 会立刻把它弹出来，
     * 且因为 startLine == endLine 不会产出 block（单行块没意义）。
     */
    static void foldPython(String line, int lineNo, Deque<int[]> stack, List<CodeBlock> out) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return;
        int indent = 0;
        while (indent < line.length()) {
            char c = line.charAt(indent);
            if (c == ' ' || c == '\t') indent++;
            else break;
        }
        while (!stack.isEmpty() && indent <= stack.peek()[0]) {
            int[] open = stack.pop();
            int endLine = lineNo - 1;
            if (open[1] < endLine && out.size() < MAX_BLOCKS) {
                out.add(block(open[1], open[0], endLine, 0));
            }
        }
        if (endsWithColon(line)) {
            stack.push(new int[]{indent, lineNo});
        }
    }

    /** 去掉行尾注释（跳过引号内的 #）后，是否以冒号结尾。 */
    private static boolean endsWithColon(String line) {
        int hash = lineCommentAt(line);
        int end = hash < 0 ? line.length() : hash;
        int i = end - 1;
        while (i >= 0 && Character.isWhitespace(line.charAt(i))) i--;
        return i >= 0 && line.charAt(i) == ':';
    }

    private static int lineCommentAt(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                    continue;
                }
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'') quote = c;
            else if (c == '#') return i;
        }
        return -1;
    }

    private static CodeBlock block(int startLine, int startColumn, int endLine, int endColumn) {
        CodeBlock b = new CodeBlock();
        b.startLine = startLine;
        b.startColumn = startColumn;
        b.endLine = endLine;
        b.endColumn = endColumn;
        b.toBottomOfEndLine = true;
        return b;
    }
}
