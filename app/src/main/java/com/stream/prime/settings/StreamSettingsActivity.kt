package com.stream.prime.settings

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.stream.prime.R
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import android.widget.ImageView

class StreamSettingsActivity : AppCompatActivity(), SettingsManager.SettingsChangeListener {
    
    companion object {
        private const val TAG = "StreamSettings"
    }
    
    // UI Elements
    private lateinit var spinnerStreamingMode: Spinner
    private lateinit var spinnerStreamingService: Spinner
    private lateinit var tilStreamUrl: LinearLayout
    private lateinit var etStreamUrl: TextInputEditText
    private lateinit var etStreamKey: TextInputEditText
    private lateinit var spinnerResolution: Spinner
    private lateinit var spinnerFps: Spinner
    private lateinit var etBitrate: TextInputEditText
    private lateinit var btnSave: Button
    private lateinit var btnReset: Button
    private lateinit var btnBack: View
    private lateinit var btnHeaderSave: ImageView
    
    // State Management
    private var isUpdatingUI = false
    private var isSavingSettings = false
    
    // Settings Data
    private var currentStreamingMode = "Landscape"
    private var landscapeWidth = 1280
    private var landscapeHeight = 720
    private var landscapeFps = 30
    private var landscapeBitrate = 2500
    private var verticalWidth = 720
    private var verticalHeight = 1280
    private var verticalFps = 30
    private var verticalBitrate = 2000
    
    // Configuration Data
    private val streamingServices = listOf("YouTube Live", "Facebook Live", "Twitch", "Instagram Live", "TikTok Live", "Custom RTMP")
    private val streamingModes = listOf("Landscape", "Vertical")
    private val fpsOptions = listOf("30 FPS", "60 FPS")
    
    private val resolutionOptions = mapOf(
        "360p (640x360)" to Pair(640, 360),
        "480p (854x480)" to Pair(854, 480),
        "720p (1280x720)" to Pair(1280, 720),
        "1080p (1920x1080)" to Pair(1920, 1080)
    )
    
    private val verticalResolutionOptions = mapOf(
        "360p (360x640)" to Pair(360, 640),
        "480p (480x854)" to Pair(480, 854),
        "720p (720x1280)" to Pair(720, 1280),
        "1080p (1080x1920)" to Pair(1080, 1920)
    )
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stream_settings)
        
        Log.d(TAG, "=== SETTINGS ACTIVITY CREATED ===")
        
        initializeViews()
        setupSpinners()
        setupClickListeners()
        loadSettingsFromStorage()
        updateUI()
        
        // Register for settings changes
        SettingsManager.addListener(this)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        SettingsManager.removeListener(this)
        Log.d(TAG, "=== SETTINGS ACTIVITY DESTROYED ===")
    }
    
    override fun onSettingsChanged() {
        Log.d(TAG, "Settings changed notification received")
        
        // Only update UI if we're not currently saving
        if (!isSavingSettings) {
            Log.d(TAG, "Updating UI due to settings change")
            runOnUiThread {
                loadSettingsFromStorage()
                updateUI()
            }
        } else {
            Log.d(TAG, "Skipping UI update - currently saving settings")
        }
    }
    
    // ===== INITIALIZATION =====
    
    private fun initializeViews() {
        spinnerStreamingMode = findViewById(R.id.spinner_streaming_mode)
        spinnerStreamingService = findViewById(R.id.spinner_streaming_service)
        tilStreamUrl = findViewById(R.id.til_stream_url)
        etStreamUrl = findViewById(R.id.et_stream_url)
        etStreamKey = findViewById(R.id.et_stream_key)
        spinnerResolution = findViewById(R.id.spinner_resolution)
        spinnerFps = findViewById(R.id.spinner_fps)
        etBitrate = findViewById(R.id.et_bitrate)
        btnSave = findViewById(R.id.btn_save_settings)
        btnReset = findViewById(R.id.btn_reset)
        btnBack = findViewById(R.id.btn_back)
        btnHeaderSave = findViewById(R.id.btn_save)
    }
    
    private fun setupSpinners() {
        // Streaming Mode Spinner
        val modeAdapter = ArrayAdapter<String>(this, R.layout.spinner_item, streamingModes)
        modeAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinnerStreamingMode.adapter = modeAdapter
        spinnerStreamingMode.onItemSelectedListener = createModeListener()

        // Streaming Service Spinner
        val serviceAdapter = ArrayAdapter<String>(this, R.layout.spinner_item, streamingServices)
        serviceAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinnerStreamingService.adapter = serviceAdapter
        spinnerStreamingService.onItemSelectedListener = createServiceListener()

        // Resolution Spinner (will be updated based on mode)
        val resolutionAdapter = ArrayAdapter<String>(this, R.layout.spinner_item, resolutionOptions.keys.toList())
        resolutionAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinnerResolution.adapter = resolutionAdapter
        spinnerResolution.onItemSelectedListener = createResolutionListener()

        // FPS Spinner
        val fpsAdapter = ArrayAdapter<String>(this, R.layout.spinner_item, fpsOptions)
        fpsAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinnerFps.adapter = fpsAdapter
        spinnerFps.onItemSelectedListener = createFpsListener()
    }
    
    private fun setupClickListeners() {
        btnBack.setOnClickListener { finish() }
        
        btnSave.setOnClickListener {
            Log.d(TAG, "Save button clicked")
            saveAllSettingsAndGoBack()
        }
        
        btnHeaderSave.setOnClickListener {
            Log.d(TAG, "Header save button clicked")
            saveAllSettingsAndGoBack()
        }
        
        btnReset.setOnClickListener {
            Log.d(TAG, "Reset button clicked")
            showResetConfirmationDialog()
        }
    }
    
    // ===== LISTENER CREATION =====
    
    private fun createModeListener() = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
            if (isUpdatingUI) return
            
            val newMode = streamingModes[position]
            Log.d(TAG, "Mode selected: $newMode")
            
            if (currentStreamingMode != newMode) {
                currentStreamingMode = newMode
                
                // Update resolution spinner for the new mode
                val resolutionOptions = if (currentStreamingMode == "Landscape") {
                    this@StreamSettingsActivity.resolutionOptions
                } else {
                    this@StreamSettingsActivity.verticalResolutionOptions
                }
                
                val resolutionAdapter = ArrayAdapter<String>(this@StreamSettingsActivity, R.layout.spinner_item, resolutionOptions.keys.toList())
                resolutionAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
                spinnerResolution.adapter = resolutionAdapter
                
                updateUI()
                saveSettings()
            }
        }
        
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
    }

    private fun createServiceListener() = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
            if (isUpdatingUI) return

            val selectedService = streamingServices[position]
            Log.d(TAG, "Streaming service selected: $selectedService")

            if (selectedService == "Custom RTMP") {
                // Show both URL and Key fields for Custom RTMP
                tilStreamUrl.visibility = View.VISIBLE
                val existingUrl = SettingsManager.getCustomStreamUrl(this@StreamSettingsActivity)
                val existingKey = SettingsManager.getStreamKeyForService(this@StreamSettingsActivity, selectedService)
                etStreamUrl.setText(existingUrl)
                etStreamKey.setText(existingKey)
                Log.d(TAG, "Custom RTMP selected - loaded URL: '$existingUrl', Key: '$existingKey'")
            } else if (selectedService == "YouTube Live" || selectedService == "Facebook Live" || selectedService == "Twitch" || selectedService == "Instagram Live" || selectedService == "TikTok Live") {
                // Hide URL field, show only Key field for supported platforms
                tilStreamUrl.visibility = View.GONE
                etStreamUrl.setText("") // Clear URL for non-Custom services
                val existingKey = SettingsManager.getStreamKeyForService(this@StreamSettingsActivity, selectedService)
                etStreamKey.setText(existingKey)
                Log.d(TAG, "$selectedService selected - loaded Key: '$existingKey'")
            } else {
                // Hide both fields for other services
                tilStreamUrl.visibility = View.GONE
                etStreamUrl.setText("")
                etStreamKey.setText("")
                Log.d(TAG, "Default streaming service selected - cleared URL and key")
            }
            saveSettings()
        }

        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
    }

    private fun createResolutionListener() = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
            if (isUpdatingUI) return

            val resolutionOptions = if (currentStreamingMode == "Landscape") {
                this@StreamSettingsActivity.resolutionOptions
            } else {
                this@StreamSettingsActivity.verticalResolutionOptions
            }
            
            val resolutionNames = resolutionOptions.keys.toList()
            if (position < resolutionNames.size) {
                val selectedResolution = resolutionNames[position]
                val (width, height) = resolutionOptions[selectedResolution] ?: Pair(1280, 720)
                
                Log.d(TAG, "Resolution selected: $selectedResolution (${width}x${height})")
                
                if (currentStreamingMode == "Landscape") {
                    landscapeWidth = width
                    landscapeHeight = height
                } else {
                    verticalWidth = width
                    verticalHeight = height
                }
                saveSettings()
            }
        }

        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
    }

    private fun createFpsListener() = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
            if (isUpdatingUI) return

            val selectedFps = fpsOptions[position]
            val newFps = if (selectedFps == "30 FPS") 30 else 60
            
            Log.d(TAG, "FPS selected: $selectedFps ($newFps)")

            if (currentStreamingMode == "Landscape") {
                if (landscapeFps != newFps) {
                    landscapeFps = newFps
                    saveSettings()
                }
            } else {
                if (verticalFps != newFps) {
                    verticalFps = newFps
                    saveSettings()
                }
            }
        }

        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
    }
    
    // ===== SETTINGS MANAGEMENT =====
    
    private fun loadSettingsFromStorage() {
        Log.d(TAG, "=== LOADING SETTINGS FROM STORAGE ===")
        
        // Load RTMP settings
        val savedService = SettingsManager.getStreamingService(this)
        val savedUrl = SettingsManager.getStreamUrl(this)
        val savedKey = SettingsManager.getStreamKey(this)
        Log.d(TAG, "Loaded streaming service: $savedService")
        Log.d(TAG, "Loaded stream URL: '$savedUrl'")
        Log.d(TAG, "Loaded stream key: '$savedKey'")
        
        // Load streaming mode
        currentStreamingMode = SettingsManager.getStreamingMode(this)
        Log.d(TAG, "Loaded streaming mode: $currentStreamingMode")
        
        // Load quality settings
        landscapeWidth = SettingsManager.getLandscapeWidth(this)
        landscapeHeight = SettingsManager.getLandscapeHeight(this)
        landscapeFps = SettingsManager.getLandscapeFps(this)
        landscapeBitrate = SettingsManager.getLandscapeBitrate(this)
        
        verticalWidth = SettingsManager.getVerticalWidth(this)
        verticalHeight = SettingsManager.getVerticalHeight(this)
        verticalFps = SettingsManager.getVerticalFps(this)
        verticalBitrate = SettingsManager.getVerticalBitrate(this)
        
        Log.d(TAG, "Settings loaded successfully")
    }
    
    private fun updateUI() {
        if (isFinishing || isDestroyed) return
        
        Log.d(TAG, "=== UPDATING UI ===")
        isUpdatingUI = true
        
        try {
            // Update streaming mode spinner
            val modeIndex = streamingModes.indexOf(currentStreamingMode)
            if (modeIndex >= 0) {
                spinnerStreamingMode.setSelection(modeIndex)
                Log.d(TAG, "Set streaming mode spinner to index $modeIndex ($currentStreamingMode)")
            }

            // Update streaming service spinner
            val serviceIndex = streamingServices.indexOf(SettingsManager.getStreamingService(this))
            if (serviceIndex >= 0) {
                spinnerStreamingService.setSelection(serviceIndex)
                Log.d(TAG, "Set streaming service spinner to index $serviceIndex (${SettingsManager.getStreamingService(this)})")
            }

            // Update stream URL and key visibility
            val selectedService = SettingsManager.getStreamingService(this)
            if (selectedService == "Custom RTMP") {
                tilStreamUrl.visibility = View.VISIBLE
                etStreamUrl.setText(SettingsManager.getCustomStreamUrl(this))
                etStreamKey.setText(SettingsManager.getStreamKeyForService(this, selectedService))
                Log.d(TAG, "Custom RTMP settings visible, URL: ${SettingsManager.getCustomStreamUrl(this)}, Key: ${SettingsManager.getStreamKeyForService(this, selectedService)}")
            } else if (selectedService == "YouTube Live" || selectedService == "Facebook Live" || selectedService == "Twitch" || selectedService == "Instagram Live" || selectedService == "TikTok Live") {
                tilStreamUrl.visibility = View.GONE
                etStreamUrl.setText("") // Clear URL for non-Custom services
                etStreamKey.setText(SettingsManager.getStreamKeyForService(this, selectedService))
                Log.d(TAG, "$selectedService settings visible, Key: ${SettingsManager.getStreamKeyForService(this, selectedService)}")
            } else {
                tilStreamUrl.visibility = View.GONE
                etStreamUrl.setText("")
                etStreamKey.setText("")
                Log.d(TAG, "Default streaming service selected, URL and key hidden")
            }
            
            // Update resolution spinner for current mode
            val resolutionOptions = if (currentStreamingMode == "Landscape") {
                this.resolutionOptions
            } else {
                this.verticalResolutionOptions
            }
            
            // Update resolution adapter for current mode
            val resolutionAdapter = ArrayAdapter<String>(this, R.layout.spinner_item, resolutionOptions.keys.toList())
            resolutionAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
            spinnerResolution.adapter = resolutionAdapter
            
            // Set current resolution selection
            val currentWidth = if (currentStreamingMode == "Landscape") landscapeWidth else verticalWidth
            val currentHeight = if (currentStreamingMode == "Landscape") landscapeHeight else verticalHeight
            
            val resolutionKey = resolutionOptions.entries.find { it.value == Pair(currentWidth, currentHeight) }?.key
            if (resolutionKey != null) {
                val resolutionIndex = resolutionOptions.keys.toList().indexOf(resolutionKey)
                if (resolutionIndex >= 0) {
                    spinnerResolution.setSelection(resolutionIndex)
                    Log.d(TAG, "Set resolution spinner to index $resolutionIndex ($resolutionKey)")
                }
            }
            
            // Update FPS spinner
            val currentFps = if (currentStreamingMode == "Landscape") landscapeFps else verticalFps
            val fpsIndex = if (currentFps == 30) 0 else 1
            spinnerFps.setSelection(fpsIndex)
            Log.d(TAG, "Set FPS spinner to index $fpsIndex ($currentFps)")
            
            // Update bitrate input field
            val currentBitrate = if (currentStreamingMode == "Landscape") landscapeBitrate else verticalBitrate
            etBitrate.setText(currentBitrate.toString())
            
            Log.d(TAG, "UI updated successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating UI: ${e.message}")
        } finally {
            isUpdatingUI = false
        }
    }
    
    private fun saveSettings() {
        if (isSavingSettings) {
            Log.d(TAG, "Already saving settings, skipping")
            return
        }
        
        Log.d(TAG, "=== SAVING SETTINGS ===")
        isSavingSettings = true
        
        try {
            // Start batch update
            SettingsManager.startBatchUpdate()
            
            // Save streaming mode
            SettingsManager.setStreamingMode(this, currentStreamingMode)

            // Save streaming service
            val selectedService = streamingServices[spinnerStreamingService.selectedItemPosition]
            val previousService = SettingsManager.getStreamingService(this)
            SettingsManager.setStreamingService(this, selectedService)

            // Save stream URL and key if custom RTMP is selected
            if (selectedService == "Custom RTMP") {
                SettingsManager.setCustomStreamUrl(this, etStreamUrl.text.toString())
                SettingsManager.setStreamKeyForService(this, selectedService, etStreamKey.text.toString())
                Log.d(TAG, "Saving custom RTMP URL: ${etStreamUrl.text.toString()}, Key: ${etStreamKey.text.toString()}")
            } else if (selectedService == "YouTube Live" || selectedService == "Facebook Live" || selectedService == "Twitch" || selectedService == "Instagram Live" || selectedService == "TikTok Live") {
                // Save stream key for supported platforms
                val streamKey = etStreamKey.text.toString()
                SettingsManager.setStreamKeyForService(this, selectedService, streamKey)
                Log.d(TAG, "Saving stream key for $selectedService: '$streamKey'")
            } else {
                // Keep existing values for other services
                Log.d(TAG, "Using default streaming service, keeping existing values")
            }
            
            // Save quality settings
            SettingsManager.setLandscapeWidth(this, landscapeWidth)
            SettingsManager.setLandscapeHeight(this, landscapeHeight)
            SettingsManager.setLandscapeFps(this, landscapeFps)
            SettingsManager.setLandscapeBitrate(this, landscapeBitrate)
            
            SettingsManager.setVerticalWidth(this, verticalWidth)
            SettingsManager.setVerticalHeight(this, verticalHeight)
            SettingsManager.setVerticalFps(this, verticalFps)
            SettingsManager.setVerticalBitrate(this, verticalBitrate)
            
            Log.d(TAG, "Settings saved successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving settings: ${e.message}")
        } finally {
            SettingsManager.endBatchUpdate()
            isSavingSettings = false
        }
    }
    
    private fun saveAllSettings() {
        Log.d(TAG, "=== SAVING ALL SETTINGS ===")
        Log.d(TAG, "Current stream key in UI: ${etStreamKey.text}")
        Log.d(TAG, "Current stream URL in UI: ${etStreamUrl.text}")
        isSavingSettings = true
        
        try {
            // Start batch update
            SettingsManager.startBatchUpdate()
            
            // Save streaming mode
            SettingsManager.setStreamingMode(this, currentStreamingMode)

            // Save streaming service
            val selectedService = streamingServices[spinnerStreamingService.selectedItemPosition]
            val previousService = SettingsManager.getStreamingService(this)
            SettingsManager.setStreamingService(this, selectedService)

            // Save stream URL and key if custom RTMP is selected
            if (selectedService == "Custom RTMP") {
                val streamUrl = etStreamUrl.text.toString()
                val streamKey = etStreamKey.text.toString()
                SettingsManager.setCustomStreamUrl(this, streamUrl)
                SettingsManager.setStreamKeyForService(this, selectedService, streamKey)
                Log.d(TAG, "Saving custom RTMP URL: '$streamUrl', Key: '$streamKey'")
            } else if (selectedService == "YouTube Live" || selectedService == "Facebook Live" || selectedService == "Twitch" || selectedService == "Instagram Live" || selectedService == "TikTok Live") {
                // Save stream key for supported platforms
                val streamKey = etStreamKey.text.toString()
                SettingsManager.setStreamKeyForService(this, selectedService, streamKey)
                Log.d(TAG, "Saving stream key for $selectedService: '$streamKey'")
            } else {
                // Keep existing values for other services
                Log.d(TAG, "Using default streaming service, keeping existing values")
            }
            
            // Get bitrate from input field
            val bitrate = etBitrate.text.toString().toIntOrNull() ?: 2500
            
            // Save quality settings based on current mode
            if (currentStreamingMode == "Landscape") {
                SettingsManager.setLandscapeWidth(this, landscapeWidth)
                SettingsManager.setLandscapeHeight(this, landscapeHeight)
                SettingsManager.setLandscapeFps(this, landscapeFps)
                SettingsManager.setLandscapeBitrate(this, bitrate)
            } else {
                SettingsManager.setVerticalWidth(this, verticalWidth)
                SettingsManager.setVerticalHeight(this, verticalHeight)
                SettingsManager.setVerticalFps(this, verticalFps)
                SettingsManager.setVerticalBitrate(this, bitrate)
            }
            
            Log.d(TAG, "Settings saved successfully")
            
            // Show success toast
            android.widget.Toast.makeText(this, "Settings saved! Mode: $currentStreamingMode", android.widget.Toast.LENGTH_SHORT).show()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error saving settings: ${e.message}")
            android.widget.Toast.makeText(this, "Error saving settings", android.widget.Toast.LENGTH_SHORT).show()
        } finally {
            SettingsManager.endBatchUpdate()
            isSavingSettings = false
            Log.d(TAG, "Save process completed, isSavingSettings set to false")
        }
    }
    
    private fun saveAllSettingsAndGoBack() {
        Log.d(TAG, "=== SAVING ALL SETTINGS AND GOING BACK ===")
        Log.d(TAG, "Current stream key in UI: ${etStreamKey.text}")
        Log.d(TAG, "Current stream URL in UI: ${etStreamUrl.text}")
        isSavingSettings = true
        
        try {
            // Start batch update
            SettingsManager.startBatchUpdate()
            
            // Save streaming mode
            SettingsManager.setStreamingMode(this, currentStreamingMode)

            // Save streaming service
            val selectedService = streamingServices[spinnerStreamingService.selectedItemPosition]
            val previousService = SettingsManager.getStreamingService(this)
            SettingsManager.setStreamingService(this, selectedService)

            // Save stream URL and key if custom RTMP is selected
            if (selectedService == "Custom RTMP") {
                val streamUrl = etStreamUrl.text.toString()
                val streamKey = etStreamKey.text.toString()
                SettingsManager.setCustomStreamUrl(this, streamUrl)
                SettingsManager.setStreamKeyForService(this, selectedService, streamKey)
                Log.d(TAG, "Saving custom RTMP URL: '$streamUrl', Key: '$streamKey'")
            } else if (selectedService == "YouTube Live" || selectedService == "Facebook Live" || selectedService == "Twitch" || selectedService == "Instagram Live" || selectedService == "TikTok Live") {
                // Save stream key for supported platforms
                val streamKey = etStreamKey.text.toString()
                SettingsManager.setStreamKeyForService(this, selectedService, streamKey)
                Log.d(TAG, "Saving stream key for $selectedService: '$streamKey'")
            } else {
                // Keep existing values for other services
                Log.d(TAG, "Using default streaming service, keeping existing values")
            }
            
            // Get bitrate from input field
            val bitrate = etBitrate.text.toString().toIntOrNull() ?: 2500
            
            // Save quality settings based on current mode
            if (currentStreamingMode == "Landscape") {
                SettingsManager.setLandscapeWidth(this, landscapeWidth)
                SettingsManager.setLandscapeHeight(this, landscapeHeight)
                SettingsManager.setLandscapeFps(this, landscapeFps)
                SettingsManager.setLandscapeBitrate(this, bitrate)
            } else {
                SettingsManager.setVerticalWidth(this, verticalWidth)
                SettingsManager.setVerticalHeight(this, verticalHeight)
                SettingsManager.setVerticalFps(this, verticalFps)
                SettingsManager.setVerticalBitrate(this, bitrate)
            }
            
            Log.d(TAG, "Settings saved successfully")
            
            // Show success toast
            android.widget.Toast.makeText(this, "Settings saved! Mode: $currentStreamingMode", android.widget.Toast.LENGTH_SHORT).show()
            
            // Go back to previous screen
            finish()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error saving settings: ${e.message}")
            android.widget.Toast.makeText(this, "Error saving settings", android.widget.Toast.LENGTH_SHORT).show()
        } finally {
            SettingsManager.endBatchUpdate()
            isSavingSettings = false
            Log.d(TAG, "Save process completed, isSavingSettings set to false")
        }
    }
    
    private fun resetToDefaults() {
        Log.d(TAG, "=== RESETTING TO DEFAULTS ===")
        
        SettingsManager.resetToDefaults(this)
        loadSettingsFromStorage()
        updateUI()
        
        android.widget.Toast.makeText(this, "Settings reset to defaults", android.widget.Toast.LENGTH_SHORT).show()
    }
    
    private fun showResetConfirmationDialog() {
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("Confirm Reset")
        builder.setMessage("Are you sure you want to reset all settings to defaults?")
        builder.setPositiveButton("Yes") { _, _ ->
            resetToDefaults()
        }
        builder.setNegativeButton("No") { _, _ ->
            // User cancelled reset
        }
        builder.show()
    }
    
    // ===== UTILITY METHODS =====
    
    private fun showNotification(message: String) {
        try {
            if (!isFinishing && !isDestroyed) {
                // Create a simple notification view
                val textView = android.widget.TextView(this).apply {
                    text = message
                    setTextColor(android.graphics.Color.WHITE)
                    setBackgroundColor(android.graphics.Color.parseColor("#FF4081"))
                    setPadding(32, 16, 32, 16)
                    gravity = android.view.Gravity.CENTER
                    alpha = 0f
                }
                
                val rootView = findViewById<android.view.ViewGroup>(android.R.id.content)
                rootView.addView(textView)
                
                textView.animate()
                    .alpha(1f)
                    .setDuration(300)
                    .withEndAction {
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            textView.animate()
                                .alpha(0f)
                                .setDuration(300)
                                .withEndAction {
                                    rootView.removeView(textView)
                                }
                                .start()
                        }, 2000)
                    }
                    .start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing notification: ${e.message}")
        }
    }
} 