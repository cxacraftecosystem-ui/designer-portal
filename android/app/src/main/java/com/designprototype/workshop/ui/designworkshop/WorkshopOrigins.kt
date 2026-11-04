package com.designprototype.workshop.ui.designworkshop

class WorkshopOrigins<S : Any> {
    private val origins = LinkedHashMap<String, S>()

    fun opened(workshopId: String, from: S) {
        origins.remove(workshopId)
        origins[workshopId] = from
        while (origins.size > CAP) origins.remove(origins.keys.first())
    }

    fun openedFromList(workshopId: String) { origins.remove(workshopId) }

    fun of(workshopId: String): S? = origins[workshopId]

    private companion object { const val CAP = 32 }
}
