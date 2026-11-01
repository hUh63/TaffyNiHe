#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""16 KiB page size 兼容门禁（Android 15+）。

Android 15 起有 16 KiB page size 的设备，系统按 16 KiB 粒度把 APK 里的 .so
映射进内存。此时加载器要求：

  1. 每个 PT_LOAD 的 p_align >= 16384（bionic 会显式检查，不满足直接拒绝加载）；
  2. p_offset % p_align == p_vaddr % p_align（否则映射位置算不出来）；
  3. 理想情况下两个 PT_LOAD 不应落在同一个 16 KiB 页里 —— 一页只能有一个
     保护属性，R 段和 R+X 段共享一页时后映射的会覆盖前一个（Android 的表现是
     加载失败或者段属性错乱）。这条只给 warning：真共享页的库必须重新链接，
     无法靠改头部解决。

判定不达标就退出码 1（阻塞构建）。存量问题记录在 tools/elf-align-baseline.json，
基线内的不达标只报警告 —— 这样"新引入的不达标"会立刻挡住，老问题可见但不阻塞。

用法:
  python3 tools/check-elf-align.py                    # 检查 app/src/main/jniLibs
  python3 tools/check-elf-align.py --dir <path> ...   # 指定目录
"""
import glob
import json
import os
import struct
import sys

PAGE = 16384
PT_LOAD = 1
BASELINE = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'elf-align-baseline.json')


def parse_elf(path):
    """返回 (bits, [(offset, vaddr, filesz, memsz, align), ...]) 或抛异常。"""
    with open(path, 'rb') as fp:
        head = fp.read(64)
        if len(head) < 64 or head[:4] != b'\x7fELF':
            raise ValueError('not an ELF')
        cls, data = head[4], head[5]
        if cls != 2 or data != 1:
            raise ValueError('only ELF64 little-endian supported')
        e_phoff, = struct.unpack_from('<Q', head, 0x20)
        e_phentsize, = struct.unpack_from('<H', head, 0x36)
        e_phnum, = struct.unpack_from('<H', head, 0x38)
        if e_phoff == 0 or e_phnum == 0:
            raise ValueError('no program headers')
        if e_phoff + e_phentsize * e_phnum > os.path.getsize(path):
            raise ValueError('program header table out of file')
        fp.seek(e_phoff)
        table = fp.read(e_phentsize * e_phnum)
    loads = []
    for i in range(e_phnum):
        base = i * e_phentsize
        p_type, = struct.unpack_from('<I', table, base)
        if p_type != PT_LOAD:
            continue
        p_offset, p_vaddr, _p_paddr, p_filesz, p_memsz, p_align = \
            struct.unpack_from('<QQQQQQ', table, base + 8)
        loads.append((p_offset, p_vaddr, p_filesz, p_memsz, p_align))
    return loads


def page_spans(loads):
    """返回 [(vaddr_page_start, vaddr_page_end)]，按 16 KiB 页对齐到内存地址。"""
    out = []
    for _off, vaddr, _fs, memsz, _al in loads:
        if memsz == 0:
            continue
        out.append((vaddr - vaddr % PAGE, ((vaddr + memsz + PAGE - 1) // PAGE) * PAGE))
    return out


def overlap_pages(spans):
    """统计被两个以上 PT_LOAD 共同覆盖的 16 KiB 页数量。"""
    events = []
    for s, e in spans:
        events.append((s, 1))
        events.append((e, -1))
    events.sort()
    depth = 0
    shared = 0
    prev = None
    for pos, delta in events:
        if prev is not None and pos > prev and depth >= 2:
            shared += (pos - prev) // PAGE
        depth += delta
        prev = pos
    return shared


def check(path):
    loads = parse_elf(path)
    if not loads:
        return dict(error='no PT_LOAD')
    bad_align = [al for _o, _v, _f, _m, al in loads if al < PAGE]
    bad_congruence = [
        (off, vaddr, al) for off, vaddr, _f, _m, al in loads
        if al > 0 and off % al != vaddr % al
    ]
    return dict(
        loads=len(loads),
        min_align=min(al for *_x, al in loads) if loads else 0,
        bad_align=bad_align,
        bad_congruence=bad_congruence,
        shared_pages=overlap_pages(page_spans(loads)),
    )


def load_baseline():
    try:
        with open(BASELINE, encoding='utf-8') as fp:
            data = json.load(fp)
        return set(data.get('known_mismatch', []))
    except FileNotFoundError:
        return set()
    except Exception as exc:                                    # noqa: BLE001
        print('!! 基线文件无法解析: %s' % exc)
        return set()


def main(argv):
    dirs = []
    for i, arg in enumerate(argv):
        if arg == '--dir' and i + 1 < len(argv):
            dirs.append(argv[i + 1])
    if not dirs:
        root = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
        dirs = [os.path.join(root, p) for p in (
            'app/src/main/jniLibs/arm64-v8a',
            'taixu/src/main/jniLibs/arm64-v8a',
            'termux-terminal-emulator/src/main/jniLibs/arm64-v8a',
        )]

    baseline = load_baseline()
    fatal = 0
    warned = 0
    checked = 0
    for d in dirs:
        for path in sorted(glob.glob(os.path.join(d, '*.so'))):
            rel = os.path.relpath(path, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
            checked += 1
            try:
                info = check(path)
            except Exception as exc:                            # noqa: BLE001
                print('!! %-64s 解析失败: %s' % (rel, exc))
                fatal += 1
                continue
            if 'error' in info:
                print('!! %-64s %s' % (rel, info['error']))
                fatal += 1
                continue

            problems = []
            if info['min_align'] < PAGE:
                problems.append('p_align=0x%x < 0x%x' % (info['min_align'], PAGE))
            if info['bad_congruence']:
                problems.append('%d 段 p_offset%%p_align != p_vaddr%%p_align' % len(info['bad_congruence']))

            if problems:
                if rel in baseline:
                    print('~~ %-64s %s（基线内，待重新编译）' % (rel, '; '.join(problems)))
                    warned += 1
                else:
                    print('!! %-64s %s  <= 16 KiB 页设备上无法加载' % (rel, '; '.join(problems)))
                    fatal += 1
            elif info['shared_pages']:
                # p_align 达标但两个段共用 16 KiB 页：权限会互相覆盖，只能靠重新链接解决
                print('~~ %-64s 2 段共享 %d 个 16 KiB 页（p_align 已达标，建议重新链接）'
                      % (rel, info['shared_pages']))
                warned += 1
            else:
                print('ok %-64s p_align=0x%x' % (rel, info['min_align']))

    print('')
    print('检查 %d 个 .so：%d 个不达标（阻塞），%d 个警告' % (checked, fatal, warned))
    return 1 if fatal else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
