package com.emekli.robot.core

/** Sunucunun kabul ettiği log tipleri (docs/emekli-robot-api.md §5). Türkçe karakter yok. */
object LogTypes {
    const val SARJ_BASLADI = "SARJ_BASLADI"
    const val SARJ_KESILDI = "SARJ_KESILDI"
    const val ARAC_HAREKET = "ARAC_HAREKET"
    const val ARAC_DURUYOR = "ARAC_DURUYOR"
    const val KONUM = "KONUM"
    const val DURUM = "DURUM"
    const val KONUM_ALINAMADI = "KONUM_ALINAMADI"
    const val CIHAZ_ISINDI = "CIHAZ_ISINDI"
    const val CIHAZ_KRITIK_ISINDI = "CIHAZ_KRITIK_ISINDI"
    const val CIHAZ_SOGUDU = "CIHAZ_SOGUDU"

    val ALL = listOf(
        SARJ_BASLADI, SARJ_KESILDI, ARAC_HAREKET, ARAC_DURUYOR, KONUM, DURUM,
        KONUM_ALINAMADI, CIHAZ_ISINDI, CIHAZ_KRITIK_ISINDI, CIHAZ_SOGUDU,
    )
}
