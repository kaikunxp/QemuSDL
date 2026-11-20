package com.qemusdl.app

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data object VmLibraryKey : NavKey

@Serializable
data class VmEditorKey(val vmId: String? = null) : NavKey

@Serializable
data class VmSetupKey(val vmId: String) : NavKey

@Serializable
data class IsoDownloaderKey(val targetVmId: String? = null) : NavKey

@Serializable
data object RuntimeSetupKey : NavKey
