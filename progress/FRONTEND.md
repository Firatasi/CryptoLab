# CryptoLab — Frontend Kapsam ve İlerleme Kaydı (`progress/FRONTEND.md`)

Son Güncelleme: `2026-11-06`

## 1. Frontend Kapsam Tablosu (Özellik → Backend Endpoint → Sözleşme → Frontend Ekranı → Geliştirme Durumu)

> **Durum Etiketleri:**
> - `DONE`: Gerçek backend controller'ı (`backend/`) ve sözleşmesi ile uçtan uca bağlandı, doğrulandı ve test edildi.
> - `IMPLEMENTED_WITH_FIXTURE`: `RouteTable.java` içinde rotası tanımlı ancak backend mikroservisinde REST controller sınıfı henüz bulunmayan (`market-data-service` / `simulation-service` oturum-emir-pozisyon) uç noktalar için gerçek kanonik şemalara (`contracts/`) birebir bağlı istemci + açıkça etiketlenmiş geliştirme fixture desteği ile tamamlandı.

| Kod | Özellik | Backend Endpoint'i | Sözleşme / DTO / Şema | Frontend Ekranı / Bileşeni | Geliştirme Durumu |
|---|---|---|---|---|---|
| **F00** | Ortam, Kanonik Tipler, `DecimalInput`, API İstemcisi, SSE Ayrıştırıcı | `/api/v1/*` (Gateway Proxy) | `contracts/check_contracts.py`, `ApiErrors.java`, `SseRing.java` | `src/shared/*` (`types.ts`, `decimalParser.ts`, `formatters.ts`, `apiClient.ts`, `sseParser.ts`) | `DONE` |
| **F01** | Kimlik Doğrulama, Single-Flight Refresh, CSRF, Oturum İzolasyonu | `POST /api/v1/auth/register`<br>`POST /api/v1/auth/login`<br>`POST /api/v1/auth/refresh`<br>`POST /api/v1/auth/logout`<br>`GET /api/v1/auth/me` | `AuthController.java`, `AccessTokens.java`, `RegisterRequest`, `LoginRequest`, `TokenResponse`, `UserResponse` | `/login`, `/register`, `/settings`, `AuthContext.tsx`, `ProtectedRoute.tsx` | `DONE` |
| **F02** | Uygulama Kabuğu, Gezinme, Simülasyon Şeffaflık Çubuğu | — | Rol (`USER`, `TRAINER`, `ADMIN`) ve Yetki (`MODEL_PUBLISH`) kuralları | `AppShell.tsx`, `LandingPage.tsx`, `SimulationDisclaimerBar` | `DONE` |
| **F03** | Dinamik Piyasa Kataloğu, Anlık Fiyat (`markPrice` vs `lastPrice`), Mum Grafiği | `GET /api/v1/market/instruments`<br>`GET /api/v1/market/quotes`<br>`GET /api/v1/market/candles`<br>`GET /api/v1/market/stream` | `contracts/events/market-snapshot.schema.json`, `InstrumentItem`, `MarketSnapshot`, `CandleSeriesResponse` | `/market` (`MarketPage.tsx`, `InstrumentSelector.tsx`, `PriceCard.tsx`, `CandleChart.tsx`) | `IMPLEMENTED_WITH_FIXTURE` |
| **F04** | Sanal Oturum Yönetimi, 6-Adımlı Emir Bileti, Sunucu `ExecutionPlan` Onayı ve Strict Submit | `GET/POST /api/v1/sim/sessions`<br>`POST /api/v1/sim/order-previews`<br>`POST /api/v1/sim/orders`<br>`GET /api/v1/sim/commands/{id}` | `ManualPreviewRequest.java`, `AiPreviewRequest.java`, `ExecutionPlan.java`, `SubmitOrderRequest.java` (`contracts/http/submit-order.schema.json`) | `/sessions` (`SessionsPage.tsx`), `/trade/:sessionId` (`TradeWorkspacePage.tsx`, `OrderTicket.tsx`, `ExecutionPlanCard.tsx`) | `IMPLEMENTED_WITH_FIXTURE` |
| **F05** | Açık Pozisyon Yönetimi, Kısmi/Tam Kapatma (`expectedVersion`), SL/TP Güncelleme, Açık Limit Emir İptali | `GET /api/v1/sim/positions`<br>`POST /api/v1/sim/positions/{id}/close`<br>`PATCH /api/v1/sim/positions/{id}/brackets`<br>`GET /api/v1/sim/orders`<br>`POST /api/v1/sim/orders/{id}/cancel` | `OrderController.java`, `AcceptedOrderResponse.java`, `PositionView`, `OpenOrderView` | `/trade/:sessionId` (`PositionsAndOrders.tsx`) | `IMPLEMENTED_WITH_FIXTURE` |
| **F06** | Hesap Özeti (`READY`/`BUILDING`), Maliyet Muhasebesi ve HMAC Cursor İşlem Geçmişi | `GET /api/v1/account/me/summary`<br>`GET /api/v1/account/me/trades` | `AccountController.java`, `ReportingAccountController.java`, `AccountSummaryResponse.java`, `TradeHistoryResponse.java` | `/reports/:sessionId` (`ReportsPage.tsx`), `/history/:sessionId` (`HistoryPage.tsx`) | `DONE` |
| **F07** | AI Strateji Analizi (`LONG`/`SHORT`/`WAIT` vs `FAILED`, `TEMPLATE` Etiketi) ve Piyasa Taraması (`PARTIAL`) | `POST /api/v1/strategy/analyses`<br>`GET /api/v1/strategy/analyses/{id}`<br>`GET /api/v1/strategy/proposals/{id}`<br>`POST /api/v1/strategy/scans`<br>`GET /api/v1/strategy/scans/{id}`<br>`POST /api/v1/strategy/scans/{id}/cancel` | `StrategyController.java`, `AnalysisJobRepository.java`, `ScanJobRepository.java`, `contracts/events/prediction-result.schema.json` | `/analysis/:sessionId` (`AnalysisPage.tsx`), `/scan/:sessionId` (`ScanPage.tsx`) | `DONE` |
| **F08** | İzole Eğitici (Trainer) Alanı: PDF/URL Kaynakları, Örnek Onayı, Veri Kümesi Dondurma, Eğitim Koşumu ve `MODEL_PUBLISH` | `GET/POST/DELETE /api/v1/strategy/training/sources*`<br>`POST /api/v1/strategy/training/source-imports`<br>`POST /api/v1/strategy/training/example-jobs`<br>`GET/PATCH /api/v1/strategy/training/examples*`<br>`POST /api/v1/strategy/training/datasets`<br>`POST/GET /api/v1/strategy/training/runs*`<br>`GET/POST /api/v1/strategy/training/models*` | `TrainingController.java`, `TrainingJsonController.java`, `JsonContractGate.java` | `/training/sources`, `/training/datasets`, `/training/runs`, `/training/models` (`TrainingPages.tsx`) | `DONE` |
| **F09** | Deterministik Tarihsel Replay (`Fidelity`, `DataLayer`, `ambiguousBarCount`) ve Yetkili CSV İndirme | `POST /api/v1/sim/replays`<br>`GET /api/v1/sim/replays/{id}`<br>`POST /api/v1/sim/replays/{id}/cancel`<br>`GET /api/v1/sim/replays/{id}/csv` | `ReplayController.java`, `ReplayService.java`, `CreateReplayRequest` (`ruleProfileId`) | `/replay` (`ReplayPage.tsx`) | `DONE` |
| **F10** | Birim/Entegrasyon Test Paketi, Tip Kontrolü, Lint ve Üretim Derlemesi | — | Vitest + Testing Library + MSW (`20/20` test) | `src/tests/unitAndIntegration.test.tsx` | `DONE` |

---

## 2. Tespit Edilen Belge vs Kod Çelişkileri (Kod Esas Alındı)

1. **`D-01` — Replay Oluşturma Alan Adı (`ruleProfileId` vs `profileId`):**
   - Bazı taslak belgelerde `profileId` geçmesine rağmen gerçek çalışan `ReplayController.CreateReplayRequest` (`backend/simulation-service/.../ReplayController.java`) alanı `ruleProfileId` olarak beklemektedir. Frontend `ruleProfileId` gönderecek şekilde yazıldı.
2. **`D-02` — Auth CSRF ve `Content-Type: application/json` Zorunluluğu:**
   - `AuthController.java` içinde `/api/v1/auth/refresh` ve `/api/v1/auth/logout` uç noktaları `consumes = "application/json"` tanımlamakta ve `Origin == web.origin` + `X-CSRF: 1` kontrolü yapmaktadır. `apiClient.ts` boş gövdede dahi `{}` JSON gövdesi ve `X-CSRF: 1` gönderecek şekilde yapılandırıldı; `vite.config.ts` içinde `/api` proxy'sinde `changeOrigin: false` bırakılarak `Origin: http://localhost:5173` korundu.
3. **`D-03` — `UserResponse` ve `MODEL_PUBLISH` Yetkisi:**
   - `UserResponse.java` yalnızca `{ id, displayName, roles }` döndürmekte; `MODEL_PUBLISH` yetkisi ise `AccessTokens.java` tarafından üretilen JWT'nin `authorities` claim'inde yer almaktadır. `AuthContext.tsx` bellek içi JWT'den `authorities` claim'ini ayrıştırarak `Promote` / `Rollback` kontrollerini `MODEL_PUBLISH` yetkisine bağladı.
4. **`D-04` — `JsonContractGate.java` Sorgu Parametresi Kısıtı:**
   - `TrainingController` / `TrainingJsonController` üzerindeki GET uç noktaları yalnız `cursor`, `datasetId`, `expectedVersion` sorgu parametrelerine izin vermektedir (aksi halde `400 UNKNOWN_QUERY` döner). `training/api.ts` yalnızca izin verilen parametreleri gönderecek şekilde sınırlandı.
5. **`D-05` — `SseRing.java` Olay Kimliği Formatı (`epoch:sequence`):**
   - `SseRing.java` olay kimliklerini `<UUID>:<long>` (`epoch:sequence`) biçiminde üretmektedir. `SseSequenceGuard` hem `epoch:sequence` hem sayısal sıra numaralarını destekleyecek şekilde yazıldı.

---

## 3. Doğrulama Sonuçları

- `tsc -p frontend/tsconfig.app.json --noEmit`: **PASS (0 hata)**
- `eslint --config frontend/eslint.config.js frontend/src --max-warnings 0`: **PASS (0 hata, 0 uyarı)**
- `vitest run --root frontend`: **PASS (20/20 test başarılı)**
- `vite build frontend`: **PASS (`frontend/dist/` üretim derlemesi başarılı)**
- `contracts/check_contracts.py`: **PASS (tüm sözleşme kontrolleri başarılı)**
