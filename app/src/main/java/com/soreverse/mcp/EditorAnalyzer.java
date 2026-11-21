package com.soreverse.mcp;

import com.soreverse.mcp.core.EditorLexer;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager;
import io.github.rosemoe.sora.lang.styling.MappedSpans;
import io.github.rosemoe.sora.lang.styling.SpanFactory;
import io.github.rosemoe.sora.lang.styling.Styles;
import io.github.rosemoe.sora.lang.styling.TextStyle;
import io.github.rosemoe.sora.lang.styling.color.ConstColor;
import io.github.rosemoe.sora.lang.styling.line.LineBackground;

/**
 * sora-editor 的着色分析器：把 {@link EditorLexer} 切出的 token 变成 Span，
 * 顺带把「行级改动标记」画成整行底色。
 *
 * 为什么用 Java 写这一个类：{@code SimpleAnalyzeManager} 的 {@code analyze} 签名里
 * 带 inner-class 泛型参数（{@code SimpleAnalyzeManager<V>.Delegate<V>}），Java 能精确表达，
 * Kotlin 侧覆盖时容易在 K2 上踩类型不匹配。词法本身仍在 Kotlin（可单测）。
 *
 * 着色用「语义色槽」：token 只声明自己是 KEYWORD/COMMENT/...，颜色由
 * {@link TaffyScheme} 按当前主题写进 EditorColorScheme，换主题时不用重新分析。
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

    private final EditorLexer.Syntax syntax;
    private final Map<EditorLexer.Kind, Integer> colorIds;

    /** 行级差异底色：key = 0 基行号，value = ARGB。 */
    private volatile Map<Integer, Integer> diffLines = Collections.emptyMap();

    public EditorAnalyzer(EditorLexer.Syntax syntax, Map<EditorLexer.Kind, Integer> colorIds) {
        this.syntax = syntax;
        this.colorIds = colorIds;
    }

    /**
     * 设置差异行并触发一次重分析。
     * 只是「换底色」也要重跑：sora 的 Styles 由分析器整体产出，没有增量改 lineStyle 的入口。
     * 大文件在调用方被限制成只读，所以这里不会高频重跑。
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

        int line = 0;
        int start = 0;
        while (start <= len) {
            int nl = text.indexOf("\n", start);
            int end = nl < 0 ? len : nl;
            // 只在行内做 substring：整篇 toString 会复制一份完整文本，2 MB 就是 4 MB 的临时对象
            List<EditorLexer.Token> tokens =
                    EditorLexer.INSTANCE.tokenizeLine(text.substring(start, end), syntax, state);
            for (int i = 0, n = tokens.size(); i < n; i++) {
                EditorLexer.Token t = tokens.get(i);
                Integer cid = colorIds.get(t.getKind());
                if (cid == null) continue;
                builder.add(line, SpanFactory.obtainNoExt(t.getStart(), TextStyle.makeStyle(cid)));
            }
            if (nl < 0) break;
            start = nl + 1;
            line++;
            if (nl + 1 >= len) break;
        }
        builder.determine(lineCount);

        Styles styles = new Styles(builder.build());
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
}
