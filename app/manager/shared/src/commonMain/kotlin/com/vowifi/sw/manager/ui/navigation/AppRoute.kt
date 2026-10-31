package com.vowifi.sw.manager.ui.navigation

import androidx.navigation3.runtime.NavKey
import com.vowifi.sw.manager.ui.AppDestination
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface AppRoute : NavKey {
    sealed interface Root : AppRoute

    sealed interface Subpage : AppRoute

    @Serializable
    @SerialName("Home")
    data object Home : Root

    @Serializable
    @SerialName("Calls")
    data object Calls : Root

    @Serializable
    @SerialName("Messages")
    data object Messages : Root

    @Serializable
    @SerialName("Settings")
    data object Settings : Root

    @Serializable
    @SerialName("MessageThread")
    data class MessageThread(val conversation: ConversationHandle) : Subpage {
        override fun toString(): String = "MessageThread"
    }

    @Serializable
    @SerialName("NewMessage")
    data object NewMessage : Subpage

    @Serializable
    @SerialName("Dialpad")
    data object Dialpad : Subpage

    @Serializable
    @SerialName("CallHistoryDetail")
    data class CallHistoryDetail(val history: CallHistoryHandle) : Subpage {
        override fun toString() = "CallHistoryDetail"
    }

    @Serializable
    @SerialName("SettingsAppearance")
    data object SettingsAppearance : Subpage

    @Serializable
    @SerialName("SettingsConfiguration")
    data object SettingsConfiguration : Subpage

    /** One configured SIM's editor; the handle is the process-local opaque instance id (`sim-N`), never an identity. */
    @Serializable
    @SerialName("SettingsConfigurationSim")
    data class SettingsConfigurationSim(val sim: ConfigurationSimHandle) : Subpage {
        override fun toString(): String = "SettingsConfigurationSim"
    }

    @Serializable
    @SerialName("SettingsDiagnostics")
    data object SettingsDiagnostics : Subpage

    @Serializable
    @SerialName("SettingsLogs")
    data object SettingsLogs : Subpage

    @Serializable
    @SerialName("SettingsCallNotifications")
    data object SettingsCallNotifications : Subpage

    @Serializable
    @SerialName("SettingsAbout")
    data object SettingsAbout : Subpage

    @Serializable
    @SerialName("SettingsEngineInformation")
    data object SettingsEngineInformation : Subpage

    companion object {
        val rootRoutes: List<Root> = listOf(Home, Calls, Messages, Settings)
    }
}

@Serializable
@JvmInline
value class ConversationHandle private constructor(val value: String) {
    init {
        require(value.matches(OPAQUE_HANDLE))
    }

    override fun toString(): String = "ConversationHandle"

    companion object {
        private val OPAQUE_HANDLE = Regex("conversation-[A-Za-z0-9_-]{1,51}")

        internal fun fromOpaque(value: String): ConversationHandle = ConversationHandle(value)
        fun parse(value: String): ConversationHandle? = value.takeIf(OPAQUE_HANDLE::matches)?.let(::ConversationHandle)
    }
}

@Serializable
@JvmInline
value class CallHistoryHandle private constructor(val value: String) {
    init { require(value.matches(Regex("call-history-[A-Za-z0-9_-]{43}"))) }
    override fun toString() = "CallHistoryHandle"
    companion object {
        internal fun fromOpaque(value: String) = CallHistoryHandle(value)
    }
}

@Serializable
@JvmInline
value class ConfigurationSimHandle private constructor(val value: String) {
    init { require(value.matches(OPAQUE_INSTANCE)) }

    override fun toString(): String = "ConfigurationSimHandle"

    companion object {
        private val OPAQUE_INSTANCE = Regex("[A-Za-z0-9_-]{1,40}")

        fun parse(value: String): ConfigurationSimHandle? = value.takeIf(OPAQUE_INSTANCE::matches)?.let(::ConfigurationSimHandle)
    }
}

val AppRoute.Root.destination: AppDestination
    get() = when (this) {
        AppRoute.Home -> AppDestination.HOME
        AppRoute.Calls -> AppDestination.CALLS
        AppRoute.Messages -> AppDestination.MESSAGES
        AppRoute.Settings -> AppDestination.SETTINGS
    }

val AppDestination.rootRoute: AppRoute.Root
    get() = when (this) {
        AppDestination.HOME -> AppRoute.Home
        AppDestination.CALLS -> AppRoute.Calls
        AppDestination.MESSAGES -> AppRoute.Messages
        AppDestination.SETTINGS -> AppRoute.Settings
    }

internal val AppRoute.Subpage.root: AppRoute.Root
    get() = when (this) {
        is AppRoute.MessageThread, AppRoute.NewMessage -> AppRoute.Messages
        AppRoute.Dialpad, is AppRoute.CallHistoryDetail -> AppRoute.Calls
        AppRoute.SettingsAppearance,
        AppRoute.SettingsConfiguration,
        is AppRoute.SettingsConfigurationSim,
        AppRoute.SettingsDiagnostics,
        AppRoute.SettingsLogs,
        AppRoute.SettingsCallNotifications,
        AppRoute.SettingsAbout,
        AppRoute.SettingsEngineInformation,
        -> AppRoute.Settings
    }
