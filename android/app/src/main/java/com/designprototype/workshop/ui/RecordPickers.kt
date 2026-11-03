package com.designprototype.workshop.ui

import com.designprototype.workshop.data.ArtisanDto
import java.util.Locale

fun englishNameMirrorArmed(toolkitName: String?, englishName: String?): Boolean {
    val english = englishName.orEmpty()
    if (english.isBlank()) return true
    return english == toolkitName.orEmpty()
}

fun craftsChangeClearsArtisans(
    nextCraftIds: List<String>,
    removedCraftIds: List<String>,
    artisanIds: List<String>,
    artisans: List<ArtisanDto>,
): List<String> {
    if (removedCraftIds.isEmpty()) return emptyList()
    return artisanIds.filter { id ->
        val known = artisans.firstOrNull { it.id == id } ?: return@filter false
        val craftId = known.craftId
        if (craftId.isNullOrBlank()) return@filter false
        craftId in removedCraftIds && craftId !in nextCraftIds
    }
}

fun initialLinkIds(linked: List<String>?, fallback: String?): List<String> =
    (listOfNotNull(fallback) + linked.orEmpty()).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

fun mergeSelection(current: List<String>, next: Set<String>, optionIds: List<String>): List<String> {
    val offerable = optionIds.toSet()
    val kept = current.filter { it in next || it !in offerable }
    return kept + optionIds.filter { it in next && it !in current }
}

fun craftNameForArtisan(artisan: ArtisanDto, selectedCraftNames: Map<String, String>): String {
    val embedded = artisan.craft?.name.orEmpty()
    if (embedded.isNotBlank()) return embedded
    val byId = artisan.craftId?.let { selectedCraftNames[it] }.orEmpty()
    if (byId.isNotBlank()) return byId
    return ""
}

private fun fold(s: String): String = s.trim().lowercase(Locale.ROOT)

fun toolArtisanOptions(artisans: List<ArtisanDto>, selectedCraftNames: Map<String, String>): List<SelectOption> =
    artisans
        .map { it to craftNameForArtisan(it, selectedCraftNames) }
        .sortedWith(
            compareBy<Pair<ArtisanDto, String>> { (_, craft) -> if (craft.isBlank()) 1 else 0 }
                .thenBy { (_, craft) -> fold(craft) }
                .thenBy { (artisan, _) -> fold(artisan.name) }
                .thenBy { (artisan, _) -> artisan.name }
                .thenBy { (artisan, _) -> artisan.id }
        )
        .map { (artisan, craftName) ->
            SelectOption(
                value = artisan.id,
                label = artisan.name,
                hint = listOf(craftName, artisan.place)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                    .ifBlank { null },
            )
        }

fun offPageLinkRow(id: String, name: String?, noun: String): SelectOption = SelectOption(
    value = id,
    label = name?.takeIf { it.isNotBlank() } ?: "The $noun already on this record",
    hint = if (name.isNullOrBlank()) {
        "Linked earlier · could not list it just now, so its name is not shown"
    } else {
        "Linked earlier · not in the current list"
    },
)
