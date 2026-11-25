# EmekliRobot

Eski bir telefonu **araç takip cihazı** olarak kullanan Android uygulaması. Cihaz araçta sürekli şarjda kalır, SIM kart yoktur. Veriler önce diskte kuyruklanır, internet bulunduğunda sunucuya gönderilir.

## Hedef Cihaz

- Samsung Galaxy Note9, model SM-N960F, Android 10 (API 29)
- Ekran: 1440x2960, yaklaşık 6.4", 18.5:9
- Cihaz adı (sunucuya gönderilen): `EmektarNote9` (tek cihaz)
- SIM yok: internet yalnızca Wi-Fi/hotspot ile, zamanı belirsiz. GPS soğuk başlangıcı yavaş olabilir, cihaz saati ağdan düzelmez.
- Dağıtım: APK ile doğrudan kurulum (Play Store yok).

## Çalışma Mantığı

- **Kontak tespiti:** Şarj durumundan çıkarılır. Şarj başladı = kontak açıldı, şarj kesildi = kontak kapandı. Kontak kapalıyken araç duruyor kabul edilir, şarj kesilince pil tükenir.
- **Kuyruk:** Tüm kayıtlar önce Room (SQLite, WAL) veritabanına yazılır.
- **Gönderim:** İnternet gelince partiler halinde sunucuya gönderilir. Sunucu **200 OK** dönerse yalnızca o partideki kayıtlar silinir. Her kaydın UUID'si vardır (idempotency).
- **Kuyruk üst sınırı:** 200.000 kayıt. Dolunca en eski `KONUM` kayıtları silinir, şarj/araç olayları (`SARJ_*`, `ARAC_*`) korunur.

## Log Tipleri (tek tabloda, `logTipi` alanı ile ayrılır)

| Tip | Ne zaman |
|---|---|
| `SARJ_BASLADI` | Şarj başladı (kontak açıldı) |
| `SARJ_KESILDI` | Şarj kesildi (kontak kapandı) |
| `ARAC_HAREKET` | Araç duruştan harekete geçti (geçiş anı) |
| `ARAC_DURUYOR` | Araç hareketten duruşa geçti (geçiş anı) |
| `KONUM` | Hareket halindeyken her **15 sn** |
| `DURUM` | Durağanken her **15 dk** |
| `KONUM_ALINAMADI` | Konum alınamadığında |
| `CIHAZ_ISINDI` | Pil sıcaklığı 45 °C ve üstüne çıktı, kısıtlı moda geçildi |
| `CIHAZ_KRITIK_ISINDI` | Pil sıcaklığı 48 °C ve üstüne çıktı, her şey durduruldu |
| `CIHAZ_SOGUDU` | Pil sıcaklığı normale döndü, tam çalışmaya dönüldü |

Log tiplerinde Türkçe karakter kullanılmaz (yalnızca ASCII, büyük harf, alt çizgi).

## Kayıt Alanları

Enlem, boylam, doğruluk, pil yüzdesi, şarjda mı, pil sıcaklığı, konum zamanı (saniye hassasiyetli, ISO 8601 + saat dilimi, örn. `2026-11-25T14:03:07+03:00`), araç hareket/durağan durumu, log tipi, cihaz adı, kayıt UUID'si. Ek olarak `elapsedRealtime` ve konumun yaşı tutulur (saat sapması düzeltmesi için).

## Kurallar ve Eşikler

- **Hareket:** Hız 5 km/h üzerine çıkarsa `ARAC_HAREKET`. Hız 2 dk boyunca 5 km/h altında kalırsa `ARAC_DURUYOR`.
- **Şarj debounce:** Şarj durumu değişimi 15 sn sabit kalırsa kayıt atılır (gevşek kablo spam'ini önler).
- **`DURUM` kaydında konum:** Önce son bilinen konum. 15 dk'dan eskiyse tek seferlik GPS fix denenir, başarısızsa `KONUM_ALINAMADI`. Konumun yaşı kayda yazılır.
- **`KONUM_ALINAMADI`:** 60 sn fix gelmezse bir kez yazılır, konum geri gelene kadar tekrar yazılmaz.
- **Düşük pil:** Pil %5 altında yalnızca 15 dk'lık `DURUM` kaydı devam eder, GPS fix denenmez.
- **Güç/disk:** Durağanken GPS kapalı, hareket sensörü/Activity Recognition ile beklenir. Yazmalar toplu (batch). Konum eşiğin altında değiştiyse diske yazılmaz.

## Termal Koruma (yaz sıcağı)

Karar kaynağı pil sıcaklığıdır (`ACTION_BATTERY_CHANGED`). `PowerManager` termal durumu yalnızca ek bilgi olarak kullanılır (Samsung güvenilir raporlamayabilir). Eşikler ayar olarak tutulur, sahada değiştirilebilir. Durum geçişleri 30 sn sabit kalırsa kayıt atılır.

| Durum | Giriş | Davranış |
|---|---|---|
| NORMAL | başlangıç, ≤ 41 °C'ye inince | Tam çalışma (15 sn konum, normal gönderim) |
| ISINMIS | ≥ 45 °C | Konum 60 sn'de bir, sunucuya gönderim durur (kuyrukta bekler), arayüz ve gereksiz işler durur |
| KRITIK | ≥ 48 °C | GPS ve gönderim durur, yalnızca 15 dk'lık `DURUM` kaydı. Soğumayı bekler |

- Geri dönüş: KRITIK, 46 °C'nin altına inince ISINMIS'a; ISINMIS, 41 °C ve altına inince NORMAL'e döner (histerezis).
- Kayıtlar: NORMAL→ISINMIS `CIHAZ_ISINDI`, →KRITIK `CIHAZ_KRITIK_ISINDI`, →NORMAL `CIHAZ_SOGUDU`.
- Uygulama şarjı kesemez. Aşırı ısınmada Samsung kendi korumasıyla şarjı keserse `SARJ_KESILDI` oluşabilir. Pil sıcaklığı her kayıtta olduğundan sunucu tarafı nedenini ayırt edebilir.

## Kararlılık Gereksinimleri (7/24)

- Bellek sızıntısı olmamalı: bellekte büyüyen liste/cache yok, her şey Room'da. Debug'da LeakCanary, 24-72 saat soak testi.
- Crash olmamalı: global `UncaughtExceptionHandler` hatayı loglar ve servisi yeniden başlatır.
- Boot receiver, şarj bağlandı tetikleyicisi ve WorkManager periyodik watchdog ile servis her zaman ayakta tutulur.
- Samsung pil optimizasyonu / "uyuyan uygulamalar" muafiyeti gerekir (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).

## Teknoloji

Kotlin, minSdk 29, compileSdk/targetSdk 34, Foreground Service (`foregroundServiceType="location"`), FusedLocationProvider, Room, WorkManager, OkHttp/Retrofit. Arayüz minimum (durum ekranı). Paket adı: `com.emekli.robot`, uygulama adı: EmekliRobot.

## Geliştirme Ortamı (Mac, Apple Silicon)

Homebrew ile kuruldu (Android Studio yok, komut satırı araçları var):

- JDK 17: `/opt/homebrew/opt/openjdk@17` (keg-only, PATH'te değil)
- Android SDK kökü: `/opt/homebrew/share/android-commandlinetools` (platform 34, build-tools 34.0.0)
- `adb`: `/opt/homebrew/bin/adb`

Gradle komutlarından önce:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH
```

Telefonda Geliştirici seçenekleri > USB hata ayıklama açık olmalı. Diğer geliştirici seçenekleri (kayıt çıkış düzeyi, TTS, düğüm ağacı) varsayılan kalabilir.

## Arayüz (basit, 3 ekran)

Servis arka planda ayrı çalışır; ekranı açmak/kapatmak veri toplamayı etkilemez. Ekran sürekli açık tutulmaz (`KEEP_SCREEN_ON` yok). Canlı liste (Room `Flow`) yalnızca ekran açıkken dinlenir. Isınmış/kritik modda arayüz işleri durur. Çekirdek (konum, kuyruk, gönderim) önce yapılır, ekranlar sonra eklenir.

1. **Durum:** Servis çalışıyor mu, son konum, pil, sıcaklık ve termal durum, şarj durumu, araç hareket/durağan, kuyruktaki kayıt sayısı, son başarılı gönderim zamanı, "Şimdi gönder" butonu.
2. **Bekleyen loglar:** Henüz sunucuya gönderilmemiş kayıtlar, yeniden eskiye sıralı. Sayfalı yükleme (Paging 3), log tipine göre filtre. Alanlar: log tipi, zaman, koordinat, pil.
3. **Sunucu ayarları:** Servis URL'si (düzenlenebilir), gerekirse API anahtarı (EncryptedSharedPreferences), "Bağlantıyı test et" butonu (HTTP kodu, süre, hata mesajı gösterir). Test isteğinin içeriği API detaylarına göre belirlenecek.

Açık konular: Ayar ekranına PIN konulsun mu (karar bekliyor). URL HTTPS olmalı; HTTP ise özel izin tanımlanması gerekir.

## Sunucu API'si

Henüz verilmedi. Kullanıcı detaylı olarak sağlayacak (endpoint, istek formatı, kimlik doğrulama).
