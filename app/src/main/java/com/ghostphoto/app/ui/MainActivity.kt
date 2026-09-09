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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ghostphoto.app.matcher.LocalMediaRecord
import com.ghostphoto.app.matcher.MediaLifecycleState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: GhostScanRepository
    private val adapter = GhostCandidateAdapter { item ->
        openGooglePhotosForCandidate(item)
    }

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

        displayCachedCandidates()
    }

    private fun displayCachedCandidates() {
        val snapshot = repository.getSnapshot() ?: return
        val missing = snapshot.filter { it.state == MediaLifecycleState.MISSING_FROM_LOCAL_SCAN }
        if (missing.isNotEmpty()) {
            binding.tvStatus.text = "기록된 스냅샷: 기기 ${snapshot.size}개 중 소실 후보 ${missing.size}건 감지됨"
            adapter.submitList(missing)
        }
    }

    private fun openGooglePhotosForCandidate(item: LocalMediaRecord) {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(item.takenAtMillis))

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("filename", item.displayName))

        Toast.makeText(
            this,
            "구글포토 [$dateStr]로 이동합니다.\n(파일명 복사됨: ${item.displayName})",
            Toast.LENGTH_SHORT
        ).show()

        val searchUri = Uri.parse("https://photos.google.com/search/$dateStr")
        try {
            val intent = Intent(Intent.ACTION_VIEW, searchUri).apply {
                setPackage("com.google.android.apps.photos")
            }
            startActivity(intent)
        } catch (e: Exception) {
            val browserIntent = Intent(Intent.ACTION_VIEW, searchUri)
            startActivity(browserIntent)
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
