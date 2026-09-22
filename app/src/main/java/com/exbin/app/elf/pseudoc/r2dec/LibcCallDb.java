package com.exbin.app.elf.pseudoc.r2dec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v3.5: 常见 libc/pthread/socket 函数参数个数与头文件表 (对齐 r2dec db/macros.js).
 * <ul>
 *   <li>{@link #lookupArgs} — call 参数回溯时给出更准的参数个数 (优先于寄存器扫描)</li>
 *   <li>{@link #includeFor} — 输出头部补充 {@code #include} 声明 (函数宏)</li>
 * </ul>
 * 变参函数 (printf/scanf/fprintf 等) 取最少必需参数, 与 r2dec 一致.
 */
public final class LibcCallDb {

    private static final Map<String, Integer> ARGS = new HashMap<>();
    private static final Map<String, String> INCLUDES = new HashMap<>();

    private static final Pattern CALL = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(");

    static {
        put("libc_start_main", 7, null);
        put("exit", 1, "<stdlib.h>");
        put("access", 2, "<stdio.h>");
        put("fscanf", 2, "<stdio.h>");
        put("fgets", 3, "<stdio.h>");
        put("fclose", 1, "<stdio.h>");
        put("fopen", 2, "<stdio.h>");
        put("fwrite", 4, "<stdio.h>");
        put("fread", 4, "<stdio.h>");
        put("textdomain", 2, "<libintl.h>");
        put("bindtextdomain", 2, "<libintl.h>");
        put("setlocale", 2, "<locale.h>");
        put("wcscmp", 2, "<wchar.h>");
        put("strcmp", 2, "<string.h>");
        put("strncmp", 3, "<string.h>");
        put("memset", 3, "<string.h>");
        put("memcpy", 3, "<string.h>");
        put("strcpy", 2, "<string.h>");
        put("puts", 1, "<stdio.h>");
        put("fprintf", 2, "<stdio.h>");
        put("printf", 1, "<stdio.h>");
        put("scanf", -1, "<stdio.h>");
        put("getenv", 1, "<stdlib.h>");
        put("time", 1, "<time.h>");
        put("localtime", 1, "<time.h>");
        put("dcgettext", 2, "<libintl.h>");
        put("pthread_create", 4, "<pthread.h>");
        put("pthread_join", 2, "<pthread.h>");
        put("pthread_exit", 1, "<pthread.h>");
        put("pthread_cancel", 1, "<pthread.h>");
        put("pthread_attr_init", 1, "<pthread.h>");
        put("pthread_attr_destroy", 1, "<pthread.h>");
        put("socket", 3, "<sys/socket.h>");
        put("accept", 3, "<sys/socket.h>");
        put("bind", 3, "<sys/socket.h>");
        put("connect", 3, "<sys/socket.h>");
        put("getsockname", 3, "<sys/socket.h>");
        put("listen", 2, "<sys/socket.h>");
        put("recv", 2, "<sys/socket.h>");
        put("recvfrom", 6, "<sys/socket.h>");
        put("recvmsg", 2, "<sys/socket.h>");
        put("open", 3, "<fcntl.h>");
        put("creat", 2, "<fcntl.h>");
        put("close", 1, "<unistd.h>");
        put("read", 3, "<unistd.h>");
        put("write", 3, "<unistd.h>");
        put("daemon", 2, "<unistd.h>");
        put("perror", 1, "<stdio.h>");
        put("shmget", 3, "<sys/shm.h>");
        put("shmat", 3, "<sys/shm.h>");
        put("signal", 1, "<signal.h>");
        // iOS (对齐 r2dec, 对 Android 分析无实际作用)
        put("UIApplicationMain", 4, null);
        put("NSStringFromClass", 1, null);
        put("NSLog", 1, null);
    }

    private static void put(String name, int args, String include) {
        ARGS.put(name, args);
        if (include != null) INCLUDES.put(name, include);
    }

    private LibcCallDb() {
    }

    /**
     * 查函数参数个数. 名称会先做符号前缀清洗 (sym.imp./imp./下划线前缀).
     *
     * @param name callee 名 (可为 null)
     * @return 参数个数, -1 表示未知
     */
    public static int lookupArgs(String name) {
        if (name == null || name.isEmpty()) return -1;
        Integer n = ARGS.get(sanitize(name));
        return n != null ? n : -1;
    }

    /**
     * 查函数对应的头文件 (函数宏), 无则返回 null.
     */
    public static String includeFor(String name) {
        if (name == null || name.isEmpty()) return null;
        return INCLUDES.get(sanitize(name));
    }

    /**
     * 扫描伪 C body, 收集被调用 libc 函数所需的头文件 (去重).
     *
     * @param lines 伪 C 输出行
     * @return 头文件名列表, 如 ["stdio.h", "stdlib.h"]; 无则空列表
     */
    public static List<String> scanIncludes(List<String> lines) {
        Set<String> incs = new TreeSet<>();
        for (String line : lines) {
            if (line == null) continue;
            Matcher m = CALL.matcher(line);
            while (m.find()) {
                String inc = INCLUDES.get(m.group(1));
                if (inc != null) incs.add(inc);
            }
        }
        return new ArrayList<>(incs);
    }

    /** 清洗符号前缀: sym.imp.foo / imp.foo / _foo / foo 归一为 foo. */
    private static String sanitize(String name) {
        String n = name;
        int idx;
        if ((idx = n.indexOf("sym.imp.")) >= 0) n = n.substring(idx + "sym.imp.".length());
        else if ((idx = n.indexOf("imp.")) >= 0) n = n.substring(idx + "imp.".length());
        else if ((idx = n.indexOf("sym.")) >= 0) n = n.substring(idx + "sym.".length());
        while (n.startsWith("_")) n = n.substring(1);
        return n;
    }
}
