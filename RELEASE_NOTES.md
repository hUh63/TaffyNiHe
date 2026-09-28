# TaffyNiHe v1.3.58

## 🚀 新增

- （本批次累积后补齐）

## ✨ 改进

- **发布资产名带版本号**：APK 从 `app-<abi>-release.apk` 改为 `taffy-<版本>-<abi>.apk`，下载后一眼能认出版本，多版本并存时不再混淆。
- **Release 正文改为分段 changelog**：按「新增 / 改进 / 修复 / 工程」分节，取代只有提交标题的自动 notes。

## 🐛 修复

- （本批次累积后补齐）

## 🔧 工程 / 构建

- 多 ABI 发布流水线：资产在**收集阶段**即重命名，保证 `SHA256SUMS` 中的文件名与实际资产一致。
- 发布正文来源：仓库根 `RELEASE_NOTES.md`（存在即采用，否则回退 GitHub 自动 notes）。

---

**完整对比**：https://github.com/hUh63/TaffyNiHe/compare/v1.3.57...v1.3.58
