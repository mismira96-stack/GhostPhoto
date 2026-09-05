package com.ghostphoto.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.ghostphoto.app.data.GhostScanRepository
import com.ghostphoto.app.data.ScanExecutionResult
import com.ghostphoto.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: GhostScanRepository
    private val adapter = GhostCandidateAdapter()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            executeScan()
        } else {
            Toast.makeText(this, "미디어 읽기 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = GhostScanRepository(this)

        binding.rvCandidates.layoutManager = LinearLayoutManager(this)
        binding.rvCandidates.adapter = adapter

        binding.btnScan.setOnClickListener {
            checkPermissionsAndScan()
        }
    }

    private fun checkPermissionsAndScan() {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val allGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            executeScan()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    private fun executeScan() {
        binding.btnScan.isEnabled = false
        binding.tvStatus.text = "스캔 중..."

        when (val result = repository.performScan()) {
            is ScanExecutionResult.Success -> {
                if (result.isBaselineScan) {
                    binding.tvStatus.text = "최초 스캔 완료 (Baseline): 기기 ${result.totalLocalCount}개 등록됨. (소실 후보 0건)"
                    adapter.submitList(emptyList())
                } else {
                    val recoveryInfo = if (result.recoveredCount > 0) " (${result.recoveredCount}건 재출현 복구)" else ""
                    binding.tvStatus.text = "후속 스캔 완료: 기기 ${result.totalLocalCount}개, 소실 후보 ${result.ghostCandidates.size}건 감지됨$recoveryInfo"
                    adapter.submitList(result.ghostCandidates)
                }
            }
            is ScanExecutionResult.Aborted -> {
                binding.tvStatus.text = "스캔 중단: ${result.reason}"
                Toast.makeText(this, "스캔 중단: ${result.reason}", Toast.LENGTH_LONG).show()
            }
        }

        binding.btnScan.isEnabled = true
    }
}
