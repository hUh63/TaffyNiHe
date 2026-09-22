package com.exbin.app.elf;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * ELF 文件读取器 (mmap 后端, off-heap).
 * <p>
 * v2.0.3 关键修复: 之前用 {@code is.readAllBytes()} 把整个文件 (例如 libminecraftpe.so 18MB+)
 * 读成单个 byte[], 在 heap 紧的设备上 (largeHeap=false) 触发 OOM:
 *   "Failed to allocate a 18874384 byte allocation with 1664 free bytes"
 * <p>
 * 现改为 {@link MappedByteBuffer} (off-heap, OS page cache 友好), 一次 mmap
 * 不会消耗 Java heap. 同时在文件大小 &gt; 100MB 时 fail-fast 抛异常, 防止无意义的
 * 巨大文件拖死手机.
 *
 * <p>提供与 byte[] 类似的只读随机访问 API:
 * <ul>
 *   <li>{@link #size()} - 文件大小 (等价于 byte[].length)</li>
 *   <li>{@link #u(long)} - 读 1 字节 (无符号 0~255), 等价于 {@code data[i] & 0xff}</li>
 *   <li>{@link #b(long)} - 读 1 字节 (有符号 -128~127), 等价于 {@code data[i]}</li>
 *   <li>{@link #i(long, ByteOrder)} - 读 4 字节 int</li>
 *   <li>{@link #l(long, ByteOrder)} - 读 8 字节 long</li>
 *   <li>{@link #copy(long, int)} - 读一段 byte[], 等价于 {@code Arrays.copyOfRange}</li>
 *   <li>{@link #string(long, int)} - 读 ISO-8859-1 字符串</li>
 * </ul>
 */
public final class ElfFileReader implements AutoCloseable {

    /** 单个 ELF 文件上限: 100MB. 超过此值立刻 fail-fast 抛 OutOfMemoryError-style 异常. */
    public static final long MAX_FILE_SIZE = 100L * 1024 * 1024;

    private final File file;
    private FileChannel channel;
    private MappedByteBuffer map;
    private final long fileSize;

    public ElfFileReader(File f) throws IOException {
        this.file = f;
        this.fileSize = f.length();
        if (fileSize <= 0) {
            throw new IOException("ELF 文件为空: " + f.getAbsolutePath());
        }
        // v2.0.4: 去除 v2.0.3 引入的 100MB 上限, 支持 1KB ~ 1GB+ 的 SO.
        // 任何大小都用 mmap, off-heap 内存由 OS page cache 负责, 不消耗 Java heap.
        // 直接用 FileChannel.map 走 OS page cache, 不消耗 Java heap
        try {
            this.channel = new FileInputStream(f).getChannel();
            this.map = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
            this.map.order(ByteOrder.LITTLE_ENDIAN); // 默认 LE, 调用方可改
        } catch (Throwable t) {
            // mmap 失败 (例如某些 ROM 上有 size 限制), fallback 到全量加载
            close();
            throw new IOException("mmap 失败: " + t.getMessage(), t);
        }
    }

    public long size() { return fileSize; }

    public byte b(long off) {
        if (off < 0 || off >= fileSize) return 0;
        return map.get((int) off);
    }

    public int u(long off) {
        if (off < 0 || off >= fileSize) return 0;
        return map.get((int) off) & 0xff;
    }

    public int i(long off, ByteOrder order) {
        if (off < 0 || off + 4 > fileSize) return 0;
        ByteBuffer dup = map.duplicate();
        dup.order(order);
        dup.position((int) off);
        return dup.getInt();
    }

    public long l(long off, ByteOrder order) {
        if (off < 0 || off + 8 > fileSize) return 0;
        ByteBuffer dup = map.duplicate();
        dup.order(order);
        dup.position((int) off);
        return dup.getLong();
    }

    public short s(long off, ByteOrder order) {
        if (off < 0 || off + 2 > fileSize) return 0;
        ByteBuffer dup = map.duplicate();
        dup.order(order);
        dup.position((int) off);
        return dup.getShort();
    }

    /**
     * 读一段 byte[] (off-heap -> on-heap 拷贝).
     * 等价于 {@code Arrays.copyOfRange(data, (int)off, (int)(off+len))}.
     * 自动裁剪越界: 实际返回长度可能 &lt; len (尾部 0 填充).
     */
    public byte[] copy(long off, int len) {
        if (len <= 0) return new byte[0];
        if (off < 0) {
            int skip = (int) -off;
            if (skip >= len) return new byte[len]; // 全 0
            off = 0;
            len -= skip;
        }
        if (off >= fileSize) return new byte[len];
        long avail = fileSize - off;
        int real = (int) Math.min(len, avail);
        byte[] out = new byte[len];
        if (real > 0) {
            ByteBuffer dup = map.duplicate();
            dup.position((int) off);
            dup.get(out, 0, real);
            // 尾部保留 0 (新建数组默认就是 0)
        }
        return out;
    }

    public String string(long off, int len) {
        if (len <= 0) return "";
        if (off < 0 || off >= fileSize) return null;
        long avail = fileSize - off;
        int real = (int) Math.min(len, avail);
        if (real <= 0) return null;
        try {
            return new String(copy(off, real), java.nio.charset.StandardCharsets.ISO_8859_1);
        } catch (Throwable t) {
            return null;
        }
    }

    public File file() { return file; }

    @Override
    public void close() {
        try {
            if (channel != null) channel.close();
        } catch (Throwable ignored) {}
        channel = null;
        map = null;
    }
}
