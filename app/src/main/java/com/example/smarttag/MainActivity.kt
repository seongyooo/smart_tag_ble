package com.example.smarttag

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import com.example.smarttag.ui.navigation.AppNavGraph
import com.example.smarttag.ui.theme.SmartTagTheme
import com.example.smarttag.viewmodel.ScanViewModel

class MainActivity : ComponentActivity() {

    private var permissionsGranted by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            permissionsGranted = results.values.all { it }
        }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestBlePermissions()

        setContent {
            SmartTagTheme {
                if (!permissionsGranted) {
                    // 권한 미허용 화면
                    Scaffold { padding ->
                        Box(
                            modifier = Modifier.fillMaxSize().padding(padding),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "BLE 권한이 필요합니다.\n블루투스 및 위치 권한을 허용해 주세요.",
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Spacer(Modifier.height(24.dp))
                                Button(onClick = { requestBlePermissions() }) {
                                    Text("권한 요청")
                                }
                            }
                        }
                    }
                } else {
                    val navController = rememberNavController()
                    val scanViewModel: ScanViewModel = viewModel()
                    AppNavGraph(navController = navController, viewModel = scanViewModel)
                }
            }
        }
    }

    private fun requestBlePermissions() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }

        val notGranted = permissions.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            permissionsGranted = true
            enableBluetooth()
        } else {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun enableBluetooth() {
        val bluetoothAdapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (bluetoothAdapter?.isEnabled == false) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }
}
