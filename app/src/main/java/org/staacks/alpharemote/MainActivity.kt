package org.staacks.alpharemote

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.launch
import org.staacks.alpharemote.camera.CameraAction
import org.staacks.alpharemote.camera.CameraActionPreset
import org.staacks.alpharemote.input.BluetoothSppManager
import org.staacks.alpharemote.databinding.ActivityMainBinding
import org.staacks.alpharemote.service.AlphaRemoteService
import org.staacks.alpharemote.service.ServiceRunning

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var customButtonList: List<CameraAction>? = null
    private var bluetoothSppManager: BluetoothSppManager? = null

    companion object {
        const val NAVIGATE_TO_INTENT_EXTRA = "nav_to"
        val TAG: String = "alpharemote"
    }
    val SELECTED_PAGE = "selected_page"

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()

        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navView: BottomNavigationView = binding.navView

        val navController = findNavController(R.id.nav_host_fragment_activity_main)

        navView.setupWithNavController(navController)

        var startPage = intent?.getIntExtra(NAVIGATE_TO_INTENT_EXTRA, R.id.navigation_camera) ?: R.id.navigation_camera
        startPage = savedInstanceState?.getInt(SELECTED_PAGE, startPage) ?: startPage
        navigateTo(startPage)

        lifecycleScope.launch {
            SettingsStore(application).customButtonSettings.collect {
                customButtonList = it.customButtonList
            }
        }
    }


    override fun onStart() {
        super.onStart()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "BLUETOOTH_CONNECT permission is missing; HC-05 input will not start.")
            return
        }

        bluetoothSppManager = BluetoothSppManager(
            context = this,
            onConnected = { device ->
                runOnUiThread {
                    android.widget.Toast.makeText(this, "HC-05 terhubung: $device", android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            onShutterCommand = {
                runOnUiThread {
                    if (AlphaRemoteService.serviceState.value is ServiceRunning) {
                        val shutterAction = CameraAction(
                            toggle = false,
                            selftimer = null,
                            duration = null,
                            step = null,
                            preset = CameraActionPreset.TRIGGER_ONCE
                        )
                        AlphaRemoteService.sendCameraAction(this, shutterAction)
                    } else {
                        Log.w(TAG, "HC-05 shutter command ignored: Sony camera is not connected.")
                    }
                }
            },
            onError = { message ->
                runOnUiThread {
                    Log.w(TAG, message)
                    android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        ).also { it.connectToPairedHC05() }
    }

    override fun onStop() {
        bluetoothSppManager?.close()
        bluetoothSppManager = null
        super.onStop()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)

        intent?.getIntExtra(NAVIGATE_TO_INTENT_EXTRA, R.id.navigation_camera)?.let {
            navigateTo(it)
        }
    }

    fun navigateTo(id: Int) {
        binding.navView.selectedItemId = id
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(SELECTED_PAGE, binding.navView.selectedItemId)
    }

    // Physical keys bound to custom buttons. Dialogs (i.e. while binding a key) receive their key events directly and do not pass through here.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (AlphaRemoteService.serviceState.value is ServiceRunning) {
            customButtonList?.firstOrNull { it.keyCode == event.keyCode }?.let { cameraAction ->
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0)
                    AlphaRemoteService.sendCameraAction(this, cameraAction)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}