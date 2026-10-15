# B12 — Entegrasyon, dayanıklılık ve backend teslimi

> **DISC-01:** Eureka/local adres çözümlemesi için B01A-Eureka-ve-Servis-Kesfi.md uygulanır. Uygulama ayarları properties olarak kalır; iş kuralları değişmez.

Şartname: 3.0 · Contract: 1.0.0 · Öğretici kod referansı · Durum: BUILD_UNVERIFIED

ACC-01 eki: Account, servis sahipliği ve properties standardı için [güncel mimari eki](03-ACCOUNT-VE-GUVENLIK-EKI.md) geçerlidir.
Son görev yeni bir uygulama yazmak değildir. Önceki parçaların aynı sözleşmeyle çalıştığını ve hata anında paranın iki kez değişmediğini kanıtlarız. Eksik adapter/endpoint’ler açıkça takip edilir; test sayısını artırmak başarı ölçüsü değildir.

Bu dosyadaki kodları kendi projenize sırayla elle yazın. Burada uygulama reposu kurulmuş veya kabul testleri geçmiş değildir. Dosya yolları CryptoLab köküne göredir. Bir sınıfı yazdıktan sonra küçük deneyi yapın; tüm belgeyi tek hamlede kopyalamayın.

## Bu dersi nasıl okuyacaksın? — Katmanların birlikte doğru çalıştığını kanıtlamak

**Önce kavram:** Unit test tek sınıfın hesabını gösterir; tüm sistemin tamamlandığını göstermez. B12’de HTTP, transaction, DB, mesaj, auth ve geri kazanım sınırları birlikte denenir. Paket/import testleri ise mimari bozulmayı erken yakalar.

**Yazma sırası:** Paket/import denetimi → unit/contract → DB integration → Kafka/Redis → HTTP E2E → crash/race → restore ve ölçüm.

**Neden bu ayrım var?** Sınıf sayısı veya test sayısı başarı ölçüsü değildir. Maven yokken build PASS yazma. B01–B11’de açık kalan adaptörleri yalnız README yazıldığı için DONE yapma.

**Küçük pratik:** Kaynak checklist’ten bir başarısızlık seç; hangi testin bunu yakalayacağını göster. Raporuna gerçek komutu/çıktıyı ve fixture mı gerçek entegrasyon mu olduğunu yaz.

Ortak kurallar: [Paket ve kod standardı](01-PAKET-VE-KOD-STANDARDI.md). Dosyaların tek tek yeni konumları: [Sınıf haritası](02-SINIF-VE-PAKET-HARITASI.md). Bu dosyada verilen yeni package/import/path birlikte kullanılmalıdır; eski ve yeni kopyaları aynı projeye ekleme.

### Bu görevde sınıflar hangi sorumlulukta?

| Sınıf | Paket | Neden burada? |
|---|---|---|
| Bu görev yeni production sınıfı gerektirmez | Test/CI/rapor | Önceki katmanların birlikte çalışmasını kanıtlar. |

## Kaynak görevlerin eksiksiz kontrol listesi

- [ ] **B12.1** B01–B11 checklist ve fixture/gerçek entegrasyon borçlarını tek listede gözden geçir; eksik olanı bitmeden DONE yazma.
- [ ] **B12.2** Auth→session→manual/AI plan→preview→submit→close→ledger→report uçtan uca, F10 ile doğrula.
- [ ] **B12.3** Kafka/outbox crash, duplicate funding/submit, DB transaction rollback, Redis down, provider stale, AI unavailable yarışlarını çalıştır.
- [ ] **B12.4** A10 ile PDF store/training worker kapalı test; inference hazırken sanal işlemler sürsün. Training facade ile gerçek küçük iş yürüsün.
- [ ] **B12.5** Migration/backup restore, ledger rebuild, projection rebuild, dependency locks ve offline-demo’yu temiz ortamda dene.
- [ ] **B12.6** Consumer lag, engine latency, queue age, memory ve data quality ölç; hedef ile ölçülmüş sonucu ayrı yaz.
- [ ] **B12.7** Contract version, commit, geçen/kalınan testler ve çalıştırma sırasını kayıt altına al; secrets paylaşma.

## B12.1 — Açık işleri kapat

Bu eğitim paketi sınıflar, SQL, konfigürasyon ve uygulama adımlarını birlikte içerir. Kod örnekleri kendi bilgisayarında birleştirilip test edilmeden tamamlanmış backend değildir. Özellikle B05 preview/fill worker/query, B06 gerçek stream/backfill/export, B08 projection bootstrap, B09 durable job/scan, B10 kalan endpoint’ler ve B11 replay writer/API bağlantıları kabul kapısından önce kodlanmalıdır. Birlikte yazarken port/algoritma anlatımıyla bırakılan bu parçalar gerçek adaptörlere çevrilir.

| Görev | Yerel kanıt | Gerçek entegrasyon |
|---|---|---|
| B01 | Maven build, strict schema test | araç sürümleri |
| B02 | F01–F15, tier, rounding | saf Java; ağ gerekmez |
| B03 | PostgreSQL rollback/lock/rebuild | gerçek PostgreSQL |
| B04 | JWT/refresh/owner/CSRF | gateway/downstream |
| B05 | fixture preview→fill→close | frontend sözleşmesi |
| B06 | Kafka duplicate/fanout/cache | Binance public smoke ayrı |
| B07 | funding/liq/deadline yarışları | durable writer |
| B08 | projection/cache/SSE/export | gateway ve frontend |
| B09 | fake WAIT/LONG/FAILED | A05 model-api |
| B10 | USER403/trainer jobs | A06–A09 training |
| B11 | deterministic synthetic replay | A09 finance eval |
| B12 | bütün akış + crash restore | F10/A10 ortak kanıt |

## B12.2 — Test seviyelerini ayır

Unit: Finance, PlanHash, gate, policy. Contract: JSON Schema/DTO/event. Integration: PostgreSQL/Kafka/Redis. E2E: HTTP auth→session→preview→submit→close→report. Public provider smoke: küçük, tarihli, ağ erişimi gerektirir; CI’daki sabit hesapların canlı fiyatla değişmesine izin verme.

### `backend/pom.xml — test plugin ekleri`

```xml
<!-- Parent build/plugins altına ekle; pluginManagement tek başına çalıştırmaz. -->
<plugins>
 <plugin>
  <groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.4</version>
  <configuration><excludes><exclude>**/*IT.java</exclude></excludes></configuration>
 </plugin>
 <plugin>
  <groupId>org.apache.maven.plugins</groupId><artifactId>maven-failsafe-plugin</artifactId><version>3.5.4</version>
  <executions><execution><goals><goal>integration-test</goal><goal>verify</goal></goals></execution></executions>
 </plugin>
</plugins>
```

### `.github/workflows/backend.yml`

```yaml
name: backend
on: [push, pull_request]
jobs:
  verify:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven
          cache-dependency-path: backend/**/pom.xml
      - uses: actions/setup-python@v5
        with:
          python-version: '3.12'
      - run: python -m pip install -r contracts/requirements.txt
      - run: python contracts/check_contracts.py
      # Testcontainers runner Docker'ını kullanır; gerçek borsa/AI sırrı gerekmez.
      - run: mvn -B -f backend/pom.xml clean verify
      - uses: actions/upload-artifact@v4
        if: always()
        with:
          name: backend-test-results
          path: |
            backend/**/target/surefire-reports/**
            backend/**/target/failsafe-reports/**
# Release öncesinde action tag'lerini doğrulanmış commit SHA'larına pinle.
```

### `.gitignore`

```text
# Yerel gizli değer ve build çıktıları repoya girmez.
.env
.env.*
!.env.example
**/target/
.contracts-venv/
*.pem
*.key
artifacts/
*.log
.idea/
.vscode/
```

### `infra/.env.example`

```text
# Yerel geliştirmede doldur; gerçek secret bu örneğe yazılmaz.
POSTGRES_ADMIN_PASSWORD=
SIM_PASSWORD=
DB_URL=jdbc:postgresql://localhost:5432/simulation_db
DB_USER=simulation_app
DB_PASSWORD=
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
REDIS_HOST=localhost
REDIS_PORT=6379
JWT_ISSUER=http://localhost:8081
JWT_AUDIENCE=cryptolab-api
JWT_JWKS_URL=http://localhost:8081/.well-known/jwks.json
JWT_PRIVATE_KEY_PATH=
JWT_PUBLIC_KEY_PATH=
JWT_KID=dev-2026-01
COOKIE_SECURE=false
WEB_ORIGIN=http://localhost:5173
MARKET_SOURCE=FIXTURE
MODEL_API_URL=http://localhost:8090
INTERNAL_SERVICE_SECRET=
# Gerçek borsa emir anahtarı/secret alanı yok.
```

Bu env örneğini bütün süreçlere aynı DB kimliğiyle vermeyin; her servisin kendi başlatma ortamı var. Docker `.env` okumakla Java uygulaması `.env` dosyasını kendiliğinden okumaz. IDE run config veya shell environment’a ilgili değerleri ekle. Secret’ları komut satırı argümanına koymak process listesinde görünmelerine yol açabilir.

## B12.3 — Kritik arıza matrisi

| Deney | Enjekte et | Beklenen invariant |
|---|---|---|
| Duplicate submit | aynı key 20 paralel istek | 1 command/1 execution |
| Plan tekrar | farklı key aynı plan | 1 approval unique |
| Funding tekrar | aynı position/time iki event | 1 settlement |
| Publish crash | Kafka ACK sonrası DB published update öncesi | tekrar event, tek projection |
| Ingress crash | fan-out ortasında exception | tüm work kayıtları atomik |
| Fill rollback | ledger sonrası exception | order/fill/cash yarım değil |
| Cancel/fill yarışı | barrier ile iki worker | tek terminal state |
| Manual/stop/liq | üç eşzamanlı input | q bir kez azalır |
| Stale quote | received yeni source eski | giriş yok, uydurma close yok |
| Redis kapalı | bağlantıyı durdur | cash etkilenmez, bounded fallback |
| AI kapalı | timeout/503 | FAILED, sahte WAIT/LONG yok |
| Reporting kapalı | consumer durdur | risk çalışır, sonra tek projection |
| Training kapalı | worker/storage offline | hazır inference/manual sürer |
| Model switch | request ortasında alias değiştir | pinned bundle korunur |
| Cross-user | başka kullanıcı UUID’si | 403/404, veri yok |

Test fixture’a sabit seed/Clock koy. Yarış testinde `CountDownLatch` ile iki thread’i kritik bölüme aynı anda yönlendir. Yalnız art arda iki metod çağırmak yarış koşulu kanıtı değildir.

## B12.4 — Offline demo

Foundation ve streaming profillerini aç; fixture market publisher gerçek Binance topic namespace’inden ayrılmalı. Gerçek AI yoksa UI/response’da fixture model etiketi belirgin. Test veri snapshot’ı ve educational rule profile hash’i sabit. PDF/training worker kapalıyken kayıt→manual preview→submit→fill→close→ledger→report göster. Sonra gerçek model hazırsa ayrı koşuda AI öneri→onay hattını doğrula.

## B12.5 — Backup ve restore

PostgreSQL dump’ı yalnız güvenilir yerel admin ortamında al; ledger’i DELETE ederek reset yapma. Aşağıdaki komut mevcut DB’yi silmez, yedek dosya üretir. Dump içindeki kişisel veriyi herkese açık repo’ya koyma.

```bash
# PGPASSFILE ile parolayı process argümanında taşımadan bağlan.
pg_dump --format=custom --host=localhost --username=simulation_app --dbname=simulation_db --file=simulation.dump
# Restore deneyi AYRI boş veritabanına yapılır.
pg_restore --host=localhost --username=restore_app --dbname=simulation_restore --no-owner simulation.dump
```

Aynı anda rule/model/dataset manifestleri ve artifact hash’leri yedeklenir. Restore sırası: migration durumu → ledger/snapshot mutabakatı → inbox/outbox cursor → market gap/freshness → risk revalidation → girişleri aç. Eski quote’u fresh ilan etme. Reporting ayrı namespace’te audit export’tan rebuild edilir. Restore test DB’sinin hash/count sonuçlarını kayıt altına al.

## B12.6 — Ölçüm

İlk deney 2 fixture sembol, 10 session, 1Hz mark + recorded quote burst. CPU/RAM/JDK/docker/disk belirt. Hedef p95 engine latency500ms; ölçülmüş sonuç değildir. Timer start source receipt, end transaction commit olarak tanımlanır. Outbox age, consumer lag, pending input age, projection lag, heap ve DB duration ayrı ölçülür. UI render süresiyle engine risk süresini karıştırma.

## B12.7 — Teslim kanıtı

Maven effective-pom ve dependency tree çıktısını sürüm kaydına al. BOM sürümlerini otomatik artırma. Container image digest’lerini doğruladığın ortamda kilitle. Reproducible build için plugin sürümleri ve wrapper sürümü kaydedilir; README komutları temiz clone’da denenir. Gizli değerler test raporunda maskelenir.

### `progress/BACKEND-RELEASE.md`

```markdown
# Backend teslim kontrolü
Şartname: 3.0
Contract: 1.0.0
Commit: henüz yok
JDK / Maven / Docker / DB / Kafka / Redis: kullanıcı doğrulaması bekleniyor

| Alan | Sonuç | Kanıt |
|---|---|---|
| Build | NOT_RUN | |
| Finans F01–F15 | NOT_RUN | |
| Ledger balance/rebuild | NOT_RUN | |
| Duplicate/race | NOT_RUN | |
| Auth/owner/CSRF | NOT_RUN | |
| Contract | NOT_RUN | |
| Gerçek Binance public | NOT_RUN | |
| Gerçek AI/training | NOT_RUN | |
| Frontend E2E | NOT_RUN | |
| Backup restore | NOT_RUN | |
| Performance | NOT_MEASURED | |

Açık adaptör/endpoint:
Bilinçli tasarım sapmaları:
Fixture ve gerçek entegrasyon ayrımı:
Sonraki tek adım:
```

## Kabul: hangi çıktıyı görmelisin?

**Deneme/kabul:** F01–F15 uygulama testleri, sıfır ledger imbalance, çift parasal etki yok, cross-user erişim yok, stale veriyle uydurma fill yok. Public provider erişimi yoksa o test açık sınırlama olarak kalır. Java/React/AI uçtan uca bağlanmadıysa “tüm sistem tamamlandı” denmez.



## Kendi cümlelerinle anlat

“Bir işlem yanlış görünürse sırasıyla hangi requestId, plan, command, event ve ledger kayıtlarını izlersin?”

## Çalışma sonunda

`progress/BACKEND.md` içine alt görev, değiştirdiğin dosya, komut, gerçek çıktı, fixture/gerçek entegrasyon ayrımı ve sıradaki tek adımı yaz. Çalıştırmadığın test için PASS yazma.

## ACC-01 ek kabul kapısı

B08A.1–B08A.8 bitmeden hesap ekranı tamamlandı denmez. Account→Reporting JWT relay, cross-user session reddi, empty vs BUILDING ayrımı, toplam PnL/fee/funding, replay dışlama, cursor bağlama ve Reporting-down senaryoları uçtan uca denenir. HTTP gövdesi boyut/süre sınırının gerçekten çalıştığı doğrulanır. Account restart'ında geçmiş Reporting/Simulation'dan aynı kalır. Properties dönüşümü sonrası her servis ayrı portta başlatılır; aynı application ayarının YAML kopyası bırakılmaz.

## DISC-01 kabul kapısı

Sekiz Spring süreci, registry’de yedi client; doğru Boot/Cloud BOM; Reporting 8185’e taşındığında Gateway/Account ayarı değiştirmeden erişim; boş registry’de 503; restart/cache davranışı; JWT/owner ve idempotency korunması B01A.7 ile kanıtlanır. Bunlar tek başına tam işlevsel backend teslimi değildir.
