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
import android.graphics.Color
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import com.ghostphoto.app.accessibility.AccessibilityHelper
import com.ghostphoto.app.accessibility.GhostAccessibilityService
import com.ghostphoto.app.matcher.LiveGroundTruthCoordinator
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

        binding.btnAccessibilityStatus.setOnClickListener {
            AccessibilityHelper.openAccessibilitySettings(this)
            Toast.makeText(this, "설정 > 설치된 앱 > FindGhostPhoto에서 접근성을 켜주세요.", Toast.LENGTH_LONG).show()
        }

        binding.btnLiveCollect.setOnClickListener {
            handleLiveCollectClick()
        }

        binding.btnViewReport.setOnClickListener {
            showEvaluationReportDialog()
        }

        GhostAccessibilityService.onStatusChanged = { statusText ->
            binding.tvStatus.text = statusText
            updateAccessibilityStatus()
        }

        GhostAccessibilityService.onCandidateObserved = { candidate, count ->
            binding.tvStatus.text = "후보 수집 중 (${count}개): ${candidate.filename}"
        }

        displayCachedCandidates()
        updateAccessibilityStatus()
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
    }

    private fun updateAccessibilityStatus() {
        val isEnabled = AccessibilityHelper.isAccessibilityServiceEnabled(
            this,
            GhostAccessibilityService::class.java
        )
        if (isEnabled) {
            binding.btnAccessibilityStatus.text = "접근성: 🟢 켜짐"
        } else {
            binding.btnAccessibilityStatus.text = "접근성: 🔴 꺼짐 (설정)"
        }
    }

    private fun handleLiveCollectClick() {
        val isEnabled = AccessibilityHelper.isAccessibilityServiceEnabled(
            this,
            GhostAccessibilityService::class.java
        )

        if (!isEnabled) {
            AlertDialog.Builder(this)
                .setTitle("접근성 권한 필요")
                .setMessage("구글포토에서 소실 사진을 자동으로 식별(Read-Only)하려면 접근성 권한이 필요합니다.\n\n설정 화면으로 이동하시겠습니까?")
                .setPositiveButton("설정으로 이동") { _, _ ->
                    AccessibilityHelper.openAccessibilitySettings(this)
                }
                .setNegativeButton("취소", null)
                .show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("구글포토 실시간 수집 시작 (P2-A)")
            .setMessage("Google Photos 앱을 열고 검색 화면(예: 2026-08-29)에서 첫 번째 사진을 열면, 백그라운드 수집기가 스와이프하며 메타데이터를 수집합니다.\n\n(원칙: 100% Read-Only, Zero-Delete 보장)\n\n지금 시작하시겠습니까?")
            .setPositiveButton("시작") { _, _ ->
                GhostAccessibilityService.startCollecting()
                val launchIntent = packageManager.getLaunchIntentForPackage("com.google.android.apps.photos")?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (launchIntent != null) {
                    startActivity(launchIntent)
                    Toast.makeText(this, "구글포토로 이동합니다. 날짜 검색 후 사진을 열어주세요.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Google Photos 앱을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showEvaluationReportDialog() {
        val observed = GhostAccessibilityService.observedCandidates
        val coordinator = LiveGroundTruthCoordinator()
        val (metrics, items) = coordinator.evaluateLiveCandidates(observed)

        val reportText = metrics.toSummaryReport()

        AlertDialog.Builder(this)
            .setTitle("P2-A Ground-Truth 대조 리포트")
            .setMessage(reportText)
            .setPositiveButton("확인", null)
            .show()
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
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            val browserIntent = Intent(Intent.ACTION_VIEW, searchUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
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
