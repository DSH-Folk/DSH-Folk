package me.bmax.apatch.dsh.ext

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [ExtensionServiceRegistry] 的单元测试。
 *
 * 这个总线是「扩展整块替换宿主子系统」的唯一通道。它错了的表现很隐蔽：
 * 不是崩溃，而是**宿主用了错的那份实现**（或扩展的替换静默不生效）。
 */
class ExtensionServiceRegistryTest {

    /** 测试用接口：模拟宿主的一个子系统。 */
    interface Greeter {
        fun greet(): String
    }

    private class HostGreeter : Greeter {
        override fun greet() = "host"
    }

    private class ExtGreeter : Greeter {
        override fun greet() = "ext"
    }

    @Before
    fun setUp() {
        ExtensionServiceRegistry.clearAll()
        ExtensionGate.latched = false
    }

    @After
    fun tearDown() {
        ExtensionServiceRegistry.clearAll()
        ExtensionGate.latched = false
    }

    @Test
    fun `没有扩展注册时返回 fallback`() {
        val result = ExtensionServiceRegistry.resolve(Greeter::class.java) { HostGreeter() }
        assertEquals("host", result.greet())
    }

    @Test
    fun `扩展注册后替换掉 fallback`() {
        ExtensionServiceRegistry.register("ext.a", Greeter::class.java, ExtGreeter())
        val result = ExtensionServiceRegistry.resolve(Greeter::class.java) { HostGreeter() }
        assertEquals("ext", result.greet())
    }

    @Test
    fun `后注册的覆盖先注册的`() {
        ExtensionServiceRegistry.register("ext.a", Greeter::class.java, ExtGreeter())
        val second = object : Greeter {
            override fun greet() = "second"
        }
        ExtensionServiceRegistry.register("ext.b", Greeter::class.java, second)
        assertEquals("second", ExtensionServiceRegistry.resolve(Greeter::class.java) { HostGreeter() }.greet())
    }

    /** 第二个测试用类型：验证"只清指定扩展"时另一条注册不受影响。 */
    interface Counter {
        fun count(): Int
    }

    @Test
    fun `unregisterAll 只清掉指定扩展的注册`() {
        ExtensionServiceRegistry.register("ext.a", Greeter::class.java, ExtGreeter())
        ExtensionServiceRegistry.register("ext.b", Counter::class.java, object : Counter {
            override fun count() = 42
        })

        ExtensionServiceRegistry.unregisterAll("ext.a")

        // ext.a 的 Greeter 没了 → 回到 fallback
        assertEquals("host", ExtensionServiceRegistry.resolve(Greeter::class.java) { HostGreeter() }.greet())
        // ext.b 的注册还在（这是本条测试的重点：unregisterAll 不能清掉别人的）
        assertEquals("ext.b", ExtensionServiceRegistry.providerIdOf(Counter::class.java))
        assertEquals(42, ExtensionServiceRegistry.resolve(Counter::class.java) { object : Counter {
            override fun count() = -1
        } }.count())
    }

    @Test
    fun `providerIdOf 能查到是谁接管的`() {
        assertNull(ExtensionServiceRegistry.providerIdOf(Greeter::class.java))
        ExtensionServiceRegistry.register("ext.a", Greeter::class.java, ExtGreeter())
        assertEquals("ext.a", ExtensionServiceRegistry.providerIdOf(Greeter::class.java))
    }

    @Test
    fun `熔断后一律返回 fallback`() {
        ExtensionServiceRegistry.register("ext.a", Greeter::class.java, ExtGreeter())
        ExtensionGate.latched = true

        // 熔断之后不能还有扩展在跑 —— 这是逃生通道的语义。
        assertEquals("host", ExtensionServiceRegistry.resolve(Greeter::class.java) { HostGreeter() }.greet())
    }

    @Test
    fun `熔断后注册被忽略`() {
        ExtensionGate.latched = true
        ExtensionServiceRegistry.register("ext.a", Greeter::class.java, ExtGreeter())
        assertNull(ExtensionServiceRegistry.providerIdOf(Greeter::class.java))
    }

    @Test
    fun `同一实例被原样返回（不做拷贝）`() {
        val impl = ExtGreeter()
        ExtensionServiceRegistry.register("ext.a", Greeter::class.java, impl)
        val got = ExtensionServiceRegistry.resolve(Greeter::class.java) { HostGreeter() }
        assertSame(impl, got)
    }
}
