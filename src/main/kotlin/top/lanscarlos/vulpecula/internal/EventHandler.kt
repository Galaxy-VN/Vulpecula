package top.lanscarlos.vulpecula.internal

import taboolib.common.io.digest
import taboolib.common.platform.function.console
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.releaseResourceFile
import taboolib.common5.cbool
import taboolib.library.configuration.ConfigurationSection
import taboolib.library.kether.Quest
import taboolib.module.kether.printKetherErrorMessage
import taboolib.module.lang.asLangText
import taboolib.module.lang.sendLang
import top.lanscarlos.vulpecula.utils.config.VulConfig
import top.lanscarlos.vulpecula.utils.*
import top.lanscarlos.vulpecula.utils.Debug.debug
import java.io.File

/**
 * Vulpecula
 * top.lanscarlos.vulpecula.internal
 *
 * @author Lanscarlos
 * @since 2022-09-03 14:34
 */
class EventHandler(
    val id: String,
    val path: String,
    val wrapper: VulConfig
) : ScriptCompiler {

    val hash = id.digest("md5")
    val hashName = "handler_$hash"
    val binding by wrapper.readStringList("bind")
    val priority by wrapper.readInt("priority", 8)

    val namespace by wrapper.readStringList("namespace")

    val condition by wrapper.read("condition") {
        if (it != null) buildSection(it) else StringBuilder()
    }
    val deny by wrapper.read("deny") {
        if (it != null) buildSection(it) else StringBuilder()
    }
    val handle by wrapper.read("handle") {
        if (it != null) buildSection(it) else StringBuilder("null")
    }
    val exception by wrapper.read("exception") {
        if (it != null) buildException(it) else emptyList()
    }

    val dispatchers = mutableSetOf<EventDispatcher>()

    /* 不包含主方法的方法体 */
    lateinit var source: StringBuilder

    /* 与方法体对应的语句块 */
    lateinit var scriptBlocks: Map<String, Quest.Block>

    init {
        // 编译脚本
        compileScript()

        // 绑定调度器
        for (id in binding) {
            val dispatcher = EventDispatcher.get(id)
            if (dispatcher == null) {
                console().sendLang("Handler-Load-Dispatcher-Not-Found", this.id, id)
                continue
            }

            bind(dispatcher)
        }
    }

    override fun buildSource(): StringBuilder {
        val builder = StringBuilder()

        /* 构建核心语句 */
        builder.append(handle.toString())

        /* 构建异常处理 */
        if (exception.isNotEmpty() && (exception.size > 1 || exception.first().second.isNotEmpty())) {
            // 提取先前所有内容
            val content = builder.extract()
            compileException(builder, content, exception)
        }

        /* 构建条件处理 */
        if (condition.isNotEmpty()) {
            // 提取先前所有内容
            val content = builder.extract()
            compileCondition(builder, content, condition, deny)
        }

        /*
        * 收尾
        * 构建方法体
        * def handler_$hash = {
        *   ...$content
        * }
        * */

        // 提取先前所有内容
        val content = builder.extract()

        // 构建方法体
        builder.append("def $hashName = {\n")
        builder.appendWithIndent(content, suffix = "\n")
        builder.append("}")

        /* 消除注释 */
        eraseComment(builder)

        return builder
    }

    override fun compileScript() {
        try {
            // 尝试构建脚本
            val source = buildSource()
            val quest = source.toString().toKetherScript(namespace)

            // 编译通过
            this.source = source
            this.scriptBlocks = quest.blocks

            debug(Debug.HIGHEST, "handler \"$id\" build source:\n$source")
        } catch (e: Exception) {
            e.printKetherErrorMessage()
        }
    }

    /**
     * 绑定调度器
     * @param dispatcher 调度器
     * */
    fun bind(dispatcher: EventDispatcher) {
        if (dispatcher in dispatchers) return

        dispatchers += dispatcher
        dispatcher.addHandler(this)
    }

    /**
     * 解绑调度器
     * @param dispatcher 调度器
     * */
    fun unbind(dispatcher: EventDispatcher) {
        dispatchers -= dispatcher
        console().sendLang("Handler-Load-Dispatcher-Unbind", this.id, id)
    }

    /**
     * 重新绑定调度模块
     * @param reorder 是否令已绑定的调度模块重新排序
     * */
    fun rebind(reorder: Boolean) {

        // 检查已绑定的调度器
        val iterator = dispatchers.iterator()
        while (iterator.hasNext()) {
            val dispatcher = iterator.next()
            if (dispatcher.id in binding) {
                // 仍然处于绑定状态
                if (reorder) dispatcher.compileScript()
            } else {
                // 解绑
                dispatcher.removeHandler(this)
                // 重新编译脚本
                dispatcher.compileScript()
                // 移出队列
                iterator.remove()
            }
        }

        // 加载未绑定的调度器
        val existing = dispatchers.map { it.id }
        for (id in binding) {
            if (id in existing) continue

            val dispatcher = EventDispatcher.get(id)
            if (dispatcher == null) {
                console().sendLang("Handler-Load-Dispatcher-Not-Found", this.id, id)
                continue
            }

            // 绑定调度器
            bind(dispatcher)
            if (reorder) dispatcher.compileScript()
        }
    }

    /**
     * 解绑所有调度模块
     * */
    fun unbindAll() {
        dispatchers.forEach {
            it.removeHandler(this)
            it.compileScript()
        }
        dispatchers.clear()
    }

    /**
     * 对照并尝试更新
     * */
    fun contrast(section: ConfigurationSection) {
        var rebind = false
        var reorder = false
        var recompile = false

        wrapper.updateSource(section).forEach {
            when (it.first) {
                "binding" -> {
                    rebind = true
                    reorder = true
                }
                "priority" -> reorder = true
                "namespace", "condition", "deny", "handle", "exception" -> recompile = true
            }
        }

        if (recompile) compileScript()
        if (rebind) rebind(reorder)
    }

    override fun toString(): String {
        return "EventHandler(id='$id')"
    }

    companion object {

        val automaticReload by bindConfigNode("automatic-reload.handler") {
            it?.cbool ?: false
        }

        val folder = File(getDataFolder(), "handlers")
        val cache = mutableMapOf<String, EventHandler>()

        fun get(id: String): EventHandler? = cache[id]

        fun getAll(): Collection<EventHandler> = cache.values

        private fun onFileChanged(file: File) {
            if (!automaticReload) {
                file.removeWatcher()
                return
            }

            val start = timing()
            try {

                var counter = 0
                val path = file.canonicalPath
                val config = file.toConfig()
                val keys = config.getKeys(false).toMutableSet()

                // 遍历已存在的处理器
                val iterator = cache.iterator()
                while (iterator.hasNext()) {
                    val handler = iterator.next().value
                    if (handler.path != path) continue

                    if (handler.id in keys) {
                        // 处理器仍然存在于文件中，尝试更新处理器属性
                        config.getConfigurationSection(handler.id)?.let { section ->
                            if (section.getBoolean("disable", false)) return@let null

                            debug(Debug.HIGH, "Handler contrasting \"${handler.id}\"")
                            handler.contrast(section)
                            counter += 1
                        } ?: let {
                            // 节点寻找失败，删除处理器
                            handler.unbindAll()
                            iterator.remove()
                            debug(Debug.HIGH, "Handler delete \"${handler.id}\"")
                        }

                        // 移除该 id
                        keys -= handler.id
                    } else {
                        // 该处理器已被用户删除
                        handler.unbindAll()
                        iterator.remove()
                        debug(Debug.HIGH, "Handler delete \"${handler.id}\"")
                    }
                }

                // 遍历新的调度器
                for (key in keys) {

                    // 检查 id 冲突
                    if (key in cache) {
                        val conflict = cache[key]!!
                        console().sendLang("Handler-Load-Failed-Conflict", key, conflict.path, path)
                        continue
                    }

                    config.getConfigurationSection(key)?.let { section ->
                        if (section.getBoolean("disable", false)) return@let

                        cache[key] = EventHandler(key, path, section.wrapper()).apply {
                            this.dispatchers.forEach { it.compileScript() }
                        }

                        counter += 1
                        debug(Debug.HIGH, "Handler loaded \"$key\"")
                    }
                }

                console().sendLang("Handler-Load-Automatic-Succeeded", file.name, counter, timing(start))
            } catch (e: Exception) {
                console().sendLang("Handler-Load-Automatic-Failed", file.name, e.localizedMessage, timing(start))
            }
        }

        fun load(): String {
            val start = timing()
            return try {

                // 清除缓存
                cache.clear()

                folder.ifNotExists {
                    releaseResourceFile("handlers/#def.yml", true)
                }.getFiles().forEach { file ->

                    val path = file.canonicalPath

                    // 添加文件监听器
                    if (automaticReload) {
                        file.addWatcher(false) { onFileChanged(this) }
                    }

                    // 加载文件
                    file.toConfig().forEachSection { key, section ->
                        if (section.getBoolean("disable", false)) return@forEachSection

                        // 检查 id 冲突
                        if (key in cache) {
                            val conflict = cache[key]!!
                            console().sendLang("Handler-Load-Failed-Conflict", key, conflict.path, path)
                            return@forEachSection
                        }

                        cache[key] = EventHandler(key, path, section.wrapper())
                        debug(Debug.HIGH, "Handler loaded \"$key\"")
                    }
                }

                console().asLangText("Handler-Load-Succeeded", cache.size, timing(start)).also {
                    console().sendMessage(it)
                }
            } catch (e: Exception) {
                console().asLangText("Handler-Load-Failed", e.localizedMessage, timing(start)).also {
                    console().sendMessage(it)
                }
            }
        }
    }
}