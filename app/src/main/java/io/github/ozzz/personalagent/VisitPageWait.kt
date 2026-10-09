package io.github.ozzz.personalagent

/** No actions in the visited App. Only bounded reads, including its transient loading tree. */
internal object VisitPageWait {
    fun awaitTitle(title: String, read: () -> UiSnapshot, pause: (Long) -> Unit,
                   active: () -> Unit, log: (String) -> Unit) {
        repeat(8) { attempt ->
            active()
            val page = read()
            active()
            if (page.findExact(title) != null) return
            log("访问目标加载 ${attempt + 1}/8；尚未确认标题")
            if (attempt < 7) pause(750)
        }
        error("等待访问页面标题超时，不操作其他页面")
    }
}
