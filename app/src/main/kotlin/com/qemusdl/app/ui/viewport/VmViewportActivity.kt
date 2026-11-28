package com.qemusdl.app.ui.viewport

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.qemusdl.app.data.VmConfig
import com.qemusdl.app.data.VmRepository
import com.qemusdl.app.qemu.QemuCommandBuilder
import com.qemusdl.app.sdl.NativeQemuBridge
import com.qemusdl.app.sdl.SdlSurfaceView
import com.qemusdl.app.ui.theme.QemuSDLTheme
import kotlinx.coroutines.launch

class VmViewportActivity : ComponentActivity() {

    private val openDescriptors = mutableListOf<ParcelFileDescriptor>()
    private var activeSurfaceView: SdlSurfaceView? = null
    private lateinit var vmConfig: VmConfig
    private var qemuArgs: List<String> = emptyList()
    private var windowingState by mutableStateOf(WindowingEnvironmentState())
    private var hasEverDetectedWindowed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val initialWindowing = DesktopWindowingHelper.inspect(this)
        hasEverDetectedWindowed = initialWindowing.showFullscreenButton
        applyFullscreenState(!initialWindowing.showFullscreenButton)

        window.decorView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val newW = right - left
            val newH = bottom - top
            val oldW = oldRight - oldLeft
            val oldH = oldBottom - oldTop
            if (newW > 0 && newH > 0 && (newW != oldW || newH != oldH)) {
                refreshWindowingState()
                activeSurfaceView?.let { surface ->
                    if (surface.width > 0 && surface.height > 0) {
                        NativeQemuBridge.resizeSurface(surface.width, surface.height)
                    }
                }
            }
        }

        val vmId = intent.getStringExtra(EXTRA_VM_ID)
        val repository = VmRepository.getInstance(this)
        vmConfig = vmId?.let { repository.getVmById(it) } ?: VmConfig(name = "Quick x86_64 VM")

        val resolvedDisk = resolveMediaPath(vmConfig.diskPathOrUri, readWrite = true)
        val resolvedIso = resolveMediaPath(vmConfig.isoPathOrUri, readWrite = false)
        qemuArgs = QemuCommandBuilder.buildArgs(
            config = vmConfig,
            resolvedDiskPath = resolvedDisk,
            resolvedIsoPath = resolvedIso
        )

        NativeQemuBridge.startSession(
            context = this,
            vmName = vmConfig.name,
            guestWidth = vmConfig.resolution.width,
            guestHeight = vmConfig.resolution.height,
            qemuArgs = qemuArgs
        )
        NativeQemuBridge.setScalingMode(vmConfig.scalingMode.nativeCode)

        setContent {
            QemuSDLTheme(darkTheme = true) {
                VmViewportScreen(
                    vmConfig = vmConfig,
                    qemuCommandLine = qemuArgs.joinToString(" "),
                    showFullscreenButton = windowingState.showFullscreenButton,
                    isFullscreen = windowingState.isFullscreen,
                    onToggleFullscreen = { toggleFullscreen() },
                    onSurfaceViewCreated = { view -> activeSurfaceView = view },
                    onToggleSoftKeyboard = { toggleSoftKeyboard() },
                    onUpdateVmConfig = { updated ->
                        vmConfig = updated
                        lifecycleScope.launch {
                            repository.upsertVm(updated)
                        }
                    },
                    onPowerOff = { finish() }
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && windowingState.isFullscreen) {
            applyFullscreenState(true)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshWindowingState()
        activeSurfaceView?.post {
            activeSurfaceView?.let { surface ->
                if (surface.width > 0 && surface.height > 0) {
                    NativeQemuBridge.resizeSurface(surface.width, surface.height)
                }
            }
        }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        refreshWindowingState()
        activeSurfaceView?.post {
            activeSurfaceView?.let { surface ->
                if (surface.width > 0 && surface.height > 0) {
                    NativeQemuBridge.resizeSurface(surface.width, surface.height)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        NativeQemuBridge.stopSession()
        openDescriptors.forEach { pfd -> runCatching { pfd.close() } }
        openDescriptors.clear()
    }

    private fun toggleFullscreen() {
        val target = !windowingState.isFullscreen
        applyFullscreenState(target)
    }

    private fun applyFullscreenState(fullscreen: Boolean) {
        DesktopWindowingHelper.applyFullscreen(this, fullscreen)
        refreshWindowingState(explicitFullscreen = fullscreen)
    }

    private fun refreshWindowingState(explicitFullscreen: Boolean = windowingState.isFullscreen) {
        val inspected = DesktopWindowingHelper.inspect(
            activity = this,
            previouslyDetectedWindowed = hasEverDetectedWindowed,
            isCurrentlyFullscreen = explicitFullscreen
        )
        if (inspected.showFullscreenButton) {
            hasEverDetectedWindowed = true
        }
        windowingState = inspected
    }

    private fun toggleSoftKeyboard() {
        val target = activeSurfaceView ?: return
        target.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
    }

    /**
     * Converts Android Storage Access Framework `content://` URIs into `/proc/self/fd/<fd>`
     * paths so in-process QEMU can read/write disk and ISO images without legacy permissions.
     */
    private fun resolveMediaPath(pathOrUri: String, readWrite: Boolean): String? {
        val trimmed = pathOrUri.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith("content://")) return trimmed
        return runCatching {
            val mode = if (readWrite) "rw" else "r"
            val pfd = contentResolver.openFileDescriptor(Uri.parse(trimmed), mode)
                ?: contentResolver.openFileDescriptor(Uri.parse(trimmed), "r")
            if (pfd != null) {
                openDescriptors += pfd
                "/proc/self/fd/${pfd.fd}"
            } else {
                null
            }
        }.getOrNull()
    }

    companion object {
        private const val EXTRA_VM_ID = "extra_vm_id"

        fun createIntent(context: Context, vmId: String): Intent =
            Intent(context, VmViewportActivity::class.java).putExtra(EXTRA_VM_ID, vmId)
    }
}
