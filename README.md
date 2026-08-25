# Auto Clicker (Android / Kotlin)

Root gerektirmeyen, **AccessibilityService + `dispatchGesture()`** tabanlı otomatik tıklayıcı.
Diğer uygulamaların üzerinde yüzen bir balondan yönetilir, ana uygulama kapatılsa bile çalışır.

## Öne çıkanlar

| Alan | Ne var |
|---|---|
| Tıklama motoru | `dispatchGesture()` — root yok, `adb shell input` yok |
| Üstte çalışma | `TYPE_APPLICATION_OVERLAY` + foreground service (`specialUse`) |
| Balon | Sürüklenebilir, kenara yapışır, küçük daire ⇄ geniş panel |
| Durdurma | Tıklama limiti **ve/veya** süre limiti — hangisi önce dolarsa |
| Hız | Kalibrasyon (~3 sn) + çalışma anında kapalı çevrim kontrolcü |
| Güvenlik | Ses kısma tuşu, bildirimde “Durdur”, ekran kapanınca otomatik dur |

## Mimari

```
data/      ClickerSettings, SettingsRepository (DataStore Preferences)
engine/    ClickEngine        – jest döngüsü + adaptif kontrolcü + kalibrasyon
           DeviceLoadMonitor  – termal / /proc/stat / Choreographer
           EngineState        – tüm canlı durum (StateFlow), tek kaynak
           ClickerAccessibilityService – dispatchGesture + ses tuşu yakalama
           ClickerController  – UI ↔ motor arası tek giriş noktası
overlay/   OverlayService     – yüzen balon, hedef seçici, kalibrasyon kalkanı
           TargetCanvasView   – nişangâh çizimi
ui/        MainActivity + Compose ekranı (izin akışı, ayarlar, canlı durum)
util/      Notifications, Formatting, Haptics, Permissions
```

MVVM: Compose ekranı `MainViewModel` üzerinden DataStore’u okur/yazar; motor ve balon
aynı `EngineState` akışlarını dinler, böylece ana uygulama kapalıyken de balon doğru veriyi gösterir.

### Neden overlay tarafında Compose değil, klasik View?
Balon 100 ms’de bir güncelleniyor ve bu, 100 CPS’te tıklama yapan bir uygulamada
doğrudan CPU bütçesinden yeniyor. `TextView.setText()` yolu recomposition’dan belirgin
şekilde ucuz. Ana uygulama ekranı tamamen Compose.

## Adaptif hız sistemi

**Kalibrasyon (~3 sn):** 10 → 20 → 40 → 60 → 80 CPS, her basamak 600 ms.
`GestureResultCallback.onCompleted` sayılır; gerçekleşen/hedef oranı **%90**’ın altına
düşen ilk basamakta durulur ve bir önceki basamak `maxSafeCps` olarak DataStore’a yazılır.
Test dokunuşları alttaki oyuna gitmesin diye kalibrasyon boyunca tam ekran bir
“kalkan” penceresi açılır.

**Çalışma anındaki kontrolcü** (500 ms’de bir):

| Koşul | Eylem | Gerekçe |
|---|---|---|
| `achieved/target < 0.85` | hedefi **%10 düşür** | Jest gidiş-dönüşünde doğal %5-10 dalgalanma var; 0.90 eşiği bu gürültüde bile tetiklenirdi |
| `> 0.97` + termal normal + jank yok | hedefi **%5 artır** | Erken artış sistemi doygunluğa itip salınım yaratır |
| Termal `SEVERE` / `CRITICAL` | %20 / %40 düşür | Orana bakmadan geri çekil |
| CPU > %92 | artışı engelle | Doygun CPU’da hızlanmak sadece kaçırılan tıklama üretir |

Asimetrik adım (**%10 aşağı / %5 yukarı**) TCP’nin AIMD mantığı: hızlı geri çekil, yavaş
tırman → tavana yaklaşırken üstüne çıkmadan oturur. Aralık 500 ms çünkü `achievedCps`
1 saniyelik pencereden hesaplanıyor; ölçüm penceresinin yarısından sık karar vermek
henüz oluşmamış veriye tepki vermek olur (Nyquist).

Taban 5 CPS, tavan `min(maxSafeCps, kullanıcı limiti)`.

### Gerçekçi beklenti
Sistem servis başına **aynı anda tek jest** çalıştırır; yeni jest gönderilirse devam
eden **iptal edilir**. Bu yüzden ulaşılabilir CPS doğrudan jest gidiş-dönüş süresine
bağlıdır ve tipik cihazlarda 30–60 CPS civarındadır. Adaptif kontrolcünün var olma
sebebi tam olarak budur — 100 CPS sliderı bir *tavan*, bir vaat değil.

## Derleme

Gereken: **JDK 17**, **Android SDK platform 35 + build-tools 35.0.0**.

```bash
./gradlew assembleRelease
# APK -> out/autoclicker.apk
```

Release, doğrudan kurulabilsin diye **debug keystore** ile imzalanır
(`keystore/debug.keystore`, yoksa `~/.android/debug.keystore`, o da yoksa `keytool`
ile otomatik üretilir). `minifyEnabled false`.

> Bu imza Play Store’a yüklenemez; yalnızca yan yükleme (sideload) içindir.

SDK yoksa:

```bash
# cmdline-tools kuruluysa
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
echo "sdk.dir=$ANDROID_HOME" > local.properties
```

CI: her push’ta `.github/workflows/build-apk.yml` APK’yı derler ve
**Actions → ilgili run → Artifacts → `autoclicker-apk`** altına yükler.

## Telefona kurma

```bash
adb install -r autoclicker.apk
```

adb yoksa: APK’yı telefona kopyala → dosya yöneticisinden aç →
“Bilinmeyen kaynaklara izin ver” çıkınca ilgili uygulamaya (Dosyalar/Chrome) izin ver → Kur.

## İlk açılışta izin sırası

1. **Diğer uygulamaların üzerinde göster** — balon için (zorunlu)
2. **Erişilebilirlik servisi** — Ayarlar → Erişilebilirlik → Auto Clicker → Aç (zorunlu)
3. **Bildirim izni** — kalıcı bildirim + “Durdur” kısayolu (önerilir, zorunlu değil)

Ekrandaki kartlar canlı durum gösterir: verilmeyen zorunlu izin **kırmızı**,
verilmeyen isteğe bağlı izin **turuncu**, verilen **yeşil**.

## Kullanım

1. İzinleri ver → **Kalibre et** (isteğe bağlı ama önerilir)
2. **Hedefleri seç** → ekranda tıklanacak noktalara dokun (en fazla 4; bir noktaya
   tekrar dokunmak siler)
3. Durdurma koşullarını seç (sayı ve/veya süre)
4. **Başlat** → oyuna geç. Balon üstte kalır: `247 / 500 • 3.2 sn / 10 sn`
5. Acil durum: **ses kısma tuşu**, bildirimdeki **Durdur**, veya ekranı kapat
