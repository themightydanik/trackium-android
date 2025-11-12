package tech.trackium.companion

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * Trackium Location Service для Android
 * Отслеживает геолокацию в фоновом режиме и отправляет в Minima MiniDapp
 */
class TrackiumLocationService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    
    private var deviceId: String? = null
    private var minimaNodeUrl: String = "http://127.0.0.1:9003"
    
    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "trackium_location"
        private const val UPDATE_INTERVAL_MS = 180000L // 3 minutes
        private const val FASTEST_UPDATE_INTERVAL_MS = 60000L // 1 minute
    }
    
    override fun onCreate() {
        super.onCreate()
        
        // Загрузить конфигурацию
        loadConfig()
        
        // Создать notification channel
        createNotificationChannel()
        
        // Показать foreground notification
        startForeground(NOTIFICATION_ID, createNotification("Starting..."))
        
        // Инициализировать location client
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        // Настроить location callback
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    handleLocationUpdate(location)
                }
            }
        }
        
        // Запустить отслеживание
        startLocationTracking()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopLocationTracking()
        serviceScope.cancel()
    }
    
    /**
     * Загрузить конфигурацию из SharedPreferences
     */
    private fun loadConfig() {
        val prefs = getSharedPreferences("trackium_config", Context.MODE_PRIVATE)
        deviceId = prefs.getString("device_id", null)
        minimaNodeUrl = prefs.getString("minima_node_url", "http://127.0.0.1:9003") ?: "http://127.0.0.1:9003"
    }
    
    /**
     * Создать notification channel (Android 8+)
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Trackium Location Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background location tracking for Trackium"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * Создать notification
     */
    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 
            0, 
            intent, 
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Trackium Location Active")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_location)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    /**
     * Обновить notification
     */
    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(text))
    }
    
    /**
     * Запустить отслеживание локации
     */
    private fun startLocationTracking() {
        // Проверить разрешения
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            updateNotification("Location permission not granted")
            return
        }
        
        // Настроить location request
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            UPDATE_INTERVAL_MS
        ).apply {
            setMinUpdateIntervalMillis(FASTEST_UPDATE_INTERVAL_MS)
            setWaitForAccurateLocation(false)
        }.build()
        
        // Начать получение обновлений
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
        
        updateNotification("Tracking location...")
    }
    
    /**
     * Остановить отслеживание
     */
    private fun stopLocationTracking() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }
    
    /**
     * Обработать обновление локации
     */
    private fun handleLocationUpdate(location: Location) {
        val lat = location.latitude
        val lng = location.longitude
        val accuracy = location.accuracy
        
        updateNotification("Location: ${lat.format(6)}, ${lng.format(6)}")
        
        // Отправить в MiniDapp
        if (deviceId != null) {
            sendLocationToMiniDapp(
                deviceId!!,
                lat,
                lng,
                accuracy.toDouble(),
                location.altitude,
                location.speed.toDouble()
            )
        } else {
            updateNotification("No Device ID configured")
        }
    }
    
    /**
     * Отправить локацию в MiniDapp
     */
    private fun sendLocationToMiniDapp(
        deviceId: String,
        latitude: Double,
        longitude: Double,
        accuracy: Double,
        altitude: Double,
        speed: Double
    ) {
        serviceScope.launch {
            try {
                val json = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("latitude", latitude)
                    put("longitude", longitude)
                    put("accuracy", accuracy)
                    put("altitude", altitude)
                    put("speed", speed)
                    put("timestamp", System.currentTimeMillis())
                    put("source", "android-companion")
                }
                
                val body = json.toString()
                    .toRequestBody("application/json".toMediaType())
                
                val request = Request.Builder()
                    .url("$minimaNodeUrl/api/location/update")
                    .post(body)
                    .build()
                
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        withContext(Dispatchers.Main) {
                            updateNotification("Location uploaded ✓")
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            updateNotification("Upload failed: ${response.code}")
                        }
                    }
                }
                
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    updateNotification("Network error: ${e.message}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    updateNotification("Error: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Extension для форматирования Double
     */
    private fun Double.format(decimals: Int): String {
        return "%.${decimals}f".format(this)
    }
}
