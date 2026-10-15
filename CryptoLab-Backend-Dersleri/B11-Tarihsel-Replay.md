# B11 — Tarihsel replay ve model değerlendirme köprüsü

> **DISC-01:** Eureka/local adres çözümlemesi için B01A-Eureka-ve-Servis-Kesfi.md uygulanır. Uygulama ayarları properties olarak kalır; iş kuralları değişmez.

Şartname: 3.0 · Contract: 1.0.0 · Öğretici kod referansı · Durum: BUILD_UNVERIFIED

ACC-01 eki: Account, servis sahipliği ve properties standardı için [güncel mimari eki](03-ACCOUNT-VE-GUVENLIK-EKI.md) geçerlidir.
Replay geçmiş veriyi sanal saatle aynı Java finans motoruna verir. Aynı mumun kapanışını görüp o mumun en iyi fiyatından giriş yapmak geleceği kullanmaktır. Deterministik olmak gerçek borsayla birebir aynı olmak anlamına gelmez.

Bu dosyadaki kodları kendi projenize sırayla elle yazın. Burada uygulama reposu kurulmuş veya kabul testleri geçmiş değildir. Dosya yolları CryptoLab köküne göredir. Bir sınıfı yazdıktan sonra küçük deneyi yapın; tüm belgeyi tek hamlede kopyalamayın.

## Bu dersi nasıl okuyacaksın? — Replay’de aynı hesabı farklı saatle çalıştırmak

**Önce kavram:** VirtualClock ve ReplayRunner application.replay altında olay akışını yönetir. ReplayInput/FidelityReport domain modelidir. BarAmbiguity saf kuraldır. ArtifactReader dosya/hash adaptörüdür. ReplayScope canlı hesapla tarihsel çalışmayı ayırır.

**Yazma sırası:** ReplayInput/VirtualClock → BarAmbiguity → ReplayRunner → ArtifactReader → FidelityReport/ReplayScope → job API ve writer bağlantısı.

**Neden bu ayrım var?** Replay için ayrı finans formülü yazma. Mum kapanmadan o mumu feature’a vermek lookahead üretir. OHLC, tick sırasının kesin kanıtı değildir. Tarihsel örneği canlı cüzdana yazma.

**Küçük pratik:** Aynı fixture+seed+policy ile iki replay çıktısını karşılaştır. Aynı mumda SL/TP olursa açık ambiguity politikası uygulanmalı; en kârlı sıra varsayılmamalı.

Ortak kurallar: [Paket ve kod standardı](01-PAKET-VE-KOD-STANDARDI.md). Dosyaların tek tek yeni konumları: [Sınıf haritası](02-SINIF-VE-PAKET-HARITASI.md). Bu dosyada verilen yeni package/import/path birlikte kullanılmalıdır; eski ve yeni kopyaları aynı projeye ekleme.

### Bu görevde sınıflar hangi sorumlulukta?

| Sınıf | Paket | Neden burada? |
|---|---|---|
| `VirtualClock` | `application.replay` | Tarihsel çalışmanın yürütülmesini yönetir. |
| `ReplayInput` | `domain.model.replay` | İş verisini ve kendi değişmez kurallarını temsil eder. |
| `ReplayRunner` | `application.replay` | Tarihsel çalışmanın yürütülmesini yönetir. |
| `FidelityReport` | `domain.model.replay` | İş verisini ve kendi değişmez kurallarını temsil eder. |
| `BarAmbiguity` | `domain.service.replay` | Girdilerden iş kararı/hesap üretir. |
| `ArtifactReader` | `infrastructure.artifact` | Dış sistem veya teknik sınırı uygular. |
| `ReplayScope` | `application.replay` | Tarihsel çalışmanın yürütülmesini yönetir. |
| `ReplayRunnerTest` | `application.replay` | Ders davranışını doğrulayan testtir. |

## Kaynak görevlerin eksiksiz kontrol listesi

- [ ] **B11.1** Clock injection, seed ve immutable dataset/policy/rule hash’lerini run’a bağla; wall clock bağımlılıklarını çıkar.
- [ ] **B11.2** Önce sentetik recorded input journal’ını aynı sırada tekrar uygula; aynı ledger sonucu.
- [ ] **B11.3** Timestamp tie policy: funding boundary/settlement/mark/SLTP/quote/entry/candle düzenini şartnameye göre uygula.
- [ ] **B11.4** RECORDED_TICKS/PAIRED_OHLC/TRADE_ONLY kalite ayrımı; same bar SL+TP, missing mark ve lookahead testleri.
- [ ] **B11.5** Policy intent artifact→finance engine ve bounded async job/cancel; replay başka session’a emir açamaz.
- [ ] **B11.6** AI internal eval request→202 job→result artifact/metrics; A09’a fixture ver. Historical dataset+intent önce sentetik olabilir.
- [ ] **B11.7** Authorized result/CSV, net/gross/cost/insurance ve ambiguousBarCount; frontend replay API’siyle aynı sonuç anlamı.

## B11.1 — Saat ve veri manifesti

Replay kendi session namespace’inde HISTORICAL_REPLAY olarak oluşturulur. LIVE_PAPER session’ın mode’u sonradan değiştirilmez. Dataset, policy, rule ve model hash’leri run başında dondurulur. Tarihsel loop içinde sistem saati, internet çağrısı, Math.random yok. Seed ve tiePolicy sürümü run kaydındadır.

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/application/replay/VirtualClock.java`

**Sınıfı yazmadan önce:** Tarihsel çalışmanın yürütülmesini yönetir. Sanal saat ve deterministik sıra gerçek sistem saatinden bağımsız tutulur.

```java
package com.cryptolab.simulation.application.replay;
import java.time.*;
/**
 * Sorumluluk: Tarihsel çalışmanın yürütülmesini yönetir.
 * Okuma ipucu: Sanal saat ve deterministik sıra gerçek sistem saatinden bağımsız tutulur.
 */
public final class VirtualClock extends Clock {
 private Instant current;
 public VirtualClock(Instant start){this.current=java.util.Objects.requireNonNull(start);}
 public void advanceTo(Instant next){
  if(next.isBefore(current))throw new IllegalArgumentException("CLOCK_MOVED_BACKWARD");
  current=next;
 }
 @Override public ZoneId getZone(){return ZoneOffset.UTC;}
 @Override public Clock withZone(ZoneId zone){
  if(!ZoneOffset.UTC.equals(zone))throw new IllegalArgumentException("UTC_ONLY");return this;
 }
 @Override public Instant instant(){return current;}
}
```

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/domain/model/replay/ReplayInput.java`

**Sınıfı yazmadan önce:** İş verisini ve kendi değişmez kurallarını temsil eder. Modelin HTTP veya veritabanı biçimine bağımlı olup olmadığını kontrol et.

```java
package com.cryptolab.simulation.domain.model.replay;
import java.time.Instant;
import java.util.UUID;
/**
 * Sorumluluk: İş verisini ve kendi değişmez kurallarını temsil eder.
 * Okuma ipucu: Modelin HTTP veya veritabanı biçimine bağımlı olup olmadığını kontrol et.
 */
public record ReplayInput(UUID id,Instant time,Kind kind,long originalSequence,String payload) {
 public enum Kind {
  STATUS_BOUNDARY(1),FUNDING_SETTLEMENT(2),MARK(3),BRACKET(4),QUOTE_EXIT(5),ENTRY_FILL(6),CANDLE_CLOSE(7);
  private final int priority;Kind(int priority){this.priority=priority;}public int priority(){return priority;}
 }
 // payload üretildiğinde canonical/hash kontrolü yapılır; replay zamanı değiştirilmez.
}
```

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/application/replay/ReplayRunner.java`

**Sınıfı yazmadan önce:** Tarihsel çalışmanın yürütülmesini yönetir. Sanal saat ve deterministik sıra gerçek sistem saatinden bağımsız tutulur.

```java
package com.cryptolab.simulation.application.replay;
import com.cryptolab.simulation.domain.model.Journal;
import com.cryptolab.simulation.domain.model.replay.ReplayInput;
import java.util.*;
import java.util.function.BooleanSupplier;
/**
 * Sorumluluk: Tarihsel çalışmanın yürütülmesini yönetir.
 * Okuma ipucu: Sanal saat ve deterministik sıra gerçek sistem saatinden bağımsız tutulur.
 */
public final class ReplayRunner {
 public interface Engine {
  // Aynı finance-core ve session writer uygulaması bağlanır; ikinci para motoru değil.
  void accept(ReplayInput input,VirtualClock clock,SplittableRandom random);
 }
 public record Result(String status,long applied,String fidelity){}
 public Result run(List<ReplayInput> input,boolean recordedJournal,long seed,VirtualClock clock,
                   Engine engine,BooleanSupplier cancelled,String fidelity){
  List<ReplayInput> ordered=new ArrayList<>(input);
  if(recordedJournal){
   ordered.sort(Comparator.comparingLong(ReplayInput::originalSequence));
   long previous=0;
   for(var e:ordered){if(e.originalSequence()!=previous+1)throw new IllegalArgumentException("ENGINE_SEQUENCE_GAP");previous=e.originalSequence();}
  }else{
   // UUID tie-break gerçek piyasa sırası iddiası değildir, tekrarlanabilirlik seçimidir.
   ordered.sort(Comparator.comparing(ReplayInput::time).thenComparingInt(e->e.kind().priority()).thenComparing(e->e.id().toString()));
  }
  var random=new SplittableRandom(seed);long applied=0;
  for(var e:ordered){
   if(cancelled.getAsBoolean())return new Result("CANCELLED",applied,fidelity);
   // Journal source time geriye gidebilir. Journal kaydında acceptedTime kullanılmalıdır.
   clock.advanceTo(e.time());engine.accept(e,clock,random);applied++;
  }
  return new Result("INDETERMINATE".equals(fidelity)?"INDETERMINATE":"SUCCEEDED",applied,fidelity);
 }
}
```

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/domain/model/replay/FidelityReport.java`

**Sınıfı yazmadan önce:** İş verisini ve kendi değişmez kurallarını temsil eder. Modelin HTTP veya veritabanı biçimine bağımlı olup olmadığını kontrol et.

```java
package com.cryptolab.simulation.domain.model.replay;
/**
 * Sorumluluk: İş verisini ve kendi değişmez kurallarını temsil eder.
 * Okuma ipucu: Modelin HTTP veya veritabanı biçimine bağımlı olup olmadığını kontrol et.
 */
public record FidelityReport(String dataLayer,String fidelity,long ambiguousBarCount,
 boolean missingMark,boolean gaps,boolean lateFunding) {
 public static FidelityReport classify(String layer,boolean gap,boolean missingMark,long ambiguous,boolean lateFunding){
  if(!java.util.Set.of("RECORDED_TICKS","PAIRED_OHLC","TRADE_ONLY").contains(layer)||ambiguous<0)throw new IllegalArgumentException("INVALID_FIDELITY_INPUT");
  String f="TRADE_ONLY".equals(layer)||missingMark?"INDETERMINATE":gap||lateFunding?"DEGRADED":
   "PAIRED_OHLC".equals(layer)||ambiguous>0?"APPROXIMATE":"EXACT_WITHIN_MODEL";
  return new FidelityReport(layer,f,ambiguous,missingMark,gap,lateFunding);
 }
}
```

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/domain/service/replay/BarAmbiguity.java`

**Sınıfı yazmadan önce:** Girdilerden iş kararı/hesap üretir. Aynı girdinin aynı sonucu üretmesi beklenir; DB/ağ yan etkisi ekleme.

```java
package com.cryptolab.simulation.domain.service.replay;
import java.math.BigDecimal;
import com.cryptolab.finance.PositionSide;
/**
 * Sorumluluk: Girdilerden iş kararı/hesap üretir.
 * Okuma ipucu: Aynı girdinin aynı sonucu üretmesi beklenir; DB/ağ yan etkisi ekleme.
 */
public final class BarAmbiguity {
 public record Trigger(String reason,boolean ambiguous){}
 private BarAmbiguity(){}
 public static Trigger bracket(PositionSide side,BigDecimal markLow,BigDecimal markHigh,BigDecimal stop,BigDecimal target){
  boolean longSide=side==PositionSide.LONG;
  boolean hitStop=stop!=null&&(longSide?markLow.compareTo(stop)<=0:markHigh.compareTo(stop)>=0);
  boolean hitTarget=target!=null&&(longSide?markHigh.compareTo(target)>=0:markLow.compareTo(target)<=0);
  // Adverse-first: aynı mumda ikisi varsa stop. Bu bir path tahmini, kesin tick sırası değil.
  if(hitStop)return new Trigger("STOP_LOSS",hitTarget);
  if(hitTarget)return new Trigger("TAKE_PROFIT",false);
  return new Trigger("NONE",false);
 }
}
```

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/infrastructure/artifact/ArtifactReader.java`

**Sınıfı yazmadan önce:** Dış sistem veya teknik sınırı uygular. Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.

```java
package com.cryptolab.simulation.infrastructure.artifact;
import java.nio.file.*;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
/**
 * Sorumluluk: Dış sistem veya teknik sınırı uygular.
 * Okuma ipucu: Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.
 */
public final class ArtifactReader {
 private final Path root;
 public ArtifactReader(Path root)throws Exception{this.root=root.toRealPath();}
 public Path verified(String relative,String expectedHash,long maxBytes)throws Exception{
  // Artifact ID -> relative path eşlemesi yetkili DB'den gelir; kullanıcı path gönderemez.
  Path file=root.resolve(relative).normalize().toRealPath();
  if(!file.startsWith(root)||!Files.isRegularFile(file)||Files.size(file)>maxBytes)throw new IllegalArgumentException("INVALID_ARTIFACT");
  var digest=MessageDigest.getInstance("SHA-256");long total=0;
  try(InputStream in=Files.newInputStream(file)){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){total+=n;if(total>maxBytes)throw new IllegalArgumentException("ARTIFACT_TOO_LARGE");digest.update(buf,0,n);}}
  String actual="sha256:"+HexFormat.of().formatHex(digest.digest());
  if(!actual.equals(expectedHash))throw new IllegalArgumentException("ARTIFACT_HASH_MISMATCH");return file;
 }
}
```

Artifact doğrulama ile okuma arasında dosya değişmemesi için immutable read-only artifact deposu kullan; doğruladığın path’i sonradan kullanıcı tarafından yazılabilir dizinden tekrar okuma. Çok büyük input’ta tüm List’i belleğe alma: sıralı shard iterator ve bounded merge gerekir. İlk sentetik deney List ile başlar; ölçülmüş ölçek sınırı açık kaydedilir.

## B11.2–B11.4 — Olay sırası ve lookahead

| Aynı zaman önceliği | İşlem | Sebep |
|---|---|---|
| 1 | status/rule + funding boundary q | O anda açılan yeni entry funding’e girmesin |
| 2 | kesin funding settlement | B değişimi riskten önce |
| 3 | mark + liquidation | risk önceliği |
| 4 | SL/TP trigger | liquidation ile yarışta ikinci |
| 5 | quote + pending exit | azaltıcı iş önce |
| 6 | entry fill | kullanıcı/run yetkisi kapsamında |
| 7 | candle close feature | kapanıştan çıkan karar geçmiş fill olamaz |

Kaydedilmiş accepted-input journal’da bu sıralamayı tekrar uygulama; original engineSequence otoritedir. `ReplayInput.time` journal modu için accepted virtual time; original sourceTime payload’da ayrı tutulur. Gecikmiş source-time event geriye saat taşıyamaz.

Candle sinyali için `availableAt<=decisionTime`; en erken fill sonraki eligible quote/bar open. Önceki candle feature’ını gelecekteki candle silindiğinde aynı bulmalısın. Mark low/high liquidation eşiğini geçebilir; TRADE_ONLY data ile “likide olmadı” kesin sonucu yok.

## B11.5–B11.7 — Job API ve değerlendirme

### `backend/simulation-service/src/main/resources/db/migration/V4__replay.sql`

```sql
CREATE TABLE replay_jobs(id uuid PRIMARY KEY,owner_id uuid NOT NULL,status text NOT NULL,
 session_id uuid NOT NULL REFERENCES simulation_sessions(id),dataset_id uuid NOT NULL,
 rule_profile_id uuid NOT NULL,policy_artifact_id uuid NOT NULL,seed bigint NOT NULL,
 dataset_hash text NOT NULL,rule_hash text NOT NULL,policy_hash text NOT NULL,
 tie_policy text NOT NULL,data_layer text NOT NULL,fidelity text NOT NULL,
 ambiguous_bar_count bigint NOT NULL DEFAULT 0,result_artifact_id uuid,
 cancelled boolean NOT NULL DEFAULT false,lease_until timestamptz,worker_token uuid,
 created_at timestamptz NOT NULL,updated_at timestamptz NOT NULL);
```

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/application/replay/ReplayScope.java`

**Sınıfı yazmadan önce:** Tarihsel çalışmanın yürütülmesini yönetir. Sanal saat ve deterministik sıra gerçek sistem saatinden bağımsız tutulur.

```java
package com.cryptolab.simulation.application.replay;
import java.util.UUID;
/**
 * Sorumluluk: Tarihsel çalışmanın yürütülmesini yönetir.
 * Okuma ipucu: Sanal saat ve deterministik sıra gerçek sistem saatinden bağımsız tutulur.
 */
public record ReplayScope(UUID runId,UUID sessionId,String sessionMode,UUID policyArtifactId) {
 public ReplayScope {
  if(!"HISTORICAL_REPLAY".equals(sessionMode))throw new IllegalArgumentException("REPLAY_SCOPE_REQUIRED");
 }
 public void verify(UUID targetSession,UUID suppliedRun){
  // İç eval endpoint'i LIVE_PAPER onay kapısını dolaşamaz.
  if(!sessionId.equals(targetSession)||!runId.equals(suppliedRun))throw new IllegalArgumentException("REPLAY_SCOPE_MISMATCH");
 }
}
```

### `backend/simulation-service/src/test/java/com/cryptolab/simulation/application/replay/ReplayRunnerTest.java`

**Sınıfı yazmadan önce:** Davranışı doğrulayan test. Beklenen sonucu bağımsız belirle; testin gerçekten çalıştığını raporla.

```java
package com.cryptolab.simulation.application.replay;
import com.cryptolab.simulation.domain.model.replay.FidelityReport;
import com.cryptolab.simulation.domain.model.replay.ReplayInput;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
/**
 * Sorumluluk: Davranışı doğrulayan test.
 * Okuma ipucu: Beklenen sonucu bağımsız belirle; testin gerçekten çalıştığını raporla.
 */
class ReplayRunnerTest {
 @Test void deterministicTieAndCancel(){
  var t=Instant.parse("2026-01-01T00:00:00Z");
  var quote=new ReplayInput(UUID.fromString("11111111-1111-4111-8111-111111111111"),t,ReplayInput.Kind.QUOTE_EXIT,2,"{}");
  var mark=new ReplayInput(UUID.fromString("22222222-2222-4222-8222-222222222222"),t,ReplayInput.Kind.MARK,1,"{}");
  var order=new ArrayList<ReplayInput.Kind>();
  var runner=new ReplayRunner();
  var r=runner.run(List.of(quote,mark),false,42,new VirtualClock(t),(e,c,random)->order.add(e.kind()),()->false,"EXACT_WITHIN_MODEL");
  assertEquals(List.of(ReplayInput.Kind.MARK,ReplayInput.Kind.QUOTE_EXIT),order);assertEquals("SUCCEEDED",r.status());
  var cancelled=runner.run(List.of(mark),false,42,new VirtualClock(t),(e,c,random)->fail("cancel sonrası işlem yok"),()->true,"APPROXIMATE");
  assertEquals("CANCELLED",cancelled.status());assertEquals(0,cancelled.applied());
 }
 @Test void missingMarkCannotBeExact(){assertEquals("INDETERMINATE",FidelityReport.classify("TRADE_ONLY",false,true,0,false).fidelity());}
}
```

POST /api/v1/sim/replays owner+idempotency→durable ReplayJob202; worker immutable manifest okuyup kendi replay session’ında çalışır. Cancel owner-aware optimistic mutation; worker her bounded batch sonrası flag kontrol eder. Cancelled run’ın kısmi artifact’ı status=SUCCEEDED olarak sunulmaz.

Internal POST /internal/sim/replay-evaluations service identity ile datasetId/policyArtifactId/ruleProfileId/seed alır; scoped owner ve run kaydı oluşturur. AI doğrudan finance-core kopyalamaz; result artifact/metric alır. Policy intent alanları README/Backend17 ile aynı. Replay intent “user approved live order” değildir; sadece run scope’unda yetkilidir.

Sonuç gross/net/fees/funding/liquidationFees/insurance ayrı; fidelity/dataLayer/ambiguousBarCount/hash’ler artifact metadata’sına yazılır. CSV download owner kontrolünden geçer. Aynı seed/hash/input ledger checksum aynı olmalı; UUID ve recorded wall-clock gibi deterministik olmayan audit alanlarını ekonomik checksum’dan açıkça ayır.

**B11 kapsam kontrolü:** Saat/sıra/ambiguity/hash/scope sınıfları burada tam verilidir. Session writer bağlantısı, job persistence, JSONL iterator ve REST DTO/controller adımları ayrıca uygulanmadan replay teslimi tamamlanmış sayılmaz. A09’u beklemek gerekmez; sentetik intent ile başlayabilirsin.

## Kabul: hangi çıktıyı görmelisin?

**Deneme/kabul:** Aynı input/hash/seed aynı sonuç; kapalı mum t sinyali aynı mumun en iyi fiyatından fill olmaz. Eksik mark’ta kesin liquidation iddiası yok. Cancel yarım sonucu başarı diye yayınlamaz. B11 A09’u beklemeden synthetic fixture ile tamamlanabilir; karşılıklı gerçek bağlantı B12’de.



## Kendi cümlelerinle anlat

“OHLC mumunda hem stop hem hedef görüldüyse niye sonucu kesin bilemeyiz?”

## Çalışma sonunda

`progress/BACKEND.md` içine alt görev, değiştirdiğin dosya, komut, gerçek çıktı, fixture/gerçek entegrasyon ayrımı ve sıradaki tek adımı yaz. Çalıştırmadığın test için PASS yazma.
