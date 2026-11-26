# EmekliRobot

Eski bir telefonu **araç takip cihazı** olarak kullanan Android uygulaması. Cihaz araçta sürekli şarjda kalır, SIM kart yoktur. Veriler önce diskte kuyruklanır, internet bulunduğunda sunucuya gönderilir.

## Hedef Cihaz

- Samsung Galaxy Note9, model SM-N960F, Android 10 (API 29)
- Ekran: 1440x2960, yaklaşık 6.4", 18.5:9
- Cihaz adı (sunucuya gönderilen `cihazAdi`): Sunucu ayarları ekranında düzenlenir; boşsa otomatik `marka-model-kimliğinSon4Hanesi` (örn. `samsung-SM-N960F-5a61`), böylece birden çok telefon ayrılır. Kural: 1-50 karakter, yalnızca `A-Z a-z 0-9 _ -`. Bu telefonun eski adı `EmektarNote9`.
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

## Uygulama Mimarisi (kodda)

- `service/TrackerService`: ön plan servisi (location). `service/TrackerEngine`: tüm kurallar (şarj, hareket, termal, DURUM). Alarm (15 dk) ve `WatchdogWorker` servisi ayakta tutar, `BootReceiver` açılışta başlatır.
- Araç durağanken GPS kapalı. Uyanma kaynakları: şarj bağlanması, significant-motion sensörü, 15 dk alarmı. Uyanınca 60 sn'lik kısa GPS penceresi açılır, hız 5 km/h üstü (art arda 2 örnek) görülürse HAREKET moduna geçilir.
- Hareket sensörüne güvenilmez (pürüzsüz sürüşte tetiklenmeyebilir), bu yüzden yedekler var: şarj takılıyken (kontak açık, termal NORMAL) her 60 sn'de 20 sn'lik GPS yoklaması; her 15 dk'lık DURUM kaydından sonra 30 sn'lik yoklama; sensör hiç yoksa şarj yokken 3 dk'da bir 30 sn. Ana ekranda "Hareket sens" satırı sensörün var olup olmadığını ve kaç kez tetiklendiğini gösterir.
- `data/`: Room kuyruğu (`logs` tablosu = kuyruk, sunucu 200 dönünce satır silinir, 422'de `quarantined`). `sync/SyncEngine`: partili gönderim, backoff, 401/413/422 davranışı.
- `konumZamani` = kaydın oluştuğu andaki duvar saati. Konumun kendi yaşı `konumYasiMs` ile gider. Konum alınamayan kayıtta enlem/boylam/dogruluk/konumYasiMs null.
- Her `DURUM` kaydı atılır. 15 dk'dan eski konumda yeni fix denenir; başarısızsa önce `KONUM_ALINAMADI` (tek sefer), sonra son bilinen konumla `DURUM`.
- Saf mantık (hareket, termal, debounce, zaman, gönderim gövdesi) birim testlidir: `./gradlew testDebugUnitTest`.

## Kurallar ve Eşikler

- **Hareket (iki kural, hangisi önce):** (1) Hız art arda 2 örnek 5 km/h üstünde. (2) Yer değiştirme: referans noktadan **50 m ve üstü** uzaklaşma tek örnekte hemen `ARAC_HAREKET` yapar (hız ne olursa olsun, trafikte 4 km/h dahil). Korumalar: fix doğruluğu ≤ 50 m olmalı, eşik iki fix'in doğruluk toplamından küçük olamaz, ve GPS (Doppler) hızı ≥ 2 km/h olmalı (park halinde GPS zıplamasını hareket saymamak için). Referans nokta ilk fix ve her `ARAC_DURUYOR` anındaki konumdur; sürekli güncellenmez.
- **Durma:** `ARAC_DURUYOR` için 2 dk boyunca hem 5 km/h üstü hız hem 50 m'lik ilerleme olmaması gerekir (yavaş ama ilerleyen trafik durmuş sayılmaz).
- Yürürken de (telefon cepte) 50 m yer değiştirme hareket sayılır; bu bilinen bir sonuçtur, test için de kullanışlıdır.
- **Konum kalite kapısı (`FixFilter`):** GPS fix yokken telefon Wi-Fi/baz tahmini verir (doğruluk 100-600 m) ve haritada yüzlerce metrelik sahte sıçrama yaratır (saha verisiyle doğrulandı). Doğruluğu 50 m'den kötü konumlar `KONUM` kaydına ve hareket kararına girmez ("Zayıf konum: N reddedildi" sayacı ana ekranda). Önceki iyi konuma göre 150 km/s'i aşan sıçramalar da reddedilir (üst üste 3 kez gelirse referans yanlış sayılıp kabul edilir). Aynı fix'in iki kez iletilmesi (ARAC_HAREKET sonrası çift KONUM) elenir. Zayıf konum yalnızca 15 dk'lık `DURUM` kaydında, doğruluğuyla birlikte kullanılabilir. 60 sn iyi konum gelmezse tek `KONUM_ALINAMADI`.
- **Konum doğruluğu (kayıt kalitesi):** Yalnızca gerçek GPS (`LocationManager.GPS_PROVIDER`, fused/Wi-Fi/baz yok). Sahte (mock) konum ve <4 uydulu fix reddedilir. Hareketteyken GPS 1 sn'de bir örneklenir (ISINMIS: 5 sn), her 15 sn'lik pencerenin en iyi fix'i (doğruluk + saniye başına 3 m eskilik cezası) `KONUM` yazılır (`BestFixWindow`). Yön ≥25° değişirse (hız ≥10 km/s, doğruluk ≤20 m, en az 3 sn arayla) pencere beklenmeden ek `KONUM` yazılır (`CornerDetector`). `KONUM`/`ARAC_*` kayıtlarının `konumZamani`/`elapsedRealtime` değeri fix'in kendi anıdır (`konumYasiMs` ≈ 0); `DURUM` ve diğerleri kayıt anıdır. `DURUM` konumu park halinde son ≤9 iyi fix'in medyanıdır (`ParkedPosition`, ≥3 örnek, 30 dk'dan eskiler atılır, 50 m'den fazla taşınınca sıfırlanır). `DURUM` için fix beklenirken önce ≤20 m aranır, 15 sn sonra ≤50 m kabul edilir, 60 sn'de gelmezse `KONUM_ALINAMADI`. Hız (`hizKmh`) ve yön (`yon`, hız ≥2 km/s iken) Room'da tutulur (`speedKmh`, `bearingDeg`, DB v2); sunucuya `Config.SEND_MOTION_FIELDS` true iken gider.
- Durma sayacında yalnızca güvenilir konumlar "ilerleme" sayılır (kötü sıçramalar `ARAC_DURUYOR`'u geciktirmez).
- `SARJ_*` ve `CIHAZ_*` kayıtları, debounce bekleme süresi (15/30 sn) değil değişimin ilk görüldüğü an olarak damgalanır (zaman ve `elapsedRealtime` birlikte geri çekilir).
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
| ISINMIS | ≥ 45 °C | Konum 60 sn'de bir, sunucuya gönderim durur (kuyrukta bekler), arayüz ve gereksiz işler durur. İstisna: `CIHAZ_ISINDI` ve `CIHAZ_KRITIK_ISINDI` uyarıları oluşur oluşmaz bir kez gönderilir (`SyncPolicy`), o sırada kuyruktaki diğer kayıtlar da gider |
| KRITIK | ≥ 48 °C | GPS ve gönderim durur, yalnızca 15 dk'lık `DURUM` kaydı. Soğumayı bekler |

- Geri dönüş: KRITIK, 46 °C'nin altına inince ISINMIS'a; ISINMIS, 41 °C ve altına inince NORMAL'e döner (histerezis).
- Kayıtlar: NORMAL→ISINMIS `CIHAZ_ISINDI`, →KRITIK `CIHAZ_KRITIK_ISINDI`, →NORMAL `CIHAZ_SOGUDU`.
- Uygulama şarjı kesemez. Aşırı ısınmada Samsung kendi korumasıyla şarjı keserse `SARJ_KESILDI` oluşabilir. Pil sıcaklığı her kayıtta olduğundan sunucu tarafı nedenini ayırt edebilir.

## Kararlılık Gereksinimleri (7/24)

- Bellek sızıntısı olmamalı: bellekte büyüyen liste/cache yok, her şey Room'da. 24-72 saat soak testi.
- Crash olmamalı: global `UncaughtExceptionHandler` hatayı loglar ve servisi yeniden başlatır.
- Boot receiver, şarj bağlandı tetikleyicisi ve WorkManager periyodik watchdog ile servis her zaman ayakta tutulur.
- Samsung pil optimizasyonu / "uyuyan uygulamalar" muafiyeti gerekir (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).

## Teknoloji

Kotlin, minSdk 29, compileSdk/targetSdk 34, Foreground Service (`foregroundServiceType="location"`), GPS (LocationManager), Room, WorkManager, OkHttp/Retrofit. Arayüz minimum (durum ekranı). Paket adı: `com.emekli.robot`, uygulama adı (ekranda): Emekli Robot (paket/repo adı EmekliRobot kalır). Simge: sakallı Android robotu (`ic_launcher_foreground.xml`, uyarlanabilir). LeakCanary kaldırıldı (telefonda ayrıca "Leaks" simgesi çıkarıyordu).

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

**Sunucu ayarları ekranı şifre ile korunur** (6 haneli sabit şifre). Şifre repoya yazılmaz: derleme sırasında gitignore'lu `local.properties` içindeki `SETTINGS_PIN` değerinden okunur ve uygulamada yalnızca tuzlu hash olarak tutulur. Bu bir kolaylık kilididir, APK'yı söken biri aşabilir.

Açık konular: URL HTTPS olmalı; HTTP ise özel izin tanımlanması gerekir (karar bekliyor).

## Anlık durum isteği ("Bana son durumu göster")

Site bir cihaz için komut açar (sunucuda tek bekleyen komut, 30 dk geçerli). Telefon `CommandPoller` ile `GET komut.php?cihazAdi=` yoklar: internet var + termal NORMAL + (şarjda ya da ayarda "şarjda değilken de yokla" işaretli, varsayılan işaretli) iken 60 sn'de bir (AlarmManager `setAndAllowWhileIdle`, ekran kapalıyken Handler çalışmaz; Doze'da ~9 dk'ya uzayabilir; internet yokken alarm kurulmaz); ana ekranda "Komut yoklama" satırı son sonucu gösterir; "Şimdi gönder" butonu da hemen yoklar, ayrıca her başarılı kayıt gönderiminden sonra bir kez. Komut `DURUM_ISTE` ise `AnlikBuilder` ile tüm bilgiyi toplayıp `POST anlik.php` yapar (en az 30 sn arayla). Bu veri Room kuyruğuna/log tablosuna girmez. Hatada 5 dk bekler, ekrandaki gönderim durumunu bozmaz. Sözleşme: sunucu repo `docs/emekli-robot-api.md` v1.1.

## Veri kullanımı ölçümü

Ana ekranda "Veri kullanımı" butonu (`UsageActivity`). `net/UsageMeter`: OkHttp `EventListener` her çağrı bitince (callEnd/callFailed) Android'in uygulama (UID) `TrafficStats` sayaçlarını okur, önceki okumayla farkı (`UsageBuckets.delta`, yeniden başlatmada sayaç küçülürse yeni değer) saatlik kovaya yazar (Room `usage` tablosu, DB v3). Ek uyanma yok. Saat/gün/hafta (PAZARTESİ başlar)/ay/yıl toplamları `UsageBuckets` ile satırlardan hesaplanır, grafik kütüphanesiz (`UsageChartView`). Veri siteye gönderilmez; "Sıfırla" tabloyu siler (şifre yok). Birim 1 KB = 1024 bayt.

## Uygulama içi güncelleme

Ana ekranda "Güncellemeyi kontrol et" (`UpdateActivity`, yalnızca elle). `GET surum.php` ({versionCode, versionName, sha256, boyut, notlar}) ile sürüm sorulur; `versionCode` mevcuttan büyükse `GET indir.php` ile `cache/updates/emekli.apk` indirilir, boyut + SHA-256 + paket adı/sürüm kodu doğrulanır, FileProvider ile Android kurulum ekranı açılır (Android 10'da her seferinde kullanıcı onayı; "bilinmeyen kaynak" izni gerekir). Araç hareketteyken veya pil <%15 ve şarjda değilken başlamaz. Kurulum sonrası `MY_PACKAGE_REPLACED` ile servis yeniden başlar. **İmza:** güncelleme eski sürümle aynı anahtarla imzalı olmalı; debug anahtarı `~/.android/debug.keystore` (yedek: `~/Documents/emekli-debug.keystore.yedek`) değiştirilmez, yoksa telefona silip yeniden kurmak gerekir. Yayın: `release/<sürüm>/` (gitignore'lu) içine `emekli.apk` + `surum.json` ({versionCode, versionName, sha256 `shasum -a 256`, boyut `stat -f%z`, notlar}) hazırlanır, kullanıcı ikisini sunucuda `api/emekli/yayin/` klasörüne FTP ile elle atar (sözleşme v1.2, sunucu repo). Her yeni APK çıktığında kullanıcıya FTP'ye yüklemesi gerektiği, hedef dizinin (`api/emekli/yayin/`) ve `release/<sürüm>/` konumunun linki mutlaka yazılır. Canlıda doğrulandı: 1.0.7 -> 1.0.8 (2026-11-26). Geri dönüş: `adb install -r -d release/<eski>/emekli.apk`.

## Sürüm numarası

`app/build.gradle.kts` içinde `versionName` (şu an 1.0.8) ve `versionCode` (= major*100 + minor*10 + patch). **Her geliştirmede (commit'te) sürüm bir artırılır:** 1.0.0 → 1.0.1 → … → 1.0.9 → 1.1.0 → … → 1.9.9 → 2.0.0 (her basamak 0-9). Sürüm "PROGRAM HAKKINDA" ekranında en üstte görünür. Uygulamanın davranışı değişirse o ekrandaki açıklamalar (`AboutActivity.kt`) da güncellenir.

## Sunucu API'si

Sunucu ayrı repoda: `/Users/mustafa.tuzlu/Repo/MarmaraOfisEtiket`. **Tek doğru kaynak (sözleşme):** `/Users/mustafa.tuzlu/Repo/MarmaraOfisEtiket/docs/emekli-robot-api.md` (v1). Aşağıdaki özet ile çelişirse sözleşme geçerlidir.

- Taban adres: `https://marmaraofisetiket.net/api/emekli/` (yalnızca HTTPS, `www` yok). `http` 301 verir ve POST gövdesi kaybolur, 3xx başarı sayılmaz.
- Uç noktalar: `GET ping.php` ("Bağlantıyı test et"), `POST kayitlar.php`.
- Kimlik: her istekte `X-Api-Key` başlığı. Anahtar ayar ekranından girilir (EncryptedSharedPreferences), koda/repoya yazılmaz. Anahtarı kullanıcı verir.
- Zaman aşımı: bağlantı 10 sn, okuma 30 sn.
- Gönderim zarfı: `{gonderimElapsedRealtime (zorunlu, her denemede o an okunur), gonderimZamani (önerilir), kayitlar[1..500]}`.
- Kayıt alanları: `kayit` (UUID, kayıt oluşurken bir kez üretilir, yeniden denemede aynı kalır), `cihazAdi`, `logTipi`, `enlem`, `boylam` (ikisi birlikte dolu ya da birlikte null), `dogruluk`, `pilYuzdesi`, `sarjdaMi` (bool), `pilSicakligi`, `aracHareketli` (bool), `konumZamani` (ISO 8601 saat dilimli, zorunlu), `elapsedRealtime` (kayıt oluşurken okunur, zorunlu), `konumYasiMs`.
- **200** = partideki tüm kayıtlar sunucuda kalıcı, telefon partiyi siler. Aynı UUID tekrar gelirse hata yok (yeniden gönderim güvenli).
- Hata davranışı: ağ hatası/timeout/5xx/503/3xx → silme, backoff (30 sn → 1 dk → 2 dk … en çok 15 dk, ağ gelince hemen) ile aynı partiyi yeniden dene. 401 → silme, "API anahtarı hatalı" göster, anahtar düzelene dek dur. 413 → partiyi küçült (örn. 200). 422 `gecersiz_kayit` → hiçbir kayıt yazılmadı; `hatalar[].kayit/sira` ile listelenen kayıtları ayıkla (karantina/sil), kalanı yeniden gönder. 400/405/415 → bug, logla, döngüye sokma.
- Ping yanıtı: 200 `ok` + `veritabani: ok` + `sunucuUnixMs` (saat sapmasını göstermek için); 401 anahtar yanlış; 503 veritabanı/yapılandırma hatası.
- Sunucu yalnızca şu log tiplerini kabul eder (başkası 422): SARJ_BASLADI, SARJ_KESILDI, ARAC_HAREKET, ARAC_DURUYOR, KONUM, DURUM, KONUM_ALINAMADI, CIHAZ_ISINDI, CIHAZ_KRITIK_ISINDI, CIHAZ_SOGUDU. Yeni tip için önce sunucudaki liste güncellenmeli.

Açık kararlar: geçersiz kayıtta tüm parti 422 ile reddediliyor (sunucuda "geçerlileri yaz" moduna çevrilebilir). Sunucu DB şifresinin değiştirilmesi önerildi (kullanıcıda). API anahtarını kullanıcı telefona ayar ekranından girecek.
