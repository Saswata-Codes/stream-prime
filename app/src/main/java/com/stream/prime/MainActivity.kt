/*
 * Copyright (C) 2024 pedroSG94.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.stream.prime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.stream.prime.databinding.ActivityMainBinding
import com.stream.prime.settings.StreamSettingsActivity
import android.util.Log
import android.os.Handler

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val OVERLAY_PERMISSION_REQUEST_CODE = 101
        private const val PREFS_NAME = "MainActivityPrefs"
        private const val KEY_PERMISSIONS_REQUESTED = "permissions_requested"
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.INTERNET,
            Manifest.permission.POST_NOTIFICATIONS
        )
    }

    private var overlayPermissionDialogShown = false
    private lateinit var overlayPermissionLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.mainVersionText.text = getString(R.string.version_format, BuildConfig.VERSION_NAME)

        // Register for activity result in onCreate
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                handleOverlayPermissionResult(result.resultCode)
            }
        }

        setupClickListeners()
        checkAndRequestPermissions()
    }

    private fun setupClickListeners() {
        binding.cardStartStream.setOnClickListener {
            val intent = Intent(this, UnifiedStreamActivity::class.java)
            startActivity(intent)
        }

        // File Streaming option - use view binding
        binding.cardFileStreaming.setOnClickListener {
            val intent = Intent(this, FileStreamActivity::class.java)
            startActivity(intent)
        }

        binding.cardSettings.setOnClickListener {
            val intent = Intent(this, StreamSettingsActivity::class.java)
            startActivity(intent)
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissions.isNotEmpty()) {
            // Request basic permissions directly without dialog
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE)
        } else {
            // All basic permissions granted, check overlay permission
            checkOverlayPermission()
        }
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!android.provider.Settings.canDrawOverlays(this) && !overlayPermissionDialogShown) {
                overlayPermissionDialogShown = true
                showOverlayPermissionDialog()
            }
        }
    }

    private fun showOverlayPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Draw Over Other Apps Permission")
            .setMessage("This app needs to draw over other apps to show streaming controls and status while you use other applications.\n\n" +
                    "This allows you to control streaming without switching back to the app.")
            .setPositiveButton("Grant Permission") { _, _ ->
                requestOverlayPermission()
            }
            .setNegativeButton("Not Now") { _, _ ->
                Toast.makeText(this, "You can enable this later in Settings", Toast.LENGTH_LONG).show()
            }
            .setCancelable(false)
            .show()
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (::overlayPermissionLauncher.isInitialized) {
                    overlayPermissionLauncher.launch(intent)
                } else {
                    // Fallback to deprecated method if launcher not initialized
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
                }
            } else {
                @Suppress("DEPRECATION")
                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        when (requestCode) {
            PERMISSION_REQUEST_CODE -> {
                val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                if (allGranted) {
                    // All basic permissions granted, now check overlay permission
                    checkOverlayPermission()
                } else {
                    // Some permissions denied, show a message but continue
                    Toast.makeText(this, "Some permissions are required for full functionality", Toast.LENGTH_LONG).show()
                    // Still check overlay permission even if some basic permissions were denied
                    checkOverlayPermission()
                }
            }
        }
    }

    private fun handleOverlayPermissionResult(resultCode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (android.provider.Settings.canDrawOverlays(this)) {
                overlayPermissionDialogShown = false
                Toast.makeText(this, "Draw over apps permission granted!", Toast.LENGTH_SHORT).show()
            } else {
                overlayPermissionDialogShown = false
                Toast.makeText(this, "Draw over apps permission denied. You can enable it later in Settings.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        when (requestCode) {
            OVERLAY_PERMISSION_REQUEST_CODE -> {
                handleOverlayPermissionResult(resultCode)
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Dismiss any open dialogs to prevent window leaks
    }
}
