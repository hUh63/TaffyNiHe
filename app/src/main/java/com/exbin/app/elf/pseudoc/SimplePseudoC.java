package com.exbin.app.elf.pseudoc;

import com.exbin.app.elf.DisassembledInstruction;
import com.exbin.app.elf.FunctionInfo;
import com.exbin.app.elf.FunctionSignatureAnalyzer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 结构化伪 C 转换器 (v2.1.4)
 * <p>
 * 改进：
 * <ul>
 *   <li>移除所有基本块标题注释（// L_xxx）</li>
 *   <li>空分支/空循环体不再输出注释，直接保留结构</li>
 *   <li>无法结构化的跳转统一使用 {@code goto L_xxx;}，不再使用注释跳转</li>
 *   <li>减少不必要的 goto，已访问块优先隐式处理</li>
 *   <li>字符串常量识别（adrp + add 模式）</li>
 *   <li>所有指令扩展函数完整保留</li>
 * </ul>
 */
public class SimplePseudoC implements PseudoCConverter {

    private static final Set<String> CALL_MNEMONICS = Collections.unmodifiableSet(
		new HashSet<>(Arrays.asList("bl", "blx", "blr", "call", "jal", "jalr")));
    private static final Set<String> RET_MNEMONICS = Collections.singleton("ret");

    private static final int L_WHILE = 1, L_DO_WHILE = 2, L_WHILE1 = 3;

    private PseudoCContext ctx;
    private List<DisassembledInstruction> instructions;
    private boolean isAarch64;
    private List<String> out;

    private List<Block> blocks;
    private Map<Long, Block> byStart;
    private Map<Long, String> labelMap;
    private Map<Long, Integer> addrToIdx;

    private Map<Long, Set<Long>> dom;
    private Map<Long, Long> idomMap;
    private Map<Long, Set<Long>> pdom;
    private Map<Long, Long> ipdomMap;

    private Set<Long> loopHeaders;
    private Map<Long, Set<Long>> loopBody;
    private Map<Long, LoopInfo> loopInfoCache;

    private Set<Long> visited;
    private Map<String, String> argValues;
    private Set<Long> skipInstrs;
    private long epilogueBlockStart = -1L;

    @Override public String name() { return "Simple (结构化 v2.1.4)"; }

    @Override public List<String> convert(PseudoCContext ctx) {
        this.ctx = ctx;
        out = new ArrayList<>();
        if (ctx.instructions == null || ctx.instructions.isEmpty()) {
            out.add("// (no instructions)"); return out;
        }
        instructions = ctx.instructions;
        isAarch64 = (ctx.machine == 183);
        boolean isThumb = ctx.isThumb;

        String fname = ctx.functionName != null && !ctx.functionName.isEmpty()
			? ctx.functionName : ("sub_" + Long.toHexString(ctx.functionAddress));
        out.add("// === pseudo-C (结构化 v2.1.4) ===");
        out.add("// 架构: " + (isAarch64 ? "AArch64" : (isThumb ? "ARM/Thumb" : "ARM"))
                + "  size: " + ctx.functionSize + "  指令: " + instructions.size());

        FunctionSignatureAnalyzer.Result sig = null;
        try {
            FunctionInfo sigFn = new FunctionInfo(fname, ctx.functionAddress, ctx.functionSize, ".text");
            sigFn.rawName = fname; sigFn.instructions = instructions;
            sig = FunctionSignatureAnalyzer.analyze(sigFn, ctx.machine);
        } catch (Throwable ignored) {}

        if (sig != null && sig.paramTypes != null && !sig.paramTypes.isEmpty()) {
            out.add("// 推断签名: " + sig.signature + "  [置信度: " + sig.overallConfidence
                    + ", min=" + sig.minArgs + ", max=" + sig.maxArgs + "]");
            StringBuilder params = new StringBuilder();
            for (int i = 0; i < sig.paramTypes.size(); i++) {
                if (i > 0) params.append(", ");
                String pt = sig.paramTypes.get(i);
                if (pt.contains("char*") && !pt.contains("const")) pt = "const " + pt;
                params.append(pt).append(" arg").append(i);
            }
            String ret = sig.returnType != null && !sig.returnType.isEmpty() ? sig.returnType : "void";
            out.add(ret + " " + fname + "(" + params + ") {");
        } else {
            out.add("void " + fname + "() {");
        }

        buildCFG(); computeDominators(); computePostDominators(); detectLoops();
        argValues = new HashMap<>(); for (int i=0;i<8;i++) argValues.put("arg"+i,"?");
        visited = new HashSet<>(); skipInstrs = new HashSet<>();
        if (loopHeaders != null) for (Long h : loopHeaders) {
				LoopInfo li = analyzeLoop(h);
				if (li!=null && li.type==L_WHILE) tryRenderFor(byStart.get(h), li.bodyStart, li.follow);
			}

        int prologueEnd = detectPrologueEnd(instructions, isAarch64);
        int epilogueStart = detectEpilogueStart(instructions, isAarch64);
        if (epilogueStart >= 0 && epilogueStart < instructions.size())
            epilogueBlockStart = blockContaining(instructions.get(epilogueStart).address);
        if (prologueEnd > 0) emit(out, 1, "// ─── 函数序言 ───");

        boolean ok = false;
        try {
            if (!blocks.isEmpty()) emitRegion(blocks.get(0).start, null, null, 1, out);
            ok = true;
        } catch (StackOverflowError e) {
            android.util.Log.w("SimplePseudoC", "StackOverflow in emitRegion, blocks=" + blocks.size() + ", insns=" + instructions.size());
            out.add("    // ⚠ 结构化失败(递归过深)，退化为扁平输出");
            for (DisassembledInstruction i : instructions)
                for (String s : renderInstrLines(i,false)) emit(out, 1, s);
        } catch (Throwable t) {
            android.util.Log.e("SimplePseudoC", "convert failed", t);
            out.add("    // ⚠ 转换失败: " + t.getMessage());
            for (DisassembledInstruction i : instructions)
                out.add("    " + i.mnemonic + " " + (i.opStr != null ? i.opStr : ""));
        }
        if (ok && blocks != null) {
            Set<Long> local = new HashSet<>(); boolean first = true;
            for (Block b : blocks) {
                if (visited.contains(b.start) || local.contains(b.start)) continue;
                if (first) { out.add("    // ─── 以下未归入主控制流 ───"); first = false; }
                emitFlatRegion(b.start, 1, local, 0, out);
            }
        }
        out.add("}");
        out.add("// === end pseudo-C ===");
        return out;
    }

    // ─────────────── CFG ───────────────
    private static final class Block {
        long start; List<DisassembledInstruction> ins = new ArrayList<>();
        List<Long> succs = new ArrayList<>(), preds = new ArrayList<>();
        boolean condBranch, uncondBranch, isRet, isIndirect, fallsThrough;
        long condTaken=-1, condFallthrough=-1, uncondTarget=-1;
        Block(long s){start=s;}
    }

    private void buildCFG() {
        int n = instructions.size(); addrToIdx = new HashMap<>();
        for(int i=0;i<n;i++) addrToIdx.put(instructions.get(i).address, i);
        Set<Long> leaders = new LinkedHashSet<>();
        leaders.add(instructions.get(0).address);
        for(int i=0;i<n;i++) {
            DisassembledInstruction ins = instructions.get(i);
            String mn = n(ins.mnemonic);
            if(isControlTransfer(mn)) {
                if(i+1<n) leaders.add(instructions.get(i+1).address);
                if(isBranchWithTarget(mn)) { Long t = parseBranchTarget(ins); if(t!=null) leaders.add(t); }
            }
        }
        List<Long> sorted = new ArrayList<>(leaders); Collections.sort(sorted);
        blocks = new ArrayList<>(); byStart = new HashMap<>(); labelMap = new HashMap<>();
        for(long start : sorted) {
            Integer gi = addrToIdx.get(start); if(gi==null) continue;
            Block b = new Block(start);
            for(int k=gi;k<n;k++) {
                DisassembledInstruction ins = instructions.get(k);
                if(k>gi && leaders.contains(ins.address)) break;
                b.ins.add(ins);
            }
            if(b.ins.isEmpty()) continue;
            blocks.add(b); byStart.put(start,b); labelMap.put(start,"L_"+hex(start));
        }
        for(Block b:blocks) computeSuccs(b);
        for(Block b:blocks) for(Long s:b.succs) { Block sb=byStart.get(s); if(sb!=null) sb.preds.add(b.start); }
        pruneUnreachable();
    }

    private void pruneUnreachable() {
        if(blocks.isEmpty()) return;
        long entry = blocks.get(0).start;
        Set<Long> reach = new HashSet<>(); Stack<Long> st = new Stack<>(); st.push(entry); reach.add(entry);
        while(!st.isEmpty()) {
            Block b = byStart.get(st.pop()); if(b==null) continue;
            for(Long s:b.succs) if(byStart.containsKey(s) && reach.add(s)) st.push(s);
        }
        if(reach.size()==blocks.size()) return;
        List<Block> kept = new ArrayList<>();
        for(Block b:blocks) if(reach.contains(b.start)) kept.add(b); else { byStart.remove(b.start); labelMap.remove(b.start); }
        blocks = kept;
        for(Block b:blocks) b.preds.removeIf(p -> !reach.contains(p));
    }

    private void computeSuccs(Block b) {
        if(b.ins.isEmpty()) return;
        DisassembledInstruction last = b.ins.get(b.ins.size()-1);
        String mn = n(last.mnemonic), op = last.opStr==null?"":last.opStr.trim();
        if(mn.equals("ret")||(mn.equals("bx")&&isLr(op))||(mn.equals("br")&&isLr(op))||(mn.equals("pop")&&op.contains("pc"))) {
            b.isRet=true; return;
        }
        if(mn.equals("br")||mn.equals("bx")) { b.isIndirect=true; return; }
        if(isUncondBranchMn(mn)) {
            b.uncondBranch=true; Long t=parseBranchTarget(last); if(t!=null){b.uncondTarget=t;b.succs.add(t);} return;
        }
        if(isCondBranch(mn)) {
            b.condBranch=true; Long t=parseBranchTarget(last), ft=fallthroughAddr(last);
            if(t!=null)b.condTaken=t; if(ft!=null)b.condFallthrough=ft;
            if(t!=null)b.succs.add(t); if(ft!=null)b.succs.add(ft); return;
        }
        b.fallsThrough=true; Long ft=fallthroughAddr(last); if(ft!=null)b.succs.add(ft);
    }

    private static boolean isControlTransfer(String mn) {
        if(mn.isEmpty()) return false;
        if(mn.equals("ret")||isUncondBranchMn(mn)||mn.equals("bx")||mn.equals("br")) return true;
        if(mn.startsWith("b.")||mn.startsWith("cbz")||mn.startsWith("cbnz")||mn.startsWith("tbz")||mn.startsWith("tbnz")) return true;
        return isArmCondBranch(mn);
    }

    private static boolean isBranchWithTarget(String mn) {
        return isUncondBranchMn(mn)||mn.startsWith("b.")||mn.startsWith("cbz")||mn.startsWith("cbnz")||mn.startsWith("tbz")||mn.startsWith("tbnz")||isArmCondBranch(mn);
    }

    private static boolean isCondBranch(String mn) {
        if(isUncondBranchMn(mn)) return false;
        return mn.startsWith("b.")||isArmCondBranch(mn)||mn.startsWith("cbz")||mn.startsWith("cbnz")||mn.startsWith("tbz")||mn.startsWith("tbnz");
    }

    private static boolean isLr(String op) { return op.equals("lr")||op.equals("x30")||op.contains("lr")||op.contains("x30"); }

    private Long fallthroughAddr(DisassembledInstruction ins) {
        Integer gi=addrToIdx.get(ins.address); return gi!=null && gi+1<instructions.size()? instructions.get(gi+1).address:null;
    }

    private Long parseBranchTarget(DisassembledInstruction ins) {
        if(ins==null||ins.opStr==null) return null;
        String op=ins.opStr.trim(), mn=n(ins.mnemonic);
        if(mn.startsWith("cbz")||mn.startsWith("cbnz")||mn.startsWith("tbz")||mn.startsWith("tbnz")) {
            int c=op.lastIndexOf(','); return c<0?null:tryParseAddr(op.substring(c+1).trim());
        }
        return tryParseAddr(op);
    }

    private long blockContaining(long addr) {
        long best=-1; for(Block b:blocks) if(b.start<=addr && b.start>best) best=b.start; return best;
    }

    // ─────────────── 支配树 / 后支配树 ───────────────
    private void computeDominators() {
        dom=new HashMap<>(); idomMap=new HashMap<>();
        if(blocks.isEmpty()) return;
        long entry=blocks.get(0).start; Set<Long> all=new HashSet<>(byStart.keySet());
        dom.put(entry, new HashSet<>(Collections.singleton(entry)));
        for(Block b:blocks) if(b.start!=entry) dom.put(b.start, new HashSet<>(all));
        List<Long> rpo=reversePostOrder(entry); boolean changed=true;
        while(changed) {
            changed=false;
            for(Long b:rpo) {
                if(b==entry) continue;
                Set<Long> nd=null; Block bb=byStart.get(b);
                for(Long p:bb.preds) { Set<Long> dp=dom.get(p); if(dp==null) continue; if(nd==null) nd=new HashSet<>(dp); else nd.retainAll(dp); }
                if(nd==null) nd=new HashSet<>(); nd.add(b);
                if(!nd.equals(dom.get(b))) { dom.put(b,nd); changed=true; }
            }
        }
        for(Block b:blocks) {
            if(b.start==entry) continue;
            Set<Long> cands=new HashSet<>(dom.get(b.start)); cands.remove(b.start);
            Long best=null; int bestSize=-1;
            for(Long c:cands) { Set<Long> dc=dom.get(c); int sz=dc==null?0:dc.size(); if(sz>bestSize){bestSize=sz;best=c;} }
            idomMap.put(b.start,best);
        }
    }

    private void computePostDominators() {
        pdom=new HashMap<>(); ipdomMap=new HashMap<>();
        if(blocks.isEmpty()) return;
        Set<Long> all=new HashSet<>(byStart.keySet());
        for(Block b:blocks) {
            if(b.succs.isEmpty()) pdom.put(b.start, new HashSet<>(Collections.singleton(b.start)));
            else pdom.put(b.start, new HashSet<>(all));
        }
        boolean changed=true;
        while(changed) {
            changed=false;
            for(Block b:blocks) {
                if(b.succs.isEmpty()) continue;
                Set<Long> np=null;
                for(Long s:b.succs) { Set<Long> ps=pdom.get(s); if(ps==null) continue; if(np==null) np=new HashSet<>(ps); else np.retainAll(ps); }
                if(np==null) np=new HashSet<>(); np.add(b.start);
                if(!np.equals(pdom.get(b.start))) { pdom.put(b.start,np); changed=true; }
            }
        }
        for(Block b:blocks) {
            Set<Long> cands=new HashSet<>(pdom.get(b.start)); cands.remove(b.start);
            Long best=null; int bestSize=-1;
            for(Long c:cands) { Set<Long> pc=pdom.get(c); int sz=pc==null?0:pc.size(); if(sz>bestSize){bestSize=sz;best=c;} }
            ipdomMap.put(b.start,best);
        }
    }

    private Long ipdom(long addr) { return ipdomMap.get(addr); }

    private List<Long> reversePostOrder(long entry) {
        List<Long> post=new ArrayList<>(); Set<Long> seen=new HashSet<>();
        Stack<long[]> st=new Stack<>(); st.push(new long[]{entry,0L}); seen.add(entry);
        while(!st.isEmpty()) {
            long[] top=st.peek(); Block b=byStart.get(top[0]); int idx=(int)top[1];
            if(b==null||idx>=b.succs.size()) { post.add(top[0]); st.pop(); continue; }
            top[1]=idx+1; Long s=b.succs.get(idx);
            if(byStart.containsKey(s) && seen.add(s)) st.push(new long[]{s,0L});
        }
        Collections.reverse(post); return post;
    }

    // ─────────────── 循环识别 ───────────────
    private void detectLoops() {
        loopHeaders=new HashSet<>(); loopBody=new HashMap<>(); loopInfoCache=new HashMap<>();
        if(dom==null) return;
        for(Block u:blocks) for(Long h:u.succs) {
				if(!byStart.containsKey(h)) continue;
				Set<Long> du=dom.get(u.start); if(du==null||!du.contains(h)) continue;
				loopHeaders.add(h); Set<Long> body=loopBody.get(h); if(body==null){body=new HashSet<>();loopBody.put(h,body);}
				body.add(h);
				if(u.start!=h) { Stack<Long> st=new Stack<>(); st.push(u.start); body.add(u.start);
					while(!st.isEmpty()) { long n=st.pop(); Block nb=byStart.get(n); if(nb==null) continue;
						for(Long p:nb.preds) if(body.add(p)) st.push(p); } }
			}
    }

    private static final class LoopInfo { long header; Set<Long> body; Long follow; int type; Long latch; Long bodyStart; String cond; }
    private static final class LoopCtx {
        final long header; final Long follow; final Set<Long> body; final int type; final Long latch;
        LoopCtx(long h,Long f,Set<Long> b,int t,Long l){header=h;follow=f;body=b;type=t;latch=l;}
    }

    private LoopInfo analyzeLoop(long h) {
        LoopInfo cached=loopInfoCache.get(h); if(cached!=null) return cached;
        LoopInfo li=new LoopInfo(); li.header=h; li.body=loopBody.get(h); if(li.body==null) li.body=new HashSet<>();
        Set<Long> exitTargets=new LinkedHashSet<>();
        for(Long x:li.body) { Block xb=byStart.get(x); if(xb!=null) for(Long s:xb.succs) if(byStart.containsKey(s)&&!li.body.contains(s)) exitTargets.add(s); }
        if(exitTargets.isEmpty()) li.follow=null;
        else if(exitTargets.size()==1) li.follow=exitTargets.iterator().next();
        else {
            Long latchExit=null;
            for(Long x:li.body) { Block xb=byStart.get(x); if(xb!=null&&xb.succs.contains(h)) for(Long s:xb.succs) if(s!=h&&exitTargets.contains(s)){latchExit=s;break;} if(latchExit!=null) break; }
            Long nonRetExit=null;
            for(Long e:exitTargets) { Block eb=byStart.get(e); if(eb!=null&&!eb.isRet){nonRetExit=e;break;} }
            Set<Long> common=null;
            for(Long e:exitTargets) { Set<Long> pd=pdom.get(e); if(pd==null) pd=new HashSet<>(Collections.singleton(e)); if(common==null) common=new HashSet<>(pd); else common.retainAll(pd); }
            Long best=null; int bestSize=-1;
            if(common!=null) for(Long c:common) { if(li.body.contains(c)||!byStart.containsKey(c)) continue;
					Block cb=byStart.get(c); if(cb!=null&&cb.isRet) continue; Set<Long> pc=pdom.get(c); int sz=pc==null?0:pc.size(); if(sz>bestSize){bestSize=sz;best=c;} }
            li.follow=latchExit!=null?latchExit:(nonRetExit!=null?nonRetExit:(best!=null?best:ipdom(h)));
            if(li.follow!=null&&li.body.contains(li.follow)) li.follow=null;
        }
        Block hb=byStart.get(h);
        if(hb!=null&&hb.condBranch&&isPureTestHeader(hb)&&hb.condTaken!=h&&hb.condFallthrough!=h) {
            Long T=hb.condTaken,F=hb.condFallthrough; boolean tin=li.body.contains(T),fin=li.body.contains(F);
            if(tin&&!fin){ li.type=L_WHILE; li.bodyStart=T; li.cond=renderTakenCondition(branchOf(hb),hb); }
            else if(!tin&&fin){ li.type=L_WHILE; li.bodyStart=F; String c=renderTakenCondition(branchOf(hb),hb); li.cond=c==null?"/* cond */":"!("+c+")"; }
            else classifyLatch(li,h);
        } else classifyLatch(li,h);
        if(li.type==0){ li.type=L_WHILE1; li.bodyStart=h; li.cond=null; }
        loopInfoCache.put(h,li); return li;
    }

    private void classifyLatch(LoopInfo li, long h) {
        for(Long u:li.body) {
            Block ub=byStart.get(u); if(ub==null||!ub.succs.contains(h)) continue;
            if(ub.condBranch) {
                Long T=ub.condTaken,F=ub.condFallthrough;
                if(T==h&&!li.body.contains(F)){ li.type=L_DO_WHILE; li.latch=u; li.bodyStart=h; li.cond=renderTakenCondition(branchOf(ub),ub); return; }
                if(F==h&&!li.body.contains(T)){ li.type=L_DO_WHILE; li.latch=u; li.bodyStart=h; String c=renderTakenCondition(branchOf(ub),ub); li.cond=c==null?"/* cond */":"!("+c+")"; return; }
            }
            if(ub.uncondBranch&&ub.uncondTarget==h){ li.type=L_WHILE1; li.latch=u; li.bodyStart=h; li.cond=null; return; }
        }
    }

    private boolean isLoopHeader(long addr) { return loopHeaders!=null&&loopHeaders.contains(addr); }
    private static boolean isPureTestHeader(Block hb) {
        if(hb==null||hb.ins.isEmpty()) return false;
        for(int k=0;k<hb.ins.size()-1;k++) { String m=n(hb.ins.get(k).mnemonic); if(!(m.equals("cmp")||m.equals("cmn")||m.equals("tst")||m.equals("teq"))) return false; }
        return true;
    }

    private static String simplifyCond(String c) {
        if(c==null) return c;
        if(c.startsWith("!(")&&c.endsWith(")")) {
            String inner=c.substring(2,c.length()-1).trim();
            Matcher m=Pattern.compile("^(\\S+)\\s*(==|!=|>=|<=|>|<)\\s*(\\S+)$").matcher(inner);
            if(m.matches()) { String inv=invertSym(m.group(2)); if(inv!=null) return m.group(1)+" "+inv+" "+m.group(3); }
        }
        return c;
    }

    private static String invertSym(String s) {
        switch(s) { case "==": return "!="; case "!=": return "=="; case ">": return "<="; case "<": return ">="; case ">=": return "<"; case "<=": return ">"; default: return null; }
    }

    // ─────────────── 结构化发射（目标输出） ───────────────
    private static void emit(List<String> target, int depth, String line) {
        StringBuilder sb=new StringBuilder();
        for(int i=0;i<depth;i++) sb.append("    ");
        sb.append(line); target.add(sb.toString());
    }

    private void emitRegion(Long addr, Long stop, LoopCtx loop, int depth, List<String> target) {
        // v2.8.10: 递归深度保护，防止 ARM64 大函数 StackOverflow
        if (depth > 400) {
            emit(target, depth, "// <递归深度超限，已截断>");
            return;
        }
        if(addr==null) return;
        if(stop!=null&&addr.equals(stop)) return;
        if(loop!=null&&loop.follow!=null&&addr.equals(loop.follow)) { emit(target,depth,"break;"); return; }
        if(isLoopHeader(addr)&&(loop==null||loop.header!=addr)) { emitLoop(addr,stop,loop,depth,target); return; }
        Block b=byStart.get(addr);
        if(b==null) { emit(target,depth,"// <未知基本块 0x"+hex(addr)+">"); return; }
        if(visited.contains(addr)) {
            if(loop!=null) {
                if(addr==loop.header) return;
                if(loop.follow!=null&&addr.equals(loop.follow)) { emit(target,depth,"break;"); return; }
            }
            if(stop!=null&&addr.equals(stop)) return;
            emit(target,depth,"goto L_"+hex(addr)+";"); return;
        }
        visited.add(addr);

        if(epilogueBlockStart>=0 && addr==epilogueBlockStart && !blocks.isEmpty() && addr!=blocks.get(0).start)
            emit(target,depth,"// ─── 函数尾声 ───");

        int lastIdx=b.ins.size()-1;
        boolean lastIsTerm=lastIdx>=0 && isControlTransfer(n(b.ins.get(lastIdx).mnemonic));
        int emitUpTo=lastIsTerm?lastIdx:b.ins.size();

        int k=0;
        while(k<emitUpTo) {
            DisassembledInstruction ins=b.ins.get(k);
            if(k+1<emitUpTo) {
                DisassembledInstruction next=b.ins.get(k+1);
                if(isAdrpOrAdr(ins) && isAddToSameReg(ins,next) && next.referencedString!=null) {
                    String dst=renameReg(firstRegOf(ins.opStr));
                    String escaped=escapeC(next.referencedString);
                    emit(target,depth,"const char* "+dst+" = \""+escaped+"\";");
                    if(dst.startsWith("arg") && dst.length()<=5) argValues.put(dst,"\""+escaped+"\"");
                    k+=2; continue;
                }
            }
            for(String ln: renderInstrLines(ins,false)) emit(target,depth,ln);
            k++;
        }

        if(!lastIsTerm) {
            Long ft=b.succs.isEmpty()?null:b.succs.get(0);
            if(ft==null) return;
            if(stop!=null&&ft.equals(stop)) return;
            if(loop!=null&&loop.follow!=null&&ft.equals(loop.follow)) { emit(target,depth,"break;"); return; }
            if(isLoopHeader(ft) && !visited.contains(ft)) { emitLoop(ft,stop,loop,depth,target); return; }
            emitRegion(ft,stop,loop,depth,target); return;
        }

        DisassembledInstruction term=b.ins.get(lastIdx);
        String mn=n(term.mnemonic);
        if(b.isRet) { emit(target,depth,"return;"); return; }
        if(b.uncondBranch) { handleUncond(b,stop,loop,depth,target); return; }
        if(b.condBranch) {
            if(tryEmitSwitchChain(b,stop,loop,depth,target)) return;
            handleCond(b,stop,loop,depth,target); return;
        }
        if(b.isIndirect) { emitTableSwitch(b,stop,loop,depth,target); return; }
        String transformed=transformOne(term,ctx,labelMap,false,isAarch64);
        transformed=cleanNoise(transformed);
        transformed=renameRegsInLine(transformed,isAarch64);
        emit(target,depth,splitLines(transformed));
    }

    private void handleUncond(Block b, Long stop, LoopCtx loop, int depth, List<String> target) {
        Long T=b.uncondTarget;
        if(T==null) { emit(target,depth,"// branch (无法解析目标)"); return; }
        if(!byStart.containsKey(T)) {
            String name=null;
            if(ctx.imports!=null&&ctx.imports.containsKey(T)) name=ctx.imports.get(T);
            else if(ctx.labels!=null&&ctx.labels.containsKey(T)) name=ctx.labels.get(T);
            if(name!=null) emit(target,depth,"return "+name+"();  // tail call");
            else emit(target,depth,"// 跳转到外部 0x"+hex(T));
            return;
        }
        if(loop!=null && T==loop.header) return;
        if(stop!=null && T.equals(stop)) { Block sb=byStart.get(T); if(sb!=null&&sb.isRet) emit(target,depth,"return;"); return; }
        Block tb=byStart.get(T);
        if(tb!=null && tb.isRet) { visited.add(T); emit(target,depth,"return;"); return; }
        if(loop!=null && loop.follow!=null && T.equals(loop.follow)) { emit(target,depth,"break;"); return; }
        if(visited.contains(T)) { emit(target,depth,"goto L_"+hex(T)+";"); return; }
        if(isLoopHeader(T) && !visited.contains(T)) { emitLoop(T,stop,loop,depth,target); return; }
        emitRegion(T,stop,loop,depth,target);
    }

    private void handleCond(Block b, Long stop, LoopCtx loop, int depth, List<String> target) {
        DisassembledInstruction term=b.ins.get(b.ins.size()-1);
        String cond=renderTakenCondition(term,b); if(cond==null) cond="/* cond */";
        cond=simplifyCond(cond); String ncond=simplifyCond("!("+cond+")");
        Long T=b.condTaken, F=b.condFallthrough;

        if(loop!=null && loop.type==L_DO_WHILE && b.start==loop.latch && T!=null && T==loop.header) return;
        if(loop!=null && T!=null && T==loop.header) { emit(target,depth,"if ("+cond+") continue;"); if(F!=null) emitRegion(F,stop,loop,depth,target); return; }
        if(loop!=null && loop.follow!=null) {
            if(T!=null && T.equals(loop.follow)) { emit(target,depth,"if ("+cond+") break;"); if(F!=null) emitRegion(F,stop,loop,depth,target); return; }
            if(F!=null && F.equals(loop.follow)) { emit(target,depth,"if ("+ncond+") break;"); if(T!=null) emitRegion(T,stop,loop,depth,target); return; }
        }

        Long merge=ipdom(b.start); if(merge==null) merge=stop;

        List<String> bodyT=new ArrayList<>(), bodyF=new ArrayList<>();
        Map<String,String> snapT=new HashMap<>(argValues);
        if(T!=null) emitRegion(T,merge,loop,depth+1,bodyT);
        argValues=snapT;
        Map<String,String> snapF=new HashMap<>(argValues);
        if(F!=null) emitRegion(F,merge,loop,depth+1,bodyF);
        argValues=snapF;

        if(merge!=null && T!=null && merge.equals(T)) {
            emit(target,depth,"if ("+ncond+") {");
            target.addAll(bodyF); // may be empty
            emit(target,depth,"}");
        } else if(merge!=null && F!=null && merge.equals(F)) {
            emit(target,depth,"if ("+cond+") {");
            target.addAll(bodyT);
            emit(target,depth,"}");
        } else {
            emit(target,depth,"if ("+cond+") {");
            target.addAll(bodyT);
            emit(target,depth,"} else {");
            target.addAll(bodyF);
            emit(target,depth,"}");
        }

        if(merge!=null && byStart.containsKey(merge) && byStart.get(merge).isRet) { visited.add(merge); return; }
        if(merge!=null && (stop==null || !merge.equals(stop))) {
            if(loop!=null && loop.follow!=null && merge.equals(loop.follow)) return;
            if(isLoopHeader(merge) && !visited.contains(merge)) emitLoop(merge,stop,loop,depth,target);
            else emitRegion(merge,stop,loop,depth,target);
        }
    }

    private void emitLoop(long h, Long outerStop, LoopCtx outerLoop, int depth, List<String> target) {
        LoopInfo li=analyzeLoop(h);
        LoopCtx lc=new LoopCtx(h,li.follow,li.body,li.type,li.latch);

        String forHeader=null;
        if(li.type==L_WHILE) forHeader=tryRenderFor(byStart.get(h),li.bodyStart,li.follow);

        List<String> bodyOut=new ArrayList<>();
        visited.add(h);
        if(li.bodyStart!=null) emitRegion(li.bodyStart,h,lc,depth+1,bodyOut);
        else emitRegion(h,null,lc,depth+1,bodyOut);

        if(forHeader!=null) {
            emit(target,depth,forHeader+" {");
            target.addAll(bodyOut);
            emit(target,depth,"}");
        } else if(li.type==L_WHILE) {
            String c=li.cond==null?"/* cond */":simplifyCond(li.cond);
            emit(target,depth,"while ("+c+") {");
            target.addAll(bodyOut);
            emit(target,depth,"}");
        } else if(li.type==L_DO_WHILE) {
            String c=li.cond==null?"/* cond */":simplifyCond(li.cond);
            emit(target,depth,"do {");
            target.addAll(bodyOut);
            emit(target,depth,"} while ("+c+");");
        } else {
            emit(target,depth,"while (1) {");
            target.addAll(bodyOut);
            emit(target,depth,"}");
        }

        if(li.follow!=null && (outerStop==null || !li.follow.equals(outerStop))) {
            if(isLoopHeader(li.follow) && !visited.contains(li.follow)) emitLoop(li.follow,outerStop,outerLoop,depth,target);
            else emitRegion(li.follow,outerStop,outerLoop,depth,target);
        }
    }

    private String tryRenderFor(Block header, Long bodyStart, Long follow) {
        if(header==null||!header.condBranch) return null;
        DisassembledInstruction branch=header.ins.get(header.ins.size()-1);
        DisassembledInstruction cmp=findCmpInBlock(header,branch); if(cmp==null) return null;
        String[] cp=cmp.opStr==null?new String[0]:cmp.opStr.trim().split(","); if(cp.length!=2) return null;
        String reg=cp[0].trim(), bound=cp[1].trim(); if(bound.startsWith("#")) bound=bound.substring(1);
        String bm=n(branch.mnemonic); String op;
        if(bm.equals("b.lt")||bm.equals("blt")) op="<";
        else if(bm.equals("b.le")||bm.equals("ble")) op="<=";
        else if(bm.equals("b.hi")||bm.equals("bhi")) op="<";
        else if(bm.equals("b.ls")||bm.equals("bls")) op="<=";
        else if(bm.equals("b.ge")||bm.equals("bge")) op="<";
        else if(bm.equals("b.gt")||bm.equals("bgt")) op="<=";
        else return null;
        long[] inc=findIncrement(reg,bodyStart,follow); if(inc==null) return null;
        skipInstrs.add(inc[0]);
        String init=findLoopInit(header,reg);
        String r=renameReg(reg), b=renameReg(bound);
        String initPart=init==null?"; ":(init+"; ");
        return "for ("+initPart+r+" "+op+" "+b+"; "+r+" += "+inc[1]+")";
    }

    private long[] findIncrement(String reg, Long bodyStart, Long follow) {
        if(bodyStart==null) return null;
        Set<Long> seen=new HashSet<>(); Stack<Long> st=new Stack<>(); st.push(bodyStart); int guard=0;
        while(!st.isEmpty() && guard++<5000) {
            long a=st.pop(); if(!seen.add(a)) continue; if(follow!=null && a==follow) continue;
            Block b=byStart.get(a); if(b==null) continue;
            for(DisassembledInstruction ins:b.ins) {
                String mn=n(ins.mnemonic);
                if((mn.equals("add")||mn.equals("adds")) && ins.opStr!=null) {
                    String[] p=ins.opStr.trim().split(",");
                    if(p.length==3 && p[0].trim().equals(reg) && p[1].trim().equals(reg)) {
                        String s=p[2].trim(); if(s.startsWith("#")) s=s.substring(1);
                        try { return new long[]{ins.address,Long.parseLong(s)}; } catch(NumberFormatException ignore){}
                    }
                }
            }
            for(Long s:b.succs) st.push(s);
        }
        return null;
    }

    private String findLoopInit(Block header, String reg) {
        for(Long p:header.preds) {
            if(loopBody!=null) { Set<Long> body=loopBody.get(header.start); if(body!=null && body.contains(p)) continue; }
            Block pb=byStart.get(p); if(pb==null) continue;
            for(int k=pb.ins.size()-1;k>=0;k--) {
                DisassembledInstruction ins=pb.ins.get(k);
                String mn=n(ins.mnemonic);
                if((mn.equals("mov")||mn.equals("movz")||mn.equals("movw")) && ins.opStr!=null) {
                    String[] parts=ins.opStr.trim().split(",");
                    if(parts.length==2 && parts[0].trim().equals(reg)) {
                        String imm=parts[1].trim(); if(imm.startsWith("#")) imm=imm.substring(1);
                        if(!isIntLiteral(imm)) continue;
                        skipInstrs.add(ins.address);
                        return renameReg(reg)+" = "+imm;
                    }
                }
            }
        }
        return null;
    }

    private static boolean isIntLiteral(String s) {
        if(s==null||s.isEmpty()) return false;
        try { Long.parseLong(s); return true; } catch(NumberFormatException e) {
            if(s.startsWith("0x")||s.startsWith("0X")) { try{Long.parseLong(s.substring(2),16); return true;}catch(NumberFormatException ignored){} }
        }
        return false;
    }

    // ─────────────── switch 相关 ───────────────
    private boolean tryEmitSwitchChain(Block start, Long stop, LoopCtx loop, int depth, List<String> target) {
        List<long[]> cases=new ArrayList<>(); List<String> imms=new ArrayList<>();
        List<Long> chainBlocks=new ArrayList<>(); Block cur=start; chainBlocks.add(start.start);
        String cmpReg=null; Long defaultTarget=null; int guard=0;
        while(cur!=null && guard++<256) {
            if(cur.ins.size()!=2) break;
            DisassembledInstruction i0=cur.ins.get(0), i1=cur.ins.get(1);
            String m0=n(i0.mnemonic), m1=n(i1.mnemonic);
            if(!m0.equals("cmp")||!m1.equals("b.eq")) break;
            String[] cp=i0.opStr==null?new String[0]:i0.opStr.trim().split(","); if(cp.length!=2) break;
            String reg=cp[0].trim(), imm=cp[1].trim(); if(imm.startsWith("#")) imm=imm.substring(1);
            if(cmpReg==null) cmpReg=reg; else if(!cmpReg.equals(reg)) break;
            Long T=parseBranchTarget(i1); if(T==null) break;
            cases.add(new long[]{parseLongSafe(imm),T}); imms.add(imm);
            Long ft=fallthroughAddr(i1);
            if(ft==null||!byStart.containsKey(ft)){ cur=null; break; }
            Block next=byStart.get(ft);
            if(next.ins.size()==1 && isUncondBranchMn(n(next.ins.get(0).mnemonic))) {
                defaultTarget=parseBranchTarget(next.ins.get(0)); chainBlocks.add(next.start); cur=null; break;
            }
            chainBlocks.add(next.start); cur=next;
        }
        if(cases.size()<2) return false;
        for(Long cb:chainBlocks) visited.add(cb);

        Long merge=ipdom(start.start); if(merge==null) merge=stop;
        String swVar=renameReg(cmpReg);
        emit(target,depth,"switch ("+swVar+") {");
        for(int i=0;i<cases.size();i++) {
            long caseTarget=cases.get(i)[1];
            emit(target,depth+1,"case "+imms.get(i)+": {");
            int before=target.size();
            emitRegion(caseTarget,merge,loop,depth+2,target);
            if(!lastIsTerminator(target,before)) emit(target,depth+2,"break;");
            emit(target,depth+1,"}");
        }
        if(defaultTarget!=null && byStart.containsKey(defaultTarget)) {
            emit(target,depth+1,"default: {");
            int before=target.size();
            emitRegion(defaultTarget,merge,loop,depth+2,target);
            if(!lastIsTerminator(target,before)) emit(target,depth+2,"break;");
            emit(target,depth+1,"}");
        } else {
            emit(target,depth+1,"default:");
            emit(target,depth+1,"    break;");
        }
        emit(target,depth,"}");
        if(merge!=null && byStart.containsKey(merge) && !visited.contains(merge) && (stop==null||!merge.equals(stop))) {
            if(loop!=null&&loop.follow!=null&&merge.equals(loop.follow)) return true;
            if(byStart.get(merge).isRet){ visited.add(merge); return true; }
            if(isLoopHeader(merge)) emitLoop(merge,stop,loop,depth,target);
            else emitRegion(merge,stop,loop,depth,target);
        }
        return true;
    }

    private boolean lastIsTerminator(List<String> target, int from) {
        for(int i=target.size()-1; i>=from && i>=0; i--) {
            String s=target.get(i).trim(); if(s.isEmpty()||s.startsWith("//")) continue;
            return s.startsWith("return")||s.startsWith("break")||s.startsWith("continue")||s.startsWith("goto");
        }
        return false;
    }

    private void emitTableSwitch(Block b, Long stop, LoopCtx loop, int depth, List<String> target) {
        String indexReg="?";
        for(int k=b.ins.size()-1;k>=0;k--) {
            DisassembledInstruction ins=b.ins.get(k);
            String mn=n(ins.mnemonic), op=ins.opStr==null?"":ins.opStr;
            if(mn.startsWith("ldr")&&op.contains("lsl")) {
                int lslIdx=op.indexOf("lsl"); String before=op.substring(0,lslIdx);
                String[] toks=before.split("[,\\s]+");
                for(int i=toks.length-1;i>=0;i--) {
                    String tk=toks[i].trim(); if(tk.matches("[xw]\\d+")||tk.matches("r\\d+")){indexReg=tk;break;}
                }
                break;
            }
        }
        String idx=renameReg(indexReg);
        emit(target,depth,"// switch (跳转表, index = "+idx+")");
        emit(target,depth,"switch ("+idx+") {");
        emit(target,depth+1,"// case 目标地址在运行时从跳转表读取, 指令流中不可见;");
        emit(target,depth+1,"// 请结合反汇编视图的跳转表数据查看各 case 块");
        emit(target,depth,"}");
        Long merge=ipdom(b.start);
        if(merge!=null && byStart.containsKey(merge) && !visited.contains(merge) && (stop==null||!merge.equals(stop))) {
            emit(target,depth,"// --- switch 之后 ---");
            emitRegion(merge,stop,loop,depth,target);
        }
    }

    private void emitFlatRegion(long addr, int depth, Set<Long> local, int recGuard, List<String> target) {
        if(recGuard>600){ emit(target,depth,"// (结构化深度超限, 停止展开)"); return; }
        if(!byStart.containsKey(addr)) return;
        if(visited.contains(addr) || local.contains(addr)) {
            emit(target,depth,"goto L_"+hex(addr)+";"); return;
        }
        local.add(addr);
        Block b=byStart.get(addr);
        int lastIdx=b.ins.size()-1;
        boolean lastIsTerm=lastIdx>=0 && isControlTransfer(n(b.ins.get(lastIdx).mnemonic));
        int emitUpTo=lastIsTerm?lastIdx:b.ins.size();
        for(int k=0;k<emitUpTo;k++) for(String ln:renderInstrLines(b.ins.get(k),false)) emit(target,depth,ln);

        if(!lastIsTerm) {
            Long ft=b.succs.isEmpty()?null:b.succs.get(0);
            if(ft!=null && byStart.containsKey(ft) && !visited.contains(ft) && !local.contains(ft))
                emitFlatRegion(ft,depth,local,recGuard+1,target);
            else if(ft!=null && (visited.contains(ft)||local.contains(ft))) emit(target,depth,"goto L_"+hex(ft)+";");
            return;
        }

        DisassembledInstruction term=b.ins.get(lastIdx);
        if(b.isRet) { emit(target,depth,"return;"); return; }
        if(b.uncondBranch) {
            Long T=b.uncondTarget;
            if(T!=null && byStart.containsKey(T) && !visited.contains(T)) {
                if(!local.contains(T)) emitFlatRegion(T,depth,local,recGuard+1,target);
                else emit(target,depth,"goto L_"+hex(T)+";");
            } else if(T!=null) emit(target,depth,"goto L_"+hex(T)+";");
            return;
        }
        if(b.condBranch) {
            String cond=renderTakenCondition(term,b); if(cond==null) cond="/* cond */"; cond=simplifyCond(cond);
            Long T=b.condTaken, F=b.condFallthrough;
            emit(target,depth,"if ("+cond+") {");
            if(T!=null && byStart.containsKey(T) && !visited.contains(T) && !local.contains(T))
                emitFlatRegion(T,depth+1,local,recGuard+1,target);
            else if(T!=null) emit(target,depth+1,"goto L_"+hex(T)+";");
            emit(target,depth,"}");
            if(F!=null && byStart.containsKey(F) && !visited.contains(F)) {
                if(!local.contains(F)) emitFlatRegion(F,depth,local,recGuard+1,target);
                else emit(target,depth,"goto L_"+hex(F)+";");
            } else if(F!=null) emit(target,depth,"goto L_"+hex(F)+";");
            return;
        }
        if(b.isIndirect) emit(target,depth,"// 间接跳转/switch (跳转表, case 地址运行时读取)");
    }

    // ─────────────── 条件渲染 ───────────────
    private static DisassembledInstruction branchOf(Block b) {
        if(b==null||b.ins.isEmpty()) return null; return b.ins.get(b.ins.size()-1);
    }

    private String renderTakenCondition(DisassembledInstruction branch, Block block) {
        if(branch==null||branch.mnemonic==null) return null;
        String mn=n(branch.mnemonic), op=branch.opStr==null?"":branch.opStr.trim();
        if(mn.startsWith("cbz")) return renameReg(firstCommaPart(op))+" == 0";
        if(mn.startsWith("cbnz")) return renameReg(firstCommaPart(op))+" != 0";
        if(mn.startsWith("tbz")||mn.startsWith("tbnz")) {
            String[] parts=op.split(","); if(parts.length<2) return null;
            String reg=renameReg(parts[0].trim()), bit=parts[1].trim(); if(bit.startsWith("#")) bit=bit.substring(1);
            String base="("+reg+" & (1 << "+bit+"))";
            return mn.startsWith("tbz")? base+" == 0": base+" != 0";
        }
        String sym=branchCondition(mn); if(sym==null||sym.isEmpty()) return null;
        DisassembledInstruction cmp=findCmpInBlock(block,branch); if(cmp==null) return null;
        String cop=cmp.opStr==null?"":cmp.opStr.trim();
        String[] cp=cop.split(","); if(cp.length<2) return null;
        String a=renameReg(cp[0].trim()), bb=cp[1].trim(); if(bb.startsWith("#")) bb=bb.substring(1); bb=renameReg(bb);
        String cm=n(cmp.mnemonic);
        if(cm.equals("tst")) return "("+a+" & "+bb+") "+sym+" 0";
        if(cm.equals("teq")) return "("+a+" ^ "+bb+") "+sym+" 0";
        return a+" "+sym+" "+bb;
    }

    private DisassembledInstruction findCmpInBlock(Block block, DisassembledInstruction branch) {
        int bi=-1;
        for(int k=0;k<block.ins.size();k++) if(block.ins.get(k)==branch){bi=k;break;}
        if(bi<0) bi=block.ins.size()-1;
        for(int k=bi-1;k>=0;k--) {
            String m=n(block.ins.get(k).mnemonic);
            if(m.equals("cmp")||m.equals("cmn")||m.equals("tst")||m.equals("teq")) return block.ins.get(k);
        }
        return null;
    }

    private static String firstCommaPart(String op) { if(op==null) return "?"; int c=op.indexOf(','); return (c<0?op:op.substring(0,c)).trim(); }
    private static String lastTargetOf(String op) { if(op==null) return "?"; int c=op.lastIndexOf(','); return (c<0?op:op.substring(c+1)).trim(); }

    // ─────────────── 单指令渲染 ───────────────
    private List<String> renderInstrLines(DisassembledInstruction ins, boolean isLast) {
        List<String> res=new ArrayList<>();
        if(ins==null||ins.mnemonic==null) return res;
        if(skipInstrs!=null && skipInstrs.contains(ins.address)) return res;
        String mn=n(ins.mnemonic);
        if(mn.equals("cmp")||mn.equals("cmn")||mn.equals("tst")||mn.equals("teq")) return res;
        if(ins.referencedString!=null && isLoad(mn)) {
            String dst=renameReg(firstRegOf(ins.opStr));
            res.add("const char* "+dst+" = \""+escapeC(ins.referencedString)+"\";");
            if(dst.startsWith("arg")&&dst.length()<=5) argValues.put(dst,"\""+escapeC(ins.referencedString)+"\"");
            return res;
        }
        updateArgValues(argValues,ins,isAarch64);
        String transformed=transformOne(ins,ctx,labelMap,isLast,isAarch64);
        transformed=cleanNoise(transformed);
        transformed=renameRegsInLine(transformed,isAarch64);
        if(CALL_MNEMONICS.contains(mn) && !transformed.contains("//")) transformed=appendCallArgs(transformed,argValues);
        for(String s:transformed.split("\n",-1)) { String t=s.trim(); if(!t.isEmpty()) res.add(t); }
        if(ins.referencedString!=null && !isLoad(mn) && !res.isEmpty())
            res.set(res.size()-1, res.get(res.size()-1)+"  // \""+escapeC(ins.referencedString)+"\"");
        return res;
    }

    private static String splitLines(String s) {
        if(s==null) return ""; StringBuilder sb=new StringBuilder();
        for(String p:s.split("\n",-1)) { String t=p.trim(); if(!t.isEmpty()) { if(sb.length()>0) sb.append(" "); sb.append(t); } }
        return sb.toString();
    }

    private static final Pattern NOISE_TAIL=Pattern.compile(
		";\\s*//\\s*(arith|mov|load|store|logic|store pair|load pair|page address|PC-rel address|load \\(unscaled\\)|store \\(unscaled\\))\\s*$");
    private static String cleanNoise(String line) {
        if(line==null) return null; Matcher m=NOISE_TAIL.matcher(line); if(m.find()) line=m.replaceAll(";"); return line;
    }

    // v2.8.10: 预编译寄存器替换 Pattern，避免每行 62 次 replaceAll 重新编译正则
    private static final java.util.Map<String, Pattern> REG_PATTERN_CACHE = new java.util.HashMap<>();
    private static Pattern getRegPattern(String reg) {
        Pattern p = REG_PATTERN_CACHE.get(reg);
        if (p == null) {
            p = Pattern.compile("\\b" + Pattern.quote(reg) + "\\b");
            REG_PATTERN_CACHE.put(reg, p);
        }
        return p;
    }

    private static String renameRegsInLine(String line, boolean isAarch64) {
        if(line==null) return line;
        if(isAarch64) {
            // v2.8.10: 用预编译 Pattern 替换，大幅提升 ARM64 性能
            for(int i=0;i<=30;i++) {
                String from="x"+i, to=renameReg(from);
                if(!to.equals(from)) line=getRegPattern(from).matcher(line).replaceAll(to);
            }
            for(int i=0;i<=30;i++) {
                String from="w"+i, to=renameReg(from);
                if(!to.equals(from)) line=getRegPattern(from).matcher(line).replaceAll(to);
            }
        } else {
            for(int i=15;i>=0;i--) {
                String from="r"+i, to=renameReg(from);
                if(!to.equals(from)) line=getRegPattern(from).matcher(line).replaceAll(to);
            }
        }
        return line;
    }

    private static String replaceReg(String line, String from, String to) { if(!to.equals(from)) line=getRegPattern(from).matcher(line).replaceAll(to); return line; }

    private static String renameReg(String reg) {
        if(reg==null||reg.isEmpty()) return reg;
        if(reg.equals("x0")||reg.equals("w0")) return reg.equals("x0")?"arg0":"arg0_w";
        if(reg.equals("x1")||reg.equals("w1")) return reg.equals("x1")?"arg1":"arg1_w";
        if(reg.equals("x2")||reg.equals("w2")) return reg.equals("x2")?"arg2":"arg2_w";
        if(reg.equals("x3")||reg.equals("w3")) return reg.equals("x3")?"arg3":"arg3_w";
        if(reg.equals("x4")||reg.equals("w4")) return reg.equals("x4")?"arg4":"arg4_w";
        if(reg.equals("x5")||reg.equals("w5")) return reg.equals("x5")?"arg5":"arg5_w";
        if(reg.equals("x6")||reg.equals("w6")) return reg.equals("x6")?"arg6":"arg6_w";
        if(reg.equals("x7")||reg.equals("w7")) return reg.equals("x7")?"arg7":"arg7_w";
        if(reg.equals("x29")||reg.equals("w29")) return "fp";
        if(reg.equals("x30")||reg.equals("w30")) return "lr";
        if(reg.equals("r0")) return "arg0"; if(reg.equals("r1")) return "arg1";
        if(reg.equals("r2")) return "arg2"; if(reg.equals("r3")) return "arg3";
        if(reg.equals("r11")||reg.equals("fp")) return "fp";
        if(reg.equals("r12")||reg.equals("ip")) return "ip";
        if(reg.equals("r13")||reg.equals("sp")) return "sp";
        if(reg.equals("r14")||reg.equals("lr")) return "lr";
        if(reg.equals("r15")||reg.equals("pc")) return "pc";
        return reg;
    }

    private static String firstRegOf(String op) { if(op==null) return "?"; int c=op.indexOf(','); return (c<0?op:op.substring(0,c)).trim(); }
    private static boolean isLoad(String mn) {
        if(mn==null) return false; String m=mn.toLowerCase(Locale.ROOT);
        return m.equals("ldr")||m.equals("ldur")||m.startsWith("ldp")||m.equals("ldrb")||m.equals("ldrh")||m.equals("ldrsb")||m.equals("ldrsh")||m.equals("ldrsw")||m.equals("ld1");
    }

    // ─────────────── 序言/尾声检测 ───────────────
    private static int detectPrologueEnd(List<DisassembledInstruction> ins, boolean isAarch64) {
        if(isAarch64) for(int i=0;i+1<ins.size()&&i<8;i++) {
				String a=n(ins.get(i).mnemonic), opA=ins.get(i).opStr==null?"":ins.get(i).opStr;
				String b=n(ins.get(i+1).mnemonic), opB=ins.get(i+1).opStr==null?"":ins.get(i+1).opStr;
				if(a.equals("stp")&&(opA.contains("x29")||opA.contains("fp"))&&(opA.contains("x30")||opA.contains("lr"))
				   &&b.equals("mov")&&(opB.contains("x29")||opB.contains("fp"))&&(opB.contains("sp")||opB.contains("x29"))) return i+2;
			} else for(int i=0;i<ins.size()&&i<8;i++) {
				String a=n(ins.get(i).mnemonic), opA=ins.get(i).opStr==null?"":ins.get(i).opStr;
				if(a.equals("push")&&(opA.contains("r4")||opA.contains("lr")||opA.contains("fp"))) return i+1;
			}
        return 0;
    }

    private static int detectEpilogueStart(List<DisassembledInstruction> ins, boolean isAarch64) {
        for(int i=ins.size()-1;i>=0&&i>=ins.size()-8;i--) {
            String m=n(ins.get(i).mnemonic), op=ins.get(i).opStr==null?"":ins.get(i).opStr;
            if(isAarch64) {
                if(m.equals("ret")||(m.equals("br")&&(op.contains("x30")||op.contains("lr")))) {
                    if(i>0){ String pm=n(ins.get(i-1).mnemonic), po=ins.get(i-1).opStr==null?"":ins.get(i-1).opStr;
                        if(pm.equals("ldp")&&(po.contains("x29")||po.contains("fp"))&&(po.contains("x30")||po.contains("lr"))) return i-1; }
                    return i;
                }
            } else {
                if(m.equals("pop")&&op.contains("pc")) return i;
                if(m.equals("bx")&&op.contains("lr")) return i;
            }
        }
        return -1;
    }

    private static String n(String s) { return s==null?"":s.toLowerCase(Locale.ROOT); }

    // ─────────────── transformOne 及指令扩展 ───────────────
    private String transformOne(DisassembledInstruction ins, PseudoCContext ctx, Map<Long,String> blockLabel, boolean isLast, boolean isAarch64) {
        String mn=ins.mnemonic==null?"":ins.mnemonic.toLowerCase(Locale.ROOT);
        String op=ins.opStr==null?"":ins.opStr.trim();
        if(RET_MNEMONICS.contains(mn)) return "return;";
        if(mn.equals("bx")) return (op.equals("lr")||op.equals("x30"))?"return;":"// bx "+op+" (间接跳转)";
        if(mn.equals("br")) return (op.contains("x30")||op.contains("lr"))?"return;":"// br "+op+" (间接跳转/switch)";
        if(CALL_MNEMONICS.contains(mn)) return callOp(op,ctx)+";";
        if(isUncondBranchMn(mn)) return "// b "+op+" (→ "+labelFor(op,blockLabel)+")";
        if(mn.startsWith("b.")||isArmCondBranch(mn)) return "// "+mn+" "+op+" (→ "+labelFor(lastTargetOf(op),blockLabel)+")";
        if(mn.startsWith("cbz")||mn.startsWith("cbnz")||mn.startsWith("tbz")||mn.startsWith("tbnz")) return "// "+mn+" "+op+" (→ "+labelFor(lastTargetOf(op),blockLabel)+")";
        if(mn.equals("push")) return expandPushPop(op,true);
        if(mn.equals("pop")) return expandPushPop(op,false);
        if(mn.equals("ldm")||mn.equals("ldmia")||mn.equals("ldmfd")||mn.equals("ldmdb")||mn.equals("ldmib")) return expandLdm(op);
        if(mn.equals("stm")||mn.equals("stmia")||mn.equals("stmfd")||mn.equals("stmdb")||mn.equals("stmib")) return expandStm(op);
        if(mn.equals("stp")||mn.equals("stnp")) return expandStorePair(op,isAarch64);
        if(mn.equals("ldp")||mn.equals("ldnp")) return expandLoadPair(op,isAarch64);
        if(mn.equals("ldr")||mn.equals("ldur")||mn.equals("ldrb")||mn.equals("ldrh")||mn.equals("ldrsb")||mn.equals("ldrsh")||mn.equals("ldrsw")||mn.equals("ldtr")||mn.equals("ldxr")||mn.equals("ldar")) return expandLoad(op,mn,isAarch64);
        if(mn.equals("str")||mn.equals("stur")||mn.equals("strb")||mn.equals("strh")||mn.equals("sttr")||mn.equals("stxr")||mn.equals("stlr")) return expandStore(op,mn,isAarch64);
        if(mn.equals("adrp")) return adrpLine(op,blockLabel);
        if(mn.equals("adr")) return adrLine(op);
        if(mn.equals("mov")||mn.equals("movz")||mn.equals("movk")||mn.equals("movn")||mn.equals("mov.w")||mn.equals("mvn")||mn.startsWith("mov")) return expandMov(op,mn,isAarch64);
        if(mn.equals("add")||mn.equals("adds")||mn.equals("sub")||mn.equals("subs")||mn.equals("mul")||mn.equals("muls")||mn.equals("div")||mn.equals("udiv")||mn.equals("sdiv")||mn.equals("adc")||mn.equals("sbc")||mn.equals("rsb")||mn.equals("mla")||mn.equals("neg")||mn.equals("inc")||mn.equals("dec")) return expandArith(op,mn,isAarch64);
        if(mn.equals("and")||mn.equals("orr")||mn.equals("eor")||mn.equals("bic")||mn.equals("orn")||mn.equals("lsl")||mn.equals("lsr")||mn.equals("asr")||mn.equals("ror")||mn.equals("xor")) return expandLogic(op,mn,isAarch64);
        if(mn.equals("cmp")||mn.equals("cmn")||mn.equals("tst")||mn.equals("teq")) return "// "+op;
        if(mn.equals("nop")) return "// nop";
        if(mn.equals("svc")||mn.equals("brk")||mn.equals("hvc")||mn.equals("smc")||mn.equals("syscall")) return "// syscall "+op;
        if(mn.equals("dmb")||mn.equals("dsb")||mn.equals("isb")) return "// barrier "+op;
        return (ins.mnemonic!=null?ins.mnemonic:"?")+(ins.opStr!=null?" "+ins.opStr:"")+";";
    }

    // 指令扩展完整实现
    private static String expandStorePair(String op, boolean isAarch64) {
        if(op==null||op.isEmpty()) return "// stp"; int bra=op.indexOf('['); if(bra<0) return "// stp "+op;
        String regs=op.substring(0,bra).trim(), mem=op.substring(bra).trim(); if(mem.endsWith("!")) mem=mem.substring(0,mem.length()-1).trim();
        String[] parts=regs.split(","); if(parts.length!=2) return "// stp "+op;
        String r1=parts[0].trim(), r2=parts[1].trim(); String inner=mem.substring(1,mem.length()-1).trim();
        String[] mp=inner.split(","); String base=mp.length>0?mp[0].trim():"sp", off=mp.length>1?mp[1].trim():"#0";
        if(off.startsWith("#")) off=off.substring(1); long offVal=parseLongSafe(off);
        return "*("+base+" "+(offVal>=0?"+ ":"- ")+Math.abs(offVal)+") = "+r1
			+";\n    *("+base+" "+(offVal>=0?"+ ":"- ")+Math.abs(offVal)+" + 8) = "+r2+";";
    }

    private static String expandLoadPair(String op, boolean isAarch64) {
        if(op==null||op.isEmpty()) return "// ldp"; int bra=op.indexOf('['); if(bra<0) return "// ldp "+op;
        String regs=op.substring(0,bra).trim(), mem=op.substring(bra).trim(); if(mem.endsWith("!")) mem=mem.substring(0,mem.length()-1).trim();
        String[] parts=regs.split(","); if(parts.length!=2) return "// ldp "+op;
        String r1=parts[0].trim(), r2=parts[1].trim(); String inner=mem.substring(1,mem.length()-1).trim();
        String[] mp=inner.split(","); String base=mp.length>0?mp[0].trim():"sp", off=mp.length>1?mp[1].trim():"#0";
        if(off.startsWith("#")) off=off.substring(1); long offVal=parseLongSafe(off);
        return r1+" = *("+base+" "+(offVal>=0?"+ ":"- ")+Math.abs(offVal)+");\n    "
			+r2+" = *("+base+" "+(offVal>=0?"+ ":"- ")+Math.abs(offVal)+" + 8);";
    }

    private static String expandLoad(String op, String mn, boolean isAarch64) {
        if(op==null||op.isEmpty()) return "// "+mn; int bra=op.indexOf('['); if(bra<0) return op+";";
        String dst=beforeBracket(op,bra), mem=op.substring(bra).trim(); return dst+" = "+mem2c(mem)+";";
    }

    private static String expandStore(String op, String mn, boolean isAarch64) {
        if(op==null||op.isEmpty()) return "// "+mn; int bra=op.indexOf('['); if(bra<0) return "// "+mn+" "+op;
        String src=beforeBracket(op,bra), mem=op.substring(bra).trim(); return mem2c(mem)+" = "+src+";";
    }

    private static String beforeBracket(String op, int bra) { String s=op.substring(0,bra).trim(); if(s.endsWith(",")) s=s.substring(0,s.length()-1).trim(); return s; }

    private static List<String> expandRegList(String regsPart) {
        List<String> result=new ArrayList<>(); if(regsPart==null) return result;
        int lb=regsPart.indexOf('{'), rb=regsPart.indexOf('}'); if(lb>=0&&rb>lb) regsPart=regsPart.substring(lb+1,rb);
        for(String tok:regsPart.split(",")) { tok=tok.trim(); if(tok.isEmpty()) continue;
            int dash=tok.indexOf('-');
            if(dash>0) { String lo=tok.substring(0,dash).trim(), hi=tok.substring(dash+1).trim();
                if(lo.matches("r\\d+")&&hi.matches("r\\d+")) { int a=Integer.parseInt(lo.substring(1)), b=Integer.parseInt(hi.substring(1)); for(int i=a;i<=b;i++) result.add("r"+i); continue; } }
            result.add(tok);
        }
        return result;
    }

    private static String expandPushPop(String op, boolean isPush) {
        if(op==null||op.isEmpty()) return isPush?"// push":"// pop";
        List<String> regs=expandRegList(op); String verb=isPush?"保存":"恢复";
        return "// "+(isPush?"push":"pop")+" {"+join(regs,", ")+"}  // "+verb+"寄存器";
    }

    private static String expandLdm(String op) {
        if (op == null || op.isEmpty()) return "";
        int brace = op.indexOf('{');
        if (brace < 0) return "";
        String basePart = op.substring(0, brace).trim();
        if (basePart.endsWith(",")) basePart = basePart.substring(0, basePart.length() - 1).trim();
        boolean writeback = basePart.endsWith("!");
        String base = writeback ? basePart.substring(0, basePart.length() - 1).trim() : basePart;
        List<String> regs = expandRegList(op.substring(brace));
        if (regs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < regs.size(); i++) {
            sb.append(regs.get(i)).append(" = *(").append(base);
            if (i > 0) sb.append(" + ").append(i * 4);
            sb.append(");");
            if (i < regs.size() - 1) sb.append("\n    ");
        }
        if (writeback) sb.append("\n    ").append(base).append(" = ").append(base).append(" + ").append(regs.size() * 4).append(";");
        return sb.toString();
    }

    private static String expandStm(String op) {
        if (op == null || op.isEmpty()) return "";
        int brace = op.indexOf('{');
        if (brace < 0) return "";
        String basePart = op.substring(0, brace).trim();
        if (basePart.endsWith(",")) basePart = basePart.substring(0, basePart.length() - 1).trim();
        boolean writeback = basePart.endsWith("!");
        String base = writeback ? basePart.substring(0, basePart.length() - 1).trim() : basePart;
        List<String> regs = expandRegList(op.substring(brace));
        if (regs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < regs.size(); i++) {
            sb.append("*(").append(base);
            if (i > 0) sb.append(" + ").append(i * 4);
            sb.append(") = ").append(regs.get(i)).append(";");
            if (i < regs.size() - 1) sb.append("\n    ");
        }
        if (writeback) sb.append("\n    ").append(base).append(" = ").append(base).append(" + ").append(regs.size() * 4).append(";");
        return sb.toString();
    }

    private static String join(List<String> items, String sep) { StringBuilder sb=new StringBuilder(); for(int i=0;i<items.size();i++) { if(i>0) sb.append(sep); sb.append(items.get(i)); } return sb.toString(); }

    private static String mem2c(String mem) {
        if(mem==null) return "*?"; if(!mem.startsWith("[")) return "*"+mem;
        String inner=mem.endsWith("]")?mem.substring(1,mem.length()-1).trim():mem.substring(1).trim();
        String[] parts=inner.split(","); String base=parts[0].trim(); if(parts.length==1) return "*("+base+")";
        String off=parts[1].trim(); if(off.startsWith("#")) off=off.substring(1); long offVal=parseLongSafe(off);
        if(offVal==0) return "*("+base+")"; return "*("+base+" "+(offVal>=0?"+ ":"- ")+Math.abs(offVal)+")";
    }

    private static String expandMov(String op, String mn, boolean isAarch64) {
        if(op==null||op.isEmpty()) return "// "+mn; if(op.startsWith("[")) return expandLoad(op,mn,isAarch64);
        String[] parts=op.split(","); if(parts.length==2) { String dst=parts[0].trim(), src=parts[1].trim(); if(src.startsWith("#")) src=src.substring(1); return dst+" = "+src+";"; }
        return op+";";
    }

    private static String expandArith(String op, String mn, boolean isAarch64) {
        if(op==null||op.isEmpty()) return "// "+mn; String[] parts=op.split(",");
        if(parts.length==3) { String dst=parts[0].trim(), a=parts[1].trim(), b=parts[2].trim(); if(b.startsWith("#")) b=b.substring(1); return dst+" = "+a+" "+arithOpSym(mn)+" "+b+";"; }
        if(parts.length==2) { String dst=parts[0].trim(), b=parts[1].trim(); if(b.startsWith("#")) b=b.substring(1); if(mn.equals("neg")||mn.equals("inc")||mn.equals("dec")) return dst+" = "+(mn.equals("neg")?"-(":"")+b+(mn.equals("neg")?")":"")+";"; return dst+" = "+dst+" + "+b+";"; }
        return op+";";
    }

    private static String arithOpSym(String mn) { switch(mn) { case "add": case "adds": return "+"; case "sub": case "subs": case "rsb": return "-"; case "mul": case "muls": case "mla": return "*"; case "div": case "sdiv": case "udiv": return "/"; case "adc": return "+"; case "sbc": return "-"; default: return mn; } }

    private static String expandLogic(String op, String mn, boolean isAarch64) {
        if(op==null||op.isEmpty()) return "// "+mn; String[] parts=op.split(",");
        if(parts.length==3) { String dst=parts[0].trim(), a=parts[1].trim(), b=parts[2].trim(); return dst+" = "+a+" "+logicOpSym(mn)+" "+b+";"; }
        if(parts.length==2) { String dst=parts[0].trim(), rest=parts[1].trim(); String[] sub=rest.split(","); if(sub.length==2) return dst+" = "+sub[0].trim()+" "+logicOpSym(mn)+" "+sub[1].trim()+";"; }
        return op+";";
    }

    private static String logicOpSym(String mn) { switch(mn) { case "and": case "bic": return "&"; case "orr": case "orn": return "|"; case "eor": case "xor": return "^"; case "lsl": return "<<"; case "lsr": return ">>"; case "asr": return ">>s"; case "ror": return ">>>"; default: return mn; } }

    private static String adrpLine(String op, Map<Long,String> blockLabel) {
        if(op==null) return "// adrp"; int comma=op.indexOf(','); if(comma<0) return "// adrp "+op;
        String dst=op.substring(0,comma).trim(), page=op.substring(comma+1).trim(); if(page.startsWith("#")) page=page.substring(1);
        return dst+" = "+page+";  // adrp (page address)";
    }

    private static String adrLine(String op) {
        if(op==null) return "// adr"; int comma=op.indexOf(','); if(comma<0) return "// adr "+op;
        String dst=op.substring(0,comma).trim(), addr=op.substring(comma+1).trim(); if(addr.startsWith("#")) addr=addr.substring(1);
        return dst+" = "+addr+";  // adr (PC-rel)";
    }

    private static long parseLongSafe(String s) {
        try { if(s.startsWith("-")||s.startsWith("+")) { if(s.startsWith("0x",1)||s.startsWith("0X",1)) return Long.parseLong(s.substring(3),16); return Long.parseLong(s); }
            if(s.startsWith("0x")||s.startsWith("0X")) return Long.parseLong(s.substring(2),16); return Long.parseLong(s); } catch(Throwable t) { return 0; }
    }

    private static String callOp(String op, PseudoCContext ctx) {
        if(op==null||op.isEmpty()) return "sub_?()"; Long addr=tryParseAddr(op);
        if(addr!=null) {
            if(ctx.imports!=null&&ctx.imports.containsKey(addr)) return ctx.imports.get(addr)+"()";
            if(ctx.labels!=null&&ctx.labels.containsKey(addr)) return ctx.labels.get(addr)+"()";
            return "sub_"+Long.toHexString(addr).toUpperCase(Locale.ROOT)+"()";
        }
        if(op.startsWith("x")||op.startsWith("w")) return "call_"+op+"()  // indirect";
        return "call_"+op+"()";
    }

    private static String labelFor(String op, Map<Long,String> blockLabel) {
        if(op==null) return "?"; Long addr=tryParseAddr(op);
        if(addr!=null&&blockLabel!=null&&blockLabel.containsKey(addr)) return blockLabel.get(addr);
        return "L_0x"+(op.startsWith("0x")||op.startsWith("0X")?op.substring(2):op);
    }

    private static String branchCondition(String mn) {
        if(mn.endsWith(".w")) mn=mn.substring(0,mn.length()-2); else if(mn.endsWith(".n")) mn=mn.substring(0,mn.length()-2);
        switch(mn) {
            case "b.eq": case "beq": return "=="; case "b.ne": case "bne": return "!="; case "b.gt": case "bgt": return ">"; case "b.lt": case "blt": return "<";
            case "b.ge": case "bge": return ">="; case "b.le": case "ble": return "<="; case "b.hi": case "bhi": return ">u"; case "b.ls": case "bls": return "<=u";
            case "b.hs": case "bhs": case "b.cs": case "bcs": return ">=u"; case "b.lo": case "blo": case "b.cc": case "bcc": return "<u";
            case "b.mi": case "bmi": return "<0"; case "b.pl": case "bpl": return ">=0"; case "b.vs": case "bvs": return "overflow"; case "b.vc": case "bvc": return "!overflow";
            default: return "";
        }
    }

    private static boolean isArmCondBranch(String mn) {
        if(!mn.startsWith("b")||mn.length()<3) return false;
        String suf=mn.substring(1); if(suf.endsWith(".w")) suf=suf.substring(0,suf.length()-2); else if(suf.endsWith(".n")) suf=suf.substring(0,suf.length()-2);
        return suf.equals("eq")||suf.equals("ne")||suf.equals("gt")||suf.equals("lt")||suf.equals("ge")||suf.equals("le")
            ||suf.equals("hi")||suf.equals("lo")||suf.equals("hs")||suf.equals("ls")||suf.equals("cc")||suf.equals("cs")
            ||suf.equals("mi")||suf.equals("pl")||suf.equals("vc")||suf.equals("vs");
    }

    private static boolean isUncondBranchMn(String mn) { return mn.equals("b")||mn.equals("b.w")||mn.equals("b.n"); }

    private static Long tryParseAddr(String s) {
        if(s==null) return null; s=s.trim(); if(s.startsWith("#")||s.startsWith("0x")||s.startsWith("0X")) {
            s=s.startsWith("#")?s.substring(1):s; if(s.startsWith("0x")||s.startsWith("0X")) s=s.substring(2);
            try { return Long.parseUnsignedLong(s,16); } catch(NumberFormatException ignored) {} } return null;
    }

    private static String escapeC(String s) {
        if(s==null) return ""; StringBuilder sb=new StringBuilder();
        for(int i=0;i<s.length();i++) { char c=s.charAt(i);
            if(c=='\\') sb.append("\\\\"); else if(c=='"') sb.append("\\\""); else if(c=='\n') sb.append("\\n"); else if(c=='\r') sb.append("\\r"); else if(c=='\t') sb.append("\\t");
            else if(c>=0x20&&c<0x7f) sb.append(c); else sb.append(String.format("\\x%02x",(int)c&0xff)); }
        return sb.toString();
    }

    private static void updateArgValues(Map<String,String> argValues, DisassembledInstruction ins, boolean isAarch64) {
        if(ins==null||ins.mnemonic==null||ins.opStr==null) return;
        String mn=ins.mnemonic.toLowerCase(Locale.ROOT), op=ins.opStr.trim(); if(op.isEmpty()) return;
        if(mn.equals("mov")||mn.equals("movz")||mn.equals("movk")||mn.equals("movn")) {
            String[] parts=op.split(","); if(parts.length==2) { String dstRaw=parts[0].trim(), src=parts[1].trim(); if(src.startsWith("#")) src=src.substring(1);
                String dst=renameReg(dstRaw); if(dst.startsWith("arg")&&dst.length()<=5) argValues.put(dst,src); }
        } else if(mn.equals("ldr")||mn.equals("ldur")||mn.equals("ldrb")||mn.equals("ldrh")) {
            int bra=op.indexOf('['); if(bra<0) return; String dstRaw=op.substring(0,bra).trim(), mem=op.substring(bra).trim();
            String dst=renameReg(dstRaw); if(dst.startsWith("arg")&&dst.length()<=5) argValues.put(dst,mem2c(mem));
        } else if(mn.equals("add")||mn.equals("adds")||mn.equals("sub")||mn.equals("subs")) {
            String[] parts=op.split(","); if(parts.length==3) { String dstRaw=parts[0].trim(), a=parts[1].trim(), b=parts[2].trim(); if(b.startsWith("#")) b=b.substring(1);
                String dst=renameReg(dstRaw); if(dst.startsWith("arg")&&dst.length()<=5) { String opSym=mn.startsWith("sub")?"-":"+"; argValues.put(dst,renameReg(a)+" "+opSym+" "+b); } }
        }
    }

    private static String appendCallArgs(String callExpr, Map<String,String> argValues) {
        if(callExpr==null) return callExpr; int paren=callExpr.indexOf('('); if(paren<0||paren==0) return callExpr;
        String fname=callExpr.substring(0,paren); StringBuilder args=new StringBuilder(), decls=new StringBuilder(); boolean first=true;
        for(int i=0;i<8;i++) { String name="arg"+i, val=argValues.get(name); if(val==null||"?".equals(val)) continue;
            if(!first){args.append(", ");decls.append(", ");} args.append(val);
            String ty=val.startsWith("\"")?"const char*":(val.contains("+")||val.contains("sp")||val.startsWith("0x")||val.contains("*("))?"void*":"int";
            decls.append(ty).append(' ').append(name); first=false; }
        StringBuilder sb=new StringBuilder(fname).append('(').append(args).append(");");
        if(decls.length()>0) sb.append("  // ").append(decls); return sb.toString();
    }

    private static String hex(long addr) { return Long.toHexString(addr).toUpperCase(Locale.ROOT); }

    private static boolean isAdrpOrAdr(DisassembledInstruction ins) { String mn=n(ins.mnemonic); return mn.equals("adrp")||mn.equals("adr"); }
    private static boolean isAddToSameReg(DisassembledInstruction first, DisassembledInstruction second) {
        String mn2=n(second.mnemonic); if(!(mn2.equals("add")||mn2.equals("adds"))) return false;
        String reg1=firstRegOf(first.opStr); String[] parts=second.opStr.split(",");
        if(parts.length>=2) { String dst=parts[0].trim(); return dst.equals(reg1)&&(parts[1].trim().equals(reg1)||(parts.length==3&&parts[1].trim().equals(reg1))); }
        return false;
    }
}
