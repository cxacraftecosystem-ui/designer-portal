package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHO SET THIS FIELD — the server's answer, decoded on the handset exactly as it is sent.
 *
 * ── WHY THE PHONE NEEDS THIS AT ALL ───────────────────────────────────────────────────────────────
 *
 * A stage entry holds two kinds of value that are byte-identical once stored. `hydrate_entries`
 * COPIES 81 field-pairs onto these rows from shared records — an artisan's name, village, phone,
 * do's and don'ts — the instant a designer picks that artisan from the picker, and a copied village
 * and a typed village are the same string in `data`. Standing in a cluster with a participant row on
 * screen, a designer has had no way to tell which is which. The difference decides what they do
 * next: correcting a hydrated phone number means the MASTER record is out of date and somebody
 * should be told, while correcting a typed one is fixing your own typo.
 *
 * ── THE PHONE IS A READER AND NEVER A WRITER ──────────────────────────────────────────────────────
 *
 * The server recomputes the whole map on every save from the values themselves
 * (`entry_provenance.merge_entry_provenance`), so nothing here is ever sent back. That is deliberate
 * and not an omission: a stamp a client can set is a stamp a client can forge, and this one names a
 * researcher who is not in the room and never opened this workshop.
 *
 * ── WHAT THIS FILE PINS ───────────────────────────────────────────────────────────────────────────
 *
 * The exact JSON the server emits, verbatim from `_stages_payload`. A DTO that agrees with the model
 * we imagined the server has is worth nothing; every fixture below is the shape
 * `backend/tests/test_entry_provenance_readers.py` asserts on the other side, and the two files are
 * each other's proof. `ignoreUnknownKeys` means a mismatch does not crash — it silently reads as
 * "nobody set this field", which is the failure mode a wire test exists to catch, because on screen
 * it is indistinguishable from an archive of rows written before the feature existed.
 */
class DwFieldProvenanceWireTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The stage payload as the server emits it, with one hydrated field and one typed one. */
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
        assertEquals("From the record, by Meena Iyer", stamp.attribution())
    }

    @Test
    fun `a field the designer typed on the same row is the designer's`() {
        // One row, two authors. This is the whole reason the answer has to be per FIELD: a row-level
        // `createdById` can only ever name one of these two people, and on a shared workshop it
        // routinely names neither.
        val stamps = bucket().provenance.forRow("participant", "ent_p1")
        assertEquals("usr_asha", stamps["phone"]!!.by)
        assertFalse(stamps["phone"]!!.fromSharedRecord)
        assertEquals("Edited by Asha Patel", stamps["phone"]!!.attribution())
        assertEquals("usr_meena", stamps["name"]!!.by)
    }

    @Test
    fun `rows are addressed by entry id so a re-sort cannot misalign them`() {
        // The phone sorts its own draft, the server's `_stages_payload` sorts by `_ordinal` after
        // grouping, and its report builder sorts before. A positional list would be misaligned on
        // whichever of the three disagreed, and the failure is one participant's edits shown against
        // another participant's name — in a table that is the proof of who attended.
        val prov = bucket().provenance
        assertEquals("usr_meena", prov.forRow("participant", "ent_p1")["name"]!!.by)
        assertEquals("usr_gone", prov.forRow("participant", "ent_p2")["name"]!!.by)
        assertNull(prov.forRow("participant", "ent_p2")["phone"])
    }

    @Test
    fun `a row this device created offline has no stamps and does not crash asking`() {
        // A row created in a cluster with no signal has no server id yet. "Not yet recorded" is the
        // correct answer for a value that has never reached the server, and it must be an answer
        // rather than a null dereference on a screen a designer is typing into.
        val prov = bucket().provenance
        assertEquals(emptyMap<String, DwFieldStampDto>(), prov.forRow("participant", null))
        assertEquals(emptyMap<String, DwFieldStampDto>(), prov.forRow("participant", ""))
        assertEquals(emptyMap<String, DwFieldStampDto>(), prov.forRow("prototype", "ent_p1"))
    }

    @Test
    fun `a deleted account keeps its stamp and is named as one`() {
        // The server sends the id with no name when the account has gone. Dropping the stamp would
        // erase the fact that the field WAS attributed, which is the more useful half of what is
        // left; printing the raw cuid would be worse than either.
        val stamp = bucket().provenance.forRow("participant", "ent_p2")["name"]!!
        assertEquals("usr_gone", stamp.by)
        assertNull(stamp.byName)
        assertEquals("From the record, by an account no longer on record", stamp.attribution())
    }

    @Test
    fun `the singleton and the custom container both carry their own stamps`() {
        // Eight of the twenty-two stages declare no singleton entity at all, and the custom section
        // is a row of its own on the server — so a provenance model that only covered collection
        // rows would leave the parts of a workshop a designer most often fills in with no answer.
        val prov = bucket().provenance
        assertEquals("usr_asha", prov.singleton["venue"]!!.by)
        assertEquals("usr_asha", prov.custom["dyesrc"]!!.by)
    }

    @Test
    fun `a server that predates the feature reads as nobody recorded it rather than as an error`() {
        // Every deployment older than this column sends no `provenance` key at all, and a handset in
        // a cluster cannot be redeployed. The honest reading is "not recorded", and it must not be
        // a decode failure that costs the designer the whole stage.
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
        // Rows written before this column exist in every archive, and the server deliberately does
        // not backfill them — attributing a value to whoever saved the row next would manufacture an
        // audit trail on a document submitted to a ministry. A label reading "Unknown" on every one
        // of those rows is noise that trains a designer to stop reading the label, at which point it
        // cannot do its one job on the rows that DO carry an author.
        assertNull(DwFieldStampDto().attribution())
        assertNull(DwFieldStampDto(source = "reference").attribution())
    }

    @Test
    fun `a source this build has never heard of is kept verbatim rather than coerced`() {
        // A server one release ahead may name a third source. Reading it as "designer" — or as
        // "reference" — would put a confident wrong sentence on screen; keeping it lets the field
        // fall through to the neutral rendering instead.
        val stamp = json.decodeFromString(
            DwFieldStampDto.serializer(),
            """{"by": "usr_1", "byName": "Asha Patel", "source": "import", "at": "2026-03-08T15:30:00+00:00"}"""
        )
        assertEquals("import", stamp.source)
        assertFalse(stamp.fromSharedRecord)
        assertEquals("Edited by Asha Patel", stamp.attribution())
    }

    @Test
    fun `the phone never sends a stamp back`() {
        // The save body is the values and the sync bookkeeping, and nothing else. A stamp a client
        // can set is a stamp a client can forge, and this one names a researcher who never opened
        // this workshop. Asserted against the serialised body rather than by reading the class, so
        // that a field added to `StageSaveBody` under any name is caught.
        val body = json.encodeToString(
            StageSaveBody.serializer(),
            // `replaceCollections` has no default by deliberate design (see its own comment) — an
            // omitted key is the strongest claim the protocol has. Passed explicitly here so this
            // test never becomes the reason somebody gives it one.
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
}
