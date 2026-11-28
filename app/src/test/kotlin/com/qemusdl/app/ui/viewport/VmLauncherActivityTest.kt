package com.qemusdl.app.ui.viewport

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VmLauncherActivityTest {

    @Test
    fun parseLaunchTarget_acceptsExplicitActionWithVmIdExtra() {
        val target = VmLauncherActivity.parseLaunchTarget(
            action = VmLauncherActivity.ACTION_LAUNCH_VM,
            dataScheme = null,
            dataHost = null,
            dataPathSegment = null,
            queryVmId = null,
            queryVmName = null,
            extraVmId = "vm-1234",
            extraVmName = null
        )
        assertNotNull(target)
        assertEquals("vm-1234", target?.vmId)
        assertNull(target?.vmName)
    }

    @Test
    fun parseLaunchTarget_acceptsDeepLinkPathAndQueryParams() {
        val pathTarget = VmLauncherActivity.parseLaunchTarget(
            action = Intent.ACTION_VIEW,
            dataScheme = "qemusdl",
            dataHost = "launch",
            dataPathSegment = "macos-9-ppc",
            queryVmId = null,
            queryVmName = null,
            extraVmId = null,
            extraVmName = null
        )
        assertEquals(VmLaunchTarget(vmId = "macos-9-ppc", vmName = null), pathTarget)

        val queryTarget = VmLauncherActivity.parseLaunchTarget(
            action = Intent.ACTION_VIEW,
            dataScheme = "qemusdl",
            dataHost = "launch",
            dataPathSegment = null,
            queryVmId = null,
            queryVmName = "Mac OS 9.2.1",
            extraVmId = null,
            extraVmName = null
        )
        assertEquals(VmLaunchTarget(vmId = null, vmName = "Mac OS 9.2.1"), queryTarget)
    }

    @Test
    fun parseLaunchTarget_rejectsUnallowlistedActionsAndSchemes() {
        assertNull(
            VmLauncherActivity.parseLaunchTarget(
                action = Intent.ACTION_MAIN,
                dataScheme = "qemusdl",
                dataHost = "launch",
                dataPathSegment = "vm-1",
                queryVmId = null,
                queryVmName = null,
                extraVmId = "vm-1",
                extraVmName = null
            )
        )
        assertNull(
            VmLauncherActivity.parseLaunchTarget(
                action = Intent.ACTION_VIEW,
                dataScheme = "https",
                dataHost = "launch",
                dataPathSegment = "vm-1",
                queryVmId = null,
                queryVmName = null,
                extraVmId = null,
                extraVmName = null
            )
        )
        assertNull(
            VmLauncherActivity.parseLaunchTarget(
                action = VmLauncherActivity.ACTION_LAUNCH_VM,
                dataScheme = null,
                dataHost = null,
                dataPathSegment = null,
                queryVmId = null,
                queryVmName = null,
                extraVmId = "   ",
                extraVmName = null
            )
        )
    }

    @Test
    fun parseLaunchTarget_rejectsOversizedOrControlCharIdentifiers() {
        assertNull(
            VmLauncherActivity.parseLaunchTarget(
                action = VmLauncherActivity.ACTION_LAUNCH_VM,
                dataScheme = null,
                dataHost = null,
                dataPathSegment = null,
                queryVmId = null,
                queryVmName = null,
                extraVmId = "a".repeat(200),
                extraVmName = null
            )
        )
        assertNull(
            VmLauncherActivity.parseLaunchTarget(
                action = VmLauncherActivity.ACTION_LAUNCH_VM,
                dataScheme = null,
                dataHost = null,
                dataPathSegment = null,
                queryVmId = null,
                queryVmName = null,
                extraVmId = "vm\n123",
                extraVmName = null
            )
        )
    }
}
