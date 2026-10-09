package io.github.ozzz.personalagent

/** Boot-monotonic deadlines shared by Binder workers and the independent service watchdog. */
internal class RemoteDeadline(private val now: () -> Long) {
    private var taskUntil = 0L
    private var operationUntil = 0L
    @Synchronized fun expired(): Boolean {
        val time = now()
        return (taskUntil != 0L && time >= taskUntil) || (operationUntil != 0L && time >= operationUntil)
    }
    @Synchronized fun check() { check(!expired()) { "远端执行截止，停止操作" } }
    @Synchronized fun arm(until: Long) {
        check()
        require(until > now() && until - now() <= 120_000) { "执行期限无效" }
        taskUntil = until
    }
    @Synchronized fun beginOperation(milliseconds: Long) {
        check()
        require(milliseconds in 1..20_000)
        operationUntil = now() + milliseconds
    }
    @Synchronized fun endOperation() {
        // Finally blocks must never erase an already expired operation before the watchdog sees it.
        if (expired()) taskUntil = now().coerceAtLeast(1)
        operationUntil = 0
    }
}
