package me.bmax.apatch.dsh.ext

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [ExtensionBootPipeline] 的单元测试。
 *
 * 这条管线跑在**启动路径**上。它出错的代价是白屏或启动被扩展拖死，
 * 所以重点测三件事：顺序、异常隔离、熔断。
 */
class ExtensionBootPipelineTest {

    @Before
    fun setUp() {
        ExtensionBootPipeline.clearAll()
        ExtensionGate.latched = false
    }

    @After
    fun tearDown() {
        ExtensionBootPipeline.clearAll()
        ExtensionGate.latched = false
    }

    @Test
    fun `无 handler 时零开销返回`() {
        // 不抛、不改 ctx
        val ctx = mutableMapOf<String, Any?>()
        ExtensionBootPipeline.run(ExtensionBootPipeline.Stage.SPLASH, ctx)
        assertTrue(ctx.isEmpty())
    }

    @Test
    fun `handler 能读写 ctx`() {
        ExtensionBootPipeline.register("ext.a", ExtensionBootPipeline.Stage.AUTH) { c ->
            c["seen"] = true
        }
        val ctx = mutableMapOf<String, Any?>()
        ExtensionBootPipeline.run(ExtensionBootPipeline.Stage.AUTH, ctx)
        assertEquals(true, ctx["seen"])
    }

    @Test
    fun `按 priority 升序执行`() {
        val order = mutableListOf<String>()
        ExtensionBootPipeline.register("ext.c", ExtensionBootPipeline.Stage.SPLASH, priority = 30) { order.add("c") }
        ExtensionBootPipeline.register("ext.a", ExtensionBootPipeline.Stage.SPLASH, priority = 10) { order.add("a") }
        ExtensionBootPipeline.register("ext.b", ExtensionBootPipeline.Stage.SPLASH, priority = 20) { order.add("b") }

        ExtensionBootPipeline.run(ExtensionBootPipeline.Stage.SPLASH, mutableMapOf())

        assertEquals(listOf("a", "b", "c"), order)
    }

    @Test
    fun `一个 handler 抛异常不影响其它 handler`() {
        val ran = mutableListOf<String>()
        ExtensionBootPipeline.register("ext.bad", ExtensionBootPipeline.Stage.SPLASH, priority = 10) {
            throw IllegalStateException("故意的")
        }
        ExtensionBootPipeline.register("ext.good", ExtensionBootPipeline.Stage.SPLASH, priority = 20) {
            ran.add("good")
        }

        ExtensionBootPipeline.run(ExtensionBootPipeline.Stage.SPLASH, mutableMapOf())

        assertEquals(listOf("good"), ran)
    }

    @Test
    fun `只跑指定阶段`() {
        val ran = mutableListOf<String>()
        ExtensionBootPipeline.register("ext.a", ExtensionBootPipeline.Stage.AUTH) { ran.add("auth") }
        ExtensionBootPipeline.register("ext.b", ExtensionBootPipeline.Stage.SPLASH) { ran.add("splash") }

        ExtensionBootPipeline.run(ExtensionBootPipeline.Stage.AUTH, mutableMapOf())

        assertEquals(listOf("auth"), ran)
    }

    @Test
    fun `熔断后不执行任何 handler`() {
        var ran = false
        ExtensionBootPipeline.register("ext.a", ExtensionBootPipeline.Stage.SPLASH) { ran = true }
        ExtensionGate.latched = true

        ExtensionBootPipeline.run(ExtensionBootPipeline.Stage.SPLASH, mutableMapOf())

        assertEquals(false, ran)
    }

    @Test
    fun `unregisterAll 只清掉指定扩展`() {
        val ran = mutableListOf<String>()
        ExtensionBootPipeline.register("ext.a", ExtensionBootPipeline.Stage.SPLASH) { ran.add("a") }
        ExtensionBootPipeline.register("ext.b", ExtensionBootPipeline.Stage.SPLASH) { ran.add("b") }

        ExtensionBootPipeline.unregisterAll("ext.a")
        ExtensionBootPipeline.run(ExtensionBootPipeline.Stage.SPLASH, mutableMapOf())

        assertEquals(listOf("b"), ran)
    }
}
