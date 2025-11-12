package tech.trackium.companion

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private var deviceId by mutableStateOf("")
    private var minimaNodeUrl by mutableStateOf("http://127.0.0.1:9003")
    private var serviceRunning by mutableStateOf(false)
    
    // Permission launcher
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        when {
            permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true -> {
                // Permission granted
                startLocationService()
            }
            else -> {
                Toast.makeText(this, "Location permission required", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Load config
        loadConfig()
        
        setContent {
            MaterialTheme {
                TrackiumCompanionUI()
            }
        }
    }
    
    @Composable
    fun TrackiumCompanionUI() {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Trackium Companion") }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                
                // Header
                Text(
                    text = "📍 Location Tracking",
                    style = MaterialTheme.typography.headlineMedium
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Device ID Input
                OutlinedTextField(
                    value = deviceId,
                    onValueChange = { deviceId = it },
                    label = { Text("Device ID") },
                    placeholder = { Text("TRACK-XXX-YYY") },
                    modifier = Modifier.fillMaxWidth()
                )
                
                // Minima Node URL
                OutlinedTextField(
                    value = minimaNodeUrl,
                    onValueChange = { minimaNodeUrl = it },
                    label = { Text("Minima Node URL") },
                    placeholder = { Text("http://127.0.0.1:9003") },
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Service Status
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (serviceRunning) 
                            MaterialTheme.colorScheme.primaryContainer 
                        else 
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = if (serviceRunning) "✅ Service Active" else "⭕ Service Stopped",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (serviceRunning) 
                                "Location tracking is running in background" 
                            else 
                                "Start the service to begin tracking",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                
                // Start/Stop Button
                Button(
                    onClick = { toggleService() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (serviceRunning) 
                            MaterialTheme.colorScheme.error 
                        else 
                            MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = if (serviceRunning) "🛑 Stop Service" else "▶️ Start Service",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                
                Spacer(modifier = Modifier.weight(1f))
                
                // Info
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "ℹ️ How it works:",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "• Enter your Device ID from Trackium MiniDapp\n" +
                                   "• Service runs in background\n" +
                                   "• Updates location every 3 minutes\n" +
                                   "• Minimal battery usage",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
    
    /**
     * Toggle location service
     */
    private fun toggleService() {
        if (serviceRunning) {
            stopLocationService()
        } else {
            if (deviceId.isBlank()) {
                Toast.makeText(this, "Please enter Device ID", Toast.LENGTH_SHORT).show()
                return
            }
            
            // Save config
            saveConfig()
            
            // Check permissions
            if (hasLocationPermission()) {
                startLocationService()
            } else {
                requestLocationPermission()
            }
        }
    }
    
    /**
     * Check if location permission granted
     */
    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Request location permission
     */
    private fun requestLocationPermission() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        
        // Add notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        locationPermissionLauncher.launch(permissions.toTypedArray())
    }
    
    /**
     * Start location service
     */
    private fun startLocationService() {
        val intent = Intent(this, TrackiumLocationService::class.java)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        serviceRunning = true
        Toast.makeText(this, "Location service started", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * Stop location service
     */
    private fun stopLocationService() {
        val intent = Intent(this, TrackiumLocationService::class.java)
        stopService(intent)
        
        serviceRunning = false
        Toast.makeText(this, "Location service stopped", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * Load config from SharedPreferences
     */
    private fun loadConfig() {
        val prefs = getSharedPreferences("trackium_config", Context.MODE_PRIVATE)
        deviceId = prefs.getString("device_id", "") ?: ""
        minimaNodeUrl = prefs.getString("minima_node_url", "http://127.0.0.1:9003") ?: "http://127.0.0.1:9003"
    }
    
    /**
     * Save config to SharedPreferences
     */
    private fun saveConfig() {
        val prefs = getSharedPreferences("trackium_config", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("device_id", deviceId)
            putString("minima_node_url", minimaNodeUrl)
            apply()
        }
    }
}
