# B09 — Strategy, piyasa tarama ve AI bağlantısı

> **DISC-01:** Eureka/local adres çözümlemesi için B01A-Eureka-ve-Servis-Kesfi.md uygulanır. Uygulama ayarları properties olarak kalır; iş kuralları değişmez.

Şartname: 3.0 · Contract: 1.0.0 · Öğretici kod referansı · Durum: BUILD_UNVERIFIED

ACC-01 eki: Account, servis sahipliği ve properties standardı için [güncel mimari eki](03-ACCOUNT-VE-GUVENLIK-EKI.md) geçerlidir.
Strategy modeli çağırır, sonucu politika ile öneriye çevirir. Modelin LONG demesi emir değildir. WAIT başarılı değerlendirme, MODEL_UNAVAILABLE teknik başarısızlıktır. AI Java finans motorunun miktar/ücret otoritesini devralmaz.

Bu dosyadaki kodları kendi projenize sırayla elle yazın. Burada uygulama reposu kurulmuş veya kabul testleri geçmiş değildir. Dosya yolları CryptoLab köküne göredir. Bir sınıfı yazdıktan sonra küçük deneyi yapın; tüm belgeyi tek hamlede kopyalamayın.

## Bu dersi nasıl okuyacaksın? — AI çağrısını uygulama portu üzerinden kurmak

**Önce kavram:** ModelClient model çağrısı portudur. HttpModelClient ve FakeModelClient alternatif adaptörlerdir. AnalysisFlow sonuç kimliği/sürüm/quality kontrolü yapar. NumericPolicy sayısal tahmini proje kuralına dönüştürür. AI çıktısı kendi başına emir açmaz.

**Yazma sırası:** PredictionResult → ModelClient → FakeModelClient → NumericPolicy → AnalysisFlow → HttpModelClient → kalıcı job/proposal/scan adaptörleri.

**Neden bu ayrım var?** AI timeout’u sahte WAIT tahmini değildir; FAILED olarak görünür. Model bundle analiz başlangıcında sabitlenir. Strategy başka servisin wallet repository’sini import etmez.

**Küçük pratik:** ModelClient fake’inde yanlış requestId/modelBundleId döndür. AnalysisFlow bunu geçerli karar saymamalı. Aynı numeric girdi policy testinde kararlı sonuç vermeli.

Ortak kurallar: [Paket ve kod standardı](01-PAKET-VE-KOD-STANDARDI.md). Dosyaların tek tek yeni konumları: [Sınıf haritası](02-SINIF-VE-PAKET-HARITASI.md). Bu dosyada verilen yeni package/import/path birlikte kullanılmalıdır; eski ve yeni kopyaları aynı projeye ekleme.

### Bu görevde sınıflar hangi sorumlulukta?

| Sınıf | Paket | Neden burada? |
|---|---|---|
| `PredictionResult` | `domain.model` | İş verisini ve kendi değişmez kurallarını temsil eder. |
| `ModelClient` | `application.port` | Dış teknik yeteneği soyutlar. |
| `FakeModelClient` | `infrastructure.client.fixture` | Dış sistem veya teknik sınırı uygular. |
| `NumericPolicy` | `domain.service` | Girdilerden iş kararı/hesap üretir. |
| `AnalysisFlow` | `application.service` | Bir kullanım senaryosunun sırasını yönetir. |
| `HttpModelClient` | `infrastructure.client` | Dış sistem veya teknik sınırı uygular. |

## Kaynak görevlerin eksiksiz kontrol listesi

- [ ] **B09.1** Analysis DTO/status ve PredictionResult schema’yı A01/A05 ile aynı fixture’dan doğrula.
- [ ] **B09.2** Fake modelle QUEUED→RUNNING→SUCCEEDED LONG/SHORT/WAIT ve teknik FAILED ayrımını yaz; fake açık etiketli.
- [ ] **B09.3** AsOf/coverage→WAITING_DATA→tek backfill; bundle pin ve feature schema gate.
- [ ] **B09.4** Sürümlü numeric policy ile edge/volatility/SL/TP/leverage niyeti; miktarı Simulation’a bırak.
- [ ] **B09.5** AI_PROPOSAL preview ve exact onay hattı; expired/generation/revision testleri.
- [ ] **B09.6** Scan universe snapshot/cursor/coverage/fairness/cancel; partial sonucu tüm piyasa sanma.
- [ ] **B09.7** Açıklama timeout/template; taze olmayan önerinin TTL’sini metin beklerken uzatma; A05 gerçek internal client ile contract test.

## B09.1 — Dosya sırası

PredictionResult/ModelClient → FakeModelClient → NumericPolicy → AnalysisFlow → kalıcı job repository → scan worker → gerçek HTTP client. Strategy kendi DB’sini kullanır; Simulation cüzdan tablosuna SQL atmaz. Session ownership ve generation yetkili internal API’den alınır; gelen snapshot işlem sonunda güncellik gate’inden geçer.

### `backend/strategy-service/src/main/java/com/cryptolab/strategy/domain/model/PredictionResult.java`

**Sınıfı yazmadan önce:** İş verisini ve kendi değişmez kurallarını temsil eder. Modelin HTTP veya veritabanı biçimine bağımlı olup olmadığını kontrol et.

```java
package com.cryptolab.strategy.domain.model;
import java.time.Instant;
import java.util.*;
/**
 * Sorumluluk: İş verisini ve kendi değişmez kurallarını temsil eder.
 * Okuma ipucu: Modelin HTTP veya veritabanı biçimine bağımlı olup olmadığını kontrol et.
 */
public record PredictionResult(UUID requestId,Quality quality,List<String> reasonCodes,UUID modelBundleId,
 String featureVersion,String featureSchemaHash,UUID featureSnapshotId,Instant asOf,int horizonSeconds,
 Double predictedLogReturn,Double volatilityEstimate,String calibrationVersion,
 Map<String,Object> observedFeatureSummary,long latencyMs) {
 public enum Quality {READY,INSUFFICIENT_DATA,OUT_OF_DOMAIN,STALE_DATA}
 public PredictionResult {
  reasonCodes=List.copyOf(reasonCodes);observedFeatureSummary=Map.copyOf(observedFeatureSummary);
  if(!Set.of(60,900,3600,86400,604800).contains(horizonSeconds)||latencyMs<0)throw new IllegalArgumentException("INVALID_PREDICTION");
  if(quality==Quality.READY){
   if(predictedLogReturn==null||volatilityEstimate==null||!Double.isFinite(predictedLogReturn)
    ||!Double.isFinite(volatilityEstimate)||volatilityEstimate<0)throw new IllegalArgumentException("INVALID_NUMERIC_OUTPUT");
  }else if(predictedLogReturn!=null||volatilityEstimate!=null)throw new IllegalArgumentException("QUALITY_OUTPUT_MISMATCH");
 }
}
```

### `backend/strategy-service/src/main/java/com/cryptolab/strategy/application/port/ModelClient.java`

**Sınıfı yazmadan önce:** Dış teknik yeteneği soyutlar. Fixture ve gerçek adaptör aynı sözleşmeyi uygular; service somut client bilmez.

```java
package com.cryptolab.strategy.application.port;
import com.cryptolab.strategy.domain.model.PredictionResult;
import java.util.UUID;
import java.time.Instant;
import java.util.List;
/**
 * Sorumluluk: Dış teknik yeteneği soyutlar.
 * Okuma ipucu: Fixture ve gerçek adaptör aynı sözleşmeyi uygular; service somut client bilmez.
 */
public interface ModelClient {
 record Bundle(UUID bundleId,String featureVersion,String featureSchemaHash,List<Integer> supportedHorizons,String releaseStatus){}
 record Request(UUID requestId,UUID instrumentId,int horizonSeconds,Instant asOf,UUID modelBundleId,
                UUID marketDatasetId,UUID marketSnapshotId,String featureVersion){}
 Bundle activeBundle(int horizonSeconds);
 PredictionResult predict(Request request);
 // Bu arayüzde createOrder, wallet veya PDF arama metodu yoktur.
}
```

### `backend/strategy-service/src/main/java/com/cryptolab/strategy/infrastructure/client/fixture/FakeModelClient.java`

**Sınıfı yazmadan önce:** Dış sistem veya teknik sınırı uygular. Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.

```java
package com.cryptolab.strategy.infrastructure.client.fixture;
import com.cryptolab.strategy.application.port.ModelClient;
import com.cryptolab.strategy.domain.model.PredictionResult;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
/**
 * Sorumluluk: Dış sistem veya teknik sınırı uygular.
 * Okuma ipucu: Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.
 */
@Component @Profile("fixture")
public class FakeModelClient implements ModelClient {
 private static final UUID BUNDLE=UUID.fromString("44444444-4444-4444-8444-444444444444");
 public Bundle activeBundle(int h){return new Bundle(BUNDLE,"features-v1","sha256:"+"b".repeat(64),List.of(60,900,3600,86400,604800),"DEVELOPMENT_CANDIDATE");}
 public PredictionResult predict(Request r){
  // Sabit ve açık etiketli WAIT fixture; eğitilmiş model diye sunulmaz.
  return new PredictionResult(r.requestId(),PredictionResult.Quality.INSUFFICIENT_DATA,List.of("FIXTURE_MODEL","INSUFFICIENT_HISTORY"),
   r.modelBundleId(),r.featureVersion(),"sha256:"+"b".repeat(64),UUID.fromString("66666666-6666-4666-8666-666666666666"),
   r.asOf(),r.horizonSeconds(),null,null,null,Map.of(),1);
 }
}
```

### `backend/strategy-service/src/main/java/com/cryptolab/strategy/domain/service/NumericPolicy.java`

**Sınıfı yazmadan önce:** Girdilerden iş kararı/hesap üretir. Aynı girdinin aynı sonucu üretmesi beklenir; DB/ağ yan etkisi ekleme.

```java
package com.cryptolab.strategy.domain.service;
import com.cryptolab.finance.Decimal;
import com.cryptolab.strategy.domain.model.PredictionResult;
import java.math.BigDecimal;
import java.math.MathContext;
/**
 * Sorumluluk: Girdilerden iş kararı/hesap üretir.
 * Okuma ipucu: Aynı girdinin aynı sonucu üretmesi beklenir; DB/ağ yan etkisi ekleme.
 */
public final class NumericPolicy {
 public record Artifact(String version,double minNetEdge,double minDataCoverage,BigDecimal stopAtrMultiplier,
  BigDecimal targetRiskReward,BigDecimal suggestedLeverage,double maxVolatility){}
 public record Outcome(String action,BigDecimal stopLoss,BigDecimal takeProfit,BigDecimal suggestedLeverage,String reason){}
 private NumericPolicy(){}
 public static Outcome evaluate(PredictionResult result,Artifact policy,BigDecimal reference,
  BigDecimal verifiedAtr,double coverage,double estimatedRoundTripCost){
  if(result.quality()!=PredictionResult.Quality.READY)return new Outcome("WAIT",null,null,null,result.quality().name());
  if(!Double.isFinite(coverage)||!Double.isFinite(estimatedRoundTripCost)||estimatedRoundTripCost<0)throw new IllegalArgumentException("INVALID_POLICY_INPUT");
  if(coverage<policy.minDataCoverage()||result.volatilityEstimate()>policy.maxVolatility())return new Outcome("WAIT",null,null,null,"POLICY_GATE");
  // Skor double olabilir; fiyat düzeyini yalnız doğrulanmış Decimal ATR/reference ile kurarız.
  double edge=Math.expm1(result.predictedLogReturn());
  if(!Double.isFinite(edge))throw new IllegalArgumentException("INVALID_EDGE");
  if(Math.abs(edge)-estimatedRoundTripCost<policy.minNetEdge())return new Outcome("WAIT",null,null,null,"INSUFFICIENT_EDGE");
  if(verifiedAtr.signum()<=0||reference.signum()<=0||policy.stopAtrMultiplier().signum()<=0||policy.targetRiskReward().signum()<=0)
   throw new IllegalArgumentException("INVALID_POLICY_PRICE");
  BigDecimal stopDistance=verifiedAtr.multiply(policy.stopAtrMultiplier(),MathContext.DECIMAL128);
  BigDecimal targetDistance=stopDistance.multiply(policy.targetRiskReward(),MathContext.DECIMAL128);
  boolean longSide=edge>0;
  BigDecimal stop=longSide?reference.subtract(stopDistance):reference.add(stopDistance);
  BigDecimal target=longSide?reference.add(targetDistance):reference.subtract(targetDistance);
  if(stop.signum()<=0||target.signum()<=0)return new Outcome("WAIT",null,null,null,"INVALID_PRICE_LEVEL");
  return new Outcome(longSide?"LONG":"SHORT",stop,target,policy.suggestedLeverage(),"NUMERIC_POLICY");
 }
}
```

### `backend/strategy-service/src/main/resources/db/migration/V1__strategy.sql`

```sql
CREATE TABLE analysis_jobs(id uuid PRIMARY KEY,owner_id uuid NOT NULL,session_id uuid NOT NULL,instrument_id uuid NOT NULL,
 horizon_seconds integer NOT NULL,as_of timestamptz NOT NULL,model_bundle_id uuid,
 feature_snapshot_id uuid,status text NOT NULL,action text,error text,reason_codes jsonb NOT NULL DEFAULT '[]',
 proposal_id uuid,explanation_status text NOT NULL DEFAULT 'PENDING',explanation text,
 created_at timestamptz NOT NULL,updated_at timestamptz NOT NULL,lease_until timestamptz,worker_token uuid);
CREATE TABLE proposals(id uuid NOT NULL,revision int NOT NULL,owner_id uuid NOT NULL,session_id uuid NOT NULL,
 instrument_id uuid NOT NULL,generation bigint NOT NULL,policy_version text NOT NULL,model_bundle_id uuid NOT NULL,
 feature_snapshot_id uuid NOT NULL,plan_intent jsonb NOT NULL,valid_until timestamptz NOT NULL,state text NOT NULL,
 PRIMARY KEY(id,revision));
CREATE TABLE scan_jobs(id uuid PRIMARY KEY,owner_id uuid NOT NULL,session_id uuid NOT NULL,horizon int NOT NULL,
 universe_version text NOT NULL,cursor text,status text NOT NULL,total_known bigint NOT NULL,
 evaluated_count bigint NOT NULL DEFAULT 0,skipped_count bigint NOT NULL DEFAULT 0,reason_counts jsonb NOT NULL DEFAULT '{}',
 created_at timestamptz NOT NULL,updated_at timestamptz NOT NULL,lease_until timestamptz,worker_token uuid);
CREATE TABLE policy_artifacts(id uuid PRIMARY KEY,version text NOT NULL UNIQUE,artifact jsonb NOT NULL,hash text NOT NULL);
CREATE TABLE inbox(consumer text NOT NULL,event_id uuid NOT NULL,PRIMARY KEY(consumer,event_id));
CREATE TABLE outbox(event_id uuid PRIMARY KEY,topic text NOT NULL,event_key text NOT NULL,payload jsonb NOT NULL,
 created_at timestamptz NOT NULL,published_at timestamptz,lease_until timestamptz,attempts int NOT NULL DEFAULT 0);
CREATE TABLE idempotency_records(subject uuid NOT NULL,route text NOT NULL,scope text NOT NULL,key text NOT NULL,
 request_hash text NOT NULL,result_ref uuid NOT NULL,PRIMARY KEY(subject,route,scope,key));
```

### `backend/strategy-service/src/main/java/com/cryptolab/strategy/application/service/AnalysisFlow.java`

**Sınıfı yazmadan önce:** Bir kullanım senaryosunun sırasını yönetir. Constructor bağımlılıklarını ve transaction sınırını önce oku; hesap ve SQL sorumluluklarını ayır.

```java
package com.cryptolab.strategy.application.service;
import com.cryptolab.strategy.application.port.ModelClient;
import com.cryptolab.strategy.domain.model.PredictionResult;
import java.util.UUID;
import java.time.Instant;
/**
 * Sorumluluk: Bir kullanım senaryosunun sırasını yönetir.
 * Okuma ipucu: Constructor bağımlılıklarını ve transaction sınırını önce oku; hesap ve SQL sorumluluklarını ayır.
 */
public final class AnalysisFlow {
 public record Result(String status,String action,String reason,PredictionResult prediction){}
 private final ModelClient model;
 public AnalysisFlow(ModelClient model){this.model=model;}
 public Result evaluate(UUID request,UUID instrument,int horizon,Instant asOf,UUID dataset,UUID snapshot,
  ModelClient.Bundle pinned){
  if(!pinned.supportedHorizons().contains(horizon))return new Result("FAILED",null,"MODEL_NOT_READY",null);
  try{
   var p=model.predict(new ModelClient.Request(request,instrument,horizon,asOf,pinned.bundleId(),dataset,snapshot,pinned.featureVersion()));
   if(!p.requestId().equals(request)||!p.modelBundleId().equals(pinned.bundleId())||!p.featureVersion().equals(pinned.featureVersion())
    ||!p.featureSchemaHash().equals(pinned.featureSchemaHash())||p.horizonSeconds()!=horizon||!p.asOf().equals(asOf))
    return new Result("FAILED",null,"FEATURE_SCHEMA_MISMATCH",null);
   if(p.quality()!=PredictionResult.Quality.READY)return new Result("SUCCEEDED","WAIT",p.quality().name(),p);
   // READY numeric prediction'dır; policy henüz yön seçmedi. Dış AnalysisView'e ara state olarak maplenir.
   return new Result("RUNNING",null,"POLICY_PENDING",p);
  }catch(RuntimeException e){return new Result("FAILED",null,"MODEL_UNAVAILABLE",null);}
 }
}
```

### `backend/strategy-service/src/main/java/com/cryptolab/strategy/infrastructure/client/HttpModelClient.java`

**Sınıfı yazmadan önce:** Dış sistem veya teknik sınırı uygular. Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.

```java
package com.cryptolab.strategy.infrastructure.client;
import com.cryptolab.strategy.application.port.ModelClient;
import com.cryptolab.strategy.domain.model.PredictionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;
/**
 * Sorumluluk: Dış sistem veya teknik sınırı uygular.
 * Okuma ipucu: Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.
 */
public class HttpModelClient implements ModelClient {
 private final URI base;private final String secret;private final ObjectMapper json;
 private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
 public HttpModelClient(URI base,String secret,ObjectMapper json){this.base=base;this.secret=secret;this.json=json;}
 public Bundle activeBundle(int h){return send("/internal/model/active-bundle?horizonSeconds="+h,null,Bundle.class);}
 public PredictionResult predict(Request request){return send("/internal/model/predictions",request,PredictionResult.class);}
 private <T>T send(String path,Object body,Class<T> type){
  try{
   var request=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(10))
    .header("X-Service-Secret",secret).header("X-Request-Id",UUID.randomUUID().toString())
    .header("X-Deadline",java.time.Instant.now().plusSeconds(10).toString()).header("Content-Type","application/json");
   if(body==null)request.GET();else request.POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
   var response=client.send(request.build(),HttpResponse.BodyHandlers.ofInputStream());
   try(var in=response.body()){
    if(response.statusCode()!=200)throw new IllegalStateException("MODEL_HTTP_"+response.statusCode());
    byte[] bytes=in.readNBytes(1_048_577);if(bytes.length>1_048_576)throw new IllegalStateException("MODEL_PAYLOAD_TOO_LARGE");
    return json.readValue(bytes,type);
   }
  }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("MODEL_CANCELLED",e);}
  catch(Exception e){throw new IllegalStateException("MODEL_UNAVAILABLE",e);}
 }
}
```

Bundle HTTP cevabı tam ActiveModelBundle alanlarını taşır. Yukarıdaki küçük internal Bundle projection’ı için ayrı tolerant ObjectMapper gerekir (`FAIL_ON_UNKNOWN_PROPERTIES=false`); command DTO mapper’ını global gevşetme. Service secret yalnız iç ağa gider, tarayıcı header’ından taşınmaz. Actor scope gerektiren çağrılarda doğrulanmış subject ayrıca internal context’e eklenir; dataset erişimi AI’de de yetkilendirilir. Clock/deadline kaynağını testte inject ederek sabitle.

## B09.2–B09.4 — Kalıcı worker

POST analyses: owner/session doğrula → request dedupe → analysis_jobs QUEUED + outbox atomik → 202. Worker job’ı lease + worker_token ile claim eder. Model HTTP çağrısı sırasında DB transaction açık tutulmaz. Önce bundle ID, asOf ve generation kalıcı pin edilir; son güncelleme `WHERE worker_token=? AND status='RUNNING'` ile yapılır. Eski worker’ın geç sonucu yeni job state’ini ezemez.

Coverage eksikse WAITING_DATA ve unique range backfill; terminal model quality yetersizse SUCCEEDED/WAIT. Model servisi down → FAILED, action=null. NumericPolicy parametrelerini validation artifact’ından al; bu örnekte evrensel optimum sayı uydurulmadı. ATR ve reference, verified feature snapshot’ın Decimal alanlarından gelir. Explanation modelinden fiyat seviyesi çıkarma.

## B09.5 — AI preview bağlantısı

AI_PROPOSAL request yalnız sessionId/proposalId/proposalRevision taşır. Simulation internal Strategy query’sinden owner, revision, generation, validUntil ve LONG/SHORT intent’i alır. AI önerisinde SL/TP zorunlu. RiskPolicyEvaluator equity ve available ile miktarı hesaplar; proposal’da q/margin/fee otoritesi yok. Sonra aynı ExecutionPlan/PlanHash/SubmitOrder hattı. Proposal değiştirilirse source MANUAL + originProposalId; eski onay geçerli olmaz.

## B09.6 — Tarama

Universe snapshot ID ve kararlı cursor ile sayfala. Her aday `eligible`, `skippedReason` veya `analysisId` olur; değerlendirilen ve atlanan toplamlarını kaydet. Limit=200 sayfa boyutudur, ürün coin sınırı değildir. Bütçe bitince PARTIAL ve cursor döndür; “tüm piyasa tarandı” deme. Cancel yeni iş planlamayı durdurur; önceden açık pozisyon kapanmaz.

## B09.7 — Açıklama

Numeric sonuç + policy intent hazırsa proposal TTL’yi başlat. Açıklama çağrısı timeout olunca template explanationStatus=TEMPLATE. TTL metin bekledi diye uzamaz. Template yalnız verified reasonCodes/featureSummary kullanır; uydurma kitap sayfası, PDF retrieval veya yeni fiyat önerisi yok.

**Test:** fake WAIT → proposal sayısı0/order sayısı0. READY LONG fake → policy doğru stop/target; hâlâ order0. Model timeout → FAILED. Bundle değişirken eski request pinned bundle’da kalır. Feature hash mismatch → FAILED. Scan cancel → yeni analysis yok. Expired proposal preview reddedilir. Gerçek A05 hazır değilse INTEGRATION_PENDING kaydı.

## Kabul: hangi çıktıyı görmelisin?

**Deneme/kabul:** Scan/analysis başlatmak order sayısını değiştirmez; WAIT proposal yaratmaz; model unavailable teknik hata; score’dan para hesabı çıkmaz. Feature/model versions tek istekte karışmaz. Development candidate serving’in ACTIVE model kalite onayı olmadığı kaydedilir; A09 production promotion kapısı korunur.



## Kendi cümlelerinle anlat

“Model LONG dediğinde neden Simulation yine de planı reddedebilir?”

## Çalışma sonunda

`progress/BACKEND.md` içine alt görev, değiştirdiğin dosya, komut, gerçek çıktı, fixture/gerçek entegrasyon ayrımı ve sıradaki tek adımı yaz. Çalıştırmadığın test için PASS yazma.
