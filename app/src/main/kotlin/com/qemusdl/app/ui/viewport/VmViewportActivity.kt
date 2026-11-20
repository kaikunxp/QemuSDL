package com.qemusdl.app.ui.viewport

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

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
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        super.onDestroy()
        NativeQemuBridge.stopSession()
        openDescriptors.forEach { pfd -> runCatching { pfd.close() } }
        openDescriptors.clear()
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
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
