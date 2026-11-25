package com.emekli.robot.core

/**
 * Sunucuya giden `cihazAdi`. Sunucu kuralı: 1-50 karakter, yalnızca A-Z a-z 0-9 _ -.
 * Varsayılan ad marka + model + cihaza özgü kimliğin son 4 hanesidir; aynı model iki telefon birbirinden ayrılır.
 */
object DeviceName {
    const val MAX_LEN = 50
    private val TR = mapOf(
        'ç' to 'c', 'Ç' to 'C', 'ğ' to 'g', 'Ğ' to 'G', 'ı' to 'i', 'İ' to 'I',
        'ö' to 'o', 'Ö' to 'O', 'ş' to 's', 'Ş' to 'S', 'ü' to 'u', 'Ü' to 'U',
    )

    /** Geçersiz karakterleri '-' yapar, kırpar, en çok 50 karakter. Boş kalırsa null. */
    fun sanitize(raw: String): String? {
        val ascii = raw.map { TR[it] ?: it }.joinToString("")
        val s = ascii.trim().replace(Regex("[^A-Za-z0-9_-]+"), "-").trim('-').take(MAX_LEN).trim('-')
        return s.ifEmpty { null }
    }

    /** Örn. samsung-SM-N960F-a1b2 */
    fun auto(manufacturer: String?, model: String?, androidId: String?): String {
        val tail = (androidId ?: "").filter { it.isLetterOrDigit() }.takeLast(4).ifEmpty { "0000" }
        val head = sanitize("${manufacturer.orEmpty()}-${model.orEmpty()}") ?: "android"
        return sanitize("${head.take(MAX_LEN - 5)}-$tail") ?: "android-$tail"
    }
}
