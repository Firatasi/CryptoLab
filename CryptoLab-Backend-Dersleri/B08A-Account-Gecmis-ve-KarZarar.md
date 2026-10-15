# B08A — Account: kullanıcı işlem geçmişi ve kâr/zarar görünümü

> **DISC-01:** Eureka/local adres çözümlemesi için B01A-Eureka-ve-Servis-Kesfi.md uygulanır. Uygulama ayarları properties olarak kalır; iş kuralları değişmez.

Revizyon 4 · Şartname 3.0 + ACC-01 eki · Mevcut sözleşmeler 1.0.0 korunur · Account okuma sözleşmesi 1.0.0 · BUILD_UNVERIFIED

**Sıra:** B08'den sonra, B09'dan önce. B01–B12 numaraları değişmez; artık 12 ana görev + bu ek görev vardır.

## Neyi yapıyoruz, neden?

Kullanıcı “hesabım” ekranında geçmiş işlemlerini ve toplam kâr/zararını görmeli. Bunun için `account-service` adlı bağımsız Spring Boot servisini ekliyoruz. Bu servis kullanıcıya dönük okuma API'sidir: Reporting'in kalıcı, owner filtreli işlem geçmişini ve ledger'dan üretilmiş toplamlarını sunar.

JWT üretimini Account'a taşımıyoruz. Identity kayıt/giriş/parola/refresh/JWKS otoritesi olarak kalır. Simulation emir/pozisyon/para yazarıdır. Reporting geçmişi ve rapor projection'ını saklar. Account bunların üzerinde tutarlı kullanıcı görünümü verir. Dört servisin aynı User veya Wallet entity'sini paylaşması gerekmez.

**Geçmiş nerede tutulur?** Kullanıcıya ait trade ve journal geçmişi Reporting projection'ı ve Simulation'ın kalıcı ledger'ında tutulur; uygulama yeniden başladığında kaybolmaz. Account'un ilk sürümü stateless'tır, ayrı account_db/repository açmaz. Gereksiz ikinci bir finans kopyası yaratmamak için bu bilinçli seçimdir. İleride yalnız Account'a ait tercih/ayar eklenirse o veri için account_db eklenebilir; bu görevde gerekmiyor.

## Sorumluluk haritası

| Servis | Sahip olduğu veri/iş | Yapmayacağı iş |
|---|---|---|
| Identity | Kimlik, parola hash, rol, refresh session, JWT imzalama | Trade kâr/zararı hesaplamak |
| Simulation | Session, emir, fill, pozisyon, bakiye ve ledger | Dashboard için gecikmiş Reporting bakiyesiyle risk kararı |
| Reporting | Kalıcı owner/session trade geçmişi, ledger projection, toplamlar | Emir açmak, gerçek bakiye yazmak |
| Account | `/account/me` kullanıcısının geçmiş/özet API'si, response mapping | JWT imzalamak, bakiye değiştirmek, Reporting DB'sine doğrudan SQL |
| Gateway | Route, CORS, giriş sınırları ve token doğrulama | Downstream ownership kontrolünü kaldırmak |

## Alt görevler

- [ ] **B08A.1** Account Maven modülü, 8086 portu, application.properties ve başlangıç sınıfı.
- [ ] **B08A.2** DTO/model/mapper/service/port/client/config/exception paketleri; repository'nin neden Reporting'de olduğunu açıkla.
- [ ] **B08A.3** Account ve Reporting JWT imza/issuer/audience/expiry/subject doğrulaması; kendi kullanıcı verisi kapsamı.
- [ ] **B08A.4** Reporting owner-wide summary ve trade-history sorguları, kapsam/coverage ve pagination sözleşmesi.
- [ ] **B08A.5** Gerçekleşen net, açık pozisyon PnL, toplam net ve insurance ayrımı; geç funding düzeltmesi.
- [ ] **B08A.6** HTTP adaptörü, timeout/bounded response, JWT relay ve hata zarfı; sahte sıfır fallback yok.
- [ ] **B08A.7** Gateway account route; frontend Hesabım ekranı veri sözleşmesi ve güvenlik testleri.
- [ ] **B08A.8** Duplicate event, kullanıcılar arası erişim, restart, pagination, boş hesap ve Reporting-down kabul deneyleri.

## Paket ve yazma sırası

| Sıra | Paket | Sınıflar / görev |
|---|---|---|
| 1 | domain.model | AccountSummary, TradeHistory: Reporting'den doğrulanacak veri modeli |
| 2 | domain.exception | AccountFailure: güvenli hata kodu |
| 3 | application.port | AccountReportReader: okuma ihtiyacı |
| 4 | application.service | AccountQueryService: kullanıcı ve filtre kapsamı |
| 5 | infrastructure.client | HttpAccountReportReader: Reporting'e HTTP çağrısı |
| 6 | infrastructure.config | AccountSecurityConfig, teknik properties |
| 7 | api.dto.response | AccountSummaryResponse, TradeHistoryResponse |
| 8 | api.mapper | AccountMapper: iç → dış response |
| 9 | api.controller / api.exception | AccountController, AccountErrors |

Account repository arayüzü eklemiyoruz: bu servis DB'ye yazmıyor. Reporting sorgu repository'si kendi servisinin application.repository/infrastructure.repository paketlerinde kalır. “Her serviste aynı klasör isimleri olsun” diye kullanılmayan entity/repository üretme.

## API ve kapsam

| Endpoint | Parametre | Sonuç |
|---|---|---|
| GET `/api/v1/account/me/summary` | `sessionId` isteğe bağlı | AccountSummaryResponse |
| GET `/api/v1/account/me/trades` | `sessionId`, `cursor`, `limit` (1–100, default 20) | TradeHistoryResponse |

Owner sadece doğrulanmış JWT `sub` değerinden gelir. Body/query/header'dan userId kabul edilmez. Session seçilmişse hem Account cevap kapsamını hem Reporting owner SQL filtresini kontrol eder. ADMIN bile bu `/me` yollarıyla başka kullanıcıyı göremez; yönetim erişimi ayrı bir ihtiyaçtır.

Bu sürüm LIVE_PAPER / USDT kapsamındadır. Replay sonuçları bu toplama katılmaz. Seçili session yoksa kullanıcıya ait LIVE_PAPER session'lar toplanır. Aynı başlangıç sanal bakiyesini defalarca yaratmak kâr değildir; initial deposit toplam net PnL'ye eklenmez. Spot/replay/başka para birimini aynı toplamda karıştırma; ileride kapsam ayrı sözleşmeyle eklenir.

## Kâr/zarar alanlarını doğru okumak

`netRealizedPnl = realizedPnl - totalFees + signedFunding - liquidationFees`.

`totalNetPnl = netRealizedPnl + unrealizedPnl`.

`insuranceAdjustment` ayrıca gösterilir; trade başarısına eklenmez. İstenirse finansal hesap etkisi UI'da `totalNetPnl + insuranceAdjustment` etiketiyle ayrı sunulur. Bu iki sayı aynı kavram değildir.

Net gerçekleşen toplam sadece kapanmış pozisyonların listesi değildir: açık pozisyonda ödenmiş fee/funding ve kısmi kapanış gerçekleşen PnL'si de ledger toplamındadır. `closedTradeCount` yalnız tamamen kapanmış/likide edilmiş pozisyon sayısıdır. Bir emir reddedilmesi trade değildir; geçmiş emir listesi mevcut Simulation order API'sinde kalır.

Örnek: realized=100, fees=10.05, funding=-1, liquidationFees=0 → netRealized=88.95. Açık PnL=-20 ise totalNet=68.95. Insurance=5 ayrı gösterilir; net kâr 73.95 diye yeniden adlandırılmaz.

Rapor incomplete ise bilinen kısmı “toplam” diye sunma. `coverage=BUILDING` için parasal toplamlar null, count null; `STALE` için son tamamlanmış snapshot verileri ve eski asOf gösterilebilir. `READY` yalnız tüm kapsam tamam ve değerleme yeterince tazeyse kullanılabilir. Gerçekten hiç session/trade bulunmayan doğrulanmış boş hesap için net tutarlar "0", count 0 olabilir; hiç projection yokken sıfır göstermek farklı bir hatadır.

## Reporting sözleşmesi — Account'un ihtiyaç duyduğu iki okuma

Account Eureka/LoadBalancer ile `reporting-service` örneğine, gelen doğrulanmış bearer token'ı aktararak gider. Reporting de aynı issuer/audience altında JWT'yi doğrular ve owner'ı kendi principal'ından çıkarır. İstemciye verilen token, header veya cookie loglanmaz; refresh cookie Account'tan Reporting'e aktarılmaz.

Ek Reporting yolları:
- GET `/api/v1/reports/me/account-summary?sessionId=...`
- GET `/api/v1/reports/me/trade-history?sessionId=...&limit=20&cursor=...`

Bu yollar dış kullanıcının kendi verisi için güvenlidir; `/internal/**` allowlist'ini gevşetmez. Mevcut session report endpoint'leri aynı kalır. Reporting için endpoint/controller/query service/repository bağlantısını B08A.4'te tamamla. Aşağıdaki Account kodu bu sözleşmeye göre istemcidir; eksik Reporting endpoint'i varmış gibi başarı üretmez.

Reporting uygulama adımları:
1. Owner'a ait session manifestini Simulation'ın yetkili audit/bootstrap API'sinden edin. Owner/mode/currency eşleşmesi doğrulanmadan sorgu açılmaz; başka servisin DB'sine JOIN yok.
2. Mode/currency bilgisini Reporting'in kendi session read model'inde sakla. Bootstrap olmayan session kapsamını eksik say. Yeni session'ın tüketicide henüz görülmemesi tam kapsam iddiasına dönüşmemeli; owner manifest sürümü/tazeliği ayrıca izlenmeli.
3. `trade_summaries` ve ledger toplamlarını sadece LedgerTransactionPosted'tan güncelle. PositionClosed yalnız kapanış metadata'sı sağlar. Geç kesin funding kapalı trade toplamını da düzeltir.
4. Özet toplamını aynı Reporting DB snapshot'ında oku. Per-session sequence'leri ayrı taşı; farklı session sequence'lerini toplayıp tek global sequence üretme. Her session'ın son uygun equity/unrealized örneğini kullan, farklı tarihler varsa minimum projectionAsOf ve maksimum lag'i göster.
5. Trades sırasını değişmeyen `(opened_at DESC, position_id DESC)` ile kur. Geç funding veya kapanış güncellemesi sıralama alanını değiştirmesin. Cursor ilk sayfanın asOf üst sınırını, son anahtarı ve owner/filtre bağını taşısın; HMAC ile imzalansın. Yeni işlemler sonraki sayfalara aradan girmesin. As-of üyelik sabitliği, tutarların tarihsel snapshot'ı olduğu anlamına gelmez; geç settlement tutarı düzeltebilir.
6. SQL bütün sorgularda `session_owners.owner_id = :subject` ve scope kontrolü uygulasın. Cursor owner/session kapsamıyla eşleşmiyorsa 400, başka kullanıcı session seçerse 404. Limit+1 çek, fazlasıyla nextCursor varlığını belirle; max100.
7. Repository sayfadaki işlemleri toplam kâr hesabı için kullanmasın. Özet ayrı tam kapsam sorgusudur; 20 satırlık sayfanın toplamı kullanıcı toplamı değildir.
8. Bootstrap/rebuild tamamlanmamışsa BUILDING; upstream erişilemezse 503. Projection restart/reset kullanıcı geçmişini silmez. Kaynak ledger/manifest üzerinden yeniden kurulabilir olmalı.

`TradeHistory` pozisyon bazındadır: aynı position'ın partial fill/close olayları bir trade altında toplanır; CLOSED/LIQUIDATED satırı geçmişe dönüşür, OPEN satırı devam eden pozisyonu gösterir. Order/fill detayına mevcut Simulation API'leriyle gidilir. Symbol etiketi için olay anındaki instrument identity/version korunur; re-list edilen aynı sembol geçmiş trade'i başka kontrata bağlamaz.

### `backend/account-service/pom.xml`

JDBC/JPA/Flyway/Kafka bu stateless Account servisine eklenmez. Parent modules listesi B01 içinde güncellendi. Boot 4 projenin mevcut sürümünü düşürme; eski örneklerle framework göçünü karıştırma.

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <parent><groupId>com.cryptolab</groupId><artifactId>backend</artifactId><version>1.0.0-SNAPSHOT</version><relativePath>../pom.xml</relativePath></parent>
  <artifactId>account-service</artifactId>
  <dependencies>
    <!-- HTTP API ve istemci; bu ders paketi mevcut Boot 3.5 tabanını kullanır. -->
    <dependency><groupId>org.springframework.cloud</groupId><artifactId>spring-cloud-starter-netflix-eureka-client</artifactId></dependency>
    <dependency><groupId>org.springframework.cloud</groupId><artifactId>spring-cloud-starter-loadbalancer</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-oauth2-resource-server</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.springframework.security</groupId><artifactId>spring-security-test</artifactId><scope>test</scope></dependency>
  </dependencies>
  <build><plugins><plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build>
</project>
```

Başlangıç sınıfı `AccountServiceApplication` B01’de oluşturulmuştur; aynı sınıfı ikinci kez oluşturma. Aşağıdaki POM ve properties, B01 iskeletini bu aşamada tamamlar.

### `backend/account-service/src/main/resources/application.properties`

Bu dosya application.properties yerine kullanılır. Aynı ayarın iki formatta kopyasını bırakma. Account sadece Authorization Bearer kabul eder, refresh cookie ile giriş yapmaz.

```properties
spring.application.name=account-service
server.address=127.0.0.1
server.port=8086
management.endpoints.web.exposure.include=health,info
management.endpoint.health.show-details=never
# Identity'nin public JWKS adresi. Private key Account'a verilmez.
jwt.issuer=${JWT_ISSUER:http://localhost:8081}
jwt.audience=${JWT_AUDIENCE:cryptolab-api}
jwt.jwks-url=${JWT_JWKS_URL:http://localhost:8081/.well-known/jwks.json}
# Local geliştirme varsayılanı. Üretimde güvenilir TLS origin ve ayrı ağ politikası.
account.reporting-service-id=reporting-service
spring.cloud.loadbalancer.cache.enabled=false
spring.cloud.loadbalancer.retry.enabled=false
eureka.client.service-url.defaultZone=${EUREKA_URL:http://localhost:8761/eureka/}
eureka.client.register-with-eureka=true
eureka.client.fetch-registry=true
eureka.client.healthcheck.enabled=true
eureka.instance.prefer-ip-address=true
eureka.instance.ip-address=127.0.0.1
eureka.instance.instance-id=${spring.application.name}:${server.port}
eureka.instance.health-check-url-path=/actuator/health
eureka.instance.status-page-url-path=/actuator/info
account.connect-timeout-ms=2000
account.request-timeout-ms=5000
account.max-response-bytes=1048576
```

### `backend/account-service/src/main/java/com/cryptolab/account/domain/model/AccountSummary.java`

Domain burada okuma modelidir. Account fiyatlardan yeni finans motoru çalıştırmaz; Reporting toplamını taşır.

```java
package com.cryptolab.account.domain.model;
import java.time.Instant;
import java.util.UUID;
public record AccountSummary(UUID ownerId, UUID sessionId, String mode, String currency,
        String realizedPnl, String totalFees, String signedFunding, String liquidationFees,
        String netRealizedPnl, String unrealizedPnl, String totalNetPnl,
        String insuranceAdjustment, Long closedTradeCount, Coverage coverage,
        Instant projectionAsOf, Long lagMs) {
    public enum Coverage { READY, STALE, BUILDING }
    // Decimal'lar wire sözleşmesinde string. Hesap Reporting/Simulation sınırında yapılır.
    // BUILDING ise toplam alanları null olabilir; kullanıcıya sahte 0 dönülmez.
}
```

### `backend/account-service/src/main/java/com/cryptolab/account/domain/model/TradeHistory.java`

Kısmi kapanışlar aynı positionId altında toplanır. Kimlik ve zaman alanları UUID/UTC Instant kalır.

```java
package com.cryptolab.account.domain.model;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public record TradeHistory(UUID ownerId, UUID sessionId, String mode, String currency,
        List<Trade> items, String nextCursor, AccountSummary.Coverage coverage, Instant projectionAsOf) {
    public TradeHistory { items = List.copyOf(items); }
    public record Trade(UUID positionId, UUID sessionId, UUID instrumentId, String symbol,
            String side, String status, Instant openedAt, Instant closedAt,
            String realizedPnl, String totalFees, String signedFunding,
            String liquidationFees, String netRealizedPnl, String insuranceAdjustment) {}
    // Bir sayfa = toplam rapor değildir. nextCursor opaktır, token içermez.
}
```

### `backend/account-service/src/main/java/com/cryptolab/account/domain/exception/AccountFailure.java`

HTTP status taşımayan güvenli kod. Provider exception mesajı veya bearer token error body içine konmaz.

```java
package com.cryptolab.account.domain.exception;
public class AccountFailure extends RuntimeException {
    public enum Code { INVALID_QUERY, REPORT_UNAVAILABLE, INVALID_REPORT, UNAUTHORIZED, NOT_FOUND }
    private final Code code;
    public AccountFailure(Code code) { super(code.name()); this.code = code; }
    public Code code() { return code; }
}
```

### `backend/account-service/src/main/java/com/cryptolab/account/application/port/AccountReportReader.java`

Port HTTP kütüphanesini bilmez. User context downstream JWT ile korunur; güvensiz X-User-Id türetilmez.

```java
package com.cryptolab.account.application.port;
import com.cryptolab.account.domain.model.AccountSummary;
import com.cryptolab.account.domain.model.TradeHistory;
import java.util.UUID;
public interface AccountReportReader {
    AccountSummary summary(String bearer, UUID session);
    TradeHistory trades(String bearer, UUID session, String cursor, int limit);
    // Bearer yalnız bu senkron istek kapsamında aktarılır; DB/cache/async job'a yazılmaz.
}
```

### `backend/account-service/src/main/java/com/cryptolab/account/application/service/AccountQueryService.java`

Service read-only orkestrasyon yapar. Network çağrısı etrafına DB @Transactional eklemeyiz; burada Account DB yoktur. Ownership'in asıl SQL filtresi Reporting'dedir; cevap kapsamı ikinci kez doğrulanır.

```java
package com.cryptolab.account.application.service;
import com.cryptolab.account.application.port.AccountReportReader;
import com.cryptolab.account.domain.exception.AccountFailure;
import com.cryptolab.account.domain.model.AccountSummary;
import com.cryptolab.account.domain.model.TradeHistory;
import org.springframework.stereotype.Service;
import java.util.Objects;
import java.util.UUID;
import static com.cryptolab.account.domain.exception.AccountFailure.Code.*;
@Service
public class AccountQueryService {
    private final AccountReportReader reports;
    public AccountQueryService(AccountReportReader reports) { this.reports = reports; }
    public AccountSummary summary(UUID owner, String bearer, UUID session) {
        var result = reports.summary(bearer, session);
        if (result == null) throw new AccountFailure(INVALID_REPORT);
        checkScope(owner,session,result.ownerId(),result.sessionId(),result.mode(),result.currency());
        if (result.coverage() == null) throw new AccountFailure(INVALID_REPORT);
        var amounts = new String[] { result.realizedPnl(), result.totalFees(), result.signedFunding(),
                result.liquidationFees(), result.netRealizedPnl(), result.unrealizedPnl(),
                result.totalNetPnl(), result.insuranceAdjustment() };
        boolean building = result.coverage() == AccountSummary.Coverage.BUILDING;
        for (String amount : amounts) {
            if (building ? amount != null : amount == null || !amount.matches("-?[0-9]+([.][0-9]+)?"))
                throw new AccountFailure(INVALID_REPORT);
        }
        if (building ? result.closedTradeCount() != null : result.closedTradeCount() == null
                || result.closedTradeCount() < 0 || result.projectionAsOf() == null)
            throw new AccountFailure(INVALID_REPORT);
        return result;
    }
    public TradeHistory trades(UUID owner, String bearer, UUID session, String cursor, int limit) {
        if (limit < 1 || limit > 100 || (cursor != null &&
                (cursor.length() > 2048 || !cursor.matches("[A-Za-z0-9_.-]+")))) {
            throw new AccountFailure(INVALID_QUERY);
        }
        var page = reports.trades(bearer, session, cursor, limit);
        if (page == null) throw new AccountFailure(INVALID_REPORT);
        checkScope(owner,session,page.ownerId(),page.sessionId(),page.mode(),page.currency());
        if (page.coverage()==null || page.items().size()>limit) throw new AccountFailure(INVALID_REPORT);
        if (session!=null && page.items().stream().anyMatch(t -> !session.equals(t.sessionId())))
            throw new AccountFailure(INVALID_REPORT);
        return page;
    }
    private void checkScope(UUID expectedOwner,UUID expectedSession,UUID owner,UUID session,String mode,String currency) {
        // Downstream cevabı bile kendi istek kapsamımızla eşleşmeli.
        if (!Objects.equals(expectedOwner,owner) || !Objects.equals(expectedSession,session)
                || !"LIVE_PAPER".equals(mode) || !"USDT".equals(currency)) throw new AccountFailure(INVALID_REPORT);
    }
}
```

### `backend/account-service/src/main/java/com/cryptolab/account/api/dto/response/AccountSummaryResponse.java`

Dış /me cevabında ownerId gerekli değildir. Finans değerleri string, veri yoksa null; format/para birimi UI'da gösterilir.

```java
package com.cryptolab.account.api.dto.response;
import java.time.Instant;
import java.util.UUID;
public record AccountSummaryResponse(UUID sessionId,String mode,String currency,
        String realizedPnl,String totalFees,String signedFunding,String liquidationFees,
        String netRealizedPnl,String unrealizedPnl,String totalNetPnl,String insuranceAdjustment,
        Long closedTradeCount,String coverage,Instant projectionAsOf,Long lagMs) {}
```

### `backend/account-service/src/main/java/com/cryptolab/account/api/dto/response/TradeHistoryResponse.java`

Domain Trade nesnesini doğrudan JSON'a açmamak için response tipi ayrı tutulur.

```java
package com.cryptolab.account.api.dto.response;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public record TradeHistoryResponse(UUID sessionId,String mode,String currency,List<TradeResponse> items,
        String nextCursor,String coverage,Instant projectionAsOf) {
    public TradeHistoryResponse { items=List.copyOf(items); }
    public record TradeResponse(UUID positionId,UUID sessionId,UUID instrumentId,String symbol,String side,
            String status,Instant openedAt,Instant closedAt,String realizedPnl,String totalFees,
            String signedFunding,String liquidationFees,String netRealizedPnl,String insuranceAdjustment) {}
}
```

### `backend/account-service/src/main/java/com/cryptolab/account/api/mapper/AccountMapper.java`

Mapper hiçbir kâr/zarar formülünü tekrar hesaplamaz. BUILDING null değerlerini 0 ile değiştirmez.

```java
package com.cryptolab.account.api.mapper;
import com.cryptolab.account.domain.model.AccountSummary;
import com.cryptolab.account.domain.model.TradeHistory;
import com.cryptolab.account.api.dto.response.AccountSummaryResponse;
import com.cryptolab.account.api.dto.response.TradeHistoryResponse;
import org.springframework.stereotype.Component;
@Component
public class AccountMapper {
    public AccountSummaryResponse toResponse(AccountSummary s) {
        return new AccountSummaryResponse(s.sessionId(),s.mode(),s.currency(),s.realizedPnl(),s.totalFees(),
                s.signedFunding(),s.liquidationFees(),s.netRealizedPnl(),s.unrealizedPnl(),s.totalNetPnl(),
                s.insuranceAdjustment(),s.closedTradeCount(),s.coverage().name(),s.projectionAsOf(),s.lagMs());
    }
    public TradeHistoryResponse toResponse(TradeHistory p) {
        var items=p.items().stream().map(t -> new TradeHistoryResponse.TradeResponse(t.positionId(),t.sessionId(),
                t.instrumentId(),t.symbol(),t.side(),t.status(),t.openedAt(),t.closedAt(),t.realizedPnl(),
                t.totalFees(),t.signedFunding(),t.liquidationFees(),t.netRealizedPnl(),t.insuranceAdjustment())).toList();
        return new TradeHistoryResponse(p.sessionId(),p.mode(),p.currency(),items,p.nextCursor(),
                p.coverage().name(),p.projectionAsOf());
    }
}
```

### `backend/account-service/src/main/java/com/cryptolab/account/api/controller/AccountController.java`

Jwt doğrulanmış principal'dır. URL'ye token konmaz. Controller'ın private key, SQL veya para hesabı yoktur.

```java
package com.cryptolab.account.api.controller;
import com.cryptolab.account.application.service.AccountQueryService;
import com.cryptolab.account.api.mapper.AccountMapper;
import com.cryptolab.account.api.dto.response.AccountSummaryResponse;
import com.cryptolab.account.api.dto.response.TradeHistoryResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController
@RequestMapping("/api/v1/account/me")
public class AccountController {
    private final AccountQueryService queries;
    private final AccountMapper mapper;
    public AccountController(AccountQueryService queries,AccountMapper mapper) { this.queries=queries;this.mapper=mapper; }
    @GetMapping("/summary")
    public AccountSummaryResponse summary(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) UUID sessionId) {
        return mapper.toResponse(queries.summary(UUID.fromString(jwt.getSubject()),jwt.getTokenValue(),sessionId));
    }
    @GetMapping("/trades")
    public TradeHistoryResponse trades(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) UUID sessionId,
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit) {
        return mapper.toResponse(queries.trades(UUID.fromString(jwt.getSubject()),jwt.getTokenValue(),sessionId,cursor,limit));
    }
}
```

### `backend/account-service/src/main/java/com/cryptolab/account/infrastructure/client/HttpAccountReportReader.java`

Hedef `reporting-service` kimliği deployment config'den gelir; Eureka registry'sinden seçilir. Kullanıcı URL'si veya serviceId kabul edilmez. Registry yerel güven sınırıdır. Redirect kapalıdır; token farklı origin'e taşınmaz. GET'te bile gizli retry döngüsü kurmuyoruz. BoundedBody bellek sınırı uygular; future bekleme süresi tüm gövdeyi kapsar. Yavaş/yarım kalan gerçek HTTP cevapları entegrasyon testinde ayrıca doğrulanır.

```java
package com.cryptolab.account.infrastructure.client;
import com.cryptolab.account.application.port.AccountReportReader;
import com.cryptolab.account.domain.model.AccountSummary;
import com.cryptolab.account.domain.model.TradeHistory;
import com.cryptolab.account.domain.exception.AccountFailure;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import static com.cryptolab.account.domain.exception.AccountFailure.Code.*;
@Component
public class HttpAccountReportReader implements AccountReportReader {
    private final HttpClient http;
    private final ObjectMapper json;
    private final LoadBalancerClient loadBalancer;
    private final String serviceId;
    private final Duration timeout;
    private final int maxBytes;
    public HttpAccountReportReader(ObjectMapper json,LoadBalancerClient loadBalancer,
            @Value("${account.reporting-service-id:reporting-service}") String serviceId,
            @Value("${account.connect-timeout-ms:2000}") int connectMs,
            @Value("${account.request-timeout-ms:5000}") int requestMs,
            @Value("${account.max-response-bytes:1048576}") int maxBytes) {
        if (connectMs<=0 || requestMs<=0 || maxBytes<1024 || maxBytes>4*1024*1024
                || !"reporting-service".equals(serviceId)) throw new IllegalArgumentException("INVALID_REPORTING_CONFIG");
        this.json=json;this.loadBalancer=loadBalancer;this.serviceId=serviceId;
        this.timeout=Duration.ofMillis(requestMs);this.maxBytes=maxBytes;
        this.http=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectMs))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public AccountSummary summary(String bearer,UUID session) {
        return get("/api/v1/reports/me/account-summary",bearer,session,null,null,AccountSummary.class);
    }
    public TradeHistory trades(String bearer,UUID session,String cursor,int limit) {
        return get("/api/v1/reports/me/trade-history",bearer,session,cursor,limit,TradeHistory.class);
    }
    private <T> T get(String path,String bearer,UUID session,String cursor,Integer limit,Class<T> type) {
        var instance=loadBalancer.choose(serviceId);
        if(instance==null)throw new AccountFailure(REPORT_UNAVAILABLE);
        var uri=UriComponentsBuilder.fromUriString("http://"+serviceId).replacePath(path);
        if(session!=null)uri.queryParam("sessionId",session);
        if(cursor!=null)uri.queryParam("cursor",cursor);
        if(limit!=null)uri.queryParam("limit",limit);
        var request=HttpRequest.newBuilder(loadBalancer.reconstructURI(instance,uri.build().encode().toUri())).timeout(timeout)
                .header("Authorization","Bearer "+bearer).header("Accept","application/json").GET().build();
        BoundedBody subscriber=new BoundedBody(maxBytes);
        var pending=http.sendAsync(request,info -> subscriber);
        try {
            // get timeout tüm response body tamamlanana kadar geçerlidir.
            var response=pending.get(timeout.toMillis(),TimeUnit.MILLISECONDS);
            int status=response.statusCode();
            if(status==401)throw new AccountFailure(UNAUTHORIZED);
            if(status==404 || status==403)throw new AccountFailure(NOT_FOUND);
            if(status==400)throw new AccountFailure(INVALID_QUERY);
            if(status!=200)throw new AccountFailure(REPORT_UNAVAILABLE);
            return json.readValue(response.body(),type);
        } catch(AccountFailure e) { throw e; }
          catch(InterruptedException e) { subscriber.cancel();pending.cancel(true);Thread.currentThread().interrupt();throw new AccountFailure(REPORT_UNAVAILABLE); }
          catch(TimeoutException e) { subscriber.cancel();pending.cancel(true);throw new AccountFailure(REPORT_UNAVAILABLE); }
          catch(ExecutionException e) {
              throw new AccountFailure(e.getCause() instanceof java.io.IOException ? REPORT_UNAVAILABLE : INVALID_REPORT);
          }
          catch(java.io.IOException e) { throw new AccountFailure(INVALID_REPORT); }

    }
}
```

### `backend/account-service/src/main/java/com/cryptolab/account/infrastructure/client/BoundedBody.java`

HTTP bellek ve süre sınırını görünür yapıyoruz. Bu teknik yardımcı domain'e veya application portuna sızmaz.

```java
package com.cryptolab.account.infrastructure.client;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
public final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final int limit;
    private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result=new CompletableFuture<>();
    private Flow.Subscription subscription;
    public BoundedBody(int limit) { if(limit<=0)throw new IllegalArgumentException("INVALID_LIMIT");this.limit=limit; }
    @Override public CompletionStage<byte[]> getBody() { return result; }
    @Override public synchronized void onSubscribe(Flow.Subscription s) {
        if(subscription!=null || result.isDone()) { s.cancel();return; }
        subscription=s;s.request(1);
    }
    @Override public synchronized void onNext(List<ByteBuffer> chunks) {
        if(result.isDone())return;
        for(var chunk:chunks) {
            if(chunk.remaining()>limit-bytes.size()) { fail(new IllegalStateException("RESPONSE_TOO_LARGE"));return; }
            byte[] part=new byte[chunk.remaining()];chunk.get(part);bytes.writeBytes(part);
        }
        subscription.request(1); // Kontrollü talep; sınırsız buffer büyümesi yok.
    }
    @Override public synchronized void onError(Throwable error) { result.completeExceptionally(error); }
    @Override public synchronized void onComplete() { result.complete(bytes.toByteArray()); }
    private void fail(Throwable error) { if(subscription!=null)subscription.cancel();result.completeExceptionally(error); }
    public synchronized void cancel() { fail(new CancellationException("REQUEST_CANCELLED")); }
}
```

### `backend/account-service/src/main/java/com/cryptolab/account/api/dto/response/AccountError.java`

Mevcut hata zarfı korunur. Aynı Java entity jar'ını bütün servislerle paylaşmak yerine sözleşme ortak tutulur.

```java
package com.cryptolab.account.api.dto.response;
import java.util.Map;
public record AccountError(String code,String message,String traceId,boolean retryable,Map<String,Object> details) {
    public AccountError { details=Map.copyOf(details); }
}
```

### `backend/account-service/src/main/java/com/cryptolab/account/api/exception/AccountErrors.java`

Controller exception'ları burada, token/filter hataları security config'de karşılanır. Reporting düşerse başarılı sıfır bakiye dönülmez.

```java
package com.cryptolab.account.api.exception;
import com.cryptolab.account.domain.exception.AccountFailure;
import com.cryptolab.account.api.dto.response.AccountError;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.Map;
import java.util.UUID;
@RestControllerAdvice
public class AccountErrors {
    @ExceptionHandler(AccountFailure.class)
    public ResponseEntity<AccountError> business(AccountFailure e) {
        int status=switch(e.code()) {
            case INVALID_QUERY -> 400;
            case UNAUTHORIZED -> 401;
            case NOT_FOUND -> 404;
            case REPORT_UNAVAILABLE -> 503;
            case INVALID_REPORT -> 502;
        };
        return response(status,e.code().name(),status==503);
    }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<AccountError> malformed() { return response(400,"INVALID_QUERY",false); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<AccountError> unexpected() { return response(500,"INTERNAL_ERROR",false); }
    private ResponseEntity<AccountError> response(int status,String code,boolean retry) {
        return ResponseEntity.status(status).header("Cache-Control","no-store").body(new AccountError(code,
                "İstek tamamlanamadı.",UUID.randomUUID().toString(),retry,Map.of()));
    }
}
```

### `backend/account-service/src/main/java/com/cryptolab/account/infrastructure/config/AccountSecurityConfig.java`

İmza + alg + issuer + audience + expiry/nbf + UUID subject kontrolü. Yeni private key, ikinci token imzalayıcısı veya özel JWT parsing filtresi yok. Reporting kendi eşdeğer resource-server doğrulamasını uygular. Mevcut JWKS/issuer config güvenilir deployment kaynağı olmalı; üretimde TLS kullanılır.

```java
package com.cryptolab.account.infrastructure.config;
import com.cryptolab.account.api.dto.response.AccountError;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import java.util.Map;
import java.util.UUID;
@Configuration
public class AccountSecurityConfig {
    @Bean JwtDecoder accountDecoder(@Value("${jwt.jwks-url}") String jwks,
            @Value("${jwt.issuer}") String issuer,@Value("${jwt.audience}") String audience) {
        var decoder=NimbusJwtDecoder.withJwkSetUri(jwks).jwsAlgorithm(SignatureAlgorithm.RS256).build();
        OAuth2TokenValidator<Jwt> claims=jwt -> {
            boolean valid=jwt.getExpiresAt()!=null && jwt.getAudience().contains(audience);
            try { valid=valid && UUID.fromString(jwt.getSubject()).toString().equalsIgnoreCase(jwt.getSubject()); }
            catch(RuntimeException invalidSubject) { valid=false; }
            return valid ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),claims));
        return decoder;
    }
    @Bean SecurityFilterChain accountSecurity(HttpSecurity http,ObjectMapper json) throws Exception {
        // Account yalnız Authorization Bearer ile GET kabul eder. Cookie oturumu/login yok.
        // Bu ayarı Identity refresh/logout CSRF politikasına kopyalama.
        http.csrf(c -> c.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(f -> f.disable()).httpBasic(b -> b.disable())
                .authorizeHttpRequests(a -> a.requestMatchers("/actuator/health").permitAll()
                    .requestMatchers(HttpMethod.GET,"/api/v1/account/me/summary","/api/v1/account/me/trades").authenticated()
                    .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> {})
                    .authenticationEntryPoint((req,res,e) -> {
                        res.setStatus(401);res.setContentType("application/json");res.setHeader("Cache-Control","no-store");
                        res.setHeader("WWW-Authenticate","Bearer");
                        json.writeValue(res.getOutputStream(),new AccountError("UNAUTHORIZED","Oturum gerekli.",UUID.randomUUID().toString(),false,Map.of()));
                    }))
                .exceptionHandling(e -> e.accessDeniedHandler((req,res,ex) -> {
                    res.setStatus(403);res.setContentType("application/json");res.setHeader("Cache-Control","no-store");
                    json.writeValue(res.getOutputStream(),new AccountError("FORBIDDEN","İşleme izin verilmedi.",UUID.randomUUID().toString(),false,Map.of()));
                }));
        // Spring Security varsayılan cache headers korunur; kullanıcının raporu public cache'e verilmez.
        return http.build();
    }
}
```

## Reporting repository için somut sorgu örneği

Aşağıdaki SQL Reporting'in kendi DB'sinde, owner ve mode/currency metadata bootstrap'ı tamamlandıktan sonra çalışır. Account içinde çalıştırılmaz. Mevcut session_owners tablosuna mode/currency metadata'sı eklemek additive migration gerektirir; aşağıda V2 örneği var. Dosya adı mevcut uygulamada doluysa sıradaki boş Flyway sürümünü seç; uygulanmış migration'ı düzenleme.

### `backend/reporting-service/src/main/resources/db/migration/V2__account_scope.sql`

```sql
-- Varsayılan mod/para birimi atamak yerine audit manifestinden doğrula ve doldur.
ALTER TABLE session_owners ADD COLUMN mode text;
ALTER TABLE session_owners ADD COLUMN currency text;
ALTER TABLE trade_summaries ADD COLUMN instrument_id uuid;
ALTER TABLE trade_summaries ADD COLUMN symbol text;
ALTER TABLE trade_summaries ADD COLUMN side text;
ALTER TABLE trade_summaries ADD COLUMN status text;
ALTER TABLE trade_summaries ADD COLUMN opened_at timestamptz;
CREATE INDEX owner_session_lookup ON session_owners(owner_id,mode,currency,session_id);
CREATE INDEX trade_history_seek ON trade_summaries(opened_at DESC,position_id DESC);
-- Bu migration tek başına coverage=READY yapmaz; bootstrap/metadata consumer tamamlanmalıdır.
```

### `Reporting AccountSummaryQueryRepository — aggregate SQL`

NamedParameterJdbcTemplate ile UUID/null parametrelerini bağla. Owner filtresini hiçbir rol için kaldırma. Bu örnek realized bileşenleridir; unrealized, coverage ve full owner manifest doğrulaması tamamlanmadan AccountSummaryResponse üretme.

```sql
SELECT coalesce(sum(t.gross),0) AS realized_pnl,
       coalesce(sum(t.fees),0) AS total_fees,
       coalesce(sum(t.funding),0) AS signed_funding,
       coalesce(sum(t.liquidation_fees),0) AS liquidation_fees,
       coalesce(sum(t.gross-t.fees+t.funding-t.liquidation_fees),0) AS net_realized_pnl,
       coalesce(sum(t.insurance),0) AS insurance_adjustment,
       count(*) FILTER (WHERE t.status IN ('CLOSED','LIQUIDATED')) AS closed_trade_count
FROM trade_summaries t
JOIN session_owners s ON s.session_id=t.session_id
WHERE s.owner_id=:subject AND s.mode='LIVE_PAPER' AND s.currency='USDT'
  AND (CAST(:sessionId AS uuid) IS NULL OR s.session_id=CAST(:sessionId AS uuid));
```

Toplamlar ile coverage aynı transaction snapshot'ında okunur; Reporting read-only service için gerekiyorsa REPEATABLE_READ seçilir. Account HTTP çağrısı DB transaction'ına sarılmaz. `coalesce(...,0)` yalnız kapsamın tam ve boş olduğunun ayrıca kanıtlandığı durumda doğru kullanıcı sonucudur.

## Güvenlik ve frontend davranışı

- `/account/me` için access token Authorization header'dadır; refresh token HttpOnly cookie ile yalnız Identity refresh/logout'a gider.
- Account ve Reporting bearer doğrular. Frontend/gateway tarafından yollanan ownerId veya X-User-Id yetki vermez.
- CORS allowlist Gateway'de yönetilir. Account doğrudan tarayıcıya açılmıyorsa ayrıca geniş CORS ekleme.
- Identity private key'i yalnız Identity'de tut. Diğer servisler public JWKS okur. Anahtar rotation'da aktif eski token süresi kadar önceki public key tutulur.
- Logout refresh ailesini iptal eder. Mevcut access token'ın TTL bitmeden otomatik iptal olduğunu söyleme; daha sert iptal gerekiyorsa ayrı revocation/introspection politikası tasarla.
- JWT'yi localStorage'da kalıcı tutmak yerine frontend access token'ı bellekte tutar; refresh cookie akışıyla yeniler. URL/query/log/telemetry'ye token yazılmaz.
- Refresh/logout cookie taşıdığı için Origin/CSRF ve cookie politikası Identity'de korunur. Bearer-only Account'un csrf.disable örneğini tüm servislere gelişigüzel kopyalama.
- Account API'si hesap silme/reset/bakiye değiştirme endpoint'i eklemez. Mevcut session reset yeni session yaratır; geçmişi silmez.

Frontend Hesabım ekranı: “Net gerçekleşen”, “Açık pozisyon K/Z”, “Toplam net K/Z”, “Sigorta düzeltmesi” ayrı kartlar; altında position bazlı sayfalı işlem geçmişi. STALE için son güncelleme zamanı, BUILDING için “Geçmiş hazırlanıyor” göster. Error/503'ü 0 kâr diye gösterme. Decimal hesaplarını JS number ile yeniden üretme; sunucu sonucunu biçimlendir.

## Kabul testleri — gerçekten çalıştırınca işaretle

1. Tokensız/expired/yanlış issuer/yanlış audience/yanlış imzalı JWT: 401; bozuk UUID sub: 401.
2. Kullanıcı A'nın token'ı + B'nin sessionId'si: Reporting owner filtresi 404; hiçbir trade/summary sızmaz.
3. Summary kapsamı aynı kullanıcıya ait tüm LIVE_PAPER/USDT session'ları içerir. Replay ve başka currency eklenmez.
4. Yukarıdaki 100/10.05/-1/-20 örneği: netRealized 88.95, totalNet 68.95; insurance ayrı.
5. Aynı LedgerTransactionPosted iki kez: toplam aynı. PositionClosed: PnL ikinci kez eklenmez.
6. Açık pozisyon fee/funding ve partial close toplamda; tam kapanış sayısı yalnız kapanınca artar.
7. Geç funding aynı trade toplamını düzeltir, history cursor sırası değişmez. İmzalı cursor başka owner veya session'da reddedilir.
8. Reporting kapalı/timeout/oversized response: güvenli 5xx; başarılı boş liste veya sıfır kâr yok.
9. Account restart: geçmiş durur; çünkü kalıcı kayıt sahibi Reporting/Simulation'dır. Rebuild sırasında BUILDING görünür.
10. Limit 0/101, bozuk UUID/cursor: 400. 20 satırlık sayfanın toplamı bütün hesap toplamı sayılmaz.
11. Body yavaş akıyorsa bütün çağrı timeout'u iptal eder; token loglanmaz/redirect'e taşınmaz.
12. Repository bootstrap henüz tamamlanmadıysa bu dersi DONE işaretleme; bu MD uygulama reposunun çalıştığı iddiası değildir.

## Kendi cümlelerinle anlat

“JWT neden Identity'de, Account neden ayrı DB'siz, geçmiş neden Reporting'de; buna rağmen kullanıcı Hesabım ekranında neden tek bir bütün görüyor?”

## Sonraki adım ve sınır

Bu derste Account'un sınıfları ve güvenlik istemcisi verildi. Reporting owner-wide endpoint/repository, metadata bootstrap, cursor imzası ve coverage manifestinin bağlanması B08A.4 uygulama işidir; önceki B08 projection eksikleri de kapanmalıdır. B01–B12'nin mevcut açık işleri devam eder. Mevcut Boot 4.1.1 projen için framework uyarlaması hâlâ ayrı doğrulama gerektirir.


## DISC-01 bağlantısı

Reporting portu kodda sabit değildir. Kayıt yoksa 503; eski registry kaydı erişilemiyorsa timeout/503. Tek seçim/tek gönderim vardır; uygulama retry döngüsü yoktur. B01A port değişimi testi bu client için zorunludur. Issuer ve JWKS adresleri serviceId ile değiştirilmez.
