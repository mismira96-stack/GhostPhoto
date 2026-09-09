package com.ghostphoto.app.matcher

import org.junit.Test
import java.io.File

class GroundTruthInventoryTest {

    private val expected28Filenames = listOf(
        // PoC screen dumps (5)
        "screen.png", "app-screen.png", "app-scroll.png", "dump-screen.png", "pass-screen.png",
        // UI / Details (6)
        "photos-screen.png", "photos-selected.png", "photo-detail.png", "info-sheet.png", "real-info.png", "exif-info.png",
        // Test 1-3 (5)
        "test1-screen.png", "test1_timeline.png", "test2_detail.png", "test3_search_tab.png", "test3_search_result.png",
        // All-in-One experiments (8)
        "t1_timeline.png", "t2_detail_info.png", "t3_search_result.png", "t4_main_timeline.png", "t5_selection_check.png",
        "t3_search_20260828.png", "t4_page1.png", "t4_page2.png",
        // Adversarial / Info (4)
        "adv_timeline.png", "adv_live_timeline.png", "photo_details_dump.png", "cleanup_selected_state.png"
    )

    private fun findWorkspaceFile(relativePath: String): File {
        val possiblePaths = listOf(
            File(relativePath),
            File("../$relativePath"),
            File("c:/Users/mismi/Documents/Codex/GhostPhoto/$relativePath")
        )
        return possiblePaths.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Cannot find file: $relativePath in $possiblePaths")
    }

    @Test
    fun inventoryGroundTruthCompleteness() {
        val experimentDir = findWorkspaceFile("experiment")
        val xmlFiles = experimentDir.listFiles { f -> f.extension == "xml" } ?: emptyArray()

        println("=== GROUND TRUTH INVENTORY AUDIT ===")
        println("TOTAL_PRESERVED_FIXTURES: ${expected28Filenames.size}")

        val parsedCandidates = mutableMapOf<String, CloudCandidate>()
        for (xml in xmlFiles) {
            try {
                val candidate = CloudCandidateParser.parse(xml, xml.nameWithoutExtension)
                if (candidate.filename != null) {
                    parsedCandidates[candidate.filename!!] = candidate
                    println("Found parsed candidate in XML [${xml.name}]: ${candidate.filename} | ${candidate.dateStr} | ${candidate.width}x${candidate.height} | ${candidate.displayedSizeText}")
                }
            } catch (e: Exception) {
                // skip non-photo XML
            }
        }

        println("\n=== 28 FIXTURE METADATA COMPLETENESS REPORT ===")
        val evaluable = mutableListOf<String>()
        val insufficient = mutableListOf<String>()

        for (fn in expected28Filenames) {
            val cand = parsedCandidates[fn]
            val hasFullMeta = cand != null && cand.filename != null && cand.dateStr != null && cand.width != null && cand.height != null && cand.displayedSizeText != null
            if (hasFullMeta) {
                evaluable.add(fn)
                println("[EVALUABLE] $fn -> date=${cand?.dateStr}, res=${cand?.width}x${cand?.height}, size=${cand?.displayedSizeText}")
            } else {
                insufficient.add(fn)
                val status = if (cand == null) "NO_XML_DUMP" else "PARTIAL (date=${cand.dateStr}, res=${cand.width}x${cand.height}, size=${cand.displayedSizeText})"
                println("[INSUFFICIENT] $fn -> $status")
            }
        }

        println("\n-------------------------------------------")
        println("TOTAL_PRESERVED_FIXTURES = ${expected28Filenames.size}")
        println("EVALUABLE_GROUND_TRUTH = ${evaluable.size}")
        println("INSUFFICIENT_GROUND_TRUTH = ${insufficient.size}")
        println("-------------------------------------------")
    }
}
