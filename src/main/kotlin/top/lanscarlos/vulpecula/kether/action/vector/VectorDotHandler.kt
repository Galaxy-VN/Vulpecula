package top.lanscarlos.vulpecula.kether.action.vector

import taboolib.common.util.Vector
import taboolib.library.kether.QuestReader
import top.lanscarlos.vulpecula.kether.live.VectorLiveData
import top.lanscarlos.vulpecula.utils.nextBlock

/**
 * Vulpecula
 * top.lanscarlos.vulpecula.kether.action.vector
 *
 * @author Lanscarlos
 * @since 2022-11-14 18:56
 */
object VectorDotHandler : ActionVector.Reader {

    override val name: Array<String> = arrayOf("dot")

    override fun read(reader: QuestReader, input: String, isRoot: Boolean): ActionVector.Handler {
        val source = if (isRoot) VectorLiveData(reader.nextBlock()) else null
        val other = reader.expectVector("with", "using")

        return acceptHandler(source) { vector ->
            vector.dot(other.get(this, Vector()))
        }
    }
}