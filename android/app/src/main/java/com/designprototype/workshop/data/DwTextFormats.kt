package com.designprototype.workshop.data

import com.designprototype.workshop.pincodeValidationError
import com.designprototype.workshop.ui.artisanPhoneValidationError

internal object DwTextFormats {

    private val EMAIL_RE = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

    private const val EMAIL_MESSAGE = "Enter a valid email address (name@example.com)."

    fun emailError(value: String?): String? {
        val trimmed = (value ?: "").trim()
        if (trimmed.isEmpty()) return null
        return if (EMAIL_RE.matches(trimmed)) null else EMAIL_MESSAGE
    }

    private val AADHAAR_MASK_RE = Regex("^X{8}(?:\\d{4}|X{4})$")

    fun aadhaarOrMaskError(value: String?): String? {
        val raw = (value ?: "").trim()
        if (raw.isEmpty()) return null
        val measured = ArtisanIdentity.normalizeAadhaar(raw).ifEmpty { raw }
        if (AADHAAR_MASK_RE.matches(measured)) return null
        return ArtisanIdentity.aadhaarError(measured)
    }

    fun error(format: String, value: String): String? = when (format) {
        "" -> null
        "EMAIL" -> emailError(value)
        "PHONE_IN" -> artisanPhoneValidationError(value)
        "AADHAAR" -> aadhaarOrMaskError(value)
        "PEHCHAN" -> pehchanError(value)
        "PINCODE" -> pincodeError(value)
        else -> null
    }

    private fun pincodeError(value: String): String? {
        val raw = value.trim()
        if (raw.isEmpty()) return null
        return pincodeValidationError(SEPARATORS.replace(raw, "").ifEmpty { raw })
    }

    private fun pehchanError(value: String): String? {
        val raw = value.trim()
        if (raw.isEmpty()) return null
        return ArtisanIdentity.pehchanError(ArtisanIdentity.normalizePehchan(raw).ifEmpty { raw })
    }

    private val SEPARATORS = Regex("[\\s\\u2010-\\u2015-]+")
}
