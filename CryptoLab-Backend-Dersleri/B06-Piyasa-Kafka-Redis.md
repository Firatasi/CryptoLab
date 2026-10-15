# B06 — Dinamik piyasa servisi, Kafka ve veri önbelleği

> **DISC-01:** Eureka/local adres çözümlemesi için B01A-Eureka-ve-Servis-Kesfi.md uygulanır. Uygulama ayarları properties olarak kalır; iş kuralları değişmez.

Şartname: 3.0 · Contract: 1.0.0 · Öğretici kod referansı · Durum: BUILD_UNVERIFIED

ACC-01 eki: Account, servis sahipliği ve properties standardı için [güncel mimari eki](03-ACCOUNT-VE-GUVENLIK-EKI.md) geçerlidir.
Kafka’yı önce tek fixture olayıyla öğreniyoruz; sonra dinamik katalog ve gerçek public piyasa adapter’ına geçiyoruz. Kafka finans veritabanının yerine geçmez. Redis yalnız geçici okuma kolaylığıdır.

Bu dosyadaki kodları kendi projenize sırayla elle yazın. Burada uygulama reposu kurulmuş veya kabul testleri geçmiş değildir. Dosya yolları CryptoLab köküne göredir. Bir sınıfı yazdıktan sonra küçük deneyi yapın; tüm belgeyi tek hamlede kopyalamayın.

## Bu dersi nasıl okuyacaksın? — Port ve adaptörle provider bağımlılığını sınırlamak

**Önce kavram:** MarketSource uygulamanın veri ihtiyacını ifade eder. FixtureAdapter kontrollü örnek sağlar. PublicMarketHttp dış HTTP ayrıntısıdır, BinanceMetadataDecoder provider biçimini normalize eder. SnapshotCache yalnız okuma kolaylığıdır. Listener ve outbox teknik mesaj sınırında kalır.

**Yazma sırası:** MarketSource → fixture → sözleşme/normalizer → cache → outbox/ingress/listener → gerçek provider ve backfill bağlantısı.

**Neden bu ayrım var?** Binance JSON’u domain’e yayma. Redis düşerse bakiyeyi yeniden icat etme. Listener ACK’i DB commit’inden önce verme. Outbox ACK sonrası crash tekrar event üretebilir; consumer inbox bunu tek etkiye çevirmelidir.

**Küçük pratik:** Aynı event’i iki kez tüket ve tek DB etkisini göster. Eski sourceTime taşıyan cache verisinin TTL yenilenince fresh kabul edilmediğini dene.

Ortak kurallar: [Paket ve kod standardı](01-PAKET-VE-KOD-STANDARDI.md). Dosyaların tek tek yeni konumları: [Sınıf haritası](02-SINIF-VE-PAKET-HARITASI.md). Bu dosyada verilen yeni package/import/path birlikte kullanılmalıdır; eski ve yeni kopyaları aynı projeye ekleme.

### Bu görevde sınıflar hangi sorumlulukta?

| Sınıf | Paket | Neden burada? |
|---|---|---|
| `MarketSource` | `application.port` | Dış teknik yeteneği soyutlar. |
| `FixtureAdapter` | `infrastructure.client.fixture` | Dış sistem veya teknik sınırı uygular. |
| `OutboxPublisher` | `infrastructure.messaging` | Dış sistem veya teknik sınırı uygular. |
| `MarketIngress` | `infrastructure.messaging` | Dış sistem veya teknik sınırı uygular. |
| `MarketListener` | `infrastructure.messaging` | Dış sistem veya teknik sınırı uygular. |
| `BinanceMetadataDecoder` | `infrastructure.mapper` | Dış sistem veya teknik sınırı uygular. |
| `PublicMarketHttp` | `infrastructure.client` | Dış sistem veya teknik sınırı uygular. |
| `SnapshotCache` | `infrastructure.cache` | Dış sistem veya teknik sınırı uygular. |

## Kaynak görevlerin eksiksiz kontrol listesi

- [ ] **B06.1** Tek fixture MarkUpdated olayını produce et; consumer kapat/aç deneyiyle offset’i gör. Farklı servisler ayrı group.
- [ ] **B06.2** Outbox publish ve consumer inbox/DB commit sırasını uygula; duplicate event tek effect. Session fan-out ara crash testini kur.
- [ ] **B06.3** Dinamik instrument catalog, canonical id ve capability/reason’ları yaz; yeni varlık eklemek enum değişikliği gerektirmesin.
- [ ] **B06.4** Candle TRADE/MARK, finalized/availableAt, gap ve coverage backfill; aynı eksik range tek job.
- [ ] **B06.5** Resmi provider şemalarını uygulama gününde doğrula; REST, mark/book stream, reconnect, rate limit ve epoch dedupe adapter’larını sırayla yaz.
- [ ] **B06.6** Redis market snapshot TTL + source time; stale veriyi yeni timestamp’le canlandırma. Redis kapalı senaryoyu dene.
- [ ] **B06.7** Hot/warm/cold abonelik önceliği ve delist; UI bağlantısı kapansa açık pozisyon feed’i sürsün.
- [ ] **B06.8** Immutable dataset export job/ref/hash API’sini tamamla; A02’ye aynı formatta fixture ve gerçek küçük export ver.

## B06.1 — Bağımlılıklar ve broker

Market, Simulation ve Reporting’e Spring Kafka; Market’e JDBC/Flyway/Postgres ve Redis starter ekle. Her servisin DB’si B03 kalıbıyla ayrı kullanıcıya aittir.

```xml
<dependency><groupId>org.springframework.kafka</groupId><artifactId>spring-kafka</artifactId></dependency>
<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-redis</artifactId></dependency>
```

`infra/compose.yaml` services altına ekle. Bu tek broker/replication1 geliştirme ortamıdır. Aşağıdaki adresler hostta çalışan Java servisleri içindir; Java da container’a taşınırsa listener adreslerini iki listener ile yeniden düzenle.

### `infra/compose.yaml — streaming servisleri`

```yaml
  kafka:
    image: apache/kafka:3.9.1
    profiles: [streaming, offline-demo]
    ports: ["127.0.0.1:9092:9092"]
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_LOG_DIRS: /var/lib/kafka/data
    volumes: ["kafka-data:/var/lib/kafka/data"]
  redis:
    image: redis:7.4.2
    profiles: [streaming, offline-demo]
    ports: ["127.0.0.1:6379:6379"]
# En alttaki mevcut volumes haritasına kafka-data: ekle; ikinci volumes anahtarı açma.
```

### `Market/Simulation application.properties — Kafka ayarları`

```properties
# Group listener'da iş tüketicisi bazında sabittir; servis instance ID'si group olmaz.
spring.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer
spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer
spring.kafka.producer.acks=all
spring.kafka.producer.properties.enable.idempotence=true
spring.kafka.consumer.enable-auto-commit=false
spring.kafka.consumer.auto-offset-reset=earliest
spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer
spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer
spring.kafka.listener.ack-mode=manual_immediate
spring.data.redis.host=${REDIS_HOST:localhost}
spring.data.redis.port=${REDIS_PORT:6379}
spring.data.redis.timeout=1s
```

### `backend/market-data-service/src/main/java/com/cryptolab/marketdata/application/port/MarketSource.java`

**Sınıfı yazmadan önce:** Dış teknik yeteneği soyutlar. Fixture ve gerçek adaptör aynı sözleşmeyi uygular; service somut client bilmez.

```java
package com.cryptolab.marketdata.application.port;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
/**
 * Sorumluluk: Dış teknik yeteneği soyutlar.
 * Okuma ipucu: Fixture ve gerçek adaptör aynı sözleşmeyi uygular; service somut client bilmez.
 */
public interface MarketSource {
 // Provider JSON nesnesi domain'e sızmaz; normalize edilmiş kendi kayıtlarımız döner.
 record Instrument(String exchange,String symbol,String contractIdentity,String marketType,
  String base,String quote,String settlement,String status,String tick,String step,String minNotional){}
 record Candle(String symbol,String interval,String priceKind,Instant openTime,Instant closeTime,
  Instant availableAt,String open,String high,String low,String close,String volume,boolean finalized){}
 record Mark(String symbol,String price,Instant sourceTime,Instant receivedAt,String epoch,String sequence){}
 record Quote(String symbol,String bid,String bidQty,String ask,String askQty,Instant sourceTime,Instant receivedAt,String epoch,String sequence){}
 record Funding(String symbol,Instant settlementTime,String rate,String settlementMark,String sourceId){}
 List<Instrument> listInstruments();
 List<Candle> loadCandles(String symbol,String interval,Instant from,Instant to);
 List<Candle> loadMarkHistory(String symbol,String interval,Instant from,Instant to);
 List<Funding> loadFundingHistory(String symbol,Instant from,Instant to);
 AutoCloseable connectMarks(List<String> symbols,Consumer<Mark> sink);
 AutoCloseable connectQuotes(List<String> symbols,Consumer<Quote> sink);
}
```

### `backend/market-data-service/src/main/java/com/cryptolab/marketdata/infrastructure/client/fixture/FixtureAdapter.java`

**Sınıfı yazmadan önce:** Dış sistem veya teknik sınırı uygular. Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.

```java
package com.cryptolab.marketdata.infrastructure.client.fixture;
import com.cryptolab.marketdata.application.port.MarketSource;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
/**
 * Sorumluluk: Dış sistem veya teknik sınırı uygular.
 * Okuma ipucu: Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.
 */
public class FixtureAdapter implements MarketSource {
 private final Clock clock;
 public FixtureAdapter(Clock clock){this.clock=clock;}
 public List<Instrument> listInstruments(){return List.of(new Instrument("FIXTURE","BTCUSDT","educational-1","PERPETUAL","BTC","USDT","USDT","TRADING","0.1","0.001","5"));}
 public List<Candle> loadCandles(String s,String i,Instant f,Instant t){return List.of();}
 public List<Candle> loadMarkHistory(String s,String i,Instant f,Instant t){return List.of();}
 public List<Funding> loadFundingHistory(String s,Instant f,Instant t){return List.of();}
 public AutoCloseable connectMarks(List<String> symbols,Consumer<Mark> sink){
  // Boş history gerçek veri varmış gibi doldurulmaz. Tek kontrollü tick öğretim içindir.
  var now=clock.instant();symbols.forEach(s->sink.accept(new Mark(s,"50000",now,now,"fixture-1","1")));return ()->{};
 }
 public AutoCloseable connectQuotes(List<String> symbols,Consumer<Quote> sink){
  var now=clock.instant();symbols.forEach(s->sink.accept(new Quote(s,"50000","2","50000","2",now,now,"fixture-1","1")));return ()->{};
 }
}
```

### `backend/market-data-service/src/main/resources/db/migration/V1__market.sql`

```sql
CREATE TABLE instruments(id uuid PRIMARY KEY,exchange_id text NOT NULL,exchange_symbol text NOT NULL,
 market_type text NOT NULL,contract_identity text NOT NULL,base_asset text NOT NULL,quote_asset text NOT NULL,
 settlement_asset text,status text NOT NULL,onboard_at timestamptz,observed_at timestamptz NOT NULL,
 UNIQUE(exchange_id,exchange_symbol,market_type,contract_identity));
CREATE TABLE instrument_versions(instrument_id uuid NOT NULL REFERENCES instruments(id),version bigint NOT NULL,
 metadata jsonb NOT NULL,checksum text NOT NULL,observed_at timestamptz NOT NULL,effective_at timestamptz,
 PRIMARY KEY(instrument_id,version));
CREATE TABLE candles(instrument_id uuid NOT NULL REFERENCES instruments(id),interval text NOT NULL,price_kind text NOT NULL,
 open_time timestamptz NOT NULL,close_time timestamptz NOT NULL,available_at timestamptz NOT NULL,
 open numeric(38,18) NOT NULL,high numeric(38,18) NOT NULL,low numeric(38,18) NOT NULL,close numeric(38,18) NOT NULL,
 volume numeric(38,18),finalized boolean NOT NULL,
 PRIMARY KEY(instrument_id,interval,price_kind,open_time));
CREATE TABLE funding_rates(instrument_id uuid NOT NULL,settlement_time timestamptz NOT NULL,
 rate numeric(38,18) NOT NULL,settlement_mark numeric(38,18) NOT NULL,source_id text NOT NULL,
 PRIMARY KEY(instrument_id,settlement_time));
CREATE TABLE data_gaps(id uuid PRIMARY KEY,instrument_id uuid NOT NULL,stream text NOT NULL,
 start_at timestamptz NOT NULL,end_at timestamptz,reason text NOT NULL,resolution_status text NOT NULL);
CREATE TABLE coverage_ranges(instrument_id uuid NOT NULL,price_kind text NOT NULL,interval text NOT NULL,
 covered_from timestamptz NOT NULL,covered_to timestamptz NOT NULL,completeness text NOT NULL,
 PRIMARY KEY(instrument_id,price_kind,interval,covered_from));
CREATE TABLE backfill_jobs(id uuid PRIMARY KEY,range_hash text NOT NULL UNIQUE,request jsonb NOT NULL,
 status text NOT NULL,lease_until timestamptz);
CREATE TABLE rule_profiles(id uuid PRIMARY KEY,version bigint NOT NULL,profile jsonb NOT NULL,
 source_type text NOT NULL,checksum text NOT NULL,fetched_at timestamptz NOT NULL);
CREATE TABLE export_jobs(id uuid PRIMARY KEY,request jsonb NOT NULL,status text NOT NULL,
 artifact_id uuid,sha256 text,manifest jsonb,created_at timestamptz NOT NULL);
CREATE TABLE outbox(event_id uuid PRIMARY KEY,topic text NOT NULL,event_key text NOT NULL,
 payload jsonb NOT NULL,created_at timestamptz NOT NULL,published_at timestamptz,lease_until timestamptz,attempts int NOT NULL DEFAULT 0);
```

### `backend/market-data-service/src/main/java/com/cryptolab/marketdata/infrastructure/messaging/OutboxPublisher.java`

**Sınıfı yazmadan önce:** Dış sistem veya teknik sınırı uygular. Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.

```java
package com.cryptolab.marketdata.infrastructure.messaging;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
/**
 * Sorumluluk: Dış sistem veya teknik sınırı uygular.
 * Okuma ipucu: Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.
 */
@Component
public class OutboxPublisher {
 private record Item(UUID id,String topic,String key,String json){}
 private final JdbcTemplate db;private final KafkaTemplate<String,String> kafka;
 public OutboxPublisher(JdbcTemplate db,KafkaTemplate<String,String> kafka){this.db=db;this.kafka=kafka;}
 @Scheduled(fixedDelay=500) public void publish(){
  // Tek atomic UPDATE claim yapar; broker beklerken finans session kilidi tutulmaz.
  var batch=db.query("""
   UPDATE outbox SET lease_until=now()+interval '30 seconds',attempts=attempts+1
   WHERE event_id IN (SELECT event_id FROM outbox WHERE published_at IS NULL
    AND (lease_until IS NULL OR lease_until<now()) ORDER BY created_at LIMIT 20 FOR UPDATE SKIP LOCKED)
   RETURNING event_id,topic,event_key,payload::text
   """,(rs,n)->new Item(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4)));
  for(var item:batch){
   try{
    kafka.send(item.topic(),item.key(),item.json()).get(5,TimeUnit.SECONDS);
    // Publish başarılı, DB update öncesi crash olursa aynı eventId yeniden gelir.
    db.update("UPDATE outbox SET published_at=now(),lease_until=NULL WHERE event_id=?",item.id());
   }catch(InterruptedException e){Thread.currentThread().interrupt();return;}
   catch(Exception e){
    // Retry sonraki lease'te; exception payload/secrets olarak loglanmaz.
    // Sayaç/son hata metadatasını operasyon metriklerine bağla.
   }
  }
 }
}
```

`@EnableScheduling` konfigürasyonu ekle. Bu yayıncı Market outbox’ı için uygundur. **Simulation finans outbox’ında sıra ayrıca korunmalı:** aynı session’ın en küçük yayımlanmamış engineSequence olayı önce gider; bir session için paralel batch claim yapma. Reporting B08’de boşluk görünce pause eder. Transaction içinde üretilen eventId yayın retry’sinde değişmez.

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/infrastructure/messaging/MarketIngress.java`

**Sınıfı yazmadan önce:** Dış sistem veya teknik sınırı uygular. Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.

```java
package com.cryptolab.simulation.infrastructure.messaging;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
/**
 * Sorumluluk: Dış sistem veya teknik sınırı uygular.
 * Okuma ipucu: Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.
 */
@Service
public class MarketIngress {
 private final JdbcTemplate db;private final ObjectMapper json;
 public MarketIngress(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
 @Transactional public void accept(String payload)throws Exception{
  var event=json.readTree(payload);UUID id=UUID.fromString(event.required("eventId").asText());
  UUID instrument=UUID.fromString(event.required("payload").required("instrumentId").asText());
  // Şema validation bu parse'dan önce uygulanmalı; missing/type hatası atlanmaz.
  int added=db.update("INSERT INTO market_inputs(id,payload,received_at) VALUES (?,?::jsonb,now()) ON CONFLICT DO NOTHING",id,payload);
  if(added==0)return;
  // Input + bütün ilgili session iş kayıtları aynı TX; ortasında crash kayıp fan-out yaratmaz.
  db.update("""
   INSERT INTO pending_engine_inputs(session_id,input_id)
   SELECT DISTINCT session_id,? FROM (
    SELECT session_id FROM positions WHERE instrument_id=? AND state IN ('OPEN','LIQUIDATING')
    UNION SELECT session_id FROM orders WHERE instrument_id=? AND status IN ('NEW','EXIT_PENDING_DATA')
   ) affected ON CONFLICT DO NOTHING
   """,id,instrument,instrument);
 }
}
```

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/infrastructure/messaging/MarketListener.java`

**Sınıfı yazmadan önce:** Dış sistem veya teknik sınırı uygular. Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.

```java
package com.cryptolab.simulation.infrastructure.messaging;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
/**
 * Sorumluluk: Dış sistem veya teknik sınırı uygular.
 * Okuma ipucu: Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.
 */
@Component
public class MarketListener {
 private final MarketIngress ingress;
 public MarketListener(MarketIngress ingress){this.ingress=ingress;}
 @KafkaListener(topics={"market.mark.v2","market.quote.v2","market.funding.settled.v2"},groupId="simulation-market-v2")
 public void consume(String payload,Acknowledgment ack)throws Exception{
  ingress.accept(payload); // Proxy döndüğünde DB commit başarılıdır.
  ack.acknowledge(); // Offset DB'den önce giderse crash'te veri kaybedersin.
 }
}
```

Varsayılan error handler’ın belli retry sonrası mesajı atlamasına izin verme. Finans consumer için `CommonContainerStoppingErrorHandler` ile container’ı durdur, alarm üret ve karantinadaki kaydı düzeltince kontrollü yeniden başlat. Partition bazlı pause daha ileri adımdır; sessiz DLT+devam değildir.

```java
// Kafka listener factory bean'ini özelleştirirken:
factory.setCommonErrorHandler(new org.springframework.kafka.listener.CommonContainerStoppingErrorHandler());
```

## B06.3–B06.5 — Dinamik katalog ve gerçek sağlayıcı

Provider endpoint alanları uygulama gününde resmi kaynaktan kontrol edilir. Bu paket hazırlanırken resmi mark stream sayfasında market/public ayrımı görüldü; eski `wss://.../ws` yolunu varsayma. Referans: https://developers.binance.com/docs/derivatives/usds-margined-futures/market-data/rest-api/Exchange-Information ve https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams/Mark-Price-Stream . Gerçek ağ testi bu teslimde yapılmadı.

`BinanceAdapter` için önce REST metadata decoder’ını yaz. Filtreyi `filterType` ile bul; dizin numarasına veya pricePrecision’a güvenme. Kontrat identity, venue+market+onboardTime gibi sağlayıcı hayat döngüsü bilgisini içerir. Symbol yeniden listelenirse eski candle’la birleştirme. UUID DB’de atanır; sembol UUID değildir.

### `backend/market-data-service/src/main/java/com/cryptolab/marketdata/infrastructure/mapper/BinanceMetadataDecoder.java`

**Sınıfı yazmadan önce:** Dış sistem veya teknik sınırı uygular. Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.

```java
package com.cryptolab.marketdata.infrastructure.mapper;
import com.cryptolab.marketdata.application.port.MarketSource.Instrument;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
/**
 * Sorumluluk: Dış sistem veya teknik sınırı uygular.
 * Okuma ipucu: Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.
 */
public final class BinanceMetadataDecoder {
 private BinanceMetadataDecoder(){}
 private static JsonNode filter(JsonNode symbol,String type){
  for(var f:symbol.required("filters"))if(type.equals(f.required("filterType").asText()))return f;
  throw new IllegalArgumentException("MISSING_FILTER:"+type);
 }
 public static List<Instrument> decode(JsonNode response){
  List<Instrument> out=new ArrayList<>();
  for(var s:response.required("symbols")){
   String symbol=s.required("symbol").asText();
   String identity=s.required("contractType").asText()+":"+s.required("onboardDate").asText();
   // Katalogdan unsupported kontratı silmeyiz; paperTradable capability daha sonra false olabilir.
   out.add(new Instrument("BINANCE",symbol,identity,s.required("contractType").asText(),
    s.required("baseAsset").asText(),s.required("quoteAsset").asText(),s.required("marginAsset").asText(),
    s.required("status").asText(),filter(s,"PRICE_FILTER").required("tickSize").asText(),
    filter(s,"LOT_SIZE").required("stepSize").asText(),filter(s,"MIN_NOTIONAL").required("notional").asText()));
  }
  return List.copyOf(out);
 }
}
```

### `backend/market-data-service/src/main/java/com/cryptolab/marketdata/infrastructure/client/PublicMarketHttp.java`

**Sınıfı yazmadan önce:** Dış sistem veya teknik sınırı uygular. Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.

```java
package com.cryptolab.marketdata.infrastructure.client;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
/**
 * Sorumluluk: Dış sistem veya teknik sınırı uygular.
 * Okuma ipucu: Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.
 */
public final class PublicMarketHttp {
 public record RetryLater(int status,String retryAfter){}
 public static final class ProviderException extends RuntimeException {
  public final int status;public final String retryAfter;
  ProviderException(int status,String retryAfter){super("PROVIDER_HTTP_"+status);this.status=status;this.retryAfter=retryAfter;}
 }
 private final URI base;private final ObjectMapper mapper;
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
 public PublicMarketHttp(URI base,ObjectMapper mapper){
  if(!"https".equals(base.getScheme()))throw new IllegalArgumentException("HTTPS_REQUIRED");
  this.base=base;this.mapper=mapper;
 }
 public JsonNode get(String relative)throws Exception{
  URI target=base.resolve(relative);
  if(!base.getHost().equals(target.getHost()))throw new IllegalArgumentException("PROVIDER_HOST_CHANGED");
  var request=HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(5)).GET().build();
  var result=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
  try(var in=result.body()){
   if(result.statusCode()!=200)throw new ProviderException(result.statusCode(),result.headers().firstValue("Retry-After").orElse(""));
   byte[] bytes=in.readNBytes(4*1024*1024+1);
   if(bytes.length>4*1024*1024)throw new IllegalStateException("PAYLOAD_TOO_LARGE");
   return mapper.readTree(bytes);
  }
 }
}
```

HTTP sınıfı transport’tur; 429’da hızlı döngü kurmaz. Job scheduler Retry-After’ı parse eder, max deneme ve jitter ile tekrar planlar; 418/ban’da durur. REST sayfalamasında `[from,to)` ve son candle openTime ile ilerle; aynı son sayfayı sonsuza kadar indirme. CandleClosed event yalnız finalized=true ve availableAt≤asOf olduğunda üretilir.

WebSocket adapter’da JDK `WebSocket.Listener` veya seçilen client kullanılır: fragmented text buffer boyutu sınırlandırılır; `last=true` olunca JSON decode; her reconnect yeni connectionEpoch; mark sourceTime=E, receivedAt=Clock; quote source updateId korunur. Subscribe/unsubscribe yönetimi hot>warm>cold önceliklidir. Ping/pong ve bağlantı yaşam süresi resmi şemadan uygulanır. `onError` gap açar, eski snapshot’ı yeniden taze olarak yaymaz. Bu bağlantı kodu gerçek provider smoke testiyle tamamlanır; fixture PASS gerçek ağ PASS değildir.

## B06.6 — Redis snapshot

### `backend/market-data-service/src/main/java/com/cryptolab/marketdata/infrastructure/cache/SnapshotCache.java`

**Sınıfı yazmadan önce:** Dış sistem veya teknik sınırı uygular. Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.

```java
package com.cryptolab.marketdata.infrastructure.cache;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.DataAccessException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
/**
 * Sorumluluk: Dış sistem veya teknik sınırı uygular.
 * Okuma ipucu: Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.
 */
public class SnapshotCache {
 private final StringRedisTemplate redis;
 public SnapshotCache(StringRedisTemplate redis){this.redis=redis;}
 public void put(UUID instrument,String kind,String originalJson){
  Duration ttl=kind.equals("mark")?Duration.ofSeconds(5):Duration.ofSeconds(2);
  try{redis.opsForValue().set("market:"+kind+":"+instrument,originalJson,ttl);}
  catch(DataAccessException e){/* Cache hata metriği; finans state'i bu sınıfta yok. */}
 }
 public Optional<String> get(UUID instrument,String kind){
  try{return Optional.ofNullable(redis.opsForValue().get("market:"+kind+":"+instrument));}
  catch(DataAccessException e){return Optional.empty();}
 }
 // Cache hit olsa bile sourceTime ve receivedAt gate'i tekrar uygulanır.
}
```

## B06.7–B06.8 — Abonelik ve immutable export

Tarayıcı disconnect bir pozisyonun feed ihtiyacını bitirmez. Abonelik referansları `(instrument,reason,resourceId)` tutulur: POSITION/EXIT hot, ANALYSIS warm, UI cold. Delist’te entry kapatılır; pozisyon arşivi silinmez.

Export job isteğini kalıcı kaydet → 202 jobId. Worker repeatable-read snapshot ile `finalized=true AND available_at<=asOfCutoff` satırlarını sabit sırayla JSONL’ye yazar. SHA-256, range, priceKind, missingRanges ve availableAtPolicy içeren manifest oluştur. Temp dosya hash doğrulanınca atomik rename; job READY ancak artifact kalıcı olduktan sonra. Rastgele dış URL indirme endpoint’i açma. AI kendi DB’sinden Market tablolarını okuyamaz; internal export API kullanır.

**Deney:** consumer kapat, fixture topic’ine 5 olay; consumer açınca 5 kayıt. Aynı event’i tekrar: tek market_input. Fan-out sırasında crash: bütün session iş kayıtları ya var ya yok. Candle henüz kapanmadı: feature/export’a girmez. Redis kapat: durum UNAVAILABLE/DB fallback; eski veriye yeni zaman basılmaz.

**Tamamlama kaydı:** Bu dosyada outbox/ingress ve transport çekirdeği tam kodla verilmiştir; provider stream, normalize event schema doğrulaması, backfill/export worker’ları yukarıdaki adımlarla yazılır ve ayrı test edilir. Bunlar tamamlanmadan B06 DONE değildir.

## Kabul: hangi çıktıyı görmelisin?

**Deneme/kabul:** Consumer restart/repeat, same-symbol farklı venue, yeni eligible instrument, bozuk payload, kapalı olmayan mum, stale/gap ve 429 testleri. Gerçek ağ smoke testi ayrı tarihli sonuç; internet engeli olduğunda fixture PASS, gerçek entegrasyon BLOCKED yazılır. Kafka retention ledger arşivi yerine geçmez.



## Kendi cümlelerinle anlat

“Market mark ve quote ayrı topic’lerdeyse olayların tek bir global sırası var mı?”

## Çalışma sonunda

`progress/BACKEND.md` içine alt görev, değiştirdiğin dosya, komut, gerçek çıktı, fixture/gerçek entegrasyon ayrımı ve sıradaki tek adımı yaz. Çalıştırmadığın test için PASS yazma.
