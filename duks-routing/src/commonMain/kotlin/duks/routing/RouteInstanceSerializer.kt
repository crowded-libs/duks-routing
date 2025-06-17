package duks.routing

import kotlinx.serialization.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*

/**
 * Custom serializer for List<RouteInstance>
 */
object RouteInstanceListSerializer : KSerializer<List<RouteInstance>> {
    private val elementSerializer = SerializableRouteInstance.serializer()
    override val descriptor: SerialDescriptor = ListSerializer(elementSerializer).descriptor

    override fun serialize(encoder: Encoder, value: List<RouteInstance>) {
        // Only serialize routes without parameters
        val routesWithoutParams = value.filter { it.param == null }
        ListSerializer(elementSerializer).serialize(encoder,
            routesWithoutParams.map { x -> x.toSerializable() })
    }
    
    override fun deserialize(decoder: Decoder): List<RouteInstance> {
        return ListSerializer(SerializableRouteInstance.serializer()).deserialize(decoder)
    }
}