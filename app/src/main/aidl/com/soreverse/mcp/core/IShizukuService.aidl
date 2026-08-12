// 塔菲逆核: Shizuku UserService AIDL 接口。
// UserService 进程由 Shizuku 以 shell 权限(uid 2000)启动,
// 内部用 Runtime.exec 执行命令, 使本应用获得 adb 级能力
// (如读取全系统 logcat、执行 shell 命令等), 无需 root。
// 设计参照 LogFox(F0x1d/LogFox) 的 IUserService。
package com.soreverse.mcp.core;

import android.os.ParcelFileDescriptor;

interface IShizukuService {
    /** 一次性执行命令, 返回 [exitCode, stdout, stderr]。 */
    String[] executeNow(String command);

    /** 启动持续进程, 返回进程 id(0 表示失败)。 */
    long execute(String command);

    /** 取进程 stdout 的只读管道。 */
    ParcelFileDescriptor processOutput(long processId);

    /** 取进程 stderr 的只读管道。 */
    ParcelFileDescriptor processError(long processId);

    /** 取进程 stdin 的只写管道。 */
    ParcelFileDescriptor processInput(long processId);

    /** 销毁进程。 */
    void destroyProcess(long processId);

    /** 销毁整个 UserService。 */
    void destroy();
}
