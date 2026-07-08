package com.stream.prime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.stream.prime.R
import com.stream.prime.databinding.ActivityFileStreamBinding
import com.stream.prime.file.FileStreamingActivity
import com.stream.prime.settings.StreamSettingsActivity

class FileStreamActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityFileStreamBinding
    private var selectedVideoUri: Uri? = null
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            openFilePicker()
        } else {
            Toast.makeText(this, "Storage permission required to select video files", Toast.LENGTH_LONG).show()
        }
    }
    
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        android.util.Log.d("FileStreamActivity", "File picker result: $uri")
        uri?.let {
            selectedVideoUri = it
            binding.tvSelectedFile.text = "Video file selected"
            binding.btnStartStream.isEnabled = true
            Toast.makeText(this, "Video file selected successfully", Toast.LENGTH_SHORT).show()
            android.util.Log.d("FileStreamActivity", "Video file selected: $it")
        } ?: run {
            android.util.Log.d("FileStreamActivity", "No file selected")
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileStreamBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupUI()
        setupClickListeners()
    }
    
    private fun setupUI() {
        binding.btnStartStream.isEnabled = false
        binding.tvSelectedFile.text = "No file selected"
    }
    
    private fun setupClickListeners() {
        binding.btnSelectFile.setOnClickListener {
            checkPermissionAndPickFile()
        }
        
        binding.btnStartStream.setOnClickListener {
            startFileStreaming()
        }
        
        binding.btnBack.setOnClickListener {
            finish()
        }
        
        binding.btnSettings.setOnClickListener {
            val intent = Intent(this, StreamSettingsActivity::class.java)
            startActivity(intent)
        }
    }
    
    private fun checkPermissionAndPickFile() {
        // For Android 11+ (API 30+), READ_EXTERNAL_STORAGE is not required for GetContent()
        // The GetContent() contract handles file access automatically
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // Android 11+ - no permission needed for GetContent()
            openFilePicker()
        } else {
            // Android 10 and below - check for READ_EXTERNAL_STORAGE permission
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED -> {
                    openFilePicker()
                }
                shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE) -> {
                    // Show explanation to user
                    Toast.makeText(this, "Storage permission is needed to access video files", Toast.LENGTH_LONG).show()
                    requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
                else -> {
                    // Request permission
                    requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
        }
    }
    
    private fun openFilePicker() {
        android.util.Log.d("FileStreamActivity", "Opening file picker for video files")
        filePickerLauncher.launch("video/*")
    }
    
    private fun startFileStreaming() {
        selectedVideoUri?.let { uri ->
            android.util.Log.d("FileStreamActivity", "Starting file streaming with URI: $uri")
            val intent = Intent(this, FileStreamingActivity::class.java).apply {
                putExtra(FileStreamingActivity.EXTRA_VIDEO_URI, uri.toString())
            }
            startActivity(intent)
        } ?: run {
            Toast.makeText(this, "Please select a video file first", Toast.LENGTH_SHORT).show()
        }
    }
} 