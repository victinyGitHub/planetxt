package com.example.planetxt

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.planetxt.ui.theme.PlanetxtTheme

class MainActivity : ComponentActivity() {

    private val basePermissions = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.CHANGE_WIFI_STATE
    )

    private val requiredPermissions: Array<String>
        get() = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            basePermissions + Manifest.permission.NEARBY_WIFI_DEVICES
        } else basePermissions

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensurePermissionsAndShowUi()
    }

    private fun ensurePermissionsAndShowUi() {
        if (requiredPermissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            setupUi()
        } else {
            ActivityCompat.requestPermissions(this, requiredPermissions, 101)
        }
    }

    private fun setupUi() {
        NearbyManager.init(this)
        setContent {
            PlanetxtTheme {
                ChatScreen()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            setupUi()
        } else {
            finish() // Cannot operate without permissions
        }
    }

    override fun onStop() {
        super.onStop()
        NearbyManager.stopAll()
    }
}

@Composable
fun ChatScreen() {
    val context = LocalContext.current
    var userName by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf<Pair<String, Boolean>>() }
    var connectionCount by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        NearbyManager.onMessageReceived = { msg ->
            messages.add(msg to true)
        }
    }

    // Observe connection change
    LaunchedEffect(Unit) {
        NearbyManager.onConnectionChanged = { connectionCount = it }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "Connected peers: $connectionCount")
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = userName,
                onValueChange = { userName = it },
                label = { Text("Your name") },
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = {
                if (userName.isNotBlank()) {
                    NearbyManager.startAdvertising(context, userName)
                }
            }) { Text("Advertise") }
            Spacer(modifier = Modifier.width(4.dp))
            Button(onClick = { NearbyManager.startDiscovery { /* connected */ } }) { Text("Discover") }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(messages) { (txt, incoming) ->
                Text(text = if (incoming) "Them: $txt" else "Me: $txt")
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = message,
                onValueChange = { message = it },
                label = { Text("Message") },
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = {
                if (message.isNotBlank()) {
                    NearbyManager.broadcast(message)
                    messages.add(message to false)
                    message = ""
                }
            }) { Text("Send") }
        }
    }
}