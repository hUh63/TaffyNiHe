package com.soreverse.mcp.core

object DownloadMirrorPolicy {
    private val prefixes = listOf(
        "https://ghproxy.net/",
        "https://gh-proxy.com/",
        "https://ghp.ci/",
        "https://mirror.ghproxy.com/",
        "https://v6.gh-proxy.org/",
        "https://gh.llkk.cc/",
        "https://github-proxy.memory-echoes.cn/",
        "https://hub.gitmirror.com/",
        "https://moeyy.cn/gh-proxy/",
        "https://gh.zwy.one/",
        "https://ghproxy.imciel.com/",
        "https://gh.ddlc.top/",
        "https://ghproxy.cfd/",
        "https://ghproxy.liumingye.cn/",
        "https://gh.jiwu.dev/",
        "https://gh-proxy.lengzzz.com/",
        "https://github.moeyy.xyz/",
        "https://ghproxy.net.kg/",
        "https://ghproxy.17lai.site/",
    )

    fun candidates(original: String): List<String> {
        if (!original.startsWith("https://github.com/")) return listOf(original)
        val replacements = listOf(
            "https://kkgithub.com",
            "https://bgithub.xyz",
            "https://github.com.cnpmjs.org",
        ).mapNotNull { host ->
            original.replaceFirst("https://github.com", host)
        }
        val xget = original.replaceFirst("https://github.com", "https://xget.xi-xu.me/gh")
        return (prefixes.map { it + original } + replacements + xget + original).distinct()
    }

    /**
     * 完整性产物（SHA-256 校验和文件）的候选地址。与 APK 下载不同，这类请求绝不能
     * 走第三方镜像：被攻陷/攻击者控制的镜像可以把 APK 与校验和一起伪造，或干脆丢掉
     * 校验和请求以逼出一次未校验安装。因此 GitHub release 资产只取官方 github.com
     * 地址；非 GitHub 来源本来就没有镜像集，原样返回。
     *
     * 上游 1.0.22 (#131) 借鉴。
     */
    fun checksumCandidates(original: String): List<String> = if (original.startsWith("https://github.com/")) {
        listOf(original)
    } else {
        candidates(original)
    }
}
