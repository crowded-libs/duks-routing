package duks.routing

import kotlinx.serialization.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*

/**
 * Custom serializer for `List<RouteInstance>`.
 *
 * Serializes each instance as [SerializableRouteInstance]. Params are encoded via
 * [RouteParamRegistry.Default] when a codec is registered; unencodable params are
 * dropped while the path is retained so the stack can still restore.
 */
object RouteInstanceListSerializer : KSerializer<List<RouteInstance>> {
    private val elementSerializer = SerializableRouteInstance.serializer()
    override val descriptor: SerialDescriptor = ListSerializer(elementSerializer).descriptor

    override fun serialize(encoder: Encoder, value: List<RouteInstance>) {
        val wire = value.map { it.toSerializable(RouteParamRegistry.Default) }
        ListSerializer(elementSerializer).serialize(encoder, wire)
    }
    
    override fun deserialize(decoder: Decoder): List<RouteInstance> {
        val wire = ListSerializer(elementSerializer).deserialize(decoder)
        return wire.map { it.withDecodedParam(RouteParamRegistry.Default) }
    }
}
