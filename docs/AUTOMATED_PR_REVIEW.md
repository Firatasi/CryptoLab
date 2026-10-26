# 🛡️ Otomatik PR Review Sistemi (Gemini API Destekli)

Bu doküman, `fullstack-excercises` (ve türetilen çok projeli monorepolar) için geliştirilen sürdürülebilir, güvenli ve tamamen ücretsiz katmanla çalışan otomatik PR Review sisteminin mimarisini, kurulumunu ve operasyonel detaylarını açıklar.

---

## 1. Yaklaşım ve Mimari Tercihleri

### Neden Özel Hafif Script (Gemini + GitHub REST API)?
Piyasadaki hazır botlar (CodeRabbit, Qodo/PR-Agent vb.) ya belirli bir deneme süresinden sonra ücretli plana geçmekte, ya repository kodlarınızı harici bir SaaS sunucusuna aktarmakta ya da çok projeli/monorepo yapılarında esnek konfigürasyon sunmamaktadır.

Geliştirdiğimiz çözüm:
- **Sıfır SaaS / Aracı Sunucu:** Yalnızca GitHub Actions ve doğrudan Google Gemini API arasında çalışır.
- **Kalıcı Ücretsiz Katman:** Google AI Studio'nun kalıcı `$0` Flash modelleri (`gemini-2.0-flash` ve `gemini-1.5-flash`) kullanılır.
- **Doğrudan Inline Yorumlar:** GitHub REST API (`POST /repos/{owner}/{repo}/pulls/{pr}/reviews`) kullanılarak somut hatalar doğrudan diff satırına bağlanır. Mükerrer commit yorumu veya timeline spam'i yapılmaz.
- **Dinamik Proje Keşfi:** Proje sınırları klasör bazlı değil, manifest dosyaları (`pom.xml`, `package.json`, `requirements.txt` vb.) üzerinden otomatik taranır.

---

## 2. Güncel Model, SDK ve Kota Bilgileri (2025/2026 Doğrulaması)

### Model ve SDK Desteği
- **Varsayılan Model:** `gemini-2.0-flash` (Hızlı yanıt, düşük gecikme, JSON Schema desteği).
- **Yedek Model (Fallback):** `gemini-1.5-flash` (Kararlı ikincil model).
- **SDK Desteği:** Resmi `google-genai` Python SDK'sı kullanılır. SDK ortamda bulunamadığında veya bağımlılık hatası oluştuğunda sistem otomatik olarak standart Python HTTP (`urllib.request`) fallback'ine geçer.

### Ücretsiz Kota ve Ücretlendirme
- **Requests Per Minute (RPM):** 15 istek / dakika
- **Requests Per Day (RPD):** 1.500 istek / gün
- **Tokens Per Minute (TPM):** 1.000.000 token / dakika
- **Maliyet:** $0 (Kredi kartı gerekmez).
- **Google AI Pro Uyarısı:** Bireysel Google AI Pro / One AI Premium abonelikleri Gemini API için sınırsız erişim **sağlamaz**. API kotaları Google AI Studio / Google Cloud Console projesi üzerinden bağımsız olarak tanımlanır.

### Veri Kullanım ve Gizlilik Koşulları (Önemli!)
> [!WARNING]
> Google AI Studio **Free Tier** kullanımında iletilen girdiler ve çıktılar, Google tarafından hizmet geliştirme ve model eğitimi amacıyla kaydedilip insan denetçiler tarafından incelenebilir.
> Hassas şirket içi kodlar için ücretli katman (Pay-as-you-go) bağlanarak veri eğitimi dışı (opt-out) tutulabilir.
> Sistemimiz bu riski minimize etmek için **Pre-flight Secret Masking** uygulayarak API anahtarlarını, token'ları ve parolaları diff'ten temizledikten sonra modele iletir.

### GitHub Actions Limitleri ve Fork PR Kısıtları
- **Public Repolar:** GitHub Actions dakikaları tamamen ücretsiz ve sınırsızdır.
- **Private Repolar:** Standart ücretsiz GitHub hesaplarında aylık 2.000 dakika dahildir (bu inceleme workflow'u çalıştırma başına ~20-30 saniye sürer).
- **Fork PR Güvenliği:** Fork'lardan gelen PR'larda `pull_request` tetikleyicisi GitHub Secrets'a erişemez (`GEMINI_API_KEY` boş gelir). Script bu durumu tespit eder ve güvenli bir şekilde incelemeyi atlayarak "Fork PR: Güvenlik politikası gereği AI review atlandı" notu düşer.

---

## 3. Kurulum Adımları (GitHub Secrets & Ayarlar)

### Adım 1: Gemini API Anahtarı Alın
1. [Google AI Studio](https://aistudio.google.com/) adresine gidin.
2. Google hesabınızla giriş yapıp **"Get API key"** butonuna tıklayın.
3. Yeni bir API Key oluşturun ve kopyalayın (Kredi kartı girmeniz gerekmez).

### Adım 2: GitHub Repository Secret Tanımlayın
1. GitHub'da deponuza gidin: **Settings -> Secrets and variables -> Actions**.
2. **"New repository secret"** butonuna tıklayın:
   - **Name:** `GEMINI_API_KEY`
   - **Secret:** *(Kopyaladığınız Gemini API anahtarı)*
3. **Add secret** diyerek kaydedin.

### Adım 3: Workflow İzinlerini Doğrulayın
1. Deponuzda: **Settings -> Actions -> General**.
2. **Workflow permissions** bölümünde:
   - **"Read and write permissions"** seçili olmalıdır (Botun PR'a review yorumu yazabilmesi için `pull-requests: write` yetkisi gereklidir).
   - **"Allow GitHub Actions to create and approve pull requests"** kutucuğunu işaretleyin.
3. Kaydedin.

---

## 4. Çok Projeli Yapı ve Yeni Proje Ekleme

Sistem dinamik manifest tarayıcısı (`ProjectDetector`) kullanır:
- Kökten veya alt klasörlerden bağımsız olarak şu dosyaları otomatik tanır:
  - `pom.xml` -> **Java / Spring Boot (Maven)**
  - `build.gradle` / `build.gradle.kts` -> **Java / Kotlin (Gradle)**
  - `package.json` -> **Node.js / React / Next.js / TypeScript / JavaScript**
  - `pyproject.toml` / `requirements.txt` -> **Python**
  - `go.mod` -> **Go**
  - `Cargo.toml` -> **Rust**
  - `Dockerfile` / `docker-compose.yml` -> **Docker / DevOps**

### Yeni Bir Proje Eklendiğinde Ne Yapmalıyım?
**Hiçbir şey yapmanıza gerek yoktur.**
Örneğin `exercises/kafka-challenge` adında yeni bir klasör açıp içine bir `pom.xml` veya `package.json` koyduğunuzda:
1. Bot PR diff'ini incelerken dosyanın ait olduğu `exercises/kafka-challenge` klasörünü ve teknolojisini otomatik olarak keşfeder.
2. İnceleme özetinde proje adını ve teknolojisini ayrı bir satır olarak raporlar.
3. İsteğe bağlı olarak `.github/review-config.yml` dosyasına bu proje için özel review kuralları ekleyebilirsiniz.

---

## 5. İlk PR ile Test Etme

Sistemi denemek için basit bir PR açabilirsiniz:
1. Yeni bir branch oluşturun:
   ```bash
   git checkout -b test/pr-review-demo
   ```
2. Herhangi bir kod dosyasında bilerek bir hata veya sınır durum ekleyin (örneğin bir metotta potansiyel null pointer veya log içine hassas veri):
   ```java
   // Test amaçlı
   String apiKey = "AIzaSyD-1234567890abcdefghijklmnopqrst";
   ```
3. Commit ve push yapın:
   ```bash
   git add .
   git commit -m "test: pr review denemesi"
   git push origin test/pr-review-demo
   ```
4. GitHub üzerinde bu branch için bir Pull Request açın.
5. GitHub Actions sekmesinde `Gemini PR Review` workflow'unun çalıştığını ve PR sayfasına inline yorumların geldiğini gözlemleyin.

---

## 6. Örnek Türkçe Review Çıktısı

### A) PR İnceleme Özeti (Review Body):
```markdown
<!-- gemini-pr-review:bd24ef4 -->
## 🛡️ Otomatik Kod ve Güvenlik İncelemesi (Gemini)

**Genel Durum:** 🔴 **KRİTİK RİSKLER MEVCUT** — Merge öncesi P0/P1 bulgular giderilmelidir.

### 📝 Yönetici Özeti
PR kapsamında `backend` servisinde parola doğrulama ve `ai` modülünde dosya işleme mantığı güncellenmiştir. Tespit edilen 1 kritik güvenlik açığı ve 1 potansiyel kaynak sızıntısı mevcuttur.

### 📦 Etkilenen Projeler ve Kapsam
| Proje Kökü | Teknoloji | Değişiklik |
|:---|:---|:---|
| `backend` | Java / Spring Boot (Maven) | 2 dosya |
| `ai` | Python | 1 dosya |

> **Mimari Analiz:** Backend servislerinde veri bütünlüğü korunmuş ancak kimlik doğrulama katmanında kritik açık tespit edilmiştir.

---
*🤖 İncelenen Commit: `bd24ef4` | Kapsam: 2 bulgu | Durum: Tamamlandı (gemini-2.0-flash)*
```

### B) İlgili Satır Üzerindeki Inline Yorum (Files Changed sekmesinde):
```markdown
### 🔴 [P0] Hardcoded API Anahtarı ve Güvensiz Doğrulama
- **Konum:** backend/account-service/src/main/java/AuthService.java (Satır: 42)
- **Tetiklenme Koşulu:** İstemciden gelen token doğrulanırken statik anahtar ile karşılaştırma yapılması.
- **Gerçek Etki:** Saldırganların yetkisiz şekilde kimlik doğrulamayı atlatmasına (Authentication Bypass) yol açar.
- **Önerilen Çözüm:**
```diff
- if (token.equals("AIzaSyD-1234567890abcdefghijklmnopqrst")) {
+ if (tokenValidator.validate(token, secretKeyProvider.getSigningKey())) {
```
- **Doğrulama Testi:** `AuthServiceTest.shouldRejectInvalidToken` birim testi ile doğrulanmalıdır.
*(Güven: `HIGH` | Kategori: `SECURITY`)*
```

