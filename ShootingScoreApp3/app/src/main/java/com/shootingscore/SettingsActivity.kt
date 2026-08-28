package com.shootingscore

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import com.shootingscore.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val selectDirLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            // Persist permission
            contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            
            // Save to prefs
            val prefs = getSharedPreferences("shooting_prefs", MODE_PRIVATE)
            prefs.edit { putString("custom_save_uri", it.toString()) }
            
            binding.txtSaveLocation.text = it.path ?: it.toString()
            Toast.makeText(this, "Save location updated", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupHeader()
        setupDetectionMode()
        setupCameraSelection()
        setupStorageInfo()
        setupAppInfo()
    }

    private fun setupHeader() {
        binding.btnSettingsBack.setOnClickListener {
            finish()
        }
    }

    private fun setupDetectionMode() {
        val prefs = getSharedPreferences("shooting_prefs", MODE_PRIVATE)
        val isComparisonMode = prefs.getBoolean("use_comparison_mode", false)
        
        binding.switchComparisonMode.isChecked = isComparisonMode
        
        binding.switchComparisonMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean("use_comparison_mode", isChecked) }
            val modeText = if (isChecked) "Comparison Mode Enabled" else "AI Mode Enabled"
            Toast.makeText(this, modeText, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupCameraSelection() {
        val prefs = getSharedPreferences("shooting_prefs", MODE_PRIVATE)
        val cameras = arrayOf("Built-in Camera", "RTSP Wi-Fi Camera")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, cameras)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerCamera.adapter = adapter

        val selectedSource = prefs.getInt("camera_source", 0) // 0: Local, 1: RTSP
        binding.spinnerCamera.setSelection(selectedSource)

        val rtspUrl = prefs.getString("rtsp_url", "rtsp://admin:646335@192.168.1.148:554/live/profile.0/video")
        binding.editRtspUrl.setText(rtspUrl)

        updateRtspVisibility(selectedSource == 1)

        binding.spinnerCamera.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                prefs.edit { putInt("camera_source", position) }
                updateRtspVisibility(position == 1)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        binding.editRtspUrl.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                prefs.edit { putString("rtsp_url", s.toString()) }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun updateRtspVisibility(isRtsp: Boolean) {
        val visibility = if (isRtsp) android.view.View.VISIBLE else android.view.View.GONE
        binding.lblRtspUrl.visibility = visibility
        binding.editRtspUrl.visibility = visibility
    }

    private fun setupStorageInfo() {
        val prefs = getSharedPreferences("shooting_prefs", Context.MODE_PRIVATE)
        val customUri = prefs.getString("custom_save_uri", null)
        
        if (customUri != null) {
            val uri = customUri.toUri()
            binding.txtSaveLocation.text = uri.path ?: uri.toString()
        } else {
            val directory = getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
            binding.txtSaveLocation.text = directory?.absolutePath ?: "Internal Storage"
        }
        
        binding.btnChangeLocation.setOnClickListener {
            selectDirLauncher.launch(null)
        }
        
        binding.txtSaveLocation.setOnClickListener {
            Toast.makeText(this, "Path info displayed above", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupAppInfo() {
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val version = pInfo.versionName
            binding.txtVersionInfo.text = getString(R.string.version_format, version)
        } catch (e: Exception) {
            binding.txtVersionInfo.text = "Version 1.8.9"
        }
    }
}
