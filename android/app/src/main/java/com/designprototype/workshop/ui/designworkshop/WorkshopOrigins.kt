package com.designprototype.workshop.ui.designworkshop

/**
 * WHERE A DESIGN WORKSHOP WAS OPENED FROM, WHEN THAT WAS NOT ITS LIST (0.0.6).
 *
 * A workshop opened from a search result returns there on Back from its index. Its sub-screens (a
 * stage, the report, the codes, the photos, …) back out to the index, and the index is rebuilt each
 * time, so the search it came from is kept here per workshop and handed to every rebuilt index:
 * search → workshop → stage → Back → Back lands on the search with its query, filters and scroll.
 * Opening the workshop from its own list forgets the search.
 */
class WorkshopOrigins<S : Any> {
    private val origins = LinkedHashMap<String, S>()

    /** [workshopId] was opened from [from] (a search). */
    fun opened(workshopId: String, from: S) {
        origins.remove(workshopId)
        origins[workshopId] = from
        while (origins.size > CAP) origins.remove(origins.keys.first())
    }

    /** [workshopId] was opened from the workshop list: Back from its index is the list again. */
    fun openedFromList(workshopId: String) { origins.remove(workshopId) }

    /** Where Back from [workshopId]'s index goes, or null for the workshop list. */
    fun of(workshopId: String): S? = origins[workshopId]

    private companion object { const val CAP = 32 }
}
