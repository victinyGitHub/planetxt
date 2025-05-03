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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import com.example.planetxt.CryptoUtil
import com.example.planetxt.ui.theme.PlanetxtTheme
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix

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
    val fetchedAnnouncements = remember { mutableStateListOf<String>() }
    var lastConnectionCount by remember { mutableStateOf(0) }

    val scope = rememberCoroutineScope()
    // Regex to split on commas that are not inside double quotes (handles quoted fields)
    val splitterRegex = Regex(""",(?=(?:[^"]*"[^"]*")*[^"]*$)""")

    fun parseCsvLine(line: String): List<String> {
        // Split on commas that are not inside quotes
        return splitterRegex.split(line).map { it.trim('"', ' ') }
    }

    fun broadcastAnnouncement(text: String, index: Int) {
        val payload = "ANN|$index|$text"
        NearbyManager.broadcast(payload, true)
    }

    LaunchedEffect(Unit) {
        NearbyManager.onConnectionChanged = { cnt ->
            connectionCount = cnt
            if (cnt > lastConnectionCount) {
                // New peer(s) connected – replay cached announcements
                fetchedAnnouncements.forEachIndexed { i, msg ->
                    broadcastAnnouncement(msg, i)
                }
            }
            lastConnectionCount = cnt
        }

        // Keep advertising forever, retry every 60s in case the system stops it
        while (true) {
            NearbyManager.startAdvertising(context, "Admin") { _, _ -> }
            delay(60_000)
        }
    }

    LaunchedEffect("announcement_poll") {
        val seenAnnouncements = mutableSetOf<String>()
        while (true) {
            try {
                val csv = withContext(Dispatchers.IO) {
                    URL("https://www.planetext.us/api/announcements/export").readText()
                }
                val lines = csv.lines().filter { it.isNotBlank() }
                lines.drop(1).forEach { line ->
                    if (seenAnnouncements.add(line)) {
                        fetchedAnnouncements.add(line)
                        broadcastAnnouncement(line, fetchedAnnouncements.lastIndex)
                        Log.i(ADMIN_TAG, "Broadcasted new announcement from server: $line")
                    }
                }
            } catch (e: Exception) {
                Log.e(ADMIN_TAG, "Announcement polling failed", e)
            }
            delay(30_000)
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
            scope.launch {
                csvStatus = "Fetching passenger CSV..."
                Log.i(ADMIN_TAG, "Starting CSV fetch...")
                try {
                    val csvText = withContext(Dispatchers.IO) {
                        URL("https://planetext.us/api/passengers/export").readText()
                    }
                    Log.i(ADMIN_TAG, "CSV fetched, size=${'$'}{csvText.length}")
                    val lines = csvText.lines().filter { it.isNotBlank() }
                    var sent = 0
                    lines.drop(1).forEachIndexed { index, line ->
                        if (line.isBlank()) return@forEachIndexed
                        val parts = parseCsvLine(line)
                        if (parts.size >= 14) {
                            val ln = parts[1]
                            val ref = parts[5]
                            val encryptedMessage = CryptoUtil.encryptMessage(line, ln, ref)
                            NearbyManager.broadcast(encryptedMessage)
                            sent++
                        } else {
                            Log.w(ADMIN_TAG, "CSV line $index malformed, parts=${'$'}{parts.size}: $line")
                        }
                    }
                    csvStatus = "Broadcasted $sent passengers from CSV"
                } catch (e: Exception) {
                    csvStatus = "Failed to broadcast CSV: ${'$'}{e.localizedMessage}"
                    Log.e(ADMIN_TAG, "Full error:", e)
                }
            }
        }) {
            Text("Fetch CSV & Broadcast Now")
        }
        if (csvStatus.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(csvStatus, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text("Server Announcements:")
        LazyColumn(modifier = Modifier.height(120.dp)) {
            items(fetchedAnnouncements) { txt -> Text(txt) }
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
                fetchedAnnouncements.add(announcement)
                broadcastAnnouncement(announcement, fetchedAnnouncements.lastIndex)
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
    val receivedAnnIds = remember { mutableSetOf<Int>() }
    var lastName by rememberSaveable { mutableStateOf("") }
    var bookingRef by rememberSaveable { mutableStateOf("") }
    var loggedIn by rememberSaveable { mutableStateOf(false) }
    var connectionCount by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        NearbyManager.onMessageReceived = listener@ { content, metadata ->
            // Handle versioned announcement payloads first
            if (content.startsWith("ANN|")) {
                val secondSep = content.indexOf('|', 4)
                if (secondSep > 4) {
                    val idPart = content.substring(4, secondSep)
                    val id = idPart.toIntOrNull()
                    val body = content.substring(secondSep + 1)
                    if (id != null && receivedAnnIds.add(id)) {
                        announcements.add(body)
                    }
                }
                return@listener
            }

            if (metadata.isAnnouncement) {
                announcements.add(content)
                return@listener
            }

            if (loggedIn) {
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

            // QR code for the booking
            val qrContent = "$bookingRef,$lastName"
            val qrBitmap = remember(qrContent) {
                generateQrBitmap(qrContent)
            }
            if (qrBitmap != null) {
                Image(bitmap = qrBitmap.asImageBitmap(), contentDescription = "QR Code", modifier = Modifier.size(200.dp).align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(16.dp))
            }

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

fun generateQrBitmap(content: String): android.graphics.Bitmap? {
    val writer = MultiFormatWriter()
    val matrix = writer.encode(content, BarcodeFormat.QR_CODE, 200, 200)
    val width = matrix.width
    val height = matrix.height
    val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.RGB_565)
    for (x in 0 until width) {
        for (y in 0 until height) {
            bitmap.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    return bitmap
}