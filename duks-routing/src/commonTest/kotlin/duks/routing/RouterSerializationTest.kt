package duks.routing

import androidx.compose.runtime.Composable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RouterSerializationTest {
    
    @Serializable
    data class TestParam(
        val id: String,
        val name: String
    )
    
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    
    @Test
    fun `RouterState serialization should preserve non-parameterized routes`() {
        val originalState = RouterState(
            sceneRoutes = listOf(
                SerializableRouteInstance("/home"),
                SerializableRouteInstance("/settings")
            ),
            contentRoutes = listOf(
                SerializableRouteInstance("/dashboard"),
                SerializableRouteInstance("/profile")
            ),
            modalRoutes = listOf(
                SerializableRouteInstance("/alert")
            ),
            lastRouteType = RouteType.Content
        )
        
        val jsonString = json.encodeToString(originalState)
        val deserializedState = json.decodeFromString<RouterState>(jsonString)
        
        assertEquals(2, deserializedState.sceneRoutes.size)
        assertEquals(2, deserializedState.contentRoutes.size)
        assertEquals(1, deserializedState.modalRoutes.size)
        assertEquals(RouteType.Content, deserializedState.lastRouteType)
        
        assertEquals("/home", deserializedState.sceneRoutes[0].path)
        assertEquals("/settings", deserializedState.sceneRoutes[1].path)
        assertEquals("/dashboard", deserializedState.contentRoutes[0].path)
        assertEquals("/profile", deserializedState.contentRoutes[1].path)
        assertEquals("/alert", deserializedState.modalRoutes[0].path)
        
        deserializedState.sceneRoutes.forEach { assertNull(it.param) }
        deserializedState.contentRoutes.forEach { assertNull(it.param) }
        deserializedState.modalRoutes.forEach { assertNull(it.param) }
    }

    @Test
    fun `RouterState serialization round-trips String params`() {
        val withParam = createSerializableRouteInstance("/item", param = "abc-123")
        val state = RouterState(contentRoutes = listOf(withParam))

        val decoded = json.decodeFromString<RouterState>(json.encodeToString(state))
        assertEquals(1, decoded.contentRoutes.size)
        assertEquals("/item", decoded.contentRoutes[0].path)
        assertEquals("abc-123", decoded.contentRoutes[0].param)
    }

    @Test
    fun `RouterState serialization round-trips registered custom params`() {
        RouteParamRegistry.Default.register<TestParam>("test.TestParam")
        val param = TestParam("1", "Widget")
        val withParam = createSerializableRouteInstance("/detail", param = param)
        val state = RouterState(contentRoutes = listOf(withParam))

        val decoded = json.decodeFromString<RouterState>(json.encodeToString(state))
        assertEquals(param, decoded.contentRoutes.single().param)
    }

    @Test
    fun `unregistered params drop payload but keep path`() {
        data class Opaque(val x: Int)
        val routes = listOf(
            object : RouteInstance {
                override val path = "/home"
                override val param: Any? = null
                @Composable override fun Content() {}
            },
            object : RouteInstance {
                override val path = "/opaque"
                override val param: Any? = Opaque(42)
                @Composable override fun Content() {}
            }
        )

        val jsonString = json.encodeToString(RouteInstanceListSerializer, routes)
        val deserialized = json.decodeFromString(RouteInstanceListSerializer, jsonString)

        assertEquals(2, deserialized.size)
        assertEquals("/home", deserialized[0].path)
        assertNull(deserialized[0].param)
        assertEquals("/opaque", deserialized[1].path)
        assertNull(deserialized[1].param)
    }
    
    @Test
    fun `empty RouterState should serialize and deserialize correctly`() {
        val emptyState = RouterState()
        val deserializedState = json.decodeFromString<RouterState>(json.encodeToString(emptyState))
        
        assertEquals(0, deserializedState.sceneRoutes.size)
        assertEquals(0, deserializedState.contentRoutes.size)
        assertEquals(0, deserializedState.modalRoutes.size)
        assertEquals(null, deserializedState.lastRouteType)
    }
}
