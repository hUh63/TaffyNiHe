package top.wkbin.taixu.harness.groupchat

import top.wkbin.taixu.harness.REASONING_STREAM_WAIT_MS

/** Do not cancel hidden reasoning earlier than the provider's supported stream wait. */
internal object GroupReplyTimeoutPolicy {
    const val OUTPUT_WAIT_MS = REASONING_STREAM_WAIT_MS
    const val REPLY_TIMEOUT_MS = 60 * 60 * 1000L
}
