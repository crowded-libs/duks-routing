package duks.routing

import androidx.compose.runtime.Composable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RouterSerializationTest {
    
    @Serializable
    data class TestParam(
        val id: String,
        val name: String
    )
    
    // Use default Json - no special configuration needed since we don't serialize parameters
    private val json = Json
    
    @Test
    fun `RouterState serialization should preserve non-parameterized routes`() {
        // Since we can't serialize parameters, all SerializableRouteInstances have null params
        val originalState = RouterState(
            sceneRoutes = listOf(
                SerializableRouteInstance("/home"),
                SerializableRouteInstance("/settings")
            ),
            contentRoutes = listOf(
                SerializableRouteInstance("/dashboard"),
                SerializableRouteInstance("/profile") // No parameters
            ),
            modalRoutes = listOf(
                SerializableRouteInstance("/alert")
            ),
            lastRouteType = RouteType.Content
        )
        
        // Serialize to JSON
        val jsonString = json.encodeToString(originalState)
        
        // Deserialize
        val deserializedState = json.decodeFromString<RouterState>(jsonString)
        
        // Verify structure is preserved
        assertEquals(2, deserializedState.sceneRoutes.size)
        assertEquals(2, deserializedState.contentRoutes.size)
        assertEquals(1, deserializedState.modalRoutes.size)
        assertEquals(RouteType.Content, deserializedState.lastRouteType)
        
        // Verify paths
        assertEquals("/home", deserializedState.sceneRoutes[0].path)
        assertEquals("/settings", deserializedState.sceneRoutes[1].path)
        assertEquals("/dashboard", deserializedState.contentRoutes[0].path)
        assertEquals("/profile", deserializedState.contentRoutes[1].path)
        assertEquals("/alert", deserializedState.modalRoutes[0].path)
        
        // Verify all params are null
        deserializedState.sceneRoutes.forEach { assertNull(it.param) }
        deserializedState.contentRoutes.forEach { assertNull(it.param) }
        deserializedState.modalRoutes.forEach { assertNull(it.param) }
    }
    
    @Test
    fun `RouterState serialization should handle routes with null params only`() {
        // Test the actual filtering behavior - routes with params are filtered during serialization
        // We need to create a state with actual RouteInstances that have params
        // For this test, we'll simulate what the middleware would have
        
        // Since we can't directly create RouteInstances with params in tests,
        // let's just verify that SerializableRouteInstance always has null params
        val routes = listOf(
            createSerializableRouteInstance("/home"),
            createSerializableRouteInstance("/profile", TestParam("123", "Test")),
            createSerializableRouteInstance("/settings", "ignored param"),
            createSerializableRouteInstance("/about")
        )
        
        // All routes should have null params regardless of what we passed
        routes.forEach { assertNull(it.param) }
        
        val originalState = RouterState(
            contentRoutes = routes
        )
        
        // Serialize to JSON
        val jsonString = json.encodeToString(originalState)
        
        // Deserialize
        val deserializedState = json.decodeFromString<RouterState>(jsonString)
        
        // All routes should be present since they all have null params
        assertEquals(4, deserializedState.contentRoutes.size)
        assertEquals("/home", deserializedState.contentRoutes[0].path)
        assertEquals("/profile", deserializedState.contentRoutes[1].path)
        assertEquals("/settings", deserializedState.contentRoutes[2].path)
        assertEquals("/about", deserializedState.contentRoutes[3].path)
    }
    
    @Test
    fun `empty RouterState should serialize and deserialize correctly`() {
        val emptyState = RouterState()
        
        // Serialize
        val jsonString = json.encodeToString(emptyState)
        
        // Deserialize
        val deserializedState = json.decodeFromString<RouterState>(jsonString)
        
        // Verify empty state
        assertEquals(0, deserializedState.sceneRoutes.size)
        assertEquals(0, deserializedState.contentRoutes.size)
        assertEquals(0, deserializedState.modalRoutes.size)
        assertEquals(null, deserializedState.lastRouteType)
    }
    
    @Test
    fun `RouteInstanceListSerializer should filter out parameterized routes`() {
        // Create mock route instances with and without parameters
        class MockParameterizedRoute(override val path: String, override val param: Any?) : RouteInstance {
            @Composable
            override fun Content() { }
        }
        
        val routesWithMixedParams = listOf(
            MockParameterizedRoute("/home", null),
            MockParameterizedRoute("/profile", TestParam("123", "Test")),
            MockParameterizedRoute("/settings", "some param"),
            MockParameterizedRoute("/about", null)
        )
        
        // Test the serializer directly
        val serializer = RouteInstanceListSerializer
        val jsonString = json.encodeToString(serializer, routesWithMixedParams)
        
        // The serializer should filter out routes with params
        val deserializedRoutes = json.decodeFromString(serializer, jsonString)
        
        // Only routes without params should be serialized
        assertEquals(2, deserializedRoutes.size)
        assertEquals("/home", deserializedRoutes[0].path)
        assertEquals("/about", deserializedRoutes[1].path)
        
        // All deserialized routes should have null params
        deserializedRoutes.forEach { assertNull(it.param) }
    }
}