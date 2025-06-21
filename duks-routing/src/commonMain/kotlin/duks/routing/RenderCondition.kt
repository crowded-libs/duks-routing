package duks.routing

// Render conditions with Kotlin operators
sealed class RenderCondition {
    data class ScreenSize(val minWidth: Int? = null, val maxWidth: Int? = null) : RenderCondition()
    data class Orientation(val orientation: ScreenOrientation) : RenderCondition()
    data class DeviceType(val types: Set<DeviceClass>) : RenderCondition()
    data class Custom(val check: (DeviceContext) -> Boolean) : RenderCondition()
    data class FeatureEnabled(val featureName: String) : RenderCondition()

    internal data class Composite(
        val operator: CompositeOperator,
        val conditions: List<RenderCondition>
    ) : RenderCondition()

    // Kotlin operators
    infix fun and(other: RenderCondition): RenderCondition =
        when {
            this is Composite && this.operator == CompositeOperator.AND ->
                Composite(CompositeOperator.AND, conditions + other)
            other is Composite && other.operator == CompositeOperator.AND ->
                Composite(CompositeOperator.AND, listOf(this) + other.conditions)
            else ->
                Composite(CompositeOperator.AND, listOf(this, other))
        }

    infix fun or(other: RenderCondition): RenderCondition =
        when {
            this is Composite && this.operator == CompositeOperator.OR ->
                Composite(CompositeOperator.OR, conditions + other)
            other is Composite && other.operator == CompositeOperator.OR ->
                Composite(CompositeOperator.OR, listOf(this) + other.conditions)
            else ->
                Composite(CompositeOperator.OR, listOf(this, other))
        }
}

internal enum class CompositeOperator {
    AND, OR
}