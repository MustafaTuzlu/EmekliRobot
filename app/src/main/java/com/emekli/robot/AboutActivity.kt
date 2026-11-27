package com.emekli.robot

import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** "Program hakkında": en üstte sürüm numarası, altında uygulamanın nasıl çalıştığı. */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        findViewById<TextView>(R.id.txtVersion).text = "V${BuildConfig.VERSION_NAME}"

        val box = findViewById<LinearLayout>(R.id.content)
        SECTIONS.forEach { (title, body) ->
            box.addView(TextView(this).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(getColor(R.color.brand_green))
                setPadding(0, dp(18), 0, dp(4))
            })
            box.addView(TextView(this).apply {
                text = body
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setLineSpacing(dp(2).toFloat(), 1f)
            })
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        val SECTIONS = listOf(
            "Ne yapar?" to
                "Telefon araçta sürekli şarjda kalır ve aracın konumunu, pil durumunu ve olayları kaydeder. " +
                "Kayıtlar önce telefonun diskine yazılır. İnternet (Wi-Fi/hotspot) bulunduğunda sunucuya partiler halinde gönderilir. " +
                "Sunucu \"200 OK\" derse o kayıtlar telefondan silinir; başka bir cevapta silinmez, tekrar denenir. " +
                "Telefonda SIM yoktur, internet yokken hiçbir kayıt kaybolmaz.",

            "Kontak nasıl anlaşılır?" to
                "Kontak doğrudan okunamaz, şarj durumundan çıkarılır. Şarj girdi = kontak açıldı, şarj kesildi = kontak kapandı. " +
                "Gevşek kablo kayıt kirletmesin diye durum 15 saniye sabit kalırsa kayıt atılır.\n\n" +
                "Örnek: Kontağı 08:00:00'da açtınız. Kayıt 08:00:15'te oluşur ama saati 08:00:00 yazılır (değişimin ilk görüldüğü an).",

            "Sitedeki \"Bana son durumu göster\"" to
                "Siteden bir cihaz için bu butona basılınca sunucuda bir istek açılır. Telefon internetteyken (şarjda değilken de, Sunucu ayarlarındaki kutu işaretliyse) " +
                "dakikada bir sunucuya \"bana istek var mı?\" diye küçük bir soru sorar (yaklaşık 200 bayt); her başarılı kayıt " +
                "gönderiminden sonra da bir kez sorar. İstek varsa konum, pil, sıcaklık, hız, kuyruk gibi tüm bilgiyi bir kez gönderir. " +
                "Bu bilgi log olarak kaydedilmez, kuyruğa girmez; sitede yalnızca \"son durum\" olarak görünür.\n\n" +
                "Örnek: Telefon garajda Wi-Fi'sızdır, siz 10 kez butona bastınız. 09:00'da araç hotspot'a bağlanınca tek bir cevap gider " +
                "(10 değil). İstek 30 dakika içinde cevaplanmazsa zaman aşımına uğrar. Isınma durumunda (ISINDI/KRİTİK) bu yoklama yapılmaz.",

            "Veri kullanımı" to
                "Ana ekrandaki \"Veri kullanımı\" butonu, uygulamanın sunucuyla yaptığı gerçek veri alışverişini (gönderilen ve alınan) " +
                "saat, gün, hafta (Pazartesi başlar), ay ve yıl bazında grafikle gösterir. Her sunucu isteği bitince Android'in uygulama sayacı " +
                "okunur ve saatlik kovaya eklenir; ek pil harcamaz. Veriler yalnızca telefonda tutulur, siteye gönderilmez; \"Sıfırla\" ile silinir.\n\n" +
                "Örnek: Bu ay 6,4 MB görünüyorsa, SIM'li bir hatta uygulama bu ay yaklaşık 6,4 MB kota harcamış demektir.",

            "Güncelleme" to
                "Ana ekrandaki \"Güncellemeyi kontrol et\" butonu sunucudaki sürümü sorar. Yeni sürüm varsa APK'yı indirir, " +
                "SHA-256 ve paket bilgisiyle doğrular, sonra Android'in kurulum ekranını açar; \"Yükle\"ye basmanız gerekir " +
                "(Android 10 sessiz kurulum yapmaz). Kurulum bitince uygulama kendiliğinden yeniden başlar.\n\n" +
                "Örnek: 1.0.7 kurulu, sunucuda 1.0.8 var. Butona basarsınız, \"1.0.8 (16,6 MB)\" görürsünüz, Güncelle'ye basarsınız. " +
                "Araç hareket halindeyse ya da pil çok düşük ve şarjda değilse güncelleme başlamaz.",

            "Log tipleri ve ne zaman atılır" to
                "SARJ_BASLADI: şarj başladı, kontak açıldı.\n" +
                "SARJ_KESILDI: şarj kesildi, kontak kapandı.\n" +
                "ARAC_HAREKET: araç duruştan harekete geçti (geçiş anı).\n" +
                "ARAC_DURUYOR: araç hareketten durmaya geçti (geçiş anı).\n" +
                "KONUM: araç hareket halindeyken yaklaşık 15 saniyede bir.\n" +
                "DURUM: araç duruyorken 15 dakikada bir \"yaşıyorum\" kaydı. Araç \"hareketli\" görünürken 15 dakikadır konum alınamıyorsa da yazılır (son bilinen konum ve yaşıyla).\n" +
                "KONUM_ALINAMADI: GPS konumu alınamadı (tek sefer yazılır, konum gelince sıfırlanır).\n" +
                "CIHAZ_ISINDI / CIHAZ_KRITIK_ISINDI / CIHAZ_SOGUDU: pil sıcaklığı olayları.",

            "Örnek 1: Sabah yolculuğu" to
                "08:00 Kontak açılır → SARJ_BASLADI.\n" +
                "08:01 Araç hareket eder (hız 5 km/s üstü ya da 50 m yer değiştirme) → ARAC_HAREKET.\n" +
                "08:01 – 08:30 Her ~15 sn'de bir KONUM. Virajda yön 25° değişirse ek KONUM da yazılır.\n" +
                "08:30 Araç park eder. 2 dakika boyunca ne hız ne ilerleme olursa → ARAC_DURUYOR.\n" +
                "08:31 Kontak kapanır → SARJ_KESILDI.\n" +
                "Park edince GPS kapanır, ardından yalnızca 15 dakikada bir DURUM atılır.",

            "Örnek 2: Trafikte yavaş gitmek" to
                "Araç saatte 4 km ile ilerliyorsa hız kuralı (5 km/s) tetiklenmez. Bunun için ikinci kural vardır: " +
                "başlangıç noktasından 50 metre ve üstü uzaklaşma, hız ne olursa olsun hareket sayılır ve ARAC_HAREKET hemen yazılır. " +
                "Yavaş ama ilerleyen araç durmuş sayılmaz: ARAC_DURUYOR için 2 dakika boyunca hem hızın düşük hem 50 m ilerlemenin olmaması gerekir.",

            "Örnek 3: Park halinde" to
                "Araç park halindeyken GPS kapalıdır (pil ve ısı tasarrufu). Uyandıran şeyler: şarjın takılması, telefonun hareket sensörü ve 15 dakikalık alarm. " +
                "Kontak açıkken ek olarak her dakika 20 saniyelik kısa bir GPS yoklaması yapılır; sensör kaçırsa da hareket yakalanır.\n\n" +
                "Her 15 dakikada DURUM kaydı atılır. Konumu, son iyi konumların ortası (medyanı) olarak yazılır; böylece park halinde harita üzerinde zıplama olmaz.",

            "Örnek: Kapalı otopark" to
                "17:00 Araç hareket halinde kapalı otoparka girer, GPS kaybolur. 17:01'de tek bir KONUM_ALINAMADI yazılır. " +
                "Uygulama \"durdu\" kararı vermez (tünel de böyle görünür). En geç yaklaşık 17:30'dan itibaren 15 dakikada bir DURUM yazılır: " +
                "son bilinen konum, konumun yaşı ve pil bilgisiyle. GPS geri gelince normal kayıtlara dönülür.",

            "Örnek 4: Aşırı sıcak" to
                "Karar pil sıcaklığına göre verilir.\n" +
                "45 °C ve üstü → CIHAZ_ISINDI. Konum 60 sn'de bire düşer, gönderim durur (kuyrukta bekler). Bu uyarı kaydı istisnadır, hemen gönderilir.\n" +
                "48 °C ve üstü → CIHAZ_KRITIK_ISINDI. GPS ve gönderim durur, yalnızca 15 dk'lık DURUM atılır.\n" +
                "41 °C ve altına inince → CIHAZ_SOGUDU, her şey normale döner.\n" +
                "Pil %5'in altındaysa ve şarj yoksa GPS denenmez, yalnızca DURUM atılır.",

            "Konum nasıl daha doğru olur?" to
                "Yalnızca gerçek GPS kullanılır; Wi-Fi/baz tahmini (yüzlerce metre sapma) kullanılmaz. " +
                "Hareket halindeyken GPS saniyede bir okunur, her 15 saniyelik dilimin en iyi konumu yazılır. " +
                "Doğruluğu 50 m'den kötü ve imkânsız hızla \"sıçrayan\" konumlar kayda alınmaz; ana ekrandaki \"Zayıf konum\" sayacı bunları gösterir. " +
                "KONUM kayıtları konumun alındığı gerçek anla damgalanır. Hız ve gidiş yönü de kayda eklenir.\n\n" +
                "Not: Telefon cepteyken yürürseniz 50 m yer değiştirme de hareket sayılır.",

            "İnternet ve gönderim" to
                "İnternet gelince hemen, sonra en az 60 saniye arayla gönderilir. Partide en çok 200 kayıt vardır. " +
                "Hata olursa 30 sn, 1, 2, 4, 8, 15 dk bekleyerek tekrar denenir; internet yeniden gelince bekleme sıfırlanır. " +
                "API anahtarı yanlışsa (401) anahtar düzeltilene kadar durur. Kuyruk 200.000 kaydı aşarsa en eski KONUM kayıtları silinir; şarj ve araç olayları korunur.\n\n" +
                "Ana ekrandaki \"Bekleyen loglar\" henüz gönderilmemiş kayıtları, \"Şimdi gönder\" anında denemeyi sağlar.",

            "Ekran ve izinler" to
                "Uygulama arka planda ayrı bir servis olarak çalışır; ekranı kapatmak ya da uygulamayı kapatmak kaydı durdurmaz. " +
                "Konum için \"Her zaman izin ver\" ve pil optimizasyonundan muafiyet gerekir. Ayarlar ekranı şifrelidir.",
        )
    }
}
