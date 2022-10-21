package top.lanscarlos.vulpecula.kether.property.event

import org.bukkit.event.entity.EntityDeathEvent
import taboolib.common.OpenResult
import top.lanscarlos.vulpecula.kether.VulKetherProperty
import top.lanscarlos.vulpecula.kether.VulScriptProperty
import top.lanscarlos.vulpecula.utils.toDouble
import top.lanscarlos.vulpecula.utils.toInt

/**
 * Vulpecula
 * top.lanscarlos.vulpecula.kether.property.event
 *
 * @author Lanscarlos
 * @since 2022-10-21 11:45
 */

@VulKetherProperty(
    id = "entity-death-event",
    bind = EntityDeathEvent::class,
)
class EntityDeathEventProperty : VulScriptProperty<EntityDeathEvent>("entity-death-event") {

    override fun read(instance: EntityDeathEvent, key: String): OpenResult {
        val property: Any? = when (key) {
            "droppedExp", "dropped-exp", "exp" -> instance.droppedExp
            "drops" -> instance.drops
            "cause" -> instance.entity.lastDamageCause?.cause?.name
            else -> return OpenResult.failed()
        }
        return OpenResult.successful(property)
    }

    override fun write(instance: EntityDeathEvent, key: String, value: Any?): OpenResult {
        when (key) {
            "droppedExp", "dropped-exp", "exp" -> {
                instance.droppedExp = value?.toInt() ?: return OpenResult.successful()
            }
            else -> return OpenResult.failed()
        }
        return OpenResult.successful()
    }
}