package io.github.ozzz.personalagent

import android.os.Bundle
import android.os.Binder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlin.system.exitProcess

/** Instantiated by Shizuku in a separate shell/root process, not an Android Service. */
class AutomationUserService : IAutomationService.Stub() {
    private val deadline = RemoteDeadline(SystemClock::elapsedRealtime)
    private val watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor().apply {
        scheduleAtFixedRate({
            if (deadline.expired()) {
                Log.e("PersonalAgent", "REMOTE_DEADLINE_EXPIRED pid=${Process.myPid()}; terminating service")
                exitProcess(1)
            }
        }, 100, 100, java.util.concurrent.TimeUnit.MILLISECONDS)
    }
    // Never acquire the UserService monitor: a stalled UI read must not block the watchdog.
    override fun armDeadline(deadlineElapsedMs: Long) = deadline.arm(deadlineElapsedMs)

    @Synchronized
    override fun visitAndReturn(expectedPackage: String, x: Int, y: Int, visitPackage: String, title: String): Bundle {
        val started = SystemClock.elapsedRealtime()
        return try {
            require(LaunchProtocol.validPackage(expectedPackage) && LaunchProtocol.validPackage(visitPackage))
            require(expectedPackage != visitPackage && title.isNotBlank() && title.length <= 100)
            deadline.beginOperation(20_000)
            val clicked = tap(expectedPackage, x, y)
            check(clicked.getBoolean("success")) { clicked.getString("error").orEmpty() }
            var arrived = false
            for (attempt in 1..10) {
                SystemClock.sleep(500)
                val foreground = CommandRunner.run(listOf("/system/bin/dumpsys", "activity", "activities"), 1500, 256000)
                if (TapProtocol.isForeground(visitPackage, foreground)) { arrived = true; break }
            }
            check(arrived) { "未确认跳转到访问目标" }
            VisitPageWait.awaitTitle(title, read = {
                val identity = Binder.clearCallingIdentity()
                try { UiSnapshot.parse(UiHierarchyReader.read(visitPackage)) }
                finally { Binder.restoreCallingIdentity(identity) }
            }, pause = { SystemClock.sleep(it) }, active = {
                deadline.check()
                val current = CommandRunner.run(listOf("/system/bin/dumpsys", "activity", "activities"), 1500, 256000)
                check(TapProtocol.isForeground(visitPackage, current)) { "访问目标已离开前台，不抢占用户页面" }
            }, log = { Log.i("PersonalAgent", "VISIT_WAIT $it") })
            Log.i("PersonalAgent", "VISIT_CONFIRMED package=$visitPackage title=$title")
            SystemClock.sleep(1500)
            val foreground = CommandRunner.run(listOf("/system/bin/dumpsys", "activity", "activities"), 1500, 256000)
            check(TapProtocol.isForeground(visitPackage, foreground)) { "访问目标已离开前台，不抢占用户页面" }
            val launched = launchApp(expectedPackage)
            check(launched.getBoolean("success")) { "访问已确认，但返回原App失败" }
            Bundle().apply { putBoolean("success", true); putInt("exitCode", 0) }
        } catch (e: Exception) {
            Bundle().apply { putBoolean("success", false); putInt("exitCode", -1); putString("error", e.message) }
        } finally { deadline.endOperation() }
            .apply { putLong("elapsedMs", SystemClock.elapsedRealtime() - started) }
    }

    @Synchronized
    override fun captureScreen(expectedPackage: String): android.os.ParcelFileDescriptor {
        deadline.check()
        require(LaunchProtocol.validPackage(expectedPackage))
        val foreground = CommandRunner.run(listOf("/system/bin/dumpsys", "activity", "activities"), 3000, 256000)
        check(TapProtocol.isForeground(expectedPackage, foreground)) { "目标不在主屏前台，取消截图" }
        val file = java.io.File.createTempFile("diandao-screen-", ".png", java.io.File("/data/local/tmp"))
        try {
            val result = CommandRunner.run(listOf("/system/bin/screencap", "-p", file.absolutePath), 3000)
            check(!result.timedOut && result.exitCode == 0 && file.length() in 1..8_000_000) { "截图失败或超过大小限制" }
            return android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        } finally { file.delete() }
    }

    @Synchronized
    override fun swipe(expectedPackage: String, startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int): Bundle {
        val started = SystemClock.elapsedRealtime()
        var result: CommandRunner.Result? = null
        return try {
            val foreground = CommandRunner.run(listOf("/system/bin/dumpsys", "activity", "activities"), 3000, 256000)
            val command = SwipeProtocol.command(expectedPackage, startX, startY, endX, endY, durationMs, foreground)
            Log.i("PersonalAgent", "SWIPE_BEGIN package=$expectedPackage display=0 from=$startX,$startY to=$endX,$endY durationMs=$durationMs")
            deadline.check()
            result = CommandRunner.run(command, 4000)
            check(!result.timedOut && result.exitCode == 0 && result.stderr.isBlank() &&
                !result.stdout.contains("Error", ignoreCase = true)) { "滑动命令未确认成功" }
            Bundle().apply { putBoolean("success", true) }
        } catch (e: Exception) {
            Log.e("PersonalAgent", "SWIPE_FAILED", e)
            Bundle().apply { putBoolean("success", false); putString("error", e.message) }
        }.apply {
            putInt("exitCode", result?.exitCode ?: -1)
            putBoolean("timedOut", result?.timedOut ?: false)
            putString("stderr", result?.stderr.orEmpty())
            putLong("elapsedMs", SystemClock.elapsedRealtime() - started)
            Log.i("PersonalAgent", "SWIPE_RESULT success=${getBoolean("success")} exit=${getInt("exitCode")}")
        }
    }

    @Synchronized
    override fun back(expectedPackage: String): Bundle = navigationKey(expectedPackage, "KEYCODE_BACK")

    @Synchronized
    override fun returnHome(expectedPackage: String): Bundle = navigationKey(expectedPackage, "KEYCODE_HOME")

    private fun navigationKey(expectedPackage: String, key: String): Bundle {
        val started = SystemClock.elapsedRealtime()
        return try {
            deadline.check()
        require(LaunchProtocol.validPackage(expectedPackage))
            val foreground = CommandRunner.run(listOf("/system/bin/dumpsys", "activity", "activities"), 3000, 256000)
            check(TapProtocol.isForeground(expectedPackage, foreground)) { "目标已不在前台，未发送$key" }
            deadline.check()
            val result = CommandRunner.run(listOf("/system/bin/input", "-d", "0", "keyevent", key), 3000)
            check(!result.timedOut && result.exitCode == 0 && result.stderr.isBlank()) { "$key 命令失败" }
            Bundle().apply { putBoolean("success", true); putInt("exitCode", 0) }
        } catch (e: Exception) {
            Bundle().apply { putBoolean("success", false); putInt("exitCode", -1); putString("error", e.message) }
        }.apply { putLong("elapsedMs", SystemClock.elapsedRealtime() - started) }
    }

    @Synchronized
    override fun dumpUi(expectedPackage: String): String {
        deadline.check()
        require(LaunchProtocol.validPackage(expectedPackage))
        val identity = Binder.clearCallingIdentity()
        return try { UiHierarchyReader.read(expectedPackage) } finally { Binder.restoreCallingIdentity(identity) }
    }
    @Synchronized
    override fun tap(expectedPackage: String, x: Int, y: Int): Bundle {
        val started = SystemClock.elapsedRealtime()
        var command: CommandRunner.Result? = null
        return try {
            require(LaunchProtocol.validPackage(expectedPackage) && x >= 0 && y >= 0)
            val foreground = CommandRunner.run(listOf("/system/bin/dumpsys", "activity", "activities"), 3_000, 256_000)
            check(TapProtocol.isForeground(expectedPackage, foreground)) { "目标 App 未处于前台，取消点击" }
            Log.i("PersonalAgent", "TAP_BEGIN package=$expectedPackage display=0 x=$x y=$y uid=${Process.myUid()} pid=${Process.myPid()}")
            deadline.check()
            command = CommandRunner.run(listOf("/system/bin/input", "-d", "0", "tap", x.toString(), y.toString()), 3_000)
            val success = !command.timedOut && command.exitCode == 0 && command.stderr.isBlank() &&
                !command.stdout.contains("Error", ignoreCase = true)
            Bundle().apply {
                putBoolean("success", success)
                if (!success) putString("error", "点击命令未确认成功")
            }
        } catch (e: Exception) {
            Log.e("PersonalAgent", "TAP_FAILED", e)
            Bundle().apply {
                putBoolean("success", false)
                putString("error", "${e.javaClass.simpleName}: ${e.message}")
            }
        }.apply {
            putInt("exitCode", command?.exitCode ?: -1)
            putBoolean("timedOut", command?.timedOut ?: false)
            putString("stdout", command?.stdout.orEmpty())
            putString("stderr", command?.stderr.orEmpty())
            putLong("elapsedMs", SystemClock.elapsedRealtime() - started)
            Log.i("PersonalAgent", "TAP_RESULT success=${getBoolean("success")} exit=${getInt("exitCode")} elapsedMs=${getLong("elapsedMs")}")
        }
    }

    @Synchronized
    override fun launchApp(packageName: String): Bundle {
        val started = SystemClock.elapsedRealtime()
        var stage = "resolve"
        var command: CommandRunner.Result? = null
        return try {
            require(LaunchProtocol.validPackage(packageName)) { "Invalid package name" }
            Log.i("PersonalAgent", "LAUNCH_BEGIN package=$packageName uid=${Process.myUid()} pid=${Process.myPid()}")
            deadline.check()
            command = CommandRunner.run(listOf(
                "/system/bin/cmd", "package", "resolve-activity", "--brief", "--user", "current",
                "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-p", packageName,
            ), 5_000)
            val component = LaunchProtocol.component(packageName, command)
                ?: error("未找到可启动入口，或解析命令失败")
            stage = "start"
            Log.i("PersonalAgent", "LAUNCH_COMPONENT $component")
            deadline.check()
            command = CommandRunner.run(listOf(
                "/system/bin/am", "start", "-W", "--user", "current", "-n", component,
                "-f", "0x10000000",
            ), 8_000)
            val success = LaunchProtocol.succeeded(command)
            Bundle().apply {
                putBoolean("success", success)
                putString("component", component)
                if (!success) putString("error", "启动命令未确认成功，请检查输出")
            }
        } catch (e: Exception) {
            Log.e("PersonalAgent", "LAUNCH_FAILED stage=$stage", e)
            Bundle().apply {
                putBoolean("success", false)
                putString("error", "${e.javaClass.simpleName}: ${e.message}")
            }
        }.apply {
            putString("stage", stage)
            putInt("exitCode", command?.exitCode ?: -1)
            putBoolean("timedOut", command?.timedOut ?: false)
            putString("stdout", command?.stdout.orEmpty())
            putString("stderr", command?.stderr.orEmpty())
            putLong("elapsedMs", SystemClock.elapsedRealtime() - started)
            Log.i("PersonalAgent", "LAUNCH_RESULT success=${getBoolean("success")} stage=$stage exit=${getInt("exitCode")} elapsedMs=${getLong("elapsedMs")}")
        }
    }

    override fun getIdentity(): Bundle {
        val uid = Process.myUid()
        val pid = Process.myPid()
        Log.i("PersonalAgent", "SERVICE_IDENTITY uid=$uid pid=$pid")
        return Bundle().apply {
            putInt("uid", uid)
            putInt("pid", pid)
        }
    }

    override fun destroy() {
        watchdog.shutdownNow()
        Log.i("PersonalAgent", "SERVICE_DESTROY pid=${Process.myPid()}")
        exitProcess(0)
    }
}
