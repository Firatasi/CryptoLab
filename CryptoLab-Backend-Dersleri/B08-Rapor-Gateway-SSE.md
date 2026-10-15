# B08 — Reporting, Redis, gateway ve SSE

> **DISC-01:** Eureka/local adres çözümlemesi için B01A-Eureka-ve-Servis-Kesfi.md uygulanır. Uygulama ayarları properties olarak kalır; iş kuralları değişmez.

Şartname: 3.0 · Contract: 1.0.0 · Öğretici kod referansı · Durum: BUILD_UNVERIFIED

ACC-01 eki: Account, servis sahipliği ve properties standardı için [güncel mimari eki](03-ACCOUNT-VE-GUVENLIK-EKI.md) geçerlidir.
Reporting gecikebilir; finans otoritesi Simulation’dır. Rapor tüketicisi yalnız LedgerTransactionPosted ile para toplamlarını günceller; PositionClosed aynı kârı ikinci kez ekleyemez.

Bu dosyadaki kodları kendi projenize sırayla elle yazın. Burada uygulama reposu kurulmuş veya kabul testleri geçmiş değildir. Dosya yolları CryptoLab köküne göredir. Bir sınıfı yazdıktan sonra küçük deneyi yapın; tüm belgeyi tek hamlede kopyalamayın.

## Bu dersi nasıl okuyacaksın? — Okuma modeli ile para otoritesini ayırmak

**Önce kavram:** LedgerProjection infrastructure.repository altında Reporting DB’sinin transaction/inbox/checkpoint adaptörüdür; asıl bakiyenin sahibi değildir. ReportMetrics saf hesap yapar. Gateway ProxyController dış HTTP sınırıdır. SSE ve CSV sınıfları teknik output adaptörleridir.

**Yazma sırası:** Projection SQL → LedgerProjection → ReportMetrics → RedisLoginLimiter seçimi → RouteTable/ProxyController → SseRing → CsvCell.

**Neden bu ayrım var?** Reporting, Simulation DB’sine doğrudan bağlanmaz. CSV escaping finans hesabı değildir. SSE bağlantısı kapanınca risk işlemleri durmaz. Proxy header’ları kör kopyalanmaz.

**Küçük pratik:** Reporting tüketicisini durdur: Simulation çalışmayı sürdürmeli. Yeniden açıldığında duplicate event para değerini iki kez artırmamalı. CSV hücresinde formül başlangıcı örneğini dene.

Ortak kurallar: [Paket ve kod standardı](01-PAKET-VE-KOD-STANDARDI.md). Dosyaların tek tek yeni konumları: [Sınıf haritası](02-SINIF-VE-PAKET-HARITASI.md). Bu dosyada verilen yeni package/import/path birlikte kullanılmalıdır; eski ve yeni kopyaları aynı projeye ekleme.

### Bu görevde sınıflar hangi sorumlulukta?

| Sınıf | Paket | Neden burada? |
|---|---|---|
| `LedgerProjection` | `infrastructure.repository` | Portun SQL veya okuma modeli kalıcılığını uygular. |
| `ReportMetrics` | `domain.service` | Girdilerden iş kararı/hesap üretir. |
| `RedisLoginLimiter` | `infrastructure.ratelimit` | Dış sistem veya teknik sınırı uygular. |
| `RouteTable` | `infrastructure.config` | Teknik nesneleri ve Spring bağlantılarını kurar. |
| `ProxyController` | `api.controller` | HTTP isteğini uygulama akışına bağlar. |
| `SseRing` | `infrastructure.streaming` | Dış sistem veya teknik sınırı uygular. |
| `CsvCell` | `infrastructure.export` | Dış sistem veya teknik sınırı uygular. |

## Kaynak görevlerin eksiksiz kontrol listesi

- [ ] **B08.1** LedgerTransactionPosted tek finans projection kaynağı; PositionClosed timeline olsun, kâr iki kez toplanmasın.
- [ ] **B08.2** Summary/equity/trade/funding-fee ayrımı, null metric durumları ve projectionAsOf/lag alanlarını yaz.
- [ ] **B08.3** Projection rebuild API export/checkpoint; duplicate/missing sequence davranışı; finans event’inde pause/alarm.
- [ ] **B08.4** Redis summary cache/key/TTL/invalidation ve identity limiter adapter; DB fallback ve quota fallback’i açık test et.
- [ ] **B08.5** Gateway auth/market/sim/strategy/reports route; internal route kapalı, CORS, request limits ve safe logging.
- [ ] **B08.6** Market/session SSE, Last-Event-ID/resync.required, heartbeat ve Bearer proxy; session ownership.
- [ ] **B08.7** Bildirim read ve authorized CSV export; formül injection önlemi. Sağlık/metric temelini görünür yap.

## B08.1–B08.3 — Projection mantığı

Reporting POM’a JDBC/Flyway/Postgres/Kafka/Redis ekle, reporting_db/ayrı kullanıcı oluştur. Projection tablo tasarımı event’teki accountId’yi hesap türüyle eşlemeyi gerektirir. Ledger event yalnız accountId taşır; `accountId`’den “fee” tahmin edilmez. Simulation yetkili audit export’u account metadata + owner + journal/event sequence manifestini vermeli. Bootstrap bitmeden event karantinada bekler; başka DB’ye JOIN yapılmaz.

### `backend/reporting-service/src/main/resources/db/migration/V1__projections.sql`

```sql
CREATE TABLE session_owners(session_id uuid PRIMARY KEY,owner_id uuid NOT NULL);
CREATE TABLE account_metadata(account_id uuid PRIMARY KEY,session_id uuid NOT NULL,account_key text NOT NULL,kind text NOT NULL,position_id uuid);
CREATE TABLE ledger_projection(transaction_id uuid PRIMARY KEY,session_id uuid NOT NULL,event_id uuid NOT NULL UNIQUE,
 engine_sequence bigint NOT NULL,effective_at timestamptz NOT NULL,payload jsonb NOT NULL);
CREATE TABLE account_projection(account_id uuid PRIMARY KEY,balance numeric(38,18) NOT NULL DEFAULT 0);
CREATE TABLE projection_checkpoints(session_id uuid PRIMARY KEY,last_sequence bigint NOT NULL DEFAULT 0,as_of timestamptz NOT NULL);
CREATE TABLE inbox(consumer text NOT NULL,event_id uuid NOT NULL,PRIMARY KEY(consumer,event_id));
CREATE TABLE equity_samples(session_id uuid NOT NULL,engine_sequence bigint NOT NULL,as_of timestamptz NOT NULL,
 equity numeric(38,18) NOT NULL,unrealized_pnl numeric(38,18) NOT NULL,quality text NOT NULL,PRIMARY KEY(session_id,engine_sequence));
CREATE TABLE trade_summaries(position_id uuid PRIMARY KEY,session_id uuid NOT NULL,gross numeric(38,18) NOT NULL DEFAULT 0,
 fees numeric(38,18) NOT NULL DEFAULT 0,funding numeric(38,18) NOT NULL DEFAULT 0,
 liquidation_fees numeric(38,18) NOT NULL DEFAULT 0,insurance numeric(38,18) NOT NULL DEFAULT 0,closed_at timestamptz);
CREATE TABLE notifications(id uuid PRIMARY KEY,owner_id uuid NOT NULL,source_event_id uuid NOT NULL UNIQUE,
 type text NOT NULL,payload jsonb NOT NULL,read_at timestamptz,created_at timestamptz NOT NULL);
CREATE TABLE report_jobs(id uuid PRIMARY KEY,owner_id uuid NOT NULL,session_id uuid NOT NULL,status text NOT NULL,
 artifact_id uuid,created_at timestamptz NOT NULL);
```

### `backend/reporting-service/src/main/java/com/cryptolab/reporting/infrastructure/repository/LedgerProjection.java`

**Sınıfı yazmadan önce:** Portun SQL veya okuma modeli kalıcılığını uygular. Parametreli SQL, transaction katılımı, owner/unique/lock kapsamını incele.

```java
package com.cryptolab.reporting.infrastructure.repository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
/**
 * Sorumluluk: Portun SQL veya okuma modeli kalıcılığını uygular.
 * Okuma ipucu: Parametreli SQL, transaction katılımı, owner/unique/lock kapsamını incele.
 */
@Service
public class LedgerProjection {
 private final JdbcTemplate db;private final ObjectMapper json;
 public LedgerProjection(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
 @Transactional public void accept(String raw)throws Exception{
  var event=json.readTree(raw);UUID id=UUID.fromString(event.required("eventId").asText());
  UUID session=UUID.fromString(event.required("sessionId").asText());long seq=event.required("engineSequence").asLong();
  db.update("INSERT INTO projection_checkpoints VALUES (?,0,?) ON CONFLICT DO NOTHING",session,Timestamp.from(Instant.EPOCH));
  long last=db.queryForObject("SELECT last_sequence FROM projection_checkpoints WHERE session_id=? FOR UPDATE",Long.class,session);
  Integer seen=db.queryForObject("SELECT count(*) FROM inbox WHERE consumer='reporting-simulation-v2' AND event_id=?",Integer.class,id);
  if(seen!=null&&seen>0)return;
  if(seq!=last+1)throw new IllegalStateException("PROJECTION_SEQUENCE_GAP");
  if("LedgerTransactionPosted".equals(event.required("eventType").asText())){
   var p=event.required("payload");UUID journal=UUID.fromString(p.required("journalTransactionId").asText());
   BigDecimal sum=BigDecimal.ZERO;
   for(var posting:p.required("postings"))sum=sum.add(new BigDecimal(posting.required("amount").asText()));
   if(sum.signum()!=0)throw new IllegalStateException("UNBALANCED_EVENT");
   int added=db.update("INSERT INTO ledger_projection VALUES (?,?,?,?,?,?::jsonb) ON CONFLICT(transaction_id) DO NOTHING",
    journal,session,id,seq,Timestamp.from(Instant.parse(p.required("effectiveAt").asText())),p.toString());
   if(added==1)for(var posting:p.required("postings")){
    UUID account=UUID.fromString(posting.required("accountId").asText());
    int metadata=db.queryForObject("SELECT count(*) FROM account_metadata WHERE account_id=? AND session_id=?",Integer.class,account,session);
    if(metadata!=1)throw new IllegalStateException("ACCOUNT_METADATA_MISSING");
    db.update("INSERT INTO account_projection VALUES (?,?) ON CONFLICT(account_id) DO UPDATE SET balance=account_projection.balance+EXCLUDED.balance",account,new BigDecimal(posting.required("amount").asText()));
   }
  }
  // Position events için para ekleme yok; timeline handler burada ayrı çağrılır.
  if("SessionValuationUpdated".equals(event.required("eventType").asText())){
   var p=event.required("payload");
   db.update("INSERT INTO equity_samples VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING",session,seq,Timestamp.from(Instant.parse(p.required("asOf").asText())),new BigDecimal(p.required("equity").asText()),new BigDecimal(p.required("unrealizedPnl").asText()),p.required("dataQuality").asText());
  }
  db.update("INSERT INTO inbox VALUES ('reporting-simulation-v2',?)",id);
  db.update("UPDATE projection_checkpoints SET last_sequence=?,as_of=? WHERE session_id=?",seq,Timestamp.from(Instant.parse(event.required("recordedAt").asText())),session);
 }
}
```

Bütün simulation.events.v2 event’leri checkpoint’ten geçmeli; sadece ledger event dinlersen sequence aralarında sahte gap görürsün. İlk event sequence=1 değilse “atlayıp başlayalım” yapma; audit export + checkpoint ile bootstrap et. Rebuild için yeni projection namespace/tables doldur, hash/count/ledger mutabakatından sonra aktif namespace’i atomik değiştir. Kafka retention tek başına yeterli arşiv değildir.

### `backend/reporting-service/src/main/java/com/cryptolab/reporting/domain/service/ReportMetrics.java`

**Sınıfı yazmadan önce:** Girdilerden iş kararı/hesap üretir. Aynı girdinin aynı sonucu üretmesi beklenir; DB/ağ yan etkisi ekleme.

```java
package com.cryptolab.reporting.domain.service;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
/**
 * Sorumluluk: Girdilerden iş kararı/hesap üretir.
 * Okuma ipucu: Aynı girdinin aynı sonucu üretmesi beklenir; DB/ağ yan etkisi ekleme.
 */
public final class ReportMetrics {
 private static final MathContext MC=MathContext.DECIMAL128;
 private ReportMetrics(){}
 public record Trade(BigDecimal realized,BigDecimal fees,BigDecimal funding,BigDecimal liquidationFees,BigDecimal insurance){
  public BigDecimal net(){return realized.subtract(fees).add(funding).subtract(liquidationFees);}
 }
 public static BigDecimal winRate(List<Trade> closed){
  if(closed.isEmpty())return null;
  long wins=closed.stream().filter(t->t.net().signum()>0).count();
  return BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(closed.size()),MC);
 }
 public static BigDecimal profitFactor(List<Trade> closed){
  BigDecimal gains=BigDecimal.ZERO,losses=BigDecimal.ZERO;
  for(var t:closed)if(t.net().signum()>0)gains=gains.add(t.net());else losses=losses.add(t.net().negate());
  return losses.signum()==0?null:gains.divide(losses,MC); // Infinity JSON üretme.
 }
 public static BigDecimal maxDrawdown(List<BigDecimal> chronologicalEquity){
  if(chronologicalEquity.isEmpty())return null;
  BigDecimal peak=chronologicalEquity.get(0),worst=BigDecimal.ZERO;
  for(var equity:chronologicalEquity){peak=peak.max(equity);if(peak.signum()>0)worst=worst.max(peak.subtract(equity).divide(peak,MC));}
  return worst;
 }
}
```

Bu sınıf ledger-derived trade/equity özetinden rapor metriği hesaplar; yeni bir fill/PnL motoru değildir. Insurance trade net’ine sessizce katılmaz; `insuranceAdjustment` ayrı alan. CurrentEquity son SessionValuationUpdated’dan gelir; asOf ve sampling interval gösterilir.

## B08.4 — Redis ve limiter

Summary key `report:{owner}:{session}:{projectionVersion}`, TTL10s. Önce DB/owner doğrula, sonra cache oku. Redis yoksa DB; eski asOf değiştirilmez. Login limiter için aşağıdaki Lua atomik INCR+EXPIRE’dır; connection failure’da B04 bounded local limiter çalışır. Distributed quota’nın fallback’te toplam instance sayısınca gevşeyebileceğini operasyon kaydına yaz.

### `backend/identity-service/src/main/resources/login-limit.lua`

```lua
-- ARGV[1]: pencere saniyesi. INCR ile EXPIRE ayrı ağ çağrısı olursa arada crash kalıcı key bırakabilir.
local count = redis.call('INCR', KEYS[1])
if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
return count
```

### `backend/identity-service/src/main/java/com/cryptolab/identity/infrastructure/ratelimit/RedisLoginLimiter.java`

**Sınıfı yazmadan önce:** Dış sistem veya teknik sınırı uygular. Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.

```java
package com.cryptolab.identity.infrastructure.ratelimit;
import com.cryptolab.identity.application.security.Crypto;
import com.cryptolab.identity.infrastructure.ratelimit.LocalLoginLimiter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import java.util.List;
/**
 * Sorumluluk: Dış sistem veya teknik sınırı uygular.
 * Okuma ipucu: Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.
 */
@Component
@org.springframework.context.annotation.Primary
public class RedisLoginLimiter implements com.cryptolab.identity.application.port.LoginLimiter {
 private final StringRedisTemplate redis;private final LocalLoginLimiter fallback;private final DefaultRedisScript<Long> script;
 public RedisLoginLimiter(StringRedisTemplate redis,LocalLoginLimiter fallback){
  this.redis=redis;this.fallback=fallback;script=new DefaultRedisScript<>();script.setLocation(new ClassPathResource("login-limit.lua"));script.setResultType(Long.class);
 }
 public void check(String key){
  Long count;
  try{count=redis.execute(script,List.of("login:"+Crypto.hash(key)),"60");}
  catch(DataAccessException e){fallback.check(key);return;}
  if(count==null){fallback.check(key);return;}
  if(count>10)throw new IllegalStateException("RATE_LIMITED");
 }
}
```

IdentityService zaten LoginLimiter portuna bağlıdır. B08’de @Primary RedisLoginLimiter seçilir; LocalLoginLimiter yalnız adaptörün fallback bağımlılığı olarak kalır. 429 hata zarfını ekle. Redis’te raw email tutmak yerine hash key kullanılması gereksiz kişisel veri yayılmasını azaltır.

## B08.5 — Gateway

Gateway tek dış giriş 8080 portundadır; yeni Account ayrı süreçte 8086 portunu kullanır. Route tablosu sabit serviceId allowlist kullanır; host/port Eureka + LoadBalancer üzerinden çözülür (B01A). Internal yolları ve istemciden gelen X-User-Id/X-Actor-Id/X-Service-* header’larını geçirme. Authorization ve Idempotency-Key korunur. Aşağıdaki route çözümleyici path traversal ve yanlış prefix eşleşmesini azaltır; proxy işini seçilen HTTP framework’e bağla.

### `backend/api-gateway/src/main/java/com/cryptolab/apigateway/infrastructure/config/RouteTable.java`

**Sınıfı yazmadan önce:** Teknik nesneleri ve Spring bağlantılarını kurar. Bean hangi portu karşılıyor ve hangi profile’da etkin sorularını cevapla.

```java
package com.cryptolab.apigateway.infrastructure.config;
import java.util.Map;
/**
 * Sorumluluk: Teknik nesneleri ve Spring bağlantılarını kurar.
 * Okuma ipucu: Bean hangi portu karşılıyor ve hangi profile’da etkin sorularını cevapla.
 */
public final class RouteTable {
 private static final Map<String,String> SERVICES=Map.of("auth","identity-service","market","market-data-service","sim","simulation-service","strategy","strategy-service","reports","reporting-service","account","account-service");
 private RouteTable(){}
 public static String serviceId(String rawPath){
  if(rawPath.equals("/.well-known/jwks.json"))return "identity-service";
  if(rawPath.contains("..")||rawPath.contains("%")||rawPath.contains("\\")||!rawPath.startsWith("/api/v1/"))throw new IllegalArgumentException("NO_ROUTE");
  String group=rawPath.substring(8).split("/",2)[0];String service=SERVICES.get(group);
  if(service==null)throw new IllegalArgumentException("NO_ROUTE");
  return service;
 }
}
```

### `backend/api-gateway/src/main/java/com/cryptolab/apigateway/api/controller/ProxyController.java`

**Sınıfı yazmadan önce:** HTTP isteğini uygulama akışına bağlar. DTO doğrulamasını, principal kaynağını ve HTTP cevabını takip et; SQL burada bulunmaz.

```java
package com.cryptolab.apigateway.api.controller;
import com.cryptolab.apigateway.infrastructure.client.GatewayTargetResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import java.net.http.*;
import java.time.Duration;
import java.util.Set;
/**
 * Sorumluluk: HTTP isteğini uygulama akışına bağlar.
 * Okuma ipucu: DTO doğrulamasını, principal kaynağını ve HTTP cevabını takip et; SQL burada bulunmaz.
 */
@RestController
public class ProxyController {
 private final GatewayTargetResolver targets;
 public ProxyController(GatewayTargetResolver targets){this.targets=targets;}
 private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
 private static final Set<String> REQUEST_HEADERS=Set.of("authorization","content-type","accept","idempotency-key","last-event-id","origin","x-csrf","cookie");
 private static final Set<String> RESPONSE_HEADERS=Set.of("content-type","cache-control","set-cookie","location","retry-after","x-accel-buffering");
 @RequestMapping({"/api/v1/**","/.well-known/jwks.json"}) public void proxy(HttpServletRequest in,HttpServletResponse out)throws Exception{
  long limit=in.getRequestURI().startsWith("/api/v1/strategy/training/sources")?20L*1024*1024:1024*1024;
  if(in.getContentLengthLong()>limit){out.sendError(413);return;}
  // Bu öğretim proxy'si bounded body buffer kullanır; sınırsız readAllBytes yok.
  byte[] body=in.getInputStream().readNBytes((int)limit+1);
  if(body.length>limit){out.sendError(413);return;}
  var builder=HttpRequest.newBuilder(targets.resolve(in.getRequestURI(),in.getQueryString()));
  boolean stream=in.getRequestURI().endsWith("/stream");
  if(!stream)builder.timeout(Duration.ofSeconds(15));
  for(String h:REQUEST_HEADERS){String value=in.getHeader(h);if(value!=null)builder.header(h,value);}
  builder.method(in.getMethod(),body.length==0?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body));
  // Tek send; POST'u otomatik uygulama retry'siyle yeniden gönderme.
  var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofInputStream());
  out.setStatus(response.statusCode());
  response.headers().map().forEach((name,values)->{if(RESPONSE_HEADERS.contains(name.toLowerCase()))values.forEach(v->out.addHeader(name,v));});
  try(var content=response.body()){
   byte[] buffer=new byte[8192];int read;
   while((read=content.read(buffer))!=-1){out.getOutputStream().write(buffer,0,read);if(stream)out.flushBuffer();}
  }
 }
}
```

Gateway MVC blocking proxy eğitim için açık tercihtir. Uzun SSE bağlantıları thread tutar; bounded executor/connection sayısı ve read-idle timeout ekleyip ölç. Çok bağlantılı hedefte Spring Cloud Gateway WebFlux’a uyumlu BOM ile geçmek ayrı uygulama seçimidir. Bu proxy doğrudan üretim ölçeği iddiası taşımaz. Gateway B04 resource server doğrulamasını da uygular; downstream de tekrar doğrular. CORS preflight Spring config ile `http://localhost:5173`, yöntem allowlist, credentials=true, Authorization/Content-Type/Idempotency-Key/X-CSRF/Last-Event-ID header’larına izin verir. Training body sınırını exact route ile daralt; JSON endpoint’leri 1MiB sınırında kalır.

## B08.6 — SSE ring buffer

Event id `epoch:sequence`. Process restart epoch değişir; eski Last-Event-ID bulunamazsa resync.required. Ring buffer yalnız transient bildirim, ledger arşivi değildir. Session ownership controller’da stream açılmadan doğrulanır; yayıncı herkesin session’ını aynı broadcast’e basmaz.

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/infrastructure/streaming/SseRing.java`

**Sınıfı yazmadan önce:** Dış sistem veya teknik sınırı uygular. Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.

```java
package com.cryptolab.simulation.infrastructure.streaming;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;
import java.io.IOException;
/**
 * Sorumluluk: Dış sistem veya teknik sınırı uygular.
 * Okuma ipucu: Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.
 */
public class SseRing {
 private record Event(String id,String name,Object data){}
 private final String epoch=UUID.randomUUID().toString();private long sequence;
 private final Deque<Event> ring=new ArrayDeque<>();private final Set<SseEmitter> clients=new HashSet<>();
 public synchronized SseEmitter subscribe(String lastId){
  var emitter=new SseEmitter(60_000L);
  emitter.onCompletion(()->remove(emitter));emitter.onTimeout(()->remove(emitter));emitter.onError(e->remove(emitter));
  if(lastId!=null&&!lastId.isBlank()){
   boolean found=false;
   for(var event:ring){if(found)send(emitter,event);if(event.id().equals(lastId))found=true;}
   if(!found)send(emitter,new Event(epoch+":"+sequence,"resync.required",Map.of("reason","CURSOR_EXPIRED")));
  }
  clients.add(emitter);return emitter;
 }
 public synchronized void publish(String name,Object data){
  var event=new Event(epoch+":"+(++sequence),name,data);ring.addLast(event);if(ring.size()>256)ring.removeFirst();
  for(var client:List.copyOf(clients))send(client,event);
 }
 public synchronized void heartbeat(){
  for(var client:List.copyOf(clients))try{client.send(SseEmitter.event().comment("heartbeat"));}catch(IOException e){clients.remove(client);client.complete();}
 }
 private void send(SseEmitter emitter,Event event){
  try{emitter.send(SseEmitter.event().id(event.id()).name(event.name()).data(event.data()));}
  catch(IOException e){clients.remove(emitter);emitter.complete();}
 }
 private synchronized void remove(SseEmitter e){clients.remove(e);}
}
```

Session→SseRing map’i sınırlı tut; son bağlantı kapandıktan sonra TTL ile kaldır. Heartbeat15s; 60s timeout sonrası istemci reconnect eder. Slow-client send işlemini global session writer kilidinde yapma: after-commit bildirim kuyruğu/bounded executor kullan. Event kaçarsa REST snapshot resync yapılır. Bearer token URL’de değil fetch header’ındadır. Token süresi dolunca yeniden bağlantı zorunludur; sonsuz yetkili bağlantı bırakma.

## B08.7 — CSV ve bildirim

### `backend/reporting-service/src/main/java/com/cryptolab/reporting/infrastructure/export/CsvCell.java`

**Sınıfı yazmadan önce:** Dış sistem veya teknik sınırı uygular. Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.

```java
package com.cryptolab.reporting.infrastructure.export;
/**
 * Sorumluluk: Dış sistem veya teknik sınırı uygular.
 * Okuma ipucu: Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.
 */
public final class CsvCell {
 private CsvCell(){}
 public static String encode(String value){
  String text=value==null?"":value;
  String trimmed=text.stripLeading();
  // Hücre tırnaklamak formül çalışmasını tek başına engellemez.
  if(!trimmed.isEmpty()&&"=+-@".indexOf(trimmed.charAt(0))>=0||text.startsWith("\t")||text.startsWith("\r"))text="'"+text;
  return "\""+text.replace("\"","\"\"")+"\"";
 }
}
```

CSV export job owner/session ile kaydolur; download route aynı owner’ı kontrol eder. Dosya yolunu kullanıcıdan alma; artifact UUID→sunucu kontrollü path. Notification read SQL `UPDATE notifications SET read_at=coalesce(read_at,now()) WHERE id=? AND owner_id=?`; idempotent, cross-user 404.

**Test:** Reporting kapalıyken aç/kapat işlemi; tekrar açınca tek projection. Aynı ledger event iki kez: balance aynı. PositionClosed sonra kâr ikinci kez artmamalı. Sequence eksik: pause/alarm. Redis down DB sonucu aynı asOf. SSE eski epoch resync. CSV `=HYPERLINK(...)` metin hücresi. Başka kullanıcının export’u 403/404.

## Kabul: hangi çıktıyı görmelisin?

**Deneme/kabul:** Reporting kapat→işlem→aç→tek projection. Redis down bakiyeyi bozmaz. SSE gap sonrası doğru resync. Gateway emir POST’unu kör tekrar etmez. Summary currentEquity örneklemesi/asOf açık. Cross-user stream/export reddi.



## Kendi cümlelerinle anlat

“Dashboard neden hafif gecikebilir ama order risk hesabı aynı gecikmiş bakiyeye dayanamaz?”

## Çalışma sonunda

`progress/BACKEND.md` içine alt görev, değiştirdiğin dosya, komut, gerçek çıktı, fixture/gerçek entegrasyon ayrımı ve sıradaki tek adımı yaz. Çalıştırmadığın test için PASS yazma.

## B08A bağlantısı — Hesabım verisinin kalıcılığı

Account, Reporting'in HTTP okuma API'sini kullanır. Geçmiş ve ledger projection burada kalıcıdır. Account için aynı Kafka event'ini ikinci bir finans toplamına eklemeyiz. Yeni owner-wide endpoint'ler, additive migration, scope manifest ve pagination kuralları [B08A](B08A-Account-Gecmis-ve-KarZarar.md) içindedir.

B08.2 kabulüne ek: açık pozisyon fee/funding ve partial realized toplamda yer almalı; kapalı işlem listesi toplam portföy hesabının yerine geçmemeli. Geç funding geçmiş trade'i düzeltir. Başka kullanıcıya ait session içeren sorgu owner filtresinden geçemez.

## DISC-01 bağlantısı

GatewayTargetResolver ve GatewayErrors B01A’da tanımlıdır; ProxyController bu resolver’ı constructor ile alır. discovery-server Gateway üzerinden route edilmez. Eureka’ya kayıt, internal endpoint’leri otomatik dışarı açmaz. Mevcut MVC proxy korunur; Spring Cloud Gateway/WebFlux starter’ı bu modüle eklenmez.
