package top.lanscarlos.vulpecula.kether.action

import taboolib.common.platform.function.console
import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.releaseResourceFile
import taboolib.module.kether.actionNow
import taboolib.module.kether.scriptParser
import taboolib.module.lang.asLangText
import taboolib.module.lang.sendLang
import top.lanscarlos.vulpecula.kether.VulKetherParser
import top.lanscarlos.vulpecula.kether.live.StringLiveData
import top.lanscarlos.vulpecula.utils.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.text.StringBuilder

/**
 * Vulpecula
 * top.lanscarlos.vulpecula.kether.action
 *
 * @author Lanscarlos
 * @since 2022-11-13 16:16
 */
object ActionUnicode {

    private val file by lazy { File(getDataFolder(), "actions/unicode-mapping.yml") }

    private val mapping by lazy { ConcurrentHashMap<String, String>() }

    private fun onFileChange(file: File) {
        try {
            val start = timing()

            mapping.clear()

            file.toConfig().forEachLine { key, value ->
                mapping[key] = value
            }

            console().sendLang("Unicode-Mapping-Load-Succeeded", mapping.size, timing(start))
        } catch (e: Exception) {
            e.printStackTrace()
            console().sendLang("Unicode-Mapping-Load-Failed", e.localizedMessage)
        }
    }

    fun load(): String {
        return try {
            val start = timing()

            mapping.clear()

            file.ifNotExists {
                releaseResourceFile("actions/unicode-mapping.yml", true)
            }.addWatcher {
                onFileChange(this)
            }.toConfig().forEachLine { key, value ->
                mapping[key] = value
            }

            console().asLangText("Unicode-Mapping-Load-Succeeded", mapping.size, timing(start)).also {
                console().sendMessage(it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            console().asLangText("Unicode-Mapping-Load-Failed", e.localizedMessage).also {
                console().sendMessage(it)
            }
        }
    }

    fun String.replaceUnicode(keyword: Char = '$', prefix: Char = '{', suffix: Char = '}'): String {
        val content = this.toCharArray()
        var index = 0
        val builder = StringBuilder()
        while (index < content.size) {

            if (content[index] == keyword) {
                // 检测到起始符
                val key = StringBuilder()
                index++ // 跳过起始符 "$"
                if (index >= content.size) continue // 已检索到字符串末尾
                if (content[index] == prefix) {
                    // 含有拓展符 "{"
                    index++ // 跳过拓展符 "{"
                    while (index < content.size && content[index] != suffix) {
                        // 依次加入变量名缓存
                        key.append(content[index])
                        index++
                    }
                    index++ // 跳过最后的终止符 "}"
                } else {
                    if (Character.isDigit(content[index])) {
                        // 变量名以数字开头，不合法，跳过处理
                        builder.append('$')
                        builder.append(content[index])
                        continue
                    }
                    // 依次将字母或数字加入变量名缓存
                    while (index < content.size && isLetterOrDigit(content[index])) {
                        key.append(content[index])
                        index++
                    }
                }

                // 查询 unicode 映射
                val unicode = mapping[key.toString()]
                if (unicode != null) {
                    // 查询成功
                    builder.append(unicode)
                } else {
                    // 查询失败
                    builder.append('$')
                    builder.append('{')
                    builder.append(key.toString())
                    builder.append('}')
                }
            } else {
                // 非起始符
                builder.append(content[index++])
            }
        }
        return builder.toString()
    }

    fun isLetterOrDigit(char: Char): Boolean {
        val uppercase = 1 shl Character.UPPERCASE_LETTER.toInt()
        val lowercase = 1 shl Character.LOWERCASE_LETTER.toInt()
        val digit = 1 shl Character.DECIMAL_DIGIT_NUMBER.toInt()
        return ((( (uppercase or lowercase) or digit ) shr Character.getType(char.code)) and 1) != 0
    }

    @VulKetherParser(
        id = "unicode",
        name = ["unicode"]
    )
    fun parser() = scriptParser { reader ->
        val source = StringLiveData(reader.nextBlock())
        actionNow { source.getOrNull(this)?.replaceUnicode() }
    }

}