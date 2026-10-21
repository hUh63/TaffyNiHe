package top.wkbin.taixu.runtime.terminal

/**
 * 终端登录横幅。由 [top.wkbin.taixu.runtime.LinuxRuntimeImpl] 在终端会话启动前
 * 写入发行版 `/opt/taixu/motd`，登录 shell 通过 `cat` 打印，绕开命令串转义问题。
 */
internal fun terminalBanner(): String =
    "太墟 · TaiXu Linux AI Runtime\n"
