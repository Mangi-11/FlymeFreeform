package io.github.mangi.flymefreeform.framework

import androidx.compose.runtime.Immutable
import io.github.mangi.flymefreeform.config.ModuleSettingsSnapshot

@Immutable
internal data class FrameworkConnectionState(
    val status: FrameworkConnectionStatus = FrameworkConnectionStatus.Waiting,
    val frameworkName: String? = null,
    val frameworkVersion: String? = null,
    val apiVersion: Int? = null,
    val settings: ModuleSettingsSnapshot = ModuleSettingsSnapshot(),
    val grantedScopes: Set<String> = emptySet(),
    val isUpdating: Boolean = false,
    val isRequestingScope: Boolean = false,
    val issue: FrameworkConnectionIssue? = null,
) {
    val moduleEnabled: Boolean
        get() = settings.enabled

    val missingScopes: Set<String>
        get() = REQUIRED_SCOPES - grantedScopes

    val canChangeSettings: Boolean
        get() =
            status == FrameworkConnectionStatus.Connected &&
                !isUpdating &&
                !isRequestingScope &&
                missingScopes.isEmpty()

    val canRequestScope: Boolean
        get() =
            status == FrameworkConnectionStatus.Connected &&
                !isUpdating &&
                !isRequestingScope &&
                missingScopes.isNotEmpty()

    companion object {
        val REQUIRED_SCOPES: Set<String> =
            linkedSetOf(
                "system",
                "com.android.systemui",
                "com.android.launcher",
            )
    }
}

internal enum class FrameworkConnectionStatus {
    Waiting,
    Connected,
    Incompatible,
    Error,
}

internal enum class FrameworkConnectionIssue {
    ServiceApiTooOld,
    RemoteCapabilityMissing,
    SystemCapabilityMissing,
    MultipleServices,
    ConnectionFailed,
    WriteFailed,
    ScopeRequestFailed,
}
