package me.bmax.apatch.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [compareAppVersions] 的单元测试。
 *
 * ## 为什么这个函数必须单独测
 *
 * 它决定"用户能不能收到更新"。错了的表现不是崩溃，而是**更新通道静默失效** ——
 * 用户在设置里切了通道、点了检查更新，界面说"已是最新"，而实际上有新版本。
 * 这种错没有日志、没有异常、没有崩溃栈，只能靠用户投诉才会被发现。
 *
 * 真实事故（2026-11）：beta 线走到 `2.2.1-beta.309`，之后发了
 * `2.2.1-alpha.310`～`alpha.317`。装了 beta.309 的用户切到 Alpha 通道后
 * 永远收不到更新 —— 因为 semver 规定同核心版本下 alpha < beta，那些 alpha
 * 全被判成"比本机旧"。代码按 semver 看完全正确，功能却完全没用。
 *
 * ## 本测试钉住的规则
 *
 * 1. 核心版本优先：`2.3.0-alpha.1 > 2.2.9`
 * 2. 同核心版本下**只比序号**，通道名不参与：`alpha.317 > beta.309`
 * 3. 正式版 > 同核心版本的任何预发布版
 * 4. 序号相同才退回比通道名（保证比较结果不依赖入参顺序）
 *
 * 第 2 条是本测试的核心 —— 它是上面那次事故的直接修复。
 */
class VersionCompareAppTest {

    private fun cmp(a: String, b: String) = compareAppVersions(a, b)

    /** 事故场景：alpha 的序号比 beta 大，就该算更新。 */
    @Test
    fun alphaWithHigherSeqBeatsLowerBeta() {
        assertTrue("alpha.317 应比 beta.309 新", cmp("v2.2.1-alpha.317", "2.2.1-beta.309") > 0)
        assertTrue("alpha.316 应比 beta.309 新", cmp("v2.2.1-alpha.316", "2.2.1-beta.309") > 0)
        assertTrue("alpha.313 应比 beta.309 新", cmp("v2.2.1-alpha.313", "2.2.1-beta.309") > 0)
    }

    /** 反过来也成立：beta 的序号更大就是更新（通道名不该让它变旧）。 */
    @Test
    fun betaWithHigherSeqBeatsLowerAlpha() {
        assertTrue("beta.400 应比 alpha.317 新", cmp("v2.2.1-beta.400", "2.2.1-alpha.317") > 0)
    }

    /** 同通道按序号。 */
    @Test
    fun sameChannelComparesBySeq() {
        assertTrue(cmp("v2.2.1-alpha.317", "2.2.1-alpha.313") > 0)
        assertTrue(cmp("v2.2.1-beta.9", "2.2.1-beta.10") < 0)
        assertEquals(0, cmp("v2.2.1-alpha.317", "v2.2.1-alpha.317"))
    }

    /** 核心版本优先于预发布标识：新核心版本的 alpha 比旧核心版本的新。 */
    @Test
    fun coreVersionDominates() {
        assertTrue("2.3.0-alpha.1 应比 2.2.9 新", cmp("v2.3.0-alpha.1", "2.2.9") > 0)
        assertTrue("2.2.2-alpha.1 应比 2.2.1-beta.309 新",
            cmp("v2.2.2-alpha.1", "2.2.1-beta.309") > 0)
    }

    /** 正式版 > 同核心版本的任何预发布版（含序号很大的）。 */
    @Test
    fun stableBeatsAnyPrereleaseOfSameCore() {
        assertTrue(cmp("v2.2.2", "2.2.2-alpha.999") > 0)
        assertTrue(cmp("v2.2.2-alpha.1", "2.2.2") < 0)
    }

    /** 序号归零后：新核心版本的 .1 比旧核心版本的 .999 新。 */
    @Test
    fun seqResetAfterStableWorks() {
        assertTrue("2.2.2-alpha.1 应比 2.2.1-alpha.999 新",
            cmp("v2.2.2-alpha.1", "2.2.1-alpha.999") > 0)
    }

    /** 相同序号、不同通道：不能判成相等（否则比较结果依赖入参顺序）。 */
    @Test
    fun sameSeqDifferentChannelIsDeterministic() {
        val ab = cmp("v2.2.1-alpha.5", "2.2.1-beta.5")
        val ba = cmp("v2.2.1-beta.5", "2.2.1-alpha.5")
        assertTrue("必须分出大小，不能是 0", ab != 0)
        assertEquals("两个方向必须互为相反数", -ab, ba)
    }

    /** 认 v 前缀与四段式核心版本。 */
    @Test
    fun handlesPrefixAndFourSegment() {
        assertEquals(0, cmp("v2.2.2-alpha.1", "2.2.2-alpha.1"))
        assertTrue(cmp("v2.2.2.1", "2.2.2") > 0)
    }

    /** 解析不出序号时退回 semver 逐段比，而不是静默判等。 */
    @Test
    fun fallsBackForNonNumericSeq() {
        val c = cmp("v2.2.1-rc.1", "2.2.1-rc.2")
        assertTrue("rc.1 < rc.2", c < 0)
    }
}
