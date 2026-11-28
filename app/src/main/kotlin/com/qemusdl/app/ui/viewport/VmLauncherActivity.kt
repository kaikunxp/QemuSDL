package com.qemusdl.app.ui.viewport

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.qemusdl.app.data.VmConfig
import com.qemusdl.app.data.VmRepository
import com.qemusdl.app.sdl.NativeQemuBridge

internal data class VmLaunchTarget(
    val vmId: String? = null,
    val vmName: String? = null
)

/**
 * Exported trampoline Activity enabling pinned launcher shortcuts, dynamic app shortcuts,
 * and external automation apps (Tasker, ADB, custom launchers) to directly boot a saved VM.
 *
 * Security model:
 * - Only accepts an allowlisted action ([ACTION_LAUNCH_VM] or [Intent.ACTION_VIEW] with
 *   `qemusdl://launch/...`) and a bounded VM identifier (`vm_id` or `vm_name`).
 * - Never forwards incoming Intents or reads untrusted VM configs, file paths, or QEMU flags
 *   from external extras; every target is strictly resolved against saved VMs in [VmRepository].
 */
class VmLauncherActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleLaunchIntent(intent)
        finish()
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        intent = newIntent
        handleLaunchIntent(newIntent)
        finish()
    }

    private fun handleLaunchIntent(incoming: Intent?) {
        if (incoming == null) {
            Log.w(TAG, "Ignoring null launch intent")
            return
        }

        val target = parseLaunchTarget(
            action = incoming.action,
            dataScheme = incoming.data?.scheme,
            dataHost = incoming.data?.host,
            dataPathSegment = incoming.data?.pathSegments?.firstOrNull(),
            queryVmId = incoming.data?.getQueryParameter("id") ?: incoming.data?.getQueryParameter(EXTRA_VM_ID),
            queryVmName = incoming.data?.getQueryParameter("name") ?: incoming.data?.getQueryParameter(EXTRA_VM_NAME),
            extraVmId = runCatching { incoming.getStringExtra(EXTRA_VM_ID) }.getOrNull(),
            extraVmName = runCatching { incoming.getStringExtra(EXTRA_VM_NAME) }.getOrNull()
        )

        if (target == null) {
            Log.w(TAG, "Rejected invalid or unallowlisted VM launch intent (action=${incoming.action})")
            Toast.makeText(this, "Invalid VM shortcut", Toast.LENGTH_SHORT).show()
            return
        }

        val repository = VmRepository.getInstance(applicationContext)
        val vm = target.vmId?.let { repository.getVmById(it) }
            ?: target.vmName?.let { repository.getVmByName(it) }

        if (vm == null) {
            Log.w(TAG, "Requested VM not found in repository")
            Toast.makeText(this, "Virtual machine not found", Toast.LENGTH_SHORT).show()
            return
        }

        val runtimeStatus = NativeQemuBridge.probeRuntime(applicationContext)
        val launchIntent = if (runtimeStatus.isReadyForArch(vm.architecture)) {
            QemuSdlActivity.createIntent(this, vm)
        } else {
            VmViewportActivity.createIntent(this, vm.id)
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(launchIntent)
    }

    companion object {
        private const val TAG = "VmLauncherActivity"
        private const val MAX_IDENTIFIER_LENGTH = 128

        const val ACTION_LAUNCH_VM = "com.qemusdl.app.action.LAUNCH_VM"
        const val EXTRA_VM_ID = "vm_id"
        const val EXTRA_VM_NAME = "vm_name"
        const val DEEP_LINK_SCHEME = "qemusdl"
        const val DEEP_LINK_HOST = "launch"

        fun createLaunchUri(vmId: String): Uri =
            Uri.Builder()
                .scheme(DEEP_LINK_SCHEME)
                .authority(DEEP_LINK_HOST)
                .appendPath(vmId)
                .build()

        fun createLaunchIntent(context: Context, vm: VmConfig): Intent =
            Intent(ACTION_LAUNCH_VM, createLaunchUri(vm.id), context, VmLauncherActivity::class.java).apply {
                putExtra(EXTRA_VM_ID, vm.id)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

        fun formatLaunchUriString(vm: VmConfig): String =
            "$DEEP_LINK_SCHEME://$DEEP_LINK_HOST/${vm.id}"

        fun formatAdbLaunchCommand(vm: VmConfig): String =
            "adb shell am start -a $ACTION_LAUNCH_VM --es $EXTRA_VM_ID \"${vm.id}\" com.qemusdl.app/.ui.viewport.VmLauncherActivity"

        internal fun parseLaunchTarget(
            action: String?,
            dataScheme: String?,
            dataHost: String?,
            dataPathSegment: String?,
            queryVmId: String?,
            queryVmName: String?,
            extraVmId: String?,
            extraVmName: String?
        ): VmLaunchTarget? {
            val isExplicitLaunchAction = action == ACTION_LAUNCH_VM
            val isDeepLinkAction = action == Intent.ACTION_VIEW &&
                dataScheme.equals(DEEP_LINK_SCHEME, ignoreCase = true) &&
                dataHost.equals(DEEP_LINK_HOST, ignoreCase = true)

            if (!isExplicitLaunchAction && !isDeepLinkAction) {
                return null
            }

            val sanitizedId = sanitizeIdentifier(extraVmId)
                ?: sanitizeIdentifier(queryVmId)
                ?: if (dataScheme.equals(DEEP_LINK_SCHEME, ignoreCase = true) &&
                    dataHost.equals(DEEP_LINK_HOST, ignoreCase = true)
                ) {
                    sanitizeIdentifier(dataPathSegment)
                } else {
                    null
                }

            val sanitizedName = sanitizeIdentifier(extraVmName)
                ?: sanitizeIdentifier(queryVmName)

            if (sanitizedId == null && sanitizedName == null) {
                return null
            }
            return VmLaunchTarget(vmId = sanitizedId, vmName = sanitizedName)
        }

        private fun sanitizeIdentifier(raw: String?): String? {
            val trimmed = raw?.trim() ?: return null
            if (trimmed.isEmpty() || trimmed.length > MAX_IDENTIFIER_LENGTH) return null
            if (trimmed.any { it.isISOControl() }) return null
            return trimmed
        }
    }
}
