package io.github.ozzz.personalagent

/** Keywords are supplied by the task; the executor knows no business-specific page. */
internal object CompletionWait {
    fun confirm(markers: List<String>, read: () -> String, pause: (Long) -> Unit,
                active: () -> Unit): String {
        require(markers.isNotEmpty() && markers.size <= 4 && markers.all { it.isNotBlank() && it.length <= 100 })
        repeat(8) { attempt ->
            active()
            val xml = read()
            active()
            val page = UiSnapshot.parse(xml)
            if (markers.all { page.findExact(it) != null }) return xml
            if (attempt < 7) pause(750)
        }
        error("返回页面未确认全部完成标记，不发送HOME")
    }
}
