# CryptoLab Frontend — Sanal Vadeli İşlemler (Paper Futures) & AI Araştırma Terminali

Bu paket, CryptoLab platformunun gerçek `backend/` (Spring Boot 4.1.1 / Java 21) ve `ai/` (FastAPI / Python 3.12) sözleşmelerine, mimarisine ve iş kurallarına birebir bağlı olarak geliştirilmiş üretim kalitesinde React 18 + TypeScript (`strict: true`) + Vite + Tailwind CSS istemcisidir.

---

## 1. Mimari ve Temel Güvenlik / İş Kuralları

- **Tek Giriş Kapısı (API Gateway):** Tüm istemci istekleri `/api/v1/*` üzerinden yalnızca API Gateway'e (`VITE_GATEWAY_URL=http://localhost:8080`) gider. İç mikroservislere (`8081..8086`) veya AI servisine (`8090`) doğrudan çağrı yapılmaz.
- **Sanal İşlem Şeffaflığı:** Üst bilgi çubuğunda (`SimulationDisclaimerBar`) ve işlem ekranlarında sistemin sanal vadeli işlem simülasyonu olduğu açıkça belirtilir; gerçek para, cüzdan veya borsa API anahtarı istenmez.
- **Finansal Hassasiyet (`DecimalString`):** Fiyat, miktar, bakiye, komisyon, fonlama, PnL ve kaldıraç değerleri tel üzerinde ve bellekte `string` olarak korunur. Kâr/zarar, komisyon, marjin veya likidasyon fiyatı **asla** tarayıcıda JavaScript `Number` ile hesaplanmaz; yalnız sunucu yanıtları gösterilir. Mum grafiği dönüşümü `chartNumber.ts` içinde izole edilmiştir.
- **Kimlik ve Token Güvenliği:**
  - `accessToken` yalnızca JavaScript modül belleğinde (`apiClient.ts`) tutulur; `localStorage`, `sessionStorage`, `IndexedDB` veya URL sorgu parametrelerine **asla** yazılmaz.
  - `POST /api/v1/auth/refresh` ve `POST /api/v1/auth/logout` çağrıları `AuthController.java` sözleşmesi gereği `credentials: 'include'`, `Content-Type: application/json`, `X-CSRF: 1` ve `Origin: http://localhost:5173` başlıklarıyla gönderilir.
  - Eşzamanlı `401` yanıtlarında tek uçuşlu (`single-flight`) token yenileme uygulanır.
  - Çıkış (`logout`) anında bellek içi token, aktif SSE `AbortController` akışları, React Query önbelleği ve kullanıcıya bağlı iş referansları tamamen temizlenir.
- **Sunucu Önizleme Onayı (`ExecutionPlan`) ve Strict Emir Gönderimi:**
  - Emir akışı 6 durumlu makineyi izler: `EDITING → PREVIEW_LOADING → PREVIEW_READY → SUBMITTING → ACCEPTED_PENDING → RESULT`.
  - Herhangi bir form alanı veya seçili enstrüman değiştiğinde mevcut `ExecutionPlan` derhal geçersiz kılınır.
  - `POST /api/v1/sim/orders` gövdesinde (`contracts/http/submit-order.schema.json` — `additionalProperties: false`) **yalnızca** `{ planId, revision, planHash }` gönderilir.
  - `202 ACCEPTED` yanıtı emrin gerçekleştiği (`FILLED`) anlamına gelmez; komut durumu izlenir.
- **AI Analiz Dürüstlüğü (`WAIT` vs `FAILED` ve `TEMPLATE`):**
  - `WAIT` kararı ekonomik çekimserliktir; `WAIT` durumunda emir önerisi veya emir gönderme butonu gösterilmez.
  - `FAILED` / `MODEL_UNAVAILABLE` teknik hataları ekonomik `WAIT` olarak gösterilmez.
  - `explanationStatus: TEMPLATE` şablon açıklamaları açıkça *"ŞABLON AÇIKLAMA (TEMPLATE — Sayısal Verilerden Türetilmiş, Eğitimli LLM Değil)"* olarak etiketlenir.
- **İzole Eğitici (Trainer) Alanı ve `MODEL_PUBLISH` Ayrımı:**
  - `/training/*` rotaları işlem ekranlarından tamamen ayrıdır ve yalnız `TRAINER` / `ADMIN` rollerine açıktır (`USER` rolüne `403` gösterilir).
  - PDF yüklemeleri istemcide `20 MiB` sınırına göre doğrulanır ve yükleme sonrası *"Kaynak İşleme Alındı (Model Henüz Eğitilmedi)"* mesajı verilir.
  - Model `Promote` ve `Rollback` eylemleri yalnızca `MODEL_PUBLISH` yetkisine (`JWT authorities`) sahip kullanıcılarda aktiftir.

---

## 2. Çalıştırma ve Doğrulama Komutları

### Kurulum
```bash
npm --prefix frontend install
```

### Ortam Değişkenleri (`frontend/.env.example`)
```ini
VITE_GATEWAY_URL=http://localhost:8080
VITE_API_MODE=live
```
- `VITE_API_MODE=live` (varsayılan): Tüm istekler gerçek API Gateway'e iletilir. Üretim derlemesine MSW dahil edilmez.
- `VITE_API_MODE=hybrid` / `fixture` (yalnız geliştirme): Arka uçta henüz controller'ı olmayan uç noktalar için sözleşme şemalarıyla uyumlu geliştirme yanıtlarını devreye alır ve arayüzde geliştirme modu uyarısını gösterir.

### Geliştirme Sunucusu (`http://localhost:5173`)
```bash
npm --prefix frontend run dev
```

### Tip Kontrolü, Lint, Test ve Üretim Derlemesi
```bash
npm --prefix frontend run typecheck
npm --prefix frontend run lint
npm --prefix frontend run test
npm --prefix frontend run build
```

