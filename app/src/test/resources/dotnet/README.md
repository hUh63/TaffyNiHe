# .NET 测试样本

`System.Buffers.dll` 是 **真实的** .NET 程序集，用于 `DotNetReaderRealSampleTest`。

| 项 | 值 |
|---|---|
| 来源 | nuget.org 包 `System.Buffers` 4.5.1 中的 `lib/netstandard2.0/System.Buffers.dll` |
| 大小 | 21,376 字节 |
| 版本 | Assembly Version 4.0.3.0（FileVersion 4.6.26515.06） |
| 许可 | MIT（.NET Foundation） |
| 用途 | 只读/写入解析校验，不再单独分发 |

选它的原因：只有一个 2 万字节的真实程序集才带得动 case 表宽度、coded index、
用户字符串堆这些「手算最小样本测不到」的边界；而它足够小，不会把仓库拖胖。
测试里的期望值由一份独立的 Python 解析器在同一文件上算出，不是从被测代码抄的。
