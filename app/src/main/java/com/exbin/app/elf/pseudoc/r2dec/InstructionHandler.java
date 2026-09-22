package com.exbin.app.elf.pseudoc.r2dec;

import java.util.List;

/**
 * Instruction handler interface — ported from r2dec's arch handler pattern.
 * <p>Each architecture (ARM32, AArch64) implements this to translate
 * machine instructions into IR nodes.
 */
public interface InstructionHandler {

    /** Architecture name (e.g. "aarch64", "arm"). */
    String archName();

    /**
     * Handle a single instruction, producing IR nodes.
     * @param insn the instruction to handle
     * @param ctx decompilation context (condition state, marker tracking)
     * @param all all instructions in the function (for look-ahead/behind)
     * @return list of IR nodes produced (may be empty for nop/skip)
     */
    List<IrNode> handle(IrInsn insn, DecompContext ctx, List<IrInsn> all);
}
