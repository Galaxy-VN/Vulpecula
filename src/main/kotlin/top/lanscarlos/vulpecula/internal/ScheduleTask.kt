package top.lanscarlos.vulpecula.internal

import taboolib.common.LifeCycle
import taboolib.common.platform.Awake
import taboolib.common.platform.function.*
import taboolib.common.platform.service.PlatformExecutor
import taboolib.library.configuration.ConfigurationSection
import taboolib.module.kether.Script
import taboolib.module.kether.ScriptContext
import taboolib.module.kether.parseKetherScript
import taboolib.module.lang.asLangText
import taboolib.module.lang.sendLang
import top.lanscarlos.vulpecula.config.VulConfig
import top.lanscarlos.vulpecula.utils.*
import top.lanscarlos.vulpecula.utils.Debug.debug
import java.io.File
import java.text.SimpleDateFormat
import java.time.Duration

/**
 * Vulpecula
 * top.lanscarlos.vulpecula.internal.schedule
 *
 * @author Lanscarlos
 * @since 2022-12-15 11:22
 */
class ScheduleTask(
    val id: String,
    val path: String, // 所在文件路径
    val wrapper: VulConfig
) {

    val dateFormat by wrapper.read("date-format") {
        SimpleDateFormat(it?.toString() ?: defDateFormat)
    }

    val async by wrapper.readBoolean("async", false)

    val startOf by wrapper.read("start") {
        if (it == null) return@read -1L
        try {
            dateFormat.parse(it.toString()).time
        } catch (ignored: Exception) {
            -1L
        }
    }

    val endOf by wrapper.read("end") {
        if (it == null) return@read -1L
        try {
            dateFormat.parse(it.toString()).time
        } catch (ignored: Exception) {
            -1L
        }
    }

    val duration by wrapper.read("period") {
        if (it == null) return@read Duration.ZERO
        val pattern = "\\d+[dhms]".toPattern()
        val matcher = pattern.matcher(it.toString())
        var seconds = 0L
        while (matcher.find()) {
            val found = matcher.group()
            val number = found.substring(0, found.lastIndex).toLongOrNull() ?: 0
            when (found.last().uppercaseChar()) {
                'D' -> seconds += Duration.ofDays(number).seconds
                'H' -> seconds += number * 3600
                'M' -> seconds += number * 60
                'S' -> seconds += number
            }
        }
        Duration.ofSeconds(seconds)
    }

    val namespace by wrapper.readStringList("namespace", listOf("vulpecula"))

    val executable by wrapper.read("execute") { value ->
        val def = "print *\"${console().asLangText("Schedule-Execution-Undefined", id)}\""
        val body = when (value) {
            is String -> listOf(value)
            is Array<*> -> value.mapNotNull { it?.toString() }
            is Collection<*> -> value.mapNotNull { it?.toString() }
            else -> listOf(def)
        }.joinToString(separator = "\n")

        return@read "def main = { $body }"
    }

    var script: Script? = null
    private var task: PlatformExecutor.PlatformTask? = null

    init {
        script = try {
            executable.parseKetherScript(namespace)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 对照并尝试更新
     * */
    fun contrast(section: ConfigurationSection) {
        var refresh = false // 是否更新脚本
        var restart = false // 是否重启任务

        wrapper.updateSource(section).forEach {
            when (it.first) {
                "namespace", "execute" -> {
                    refresh = true
                }
                "async", "start", "end", "duration" -> {
                    restart = true
                }
            }
        }

        if (refresh) script = executable.parseKetherScript(namespace)
        if (restart) run()

        if (refresh || restart) {
            debug(Debug.HIGH, "ScheduleTask updated \"$id\"")
        }
    }

    /**
     * 开始任务
     * */
    fun run() {
        // 取消上一次未结束的任务
        terminate()

        val now = System.currentTimeMillis()

        // 已超过结束时间
        if (now >= endOf) {
            debug("ScheduleTask $id has completed. {now=${dateFormat.format(now)}, end-of=${dateFormat.format(endOf)}}")
            return
        }

        val period = if (!duration.isZero) duration.toMillis() else 0L
        val delay = if (now < startOf) {
            // 未达到开始时间
            startOf - now
        } else {
            // 已达到开始时间
            if (!duration.isZero) {
                /* 循环任务 */
                // 获取过去的循环次数 + 1
                val times = (now - startOf) / period + 1
                // 计算与下一次任务的间隔时间
                (startOf + times * period) - now
            } else {
                /*
                * 非循环任务
                * 直接开始
                * */
                0L
            }
        }

        debug("ScheduleTask $id ready to run. {async=$async, delay=${delay/50L}, period=${period/50L}, start-of=${dateFormat.format(startOf)}}")

        // 开始新的任务
        task = submit(async = async, delay = delay / 50L + 10, period = period / 50L) {
            if (System.currentTimeMillis() >= endOf) {
                debug("ScheduleTask $id has completed. {end-of=${dateFormat.format(endOf)}}")
                terminate()
                return@submit
            }

            debug("ScheduleTask $id running...")

            script?.let {
                ScriptContext.create(it).runActions()
            } ?: console().sendLang("Schedule-Execution-Undefined", id)
        }
    }

    /**
     * 终止任务
     * */
    fun terminate() {
        task?.cancel()
        task = null
    }

    companion object {

        val defDateFormat by bindConfigNode("schedule-setting.date-format") {
            it?.toString() ?: "yyyy-MM-dd HH:mm:ss"
        }

        val folder = File(getDataFolder(), "schedules")
        val cache = mutableMapOf<String, ScheduleTask>()

        fun get(id: String) = cache[id]

        @Awake(LifeCycle.ACTIVE)
        fun onActive() {
            cache.values.forEach { it.run() }
        }

        private fun onFileChanged(file: File) {
            val start = timing()
            try {
                var counter = 0
                val path = file.canonicalPath
                val config = file.toConfig()
                val keys = config.getKeys(false).toMutableSet()

                // 遍历已存在的任务
                val iterator = cache.iterator()
                while (iterator.hasNext()) {
                    val task = iterator.next().value
                    if (task.path != path) continue

                    if (task.id in keys) {
                        // 任务仍然存在于文件中，尝试更新任务属性
                        config.getConfigurationSection(task.id)?.let { section ->
                            if (section.getBoolean("disable", false)) return@let null

                            debug(Debug.HIGH, "ScheduleTask contrasting \"${task.id}\"")
                            task.contrast(section)
                            counter += 1
                        } ?: let {
                            // 节点寻找失败，删除任务
                            task.terminate()
                            iterator.remove()
                            debug(Debug.HIGH, "ScheduleTask delete \"${task.id}\"")
                        }

                        // 移除该 id
                        keys -= task.id
                    } else {
                        // 该任务已被用户删除
                        task.terminate()
                        iterator.remove()
                        debug(Debug.HIGH, "ScheduleTask delete \"${task.id}\"")
                    }
                }

                // 遍历新的任务
                for (key in keys) {

                    // 检查 id 冲突
                    if (key in cache) {
                        val conflict = cache[key]!!
                        console().sendLang("Schedule-Load-Failed-Conflict", key, conflict.path, path)
                        continue
                    }

                    config.getConfigurationSection(key)?.let { section ->
                        if (section.getBoolean("disable", false)) return@let

                        cache[key] = ScheduleTask(key, path, section.wrapper())
                        counter += 1
                        debug(Debug.HIGH, "ScheduleTask loaded \"$key\"")
                    }
                }

                console().sendLang("Schedule-Load-Automatic-Succeeded", file.name, counter, timing(start))
            } catch (e: Exception) {
                console().sendLang("Schedule-Load-Automatic-Failed", file.name, e.localizedMessage, timing(start))
            }
        }

        fun load(): String {
            val start = timing()
            return try {

                // 暂停所有任务
                if (cache.isNotEmpty()) {
                    cache.values.forEach { it.terminate() }
                }

                // 清除缓存
                cache.clear()

                folder.ifNotExists {
                    listOf(
                        "def.yml"
                    ).forEach { releaseResourceFile("schedules/$it", true) }
                }.getFiles().forEach { file ->

                    val path = file.canonicalPath

                    // 添加文件监听器
                    file.addWatcher(false) { onFileChanged(this) }

                    // 加载文件
                    file.toConfig().forEachSection { key, section ->
                        if (section.getBoolean("disable", false)) return@forEachSection

                        // 检查 id 冲突
                        if (key in cache) {
                            val conflict = cache[key]!!
                            console().sendLang("Schedule-Load-Failed-Conflict", key, conflict.path, path)
                            return@forEachSection
                        }

                        cache[key] = ScheduleTask(key, path, section.wrapper())
                        debug(Debug.HIGH, "ScheduleTask loaded \"$key\"")
                    }
                }

                console().asLangText("Schedule-Load-Succeeded", cache.size, timing(start)).also {
                    console().sendMessage(it)
                }
            } catch (e: Exception) {
                console().asLangText("Schedule-Load-Failed", e.localizedMessage, timing(start)).also {
                    console().sendMessage(it)
                }
            }
        }
    }
}