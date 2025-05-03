package com.example.planetxt

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.example.planetxt.CryptoUtil
import com.example.planetxt.ui.theme.PlanetxtTheme

private const val ADMIN_TAG = "AdminScreen"

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

    override fun onDestroy() {
        super.onDestroy()
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
    var lastName by remember { mutableStateOf("") }
    var bookingRef by remember { mutableStateOf("") }
    var connectionCount by remember { mutableStateOf(0) }
    var csvStatus by remember { mutableStateOf("") }
    var announcement by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()
    val csvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                try {
                    val lines = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readLines() ?: return@launch
                    var sent = 0
                    lines.forEach { line ->
                        val parts = line.split(',')
                        if (parts.size >= 3) {
                            val ln = parts[0].trim()
                            val ref = parts[1].trim()
                            val data = parts.drop(2).joinToString(",").trim()
                            Log.d(ADMIN_TAG, "Broadcast CSV line for $ln/$ref : $data")
                            val encryptedMessage = CryptoUtil.encryptMessage(data, ln, ref)
                            if (encryptedMessage != null) {
                                NearbyManager.broadcast(encryptedMessage)
                                sent++
                            }
                        }
                    }
                    csvStatus = "Broadcasted $sent boarding passes from CSV"
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    )

    LaunchedEffect(Unit) {
        NearbyManager.onConnectionChanged = { connectionCount = it }

        // Keep advertising forever, retry every 60s in case the system stops it
        while (true) {
            NearbyManager.startAdvertising(context, "Admin") { _, _ -> }
            delay(60_000)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "Admin Mode – Connected peers: $connectionCount")
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = message,
            onValueChange = { message = it },
            label = { Text("Broadcast message") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = lastName,
            onValueChange = { lastName = it },
            label = { Text("Passenger last name") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = bookingRef,
            onValueChange = { bookingRef = it },
            label = { Text("Booking ref") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = {
            if (message.isNotBlank()) {
                val encryptedMessage = CryptoUtil.encryptMessage(message, lastName, bookingRef)
                if (encryptedMessage != null) {
                    NearbyManager.broadcast(encryptedMessage)
                    message = ""
                    lastName = ""
                    bookingRef = ""
                }
            }
        }, modifier = Modifier.align(Alignment.End)) {
            Text("Send Manual Message")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = {
            // launch file picker for CSV
            csvLauncher.launch(arrayOf("text/*"))
        }) {
            Text("Import CSV & Broadcast Now")
        }
        if (csvStatus.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(csvStatus, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = announcement,
            onValueChange = { announcement = it },
            label = { Text("Announcement to all") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = {
            if (announcement.isNotBlank()) {
                NearbyManager.broadcast(announcement, true)
                announcement = ""
            }
        }, modifier = Modifier.align(Alignment.End)) {
            Text("Broadcast Announcement")
        }
    }
}

@Composable
fun UserScreen() {
    val context = LocalContext.current
    val messages = remember { mutableStateListOf<String>() }
    val announcements = remember { mutableStateListOf<String>() }
    var lastName by rememberSaveable { mutableStateOf("") }
    var bookingRef by rememberSaveable { mutableStateOf("") }
    var loggedIn by rememberSaveable { mutableStateOf(false) }
    var connectionCount by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        NearbyManager.onMessageReceived = { content, metadata ->
            if (metadata.isAnnouncement) {
                announcements.add(content)
            } else if (loggedIn) {
                val plain = if (lastName.isNotBlank() && bookingRef.isNotBlank())
                    CryptoUtil.decryptMessage(content, lastName, bookingRef) else null
                if (plain != null) messages.add(plain)
            }
        }
        NearbyManager.onConnectionChanged = { connectionCount = it }

        // Continuous discovery loop (retry every 30s)
        while (true) {
            NearbyManager.startDiscovery {}
            delay(30_000)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (!loggedIn) {
            OutlinedTextField(
                value = lastName,
                onValueChange = { lastName = it },
                label = { Text("Last name") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = bookingRef,
                onValueChange = { bookingRef = it },
                label = { Text("Booking ref") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                if (lastName.isNotBlank() && bookingRef.isNotBlank()) {
                    loggedIn = true
                }
            }, modifier = Modifier.align(Alignment.End)) {
                Text("Log In")
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Logged in as $lastName / $bookingRef", modifier = Modifier.weight(1f))
                Button(onClick = {
                    loggedIn = false
                    messages.clear()
                    announcements.clear()
                    lastName = ""
                    bookingRef = ""
                }) { Text("Log Out") }
            }

            Spacer(Modifier.height(16.dp))

            Text("Announcements:")
            LazyColumn(modifier = Modifier.height(120.dp)) {
                items(announcements) { txt -> Text(txt) }
            }

            Spacer(Modifier.height(16.dp))
            Text("Your messages:")
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(messages) { txt -> Text(text = txt) }
            }
        }
    }
}