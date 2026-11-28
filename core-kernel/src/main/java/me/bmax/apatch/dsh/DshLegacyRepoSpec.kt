package me.bmax.apatch.dsh

/**
 * 旧仓库地址的识别与改写。
 *
 * ## 为什么抽到 core-kernel 做成纯函数
 *
 * 这段逻辑原本内联在 `DshPluginRepo.migrateLegacyGitSpecs()` 里，靠容器里跑
 * `grep`/`sed` 完成。两个问题：
 *
 * 1. **不可测**：要验证"大小写变体都覆盖到了"必须起一个 proot 容器，代价高到没人会写。
 * 2. **区分大小写**：`grep`/`sed` 默认敏感，而 `pnpm-lock.yaml` 里 `pnpm` 归一化过的
 *    小写形式（`ipf-sinon/dsh-folk-cloud`）是真实存在的。漏掉一种变体 = 没修。
 *
 * 抽成纯函数后，规则本身可以在 JVM 上直接测，宿主侧只负责读写文件。
 *
 * ## 改写的边界
 *
 * 只替换 `组织名/仓库名` 这一段，其余字节原样保留。用正则而不是整份重排：
 * `package.json` 里可能有用户手写的字段、自定义 scripts，重排会让 diff 无法审阅。
 */
object DshLegacyRepoSpec {

    /** 改名前组织名下的仓库路径（相对 github 根，不含协议/域名）。 */
    const val LEGACY_REPO_PATH = "IPF-Sinon/dsh-folk-cloud"

    /** 改名后的仓库路径。 */
    const val CURRENT_REPO_PATH = "DSH-Folk/dsh-folk-cloud"

    /**
     * 匹配旧路径，**忽略大小写**。
     *
     * 忽略大小写不是保险起见：`pnpm-lock.yaml` 里确实出现过小写形式
     * （pnpm 对 git 依赖的 resolution 做过归一化）。只认一种写法等于修一半。
     */
    private val LEGACY_PATTERN = Regex(
        Regex.escape(LEGACY_REPO_PATH),
        RegexOption.IGNORE_CASE,
    )

    /** 这段文本里有没有旧地址（任意大小写变体）。 */
    fun containsLegacy(text: String): Boolean = LEGACY_PATTERN.containsMatchIn(text)

    /**
     * 把旧地址换成新地址，其余内容一字不动。
     *
     * 没有旧地址时原样返回（`=== ` 比较即可判断是否需要写盘）。
     */
    fun rewrite(text: String): String = LEGACY_PATTERN.replace(text, CURRENT_REPO_PATH)

    /**
     * git 层的旧地址写法清单：要把这些前缀重写到新仓库。
     *
     * 与 [DshPluginRepo.GIT_REWRITE_BASES] 是**不同维度**的两组：
     * 那组把"写法"归一（ssh → https），这组把"路径"改对（旧组织 → 新组织）。
     * 两者都要，缺任一个都封不住 `bad object`。
     */
    fun legacyGitBases(): List<String> = listOf(
        "git@github.com:" + LEGACY_REPO_PATH,
        "git+ssh://git@github.com/" + LEGACY_REPO_PATH,
        "ssh://git@github.com/" + LEGACY_REPO_PATH,
        "https://github.com/" + LEGACY_REPO_PATH,
        "git+https://github.com/" + LEGACY_REPO_PATH,
    )
}
