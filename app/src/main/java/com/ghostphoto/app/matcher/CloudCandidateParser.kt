package com.ghostphoto.app.matcher

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Google Photos UI 접근성 덤프 XML에서 CloudCandidate 메타데이터를 추출하는 파서 유틸리티.
 */
object CloudCandidateParser {

    fun parse(xmlFile: File, candidateId: String = xmlFile.nameWithoutExtension): CloudCandidate {
        return xmlFile.inputStream().use { parse(it, candidateId) }
    }

    fun parse(xmlString: String, candidateId: String = "candidate_parsed"): CloudCandidate {
        return xmlString.byteInputStream(Charsets.UTF_8).use { parse(it, candidateId) }
    }

    fun parse(inputStream: InputStream, candidateId: String): CloudCandidate {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc: Document = builder.parse(inputStream)

        val textList = mutableListOf<String>()
        val contentDescList = mutableListOf<String>()

        collectTexts(doc.documentElement, textList, contentDescList)

        var dateStr: String? = null
        var timeMinuteStr: String? = null
        var filename: String? = null
        var width: Int? = null
        var height: Int? = null
        var displayedSizeText: String? = null
        var device: String? = null
        var location: String? = null

        val dateTimeRegex = Regex("""(\d{4})년\s*(\d{1,2})월\s*(\d{1,2})일.*?((?:오전|오후)\s*\d{1,2}:\d{2})""")
        val resRegex = Regex("""^(\d{3,5})\s*x\s*(\d{3,5})$""")
        val sizeRegex = Regex("""(?:백업됨\s*[•·]\s*)?([0-9]+(?:\.[0-9]+)?\s*(?:MB|KB|GB))""", RegexOption.IGNORE_CASE)
        val fileRegex = Regex("""^[\w\-\.]+\.(png|jpg|jpeg|mp4|mov|gif|webp)$""", RegexOption.IGNORE_CASE)

        for (text in textList) {
            val t = text.trim()
            if (t.isEmpty()) continue

            // 1. 촬영 일시: "2026년 8월 29일 (토) • 오후 11:59"
            val dtMatch = dateTimeRegex.find(t)
            if (dtMatch != null && dateStr == null) {
                val y = dtMatch.groupValues[1]
                val m = dtMatch.groupValues[2].padStart(2, '0')
                val d = dtMatch.groupValues[3].padStart(2, '0')
                dateStr = "$y-$m-$d"
                timeMinuteStr = dtMatch.groupValues[4].trim()
            }

            // 2. 해상도: "1248 x 1972"
            val resMatch = resRegex.find(t)
            if (resMatch != null && width == null) {
                width = resMatch.groupValues[1].toIntOrNull()
                height = resMatch.groupValues[2].toIntOrNull()
            }

            // 3. 용량: "백업됨 • 2.8MB" 또는 "7.7MB"
            val sizeMatch = sizeRegex.find(t)
            if (sizeMatch != null && displayedSizeText == null && (t.contains("MB") || t.contains("KB") || t.contains("GB"))) {
                displayedSizeText = t
            }

            // 4. 파일명: "adv_live_timeline.png"
            if (fileRegex.matches(t) && filename == null) {
                filename = t
            }

            // 5. 기기: "samsung Galaxy Z Fold8"
            if (t.contains("Galaxy", ignoreCase = true) || t.contains("Pixel", ignoreCase = true) || t.contains("samsung", ignoreCase = true)) {
                device = t
            }

            // 6. 위치: "서울특별시", "성남시"
            if (t.endsWith("시") || t.endsWith("구") || t.endsWith("동") || t == "서울특별시") {
                if (!t.contains("일") && !t.contains("월") && !t.contains("년") && t.length <= 15) {
                    location = t
                }
            }
        }

        return CloudCandidate(
            candidateId = candidateId,
            dateStr = dateStr,
            timeMinuteStr = timeMinuteStr,
            contentDesc = contentDescList.firstOrNull { it.contains("사진") || it.contains("동영상") },
            filename = filename,
            width = width,
            height = height,
            displayedSizeText = displayedSizeText,
            device = device,
            location = location
        )
    }

    private fun collectTexts(element: Element, texts: MutableList<String>, descs: MutableList<String>) {
        val t = element.getAttribute("text")
        if (!t.isNullOrBlank()) texts.add(t)

        val cd = element.getAttribute("content-desc")
        if (!cd.isNullOrBlank()) descs.add(cd)

        val children = element.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == Node.ELEMENT_NODE) {
                collectTexts(child as Element, texts, descs)
            }
        }
    }
}
