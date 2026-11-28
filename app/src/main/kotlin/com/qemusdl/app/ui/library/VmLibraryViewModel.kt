package com.qemusdl.app.ui.library

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qemusdl.app.data.DownloadedIsoInfo
import com.qemusdl.app.data.VirtualDiskInfo
import com.qemusdl.app.data.VmConfig
import com.qemusdl.app.data.VmRepository
import com.qemusdl.app.qemu.DiskFormat
import com.qemusdl.app.qemu.IsoCatalogEntry
import com.qemusdl.app.qemu.IsoDownloadState
import com.qemusdl.app.qemu.IsoDownloader
import com.qemusdl.app.sdl.NativeQemuBridge
import com.qemusdl.app.sdl.RuntimeProbeStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class VmLibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = VmRepository.getInstance(application)
    private val isoDownloader = IsoDownloader.getInstance(application)

    val vms: StateFlow<List<VmConfig>> = repository.vms
    val disks: StateFlow<List<VirtualDiskInfo>> = repository.disks
    val isoDownloadStates: StateFlow<Map<String, IsoDownloadState>> = isoDownloader.states
    val downloadedIsos: StateFlow<List<DownloadedIsoInfo>> = isoDownloader.downloadedIsos

    private val _runtimeStatus = MutableStateFlow(RuntimeProbeStatus())
    val runtimeStatus: StateFlow<RuntimeProbeStatus> = _runtimeStatus.asStateFlow()

    private val _isProbing = MutableStateFlow(false)
    val isProbing: StateFlow<Boolean> = _isProbing.asStateFlow()

    init {
        refreshRuntime()
        syncLauncherShortcuts()
    }

    fun getVm(id: String): VmConfig? = repository.getVmById(id)

    fun pinVmShortcut(vm: VmConfig): ShortcutPinResult =
        VmShortcutManager.pinVmShortcut(getApplication(), vm)

    private fun syncLauncherShortcuts() {
        VmShortcutManager.syncShortcuts(getApplication(), repository.vms.value)
    }

    fun refreshRuntime() {
        viewModelScope.launch {
            _isProbing.value = true
            val probe = withContext(Dispatchers.IO) {
                NativeQemuBridge.probeRuntime(getApplication())
            }
            _runtimeStatus.value = probe
            repository.refreshDisks()
            isoDownloader.refreshLocalIsos()
            _isProbing.value = false
        }
    }

    fun saveVm(config: VmConfig) {
        viewModelScope.launch {
            repository.upsertVm(config)
            syncLauncherShortcuts()
        }
    }

    fun cloneVm(id: String) {
        viewModelScope.launch {
            repository.cloneVm(id)
            syncLauncherShortcuts()
        }
    }

    fun deleteVm(id: String) {
        viewModelScope.launch {
            repository.deleteVm(id)
            VmShortcutManager.disableVmShortcut(getApplication(), id)
            syncLauncherShortcuts()
        }
    }

    fun createDisk(name: String, sizeMb: Int, format: DiskFormat, onCreated: (VirtualDiskInfo) -> Unit = {}) {
        viewModelScope.launch {
            val info = repository.createVirtualDisk(name, sizeMb, format)
            onCreated(info)
        }
    }

    fun deleteDisk(path: String) {
        viewModelScope.launch {
            repository.deleteVirtualDisk(path)
        }
    }

    fun getAlreadyDownloadedIso(entry: IsoCatalogEntry): File? =
        isoDownloader.getDownloadedFile(entry)

    fun startIsoDownload(
        entry: IsoCatalogEntry,
        autoAttachVmId: String? = null,
        onComplete: (File) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        isoDownloader.startCatalogDownload(
            entry = entry,
            onComplete = { file ->
                if (autoAttachVmId != null) {
                    if (entry.isDriverTools) {
                        attachVirtioDriversIsoToVm(autoAttachVmId, file.absolutePath)
                    } else {
                        attachIsoToVm(autoAttachVmId, file.absolutePath, file.name)
                    }
                }
                onComplete(file)
            },
            onError = onError
        )
    }

    fun startCustomIsoDownload(
        url: String,
        preferredName: String? = null,
        autoAttachVmId: String? = null,
        onComplete: (File) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        isoDownloader.startCustomDownload(
            urlString = url,
            preferredFileName = preferredName,
            onComplete = { file ->
                if (autoAttachVmId != null) {
                    attachIsoToVm(autoAttachVmId, file.absolutePath, file.name)
                }
                onComplete(file)
            },
            onError = onError
        )
    }

    fun cancelIsoDownload(id: String) {
        isoDownloader.cancelDownload(id)
        isoDownloader.clearError(id)
    }

    fun deleteLocalIso(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            isoDownloader.deleteLocalIso(path)
        }
    }

    fun attachIsoToVm(vmId: String, isoPath: String, isoName: String) {
        val vm = repository.getVmById(vmId) ?: return
        saveVm(vm.copy(isoPathOrUri = isoPath, isoDisplayName = isoName))
    }

    fun attachVirtioDriversIsoToVm(vmId: String, isoPath: String) {
        val vm = repository.getVmById(vmId) ?: return
        saveVm(vm.copy(attachVirtioDriversIso = true, virtioDriversIsoPath = isoPath))
    }

    fun importNativeLibrary(uri: Uri, onComplete: (String) -> Unit) {
        viewModelScope.launch {
            val message = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    val fileName = resolveDisplayName(uri) ?: "imported.so"
                    if (!fileName.endsWith(".so", ignoreCase = true)) {
                        return@runCatching "Skipped '$fileName': expected a .so shared library."
                    }
                    val target = File(NativeQemuBridge.customLibDirectory(getApplication()), fileName)
                    resolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@runCatching "Unable to read selected file."
                    "Imported $fileName (${target.length() / 1024} KiB)"
                }.getOrElse { err ->
                    "Import failed: ${err.message}"
                }
            }
            refreshRuntime()
            onComplete(message)
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        val resolver = getApplication<Application>().contentResolver
        return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }
}
