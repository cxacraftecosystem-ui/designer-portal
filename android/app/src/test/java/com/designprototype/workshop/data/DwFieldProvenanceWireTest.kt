package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DwFieldProvenanceWireTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val stageJson = """
        {
          "singleton": {"venue": "Barpali"},
          "collections": {
            "participant": [
              {"name": "Latha Devi", "phone": "9000011111", "_entryId": "ent_p1", "_ordinal": 0},
              {"name": "Kamla Bai", "_entryId": "ent_p2", "_ordinal": 1}
            ]
          },
          "custom": {"dyesrc": "Indigo"},
          "provenance": {
            "singleton": {
              "venue": {"by": "usr_asha", "byName": "Asha Patel",
                        "at": "2026-03-08T15:30:00+00:00", "source": "designer"}
            },
            "collections": {
              "participant": {
                "ent_p1": {
                  "name": {"by": "usr_meena", "byName": "Meena Iyer",
                           "at": "2026-03-01T09:00:00+00:00", "source": "reference",
                           "refModel": "Artisan", "refId": "art_1", "refKey": "name"},
                  "phone": {"by": "usr_asha", "byName": "Asha Patel",
                            "at": "2026-03-08T15:30:00+00:00", "source": "designer"}
                },
                "ent_p2": {
                  "name": {"by": "usr_gone", "byName": null,
                           "at": "2026-03-01T09:00:00+00:00", "source": "reference",
                           "refModel": "Artisan", "refId": "art_2", "refKey": "name"}
                }
              }
            },
            "custom": {
              "dyesrc": {"by": "usr_asha", "byName": "Asha Patel",
                         "at": "2026-03-08T15:30:00+00:00", "source": "designer"}
            }
          },
          "customSchemaVersion": "v1"
        }
    """.trimIndent()

    private fun bucket(): StageBucketDto =
        json.decodeFromString(StageBucketDto.serializer(), stageJson)

    @Test
    fun `a hydrated field names the record's author and not the designer who picked it`() {
        val stamp = bucket().provenance.forRow("participant", "ent_p1")["name"]!!
        assertEquals("usr_meena", stamp.by)
        assertEquals("Meena Iyer", stamp.byName)
        assertTrue(stamp.fromSharedRecord)
        assertEquals("Artisan", stamp.refModel)
        assertEquals("art_1", stamp.refId)
        assertEquals("From the artisan record, by Meena Iyer", stamp.attribution())
    }

    @Test
    fun `a field the designer typed on the same row is the designer's`() {
        val stamps = bucket().provenance.forRow("participant", "ent_p1")
        assertEquals("usr_asha", stamps["phone"]!!.by)
        assertFalse(stamps["phone"]!!.fromSharedRecord)
        assertTrue(stamps["phone"]!!.attribution()!!.startsWith("Asha Patel, "))
        assertEquals("usr_meena", stamps["name"]!!.by)
    }

    @Test
    fun `rows are addressed by entry id so a re-sort cannot misalign them`() {
        val prov = bucket().provenance
        assertEquals("usr_meena", prov.forRow("participant", "ent_p1")["name"]!!.by)
        assertEquals("usr_gone", prov.forRow("participant", "ent_p2")["name"]!!.by)
        assertNull(prov.forRow("participant", "ent_p2")["phone"])
    }

    @Test
    fun `a row this device created offline has no stamps and does not crash asking`() {
        val prov = bucket().provenance
        assertEquals(emptyMap<String, DwFieldStampDto>(), prov.forRow("participant", null))
        assertEquals(emptyMap<String, DwFieldStampDto>(), prov.forRow("participant", ""))
        assertEquals(emptyMap<String, DwFieldStampDto>(), prov.forRow("prototype", "ent_p1"))
    }

    @Test
    fun `a deleted account keeps its stamp and is named as one`() {
        val stamp = bucket().provenance.forRow("participant", "ent_p2")["name"]!!
        assertEquals("usr_gone", stamp.by)
        assertNull(stamp.byName)
        assertEquals("From the artisan record", stamp.attribution())
    }

    @Test
    fun `the singleton and the custom container both carry their own stamps`() {
        val prov = bucket().provenance
        assertEquals("usr_asha", prov.singleton["venue"]!!.by)
        assertEquals("usr_asha", prov.custom["dyesrc"]!!.by)
    }

    @Test
    fun `a server that predates the feature reads as nobody recorded it rather than as an error`() {
        val old = json.decodeFromString(
            StageBucketDto.serializer(),
            """{"singleton": {"venue": "Barpali"}, "collections": {}, "custom": {}}"""
        )
        assertEquals(emptyMap<String, DwFieldStampDto>(), old.provenance.singleton)
        assertEquals(emptyMap<String, DwFieldStampDto>(), old.provenance.forRow("participant", "x"))
        assertEquals("Barpali", old.singleton["venue"]?.toString()?.trim('"'))
    }

    @Test
    fun `an unattributed field says nothing rather than saying Unknown`() {
        assertNull(DwFieldStampDto().attribution())
        assertNull(DwFieldStampDto(source = "reference").attribution())
        assertEquals(
            "From the craft record",
            DwFieldStampDto(source = "reference", refModel = "Craft").attribution()
        )
    }

    @Test
    fun `a source this build has never heard of is kept verbatim rather than coerced`() {
        val stamp = json.decodeFromString(
            DwFieldStampDto.serializer(),
            """{"by": "usr_1", "byName": "Asha Patel", "source": "import", "at": "2026-03-08T15:30:00+00:00"}"""
        )
        assertEquals("import", stamp.source)
        assertFalse(stamp.fromSharedRecord)
        assertTrue(stamp.attribution()!!.startsWith("Asha Patel, "))
    }

    @Test
    fun `the phone never sends a stamp back`() {
        val body = json.encodeToString(
            StageSaveBody.serializer(),
            StageSaveBody(
                entries = listOf(
                    StageEntryBody(
                        entityKey = "participant",
                        data = kotlinx.serialization.json.JsonObject(emptyMap()),
                    )
                ),
                replaceCollections = false,
            )
        )
        assertFalse(body.contains("provenance"))
        assertFalse(body.contains("fieldProvenance"))
        assertFalse(body.contains("byName"))
    }

    @Test
    fun `the day is the reader's own, not the server's`() {
        val original = java.util.TimeZone.getDefault()
        try {
            val stamp = DwFieldStampDto(
                by = "usr_asha",
                byName = "Asha Patel",
                at = "2026-03-01T20:00:00+00:00",
                source = "designer",
            )
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Kolkata"))
            assertEquals("Asha Patel, 2 Mar", stamp.attribution())
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/London"))
            assertEquals("Asha Patel, 1 Mar", stamp.attribution())
        } finally {
            java.util.TimeZone.setDefault(original)
        }
    }

    @Test
    fun `a padded name does not reach the label with its padding`() {
        assertEquals(
            "From the artisan record, by Meena Iyer",
            DwFieldStampDto(
                by = "usr_meena",
                byName = "  Meena Iyer  ",
                at = "2026-03-01T09:00:00+00:00",
                source = "reference",
                refModel = "Artisan",
                refId = "art_1",
                refKey = "name",
            ).attribution()
        )
        assertEquals(
            "From the artisan record",
            DwFieldStampDto(
                by = "usr_meena",
                byName = "   ",
                at = "2026-03-01T09:00:00+00:00",
                source = "reference",
                refModel = "Artisan",
                refId = "art_1",
                refKey = "name",
            ).attribution()
        )
    }
}
