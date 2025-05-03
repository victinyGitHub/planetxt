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
                AppScreen()
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

// Simple navigation enum
enum class Screen { SELECT_ROLE, ADMIN, USER }

@Composable
fun AppScreen() {
    var screen by remember { mutableStateOf(Screen.SELECT_ROLE) }
    when (screen) {
        Screen.SELECT_ROLE -> RoleSelectionScreen { screen = it }
        Screen.ADMIN -> AdminScreen()
        Screen.USER -> UserScreen()
    }
}

@Composable
fun RoleSelectionScreen(onRoleChosen: (Screen) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(onClick = { onRoleChosen(Screen.ADMIN) }) {
            Text("Admin Panel")
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = { onRoleChosen(Screen.USER) }) {
            Text("User Screen")
        }
    }
}

@Composable
fun AdminScreen() {
    val context = LocalContext.current
    var message by remember { mutableStateOf("") }
    var connectionCount by remember { mutableStateOf(0) }

    // Start advertising once
    LaunchedEffect(Unit) {
        NearbyManager.startAdvertising(context, "Admin") { _, _ -> }
        NearbyManager.onConnectionChanged = { connectionCount = it }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "Admin Mode – Connected peers: $connectionCount")
        Spacer(modifier = Modifier.height(16.dp))
        TextField(
            value = message,
            onValueChange = { message = it },
            label = { Text("Broadcast message") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = {
            if (message.isNotBlank()) {
                NearbyManager.broadcast(message)
                message = ""
            }
        }, modifier = Modifier.align(Alignment.End)) {
            Text("Send to All")
        }
    }
}

@Composable
fun UserScreen() {
    val context = LocalContext.current
    val messages = remember { mutableStateListOf<String>() }
    var connectionCount by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        NearbyManager.onMessageReceived = { msg ->
            messages.add(msg)
        }
        NearbyManager.onConnectionChanged = { connectionCount = it }
        NearbyManager.startDiscovery {}
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "User Mode – Connected to $connectionCount peer(s)")
        Spacer(modifier = Modifier.height(16.dp))
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(messages) { txt ->
                Text(text = txt)
            }
        }
    }
}