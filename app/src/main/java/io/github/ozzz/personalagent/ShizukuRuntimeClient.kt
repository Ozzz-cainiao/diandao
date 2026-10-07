package io.github.ozzz.personalagent

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** All mutable connection state and UI callbacks live on the main thread. */
class ShizukuRuntimeClient(
    context: Context,
    private val onReport: (String) -> Unit,
    private val busyChanged: (Boolean) -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private var diagnostics: TaskDiagnostics? = null
    private var pageTimeout: Runnable? = null
    private fun report(message: String) {
        diagnostics?.log(message)
        onReport(message)
    }
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val args = Shizuku.UserServiceArgs(
        ComponentName(context.applicationContext, AutomationUserService::class.java)
    ).daemon(false).tag("automation-runtime").processNameSuffix("runtime")
        .debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)
    @Volatile private var closed = false
    private var busy = false
    private var activeConnection: ServiceConnection? = null
    @Volatile private var generation = 0
    private var remote: IAutomationService? = null
    private var requestedPackage: String? = null
    data class TapRequest(val x: Int, val y: Int, val waitMs: Long)
    private var requestedTap: TapRequest? = null
    private var pendingTap: Runnable? = null
    private var requestedTask: ((AutomationRuntime) -> String)? = null
    private var taskFuture: Future<*>? = null

    private val timeout = Runnable {
        if (!closed && busy) {
            diagnostics?.status("总超时，已停止；截图为此前最后成功采集，不保证是超时瞬间")
            disconnect()
            report("[失败] 操作总超时，已停止。打开运行记录查看最后页面与截图。")
        }
    }

    private fun newConnection() = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (closed || activeConnection !== this) return
            remote = IAutomationService.Stub.asInterface(binder)
            report("[服务] Binder 已连接，读取远端身份…")
            readIdentity()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            if (closed || activeConnection !== this) return
            generation++
            remote = null
            finish()
            report("[断开] UserService 已断开，可重新测试。")
        }
    }

    private val received = Shizuku.OnBinderReceivedListener {
        if (!closed) report("[连接] Shizuku Binder 已就绪。")
    }
    private val dead = Shizuku.OnBinderDeadListener {
        if (!closed) {
            generation++
            remote = null
            finish()
            report("[断开] Shizuku 服务已停止，请启动 Shizuku 后重试。")
        }
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { code, grant ->
        if (!closed && code == REQUEST_CODE && busy) {
            if (grant == PackageManager.PERMISSION_GRANTED) {
                report("[授权] 已允许。")
                bind()
            } else {
                finish()
                report("[授权] 未允许；未启动特权服务。")
            }
        }
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(received, main)
        Shizuku.addBinderDeadListener(dead, main)
        Shizuku.addRequestPermissionResultListener(permissionResult, main)
    }

    fun testConnection() = start(null)

    fun runTask(task: (AutomationRuntime) -> String) = start(null, task = task)

    fun launchApp(packageName: String) = start(packageName)

    fun launchAndTap(packageName: String, tap: TapRequest) {
        require(tap.x >= 0 && tap.y >= 0 && tap.waitMs in 0..10_000)
        start(packageName, tap)
    }

    private fun start(packageName: String?, tap: TapRequest? = null, task: ((AutomationRuntime) -> String)? = null) {
        if (closed || busy) return
        diagnostics = if (task != null) TaskDiagnostics(appContext) else null
        requestedPackage = packageName
        requestedTap = tap
        requestedTask = task
        busy = true
        busyChanged(true)
        report("[本机] App uid=${Process.myUid()} pid=${Process.myPid()}")
        try {
            check(Shizuku.pingBinder()) { "Shizuku 未连接，请确认已启动。" }
            check(Shizuku.getVersion() >= 13) { "本版本需要 Shizuku 13 或更新版本。" }
            report("[连接] Shizuku API=${Shizuku.getVersion()}，服务 uid=${Shizuku.getUid()}")
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                bind()
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                finish()
                report("[授权] 请在 Shizuku 的应用管理中允许 点到，然后重试。")
            } else {
                report("[授权] 请在手机弹窗中选择允许。")
                // Human authorization is not subject to the service's 15-second timeout.
                Shizuku.requestPermission(REQUEST_CODE)
            }
        } catch (e: Exception) {
            fail("连接检查", e)
        }
    }

    private fun bind() {
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, 15_000)
        if (remote?.asBinder()?.isBinderAlive == true) {
            readIdentity()
            return
        }
        try {
            releaseService()
            val connection = newConnection()
            activeConnection = connection
            report("[服务] 正在绑定 UserService…")
            Shizuku.bindUserService(args, connection)
        } catch (e: Exception) {
            fail("绑定服务", e)
        }
    }

    private fun readIdentity() {
        val service = remote ?: return
        val attempt = ++generation
        worker.execute {
            val result = runCatching {
                val identity = service.identity
                val uid = identity.getInt("uid", -1)
                val pid = identity.getInt("pid", -1)
                check(uid == 2000 || uid == 0) { "远端不是 shell/root：uid=$uid" }
                check(pid > 0 && pid != Process.myPid()) { "远端 PID 无效：$pid" }
                "[通过] UserService uid=$uid pid=$pid（${if (uid == 2000) "shell" else "root"}）"
            }
            main.post {
                if (!closed && generation == attempt) {
                    result.onSuccess {
                        report(it)
                        val packageName = requestedPackage
                        val task = requestedTask
                        if (task != null) executeTask(service, task)
                        else if (packageName == null) finish() else launchRemote(service, packageName)
                    }
                        .onFailure { fail("读取身份", it) }
                }
            }
        }
    }

    private fun executeTask(service: IAutomationService, task: (AutomationRuntime) -> String) {
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, 120_000)
        val attempt = ++generation
        val evidence = diagnostics
        val budget = PageBudget(android.os.SystemClock::elapsedRealtime)
        fun armPageTimeout() {
            main.post {
                if (!closed && generation == attempt && busy) {
                    pageTimeout?.let(main::removeCallbacks)
                    pageTimeout = Runnable {
                        if (!closed && generation == attempt && busy) {
                            if (budget.remaining() > 0) {
                                armPageTimeout()
                                return@Runnable
                            }
                            evidence?.status("单页60秒超时：${budget.page}；已停止，保留最后成功采集的页面与截图")
                            disconnect()
                            report("[任务失败] 单页60秒超时：${budget.page}；已停止，见运行记录")
                        }
                    }.also { main.postDelayed(it, budget.remaining()) }
                }
            }
        }
        armPageTimeout()
        taskFuture = worker.submit {
            fun checkActive() {
                check(!closed && generation == attempt && !Thread.currentThread().isInterrupted) { "任务已取消" }
                budget.check()
            }
            var lastPackage: String? = null
            fun capture(reason: String) {
                val target = lastPackage ?: return
                if (closed || generation != attempt) return
                runCatching { evidence?.screenshot(service.captureScreen(target), reason) }
                    .onFailure { evidence?.log("[截图不可用] $reason：${it.message}；原有截图不代表当前现场") }
            }
            val runtime = object : AutomationRuntime {
                override fun log(message: String) {
                    checkActive()
                    main.post { if (!closed && generation == attempt) report(message) }
                }
                private fun command(name: String, call: () -> android.os.Bundle) {
                    checkActive()
                    val value = call()
                    checkActive()
                    log("[$name] exit=${value.getInt("exitCode")} 耗时=${value.getLong("elapsedMs")}ms")
                    check(value.getBoolean("success")) { "$name 失败：${value.getString("error")}; ${value.getString("stderr")}" }
                }
                override fun visitAndReturn(packageName: String, x: Int, y: Int, visitPackage: String, title: String) =
                    command("访问 $visitPackage 并返回") { service.visitAndReturn(packageName, x, y, visitPackage, title) }
                override fun back(expectedPackage: String) = command("返回任务面板") { service.back(expectedPackage) }
                override fun returnHome(expectedPackage: String) {
                    checkActive()
                    capture("任务结束前")
                    command("返回桌面") { service.returnHome(expectedPackage) }
                }
                override fun launch(packageName: String) = command("启动") { service.launchApp(packageName) }
                override fun tap(packageName: String, x: Int, y: Int) = command("点击 $x,$y") { service.tap(packageName, x, y) }
                override fun swipe(packageName: String, startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int) =
                    command("滑动 $startX,$startY → $endX,$endY") {
                        service.swipe(packageName, startX, startY, endX, endY, durationMs)
                    }
                override fun readUi(packageName: String): String {
                    checkActive()
                    val started = android.os.SystemClock.elapsedRealtime()
                    val xml = service.dumpUi(packageName)
                    checkActive()
                    val key = PageBudget.key(packageName, UiSnapshot.parse(xml))
                    val changed = budget.observe(key)
                    lastPackage = packageName
                    evidence?.page(xml, key)
                    log("[页面] $key，读取${android.os.SystemClock.elapsedRealtime() - started}ms，剩余${budget.remaining() / 1000}秒")
                    if (changed) {
                        armPageTimeout()
                        capture("进入 $key")
                    }
                    checkActive()
                    return xml
                }
                override fun pause(milliseconds: Long) { checkActive(); Thread.sleep(milliseconds); checkActive() }
            }
            val result = runCatching { task(runtime) }
            if (result.isFailure && !Thread.currentThread().isInterrupted && generation == attempt) {
                evidence?.log("[异常堆栈] ${result.exceptionOrNull()?.stackTraceToString()}")
                capture("任务失败现场（页面文字可能来自上次读取）")
            }
            main.post {
                if (!closed && generation == attempt) {
                    taskFuture = null
                    evidence?.status(if (result.isSuccess) "已完成：${result.getOrNull()}" else "失败：${result.exceptionOrNull()?.message}")
                    finish()
                    result.onSuccess { report("[任务结果] $it") }
                        .onFailure { Log.e("PersonalAgent", "TASK_FAILED", it); report("[任务失败] ${it.message}") }
                }
            }
        }
    }

    private fun launchRemote(service: IAutomationService, packageName: String) {
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, 20_000)
        val attempt = ++generation
        report("[启动] package=$packageName，正在通过 UserService 启动…")
        worker.execute {
            val result = runCatching { service.launchApp(packageName) }
            main.post {
                if (!closed && generation == attempt) {
                    result.onSuccess { value ->
                        report("[启动] stage=${value.getString("stage")} exit=${value.getInt("exitCode")} timeout=${value.getBoolean("timedOut")} 耗时=${value.getLong("elapsedMs")}ms")
                        for (key in listOf("stdout", "stderr")) {
                            value.getString(key)?.takeIf { it.isNotBlank() }?.let { report("[$key] $it") }
                        }
                        if (value.getBoolean("success")) {
                            report("[启动完成] ${value.getString("component")}；请在手机确认页面。")
                            val tap = requestedTap
                            if (tap == null) finish() else scheduleTap(service, packageName, tap)
                        } else {
                            finish()
                            report("[启动失败] ${value.getString("error")}")
                        }
                    }.onFailure { fail("启动应用", it) }
                }
            }
        }
    }

    private fun scheduleTap(service: IAutomationService, packageName: String, tap: TapRequest) {
        val attempt = ++generation
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, tap.waitMs + 12_000)
        report("[等待] ${tap.waitMs}ms，然后点击 (${tap.x}, ${tap.y}) 一次。")
        pendingTap = Runnable {
            pendingTap = null
            if (closed || generation != attempt) return@Runnable
            report("[点击] 检查目标 App 前台状态，准备注入。")
            worker.execute {
                val result = runCatching { service.tap(packageName, tap.x, tap.y) }
                main.post {
                    if (!closed && generation == attempt) {
                        result.onSuccess { value ->
                            finish()
                            report("[点击] exit=${value.getInt("exitCode")} timeout=${value.getBoolean("timedOut")} 耗时=${value.getLong("elapsedMs")}ms")
                            for (key in listOf("stdout", "stderr")) {
                                value.getString(key)?.takeIf { it.isNotBlank() }?.let { report("[$key] $it") }
                            }
                            if (value.getBoolean("success")) report("[流程完成] 已执行一次点击命令，请确认页面效果。")
                            else report("[点击失败] ${value.getString("error")}")
                        }.onFailure { fail("点击", it) }
                    }
                }
            }
        }.also { main.postDelayed(it, tap.waitMs) }
    }

    fun disconnect() {
        if (busy) diagnostics?.stopIfRunning("已取消或连接中断；保留最后成功采集的页面与截图")
        generation++
        taskFuture?.cancel(true)
        taskFuture = null
        remote = null
        releaseService()
        finish()
        if (!closed) report("[服务] 已释放本 App 的连接。")
    }

    private fun releaseService() {
        activeConnection?.let { connection ->
            activeConnection = null
            runCatching { Shizuku.unbindUserService(args, connection, true) }
                .onFailure { Log.w("PersonalAgent", "UNBIND_FAILED", it) }
            // Also clear local SDK callbacks, including when the server has already died.
            runCatching { Shizuku.unbindUserService(args, connection, false) }
                .onFailure { Log.w("PersonalAgent", "DETACH_FAILED", it) }
        }
    }

    private fun fail(step: String, error: Throwable) {
        diagnostics?.status("失败：$step：${error.message}")
        Log.e("PersonalAgent", "FAILED step=$step", error)
        disconnect()
        report("[失败] $step：${error.javaClass.simpleName}: ${error.message}")
    }

    private fun finish() {
        diagnostics?.stopIfRunning("任务已停止；具体原因见步骤日志")
        pageTimeout?.let(main::removeCallbacks)
        pageTimeout = null
        main.removeCallbacks(timeout)
        pendingTap?.let(main::removeCallbacks)
        pendingTap = null
        busy = false
        requestedPackage = null
        requestedTap = null
        requestedTask = null
        if (!closed) busyChanged(false)
    }

    override fun close() {
        closed = true
        Shizuku.removeBinderReceivedListener(received)
        Shizuku.removeBinderDeadListener(dead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        disconnect()
        worker.shutdownNow()
    }

    companion object { private const val REQUEST_CODE = 1001 }
}
