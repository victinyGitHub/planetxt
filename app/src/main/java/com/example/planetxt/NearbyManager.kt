package com.example.planetxt

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.gson.Gson
import java.util.UUID

/**
 * Helper object that wraps Google Nearby Connections API to provide mesh networking
 * capabilities for small payloads with hop counting and deduplication.
 */
object NearbyManager {
    private const val SERVICE_ID = "com.example.planetxt.NEARBY_SERVICE"
    private const val TAG = "NearbyManager"
    private const val MAX_HOPS = 5  // Maximum number of hops before a packet dies

    private lateinit var connectionsClient: ConnectionsClient
    private val connectedEndpoints = mutableSetOf<String>()
    private val seenPacketIds = mutableSetOf<String>()  // Track seen packets by ID
    private var isDiscovering = false
    private var isAdvertising = false
    private val gson = Gson()

    /** Emits the number of connected endpoints whenever it changes. */
    var onConnectionChanged: ((Int) -> Unit)? = null

    /** Listener for incoming messages with metadata. */
    var onMessageReceived: ((String, PacketMetadata) -> Unit)? = null

    // Data classes for packet structure
    data class Packet(
        val metadata: PacketMetadata,
        val content: String
    )

    data class PacketMetadata(
        val id: String = UUID.randomUUID().toString(),
        val isAnnouncement: Boolean = false,
        var hopCount: Int = 0,
        val timestamp: Long = System.currentTimeMillis()
    )

    /** Initializes the underlying [ConnectionsClient]. Must be called before any other method. */
    fun init(context: Context) {
        connectionsClient = Nearby.getConnectionsClient(context.applicationContext)
        Log.i(TAG, "Initialized NearbyManager")
    }

    /**
     * Broadcasts a message to all connected peers with proper metadata.
     * @param content The message content
     * @param isAnnouncement Whether this is an announcement (to be rebroadcast)
     */
    fun broadcast(content: String, isAnnouncement: Boolean = false) {
        if (connectedEndpoints.isEmpty()) {
            Log.d(TAG, "No endpoints connected, cannot broadcast")
            return
        }

        val packet = Packet(
            metadata = PacketMetadata(isAnnouncement = isAnnouncement),
            content = content
        )
        
        Log.i(TAG, "Broadcasting new packet: id=${packet.metadata.id}, announcement=$isAnnouncement")
        broadcastPacket(packet)
    }

    private fun broadcastPacket(packet: Packet) {
        val json = gson.toJson(packet)
        val payload = Payload.fromBytes(json.toByteArray())
        
        for (endpoint in connectedEndpoints) {
            connectionsClient.sendPayload(endpoint, payload)
            Log.d(TAG, "Sent to endpoint $endpoint: packet=${packet.metadata.id}, hops=${packet.metadata.hopCount}")
        }
    }

    /** Starts advertising so other devices can discover this one. */
    fun startAdvertising(
        context: Context,
        userName: String,
        onAdvertisingResult: (Boolean, String?) -> Unit = { _, _ -> }
    ) {
        if (isAdvertising) {
            Log.d(TAG, "Already advertising")
            return
        }

        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        try {
            isAdvertising = true  // Mark as advertising BEFORE the async call
            getClient(context).startAdvertising(
                userName,
                SERVICE_ID,
                connectionLifecycleCallback(onAdvertisingResult),
                advertisingOptions
            ).addOnSuccessListener {
                Log.i(TAG, "Advertising started: userName=$userName")
            }.addOnFailureListener { e ->
                isAdvertising = false  // Reset flag on failure
                Log.e(TAG, "Advertising failed", e)
            }
        } catch (e: Exception) {
            isAdvertising = false  // Reset flag on exception
            Log.e(TAG, "Error starting advertising", e)
        }
    }

    /** Starts discovery to find other devices. */
    fun startDiscovery(onEndpointConnected: (String) -> Unit = {}) {
        if (isDiscovering) {
            Log.d(TAG, "Already discovering")
            return
        }

        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        try {
            isDiscovering = true  // Mark as discovering BEFORE the async call
            connectionsClient.startDiscovery(
                SERVICE_ID,
                object : EndpointDiscoveryCallback() {
                    override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                        Log.i(TAG, "Endpoint found: id=$endpointId, name=${info.endpointName}")
                        connectionsClient.requestConnection(
                            "User",
                            endpointId,
                            connectionLifecycleCallback { success, _ ->
                                if (success) {
                                    onEndpointConnected(endpointId)
                                }
                            }
                        )
                    }

                    override fun onEndpointLost(endpointId: String) {
                        Log.d(TAG, "Endpoint lost: $endpointId")
                    }
                },
                discoveryOptions
            ).addOnSuccessListener {
                Log.i(TAG, "Discovery started")
            }.addOnFailureListener { e ->
                isDiscovering = false  // Reset flag on failure
                Log.e(TAG, "Discovery failed", e)
            }
        } catch (e: Exception) {
            isDiscovering = false  // Reset flag on exception
            Log.e(TAG, "Error starting discovery", e)
        }
    }

    /** Stop all ongoing Nearby activities and disconnect from peers. */
    fun stopAll() {
        try {
            connectionsClient.stopAllEndpoints()
            stopAdvertising()
            stopDiscovery()
            connectedEndpoints.clear()
            onConnectionChanged?.invoke(0)
            Log.i(TAG, "All Nearby connections stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping all connections", e)
        }
    }

    /** Stops advertising if it is currently active. */
    fun stopAdvertising() {
        if (isAdvertising) {
            try {
                connectionsClient.stopAdvertising()
                isAdvertising = false
                Log.i(TAG, "Stopped advertising")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping advertising", e)
            }
        }
    }

    /** Stops discovery if it is currently active. */
    fun stopDiscovery() {
        if (isDiscovering) {
            try {
                connectionsClient.stopDiscovery()
                isDiscovering = false
                Log.i(TAG, "Stopped discovery")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping discovery", e)
            }
        }
    }

    /** Convenience wrapper – starts advertising only when not already advertising. */
    fun safeStartAdvertising(
        context: Context,
        userName: String,
        onAdvertisingResult: (Boolean, String?) -> Unit = { _, _ -> }
    ) {
        if (!isAdvertising) {
            startAdvertising(context, userName, onAdvertisingResult)
        }
    }

    /** Convenience wrapper – starts discovery only when not already discovering. */
    fun safeStartDiscovery(onEndpointConnected: (String) -> Unit = {}) {
        if (!isDiscovering) {
            startDiscovery(onEndpointConnected)
        }
    }

    private fun getClient(context: Context): ConnectionsClient {
        if (!::connectionsClient.isInitialized) {
            init(context)
        }
        return connectionsClient
    }

    private fun connectionLifecycleCallback(
        resultCallback: (Boolean, String?) -> Unit
    ): ConnectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            Log.i(TAG, "Connection initiated: endpoint=$endpointId, name=${connectionInfo.endpointName}")
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedEndpoints.add(endpointId)
                Log.i(TAG, "Connection successful: endpoint=$endpointId, total=${connectedEndpoints.size}")
                onConnectionChanged?.invoke(connectedEndpoints.size)
                resultCallback(true, null)
            } else {
                Log.w(TAG, "Connection failed: endpoint=$endpointId, status=${result.status.statusMessage}")
                resultCallback(false, result.status.statusMessage)
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            Log.i(TAG, "Endpoint disconnected: $endpointId, total=${connectedEndpoints.size}")
            onConnectionChanged?.invoke(connectedEndpoints.size)
            
            // Remove automatic restart to avoid race conditions
            // Let the periodic discovery in MainActivity handle reconnection
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let { bytes ->
                try {
                    val json = String(bytes, Charsets.UTF_8)
                    val packet = gson.fromJson(json, Packet::class.java)
                    
                    Log.i(TAG, "Received packet: id=${packet.metadata.id}, from=$endpointId, hops=${packet.metadata.hopCount}")

                    // Check if we've seen this packet before
                    if (seenPacketIds.contains(packet.metadata.id)) {
                        Log.d(TAG, "Duplicate packet ${packet.metadata.id}, ignoring")
                        return@let
                    }
                    seenPacketIds.add(packet.metadata.id)

                    // Notify listeners of the received message
                    onMessageReceived?.invoke(packet.content, packet.metadata)

                    // If it's an announcement and hasn't exceeded max hops, rebroadcast
                    if (packet.metadata.isAnnouncement && packet.metadata.hopCount < MAX_HOPS) {
                        val updatedPacket = packet.copy(
                            metadata = packet.metadata.copy(
                                hopCount = packet.metadata.hopCount + 1
                            )
                        )
                        Log.d(TAG, "Rebroadcasting packet ${packet.metadata.id}, hops=${updatedPacket.metadata.hopCount}")
                        broadcastPacket(updatedPacket)
                    }
                    
                    if (packet.metadata.hopCount >= MAX_HOPS) {
                        Log.d(TAG, "Packet ${packet.metadata.id} reached max hops (${packet.metadata.hopCount}), dropping")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing received payload", e)
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // No-op for small payloads
        }
    }
}
