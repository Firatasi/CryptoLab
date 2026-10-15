# B01A — Eureka ve localhost servis keşfi

Revizyon 4 · DISC-01 · B01’den sonra, B02’den önce uygulanır.

## Amaç ve sınır

Eureka, **Spring Cloud Netflix** bileşenidir. Servislerin isim/adres/kayıt durumunu tutar. Dashboard servisleri başlatan/durduran bir yönetici, Docker yerine geçen orchestrator, config server veya Kafka değildir. Uygulamaları IDE/Maven ile sen başlatırsın. Frontend yine Gateway’e gider; HTTP trafiği Eureka üzerinden proxy edilmez.

Bu ek mevcut MVC Gateway’i, endpoint isimlerini, JWT/owner kontrollerini ve finans transaction sınırını korur. Yeni `discovery-server` yalnız teknik altyapıdır: port 8761; iş DB’si, domain entity’si veya ledger repository’si yoktur. Toplam **10 Maven modülü / 8 çalışan Spring Boot süreci** vardır. Bunların yedisi mevcut servis, biri registry’dir.

- [ ] **B01A.1** Boot/Cloud sürüm hattını seç ve effective POM’u doğrula.
- [ ] **B01A.2** discovery-server modülünü ve başlangıç sınıfını ekle.
- [ ] **B01A.3** Yedi uygulamanın Eureka kayıt ayarlarını ekle.
- [ ] **B01A.4** Gateway serviceId çözümlemesini bağla.
- [ ] **B01A.5** Account → Reporting çağrısını discovery’ye bağla.
- [ ] **B01A.6** Localhost adres/port/health ve güven sınırını doğrula.
- [ ] **B01A.7** Başlatma, port değişimi ve kesinti testlerini çalıştır.
- [ ] **B01A.8** Sonuçları kaydet; asıl backend entegrasyonu ile registry testini ayır.

## B01A.1 — Sürüm uyumu: iki hattı karıştırma

| Kapsam | Boot parent | Cloud BOM | Karar |
|---|---|---|---|
| Senin mevcut proje POM’un | 4.1.1 | 2025.1.2 | Boot parent’ı koru; Eureka için bu BOM’u kullan |
| Önceki derslerin Jackson 2 eğitim tabanı | 3.5.16 | 2025.0.3 | Yalnız bu eğitim tabanında; gerçek projeni buraya düşürme |

Resmî uyumluluk tablosu Boot 4.1.x desteğinin Cloud 2025.1.2 ile başladığını belirtir. Burada sürüm sabitlenmiştir; “en son sürüm” iddiası yoktur. Cloud 2025.0.x’i Boot 4.1.1 ile birleştirme. Compatibility verifier’ı kapatarak hatayı gizleme. BOM bir dependency eklemez, eklenen Cloud bileşenlerinin sürümlerini yönetir.

**Mevcut Boot 4.1.1 kök POM’una birleştirilecek bölüm:** Var olan properties/dependencyManagement bloklarını ikinci kez açma. Parent, groupId ve diğer bağımlılıklarını koru.

```xml
<properties>
    <java.version>21</java.version>
    <spring-cloud.version>2025.1.2</spring-cloud.version>
</properties>
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-dependencies</artifactId>
            <version>${spring-cloud.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

B01’deki tam eğitim POM’u 3.5.16/2025.0.3 eşleşmesini içerir. Bu ekin Eureka sınıfları Jackson API’sine bağlı değildir; önceki derslerdeki Jackson 2 kodları ise Boot 4’e geçerken ayrı uyarlanmalıdır. **Eureka uyumu, bütün eski backend kodlarının Boot 4 ile derlendiği anlamına gelmez.**

Root `<modules>` listesine `discovery-server` eklendi. Eureka client starter’ı yalnız yedi çalışan uygulamada bulunur. `finance-core` ve `test-support` discovery client değildir. LoadBalancer starter’ı şu an hedef seçen Gateway ve Account’ta bulunur.

```xml
<!-- Yedi iş/giriş uygulamasında; starter sürümü BOM’dan gelir. -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
</dependency>
<!-- Gateway ve Account’ta ayrıca: -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-loadbalancer</artifactId>
</dependency>
```

## B01A.2 — discovery-server dosyaları

### `backend/discovery-server/pom.xml`

Parent’ın örnek groupId’si `com.cryptolab`; kendi kökün farklıysa bütün child parent referanslarıyla aynı kullan. Bu child, root’taki seçilmiş Boot/Cloud hattını devralır.

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.cryptolab</groupId><artifactId>backend</artifactId>
        <version>1.0.0-SNAPSHOT</version><relativePath>../pom.xml</relativePath>
    </parent>
    <artifactId>discovery-server</artifactId>
    <dependencies>
        <dependency><groupId>org.springframework.cloud</groupId><artifactId>spring-cloud-starter-netflix-eureka-server</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
    </dependencies>
    <build><plugins><plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build>
</project>
```

### `backend/discovery-server/src/main/java/com/cryptolab/discovery/DiscoveryServerApplication.java`

```java
package com.cryptolab.discovery;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;
@SpringBootApplication
@EnableEurekaServer
public class DiscoveryServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(DiscoveryServerApplication.class,args);
    }
}
```

### `backend/discovery-server/src/main/resources/application.properties`

```properties
spring.application.name=discovery-server
server.address=127.0.0.1
server.port=8761
eureka.instance.hostname=localhost
eureka.client.register-with-eureka=false
eureka.client.fetch-registry=false
eureka.client.service-url.defaultZone=http://localhost:8761/eureka/
eureka.server.enable-self-preservation=true
management.endpoints.web.exposure.include=health,info
management.endpoint.health.show-details=never
```

Bu **yalnız kendi bilgisayarındaki loopback ağı** için parola içermeyen yerel ayardır. `server.address=127.0.0.1` kaldırılıp LAN/public ağa açılacak bir deploy reçetesi değildir. Registry kaydı hedef adresi etkiler; bilgisayarındaki registry’ye yazabilen süreçler güven sınırının içindedir. Gerçek ağ dağıtımında ağ kısıtlaması/TLS/registry authentication ayrıca tasarlanır. Eureka’ya uygulama kullanıcısının JWT’si gönderilmez.

Server security eklenirse client kayıt/heartbeat çağrıları için `/eureka/**` CSRF istisnası ve makine authentication gerekir; tüm iş servislerinde CSRF’yi kapatmak çözüm değildir. Bu localhost örneğinde registry modülüne security starter eklenmedi. Self-preservation’ı hata çözümü diye kapatma.

## B01A.3 — Yedi client’ın properties ayarları

| spring.application.name | Varsayılan port |
|---|---:|
| api-gateway | 8080 |
| identity-service | 8081 |
| market-data-service | 8082 |
| simulation-service | 8083 |
| strategy-service | 8084 |
| reporting-service | 8085 |
| account-service | 8086 |

İsimler B01’deki application.properties dosyalarında sabittir. Dashboard büyük harfle gösterebilir; kod/config kimlikleri tabloda yazıldığı gibidir. Client starter auto-configuration sağlar; uygulama sınıflarına `@EnableEurekaClient` eklemek gerekmez.

Her uygulamanın mevcut application.properties dosyasına şu anahtarlar bir kez birleştirilir; B01 örnekleri zaten güncellenmiştir:

```properties
eureka.client.service-url.defaultZone=${EUREKA_URL:http://localhost:8761/eureka/}
eureka.client.register-with-eureka=true
eureka.client.fetch-registry=true
eureka.client.healthcheck.enabled=true
eureka.instance.prefer-ip-address=true
eureka.instance.ip-address=127.0.0.1
eureka.instance.instance-id=${spring.application.name}:${server.port}
eureka.instance.health-check-url-path=/actuator/health
eureka.instance.status-page-url-path=/actuator/info
```

`defaultZone` büyük/küçük harfe duyarlı map anahtarıdır; `default-zone` yazma. `server.address=127.0.0.1` ile registry’ye duyurulan IP burada aynı erişim alanındadır. Host üzerinde çalışan JVM’ler için sabit loopback, VPN/virtual adapter IP’sinin yanlış seçilmesini önler. Health bilgisi Actuator’dan yayımlanır; DB kapalıysa servis UP görünmeyebilir. Health URL’lerinin kaydı, Eureka’nın her seferinde bu URL’yi HTTP ile yokladığı anlamına gelmez.

Health endpoint’i yalnız gerekli ayrıntısız görünümle erişilir; tüm `/actuator/**` yolları public yapılmaz. `health,info` dışındaki yönetim endpoint’lerini gelişigüzel açma. Health UP olması schema/bootstrap/iş uçlarının tamamlandığını kanıtlamaz.

Gateway ve Account’ta ayrıca:

```properties
spring.cloud.loadbalancer.cache.enabled=false
spring.cloud.loadbalancer.retry.enabled=false
```

Eureka client zaten registry cache tutar; ek LoadBalancer cache katmanını burada kapatıyoruz. HTTP client’lar tek gönderim yapar; yazma isteklerine otomatik retry eklenmez. Discovery kaydının kalkması anlık değildir; heartbeat, registry cache ve self-preservation nedeniyle kısa süre eski örnek görülebilir.

**Saf unit test profili:** application-test.properties içinde `eureka.client.enabled=false` kullanılabilir. Discovery testi için bu profil kullanılmaz; servis seçen sınıfların unit testinde LoadBalancerClient fake/mock sağlanır. Testte registry kapalıyken production’da fixed-port fallback açılmaz.

## B01A.4 — Gateway: route allowlist + discovery

Mevcut MVC `ProxyController` korunur. B08 `RouteTable` artık port değil serviceId döndürür. İstemcinin header/query değerinden servis adı alınmaz. `/eureka/**` veya registry’de görünen her servis otomatik public route olmaz. `lb://...` URI’si JDK HttpClient’e doğrudan verilmez; aşağıdaki resolver gerçek HTTP URI üretir.

### `backend/api-gateway/src/main/java/com/cryptolab/apigateway/infrastructure/client/GatewayTargetResolver.java`

```java
package com.cryptolab.apigateway.infrastructure.client;
import com.cryptolab.apigateway.infrastructure.config.RouteTable;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.net.URI;
@Component
public class GatewayTargetResolver {
    private final LoadBalancerClient loadBalancer;
    public GatewayTargetResolver(LoadBalancerClient loadBalancer) { this.loadBalancer=loadBalancer; }
    public URI resolve(String rawPath,String rawQuery) {
        final String serviceId;
        try { serviceId=RouteTable.serviceId(rawPath); }
        catch(IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.NOT_FOUND); }
        var instance=loadBalancer.choose(serviceId);
        if(instance==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        // Host kullanıcıdan gelmez; RouteTable sabit allowlist'tir. Raw query yeniden encode edilmez.
        try {
            var logical=URI.create("http://"+serviceId+rawPath+(rawQuery==null?"":"?"+rawQuery));
            if(logical.getRawFragment()!=null)throw new IllegalArgumentException("INVALID_QUERY");
            return loadBalancer.reconstructURI(instance,logical);
        } catch(IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
    }
}
```

### `backend/api-gateway/src/main/java/com/cryptolab/apigateway/api/exception/GatewayErrors.java`

Registry boşken veya eski örnek erişilemezken 500/HTML yerine güvenli hata zarfı. SSE cevabı başladıktan sonra yeni JSON hata gövdesi yazılmaz; bağlantı tamamlanamazsa istemci reconnect/resync yolunu kullanır.

```java
package com.cryptolab.apigateway.api.exception;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
@RestControllerAdvice
public class GatewayErrors {
    @ExceptionHandler({ResponseStatusException.class,IOException.class,InterruptedException.class})
    public Map<String,Object> unavailable(Exception error,HttpServletResponse response) {
        if(error instanceof InterruptedException)Thread.currentThread().interrupt();
        if(response.isCommitted())return null;
        int status=error instanceof ResponseStatusException e ? e.getStatusCode().value() : 503;
        String code=status==404?"NO_ROUTE":status==400?"INVALID_QUERY":"SERVICE_UNAVAILABLE";
        response.setStatus(status);
        response.setHeader("Cache-Control","no-store");
        return Map.of("code",code,"message","İstek tamamlanamadı.","traceId",UUID.randomUUID().toString(),
                "retryable",status==503,"details",Map.of());
    }
}
```

B08’de ProxyController constructor’ına GatewayTargetResolver enjekte edilir. Önceki header allowlist, auth, cookie/CSRF, Idempotency-Key ve SSE davranışı korunur. `retryable=true` yeni emir üretme izni değildir; timeout bir işlemin gerçekleşmediğini kanıtlamaz. Yazma sonucunu mevcut idempotency protokolüyle sorgula/tekrar et.

Bu ek Spring Cloud Gateway WebFlux veya OpenFeign eklemez. Mevcut MVC + JDK HTTP adaptörünü değiştirmeden Spring Cloud LoadBalancerClient kullanır. Böylece aynı modülde iki farklı gateway stack’i oluşmaz. B08’in sınırlı proxy eğitim örneğinin streaming/read-idle/connection limit testleri hâlâ gerekir.

## B01A.5 — Account → Reporting ve diğer bağlantılar

B08A’daki HttpAccountReportReader artık `LoadBalancerClient` ile `reporting-service` seçer. Port 8085 constructor/config hedefi değildir. `account.reporting-base-url` kaldırıldı; `account.reporting-service-id=reporting-service` kullanılır. `choose` sonucu null ise REPORT_UNAVAILABLE/503; bozuk JSON INVALID_REPORT/502. Bearer aktarımı yalnız seçilen güvenilir Reporting örneğine yapılır, redirect kapalıdır. Tam body timeout ve byte sınırı korunur.

| Bağlantı | Bu revizyondaki yöntem |
|---|---|
| Frontend → Gateway | Sabit dış adres localhost:8080 |
| Gateway → altı iş servisi | Route allowlist + Eureka + LoadBalancer |
| Account → Reporting | Eureka + LoadBalancer |
| Servisler → Eureka | EUREKA_URL; bootstrap adresi sabit/config |
| Servisler → PostgreSQL/Kafka/Redis | Mevcut altyapı bağlantıları; Eureka’ya taşınmaz |
| Strategy → AI / training provider | Ayrı paketin mevcut provider URL sözleşmesi |
| Market → public provider | Mevcut provider URL’si; Eureka kaydı yok |
| JWT issuer / JWKS | Mevcut sabit güven sözleşmesi; aşağıdaki açıklama |

**JWT issuer bir servis adresi keşif alanı değildir.** Mevcut token’ların `iss` değeri ve bütün validator ayarları aynı kalır. `jwt.issuer` değerini `lb://identity-service` yapma. JWK decoder düz HTTP URL bekler; mevcut `jwt.jwks-url` bu localhost rehberinde Identity’nin sabit 8081 adresidir. Discovery nedeniyle auth trust anchor’ını rastgele registry kaydına bağlamıyoruz. Identity portu değiştirilecekse tüm JWKS URL ayarları açıkça güncellenir; issuer string’i sırf port değişti diye değiştirilmez. Bu yüzden port değişimi kabul testi Reporting üzerinde yapılır.

Gelecekte başka backend içi senkron client yazılırsa serviceId seçimi infrastructure.client altında yapılır; domain/application’a Eureka sınıfı girmez. Ortak business entity/repository jar’ı eklenmez. Bütün servisleri paralel çoğaltmak için sadece registry yeterli değildir: worker sahipliği, Kafka consumer group, idempotency, DB lock, SSE oturumu gibi mevcut kurallar ayrıca korunmalıdır.

## B01A.6 — Localhost ile Docker ayrımı

Bu rehberin çalıştırma modeli: **Spring JVM’leri host/IDE üzerinde; PostgreSQL/Kafka/Redis mevcut Compose altyapısında.** Eureka da host üzerinde 8761’de çalışır. JDBC/Kafka advertised listener ayarları önceki host çalışma modeliyle aynı kalır.

Container içindeki localhost o container’dır. Bütün Spring servislerini daha sonra Compose’a taşırsan ayrı profil gerekir: bind adresi, registry URL’si ve duyurulan servis adresi container ağına göre birlikte değiştirilir. Yalnız EUREKA_URL değiştirmek yeterli değildir. Loopback kayıtlarını container’lara duyurma. Bu revizyon ikinci, denenmemiş bir container topolojisini localhost ayarlarına karıştırmaz.

## B01A.7 — Başlatma ve doğrulama

Windows/PowerShell örnekleri backend kökünde çalıştırılır. Her uzun süre çalışan uygulama ayrı terminal/IDE run configuration kullanır. Root’ta `-am spring-boot:run` ile finance-core gibi kütüphaneleri çalıştırmaya kalkma.

```powershell
java -version
mvn -version
mvn -pl discovery-server help:effective-pom
mvn -pl api-gateway,account-service dependency:tree "-Dincludes=org.springframework.cloud:*"
mvn clean install
mvn -pl discovery-server spring-boot:run
```

İlk tam install henüz derslerin diğer eksikleri nedeniyle geçmiyorsa bunu Eureka arızası diye gizleme. Registry iskeletini bağımsız derlemek için `mvn -pl discovery-server -am package` kullan; asıl reactor kapısı açık kalır. `-DskipTests` ile geçen build’i test başarısı sayma.

Başlatma sırası: altyapı → discovery-server → Identity/Market/Simulation/Strategy/Reporting/Account → Gateway. B01 iskeletinde DB bağımlılıkları henüz eklenmemiş olabilir; dersler ilerledikçe gerekli altyapı ayarları uygulanır. Kayıtların görünmesi asenkron olduğundan sabit iki saniye bekleyip başarılı varsayma; log ve registry sonucunu kontrol et.

```powershell
# Her komutu ayrı terminalde çalıştır.
mvn -pl identity-service spring-boot:run
mvn -pl market-data-service spring-boot:run
mvn -pl simulation-service spring-boot:run
mvn -pl strategy-service spring-boot:run
mvn -pl reporting-service spring-boot:run
mvn -pl account-service spring-boot:run
mvn -pl api-gateway spring-boot:run
```

```powershell
Invoke-RestMethod http://localhost:8761/actuator/health
$registry = Invoke-RestMethod http://localhost:8761/eureka/apps -Headers @{Accept='application/json'}
$registry.applications.application | Select-Object name
```

Dashboard: `http://localhost:8761`. Beklenen yedi client uygulaması; discovery-server kendini kaydetmediği için sekizinci client bekleme. Finance-core/test-support görünmemeli.

### Port değişimi testi: sabit port kalmadığını kanıtla

1. Reporting’i düzgün şekilde durdur; Account ve Gateway açık kalsın.
2. Reporting’i aşağıdaki komutla 8185’te başlat.
3. Registry/client cache yeni örneği gördükten sonra önceden çalışan yetkili Account summary/trades ve Gateway reports isteğini tekrar dene.
4. Gateway veya Account config’inde 8085→8185 değiştirmeden isteğin yeni örneğe gittiğini log/erişim kanıtıyla doğrula. Eski kayıt geçici olarak görünürse failure olabilir; hiçbir hata sıfır rapora çevrilmemeli.

```powershell
mvn -pl reporting-service spring-boot:run "-Dspring-boot.run.arguments=--server.port=8185"
```

Gerçek Reporting endpoint’leri ve veri bootstrap’ı henüz yazılmadıysa bu adım uçtan uca PASS değildir. Yalnız registry’de 8185 görünmesini “Account geçmişi çalıştı” diye kaydetme.

### Kesinti ve güvenlik kabul tablosu

| Senaryo | Beklenti |
|---|---|
| Registry’de hedef yok | 503; fixed-port fallback yok |
| Reporting süreç kaybı/eski kayıt | Timeout/503; sahte sıfır rapor yok |
| Registry durur | Cache’lenmiş adresler bir süre çalışabilir; yeni keşif garantisi yok |
| Registry yeniden başlar | Client’lar yeniden kayıt/fetch ile toparlar; finans kaydı değişmez |
| Uydurma serviceId/header | Gateway allowlist dışına çıkılmaz |
| Geçersiz JWT / başka owner | Discovery olsa da 401/403 veya sahipliği gizleyen 404 |
| POST timeout | Otomatik yeni emir yok; aynı idempotency protokolü |
| Reporting DB kapalı | Sağlık/başlatma hatası görünür; registry UP işlevsellik kanıtı değildir |
| SSE akışı kesilir | Reconnect/resync; geçmiş ledger’dan kaybolmaz |

## B01A.8 — Sık sorunlar ve tamamlanma kaydı

| Belirti | Kontrol |
|---|---|
| Compatibility verifier hatası | Boot/Cloud tablosu ve effective POM; verifier kapatma |
| Client dashboard’da yok | Starter, doğru defaultZone, isim, port 8761 ve kayıt logu |
| Servis UP ama çağrı yapılamıyor | Duyurulan IP/port, localhost/container ayrımı, uygulama endpoint’i |
| LoadBalancerClient bean yok | Gateway/Account loadbalancer starter ve Cloud BOM |
| JDK HttpClient unknown protocol | `lb://` URI verilmiş; resolver ile gerçek HTTP URI üret |
| No instances available | Fetch tamamlandı mı, servis adı doğru mu, health durumu UP mı? |
| Kapanan servis hâlâ görünüyor | Graceful shutdown, heartbeat/cache ve self-preservation; DB silme yok |
| 401/403 kayıt hatası | Registry’ye sonradan security eklendi mi, makine credentials/CSRF politikası? |
| NoSuchMethod/ClassNotFound | Cloud starter sürümlerini elle karıştırma; dependency tree incele |

Kayda ayrı ayrı yaz: sürüm çifti, sekiz sürecin açılışı, yedi client kaydı, port değişimi sonucu, registry kesinti sonucu, JWT/owner testleri ve tam Maven/DB entegrasyon durumu. Gerçekten çalıştırılmayan adımlar NOT_RUN kalır.

## Resmî başvuru kaynakları

14.10.2026 tarihinde kontrol edildi. İş/veri sahipliği kararları bu projenin kendi mimarisidir.

- [Spring Cloud sürüm uyumluluğu](https://spring.io/projects/spring-cloud/)
- [Spring Cloud Netflix: client/server, health ve standalone](https://docs.spring.io/spring-cloud-netflix/reference/spring-cloud-netflix.html)
- [Eureka configuration properties](https://docs.spring.io/spring-cloud-netflix/reference/configprops.html)
- [Spring Cloud Commons: LoadBalancerClient](https://docs.spring.io/spring-cloud-commons/reference/spring-cloud-commons/common-abstractions.html)
- [Spring Cloud LoadBalancer](https://docs.spring.io/spring-cloud-commons/reference/spring-cloud-commons/loadbalancer.html)
