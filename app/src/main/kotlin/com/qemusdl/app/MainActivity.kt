package com.qemusdl.app

import android.os.Bundle
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
import com.qemusdl.app.ui.theme.QemuSDLTheme

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
                KeyboardShortcutInfo("Runtime & Disks", KeyEvent.KEYCODE_3, KeyEvent.META_CTRL_ON)
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
