package top.wkbin.taixu

// 已退役。
//
// 早期实现把太墟壳「精简」成在此处渲染 TaiXuTheme + TaiXuNavHost，嵌在宿主（塔菲）的
// Activity 里；但太墟整套布局按 edge-to-edge + WindowInsets 设计，而宿主 Activity 没有
// enableEdgeToEdge()/splash 预处理，measure 阶段会读到异常 insets 并崩溃
// （v1.4.2 / v1.4.3 真机启动崩溃即此）。
//
// 现在壳运行在它自己的 Activity（top.wkbin.taixu.MainActivity，含 splash + edgeToEdge +
// 主题 + onboarding 全链路），宿主只负责「是否进入壳」的判断与兜底界面。
// 壳层入口约定见 top.wkbin.taixu.TaiXuShellEntry.kt / isTaiXuShellReady()。
