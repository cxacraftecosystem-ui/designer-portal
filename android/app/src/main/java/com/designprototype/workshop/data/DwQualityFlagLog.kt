package com.designprototype.workshop.data

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

object DwQualityFlagLog {

    private const val STORE = "dw_quality_findings"

    const val MAX_LOGGED_FINDINGS = 200

    private val json = Json { ignoreUnknownKeys = true }

    fun read(context: Context, workshopId: String): List<DwPhotoGate.CapturedFinding> {
        if (workshopId.isBlank()) return emptyList()
        return runCatching {
            val raw = context
                .getSharedPreferences(STORE, Context.MODE_PRIVATE)
                .getString(workshopId, null)
                ?: return emptyList()
            val parsed = json.parseToJsonElement(raw) as? JsonArray ?: return emptyList()
            parsed.mapNotNull { element -> (element as? JsonObject)?.let(::findingOrNull) }
        }.getOrDefault(emptyList())
    }

    fun record(
        context: Context,
        workshopId: String,
        findings: List<DwPhotoGate.CapturedFinding>,
    ): List<DwPhotoGate.CapturedFinding> {
        if (workshopId.isBlank() || findings.isEmpty()) return read(context, workshopId)
        val byKey = LinkedHashMap<String, DwPhotoGate.CapturedFinding>()
        for (entry in read(context, workshopId)) byKey["${entry.mediaId}:${entry.flag}"] = entry
        for (entry in findings) byKey["${entry.mediaId}:${entry.flag}"] = entry
        val next = byKey.values.toList().takeLast(MAX_LOGGED_FINDINGS)
        runCatching {
            context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
                .edit()
                .putString(workshopId, encode(next))
                .apply()
        }
        return next
    }

    fun clear(context: Context, workshopId: String) {
        if (workshopId.isBlank()) return
        runCatching {
            context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().remove(workshopId).apply()
        }
    }

    private fun encode(findings: List<DwPhotoGate.CapturedFinding>): String =
        buildJsonArray {
            findings.forEach { finding ->
                add(
                    buildJsonObject {
                        put("mediaId", JsonPrimitive(finding.mediaId))
                        put("fileName", JsonPrimitive(finding.fileName))
                        put("flag", JsonPrimitive(finding.flag.name))
                        put("severity", JsonPrimitive(finding.severity.name))
                        put("note", JsonPrimitive(finding.note))
                        put("raisedAt", JsonPrimitive(finding.raisedAt))
                    }
                )
            }
        }.toString()

    private fun findingOrNull(row: JsonObject): DwPhotoGate.CapturedFinding? {
        val mediaId = row.text("mediaId") ?: return null
        if (mediaId.isBlank()) return null
        val flag = QualityFlag.entries.firstOrNull { it.name == row.text("flag") } ?: return null
        val severity = QualitySeverity.entries.firstOrNull { it.name == row.text("severity") } ?: return null
        return DwPhotoGate.CapturedFinding(
            mediaId = mediaId,
            fileName = row.text("fileName").orEmpty(),
            flag = flag,
            severity = severity,
            note = row.text("note").orEmpty(),
            raisedAt = row.text("raisedAt").orEmpty(),
        )
    }

    private fun JsonObject.text(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()
}
