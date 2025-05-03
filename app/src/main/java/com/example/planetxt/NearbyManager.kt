package com.example.planetxt

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*

/**
 * Helper object that wraps Google Nearby Connections API to provide simple
 * broadcast (advertising) + receive (discovery) capabilities for small
 * payloads.
 *
 * Call [init] once from your Application or first Activity and then use
 * [startAdvertising] / [startDiscovery] to establish connections.  Messages can
 * be broadcast to all currently connected peers via [broadcast].  Register for
 * incoming messages by assigning a lambda to [onMessageReceived].
 */
object NearbyManager {

    private const val SERVICE_ID = "com.example.planetxt.NEARBY_SERVICE"
    private const val TAG = "NearbyManager"

    private lateinit var connectionsClient: ConnectionsClient
    private val connectedEndpoints = mutableSetOf<String>()

    /** Emits the number of connected endpoints whenever it changes.  Set from UI layer. */
    var onConnectionChanged: ((Int) -> Unit)? = null

    /** Initialises the underlying [ConnectionsClient]. Must be called before any other method. */
    fun init(context: Context) {
        connectionsClient = Nearby.getConnectionsClient(context.applicationContext)
    }

    /**
     * Starts advertising so that other devices can discover this one.
     * @param context Android context
     * @param userName Name that is shown to remote devices
     * @param onAdvertisingResult Callback that returns success + optional msg
     */
    @JvmStatic
    fun startAdvertising(
        context: Context,
        userName: String,
        onAdvertisingResult: (Boolean, String?) -> Unit = { _, _ -> }
    ) {
        Log.d(TAG, "startAdvertising name=$userName")
        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        getClient(context).startAdvertising(
            userName,
            SERVICE_ID,
            connectionLifecycleCallback(onAdvertisingResult),
            advertisingOptions
        ).addOnSuccessListener {
            Log.d(TAG, "Advertising successfully started")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Advertising failed", e)
        }
    }

    /**
     * Starts discovery so that this device looks for advertisers and attempts
     * to connect to them automatically.
     * @param onEndpointConnected Callback with the endpoint id when connected.
     */
    @JvmStatic
    fun startDiscovery(onEndpointConnected: (String) -> Unit = {}) {
        Log.d(TAG, "startDiscovery")
        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        connectionsClient.startDiscovery(
            SERVICE_ID,
            object : EndpointDiscoveryCallback() {
                override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                    Log.d(TAG, "Endpoint found $endpointId -> requesting connection")
                    // Attempt to connect automatically
                    connectionsClient.requestConnection(
                        /* name= */ "receiver",
                        endpointId,
                        connectionLifecycleCallback { success, _ ->
                            if (success) onEndpointConnected(endpointId)
                        }
                    )
                }

                override fun onEndpointLost(endpointId: String) {
                    Log.d(TAG, "Endpoint lost $endpointId")
                }
            },
            discoveryOptions
        ).addOnSuccessListener { Log.d(TAG, "Discovery successfully started") }
            .addOnFailureListener { e -> Log.e(TAG, "Discovery failed", e) }
    }

    /** Broadcasts a string message to all currently connected endpoints. */
    @JvmStatic
    fun broadcast(message: String) {
        if (connectedEndpoints.isEmpty()) return
        Log.d(TAG, "broadcast to ${'$'}{connectedEndpoints.size} endpoints: $message")
        val payload = Payload.fromBytes(message.toByteArray())
        connectionsClient.sendPayload(connectedEndpoints.toList(), payload)
    }

    /** Stop all ongoing Nearby activities and disconnect from peers. */
    @JvmStatic
    fun stopAll() {
        Log.d(TAG, "stopAll()")
        connectionsClient.stopAllEndpoints()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectedEndpoints.clear()
    }

    /** Listener for incoming messages (as UTF-8 String). */
    var onMessageReceived: ((String) -> Unit)? = null

    // -----------------------------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------------------------

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
            // Immediately accept the connection and register our payload callback
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedEndpoints.add(endpointId)
                Log.d(TAG, "onConnectionResult SUCCESS -> $endpointId (total ${'$'}{connectedEndpoints.size})")
                onConnectionChanged?.invoke(connectedEndpoints.size)
                resultCallback(true, null)
            } else {
                Log.d(TAG, "onConnectionResult FAILURE -> ${'$'}{result.status.statusMessage}")
                resultCallback(false, result.status.statusMessage)
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            Log.d(TAG, "onDisconnected $endpointId (total ${'$'}{connectedEndpoints.size})")
            onConnectionChanged?.invoke(connectedEndpoints.size)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let { bytes ->
                val msg = bytes.toString(Charsets.UTF_8)
                Log.d(TAG, "payloadReceived from $endpointId: $msg")
                onMessageReceived?.invoke(msg)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // No-op for small payloads
        }
    }
}
