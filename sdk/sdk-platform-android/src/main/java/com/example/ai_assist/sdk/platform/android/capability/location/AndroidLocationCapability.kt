package com.example.ai_assist.sdk.platform.android.capability.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.HandlerThread
import com.example.ai_assist.sdk.capability.LocationCapability
import com.example.ai_assist.sdk.capability.LocationSample
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** 使用 Android 可用的位置 provider 发布位置更新。 */
internal class AndroidLocationCapability(context: Context) : LocationCapability, LocationListener {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private val locationThread = HandlerThread("assist-sdk-location").apply { start() }
    private val lock = Any()
    private val mutableLocations = MutableSharedFlow<LocationSample>(
        extraBufferCapacity = LOCATION_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var activeProvider: String? = null
    private var closed = false

    override val locations: Flow<LocationSample> = mutableLocations.asSharedFlow()

    override fun isAvailable(): Boolean = synchronized(lock) {
        !closed && hasLocationPermission() && selectProvider() != null
    }

    override fun supportedFeatures(): Set<String> = setOf("location-updates")

    @SuppressLint("MissingPermission")
    override fun start(minUpdateIntervalMillis: Long) {
        require(minUpdateIntervalMillis > 0) { "minUpdateIntervalMillis must be positive" }
        synchronized(lock) {
            check(!closed) { "Location capability has been closed" }
            check(hasLocationPermission()) { "Location permission is not granted" }
            if (activeProvider != null) return
            val manager = checkNotNull(locationManager) { "Location manager is unavailable" }
            val provider = checkNotNull(selectProvider()) { "No enabled location provider is available" }
            manager.requestLocationUpdates(
                provider,
                minUpdateIntervalMillis,
                MIN_DISTANCE_METERS,
                this,
                locationThread.looper
            )
            activeProvider = provider
        }
    }

    override fun stop() {
        synchronized(lock) {
            if (activeProvider == null) return
            activeProvider = null
            runCatching { locationManager?.removeUpdates(this) }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        stop()
        locationThread.quitSafely()
    }

    override fun onLocationChanged(location: Location) {
        if (synchronized(lock) { activeProvider == null }) return
        mutableLocations.tryEmit(
            LocationSample(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
                capturedAtMillis = location.time
            )
        )
    }

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) = Unit

    @Deprecated("Deprecated in Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    private fun hasLocationPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasFineLocationPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun selectProvider(): String? {
        val providers = buildList {
            add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (hasFineLocationPermission()) add(LocationManager.GPS_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }
        return providers.firstOrNull { provider ->
            runCatching {
                locationManager?.allProviders?.contains(provider) == true &&
                    locationManager.isProviderEnabled(provider)
            }.getOrDefault(false)
        }
    }

    private companion object {
        const val LOCATION_BUFFER_CAPACITY = 16
        const val MIN_DISTANCE_METERS = 0f
    }
}
