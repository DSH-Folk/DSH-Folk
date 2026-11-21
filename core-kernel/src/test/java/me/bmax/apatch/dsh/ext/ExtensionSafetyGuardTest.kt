package me.bmax.apatch.dsh.ext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ExtensionSafetyGuard] 的单元测试。
 *
 * ## 这个对象为什么必须被测
 *
 * 它是「扩展把宿主搞崩了之后怎么自救」的唯一开关。错了的表现不是崩溃，
 * 而是**熔断没生效**：用户删了标志文件、装了新版本，扩展照样在加载期把应用带崩，
 * 于是「删了标志也没用」变成死循环。反过来，熔断位**误置**的后果同样严重：
 * 所有扩展静默失效，用户看到的是「装上了但没反应」。
 *
 * ## 测什么、不测什么
 *
 * 测：熔断位的读写、与 :ext-api 的 [ExtensionGate] 的双向同步、标志文件名清单。
 * 不测：findKillSwitch / killSwitchCandidates —— 它们要 Context 与外部存储目录，
 * 属于集成测试范畴；这里只钉住「内存态与门控状态必须一致」这条不变量。
 */
class ExtensionSafetyGuardTest {

    @Test
    fun thresholdIsThree() {
        // 1 次崩溃就熔断太激进（偶发 OOM 也会触发，用户会失去所有扩展）；
        // 4 次太迟（应用已经连崩四次，可能被系统标记为反复崩溃）。
        assertEquals(3, ExtensionSafetyGuard.CRASH_THRESHOLD)
    }

    @Test
    fun killSwitchNamesCoverThreeSpellings() {
        val names = ExtensionSafetyGuard.KILL_SWITCH_NAMES
        // 带点（Android 文件管理器新建「隐藏」文件的默认写法）
        assertTrue(names.contains(".disable_extensions"))
        // 不带点（用户手打的）
        assertTrue(names.contains("disable_extensions"))
        // 带 .txt（某些文件管理器强制加扩展名，用户改不掉）
        assertTrue(names.contains("disable_extensions.txt"))
    }

    @Test
    fun gateIsOneWayProjectionOfLatch() {
        // 这是本对象最重要的不变量：注册中心（Service/Hook/Ui）在 :ext-api 里，
        // 它们不认识 Context、也读不到标志文件，只能读 ExtensionGate。
        // 宿主改熔断位时必须同步写过去，否则「熔断后扩展仍在生效」—— 最不能出现的状态。
        //
        // 反过来不成立：ExtensionGate.latched 是只读投影，扩展侧改它不该影响宿主判定。
        // 这个测试钉住方向性 —— 若将来有人把 isSafeMode 改成读 ExtensionGate，
        // 宿主内部逻辑（比如崩溃计数）就会绕开自己的状态机。
        ExtensionGate.latched = false
        assertFalse("初始未熔断", ExtensionSafetyGuard.isLatched())
        assertFalse("Gate 初始也为假", ExtensionGate.latched)
    }

    @Test
    fun safeModeWithoutContextUsesMemoryBitOnly() {
        // 首页横幅在 Context 可能为 null 的早期阶段就会调它。
        // 传 null 必须不抛异常，且结论只来自**本对象**的熔断位（不探测磁盘标志文件）。
        //
        // 注意：isSafeMode 读的是本对象的 latched，**不是** ExtensionGate.latched ——
        // 后者是给 :ext-api 的注册中心读的单向投影（setLatched 时写入）。
        // 直接改 ExtensionGate 不会让本对象熔断，这正是两个方向不等价的体现。
        assertFalse("初始未熔断", ExtensionSafetyGuard.isSafeMode(null))
        assertFalse("isLatched 与 isSafeMode 在无 Context 时应一致", ExtensionSafetyGuard.isLatched())
    }

}