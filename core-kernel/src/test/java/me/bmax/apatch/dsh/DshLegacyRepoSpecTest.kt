package me.bmax.apatch.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DshLegacyRepoSpec] 的单元测试。
 *
 * ## 为什么这几条必须测
 *
 * 它决定"老用户能不能装上插件"。错了的表现不是崩溃，而是**安装静默失败** ——
 * 用户更新任何插件都会收到一条和他无关的 `git clone` 报错，挂不挂代理都一样
 * （issue #12）。这种错没有异常栈指向我们，只能靠用户投诉才发现。
 *
 * 两个真实踩过的坑，各有一条测试盯着：
 *
 * 1. **大小写**：第一版用容器里的 `grep`/`sed`，默认区分大小写，而
 *    `pnpm-lock.yaml` 里 pnpm 归一化过的小写形式是真实存在的 —— 漏一种变体等于没修。
 * 2. **写法的种类**：`pnpm` 把 `github:owner/name` 解析成 `git+ssh://…`，
 *    而用户 git 配置里还可能是 scp 写法。少收一种，那条路径就继续报 `bad object`。
 */
class DshLegacyRepoSpecTest {

    @Test
    fun `识别 github 规格写法`() {
        assertTrue(
            DshLegacyRepoSpec.containsLegacy(
                """{ "dsh-folk-cloud": "github:IPF-Sinon/dsh-folk-cloud" }""",
            ),
        )
    }

    @Test
    fun `识别 pnpm 解析出的 git+ssh 写法`() {
        assertTrue(
            DshLegacyRepoSpec.containsLegacy(
                "git+ssh://git@github.com/IPF-Sinon/dsh-folk-cloud.git",
            ),
        )
    }

    @Test
    fun `识别 scp 写法`() {
        // 真机报错里就是这种：fatal: bad object ...
        assertTrue(DshLegacyRepoSpec.containsLegacy("git@github.com:IPF-Sinon/dsh-folk-cloud"))
    }

    @Test
    fun `识别小写变体`() {
        // pnpm 对 git 依赖的 resolution 做过归一化，lockfile 里会留小写形式。
        // 区分大小写的实现会漏掉这条 —— 那就是"改了但没生效"。
        assertTrue(DshLegacyRepoSpec.containsLegacy("github:ipf-sinon/dsh-folk-cloud"))
        assertTrue(DshLegacyRepoSpec.containsLegacy("GIT+SSH://GIT@GITHUB.COM/IPF-SINON/DSH-FOLK-CLOUD"))
    }

    @Test
    fun `改写后统一成新组织`() {
        assertEquals(
            "github:DSH-Folk/dsh-folk-cloud",
            DshLegacyRepoSpec.rewrite("github:IPF-Sinon/dsh-folk-cloud"),
        )
        // 小写变体改写后也归一成规范大小写：新地址只有一个正确写法。
        assertEquals(
            "github:DSH-Folk/dsh-folk-cloud",
            DshLegacyRepoSpec.rewrite("github:ipf-sinon/dsh-folk-cloud"),
        )
    }

    @Test
    fun `改写不动其它依赖`() {
        // 只换目标那一段，邻居一个字节都不能动 —— 改错别人的依赖比不修更糟。
        val src = """
            {
              "dependencies": {
                "dsh-folk-cloud": "github:IPF-Sinon/dsh-folk-cloud",
                "dsh-config-manager": "0.12.0",
                "other": "github:someone/other-repo"
              }
            }
        """.trimIndent()
        val out = DshLegacyRepoSpec.rewrite(src)
        assertTrue(out.contains("github:DSH-Folk/dsh-folk-cloud"))
        assertTrue(out.contains("\"dsh-config-manager\": \"0.12.0\""))
        assertTrue(out.contains("github:someone/other-repo"))
        assertFalse(DshLegacyRepoSpec.containsLegacy(out))
    }

    @Test
    fun `没有旧地址时不误判`() {
        // 幂等的判据：绝大多数用户没有旧地址，不该被写盘。
        assertFalse(DshLegacyRepoSpec.containsLegacy("""{ "dsh-folk-cloud": "github:DSH-Folk/dsh-folk-cloud" }"""))
        assertFalse(DshLegacyRepoSpec.containsLegacy("""{ "dependencies": {} }"""))
        assertFalse(DshLegacyRepoSpec.containsLegacy(""))
        // 只差一点点的字符串不能命中：名字是别的仓库
        assertFalse(DshLegacyRepoSpec.containsLegacy("github:IPF-Sinon/dsh-folk-cloud-extra"))
    }

    @Test
    fun `改写是幂等的`() {
        val once = DshLegacyRepoSpec.rewrite("github:IPF-Sinon/dsh-folk-cloud")
        assertEquals(once, DshLegacyRepoSpec.rewrite(once))
    }

    @Test
    fun `git 兜底写法覆盖五种`() {
        val bases = DshLegacyRepoSpec.legacyGitBases()
        // 少一种，那条路径就继续报 bad object：
        // github: 规格被 pnpm 解析成 git+ssh；用户 git 配置里可能是 scp；
        // lockfile 里可能存 https。
        assertEquals(5, bases.size)
        assertTrue(bases.any { it.startsWith("git@github.com:") })
        assertTrue(bases.any { it.startsWith("git+ssh://") })
        assertTrue(bases.any { it.startsWith("ssh://") })
        assertTrue(bases.any { it.startsWith("https://") })
        assertTrue(bases.any { it.startsWith("git+https://") })
        // 每一条都必须指向旧路径（否则重写了也落不到要改的地方）
        assertTrue(bases.all { it.contains(DshLegacyRepoSpec.LEGACY_REPO_PATH) })
    }
}
