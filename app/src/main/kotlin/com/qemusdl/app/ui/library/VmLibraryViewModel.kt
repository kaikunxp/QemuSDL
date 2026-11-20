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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class VmLibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = VmRepository.getInstance(application)
    private val isoDownloader = IsoDownloader.getInstance(application)
    private val activeDownloadJobs = mutableMapOf<String, Job>()

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
    }

    fun getVm(id: String): VmConfig? = repository.getVmById(id)

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
        }
    }

    fun cloneVm(id: String) {
        viewModelScope.launch {
            repository.cloneVm(id)
        }
    }

    fun deleteVm(id: String) {
        viewModelScope.launch {
            repository.deleteVm(id)
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
        onComplete: (File) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (activeDownloadJobs[entry.id]?.isActive == true) return
        val job = viewModelScope.launch {
            val result = isoDownloader.downloadCatalogEntry(entry)
            result.onSuccess(onComplete)
            result.onFailure { err -> onError(err.message ?: "Download failed") }
        }
        activeDownloadJobs[entry.id] = job
    }

    fun startCustomIsoDownload(
        url: String,
        preferredName: String? = null,
        onComplete: (File) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val id = IsoDownloader.CUSTOM_DOWNLOAD_ID
        if (activeDownloadJobs[id]?.isActive == true) return
        val job = viewModelScope.launch {
            val result = isoDownloader.downloadCustomUrl(url, preferredName)
            result.onSuccess(onComplete)
            result.onFailure { err -> onError(err.message ?: "Download failed") }
        }
        activeDownloadJobs[id] = job
    }

    fun cancelIsoDownload(id: String) {
        activeDownloadJobs.remove(id)?.cancel()
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
