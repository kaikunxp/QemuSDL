package com.qemusdl.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.qemusdl.app.data.VmConfig
import com.qemusdl.app.ui.editor.VmEditorScreen
import com.qemusdl.app.ui.isos.IsoDownloaderScreen
import com.qemusdl.app.ui.library.VmLibraryScreen
import com.qemusdl.app.ui.library.VmLibraryViewModel
import com.qemusdl.app.ui.runtime.RuntimeSetupScreen
import com.qemusdl.app.ui.setup.VmSetupScreen
import com.qemusdl.app.ui.viewport.QemuSdlActivity
import com.qemusdl.app.ui.viewport.VmViewportActivity

@Composable
fun MainNavigation(
    viewModel: VmLibraryViewModel = viewModel()
) {
    val context = LocalContext.current
    val runtimeStatus by viewModel.runtimeStatus.collectAsState()
    val backStack = rememberNavBackStack(VmLibraryKey)

    fun launchVm(vm: VmConfig) {
        val intent = if (runtimeStatus.isReadyForArch(vm.architecture)) {
            QemuSdlActivity.createIntent(context, vm)
        } else {
            VmViewportActivity.createIntent(context, vm.id)
        }
        context.startActivity(intent)
    }

    fun popBackSafely() {
        if (backStack.size > 1) {
            backStack.removeLastOrNull()
        }
    }

    NavDisplay(
        backStack = backStack,
        onBack = { popBackSafely() },
        entryProvider = entryProvider {
            entry<VmLibraryKey> {
                VmLibraryScreen(
                    viewModel = viewModel,
                    onCreateVm = { backStack.add(VmEditorKey(vmId = null)) },
                    onEditVm = { vmId -> backStack.add(VmEditorKey(vmId = vmId)) },
                    onRunSetup = { vmId -> backStack.add(VmSetupKey(vmId = vmId)) },
                    onBootVm = { vm -> launchVm(vm) },
                    onOpenIsoDownloader = { backStack.add(IsoDownloaderKey(targetVmId = null)) },
                    onOpenRuntimeSetup = { backStack.add(RuntimeSetupKey) }
                )
            }

            entry<VmSetupKey> { key ->
                VmSetupScreen(
                    vmId = key.vmId,
                    viewModel = viewModel,
                    onBack = { popBackSafely() },
                    onOpenIsoCatalog = { backStack.add(IsoDownloaderKey(targetVmId = key.vmId)) },
                    onCompletedAndBoot = { configuredVm ->
                        popBackSafely()
                        launchVm(configuredVm)
                    }
                )
            }

            entry<IsoDownloaderKey> { key ->
                IsoDownloaderScreen(
                    viewModel = viewModel,
                    targetVmId = key.targetVmId,
                    onBack = { popBackSafely() },
                    onIsoSelectedForVm = { _, _ ->
                        popBackSafely()
                    }
                )
            }

            entry<VmEditorKey> { key ->
                VmEditorScreen(
                    vmId = key.vmId,
                    viewModel = viewModel,
                    onBack = { popBackSafely() },
                    onOpenIsoDownloader = { targetId ->
                        backStack.add(IsoDownloaderKey(targetVmId = targetId))
                    },
                    onSavedAndBoot = { saved ->
                        popBackSafely()
                        launchVm(saved)
                    }
                )
            }

            entry<RuntimeSetupKey> {
                RuntimeSetupScreen(
                    viewModel = viewModel,
                    onBack = { popBackSafely() }
                )
            }
        }
    )
}
