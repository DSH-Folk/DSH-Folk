package me.bmax.apatch.dsh

/**
 * semver 比较（够用版）。原本在 `DshPluginRepo.kt` 里 —— 抽出来是因为
 * `DshRuntimeModels` 要用它，而 PluginRepo 依赖整个插件商店，下沉不动。
 */
/**
 * semver 比较（够用版）：先比 major.minor.patch，相同再比预发布标识。
 *
 * 预发布必须参与比较，否则 `1.0.0-rc.1` 与 `1.0.0` 会判成相等 —— 用户装着 rc
 * 却看不到正式版的更新。规则同 semver：有预发布标识的版本小于没有的，
 * 都有则按点分段逐段比（数字段按数值，其余按字典序）。
 */
fun compareVersions(a: String, b: String): Int {
    fun clean(v: String) = v.trim().removePrefix("v").substringBefore('+')
    fun core(v: String) = clean(v).substringBefore('-')
    fun pre(v: String) = clean(v).substringAfter('-', "")

    val pa = core(a).split('.')
    val pb = core(b).split('.')
    for (i in 0 until maxOf(pa.size, pb.size)) {
        // 缺的段按 0 补：1.0 与 1.0.0 是同一个版本，别比出 -1
        val sa = pa.getOrNull(i) ?: "0"
        val sb = pb.getOrNull(i) ?: "0"
        val x = sa.toIntOrNull()
        val y = sb.toIntOrNull()
        if (x == null || y == null) {
            val c = sa.compareTo(sb)
            if (c != 0) return c
        } else if (x != y) {
            return x.compareTo(y)
        }
    }

    val qa = pre(a)
    val qb = pre(b)
    if (qa == qb) return 0
    // 正式版 > 预发布版
    if (qa.isEmpty()) return 1
    if (qb.isEmpty()) return -1
    val ra = qa.split('.')
    val rb = qb.split('.')
    for (i in 0 until maxOf(ra.size, rb.size)) {
        val x = ra.getOrNull(i)
        val y = rb.getOrNull(i)
        if (x == null) return -1
        if (y == null) return 1
        val nx = x.toIntOrNull()
        val ny = y.toIntOrNull()
        val c = if (nx != null && ny != null) nx.compareTo(ny) else x.compareTo(y)
        if (c != 0) return c
    }
    return 0
}

/**
 * App 自身版本的比较（**不用 semver 的 alpha<beta 语义**）。
 *
 * ## 为什么不能直接用 [compareVersions]
 *
 * semver 规定：同核心版本下预发布标识按字典序比，所以 `2.2.1-alpha.9 < 2.2.1-beta.1`。
 * 这条规则对**第三方包**是对的，但对**我们自己的发布序列**是灾难：
 *
 * 实际踩过的坑：beta 已经走到 `2.2.1-beta.309`，之后发了一批
 * `2.2.1-alpha.310`～`alpha.317`。装了 beta.309 的用户切到 Alpha 通道后，
 * 候选集里 alpha 全被判成"比 beta.309 旧"，于是永远显示"已是最新"。
 * 用户看到的是"切了通道没用"，而代码按 semver 看完全正确 —— 这种"正确但没用"
 * 最难查。
 *
 * ## 本函数的规则
 *
 * 核心版本（major.minor.patch）仍然是主要判据：`2.3.0-alpha.1 > 2.2.9`。
 * 核心版本相同时，**只看预发布序号**，不看通道名：
 *
 * - 两边都有预发布标识 → 比序号（`alpha.317 > beta.309`，因为 317 > 309）
 * - 一边有预发布、一边没有 → 正式版更大（`2.2.1 > 2.2.1-beta.309`）
 *
 * 这条规则成立的前提是**序号全局单调递增**（见 beta.yml 的序号推导）：同一个核心
 * 版本下，后发的预发布版序号一定更大，无论通道叫什么。这也是"每次发正式版后序号
 * 归零"的配套约束 —— 归零只发生在核心版本推进时，那时核心版本已经比出大小了，
 * 序号不参与比较。
 *
 * ## 为什么单独一个函数而不是改 [compareVersions]
 *
 * [compareVersions] 还被插件版本、用户脚本版本、运行时版本使用（8 处）。那些场景
 * 面对的是**外部世界的 semver**，必须守 semver 规则。把本规则混进去会让插件更新
 * 判断出错 —— 两类版本号是不同的东西，不该共用一个比较器。
 */
fun compareAppVersions(a: String, b: String): Int {
    fun clean(v: String) = v.trim().removePrefix("v").substringBefore('+')
    fun core(v: String) = clean(v).substringBefore('-')
    fun pre(v: String) = clean(v).substringAfter('-', "")

    // 1) 核心版本：数字段逐段比，缺段补 0。
    val pa = core(a).split('.')
    val pb = core(b).split('.')
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val sa = pa.getOrNull(i) ?: "0"
        val sb = pb.getOrNull(i) ?: "0"
        val x = sa.toIntOrNull()
        val y = sb.toIntOrNull()
        if (x == null || y == null) {
            val c = sa.compareTo(sb)
            if (c != 0) return c
        } else if (x != y) {
            return x.compareTo(y)
        }
    }

    // 2) 核心版本相同：正式版 > 任何预发布版。
    val qa = pre(a)
    val qb = pre(b)
    if (qa == qb) return 0
    if (qa.isEmpty()) return 1
    if (qb.isEmpty()) return -1

    // 3) 都是预发布：只比序号（通道名不参与）。
    //
    // 序号取"最后一个点之后的数字段"：alpha.317 → 317，beta.309 → 309。
    // 兼容两段式（beta.1.2 → 2）—— 但当前发布流程只产出单段序号，两段式只是兜底，
    // 让它不至于因为解析不出数字而静默判成相等。
    fun seq(v: String): Int? = v.substringAfterLast('.').toIntOrNull()
    val na = seq(qa)
    val nb = seq(qb)
    if (na != null && nb != null) {
        if (na != nb) return na.compareTo(nb)
        // 序号相同但通道不同（alpha.5 vs beta.5）：这是异常状态，按通道名定序求稳定，
        // 免得比较结果依赖入参顺序。正常发布流程不会产出这种组合。
        return qa.compareTo(qb)
    }
    // 有一边解析不出序号（比如 rc.1）：退回 semver 的逐段比，至少不会判成相等。
    return compareVersions(a, b)
}
