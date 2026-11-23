package com.example.earbuds

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.SoundEffectConstants
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    private var hasPermission by mutableStateOf(false)
    private var a2dp: BluetoothA2dp? = null
    private var tick by mutableStateOf(0)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasPermission = granted
            if (granted) connectProxy()
        }

    private val adapter: BluetoothAdapter?
        get() = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hasPermission = Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) connectProxy()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Screen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        tick++
    }

    override fun onDestroy() {
        adapter?.closeProfileProxy(BluetoothProfile.A2DP, a2dp)
        super.onDestroy()
    }

    private fun click() {
        window.decorView.playSoundEffect(SoundEffectConstants.CLICK)
    }

    @SuppressLint("MissingPermission")
    private fun connectProxy() {
        adapter?.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                a2dp = proxy as BluetoothA2dp
                tick++
            }

            override fun onServiceDisconnected(profile: Int) {
                a2dp = null
                tick++
            }
        }, BluetoothProfile.A2DP)
    }

    @SuppressLint("MissingPermission")
    private fun pairedAudioDevices(): List<BluetoothDevice> =
        if (!hasPermission) emptyList()
        else adapter?.bondedDevices.orEmpty().filter {
            it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO
        }

    @SuppressLint("MissingPermission")
    private fun isConnected(d: BluetoothDevice) =
        a2dp?.connectedDevices?.any { it.address == d.address } == true

    private fun batteryOf(d: BluetoothDevice): Int = try {
        d.javaClass.getMethod("getBatteryLevel").invoke(d) as Int
    } catch (e: Throwable) {
        -1
    }

    private fun toggleConnection(d: BluetoothDevice, connect: Boolean) {
        click()
        val proxy = a2dp
        val ok = try {
            val m = BluetoothA2dp::class.java.getDeclaredMethod(
                if (connect) "connect" else "disconnect", BluetoothDevice::class.java
            )
            m.isAccessible = true
            m.invoke(proxy, d) as Boolean
        } catch (e: Throwable) {
            false
        }
        if (!ok) {
            Toast.makeText(this, "Система не разрешила — откройте настройки Bluetooth", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
        tick++
    }

    private fun sendKey(code: Int) {
        click()
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    @SuppressLint("MissingPermission")
    @Composable
    private fun Screen() {
        @Suppress("UNUSED_VARIABLE") val refresh = tick
        var volume by remember {
            mutableStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat())
        }
        val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Earbuds App", style = MaterialTheme.typography.headlineMedium)

            if (!hasPermission) {
                Text("Нужно разрешение на доступ к Bluetooth.")
                Button(onClick = {
                    click()
                    permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }) { Text("Дать разрешение") }
                return@Column
            }

            val devices = pairedAudioDevices()
            if (devices.isEmpty()) {
                Text("Сопряжённых наушников нет. Сначала подключите их в настройках Bluetooth.")
            }

            devices.forEach { d ->
                val connected = isConnected(d)
                val battery = batteryOf(d)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val name = d.name ?: d.address
                        Text(name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            (if (connected) "Подключены" else "Не подключены") +
                                if (battery in 0..100) " · заряд $battery%" else ""
                        )
                        Button(
                            onClick = { toggleConnection(d, !connected) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (connected) "Отключить" else "Подключить") }
                    }
                }
            }

            Text("Громкость: ${volume.toInt()} / $maxVol")
            Slider(
                value = volume,
                onValueChange = {
                    volume = it
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, it.toInt(), 0)
                },
                valueRange = 0f..maxVol.toFloat(),
                steps = maxVol - 1
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = { sendKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }) { Text("⏮") }
                OutlinedButton(onClick = { sendKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }) { Text("⏯") }
                OutlinedButton(onClick = { sendKey(KeyEvent.KEYCODE_MEDIA_NEXT) }) { Text("⏭") }
            }

            OutlinedButton(
                onClick = {
                    click()
                    startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Настройки Bluetooth") }

            OutlinedButton(
                onClick = {
                    click()
                    tick++
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Обновить") }
        }
    }
}
