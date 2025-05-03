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

        getClient(context).startAdvertising(
            userName,
            SERVICE_ID,
            connectionLifecycleCallback(onAdvertisingResult),
            advertisingOptions
        ).addOnSuccessListener {
            Log.i(TAG, "Advertising started: userName=$userName")
            isAdvertising = true
        }.addOnFailureListener { e ->
            Log.e(TAG, "Advertising failed", e)
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

        connectionsClient.startDiscovery(
            SERVICE_ID,
            object : EndpointDiscoveryCallback() {
                override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                    Log.i(TAG, "Endpoint found: id=$endpointId, name=${info.endpointName}")
                    connectionsClient.requestConnection(
                        "receiver",
                        endpointId,
                        connectionLifecycleCallback { success, _ ->
                            if (success) onEndpointConnected(endpointId)
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
            isDiscovering = true
        }.addOnFailureListener { e ->
            Log.e(TAG, "Discovery failed", e)
        }
    }

    /** Stop all ongoing Nearby activities and disconnect from peers. */
    fun stopAll() {
        Log.i(TAG, "Stopping all Nearby activities")
        connectionsClient.stopAllEndpoints()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        isAdvertising = false
        isDiscovering = false
        connectedEndpoints.clear()
        seenPacketIds.clear()
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

            if (connectedEndpoints.isEmpty()) {
                isDiscovering = false
                Log.d(TAG, "All peers gone, restarting discovery")
                startDiscovery()
            }
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
