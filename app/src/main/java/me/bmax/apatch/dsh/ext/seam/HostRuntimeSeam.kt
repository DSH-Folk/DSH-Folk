package me.bmax.apatch.dsh.ext.seam

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import me.bmax.apatch.dsh.DshPhase
import me.bmax.apatch.dsh.DshRuntime
import me.bmax.apatch.dsh.ext.ExtLog

/**
 * [RuntimeSeam] 的宿主实现。
 *
 * ## 为什么必须存在（而不是继续走反射）
 *
 * 以前扩展通过 [AppExtensionPlatform] 反射 `DshRuntime`。但 `DshRuntime` 在
 * `me.bmax.apatch.dsh` 包里，proguard 只 `-keep` 了 `me.bmax.apatch.dsh.ext.**` ——
 * release 包里方法名被混淆，反射按名字找不到。
 *
 * 表现很隐蔽：`onLoad` 成功、扩展显示"已启用"，但一调能力就失败，日志里看不出原因。
 *
 * 这个类是**编译期绑定**：R8 看得见调用关系，混淆不影响。
 */
internal class HostRuntimeSeam : RuntimeSeam {

    private val main = Handler(Looper.getMainLooper())

    override fun state(): RuntimeState = DshRuntime.state.value.toSeam()

    override fun observe(handler: (RuntimeState) -> Unit): () -> Unit {
        // 每个订阅者一个 scope：取消订阅时整条链一起停，不泄漏。
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        DshRuntime.state
            .onEach { s ->
                // 回调里抛异常不能把整条流打断（一个扩展写错不该让别的扩展收不到状态）。
                runCatching { handler(s.toSeam()) }.onFailure {
                    ExtLog.log("RuntimeSeam.observe 回调抛异常（已忽略）：" + (it.message ?: it.javaClass.simpleName))
                }
            }
            .launchIn(scope)
        return {
            scope.cancel()
        }
    }

    override fun exec(bashCommand: String): Process = DshRuntime.execRootfs(bashCommand)

    override fun execCapture(bashCommand: String, timeoutMs: Long): String =
        DshRuntime.execRootfsForOutput(bashCommand, timeoutMs)

    override fun execStreaming(
        bashCommand: String,
        timeoutMs: Long,
        onLine: (String) -> Unit,
    ): String = DshRuntime.execRootfsStreaming(bashCommand, timeoutMs, onLine)

    override fun log(message: String) {
        ExtLog.log(message)
    }
}

/** 宿主 `DshState` → seam 的 `RuntimeState`。只做投影，不改语义。 */
private fun me.bmax.apatch.dsh.DshState.toSeam() = RuntimeState(
    installed = phase != DshPhase.NOT_READY,
    running = phase == DshPhase.RUNNING,
    installing = phase == DshPhase.DOWNLOADING || phase == DshPhase.EXTRACTING,
    progress = progress,
    message = message,
)
