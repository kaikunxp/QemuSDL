package com.qemusdl.app.ui.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.qemusdl.app.data.GuestArchitecture
import com.qemusdl.app.data.OsPreset
import com.qemusdl.app.data.VmConfig
import com.qemusdl.app.ui.viewport.VmLauncherActivity

enum class ShortcutPinResult {
    REQUESTED,
    UNSUPPORTED_LAUNCHER,
    FAILED
}

/**
 * Manages pinned home screen shortcuts and dynamic launcher app shortcuts for saved VMs.
 * Each shortcut directly invokes [VmLauncherActivity] for the target VM ID.
 */
object VmShortcutManager {

    private const val MAX_DYNAMIC_SHORTCUTS = 4

    fun shortcutIdFor(vmId: String): String = "vm_$vmId"

    fun pinVmShortcut(context: Context, vm: VmConfig): ShortcutPinResult {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            return ShortcutPinResult.UNSUPPORTED_LAUNCHER
        }
        return runCatching {
            val info = buildShortcutInfo(context, vm)
            val accepted = ShortcutManagerCompat.requestPinShortcut(context, info, null)
            if (accepted) ShortcutPinResult.REQUESTED else ShortcutPinResult.FAILED
        }.getOrDefault(ShortcutPinResult.FAILED)
    }

    fun syncShortcuts(context: Context, vms: List<VmConfig>) {
        runCatching {
            val maxAllowed = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)
                .coerceAtLeast(1)
                .coerceAtMost(MAX_DYNAMIC_SHORTCUTS)
            val dynamic = vms.take(maxAllowed).map { buildShortcutInfo(context, it) }
            ShortcutManagerCompat.setDynamicShortcuts(context, dynamic)

            // Keep any already-pinned home screen shortcuts synchronized with renamed/edited VMs
            val pinnedIds = ShortcutManagerCompat.getShortcuts(
                context,
                ShortcutManagerCompat.FLAG_MATCH_PINNED
            ).map { it.id }.toSet()
            if (pinnedIds.isNotEmpty()) {
                val updatedPinned = vms
                    .filter { shortcutIdFor(it.id) in pinnedIds }
                    .map { buildShortcutInfo(context, it) }
                if (updatedPinned.isNotEmpty()) {
                    ShortcutManagerCompat.updateShortcuts(context, updatedPinned)
                }
            }
        }
    }

    fun disableVmShortcut(context: Context, vmId: String) {
        runCatching {
            ShortcutManagerCompat.disableShortcuts(
                context,
                listOf(shortcutIdFor(vmId)),
                "This virtual machine was deleted"
            )
        }
    }

    private fun buildShortcutInfo(context: Context, vm: VmConfig): ShortcutInfoCompat {
        val shortLabel = vm.name.trim().ifBlank { "QemuSDL VM" }.take(24)
        val longLabel = "Boot ${vm.name.trim().ifBlank { "VM" }} (${vm.architecture.badgeText})".take(48)
        val intent = VmLauncherActivity.createLaunchIntent(context, vm)
        return ShortcutInfoCompat.Builder(context, shortcutIdFor(vm.id))
            .setShortLabel(shortLabel)
            .setLongLabel(longLabel)
            .setIcon(IconCompat.createWithAdaptiveBitmap(renderAdaptiveShortcutIcon(vm)))
            .setIntent(intent)
            .build()
    }

    /**
     * Renders a crisp 192x192 adaptive icon bitmap (content kept inside the 66% center safe zone)
     * with an OS/architecture color palette and display bezel motif so each VM shortcut is
     * immediately recognizable on any launcher mask (circle, squircle, Niagara, etc.).
     */
    private fun renderAdaptiveShortcutIcon(vm: VmConfig): Bitmap {
        val size = 192
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val (bgStart, bgEnd, accentColor, badgeText) = shortcutPalette(vm)

        // Full-bleed adaptive background gradient
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, size.toFloat(), size.toFloat(),
                bgStart, bgEnd,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bgPaint)

        // Center-safe monitor silhouette (stays well within 66% adaptive icon safe zone: 33..159)
        val screenRect = RectF(46f, 52f, 146f, 122f)
        val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#111622")
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(screenRect, 14f, 14f, framePaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            style = Paint.Style.STROKE
            strokeWidth = 4.5f
        }
        canvas.drawRoundRect(screenRect, 14f, 14f, borderPaint)

        // Monitor stand base
        val standPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = 215
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(RectF(86f, 122f, 106f, 134f), 3f, 3f, standPaint)
        canvas.drawRoundRect(RectF(70f, 132f, 122f, 140f), 4f, 4f, standPaint)

        // Architecture / OS label centered inside monitor screen
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = if (badgeText.length <= 3) 30f else 24f
        }
        val textY = screenRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(badgeText, screenRect.centerX(), textY, textPaint)

        return bitmap
    }

    private data class ShortcutPalette(
        val bgStart: Int,
        val bgEnd: Int,
        val accent: Int,
        val badge: String
    )

    private fun shortcutPalette(vm: VmConfig): ShortcutPalette = when (vm.osPreset) {
        OsPreset.MACOS_9, OsPreset.MACOS_X_PPC -> ShortcutPalette(
            bgStart = Color.parseColor("#3B2F63"),
            bgEnd = Color.parseColor("#1D4E89"),
            accent = Color.parseColor("#A8DADC"),
            badge = if (vm.osPreset == OsPreset.MACOS_9) "OS 9" else "OSX"
        )
        OsPreset.WINDOWS, OsPreset.WINDOWS_AARCH64 -> ShortcutPalette(
            bgStart = Color.parseColor("#0B4F8A"),
            bgEnd = Color.parseColor("#0078D4"),
            accent = Color.parseColor("#7CE0FF"),
            badge = if (vm.architecture == GuestArchitecture.AARCH64) "W11" else "WIN"
        )
        OsPreset.FREEDOS -> ShortcutPalette(
            bgStart = Color.parseColor("#2B2D42"),
            bgEnd = Color.parseColor("#4A4E69"),
            accent = Color.parseColor("#FFD166"),
            badge = "DOS"
        )
        OsPreset.LINUX_ALPINE, OsPreset.LINUX_ALPINE_AARCH64 -> ShortcutPalette(
            bgStart = Color.parseColor("#0D597F"),
            bgEnd = Color.parseColor("#0B3B59"),
            accent = Color.parseColor("#64DFDF"),
            badge = if (vm.architecture == GuestArchitecture.AARCH64) "ARM" else "x64"
        )
        OsPreset.LINUX_DEBIAN, OsPreset.LINUX_DEBIAN_AARCH64 -> ShortcutPalette(
            bgStart = Color.parseColor("#8B1E3F"),
            bgEnd = Color.parseColor("#4A1942"),
            accent = Color.parseColor("#FF8FA3"),
            badge = if (vm.architecture == GuestArchitecture.AARCH64) "ARM" else "DEB"
        )
        else -> when (vm.architecture) {
            GuestArchitecture.AARCH64 -> ShortcutPalette(
                bgStart = Color.parseColor("#1B4332"),
                bgEnd = Color.parseColor("#2D6A4F"),
                accent = Color.parseColor("#95D5B2"),
                badge = "ARM"
            )
            GuestArchitecture.PPC -> ShortcutPalette(
                bgStart = Color.parseColor("#5A189A"),
                bgEnd = Color.parseColor("#3C096C"),
                accent = Color.parseColor("#E0AAFF"),
                badge = "PPC"
            )
            GuestArchitecture.X86_64 -> ShortcutPalette(
                bgStart = Color.parseColor("#1D3557"),
                bgEnd = Color.parseColor("#2A4D7A"),
                accent = Color.parseColor("#90E0EF"),
                badge = "x64"
            )
        }
    }
}
