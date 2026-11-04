package com.designprototype.workshop.data

enum class DwTier2Verb {
    PROOFREAD,

    EXPANDED,

    TRANSLATION,

    CAPTION,
    ;

}

data class DwTier2Provenance(
    val provider: String,
    val modelId: String,
    val modelVersion: String,
    val language: String,
    val producedAtIso: String,
) {
    init {
        require(provider.isNotBlank()) {
            "A run has to say what produced it. Write DW_TIER2_UNRECORDED if it genuinely is not " +
                "known; a blank would print as an empty provenance column and read as “no model”."
        }
        require(modelId.isNotBlank()) {
            "A run has to name the exact artifact. “Gemma” is a family; a provenance column carrying " +
                "a family name cannot tell a reviewer which of two models with different footprints " +
                "and different licences wrote the words they are reading."
        }
        require(modelVersion.isNotBlank()) {
            "A run has to name the runtime build that loaded the model. A defect found in one " +
                "version of LiteRT-LM is traceable only if the row says which one ran."
        }
        require(language.isNotBlank()) {
            "A run has to say what language it produced, or the word “multi”. This is the field a " +
                "reader uses to know whether the prose beside an artisan's Odia is a translation."
        }
        require(producedAtIso.isNotBlank()) {
            "A run has to say WHEN the model ran. The server records when the row appeared; that is " +
                "a different moment, and on a handset that has been offline for a day it is a very " +
                "different one."
        }
    }
}

const val DW_TIER2_UNRECORDED: String = "UNRECORDED"

const val DW_TIER2_TIER: String = "TIER_2"

sealed interface DwTier2Source {
    data class Layer(val layerId: String) : DwTier2Source

    data class Media(val mediaId: String) : DwTier2Source

    data class SuppliedText(val text: String) : DwTier2Source
}

data class DwTier2Draft(
    val verb: DwTier2Verb,
    val source: DwTier2Source,
    val text: String,
    val provenance: DwTier2Provenance,
) {
    init {
        require(text.isNotBlank()) {
            "A layer with no words in it is not a layer. A verb that produced nothing should record " +
                "a failure where failures are recorded (DwLoadFailureNote), not an empty row that a " +
                "reviewer has to open to discover is empty."
        }
    }
}

fun dwTier2LayerBody(draft: DwTier2Draft): Map<String, String> {
    val body = linkedMapOf(
        "kind" to draft.verb.name,
        "text" to draft.text,
        "provider" to draft.provenance.provider,
        "modelId" to draft.provenance.modelId,
        "modelVersion" to draft.provenance.modelVersion,
        "language" to draft.provenance.language,
        "producedAt" to draft.provenance.producedAtIso,
    )
    when (val source = draft.source) {
        is DwTier2Source.Layer -> body["sourceLayerId"] = source.layerId
        is DwTier2Source.Media -> body["sourceMediaId"] = source.mediaId
        is DwTier2Source.SuppliedText -> body["sourceText"] = source.text
    }
    return body
}

const val DW_TIER2_NO_GATES_NOTE: String =
    "Runs on this phone and sends nothing off it, so no consent or allowance is needed."

enum class DwTier2WriteBlocker {
    NO_RUNTIME_IN_THIS_BUILD,

    NO_ROUTE_THAT_ACCEPTS_A_DEVICE_LAYER,
}

fun dwTier2WriteBlocker(
    runtimePresent: Boolean = DW_TIER2_RUNTIME_PRESENT,
    routeExists: Boolean = DW_TIER2_DEVICE_LAYER_ROUTE_EXISTS,
): DwTier2WriteBlocker? = when {
    !runtimePresent -> DwTier2WriteBlocker.NO_RUNTIME_IN_THIS_BUILD
    !routeExists -> DwTier2WriteBlocker.NO_ROUTE_THAT_ACCEPTS_A_DEVICE_LAYER
    else -> null
}

const val DW_TIER2_DEVICE_LAYER_ROUTE_EXISTS: Boolean = true

const val DW_TIER2_LAYER_ROUTE_SEGMENT: String = "on-device"

const val DW_TIER2_PROVIDER: String = "litert-lm"

const val DW_TIER2_RUNTIME_VERSION: String = "litertlm-android 0.19.0"
