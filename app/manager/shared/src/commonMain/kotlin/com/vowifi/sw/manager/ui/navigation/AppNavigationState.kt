package com.vowifi.sw.manager.ui.navigation

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class AppNavigationState private constructor(
    val backStack: List<AppRoute>,
) {
    val currentRoot: AppRoute.Root
        get() = backStack.first() as AppRoute.Root

    val topRoute: AppRoute
        get() = backStack.last()

    val bottomBarVisible: Boolean
        get() = topRoute is AppRoute.Root

    fun save(): String = NavigationStateCodec.encode(this)

    override fun equals(other: Any?): Boolean =
        other is AppNavigationState && backStack == other.backStack

    override fun hashCode(): Int = backStack.hashCode()

    override fun toString(): String = "AppNavigationState(backStack=$backStack)"

    companion object {
        fun initial(root: AppRoute.Root = AppRoute.Home): AppNavigationState = AppNavigationState(listOf(root))

        fun restore(
            serialized: String,
            current: AppNavigationState,
        ): NavigationRestoreResult = NavigationStateCodec.decode(serialized, current)

        internal fun create(backStack: List<AppRoute>): AppNavigationState? {
            val root = backStack.firstOrNull() as? AppRoute.Root ?: return null
            val validSubpages = backStack.drop(1).all { route ->
                route is AppRoute.Subpage && route.root == root
            }
            return if (validSubpages) AppNavigationState(backStack.toList()) else null
        }
    }
}

enum class NavigationRestoreError {
    MALFORMED_STATE,
}

sealed interface NavigationRestoreResult {
    data class Success(val state: AppNavigationState) : NavigationRestoreResult

    data class Failure(
        val state: AppNavigationState,
        val error: NavigationRestoreError,
    ) : NavigationRestoreResult
}

enum class RouteDecodeError {
    MALFORMED_ROUTE,
}

sealed interface RouteDecodeResult {
    data class Success(val route: AppRoute) : RouteDecodeResult

    data class Failure(val error: RouteDecodeError) : RouteDecodeResult
}

object AppRouteCodec {
    fun encode(route: AppRoute): String = navigationJson.encodeToString(AppRoute.serializer(), route)

    fun decode(serialized: String): RouteDecodeResult = try {
        RouteDecodeResult.Success(navigationJson.decodeFromString(AppRoute.serializer(), serialized))
    } catch (_: SerializationException) {
        RouteDecodeResult.Failure(RouteDecodeError.MALFORMED_ROUTE)
    } catch (_: IllegalArgumentException) {
        RouteDecodeResult.Failure(RouteDecodeError.MALFORMED_ROUTE)
    }
}

@Serializable
private data class NavigationSnapshot(val backStack: List<AppRoute>)

private object NavigationStateCodec {
    fun encode(state: AppNavigationState): String = navigationJson.encodeToString(
        NavigationSnapshot.serializer(),
        NavigationSnapshot(state.backStack),
    )

    fun decode(serialized: String, current: AppNavigationState): NavigationRestoreResult {
        val snapshot = try {
            navigationJson.decodeFromString(NavigationSnapshot.serializer(), serialized)
        } catch (_: SerializationException) {
            return NavigationRestoreResult.Failure(current, NavigationRestoreError.MALFORMED_STATE)
        } catch (_: IllegalArgumentException) {
            return NavigationRestoreResult.Failure(current, NavigationRestoreError.MALFORMED_STATE)
        }
        val restored = AppNavigationState.create(snapshot.backStack)
            ?: return NavigationRestoreResult.Failure(current, NavigationRestoreError.MALFORMED_STATE)
        return NavigationRestoreResult.Success(restored)
    }
}

private val navigationJson = Json {
    classDiscriminator = "type"
    encodeDefaults = true
}
