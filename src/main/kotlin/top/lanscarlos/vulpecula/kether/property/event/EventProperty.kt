package top.lanscarlos.vulpecula.kether.property.event

import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.entity.EntityEvent
import org.bukkit.event.player.PlayerEvent
import taboolib.common.OpenResult
import top.lanscarlos.vulpecula.kether.VulKetherProperty
import top.lanscarlos.vulpecula.kether.VulScriptProperty
import top.lanscarlos.vulpecula.utils.toBoolean

/**
 * Vulpecula
 * top.lanscarlos.vulpecula.kether.property.event
 *
 * @author Lanscarlos
 * @since 2022-10-21 10:59
 */

@VulKetherProperty(
    id = "event",
    bind = Event::class,
)
class EventProperty : VulScriptProperty<Event>("event") {

    override fun read(instance: Event, key: String): OpenResult {
        val property: Any = when (key) {
            "entity" -> (instance as? EntityEvent)?.entity ?: OpenResult.failed()
            "player" -> (instance as? PlayerEvent)?.player ?: OpenResult.failed()
            "eventName", "event-name", "name" -> instance.eventName
            "isAsynchronous", "asynchronous", "async" -> instance.isAsynchronous
            "isCancelled", "cancelled", "cancel" -> (instance as? Cancellable)?.isCancelled ?: false
            else -> return OpenResult.failed()
        }
        return OpenResult.successful(property)
    }

    override fun write(instance: Event, key: String, value: Any?): OpenResult {
        when (key) {
            "isCancelled", "cancelled", "cancel" -> {
                val cancellable = instance as? Cancellable ?: return OpenResult.failed()
                cancellable.isCancelled = value?.toBoolean() ?: return OpenResult.successful()
            }
            else -> return OpenResult.failed()
        }
        return OpenResult.successful()
    }
}