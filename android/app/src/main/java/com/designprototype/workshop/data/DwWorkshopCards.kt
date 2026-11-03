package com.designprototype.workshop.data

import com.designprototype.workshop.report.summary as richSummary
import kotlinx.serialization.json.JsonPrimitive

const val DW_PROTOTYPE_MODEL: String = "DwPrototype"

val DW_CARD_ECC_LEVEL: DwQrEccLevel = DwQrEccLevel.Q

data class DwCardSource(val stage: StageDto, val entity: EntityDto)

fun findPrototypeSource(schema: SchemaResponse): DwCardSource? {
    for (stage in schema.stages) {
        for (entity in stage.entities) {
            if (entity.cardinality == "COLLECTION" && entity.name == DW_PROTOTYPE_MODEL) {
                return DwCardSource(stage, entity)
            }
        }
    }
    return null
}

fun findRosterSource(schema: SchemaResponse): DwCardSource? {
    for (stage in schema.stages) {
        for (entity in stage.entities) {
            if (entity.cardinality != "COLLECTION") continue
            if (entity.fields.any { it.type == "REF" && it.refModel == "Artisan" }) {
                return DwCardSource(stage, entity)
            }
        }
    }
    return null
}

fun artisanRefKey(entity: EntityDto): String? =
    entity.fields.firstOrNull { it.type == "REF" && it.refModel == "Artisan" }?.key

fun workshopCardSource(recordType: DwWorkshopRecordType, schema: SchemaResponse): DwCardSource? =
    when (recordType) {
        DwWorkshopRecordType.PROTOTYPE -> findPrototypeSource(schema)
        DwWorkshopRecordType.ARTISAN -> findRosterSource(schema)
        else -> null
    }

fun DraftRow.codeRow(): DwDataRow {
    val clientKey = id.substringAfter(DW_ROW_KEY_SEPARATOR, "")
    if (clientKey.isEmpty() || values.containsKey("_clientKey")) return values
    return values + ("_clientKey" to JsonPrimitive(clientKey))
}

fun workshopCardRows(
    source: DwCardSource,
    draft: WorkshopDraft?,
    remoteStages: Map<String, StageBucketDto>,
): List<DwDataRow> {
    val stored = draft?.stages?.get(source.stage.key)
    val touchedLocally = stored != null && (stored.values.isNotEmpty() || stored.rows.isNotEmpty())
    if (touchedLocally) return stored.rowsFor(source.entity.key).map { it.codeRow() }
    return remoteStages[source.stage.key]?.collections?.get(source.entity.key).orEmpty()
}

data class DwCardSpec(
    val key: String,
    val recordType: DwWorkshopRecordType,
    val id: String?,
    val title: String,
    val lines: List<String>,
    val cardToken: String? = null,
    val cardShortCode: String? = null,
    val signedCard: Boolean = false,
)

const val DW_ARTISAN_CARD_UNSYNCED = "Sync this artisan first."

fun dwAadhaarCardLine(last4: String?): String? =
    last4?.takeIf { it.length == 4 && it.all(Char::isDigit) }?.let { "Aadhaar XXXX XXXX $it" }

sealed interface DwCardRender {
    val spec: DwCardSpec

    data class Ok(
        override val spec: DwCardSpec,
        val code: String,
        val printed: String,
        val symbol: DwQrSymbol,
    ) : DwCardRender

    data class Refused(override val spec: DwCardSpec, val message: String) : DwCardRender
}

fun dwCardRowTitle(entity: EntityDto, row: DwDataRow, index: Int): String {
    if (entity.labelField.isNotEmpty()) {
        val labelled = DwPy.strip(DwValues.text(row[entity.labelField]))
        if (labelled.isNotEmpty()) return labelled
    }
    for (field in entity.liveFields) {
        when (DwFieldType.of(field.type)) {
            DwFieldType.RICH_TEXT ->
                richSummary(row[field.key], 80).takeIf { it.isNotEmpty() }?.let { return it }
            DwFieldType.TEXT, DwFieldType.LONG_TEXT ->
                DwPy.strip(DwValues.text(row[field.key])).takeIf { it.isNotEmpty() }?.let { return it }
            else -> Unit
        }
    }
    return "${entity.title} ${index + 1}"
}

fun workshopCardSpecs(
    recordType: DwWorkshopRecordType,
    source: DwCardSource,
    rows: List<DwDataRow>,
    register: Map<String, ArtisanDto> = emptyMap(),
    artisanCardsOn: Boolean = true,
): List<DwCardSpec> = rows.mapIndexed { index, row ->
    val key = (row["_entryId"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }
        ?: (row["_clientKey"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }
        ?: "row-$index"
    when (recordType) {
        DwWorkshopRecordType.PROTOTYPE -> DwCardSpec(
            key = key,
            recordType = recordType,
            id = workshopCodeIdForRow(row),
            title = dwCardRowTitle(source.entity, row, index),
            lines = listOf(
                DwValues.text(row["prototypeCode"]),
                DwValues.list(row["materials"]).joinToString(", "),
            ).filter { it.isNotBlank() },
        )

        DwWorkshopRecordType.ARTISAN -> {
            val artisanId = artisanRefKey(source.entity)?.let { DwValues.text(row[it]) }?.takeIf { it.isNotBlank() }
            val artisan = artisanId?.let { register[it] }
            if (!artisanCardsOn) {
                return@mapIndexed DwCardSpec(
                    key = key,
                    recordType = recordType,
                    id = artisanId,
                    title = dwCardRowTitle(source.entity, row, index),
                    lines = listOf(
                        DwValues.text(row["serialNo"]).let { if (it.isBlank()) "" else "S. No. $it" },
                        DwValues.text(row["village"]),
                    ).filter { it.isNotBlank() },
                )
            }
            DwCardSpec(
                key = key,
                recordType = recordType,
                id = artisanId,
                title = artisan?.name?.takeIf { it.isNotBlank() } ?: dwCardRowTitle(source.entity, row, index),
                lines = listOfNotNull(
                    DwValues.text(row["village"]).ifBlank { artisan?.place.orEmpty() }.ifBlank { null },
                    dwAadhaarCardLine(artisan?.aadhaarLast4),
                ),
                cardToken = artisan?.card?.token,
                cardShortCode = artisan?.card?.shortCode,
                signedCard = true,
            )
        }

        else -> DwCardSpec(
            key = key,
            recordType = recordType,
            id = workshopCodeIdForRow(row),
            title = dwCardRowTitle(source.entity, row, index),
            lines = emptyList(),
        )
    }
}

fun renderWorkshopCard(spec: DwCardSpec): DwCardRender {
    if (spec.recordType == DwWorkshopRecordType.ARTISAN && spec.signedCard) return renderArtisanCard(spec)
    val encoded = encodeWorkshopCode(spec.recordType, spec.id)
    if (encoded is DwEncodeResult.Refused) return DwCardRender.Refused(spec, encoded.message)
    val ok = encoded as DwEncodeResult.Ok
    return try {
        DwCardRender.Ok(
            spec = spec,
            code = ok.code,
            printed = formatWorkshopCodeForPrint(ok.code),
            symbol = DwQrEncode.encode(ok.code, DW_CARD_ECC_LEVEL),
        )
    } catch (e: DwQrEncodeException) {
        DwCardRender.Refused(spec, e.userMessage("This code could not be drawn."))
    }
}

fun renderArtisanCard(spec: DwCardSpec): DwCardRender {
    val token = spec.cardToken
    val short = spec.cardShortCode
    if (token.isNullOrBlank() || short.isNullOrBlank()) return DwCardRender.Refused(spec, DW_ARTISAN_CARD_UNSYNCED)
    return try {
        DwCardRender.Ok(spec = spec, code = token, printed = short, symbol = DwQrEncode.encode(token, DW_CARD_ECC_LEVEL))
    } catch (e: DwQrEncodeException) {
        DwCardRender.Refused(spec, e.userMessage("This code could not be drawn."))
    }
}

data class DwCardHit(val label: String, val detail: String)

fun findWorkshopCodeInDraft(
    ref: DwWorkshopCodeRef,
    schema: SchemaResponse,
    draft: WorkshopDraft?,
    remoteStages: Map<String, StageBucketDto>,
): DwCardHit? {
    val source = workshopCardSource(ref.recordType, schema) ?: return null
    val rows = workshopCardRows(source, draft, remoteStages)

    if (ref.recordType == DwWorkshopRecordType.PROTOTYPE) {
        val index = rows.indexOfFirst { workshopCodeMatchesRow(ref, it) }
        if (index < 0) return null
        val code = DwValues.text(rows[index]["prototypeCode"])
        return DwCardHit(
            label = dwCardRowTitle(source.entity, rows[index], index),
            detail = buildString {
                append(source.stage.title)
                append(" · entry ${index + 1}")
                if (code.isNotBlank()) append(" · $code")
            },
        )
    }

    val refKey = artisanRefKey(source.entity) ?: return null
    val index = rows.indexOfFirst { DwValues.text(it[refKey]) == ref.id }
    if (index < 0) return null
    return DwCardHit(
        label = dwCardRowTitle(source.entity, rows[index], index),
        detail = "On this workshop's roster · ${source.stage.title}",
    )
}
