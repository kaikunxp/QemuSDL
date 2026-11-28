package com.qemusdl.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import android.view.Menu
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.qemusdl.app.data.VmRepository
import com.qemusdl.app.sdl.NativeQemuBridge
import com.qemusdl.app.ui.theme.QemuSDLTheme
import com.qemusdl.app.ui.viewport.QemuSdlActivity
import com.qemusdl.app.ui.viewport.VmViewportActivity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            QemuSDLTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainNavigation()
                }
            }
        }
        handleRelaunchVmIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRelaunchVmIntent(intent)
    }

    private fun handleRelaunchVmIntent(incoming: Intent?) {
        val targetVmId = incoming?.getStringExtra(EXTRA_RELAUNCH_VM_ID) ?: return
        val launchAdjacent = incoming.getBooleanExtra(EXTRA_RELAUNCH_ADJACENT, false)
        incoming.removeExtra(EXTRA_RELAUNCH_VM_ID)
        incoming.removeExtra(EXTRA_RELAUNCH_ADJACENT)

        // Wait briefly for the previous isolated :qemu_vm process to finish exiting before booting the new VM
        Handler(Looper.getMainLooper()).postDelayed({
            if (isDestroyed || isFinishing) return@postDelayed
            val vm = VmRepository.getInstance(applicationContext).getVmById(targetVmId) ?: return@postDelayed
            val runtimeStatus = NativeQemuBridge.probeRuntime(applicationContext)
            val launchIntent = if (runtimeStatus.isReadyForArch(vm.architecture)) {
                QemuSdlActivity.createIntent(this, vm, launchAdjacent = launchAdjacent)
            } else {
                VmViewportActivity.createIntent(this, vm.id).apply {
                    if (launchAdjacent) {
                        addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
            }
            startActivity(launchIntent)
        }, 300L)
    }

    companion object {
        private const val EXTRA_RELAUNCH_VM_ID = "extra_relaunch_vm_id"
        private const val EXTRA_RELAUNCH_ADJACENT = "extra_relaunch_adjacent"

        fun createRelaunchVmIntent(
            context: Context,
            vmId: String,
            launchAdjacent: Boolean = false
        ): Intent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_RELAUNCH_VM_ID, vmId)
            putExtra(EXTRA_RELAUNCH_ADJACENT, launchAdjacent)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
    }

    override fun onProvideKeyboardShortcuts(
        data: MutableList<KeyboardShortcutGroup>?,
        menu: Menu?,
        deviceId: Int
    ) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        val navigationShortcuts = KeyboardShortcutGroup(
            "Navigation",
            listOf(
                KeyboardShortcutInfo("Virtual Machines", KeyEvent.KEYCODE_1, KeyEvent.META_CTRL_ON),
                KeyboardShortcutInfo("OS & ISO Catalog", KeyEvent.KEYCODE_2, KeyEvent.META_CTRL_ON),
                KeyboardShortcutInfo("Runtime & Disks", KeyEvent.KEYCODE_3, KeyEvent.META_CTRL_ON),
                KeyboardShortcutInfo("About & Updates", KeyEvent.KEYCODE_4, KeyEvent.META_CTRL_ON)
            )
        )
        val workspaceShortcuts = KeyboardShortcutGroup(
            "Virtual Machines",
            listOf(
                KeyboardShortcutInfo("New Virtual Machine", KeyEvent.KEYCODE_N, KeyEvent.META_CTRL_ON),
                KeyboardShortcutInfo("Save VM Configuration", KeyEvent.KEYCODE_S, KeyEvent.META_CTRL_ON),
                KeyboardShortcutInfo("Refresh Runtime & Disks", KeyEvent.KEYCODE_R, KeyEvent.META_CTRL_ON),
                KeyboardShortcutInfo("Close Active Panel", KeyEvent.KEYCODE_ESCAPE, 0)
            )
        )
        data?.add(navigationShortcuts)
        data?.add(workspaceShortcuts)
    }
}
