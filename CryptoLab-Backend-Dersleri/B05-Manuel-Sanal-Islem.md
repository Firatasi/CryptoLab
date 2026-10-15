# B05 — Önizleme, onay ve manuel sanal işlem

> **DISC-01:** Eureka/local adres çözümlemesi için B01A-Eureka-ve-Servis-Kesfi.md uygulanır. Uygulama ayarları properties olarak kalır; iş kuralları değişmez.

Şartname: 3.0 · Contract: 1.0.0 · Öğretici kod referansı · Durum: BUILD_UNVERIFIED

ACC-01 eki: Account, servis sahipliği ve properties standardı için [güncel mimari eki](03-ACCOUNT-VE-GUVENLIK-EKI.md) geçerlidir.
Bu görev en büyük dikey akıştır: session → preview → kullanıcı onayı → kalıcı command → fill → close → ledger. Aşağıdaki domain ve application parçaları finans kurallarını görünür kılar. Her alt bölüm ayrı çalışma oturumudur; port tanımı adaptörün tamamlandığı anlamına gelmez.

Bu dosyadaki kodları kendi projenize sırayla elle yazın. Burada uygulama reposu kurulmuş veya kabul testleri geçmiş değildir. Dosya yolları CryptoLab köküne göredir. Bir sınıfı yazdıktan sonra küçük deneyi yapın; tüm belgeyi tek hamlede kopyalamayın.

## Bu dersi nasıl okuyacaksın? — Bir HTTP isteğini bütün katmanlarda izlemek

**Önce kavram:** OrderController → OrderMapper → SubmitOrderCommand → SubmitOrder → OrderSubmissionRepository → JdbcOrderSubmissionRepository → AcceptedOrderResponse akışını takip et. ExecutionPlan immutable onayın modelidir; mapper miktar hesaplamaz, SubmitOrder onaylanan planı DB’den okur.

**Yazma sırası:** ExecutionPlan/gate → command/response/exception → repository portu ve JDBC adaptörü → SubmitOrder → OrderMapper → OrderController/ApiErrors → preview/worker bağlantıları.

**Neden bu ayrım var?** Request validation ile plan TTL kontrolü farklıdır. DTO geçerli olsa bile başka kullanıcının planı reddedilir. Repository’ye ayırmak session lock sırasını veya tek transaction’ı değiştirmez. 202 ile FILLED aynı sonuç değildir.

**Küçük pratik:** planHash değiştir: 409 bekle. Aynı key+body tekrarında aynı command dönmeli. Yeni mapper JSON alan adlarını değiştirmemeli; key ve owner body’ye taşınmamalı.

Ortak kurallar: [Paket ve kod standardı](01-PAKET-VE-KOD-STANDARDI.md). Dosyaların tek tek yeni konumları: [Sınıf haritası](02-SINIF-VE-PAKET-HARITASI.md). Bu dosyada verilen yeni package/import/path birlikte kullanılmalıdır; eski ve yeni kopyaları aynı projeye ekleme.

### Bu görevde sınıflar hangi sorumlulukta?

| Sınıf | Paket | Neden burada? |
|---|---|---|
| `ExecutionPlan` | `domain.model` | İş verisini ve kendi değişmez kurallarını temsil eder. |
| `PlanHash` | `application.service` | Bir kullanım senaryosunun sırasını yönetir. |
| `ExecutionGate` | `domain.service` | Girdilerden iş kararı/hesap üretir. |
| `SubmitOrderRequest` | `api.dto.request` | İstemciden kabul edilen alanları tarif eder. |
| `SubmitOrder` | `application.service` | Bir kullanım senaryosunun sırasını yönetir. |
| `OrderController` | `api.controller` | HTTP isteğini uygulama akışına bağlar. |
| `FillAccounting` | `domain.service` | Girdilerden iş kararı/hesap üretir. |
| `ApiErrors` | `api.exception` | Hataları güvenli HTTP cevabına dönüştürür. |
| `ManualPreviewRequest` | `api.dto.request` | İstemciden kabul edilen alanları tarif eder. |
| `PreviewFactory` | `domain.service` | Girdilerden iş kararı/hesap üretir. |
| `DecimalJsonConfig` | `infrastructure.config` | Teknik nesneleri ve Spring bağlantılarını kurar. |
| `LimitDecision` | `domain.service` | Girdilerden iş kararı/hesap üretir. |
| `SubmitOrderCommand` | `application.command` | Kullanım senaryosunun girdisini taşır. |
| `AcceptedOrderResponse` | `api.dto.response` | Dış API alanlarını sabit tutar. |
| `OrderMapper` | `api.mapper` | HTTP modeli ile uygulama modelini dönüştürür. |
| `OrderSubmissionRepository` | `application.repository` | Kalıcılık davranışını arayüz olarak tanımlar. |
| `JdbcOrderSubmissionRepository` | `infrastructure.repository` | Portun SQL veya okuma modeli kalıcılığını uygular. |
| `BusinessException` | `domain.exception` | İş kuralı ihlalini HTTP’den bağımsız ifade eder. |
| `ApiError` | `api.dto.response` | Dış API alanlarını sabit tutar. |

## Kaynak görevlerin eksiksiz kontrol listesi

- [ ] **B05.1** README 7.2 request/response şemalarını tamamla; submit yalnız planId/revision/planHash. API’de ikinci alternatif body açma.
- [ ] **B05.2** ManualPreview’dan q/margin/fee/risk üret; nullable manuel stop/hedef için profil gereksinimini uygula.
- [ ] **B05.3** Immutable plan revision/hash/TTL/generation kaydını yaz; canonical byte fixture ile hash kararlı olsun.
- [ ] **B05.4** Session lock altında ownership+expiry+idempotency+unique plan execution; approval audit ve command aynı transaction, commit sonrası 202.
- [ ] **B05.5** Worker snapshot’ı yeniden doğrulasın; market full-fill-or-reject ve LIMIT reserve/NEW/cancel akışını yaz.
- [ ] **B05.6** Tam manuel close ve generation invalidation; fill, journal, position, outbox aynı transaction. Çıkış talebini sonucu bilinmeden CLOSED yapma.
- [ ] **B05.7** GET command/order/position/wallet/ledger ile sonucu sorgula; timeout+aynı key testini çalıştır.

## B05.1 — Dosya ve bağımlılık sırası

B03 transaction/ledger ve B04 güvenlik hazır olmadan public emir endpoint’i açma. `ExecutionPlan` immutable onay belgesidir. `Command` işlenmeyi bekleyen kalıcı talep; `Order` emir yaşam döngüsü; `Fill` gerçekleşme; `Position` açık risk durumudur. Bunları tek entity’ye toplamak 202 ile fill’i karıştırır.

| Sınıf | Neden ayrı? |
|---|---|
| ExecutionPlan | Kullanıcının onayladığı sayıları dondurur |
| PlanHash | Aynı planın byte kimliğini verir |
| SubmitOrder | Kilit, idempotency ve command kabulünü yönetir |
| ExecutionGate | Güncel veri/fiyat/maliyet koşulunu tekrar kontrol eder |
| FillAccounting | Açılış/kapanışın dengeli posting’lerini üretir |
| OrderController | HTTP’yi use case’e bağlar; para hesabı yapmaz |

B05’te source=Fixture; B06’da gerçek adapter takılır. Fixture’ın zamanı Clock ile üretilirse sadece fixture etiketiyle kullanılmalı; gerçek eski quote’a yeni timestamp basılmaz.

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/domain/model/ExecutionPlan.java`

**Sınıfı yazmadan önce:** İş verisini ve kendi değişmez kurallarını temsil eder. Modelin HTTP veya veritabanı biçimine bağımlı olup olmadığını kontrol et.

```java
package com.cryptolab.simulation.domain.model;
import com.cryptolab.finance.Decimal;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
/**
 * Sorumluluk: İş verisini ve kendi değişmez kurallarını temsil eder.
 * Okuma ipucu: Modelin HTTP veya veritabanı biçimine bağımlı olup olmadığını kontrol et.
 */
public record ExecutionPlan(UUID planId,int revision,String planHash,UUID sessionId,
 UUID instrumentId,long instrumentVersion,String source,UUID proposalId,long positionGeneration,
 String side,String orderType,BigDecimal quantity,BigDecimal referenceEntry,BigDecimal limitPrice,
 BigDecimal marginRequired,BigDecimal openFeeEstimate,BigDecimal notional,BigDecimal leverage,
 BigDecimal stopLoss,BigDecimal takeProfit,int horizonSeconds,String expiryPolicy,
 BigDecimal priceLowerBound,BigDecimal priceUpperBound,BigDecimal maxOpenFee,
 BigDecimal maxSlippageBps,UUID ruleProfileId,long riskPolicyVersion,Instant createdAt,
 Instant validUntil,BigDecimal estimatedLiquidationPrice,List<String> warnings) {
 public ExecutionPlan { warnings=List.copyOf(warnings); }
 // Bu domain record BigDecimal taşır. HTTP mapper Decimal.wire ile string üretir.
 // Controller Jackson'ın varsayılan numeric BigDecimal çıktısını doğrudan kullanmamalı.
}
```

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/application/service/PlanHash.java`

**Sınıfı yazmadan önce:** Bir kullanım senaryosunun sırasını yönetir. Constructor bağımlılıklarını ve transaction sınırını önce oku; hesap ve SQL sorumluluklarını ayır.

```java
package com.cryptolab.simulation.application.service;
import com.cryptolab.simulation.domain.model.ExecutionPlan;
import com.cryptolab.finance.Decimal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatterBuilder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
/**
 * Sorumluluk: Bir kullanım senaryosunun sırasını yönetir.
 * Okuma ipucu: Constructor bağımlılıklarını ve transaction sınırını önce oku; hesap ve SQL sorumluluklarını ayır.
 */
public final class PlanHash {
 private static final ObjectMapper JSON=new ObjectMapper();
 private PlanHash(){}
 public static byte[] canonical(ExecutionPlan p){
  // LinkedHashMap: alan sırası sözleşmedir. Hash ve warnings kasıtlı dışarıda.
  Map<String,Object> m=new LinkedHashMap<>();
  Object[][] fields={{"planId",p.planId()},{"revision",p.revision()},{"sessionId",p.sessionId()},
   {"instrumentId",p.instrumentId()},{"instrumentVersion",p.instrumentVersion()},{"source",p.source()},
   {"proposalId",p.proposalId()},{"positionGeneration",p.positionGeneration()},{"side",p.side()},
   {"orderType",p.orderType()},{"quantity",p.quantity()},{"referenceEntry",p.referenceEntry()},
   {"limitPrice",p.limitPrice()},{"marginRequired",p.marginRequired()},{"openFeeEstimate",p.openFeeEstimate()},
   {"notional",p.notional()},{"leverage",p.leverage()},{"stopLoss",p.stopLoss()},{"takeProfit",p.takeProfit()},
   {"horizonSeconds",p.horizonSeconds()},{"expiryPolicy",p.expiryPolicy()},
   {"priceLowerBound",p.priceLowerBound()},{"priceUpperBound",p.priceUpperBound()},
   {"maxOpenFee",p.maxOpenFee()},{"maxSlippageBps",p.maxSlippageBps()},{"ruleProfileId",p.ruleProfileId()},
   {"riskPolicyVersion",p.riskPolicyVersion()},{"createdAt",p.createdAt()},{"validUntil",p.validUntil()},
   {"estimatedLiquidationPrice",p.estimatedLiquidationPrice()}};
  for(Object[] field:fields){
   Object v=field[1];
   if(v instanceof BigDecimal d)v=Decimal.wire(d);
   else if(v instanceof Instant t)v=new DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(t);
   else if(v instanceof java.util.UUID u)v=u.toString();
   m.put((String)field[0],v); // Null alanlar da hash girdisinde kalır.
  }
  try{return JSON.writeValueAsBytes(m);}catch(Exception e){throw new IllegalStateException(e);}
 }
 public static String hash(ExecutionPlan p){
  try{return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(p)));}
  catch(Exception e){throw new IllegalStateException(e);}
 }
}
```

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/domain/service/ExecutionGate.java`

**Sınıfı yazmadan önce:** Girdilerden iş kararı/hesap üretir. Aynı girdinin aynı sonucu üretmesi beklenir; DB/ağ yan etkisi ekleme.

```java
package com.cryptolab.simulation.domain.service;
import com.cryptolab.simulation.domain.model.ExecutionPlan;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.Duration;
/**
 * Sorumluluk: Girdilerden iş kararı/hesap üretir.
 * Okuma ipucu: Aynı girdinin aynı sonucu üretmesi beklenir; DB/ağ yan etkisi ekleme.
 */
public final class ExecutionGate {
 private ExecutionGate(){}
 public static void fresh(Instant source,Instant received,Instant now,long maxAgeMs,long skewMs,String code){
  if(source.isAfter(now.plusMillis(skewMs))||received.isAfter(now.plusMillis(skewMs))
   ||Duration.between(source,now).toMillis()>maxAgeMs||Duration.between(received,now).toMillis()>maxAgeMs)
   throw new IllegalStateException(code);
 }
 public static void bracket(String side,BigDecimal entry,BigDecimal stop,BigDecimal target,boolean requireStop,boolean requireTarget){
  if((requireStop&&stop==null)||(requireTarget&&target==null))throw new IllegalArgumentException("BRACKET_REQUIRED");
  boolean longSide="LONG".equals(side);
  if(!longSide&&!"SHORT".equals(side))throw new IllegalArgumentException("INVALID_SIDE");
  if(stop!=null&&(stop.signum()<=0||(longSide?stop.compareTo(entry)>=0:stop.compareTo(entry)<=0)))throw new IllegalArgumentException("INVALID_STOP");
  if(target!=null&&(target.signum()<=0||(longSide?target.compareTo(entry)<=0:target.compareTo(entry)>=0)))throw new IllegalArgumentException("INVALID_TARGET");
 }
 public static void approved(ExecutionPlan p,Instant now,long generation,BigDecimal fill,BigDecimal fee){
  if(!now.isBefore(p.validUntil()))throw new IllegalStateException("PLAN_EXPIRED");
  if(generation!=p.positionGeneration())throw new IllegalStateException("PLAN_REVOKED");
  if(fill.compareTo(p.priceLowerBound())<0||fill.compareTo(p.priceUpperBound())>0||fee.compareTo(p.maxOpenFee())>0)
   throw new IllegalStateException("REVALIDATION_REQUIRED");
  bracket(p.side(),fill,p.stopLoss(),p.takeProfit(),"AI_PROPOSAL".equals(p.source()),"AI_PROPOSAL".equals(p.source()));
 }
}
```

### `backend/simulation-service/src/main/resources/db/migration/V2__orders.sql`

```sql
CREATE TABLE instrument_generations(session_id uuid NOT NULL REFERENCES simulation_sessions(id),
 instrument_id uuid NOT NULL,generation bigint NOT NULL DEFAULT 1,PRIMARY KEY(session_id,instrument_id));
CREATE TABLE execution_plans(plan_id uuid NOT NULL,revision integer NOT NULL CHECK(revision>0),
 session_id uuid NOT NULL REFERENCES simulation_sessions(id),instrument_id uuid NOT NULL,
 canonical_json jsonb NOT NULL,hash text NOT NULL,position_generation bigint NOT NULL,
 valid_until timestamptz NOT NULL,state text NOT NULL,PRIMARY KEY(plan_id,revision));
CREATE TABLE commands(id uuid PRIMARY KEY,session_id uuid NOT NULL REFERENCES simulation_sessions(id),
 type text NOT NULL,payload jsonb NOT NULL,status text NOT NULL,reason text,created_at timestamptz NOT NULL,updated_at timestamptz NOT NULL);
CREATE TABLE approvals(id uuid PRIMARY KEY,owner_id uuid NOT NULL,plan_id uuid NOT NULL,
 revision integer NOT NULL,hash text NOT NULL,consumed_command_id uuid NOT NULL REFERENCES commands(id),
 UNIQUE(plan_id,revision),FOREIGN KEY(plan_id,revision) REFERENCES execution_plans(plan_id,revision));
CREATE TABLE positions(id uuid PRIMARY KEY,session_id uuid NOT NULL REFERENCES simulation_sessions(id),instrument_id uuid NOT NULL,
 side text NOT NULL,q numeric(38,18) NOT NULL,entry_price numeric(38,18) NOT NULL,
 original_margin numeric(38,18) NOT NULL,initial_margin_remaining numeric(38,18) NOT NULL,
 state text NOT NULL,generation bigint NOT NULL,version bigint NOT NULL DEFAULT 1,
 stop_loss numeric(38,18),take_profit numeric(38,18),deadline_at timestamptz NOT NULL,
 expiry_policy text NOT NULL,horizon_seconds integer NOT NULL,
 close_requested boolean NOT NULL DEFAULT false,opened_at timestamptz NOT NULL,closed_at timestamptz);
CREATE UNIQUE INDEX one_open_position ON positions(session_id,instrument_id) WHERE state IN ('OPEN','LIQUIDATING');
CREATE TABLE orders(id uuid PRIMARY KEY,session_id uuid NOT NULL REFERENCES simulation_sessions(id),instrument_id uuid NOT NULL,
 side text NOT NULL,type text NOT NULL,q numeric(38,18) NOT NULL CHECK(q>0),status text NOT NULL,
 reserved_amount numeric(38,18) NOT NULL DEFAULT 0,position_id uuid REFERENCES positions(id),reduce_only boolean NOT NULL,
 limit_price numeric(38,18),reason text,version bigint NOT NULL DEFAULT 1,
 command_id uuid NOT NULL UNIQUE REFERENCES commands(id),created_at timestamptz NOT NULL,updated_at timestamptz NOT NULL);
CREATE TABLE fills(id uuid PRIMARY KEY,order_id uuid NOT NULL UNIQUE REFERENCES orders(id),q numeric(38,18) NOT NULL,
 price numeric(38,18) NOT NULL,fee numeric(38,18) NOT NULL,quote_id uuid NOT NULL,
 execution_model_version text NOT NULL,engine_sequence bigint NOT NULL,time timestamptz NOT NULL);
```

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/api/dto/request/SubmitOrderRequest.java`

**Sınıfı yazmadan önce:** İstemciden kabul edilen alanları tarif eder. Annotation biçimi kontrol eder; owner ve finans iş kuralı kontrolü sonraki katmandadır.

```java
package com.cryptolab.simulation.api.dto.request;
import java.util.UUID;
import jakarta.validation.constraints.*;
/**
 * Sorumluluk: İstemciden kabul edilen alanları tarif eder.
 * Okuma ipucu: Annotation biçimi kontrol eder; owner ve finans iş kuralı kontrolü sonraki katmandadır.
 */
public record SubmitOrderRequest(@NotNull UUID planId,@Min(1)int revision,
 @NotNull @Pattern(regexp="^sha256:[0-9a-f]{64}$")String planHash) {
 // quantity, direction, fee tekrar alınmaz; onaylanan plan sunucudan okunur.
}
```

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/application/service/SubmitOrder.java`

**Sınıfı yazmadan önce:** Bir kullanım senaryosunun sırasını yönetir. Constructor bağımlılıklarını ve transaction sınırını önce oku; hesap ve SQL sorumluluklarını ayır.

```java
package com.cryptolab.simulation.application.service;
import com.cryptolab.simulation.domain.exception.BusinessException;
import com.cryptolab.simulation.application.command.SubmitOrderCommand;
import com.cryptolab.simulation.application.repository.OrderSubmissionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import java.time.Clock;
import java.time.Instant;
/**
 * Sorumluluk: Bir kullanım senaryosunun sırasını yönetir.
 * Okuma ipucu: Constructor bağımlılıklarını ve transaction sınırını önce oku; hesap ve SQL sorumluluklarını ayır.
 */
@Service
public class SubmitOrder {
    public record Accepted(UUID commandId, String status, String statusUrl, Instant acceptedAt) {}
    private final OrderSubmissionRepository orders;
    private final Clock clock;
    public SubmitOrder(OrderSubmissionRepository orders, Clock clock) {
        this.orders = orders;
        this.clock = clock;
    }
    @Transactional
    public Accepted execute(UUID owner, String key, SubmitOrderCommand request) {
        if (key == null || key.isBlank() || key.length() > 128) throw new IllegalArgumentException("INVALID_KEY");
        // Bu ilk okuma henüz kilit değildir. Yetki kontrolü kilit sorgusunda tekrar yapılır.
        UUID session = orders.findSession(request.planId(), request.revision())
                .orElseThrow(() -> new BusinessException(BusinessException.Code.NOT_FOUND));
        if (!orders.lockOwnedSession(session, owner)) throw new BusinessException(BusinessException.Code.NOT_FOUND);
        String normalized = request.planId() + ":" + request.revision() + ":" + request.planHash();
        String route = "POST /api/v1/sim/orders";
        var previous = orders.previous(owner, route, session, key);
        if (previous.isPresent()) {
            if (!previous.get().requestHash().equals(normalized)) throw new BusinessException(BusinessException.Code.IDEMPOTENCY_CONFLICT);
            return accepted(previous.get().commandId());
        }
        var plan = orders.lockPlan(request.planId(), request.revision());
        if (!request.planHash().equals(plan.hash())) throw new BusinessException(BusinessException.Code.REVALIDATION_REQUIRED);
        if (!"PREVIEWED".equals(plan.state())) throw new BusinessException(BusinessException.Code.PLAN_REVOKED);
        if (!clock.instant().isBefore(plan.validUntil())) throw new BusinessException(BusinessException.Code.PLAN_EXPIRED);
        if (orders.generation(session, plan.instrumentId()) != plan.generation()) throw new BusinessException(BusinessException.Code.PLAN_REVOKED);
        if (!"RUNNING".equals(orders.sessionStatus(session))) throw new BusinessException(BusinessException.Code.SESSION_HALTED);
        // Kalan entegrasyon: güncel yerel snapshot/risk preflight burada bağlanır.
        // Bu bölüm tamamlanmadan tam emir kabul hattı DONE değildir; HTTP/AI çağrısı lock içinde yapılmaz.
        UUID command = UUID.randomUUID();
        orders.accept(command, session, owner, request.planId(), request.revision(), request.planHash(),
                route, key, normalized, clock.instant());
        return accepted(command);
    }
    private Accepted accepted(UUID command) {
        return new Accepted(command, "ACCEPTED", "/api/v1/sim/commands/" + command, orders.acceptedAt(command));
    }
}
```

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/api/controller/OrderController.java`

**Sınıfı yazmadan önce:** HTTP isteğini uygulama akışına bağlar. DTO doğrulamasını, principal kaynağını ve HTTP cevabını takip et; SQL burada bulunmaz.

```java
package com.cryptolab.simulation.api.controller;
import com.cryptolab.simulation.api.dto.request.SubmitOrderRequest;
import com.cryptolab.simulation.application.service.SubmitOrder;
import com.cryptolab.simulation.api.dto.response.AcceptedOrderResponse;
import com.cryptolab.simulation.api.mapper.OrderMapper;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.ResponseEntity;
import java.util.UUID;
/**
 * Sorumluluk: HTTP isteğini uygulama akışına bağlar.
 * Okuma ipucu: DTO doğrulamasını, principal kaynağını ve HTTP cevabını takip et; SQL burada bulunmaz.
 */
@RestController
@RequestMapping("/api/v1/sim")
public class OrderController {
    private final SubmitOrder submit;
    private final OrderMapper mapper;
    public OrderController(SubmitOrder submit, OrderMapper mapper) {
        this.submit = submit;
        this.mapper = mapper;
    }
    @PostMapping("/orders")
    public ResponseEntity<AcceptedOrderResponse> submit(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody SubmitOrderRequest body) {
        // Kimlik istemcinin body/header iddiasından değil, doğrulanmış JWT'den gelir.
        var result = submit.execute(UUID.fromString(jwt.getSubject()), key, mapper.toCommand(body));
        // Service proxy'si dönünce commit bitmiştir. Controller SQL ve PnL hesabı yapmaz.
        return ResponseEntity.accepted().body(mapper.toResponse(result));
    }
}
```

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/domain/service/FillAccounting.java`

**Sınıfı yazmadan önce:** Girdilerden iş kararı/hesap üretir. Aynı girdinin aynı sonucu üretmesi beklenir; DB/ağ yan etkisi ekleme.

```java
package com.cryptolab.simulation.domain.service;
import com.cryptolab.simulation.domain.model.Journal;
import com.cryptolab.finance.Decimal;
import com.cryptolab.finance.Finance;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
/**
 * Sorumluluk: Girdilerden iş kararı/hesap üretir.
 * Okuma ipucu: Aynı girdinin aynı sonucu üretmesi beklenir; DB/ağ yan etkisi ekleme.
 */
public final class FillAccounting {
 private FillAccounting(){}
 public static List<Journal.Posting> opening(UUID available,UUID isolated,UUID fees,Finance.Opening open){
  BigDecimal margin=Decimal.cash(open.margin()),fee=Decimal.cash(open.fee());
  return List.of(new Journal.Posting(available,margin.add(fee).negate()),new Journal.Posting(isolated,margin),new Journal.Posting(fees,fee));
 }
 public static List<Journal.Posting> closing(UUID available,UUID isolated,UUID pnlPool,UUID fees,UUID insurance,Finance.Closing close){
  if(close.forceFullClose())throw new IllegalStateException("RECOMPUTE_FULL_CLOSE");
  BigDecimal released=Decimal.cash(close.released()),realized=Decimal.cash(close.realized()),fee=Decimal.cash(close.fee());
  BigDecimal raw=released.add(realized).subtract(fee),support=raw.negate().max(BigDecimal.ZERO);
  // Ham kaybı saklayıp explicit sigorta karşı hesabı açıyoruz; farkı yok etmiyoruz.
  return List.of(new Journal.Posting(isolated,released.negate()),new Journal.Posting(available,raw.add(support)),
   new Journal.Posting(pnlPool,realized.negate()),new Journal.Posting(fees,fee),new Journal.Posting(insurance,support.negate()));
 }
 public static List<Journal.Posting> transfer(UUID from,UUID to,BigDecimal value){
  BigDecimal amount=Decimal.cash(value);Decimal.nonNegative(amount);
  return List.of(new Journal.Posting(from,amount.negate()),new Journal.Posting(to,amount));
 }
}
```

## Kod okuma atölyesi — Submit isteğini satır satır takip et

1. **OrderController constructor:** SubmitOrder ve OrderMapper dışarıdan verilir. Controller içinde `new SubmitOrder(...)` yazmayız; aksi halde Spring transaction proxy'sini atlayabiliriz.
2. **@PostMapping:** aynı `/api/v1/sim/orders` yolu korunur. `@Valid` request annotation'larını işletir. Ancak JSON'da fazladan alan reddi için strict serializer/schema ayarı ayrıca korunmalıdır.
3. **@AuthenticationPrincipal:** owner istemcinin istediği UUID değildir; doğrulanmış JWT subject'idir. Mapper'a owner türettirmeyiz.
4. **OrderMapper.toCommand:** planId, revision ve planHash aynen geçirilir. Burada quantity hesaplamak veya fiyat okumak yanlış katman olur.
5. **SubmitOrder constructor:** somut JdbcOrderSubmissionRepository yerine arayüz alır. Clock da enjekte edilir; testte zamanı sabitleyebiliriz.
6. **@Transactional execute:** owner session kilidi, idempotency, plan kontrolü ve dört kalıcı kabul yazımı aynı transaction içindedir. `findSession` yalnız keşif okumasıdır; `lockOwnedSession` owner filtresi ve lock'u bir araya getirir.
7. **previous:** aynı key ve aynı normalize body eski command'ı döndürür. Aynı key başka body için 409'dur. Plan TTL kontrolü tekrar cevabından sonra gelir; daha önce kabul edilen komutun cevabı TTL geçti diye yeni komuta dönüştürülmez.
8. **lockPlan ve domain hata kodları:** hash/state/expiry/generation/status kontrol edilir. BusinessException HTTP bilmez; ApiErrors 404/409/422/503 eşlemesini yapar.
9. **Repository.accept:** komut, approval, plan consumption ve idempotency kaydı aynı connection/transaction içindedir. `jsonb_build_object` payload'ı SQL parametrelerinden kurar; elle JSON string kaçışı yapılmaz.
10. **Mapper.toResponse:** service sonucu JSON sözleşmesine çevrilir. Controller `202 Accepted` döner; henüz fill veya position oluştuğu söylenmez.

**Bu örneğin açık sınırı:** Snapshot/risk preflight, preview adaptörü ve fill worker önceki sürümde olduğu gibi ayrıca bağlanmalıdır. Repository ayrımı bu eksik davranışları tamamlamış sayılmaz. Kabul kontrol listesini bunun için tutuyoruz.

## B05.2–B05.3 — Preview uygulama adımları

`PreviewOrder` use case’ini şu sırayla elle tamamla; yukarıdaki tam sınıflar hesap ve onay altyapısıdır, **preview/worker adaptörleri henüz kendiliğinden oluşmuş değildir**.

1. ManualPreview DTO’sunu README 7.2 alanlarıyla yaz. marginBudget/leverage/price alanları String; domain’e `Decimal.parse` ile geçer. source yalnız MANUAL. AI_PROPOSAL ayrı DTO B09.
2. Owner session, frozen rule profile ve risk policy yükle. Horizon enum seti `[60,900,3600,86400,604800]`; orderType MARKET/LIMIT; null limit sadece MARKET için.
3. Mark ve quote’un kendi source/received zamanlarıyla `ExecutionGate.fresh` uygula. ReferenceEntry MARKET için Finance.marketPrice, LIMIT için limitPrice.
4. Manual marginBudget + leverage → Finance.open. `maxLeverage`, `maxMarginPerTrade`, tek ve toplam notional, concurrent positions, minimum notional, tick/step, stop risk bütçesini uygula. q büyütülmez. Stop zorunluluğu risk profile’dan gelir.
5. Mevcut instrument_generation yoksa `INSERT ... ON CONFLICT DO NOTHING` ile 1 oluştur. OPEN/LIQUIDATING position varsa yeni giriş reddet.
6. Server Clock ile createdAt/validUntil; frozen price bounds/maxOpenFee/slippage; UUID planId ve revision=1 üret. PlanHash.hash ile hesapla; hash alanı kendi girdisi değildir.
7. Planı canonical JSON + hash + owner session bağıyla kaydet. Cevapta Decimal string serializer kullan. Golden canonical byte dosyasını `contracts/fixtures/execution-plan.canonical.json` olarak dondur; `.0` ve `.000` aynı hash olmalı, quantity değişince farklı hash olmalı.

## B05.5 — Worker algoritması ve kalıcılık

Worker’ı `@Scheduled` poller + ayrı `@Transactional` bean olarak yaz. Poller yalnız command ID seçer; transaction’daki use case session’ı kilitler, command hâlâ ACCEPTED mi tekrar bakar. Aynı command iki worker tarafından seçilse de ikinci kilit sonrası terminal durumu görür. RUNNING durumu kalıcı lease ile kullanılacaksa crash recovery süresi tanımla; önce tek transaction’da command→fill yaklaşımı daha anlaşılırdır.

```java
// Worker transaction'ının uygulayacağı sıra (use case algoritması):
// 1. session FOR UPDATE; command terminal ise dön.
// 2. onaylı plan + güncel yerel mark/quote/profile/generation yükle.
// 3. ExecutionGate.fresh ve ExecutionGate.approved; ayrıca tüm risk limitleri.
// 4. fill q <= oppositeQty * participation (varsayılan eğitim .10).
// 5. MARKET: Finance.marketPrice ve Finance.open; q tam onaylanan q olmalı.
// 6. FillAccounting.opening -> ledger.post; position/fill/order/outbox aynı TX.
// 7. command SUCCEEDED; order FILLED. Sonra commit.
// Beklenen domain reject: command REJECTED ve reason kalıcı; para hareketi yok.
// Beklenmeyen DB/runtime hata: tüm TX rollback; retry aynı command ID ile.
```

**LIMIT:** ilk quote’ta marketable ise taker. Değilse A→H reserve (limit teminat+fee+profil tamponu), order NEW; position yok. Sonraki taze quote BUY ask≤limit / SELL bid≥limit olunca adverse fill fiyatını hesapla; fiyat limit dışındaysa bekle. Fill H→B, H→fee, kalan H→A. Yetersiz reserve’de reject ve H→A iade. Cancel ve fill aynı session kilidinde yalnız bir terminal geçiş. Cash reserve Redis TTL’sine bağlı değildir.

**Close:** owner + expectedVersion + idempotency; session lock; closeRequested=true, instrument generation+1; bağlı pending entry iptal/iade; reduce-only command yarat. Veri yoksa EXIT_PENDING_DATA; normal hacim yetmiyorsa NEW/LIQUIDITY_LIMIT. Taze quote gelince Finance.close ve FillAccounting.closing. Tam close q=0/B=0, CLOSED; kısmi B07. Stop fiyatından doğrudan fill üretme.

## B05.7 — Query ve hata zarfı

GET commands/orders/positions/wallet/ledger sorgularında session owner filtresi zorunlu. Entity doğrudan JSON dönmez; README View alanlarını DTO’ya aktar. Cursor içinde timestamp+UUID kullan; max200. Command status SUCCEEDED limit order’ın FILLED olduğunu söylemez.

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/api/exception/ApiErrors.java`

**Sınıfı yazmadan önce:** Hataları güvenli HTTP cevabına dönüştürür. Hata kodu, status ve retry davranışını ayır; iç exception mesajını dışarı yansıtma.

```java
package com.cryptolab.simulation.api.exception;
import com.cryptolab.simulation.domain.exception.BusinessException;
import com.cryptolab.simulation.api.dto.response.ApiError;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DuplicateKeyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
/**
 * Sorumluluk: Hataları güvenli HTTP cevabına dönüştürür.
 * Okuma ipucu: Hata kodu, status ve retry davranışını ayır; iç exception mesajını dışarı yansıtma.
 */
@RestControllerAdvice
public class ApiErrors {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> business(BusinessException exception) {
        return known(exception.code().name());
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception) {
        // rejectedValue taşınmaz: hassas değerler response'a geri yansıtılmaz.
        var fields = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(), "message", "Geçersiz değer.")).toList();
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_FAILED", "Alanları kontrol edin.",
                UUID.randomUUID().toString(), false, Map.of("fields", fields)));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> malformed() { return response(400, "INVALID_JSON", false); }
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiError> duplicate() { return response(409, "CONFLICT", false); }
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<ApiError> legacy(RuntimeException exception) {
        // B02 saf çekirdeğin mevcut hata kodları korunur; bilinmeyen mesajlar gösterilmez.
        String code = exception.getMessage();
        if (code != null && Set.of("NOT_FOUND", "IDEMPOTENCY_CONFLICT", "VERSION_CONFLICT",
                "PLAN_EXPIRED", "PLAN_REVOKED", "REVALIDATION_REQUIRED", "STALE_MARK", "STALE_QUOTE",
                "INSUFFICIENT_AVAILABLE_BALANCE", "LIQUIDITY_LIMIT", "SESSION_HALTED").contains(code)) return known(code);
        if (code != null && Set.of("INVALID_KEY", "INVALID_SUBMIT", "INVALID_DECIMAL", "DECIMAL_OUT_OF_RANGE",
                "POSITIVE_REQUIRED", "NON_NEGATIVE_REQUIRED", "INVALID_LEVERAGE", "INVALID_HORIZON",
                "INVALID_EXPIRY_POLICY", "INVALID_ORDER_TYPE", "INVALID_TTL", "INVALID_TICK",
                "MIN_NOTIONAL", "LIMIT_PRICE_REQUIRED_OR_FORBIDDEN", "INVALID_APPROVAL_BOUNDS").contains(code))
            return response(400, code, false);
        return unexpected(exception); // Programlama hatasını sahte 400 ile gizlemeyiz.
    }
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ApiError> forbidden() { return response(403, "FORBIDDEN", false); }
    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public ResponseEntity<ApiError> unauthenticated() { return response(401, "UNAUTHORIZED", false); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception exception) {
        String traceId = UUID.randomUUID().toString();
        // Gerçek tracing kurulduğunda aynı traceId MDC/response'a bağlanır.
        // Exception mesajını/SQL'i varsayılan loga dökmeyiz; kontrollü tanılama ayrı tutulur.
        LOG.error("Unexpected failure traceId={} type={}", traceId, exception.getClass().getName());
        return ResponseEntity.internalServerError().body(new ApiError("INTERNAL_ERROR",
                "İşlem tamamlanamadı.", traceId, false, Map.of()));
    }
    private ResponseEntity<ApiError> known(String code) {
        int status = switch (code) {
            case "NOT_FOUND" -> 404;
            case "IDEMPOTENCY_CONFLICT", "VERSION_CONFLICT", "PLAN_EXPIRED", "PLAN_REVOKED", "REVALIDATION_REQUIRED" -> 409;
            case "STALE_MARK", "STALE_QUOTE" -> 503;
            default -> 422;
        };
        return response(status, code, status == 503);
    }
    private ResponseEntity<ApiError> response(int status, String code, boolean retryable) {
        return ResponseEntity.status(status).body(new ApiError(code, "İstek tamamlanamadı.",
                UUID.randomUUID().toString(), retryable, Map.of()));
    }
}
```

**Önemli tamamlanma sınırı:** Bu derste tam verilen sınıflar onay, hash, gate ve posting çekirdeğidir. Preview/worker/query için yukarıdaki bağlantı adımları ayrıca kodlanmalıdır; bunları uygulamadan bu dosyayı “çalışan tüm emir servisi” diye değerlendirme. Birlikte kodlarken her adımın controller/repository’sini kendi mevcut koduna göre tamamlayacağız.

**Kontrol senaryoları:** Aynı key+aynı body aynı command; farklı body 409. Aynı plan farklı key ile ikinci command yok. Submit ile fill arasında fiyat sınır dışına çıkarsa REJECTED, para yok. Limit kabulü position üretmez. Timeout sonrasında aynı key ile tekrar, tek ledger. E=50000/q=.2/L=10 açılış A=8995/B=1000; 50500 normal close A=10089.95.

## Ek kod atölyesi — Preview girdileri ve hesap hazırlığı

Bu ek, yukarıdaki preview adımlarının sayısal kısmını tam sınıf olarak açar. Kalıcı profil ve snapshot adaptörlerinin çağıracağı saf factory’dir. B07 RiskPolicyEvaluator miktar sınırı olarak bu factory’nin önünde kullanılır.

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/api/dto/request/ManualPreviewRequest.java`



**Sınıfı yazmadan önce:** İstemciden kabul edilen alanları tarif eder. Annotation biçimi kontrol eder; owner ve finans iş kuralı kontrolü sonraki katmandadır.

```java
package com.cryptolab.simulation.api.dto.request;
import jakarta.validation.constraints.*;
import java.util.UUID;
/**
 * Sorumluluk: İstemciden kabul edilen alanları tarif eder.
 * Okuma ipucu: Annotation biçimi kontrol eder; owner ve finans iş kuralı kontrolü sonraki katmandadır.
 */
public record ManualPreviewRequest(@NotNull @Pattern(regexp="MANUAL")String source,
 @NotNull UUID sessionId,@NotNull UUID instrumentId,@NotNull @Pattern(regexp="LONG|SHORT")String side,
 @NotNull @Pattern(regexp="MARKET|LIMIT")String orderType,@NotBlank String marginBudget,
 @NotBlank String leverage,String limitPrice,String stopLoss,String takeProfit,
 int horizonSeconds,@NotNull @Pattern(regexp="REEVALUATE|CLOSE_AT_DEADLINE")String expiryPolicy) {
 // JSON alanı String: büyük finans sayıları JavaScript number hassasiyetine teslim edilmez.
}
```

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/domain/service/PreviewFactory.java`



**Sınıfı yazmadan önce:** Girdilerden iş kararı/hesap üretir. Aynı girdinin aynı sonucu üretmesi beklenir; DB/ağ yan etkisi ekleme.

```java
package com.cryptolab.simulation.domain.service;
import com.cryptolab.finance.Decimal;
import com.cryptolab.finance.Finance;
import com.cryptolab.finance.MaintenanceTier;
import com.cryptolab.finance.PositionSide;
import com.cryptolab.simulation.domain.model.ExecutionPlan;
import com.cryptolab.finance.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
/**
 * Sorumluluk: Girdilerden iş kararı/hesap üretir.
 * Okuma ipucu: Aynı girdinin aynı sonucu üretmesi beklenir; DB/ağ yan etkisi ekleme.
 */
public final class PreviewFactory {
 public record Rule(UUID id,BigDecimal tick,BigDecimal step,BigDecimal minNotional,BigDecimal takerRate,
                    List<MaintenanceTier> tiers){}
 public record Request(UUID session,UUID instrument,long instrumentVersion,long generation,String side,String orderType,
  BigDecimal marginBudget,BigDecimal leverage,BigDecimal limit,BigDecimal stop,BigDecimal target,int horizon,String expiryPolicy){}
 public record Context(BigDecimal bid,BigDecimal ask,BigDecimal available,BigDecimal slipBps,long riskVersion,
  long ttlSeconds,BigDecimal lower,BigDecimal upper,BigDecimal maxFee,boolean requireStop,boolean requireTarget){}
 private PreviewFactory(){}
 public static ExecutionPlan create(Request r,Context c,Rule rule,Instant now){
  if(!Set.of(60,900,3600,86400,604800).contains(r.horizon()))throw new IllegalArgumentException("INVALID_HORIZON");
  if(!Set.of("REEVALUATE","CLOSE_AT_DEADLINE").contains(r.expiryPolicy()))throw new IllegalArgumentException("INVALID_EXPIRY_POLICY");
  if(!Set.of("MARKET","LIMIT").contains(r.orderType()))throw new IllegalArgumentException("INVALID_ORDER_TYPE");
  if(c.ttlSeconds()<=0)throw new IllegalArgumentException("INVALID_TTL");
  boolean buy="LONG".equals(r.side());PositionSide side=PositionSide.valueOf(r.side());
  if(("LIMIT".equals(r.orderType()))!=(r.limit()!=null))throw new IllegalArgumentException("LIMIT_PRICE_REQUIRED_OR_FORBIDDEN");
  BigDecimal reference=r.limit()==null?Finance.marketPrice(buy,c.bid(),c.ask(),rule.tick(),c.slipBps()):Decimal.positive(r.limit());
  if(r.limit()!=null&&r.limit().remainder(rule.tick()).signum()!=0)throw new IllegalArgumentException("INVALID_TICK");
  var open=Finance.open(r.marginBudget(),r.leverage(),reference,rule.step(),rule.takerRate(),c.available());
  if(open.notional().compareTo(rule.minNotional())<0)throw new IllegalArgumentException("MIN_NOTIONAL");
  ExecutionGate.bracket(r.side(),reference,r.stop(),r.target(),c.requireStop(),c.requireTarget());
  if(c.lower().signum()<=0||c.lower().compareTo(reference)>0||c.upper().compareTo(reference)<0||c.maxFee().compareTo(open.fee())<0)
   throw new IllegalArgumentException("INVALID_APPROVAL_BOUNDS");
  return new ExecutionPlan(UUID.randomUUID(),1,null,r.session(),r.instrument(),r.instrumentVersion(),"MANUAL",null,r.generation(),
   r.side(),r.orderType(),open.quantity(),reference,r.limit(),open.margin(),open.fee(),open.notional(),r.leverage(),r.stop(),r.target(),
   r.horizon(),r.expiryPolicy(),c.lower(),c.upper(),c.maxFee(),c.slipBps(),rule.id(),c.riskVersion(),now,now.plusSeconds(c.ttlSeconds()),
   Finance.liquidation(side,open.quantity(),reference,open.margin(),rule.tiers()).orElse(null),List.of());
 }
}
```

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/infrastructure/config/DecimalJsonConfig.java`



**Sınıfı yazmadan önce:** Teknik nesneleri ve Spring bağlantılarını kurar. Bean hangi portu karşılıyor ve hangi profile’da etkin sorularını cevapla.

```java
package com.cryptolab.simulation.infrastructure.config;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.cryptolab.finance.Decimal;
import org.springframework.context.annotation.*;
import java.math.BigDecimal;
import java.io.IOException;
/**
 * Sorumluluk: Teknik nesneleri ve Spring bağlantılarını kurar.
 * Okuma ipucu: Bean hangi portu karşılıyor ve hangi profile’da etkin sorularını cevapla.
 */
@Configuration
public class DecimalJsonConfig {
 @Bean com.fasterxml.jackson.databind.Module decimals(){
  var module=new SimpleModule();
  module.addSerializer(BigDecimal.class,new JsonSerializer<BigDecimal>(){
   @Override public void serialize(BigDecimal value,JsonGenerator generator,SerializerProvider provider)throws IOException{
    generator.writeString(Decimal.wire(value)); // JSON number değil quoted canonical Decimal.
   }
  });return module;
 }
}
```

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/domain/service/LimitDecision.java`



**Sınıfı yazmadan önce:** Girdilerden iş kararı/hesap üretir. Aynı girdinin aynı sonucu üretmesi beklenir; DB/ağ yan etkisi ekleme.

```java
package com.cryptolab.simulation.domain.service;
import com.cryptolab.finance.Finance;
import com.cryptolab.simulation.domain.model.ExecutionPlan;
import java.math.BigDecimal;
/**
 * Sorumluluk: Girdilerden iş kararı/hesap üretir.
 * Okuma ipucu: Aynı girdinin aynı sonucu üretmesi beklenir; DB/ağ yan etkisi ekleme.
 */
public final class LimitDecision {
 public record Result(String action,String reason,BigDecimal price){}
 private LimitDecision(){}
 public static Result evaluate(ExecutionPlan p,BigDecimal bid,BigDecimal ask,BigDecimal bidQty,
  BigDecimal askQty,BigDecimal tick,BigDecimal participation){
  boolean buy="LONG".equals(p.side());BigDecimal visible=buy?askQty:bidQty;
  if("LIMIT".equals(p.orderType())){
   if(buy?ask.compareTo(p.limitPrice())>0:bid.compareTo(p.limitPrice())<0)return new Result("WAIT","NOT_MARKETABLE",null);
  }
  BigDecimal price=Finance.marketPrice(buy,bid,ask,tick,p.maxSlippageBps());
  if("LIMIT".equals(p.orderType())&&(buy?price.compareTo(p.limitPrice())>0:price.compareTo(p.limitPrice())<0))return new Result("WAIT","ADVERSE_PRICE_OUTSIDE_LIMIT",null);
  if(p.quantity().compareTo(visible.multiply(participation))>0)return new Result("REJECT","LIQUIDITY_LIMIT",null);
  return new Result("FILL",null,price);
 }
}
```

**Test ekleri:** Factory marginBudget1000/available1000 reddetmeli; maxFee<fee reddetmeli; LIMIT fiyat step değil tick’e uymalı. MARKET request limitPrice içeremez. Plan response’a hash eklerken canonical alanları değiştirme; `planHash` dış response DTO’sunda `PlanHash.hash(plan)` olabilir. DB canonical_json içinde planHash bulunmaz, hash ayrı sütundadır.



### `backend/simulation-service/src/main/java/com/cryptolab/simulation/application/command/SubmitOrderCommand.java`

Request HTTP biçimini, command uygulama girdisini temsil eder. Aynı alanları taşımaları iki sınıfın aynı sorumlulukta olduğu anlamına gelmez.

**Sınıfı yazmadan önce:** Kullanım senaryosunun girdisini taşır. HTTP nesnesi taşımadan gereken alanları ver; hassas değerleri loglama.

```java
package com.cryptolab.simulation.application.command;
import java.util.UUID;
/**
 * Sorumluluk: Kullanım senaryosunun girdisini taşır.
 * Okuma ipucu: HTTP nesnesi taşımadan gereken alanları ver; hassas değerleri loglama.
 */
public record SubmitOrderCommand(UUID planId, int revision, String planHash) {
    public SubmitOrderCommand {
        // HTTP validation tek giriş noktası değildir; application sınırı da korunur.
        if (planId == null || revision < 1 || planHash == null
                || !planHash.matches("^sha256:[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("INVALID_SUBMIT");
        }
    }
}
```

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/api/dto/response/AcceptedOrderResponse.java`

Service sonucunu doğrudan JSON yapmayız. Response alanları endpoint sözleşmesini sabit tutar.

**Sınıfı yazmadan önce:** Dış API alanlarını sabit tutar. Alan adları sözleşmedir; domain iç alanlarını veya hassas veriyi kendiliğinden ekleme.

```java
package com.cryptolab.simulation.api.dto.response;
import java.time.Instant;
import java.util.UUID;
/**
 * Sorumluluk: Dış API alanlarını sabit tutar.
 * Okuma ipucu: Alan adları sözleşmedir; domain iç alanlarını veya hassas veriyi kendiliğinden ekleme.
 */
public record AcceptedOrderResponse(UUID commandId, String status, String statusUrl, Instant acceptedAt) {
    // JSON alan adları kaynak sözleşmeyle aynıdır. 202, fill gerçekleşti demek değildir.
}
```

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/api/mapper/OrderMapper.java`

İlk aşamada elle mapper kullanıyoruz: alanların nereden geldiği görünür. Çok sayıda tekrar oluşursa MapStruct ayrıca değerlendirilebilir; yeni bağımlılık zorunlu değildir.

**Sınıfı yazmadan önce:** HTTP modeli ile uygulama modelini dönüştürür. Mapper’ın yan etkisi olmamalı; DB, ağ, ücret hesabı veya izin kararı çağırma.

```java
package com.cryptolab.simulation.api.mapper;
import com.cryptolab.simulation.api.dto.request.SubmitOrderRequest;
import com.cryptolab.simulation.api.dto.response.AcceptedOrderResponse;
import com.cryptolab.simulation.application.command.SubmitOrderCommand;
import com.cryptolab.simulation.application.service.SubmitOrder;
import org.springframework.stereotype.Component;
/**
 * Sorumluluk: HTTP modeli ile uygulama modelini dönüştürür.
 * Okuma ipucu: Mapper’ın yan etkisi olmamalı; DB, ağ, ücret hesabı veya izin kararı çağırma.
 */
@Component
public class OrderMapper {
    public SubmitOrderCommand toCommand(SubmitOrderRequest request) {
        // Mapper fiyat/quantity hesaplamaz, owner üretmez; yalnız biçim dönüştürür.
        return new SubmitOrderCommand(request.planId(), request.revision(), request.planHash());
    }
    public AcceptedOrderResponse toResponse(SubmitOrder.Accepted result) {
        return new AcceptedOrderResponse(result.commandId(), result.status(),
                result.statusUrl(), result.acceptedAt());
    }
}
```

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/application/repository/OrderSubmissionRepository.java`

Portta ResultSet, Timestamp ve SQL bulunmaz. Repository veri okur/yazar; planın süresinin dolduğuna service karar verir.

**Sınıfı yazmadan önce:** Kalıcılık davranışını arayüz olarak tanımlar. Service bu arayüze bağımlıdır; SQL implementasyonu infrastructure altındadır.

```java
package com.cryptolab.simulation.application.repository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
/**
 * Sorumluluk: Kalıcılık davranışını arayüz olarak tanımlar.
 * Okuma ipucu: Service bu arayüze bağımlıdır; SQL implementasyonu infrastructure altındadır.
 */
public interface OrderSubmissionRepository {
    record Previous(String requestHash, UUID commandId) {}
    record Plan(String hash, String state, Instant validUntil, UUID instrumentId, long generation) {}
    Optional<UUID> findSession(UUID plan, int revision);
    boolean lockOwnedSession(UUID session, UUID owner);
    Optional<Previous> previous(UUID owner, String route, UUID session, String key);
    Plan lockPlan(UUID plan, int revision);
    long generation(UUID session, UUID instrument);
    String sessionStatus(UUID session);
    void accept(UUID command, UUID session, UUID owner, UUID plan, int revision,
                String hash, String route, String key, String requestHash, Instant now);
    Instant acceptedAt(UUID command);
}
```

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/infrastructure/repository/JdbcOrderSubmissionRepository.java`

SQL adaptörü lock sırasını değiştirmez. Önce session kilidi alınır; plan kilidi daha sonra service tarafından istenir. DB unique constraint son savunma olarak kalır.

**Sınıfı yazmadan önce:** Portun SQL veya okuma modeli kalıcılığını uygular. Parametreli SQL, transaction katılımı, owner/unique/lock kapsamını incele.

```java
package com.cryptolab.simulation.infrastructure.repository;
import com.cryptolab.simulation.application.repository.OrderSubmissionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
/**
 * Sorumluluk: Portun SQL veya okuma modeli kalıcılığını uygular.
 * Okuma ipucu: Parametreli SQL, transaction katılımı, owner/unique/lock kapsamını incele.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcOrderSubmissionRepository implements OrderSubmissionRepository {
    private final JdbcTemplate db;
    public JdbcOrderSubmissionRepository(JdbcTemplate db) { this.db = db; }
    public Optional<UUID> findSession(UUID plan, int revision) {
        return db.queryForList("SELECT session_id FROM execution_plans WHERE plan_id=? AND revision=?",
                UUID.class, plan, revision).stream().findFirst();
    }
    public boolean lockOwnedSession(UUID session, UUID owner) {
        return !db.queryForList("SELECT id FROM simulation_sessions WHERE id=? AND owner_id=? FOR UPDATE",
                UUID.class, session, owner).isEmpty();
    }
    public Optional<Previous> previous(UUID owner, String route, UUID session, String key) {
        return db.query("""
            SELECT request_hash,result_ref FROM idempotency_records
            WHERE subject=? AND route=? AND scope=? AND key=?
            """, (rs, n) -> new Previous(rs.getString(1), UUID.fromString(rs.getString(2))),
                owner, route, session.toString(), key).stream().findFirst();
    }
    public Plan lockPlan(UUID plan, int revision) {
        return db.queryForObject("""
            SELECT hash,state,valid_until,instrument_id,position_generation
            FROM execution_plans WHERE plan_id=? AND revision=? FOR UPDATE
            """, (rs, n) -> new Plan(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant(),
                rs.getObject(4, UUID.class), rs.getLong(5)), plan, revision);
    }
    public long generation(UUID session, UUID instrument) {
        return db.queryForObject("SELECT generation FROM instrument_generations WHERE session_id=? AND instrument_id=?",
                Long.class, session, instrument);
    }
    public String sessionStatus(UUID session) {
        return db.queryForObject("SELECT status FROM simulation_sessions WHERE id=?", String.class, session);
    }
    public void accept(UUID command, UUID session, UUID owner, UUID plan, int revision,
                       String hash, String route, String key, String requestHash, Instant now) {
        // PostgreSQL JSON oluşturur; string birleştirme/kaçış hatası yapılmaz.
        db.update("""
            INSERT INTO commands(id,session_id,type,payload,status,reason,created_at,updated_at)
            VALUES (?,?,'SUBMIT',jsonb_build_object('planId',?::text,'revision',?::integer,
            'planHash',?::text),'ACCEPTED',NULL,?,?)
            """, command, session, plan.toString(), revision, hash, Timestamp.from(now), Timestamp.from(now));
        db.update("INSERT INTO approvals VALUES (?,?,?,?,?,?)", UUID.randomUUID(), owner, plan, revision, hash, command);
        db.update("UPDATE execution_plans SET state='CONSUMED' WHERE plan_id=? AND revision=?", plan, revision);
        db.update("INSERT INTO idempotency_records(subject,route,scope,key,request_hash,result_ref) VALUES (?,?,?,?,?,?)",
                owner, route, session.toString(), key, requestHash, command);
        // Dört yazım ayrı commit yapmaz; dış service transaction'ının parçalarıdır.
    }
    public Instant acceptedAt(UUID command) {
        return db.queryForObject("SELECT created_at FROM commands WHERE id=?", Timestamp.class, command).toInstant();
    }
}
```

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/domain/exception/BusinessException.java`

İş hatası HTTP durum kodunu bilmez. 409/422/503 eşlemesini exception handler yapar. Her servisin hata tipi kendi sınırındadır.

**Sınıfı yazmadan önce:** İş kuralı ihlalini HTTP’den bağımsız ifade eder. Güvenli hata kodunu taşı; status code eşlemesini API katmanına bırak.

```java
package com.cryptolab.simulation.domain.exception;
/**
 * Sorumluluk: İş kuralı ihlalini HTTP’den bağımsız ifade eder.
 * Okuma ipucu: Güvenli hata kodunu taşı; status code eşlemesini API katmanına bırak.
 */
public class BusinessException extends RuntimeException {
    public enum Code {
        NOT_FOUND, IDEMPOTENCY_CONFLICT, VERSION_CONFLICT, PLAN_EXPIRED,
        PLAN_REVOKED, REVALIDATION_REQUIRED, STALE_MARK, STALE_QUOTE,
        INSUFFICIENT_AVAILABLE_BALANCE, LIQUIDITY_LIMIT, SESSION_HALTED
    }
    private final Code code;
    public BusinessException(Code code) {
        super(code.name()); // Sadece güvenli kod; SQL veya request body taşınmaz.
        this.code = code;
    }
    public Code code() { return code; }
}
```

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/api/dto/response/ApiError.java`

Tek biçim frontend'in her hata için ayrı parser yazmasını önler. Bu DTO başka servisin domain sınıfı olarak paylaşılmaz.

**Sınıfı yazmadan önce:** Dış API alanlarını sabit tutar. Alan adları sözleşmedir; domain iç alanlarını veya hassas veriyi kendiliğinden ekleme.

```java
package com.cryptolab.simulation.api.dto.response;
import java.util.Map;
/**
 * Sorumluluk: Dış API alanlarını sabit tutar.
 * Okuma ipucu: Alan adları sözleşmedir; domain iç alanlarını veya hassas veriyi kendiliğinden ekleme.
 */
public record ApiError(String code, String message, String traceId,
                       boolean retryable, Map<String,Object> details) {
    public ApiError { details = Map.copyOf(details); }
    // Kaynak hata sözleşmesi korunur; stack trace, SQL ve reddedilen parola dönülmez.
}
```

## Kabul: hangi çıktıyı görmelisin?

**Deneme/kabul:** Aynı plan iki emir açmaz; key başka body ile 409; fiyat/tavan değişince yeniden onay; ücret yetersizliğinde bakiye kaybı yok. Fixture giriş ve kapanış B02 altın rakamlarıyla tutarlı. LIMIT kabulü position oluşturmaz. B07 tamamlanmadan uygulama eksiksiz likidasyon sistemi diye sunulmaz.



## Kendi cümlelerinle anlat

“202 cevabı geldiğinde hangi kayıt kesin, hangi sonuç henüz kesin değil?”

## Çalışma sonunda

`progress/BACKEND.md` içine alt görev, değiştirdiğin dosya, komut, gerçek çıktı, fixture/gerçek entegrasyon ayrımı ve sıradaki tek adımı yaz. Çalıştırmadığın test için PASS yazma.
