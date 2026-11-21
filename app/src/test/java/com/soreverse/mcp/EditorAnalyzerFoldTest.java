package com.soreverse.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.soreverse.mcp.core.EditorLexer;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import io.github.rosemoe.sora.lang.styling.CodeBlock;

/**
 * 折叠块单测。
 *
 * 折叠是「错了会吞掉别人代码」的功能，光靠肉眼看界面不够 —— 这里直接喂文本，
 * 断言产出的块区间（全部 0 基，与 sora 内部一致）。
 */
public class EditorAnalyzerFoldTest {

    private static CodeBlock find(List<CodeBlock> blocks, int startLine) {
        for (CodeBlock b : blocks) {
            if (b.startLine == startLine) return b;
        }
        return null;
    }

    @Test
    public void pythonNestedBlocks() {
        String[] lines = {
                "if x:",
                "    a = 1",
                "    if y:",
                "        b = 2",
                "c = 3",
        };
        Deque<int[]> stack = new ArrayDeque<>();
        List<CodeBlock> out = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            EditorAnalyzer.foldPython(lines[i], i, stack, out);
        }
        assertEquals(2, out.size());
        CodeBlock inner = find(out, 2);
        assertNotNull("内层 if 应可折叠", inner);
        assertEquals(3, inner.endLine);
        CodeBlock outer = find(out, 0);
        assertNotNull("外层 if 应可折叠", outer);
        assertEquals(3, outer.endLine);
        // 栈此时应已清空（c = 3 把两层都关了）
        assertEquals(0, stack.size());
    }

    @Test
    public void pythonSingleLineBlockIsNotFolded() {
        String[] lines = {"if x: return 1", "y = 2"};
        Deque<int[]> stack = new ArrayDeque<>();
        List<CodeBlock> out = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            EditorAnalyzer.foldPython(lines[i], i, stack, out);
        }
        assertEquals("单行 if 不该产生折叠块", 0, out.size());
    }

    @Test
    public void pythonTrailingCommentStillCounts() {
        String[] lines = {"for i in x:  # loop", "    f(i)", "g()"};
        Deque<int[]> stack = new ArrayDeque<>();
        List<CodeBlock> out = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            EditorAnalyzer.foldPython(lines[i], i, stack, out);
        }
        assertEquals(1, out.size());
        assertEquals(0, out.get(0).startLine);
        assertEquals(1, out.get(0).endLine);
    }

    @Test
    public void smaliMethodBlock() {
        String[] lines = {
                ".method public static main()V",
                "    .registers 2",
                "    return-void",
                ".end method",
        };
        Deque<int[]> stack = new ArrayDeque<>();
        List<CodeBlock> out = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            EditorAnalyzer.foldSmali(lines[i], i, stack, out);
        }
        assertEquals(1, out.size());
        CodeBlock b = out.get(0);
        assertEquals(0, b.startLine);
        assertEquals(3, b.endLine);
    }

    @Test
    public void braceBlockAcrossLines() {
        String[] lines = {
                "void f() {",
                "    if (a) { g(); }",
                "}",
        };
        Deque<int[]> stack = new ArrayDeque<>();
        List<CodeBlock> out = new ArrayList<>();
        EditorLexer.State state = new EditorLexer.State();
        for (int i = 0; i < lines.length; i++) {
            EditorAnalyzer.foldBraces(lines[i], EditorLexer.INSTANCE.tokenizeLine(lines[i], EditorLexer.Syntax.C, state), i, stack, out);
        }
        // 单行内的 {} 不产生块，只有外层 f() 一个
        assertEquals(1, out.size());
        assertEquals(0, out.get(0).startLine);
        assertEquals(2, out.get(0).endLine);
        assertEquals(0, stack.size());
    }

    @Test
    public void braceInsideStringDoesNotCloseBlock() {
        String[] lines = {
                "void f() {",
                "    const char *s = \"}\";",
                "    x();",
                "}",
        };
        Deque<int[]> stack = new ArrayDeque<>();
        List<CodeBlock> out = new ArrayList<>();
        EditorLexer.State state = new EditorLexer.State();
        for (int i = 0; i < lines.length; i++) {
            EditorAnalyzer.foldBraces(lines[i], EditorLexer.INSTANCE.tokenizeLine(lines[i], EditorLexer.Syntax.C, state), i, stack, out);
        }
        assertEquals(1, out.size());
        assertEquals("字符串里的 } 不能提前闭合函数体", 3, out.get(0).endLine);
    }

    @Test
    public void unmatchedCloseIsIgnored() {
        String[] lines = {"}", "void f() {", "}"};
        Deque<int[]> stack = new ArrayDeque<>();
        List<CodeBlock> out = new ArrayList<>();
        EditorLexer.State state = new EditorLexer.State();
        for (int i = 0; i < lines.length; i++) {
            EditorAnalyzer.foldBraces(lines[i], EditorLexer.INSTANCE.tokenizeLine(lines[i], EditorLexer.Syntax.C, state), i, stack, out);
        }
        assertEquals(1, out.size());
        assertEquals(1, out.get(0).startLine);
        assertNull(find(out, 0));
    }
}
