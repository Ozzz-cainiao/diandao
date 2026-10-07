package io.github.ozzz.personalagent

/** Observed arrival, shopping-list and video tasks; unknown offers are never clicked. */
object TaobaoQuickTask {
    private const val PACKAGE = "com.taobao.taobao"
    internal fun allDone(page: UiSnapshot): Boolean = page.findExact("今日速赚") != null &&
        page.findExact("今日快速赚奖励已拿完") != null
    /** The reward badge can expose '已得' and '30' as separate adjacent nodes. */
    internal fun browseRewardEarned(page: UiSnapshot): Boolean {
        if (page.findExact("已得30") != null || page.findExact("已得 30") != null) return true
        return page.nodes.filter { it.usable && it.named("已得") }.any { label ->
            val height = label.bottom - label.top
            page.nodes.any { amount ->
                amount.usable && amount.named("30") && amount.x > label.x &&
                    amount.left >= label.right - height / 2 && amount.left <= label.right + height &&
                    amount.top < label.bottom && amount.bottom > label.top
            }
        }
    }
    internal fun reward(page: UiSnapshot, title: String, amount: String): UiNode? {
        val label = page.findExact(title) ?: return null
        return page.nodes.filter { it.usable && it.named(amount) && it.left > label.right &&
            it.top <= label.bottom && it.bottom >= label.top }.singleOrNull()
    }

    internal fun videoReward(page: UiSnapshot): UiNode? {
        val candidates = page.nodes.filter { label ->
            label.usable && (label.named("好物沉浸看") ||
                (label.text.startsWith("看看#") && page.nodes.any { subtitle ->
                    subtitle.usable && subtitle.named("浏览15秒") && subtitle.left == label.left &&
                        subtitle.top >= label.bottom && subtitle.top <= label.bottom + 100 }))
        }.mapNotNull { reward(page, it.text, "+30") }
        return candidates.singleOrNull()
    }

    fun runAndReturnHome(runtime: AutomationRuntime, record: (String) -> Unit = {}): String {
        val result = run(runtime, record)
        runtime.log("[快速赚] $result；准备返回桌面")
        try { runtime.returnHome(PACKAGE) }
        catch (e: Exception) { throw IllegalStateException("$result；但返回桌面失败：${e.message}", e) }
        return "$result；已发送返回桌面指令"
    }

    /** Swipe only inside the observed product-video page, away from purchase controls. */
    internal fun browseViewport(page: UiSnapshot): UiNode {
        val root = checkNotNull(page.nodes.firstOrNull { it.usable && it.left == 0 && it.top == 0 }) {
            "未确认屏幕边界，停止滑动"
        }
        val videoContent = page.nodes.any { it.usable && (it.named("图片，按钮。双击可进入详情页。") ||
                it.named("视频，按钮。双击可暂停或播放视频。")) &&
            it.left <= root.right * 0.46 && it.right >= root.right * 0.46 &&
            it.top <= root.bottom * 0.36 && it.bottom >= root.bottom * 0.58 }
        check(videoContent || (page.findExact("加入购物车") != null && page.findExact("立即购买") != null)) {
            "浏览页面已变化，停止滑动"
        }
        val badge = page.nodes.filter { it.usable && it.left >= root.right * 0.7 &&
            it.top > root.bottom * 0.2 && it.bottom < root.bottom * 0.8 }
        check(badge.any { it.text.contains("浏览") || it.description.contains("浏览") } &&
            badge.any { it.named("30") || it.named("得30") || it.named("得 30") }) {
            "未确认右侧浏览奖励计时，停止滑动"
        }
        return root
    }

    internal fun browse(runtime: AutomationRuntime, read: () -> UiSnapshot): String {
        // Ten bounded gestures; the client also enforces the overall task timeout.
        repeat(11) { step ->
            val page = read()
            if (browseRewardEarned(page)) {
                runtime.log("[快速赚] 右侧奖励确认已得30，停止滑动")
                return "好物沉浸看页面确认：已得30"
            }
            check(step < 10) { "浏览已达10次滑动上限，未确认奖励，停止任务" }
            val screen = browseViewport(page)
            runtime.log("[快速赚] 浏览滑动 ${step + 1}/10")
            runtime.swipe(PACKAGE, screen.right * 46 / 100, screen.bottom * 58 / 100,
                screen.right * 46 / 100, screen.bottom * 36 / 100, 650)
            runtime.pause(2000)
        }
        error("未确认浏览结果")
    }

    fun run(runtime: AutomationRuntime, record: (String) -> Unit = {}): String {
        fun read(): UiSnapshot = TaobaoPageReader.read(runtime, record)
        fun tap(node: UiNode) = runtime.tap(PACKAGE, node.x, node.y)
        runtime.log("[快速赚] 进入淘金币")
        runtime.launch(PACKAGE)
        runtime.pause(2500)
        var initial = read()
        if (browseRewardEarned(initial)) {
            runtime.log("[快速赚] 恢复已得30页面，返回面板核对")
            runtime.back(PACKAGE)
            runtime.pause(1000)
            initial = read()
            check(initial.findExact("今日速赚") != null) { "浏览奖励恢复后未回到面板" }
        }
        if (TaobaoListTask.finalRewardShown(initial)) {
            runtime.log("[清单] 恢复已完成页面：累计60，返回面板核对")
            runtime.back(PACKAGE)
            runtime.pause(1000)
            var recovered = read()
            for (attempt in 1..4) {
                if (recovered.findExact("今日速赚") != null && TaobaoListTask.progress(recovered) == 2) break
                runtime.pause(750)
                recovered = read()
            }
            check(recovered.findExact("今日速赚") != null && TaobaoListTask.progress(recovered) == 2) {
                "清单恢复后未确认面板2/2，停止"
            }
            return "已恢复清单完成状态：累计60，面板2/2确认；本次未重复领取"
        }
        val signIn = TaobaoCoinTask.run(runtime, record, initial)
        runtime.log("[签到结果] $signIn")
        fun entry(page: UiSnapshot) = page.findExact("40秒快速赚") ?: page.findExact("快速赚")
        var targetEntry = entry(read())
        for (attempt in 1..5) {
            if (targetEntry != null) break
            runtime.log("[快速赚] 等待签到动画结束和入口加载 $attempt/5")
            runtime.pause(1000)
            targetEntry = entry(read())
        }
        checkNotNull(targetEntry) { "签到已确认，但等待后仍未找到快速赚入口" }
        tap(targetEntry)
        runtime.pause(1500)
        var page = read()
        check(page.findExact("今日速赚") != null) { "未确认快速赚任务面板" }
        if (allDone(page)) return "淘宝确认：今日快速赚奖励已拿完，无需重复执行"
        var arrival = false
        reward(page, "任务到访得金币", "+10")?.let {
            runtime.log("[快速赚] 领取到访奖励一次")
            tap(it)
            runtime.pause(1500)
            page = read()
            check(page.findExact("今日速赚") != null &&
                page.findExact("任务到访得金币每日来任务面板") != null &&
                reward(page, "任务到访得金币", "+10") == null) { "到访领奖结果未确认，停止" }
            arrival = true
        }
        val (afterLists, listCount) = TaobaoListTask.run(runtime, page, ::read)
        page = afterLists
        var quiz = false
        reward(page, "淘金币趣味课堂", "+30")?.let {
            tap(it)
            runtime.pause(1500)
            page = TaobaoQuizTask.run(runtime, ::read)
            quiz = true
        }
        val summary = (if (quiz) "课堂领奖已确认；" else "") + "本次清单完成${listCount}轮；" + if (arrival) "到访任务已完成；" else ""
        val video = videoReward(page)
        var videoResult = ""
        if (video != null) {
            runtime.log("[快速赚] 进入好物沉浸看，分段滑动并检查奖励（最多10次）")
            tap(video)
            runtime.pause(1500)
            videoResult = browse(runtime, ::read) + "；"
            runtime.back(PACKAGE)
            runtime.pause(1000)
            page = read()
            check(page.findExact("今日速赚") != null) { "视频奖励已确认，但未回到任务面板，停止" }
        }
        (reward(page, "搜一搜你心仪的宝贝", "+30")
            ?: reward(page, "发现精选好物", "+30"))?.let {
            tap(it)
            runtime.pause(1000)
            page = TaobaoSearchTask.run(runtime, ::read)
        }
        reward(page, "去蚂蚁庄园逛逛哟", "+50")?.let {
            runtime.log("[庄园] 从任务入口访问一次")
            page = TaobaoFarmTask.run(runtime, it, ::read)
        }
        return summary + videoResult + if (allDone(page)) "淘宝确认：今日快速赚奖励已拿完" else
            "当前无其他支持的待领奖任务；未知任务未执行"
    }
}
