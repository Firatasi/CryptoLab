# B01 — Ortam, paket sınırları ve kontrakt başlangıcı

> **DISC-01:** Eureka/local adres çözümlemesi için B01A-Eureka-ve-Servis-Kesfi.md uygulanır. Uygulama ayarları properties olarak kalır; iş kuralları değişmez.

Şartname: 3.0 · Contract: 1.0.0 · Öğretici kod referansı · Durum: BUILD_UNVERIFIED

ACC-01 eki: Account, servis sahipliği ve properties standardı için [güncel mimari eki](03-ACCOUNT-VE-GUVENLIK-EKI.md) geçerlidir.
İlk görevde iş mantığı yazmıyoruz. Account 8086 portunda başlangıç iskeleti olarak eklenir; B08A bu iskeleti geliştirir. Önce tek proje içinde bağımsız çalışacak sekiz Spring Boot sürecini (Eureka dahil), iki yardımcı Maven modülünü ve ortak JSON sözleşmelerini oluşturacağız. Maven modülü bir klasör ve pom.xml’dir; mikroservis ise ayrı çalışan süreçtir. finance-core bir mikroservis değildir.

Bu dosyadaki kodları kendi projenize sırayla elle yazın. Burada uygulama reposu kurulmuş veya kabul testleri geçmiş değildir. Dosya yolları CryptoLab köküne göredir. Bir sınıfı yazdıktan sonra küçük deneyi yapın; tüm belgeyi tek hamlede kopyalamayın.

## Bu dersi nasıl okuyacaksın? — Proje iskeletini anlamak

**Önce kavram:** Bir Maven modülü build birimidir, mikroservis bağımsız çalışan süreçtir. finance-core ve test-support HTTP sunucusu değildir. Yedi Application sınıfı ise farklı process/portlarda çalışır.

**Yazma sırası:** POM → modül kökü → Application → application.properties → contracts → ilk build. Henüz dto/repository sınıfına ihtiyaç yoksa boş sınıf üretme.

**Neden bu ayrım var?** Parent sürüm yönetir; dependencyManagement tek başına kütüphane eklemez. Application sınıfı tarama kökünün üstünde kalır. Domain klasörünü başka servisin component scan kapsamına dahil etme.

**Küçük pratik:** Bir servisin portunu değiştir ve sadece o servisin adresinin değiştiğini gör. Finance-core dependency ağacında Spring olmadığını kontrol et.

Ortak kurallar: [Paket ve kod standardı](01-PAKET-VE-KOD-STANDARDI.md). Dosyaların tek tek yeni konumları: [Sınıf haritası](02-SINIF-VE-PAKET-HARITASI.md). Bu dosyada verilen yeni package/import/path birlikte kullanılmalıdır; eski ve yeni kopyaları aynı projeye ekleme.

### Bu görevde sınıflar hangi sorumlulukta?

| Sınıf | Paket | Neden burada? |
|---|---|---|
| `IdentityServiceApplication` | `servis kökü` | Servisin Spring başlangıç ve tarama köküdür. |
| `MarketDataServiceApplication` | `servis kökü` | Servisin Spring başlangıç ve tarama köküdür. |
| `SimulationServiceApplication` | `servis kökü` | Servisin Spring başlangıç ve tarama köküdür. |
| `StrategyServiceApplication` | `servis kökü` | Servisin Spring başlangıç ve tarama köküdür. |
| `ReportingServiceApplication` | `servis kökü` | Servisin Spring başlangıç ve tarama köküdür. |
| `ApiGatewayApplication` | `servis kökü` | Servisin Spring başlangıç ve tarama köküdür. |

## Kaynak görevlerin eksiksiz kontrol listesi

- [ ] **B01.1** OS, JDK, Maven veya wrapper, Docker/container runtime ve boş disk durumunu sen kontrol et; sürümleri paylaş. Asistan uyumlu sürüm planı sunsun, kendiliğinden kurulum yapmasın.
- [ ] **B01.2** Tek proje kökünü ve Maven modül ağacını yaz; finance-core’un Spring’den bağımsız olduğunu dependency tree’de göster.
- [ ] **B01.3** domain/application/infrastructure/api katmanlarını küçük bir sorumluluk tablosuyla tarif et. Entity’leri servisler arasında paylaşma.
- [ ] **B01.4** README’deki Decimal/UUID/Instant/enum ve strict submit şemasını dosyaya aktar; format validator’ın gerçekten çalışmasını test et.
- [ ] **B01.5** MarketSnapshot, PredictionResult ve LedgerTransactionPosted için birer ortak fixture/schema oluştur. Bütün API’leri bir günde yazma; henüz eklenmeyen şemaların görev sahibini kaydet.
- [ ] **B01.6** Başlangıç contract sürümünü 1.0.0 olarak kaydet; build ve schema kontrol çıktısını paylaş.

## B01.1 — Ortamını kontrol et

Windows’ta PowerShell aç. Komutlar kurulum yapmaz; mevcut araçları gösterir. Docker Desktop kapalıysa `docker info` hata verebilir; bu Java hatası değildir.

```powershell
java -version
javac -version
mvn -version
docker version
docker info
Get-PSDrive C
```

Bu ders kodlarının tabanı **JDK 21, Maven 3.9.x, Spring Boot 3.5.16** olarak seçildi. Bu bir “en yeni sürüm” iddiası değildir; Spring 6 / Jackson 2 örneklerinin tutarlı kalması için sabit eğitim tabanıdır. Boot 4 projesine bu POM ve importları karıştırma. Boot 3.5 resmi gereksinimi Java ≥17 ve Maven ≥3.6.3’tür. Başlangıçta bilgisayarındaki sürümleri paylaş; değişiklik gerekiyorsa sürüm kararı kayda geçirilir. Bu dosya bilgisayarına kurulum yapmaz.

Resmi kaynaklar: https://docs.spring.io/spring-boot/3.5/system-requirements.html ve https://docs.spring.io/spring-boot/maven-plugin/using.html (13.10.2026 kontrolü). Gateway için bu pakette Spring MVC + JDK HTTP proxy öğretim yaklaşımı kullanılıyor; ek bir Spring Cloud BOM zorunluluğu yok. Spring Cloud’a geçersen Boot uyumluluğunu https://cloud.spring.io/ üzerinden ayrıca kilitle.

## B01.2 — Projeyi IDE’de nasıl oluşturacaksın?

1. Bilgisayarında `CryptoLab` adında boş klasör oluştur. Önceden proje varsa aynı kökü kullan; ikinci repo açma.
2. IDE’de **Open Folder / Open** ile bu klasörü aç. Java SDK olarak 21 seç.
3. Kök altında `backend`, `frontend`, `ai`, `contracts`, `infra`, `progress` klasörlerini oluştur. Frontend/AI kodunu bu derslerde yazmıyoruz.
4. Aşağıdaki `backend/pom.xml` dosyasını yaz. IntelliJ’de **Add as Maven Project**, VS Code’da Java/Maven import kullan.
5. Listelenen on modül klasörünü oluştur ve kendi POM dosyalarını yaz. Spring Initializr’da on ayrı kök proje indirme; bu ders Maven reactor kullanıyor.
6. `src/main/java` kaynak, `src/test/java` test, `src/main/resources` konfigürasyon/migration klasörüdür. Java package dizinleri nokta yerine `/` ile açılır.

**Parent** ortak sürüm ve derleyici ayarlarını taşır. **Aggregator** `<modules>` ile bütün modülleri tek komutta derler. Aynı POM iki rolü de üstlenebilir. Dependency management bir kütüphaneyi projeye eklemez; eklendiğinde sürümünü belirler.

**İki sürüm hattını karıştırma:** Aşağıdaki tam POM önceki derslerin Boot 3.5.16/Jackson 2 tabanı için Cloud 2025.0.3 kullanır. Senin mevcut Boot 4.1.1 parent’ını değiştirme: kendi POM’una aynı BOM bölümünü **2025.1.2** ile ekle. B01A.1 iki hattı açıklar. Eureka eklemek önceki Jackson 2 örneklerini otomatik Boot 4 uyumlu yapmaz; tam backend sürüm uyarlaması ayrı doğrulama kapısıdır.

### `backend/pom.xml`

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
 xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
 xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
 <modelVersion>4.0.0</modelVersion>
 <!-- Parent: Spring bağımlılıklarının uyumlu sürümlerini tek yerden alır. -->
 <parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>3.5.16</version><relativePath/>
 </parent>
 <groupId>com.cryptolab</groupId><artifactId>backend</artifactId><version>1.0.0-SNAPSHOT</version>
 <!-- pom packaging: buradan executable JAR çıkmaz. -->
 <packaging>pom</packaging>
 <properties><spring-cloud.version>2025.0.3</spring-cloud.version><java.version>21</java.version><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
 <dependencyManagement><dependencies>
  <dependency><groupId>org.springframework.cloud</groupId><artifactId>spring-cloud-dependencies</artifactId>
   <version>${spring-cloud.version}</version><type>pom</type><scope>import</scope></dependency>
 </dependencies></dependencyManagement>
 <modules>
  <module>discovery-server</module><module>finance-core</module><module>identity-service</module>
  <module>market-data-service</module><module>simulation-service</module>
  <module>strategy-service</module><module>reporting-service</module>
  <module>api-gateway</module><module>account-service</module><module>test-support</module>
 </modules>
 <build><pluginManagement><plugins>
  <!-- Sadece Boot servisleri bu plugini etkinleştirir; finance-core normal JAR kalır. -->
  <plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin>
 </plugins></pluginManagement></build>
</project>
```

### `backend/finance-core/pom.xml`

Bu dosya parent ayarlarını devralır; artifactId bu modülün benzersiz adıdır.

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
 <modelVersion>4.0.0</modelVersion>
 <parent><groupId>com.cryptolab</groupId><artifactId>backend</artifactId><version>1.0.0-SNAPSHOT</version><relativePath>../pom.xml</relativePath></parent>
 <artifactId>finance-core</artifactId>
 <dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency></dependencies>

</project>
```

### `backend/identity-service/pom.xml`

Bu dosya parent ayarlarını devralır; artifactId bu modülün benzersiz adıdır.

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
 <modelVersion>4.0.0</modelVersion>
 <parent><groupId>com.cryptolab</groupId><artifactId>backend</artifactId><version>1.0.0-SNAPSHOT</version><relativePath>../pom.xml</relativePath></parent>
 <artifactId>identity-service</artifactId>
 <dependencies><!-- B01'de DB/Kafka bağımlılığı eklemiyoruz; boş uygulama dış servis aramasın. -->
 <dependency><groupId>org.springframework.cloud</groupId><artifactId>spring-cloud-starter-netflix-eureka-client</artifactId></dependency>
 <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
 <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
 <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
 <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency></dependencies>
 <build><plugins><plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build>
</project>
```

### `backend/identity-service/src/main/java/com/cryptolab/identity/IdentityServiceApplication.java`

**Sınıfı yazmadan önce:** Servisin başlangıç noktası. Bu sınıfın alt paketleri taranır; diğer servislerin paketlerini tarama kapsamına ekleme.

```java
package com.cryptolab.identity;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
// Bu paket ve alt paketlerindeki Spring bileşenleri taranır.
// Başka servisin paketini taramıyoruz: süreç sınırı korunur.
/**
 * Sorumluluk: Servisin başlangıç noktası.
 * Okuma ipucu: Bu sınıfın alt paketleri taranır; diğer servislerin paketlerini tarama kapsamına ekleme.
 */
@SpringBootApplication
public class IdentityServiceApplication {
 public static void main(String[] args) {
  SpringApplication.run(IdentityServiceApplication.class, args);
 }
}
```

### `backend/identity-service/src/main/resources/application.properties`

```properties
# Her süreç farklı portta dinler; aynı port iki kez açılamaz.
# B04 güvenlik gelene kadar localhost dışına açma.
spring.application.name=identity-service
server.port=8081
server.address=127.0.0.1
management.endpoints.web.exposure.include=health,info
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

### `backend/market-data-service/pom.xml`

Bu dosya parent ayarlarını devralır; artifactId bu modülün benzersiz adıdır.

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
 <modelVersion>4.0.0</modelVersion>
 <parent><groupId>com.cryptolab</groupId><artifactId>backend</artifactId><version>1.0.0-SNAPSHOT</version><relativePath>../pom.xml</relativePath></parent>
 <artifactId>market-data-service</artifactId>
 <dependencies><!-- B01'de DB/Kafka bağımlılığı eklemiyoruz; boş uygulama dış servis aramasın. -->
 <dependency><groupId>org.springframework.cloud</groupId><artifactId>spring-cloud-starter-netflix-eureka-client</artifactId></dependency>
 <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
 <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
 <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
 <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency></dependencies>
 <build><plugins><plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build>
</project>
```

### `backend/market-data-service/src/main/java/com/cryptolab/marketdata/MarketDataServiceApplication.java`

**Sınıfı yazmadan önce:** Servisin başlangıç noktası. Bu sınıfın alt paketleri taranır; diğer servislerin paketlerini tarama kapsamına ekleme.

```java
package com.cryptolab.marketdata;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
// Bu paket ve alt paketlerindeki Spring bileşenleri taranır.
// Başka servisin paketini taramıyoruz: süreç sınırı korunur.
/**
 * Sorumluluk: Servisin başlangıç noktası.
 * Okuma ipucu: Bu sınıfın alt paketleri taranır; diğer servislerin paketlerini tarama kapsamına ekleme.
 */
@SpringBootApplication
public class MarketDataServiceApplication {
 public static void main(String[] args) {
  SpringApplication.run(MarketDataServiceApplication.class, args);
 }
}
```

### `backend/market-data-service/src/main/resources/application.properties`

```properties
# Her süreç farklı portta dinler; aynı port iki kez açılamaz.
# B04 güvenlik gelene kadar localhost dışına açma.
spring.application.name=market-data-service
server.port=8082
server.address=127.0.0.1
management.endpoints.web.exposure.include=health,info
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

### `backend/simulation-service/pom.xml`

Bu dosya parent ayarlarını devralır; artifactId bu modülün benzersiz adıdır.

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
 <modelVersion>4.0.0</modelVersion>
 <parent><groupId>com.cryptolab</groupId><artifactId>backend</artifactId><version>1.0.0-SNAPSHOT</version><relativePath>../pom.xml</relativePath></parent>
 <artifactId>simulation-service</artifactId>
 <dependencies><!-- B01'de DB/Kafka bağımlılığı eklemiyoruz; boş uygulama dış servis aramasın. -->
 <dependency><groupId>org.springframework.cloud</groupId><artifactId>spring-cloud-starter-netflix-eureka-client</artifactId></dependency>
 <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
 <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
 <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
 <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
 <dependency><groupId>com.cryptolab</groupId><artifactId>finance-core</artifactId><version>${project.version}</version></dependency></dependencies>
 <build><plugins><plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build>
</project>
```

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/SimulationServiceApplication.java`

**Sınıfı yazmadan önce:** Servisin başlangıç noktası. Bu sınıfın alt paketleri taranır; diğer servislerin paketlerini tarama kapsamına ekleme.

```java
package com.cryptolab.simulation;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
// Bu paket ve alt paketlerindeki Spring bileşenleri taranır.
// Başka servisin paketini taramıyoruz: süreç sınırı korunur.
/**
 * Sorumluluk: Servisin başlangıç noktası.
 * Okuma ipucu: Bu sınıfın alt paketleri taranır; diğer servislerin paketlerini tarama kapsamına ekleme.
 */
@SpringBootApplication
public class SimulationServiceApplication {
 public static void main(String[] args) {
  SpringApplication.run(SimulationServiceApplication.class, args);
 }
}
```

### `backend/simulation-service/src/main/resources/application.properties`

```properties
# Her süreç farklı portta dinler; aynı port iki kez açılamaz.
# B04 güvenlik gelene kadar localhost dışına açma.
spring.application.name=simulation-service
server.port=8083
server.address=127.0.0.1
management.endpoints.web.exposure.include=health,info
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

### `backend/strategy-service/pom.xml`

Bu dosya parent ayarlarını devralır; artifactId bu modülün benzersiz adıdır.

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
 <modelVersion>4.0.0</modelVersion>
 <parent><groupId>com.cryptolab</groupId><artifactId>backend</artifactId><version>1.0.0-SNAPSHOT</version><relativePath>../pom.xml</relativePath></parent>
 <artifactId>strategy-service</artifactId>
 <dependencies><!-- B01'de DB/Kafka bağımlılığı eklemiyoruz; boş uygulama dış servis aramasın. -->
 <dependency><groupId>org.springframework.cloud</groupId><artifactId>spring-cloud-starter-netflix-eureka-client</artifactId></dependency>
 <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
 <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
 <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
 <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency></dependencies>
 <build><plugins><plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build>
</project>
```

### `backend/strategy-service/src/main/java/com/cryptolab/strategy/StrategyServiceApplication.java`

**Sınıfı yazmadan önce:** Servisin başlangıç noktası. Bu sınıfın alt paketleri taranır; diğer servislerin paketlerini tarama kapsamına ekleme.

```java
package com.cryptolab.strategy;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
// Bu paket ve alt paketlerindeki Spring bileşenleri taranır.
// Başka servisin paketini taramıyoruz: süreç sınırı korunur.
/**
 * Sorumluluk: Servisin başlangıç noktası.
 * Okuma ipucu: Bu sınıfın alt paketleri taranır; diğer servislerin paketlerini tarama kapsamına ekleme.
 */
@SpringBootApplication
public class StrategyServiceApplication {
 public static void main(String[] args) {
  SpringApplication.run(StrategyServiceApplication.class, args);
 }
}
```

### `backend/strategy-service/src/main/resources/application.properties`

```properties
# Her süreç farklı portta dinler; aynı port iki kez açılamaz.
# B04 güvenlik gelene kadar localhost dışına açma.
spring.application.name=strategy-service
server.port=8084
server.address=127.0.0.1
management.endpoints.web.exposure.include=health,info
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

### `backend/reporting-service/pom.xml`

Bu dosya parent ayarlarını devralır; artifactId bu modülün benzersiz adıdır.

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
 <modelVersion>4.0.0</modelVersion>
 <parent><groupId>com.cryptolab</groupId><artifactId>backend</artifactId><version>1.0.0-SNAPSHOT</version><relativePath>../pom.xml</relativePath></parent>
 <artifactId>reporting-service</artifactId>
 <dependencies><!-- B01'de DB/Kafka bağımlılığı eklemiyoruz; boş uygulama dış servis aramasın. -->
 <dependency><groupId>org.springframework.cloud</groupId><artifactId>spring-cloud-starter-netflix-eureka-client</artifactId></dependency>
 <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
 <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
 <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
 <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency></dependencies>
 <build><plugins><plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build>
</project>
```

### `backend/reporting-service/src/main/java/com/cryptolab/reporting/ReportingServiceApplication.java`

**Sınıfı yazmadan önce:** Servisin başlangıç noktası. Bu sınıfın alt paketleri taranır; diğer servislerin paketlerini tarama kapsamına ekleme.

```java
package com.cryptolab.reporting;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
// Bu paket ve alt paketlerindeki Spring bileşenleri taranır.
// Başka servisin paketini taramıyoruz: süreç sınırı korunur.
/**
 * Sorumluluk: Servisin başlangıç noktası.
 * Okuma ipucu: Bu sınıfın alt paketleri taranır; diğer servislerin paketlerini tarama kapsamına ekleme.
 */
@SpringBootApplication
public class ReportingServiceApplication {
 public static void main(String[] args) {
  SpringApplication.run(ReportingServiceApplication.class, args);
 }
}
```

### `backend/reporting-service/src/main/resources/application.properties`

```properties
# Her süreç farklı portta dinler; aynı port iki kez açılamaz.
# B04 güvenlik gelene kadar localhost dışına açma.
spring.application.name=reporting-service
server.port=8085
server.address=127.0.0.1
management.endpoints.web.exposure.include=health,info
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

### `backend/api-gateway/pom.xml`

Bu dosya parent ayarlarını devralır; artifactId bu modülün benzersiz adıdır.

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
 <modelVersion>4.0.0</modelVersion>
 <parent><groupId>com.cryptolab</groupId><artifactId>backend</artifactId><version>1.0.0-SNAPSHOT</version><relativePath>../pom.xml</relativePath></parent>
 <artifactId>api-gateway</artifactId>
 <dependencies><!-- B01'de DB/Kafka bağımlılığı eklemiyoruz; boş uygulama dış servis aramasın. -->
 <dependency><groupId>org.springframework.cloud</groupId><artifactId>spring-cloud-starter-netflix-eureka-client</artifactId></dependency>
 <dependency><groupId>org.springframework.cloud</groupId><artifactId>spring-cloud-starter-loadbalancer</artifactId></dependency>
 <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
 <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
 <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
 <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency></dependencies>
 <build><plugins><plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build>
</project>
```

### `backend/api-gateway/src/main/java/com/cryptolab/apigateway/ApiGatewayApplication.java`

**Sınıfı yazmadan önce:** Servisin başlangıç noktası. Bu sınıfın alt paketleri taranır; diğer servislerin paketlerini tarama kapsamına ekleme.

```java
package com.cryptolab.apigateway;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
// Bu paket ve alt paketlerindeki Spring bileşenleri taranır.
// Başka servisin paketini taramıyoruz: süreç sınırı korunur.
/**
 * Sorumluluk: Servisin başlangıç noktası.
 * Okuma ipucu: Bu sınıfın alt paketleri taranır; diğer servislerin paketlerini tarama kapsamına ekleme.
 */
@SpringBootApplication
public class ApiGatewayApplication {
 public static void main(String[] args) {
  SpringApplication.run(ApiGatewayApplication.class, args);
 }
}
```

### `backend/api-gateway/src/main/resources/application.properties`

```properties
# Her süreç farklı portta dinler; aynı port iki kez açılamaz.
# B04 güvenlik gelene kadar localhost dışına açma.
spring.application.name=api-gateway
spring.cloud.loadbalancer.cache.enabled=false
spring.cloud.loadbalancer.retry.enabled=false
server.port=8080
server.address=127.0.0.1
management.endpoints.web.exposure.include=health,info
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

### `backend/test-support/pom.xml`

Bu dosya parent ayarlarını devralır; artifactId bu modülün benzersiz adıdır.

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
 <modelVersion>4.0.0</modelVersion>
 <parent><groupId>com.cryptolab</groupId><artifactId>backend</artifactId><version>1.0.0-SNAPSHOT</version><relativePath>../pom.xml</relativePath></parent>
 <artifactId>test-support</artifactId>
 <dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency></dependencies>

</project>
```

## B01.3 — Sınıf hangi pakete yazılır?

| Paket | Sorumluluk | Örnek |
|---|---|---|
| domain | Değişmez iş kuralı, durum geçişi | Position, Journal |
| application | Kullanım senaryosu ve transaction sınırı | SubmitOrder |
| infrastructure | PostgreSQL, Kafka, HTTP gibi adaptör | JdbcLedgerStore |
| api | HTTP giriş/çıkış DTO ve controller | SubmitOrderRequest |

`finance-core` sınıflarında `@Service`, `@Entity`, Kafka veya HTTP yok. Saf Java nesnesi aynı girdide aynı sonucu döndürür. `record` değişmez veri taşımayı kolaylaştırır; kendiliğinden bütün iç nesneleri derin kopyalamaz. `List.copyOf` gerektiğinde kullanılır.

## B01.4–B01.5 — Şema ve fixture

JSON Schema DTO’nun dil bağımsız sözleşmesidir. JSON yorum satırı desteklemez; bu yüzden açıklamalar `$comment` alanındadır. UUID format kontrolünü yalnız tip=string ile karşılayamazsın. Event tüketicisi ek alan kabul eder; emir request’i etmez.

### `contracts/http/submit-order.schema.json`

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "additionalProperties": false,
  "required": [
    "planId",
    "revision",
    "planHash"
  ],
  "properties": {
    "planId": {
      "type": "string",
      "format": "uuid"
    },
    "revision": {
      "type": "integer",
      "minimum": 1
    },
    "planHash": {
      "type": "string",
      "pattern": "^sha256:[0-9a-f]{64}$"
    }
  },
  "$comment": "Alan adları README v3.0 sözleşmesinden; format doğrulayıcısı etkin olmalıdır."
}
```

### `contracts/http/market-snapshot.schema.json`

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "additionalProperties": false,
  "required": [
    "instrumentId",
    "snapshotId",
    "markPrice",
    "lastPrice",
    "bidPrice",
    "askPrice",
    "bidQty",
    "askQty",
    "sourceTime",
    "receivedAt",
    "quality",
    "reasonCodes"
  ],
  "properties": {
    "instrumentId": {
      "type": "string",
      "format": "uuid"
    },
    "snapshotId": {
      "type": "string",
      "format": "uuid"
    },
    "markPrice": {
      "type": "string",
      "pattern": "^-?(0|[1-9][0-9]*)(\\.[0-9]+)?$"
    },
    "lastPrice": {
      "anyOf": [
        {
          "type": "string",
          "pattern": "^-?(0|[1-9][0-9]*)(\\.[0-9]+)?$"
        },
        {
          "type": "null"
        }
      ]
    },
    "bidPrice": {
      "type": "string",
      "pattern": "^-?(0|[1-9][0-9]*)(\\.[0-9]+)?$"
    },
    "askPrice": {
      "type": "string",
      "pattern": "^-?(0|[1-9][0-9]*)(\\.[0-9]+)?$"
    },
    "bidQty": {
      "type": "string",
      "pattern": "^-?(0|[1-9][0-9]*)(\\.[0-9]+)?$"
    },
    "askQty": {
      "type": "string",
      "pattern": "^-?(0|[1-9][0-9]*)(\\.[0-9]+)?$"
    },
    "sourceTime": {
      "type": "string",
      "format": "date-time"
    },
    "receivedAt": {
      "type": "string",
      "format": "date-time"
    },
    "quality": {
      "enum": [
        "WARMING",
        "FRESH",
        "STALE",
        "GAP",
        "UNAVAILABLE"
      ]
    },
    "reasonCodes": {
      "type": "array",
      "items": {
        "type": "string"
      }
    }
  },
  "$comment": "Alan adları README v3.0 sözleşmesinden; format doğrulayıcısı etkin olmalıdır."
}
```

### `contracts/internal-ai/prediction-result.schema.json`

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "additionalProperties": false,
  "required": [
    "requestId",
    "quality",
    "reasonCodes",
    "modelBundleId",
    "featureVersion",
    "featureSchemaHash",
    "featureSnapshotId",
    "asOf",
    "horizonSeconds",
    "predictedLogReturn",
    "volatilityEstimate",
    "calibrationVersion",
    "observedFeatureSummary",
    "latencyMs"
  ],
  "properties": {
    "requestId": {
      "type": "string",
      "format": "uuid"
    },
    "quality": {
      "enum": [
        "READY",
        "INSUFFICIENT_DATA",
        "OUT_OF_DOMAIN",
        "STALE_DATA"
      ]
    },
    "reasonCodes": {
      "type": "array",
      "items": {
        "type": "string"
      }
    },
    "modelBundleId": {
      "type": "string",
      "format": "uuid"
    },
    "featureVersion": {
      "type": "string"
    },
    "featureSchemaHash": {
      "type": "string"
    },
    "featureSnapshotId": {
      "type": "string",
      "format": "uuid"
    },
    "asOf": {
      "type": "string",
      "format": "date-time"
    },
    "horizonSeconds": {
      "enum": [
        60,
        900,
        3600,
        86400,
        604800
      ]
    },
    "predictedLogReturn": {
      "type": [
        "number",
        "null"
      ]
    },
    "volatilityEstimate": {
      "type": [
        "number",
        "null"
      ]
    },
    "calibrationVersion": {
      "type": [
        "string",
        "null"
      ]
    },
    "observedFeatureSummary": {
      "type": "object"
    },
    "latencyMs": {
      "type": "integer",
      "minimum": 0
    }
  },
  "allOf": [
    {
      "if": {
        "properties": {
          "quality": {
            "const": "READY"
          }
        }
      },
      "then": {
        "properties": {
          "predictedLogReturn": {
            "type": "number"
          },
          "volatilityEstimate": {
            "type": "number",
            "minimum": 0
          }
        }
      },
      "else": {
        "properties": {
          "predictedLogReturn": {
            "type": "null"
          },
          "volatilityEstimate": {
            "type": "null"
          }
        }
      }
    }
  ],
  "$comment": "Alan adları README v3.0 sözleşmesinden; format doğrulayıcısı etkin olmalıdır."
}
```

### `contracts/events/ledger-transaction-posted.schema.json`

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "additionalProperties": true,
  "required": [
    "eventId",
    "eventType",
    "schemaVersion",
    "occurredAt",
    "recordedAt",
    "producer",
    "aggregateId",
    "aggregateVersion",
    "correlationId",
    "causationId",
    "sessionId",
    "engineSequence",
    "transactionId",
    "transactionOrdinal",
    "payload"
  ],
  "properties": {
    "eventId": {
      "type": "string",
      "format": "uuid"
    },
    "eventType": {
      "const": "LedgerTransactionPosted"
    },
    "schemaVersion": {
      "const": 2
    },
    "occurredAt": {
      "type": "string",
      "format": "date-time"
    },
    "recordedAt": {
      "type": "string",
      "format": "date-time"
    },
    "producer": {
      "type": "string"
    },
    "aggregateId": {
      "type": "string",
      "format": "uuid"
    },
    "aggregateVersion": {
      "type": "integer",
      "minimum": 1
    },
    "correlationId": {
      "type": "string",
      "format": "uuid"
    },
    "causationId": {
      "type": "string",
      "format": "uuid"
    },
    "sessionId": {
      "type": "string",
      "format": "uuid"
    },
    "engineSequence": {
      "type": "integer",
      "minimum": 1
    },
    "transactionId": {
      "type": "string",
      "format": "uuid"
    },
    "transactionOrdinal": {
      "type": "integer",
      "minimum": 1
    },
    "payload": {
      "$schema": "https://json-schema.org/draft/2020-12/schema",
      "type": "object",
      "additionalProperties": true,
      "required": [
        "journalTransactionId",
        "sessionId",
        "reason",
        "effectiveAt",
        "positionId",
        "orderId",
        "postings"
      ],
      "properties": {
        "journalTransactionId": {
          "type": "string",
          "format": "uuid"
        },
        "sessionId": {
          "type": "string",
          "format": "uuid"
        },
        "reason": {
          "type": "string"
        },
        "effectiveAt": {
          "type": "string",
          "format": "date-time"
        },
        "positionId": {
          "anyOf": [
            {
              "type": "string",
              "format": "uuid"
            },
            {
              "type": "null"
            }
          ]
        },
        "orderId": {
          "anyOf": [
            {
              "type": "string",
              "format": "uuid"
            },
            {
              "type": "null"
            }
          ]
        },
        "postings": {
          "type": "array",
          "minItems": 2,
          "items": {
            "$schema": "https://json-schema.org/draft/2020-12/schema",
            "type": "object",
            "additionalProperties": true,
            "required": [
              "accountId",
              "amount",
              "currency"
            ],
            "properties": {
              "accountId": {
                "type": "string",
                "format": "uuid"
              },
              "amount": {
                "type": "string",
                "pattern": "^-?(0|[1-9][0-9]*)(\\.[0-9]+)?$"
              },
              "currency": {
                "type": "string"
              }
            }
          }
        }
      }
    }
  },
  "$comment": "Alan adları README v3.0 sözleşmesinden; format doğrulayıcısı etkin olmalıdır."
}
```

### `contracts/fixtures/submit-order.json`

```json
{
  "planId": "11111111-1111-4111-8111-111111111111",
  "revision": 1,
  "planHash": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
}
```

### `contracts/fixtures/market-snapshot.json`

```json
{
  "instrumentId": "33333333-3333-4333-8333-333333333333",
  "snapshotId": "66666666-6666-4666-8666-666666666666",
  "markPrice": "50000",
  "lastPrice": "50000",
  "bidPrice": "50000",
  "askPrice": "50000",
  "bidQty": "2",
  "askQty": "2",
  "sourceTime": "2026-10-12T15:00:00.000Z",
  "receivedAt": "2026-10-12T15:00:00.010Z",
  "quality": "FRESH",
  "reasonCodes": []
}
```

### `contracts/fixtures/prediction-result.json`

```json
{
  "requestId": "22222222-2222-4222-8222-222222222222",
  "quality": "INSUFFICIENT_DATA",
  "reasonCodes": [
    "INSUFFICIENT_HISTORY"
  ],
  "modelBundleId": "44444444-4444-4444-8444-444444444444",
  "featureVersion": "features-v1",
  "featureSchemaHash": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
  "featureSnapshotId": "66666666-6666-4666-8666-666666666666",
  "asOf": "2026-10-12T15:00:00.000Z",
  "horizonSeconds": 3600,
  "predictedLogReturn": null,
  "volatilityEstimate": null,
  "calibrationVersion": null,
  "observedFeatureSummary": {},
  "latencyMs": 1
}
```

### `contracts/fixtures/ledger-transaction-posted.json`

```json
{
  "eventId": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
  "eventType": "LedgerTransactionPosted",
  "schemaVersion": 2,
  "occurredAt": "2026-10-12T15:00:00.000Z",
  "recordedAt": "2026-10-12T15:00:00.020Z",
  "producer": "simulation-service",
  "aggregateId": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
  "aggregateVersion": 1,
  "correlationId": "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
  "causationId": "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
  "sessionId": "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
  "engineSequence": 3,
  "transactionId": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
  "transactionOrdinal": 1,
  "payload": {
    "journalTransactionId": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
    "sessionId": "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
    "reason": "INITIAL_CAPITAL",
    "effectiveAt": "2026-10-12T15:00:00.000Z",
    "positionId": null,
    "orderId": null,
    "postings": [
      {
        "accountId": "11111111-1111-4111-8111-111111111112",
        "amount": "10000",
        "currency": "USDT"
      },
      {
        "accountId": "11111111-1111-4111-8111-111111111113",
        "amount": "-10000",
        "currency": "USDT"
      }
    ]
  }
}
```

### `contracts/check_contracts.py`

```python
from pathlib import Path
import json
from decimal import Decimal
from jsonschema import Draft202012Validator, FormatChecker
# Yardımcı Python yalnız sözleşme testi içindir; finans motoru yazmıyoruz.
root = Path(__file__).resolve().parent
pairs = [("http/submit-order.schema.json", "submit-order.json"),
         ("http/market-snapshot.schema.json", "market-snapshot.json"),
         ("internal-ai/prediction-result.schema.json", "prediction-result.json"),
         ("events/ledger-transaction-posted.schema.json", "ledger-transaction-posted.json")]
for schema_name, fixture_name in pairs:
    schema = json.loads((root / schema_name).read_text(encoding="utf-8"))
    fixture = json.loads((root / "fixtures" / fixture_name).read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(schema)
    # FormatChecker verilmezse UUID yazmak tek başına kontrol sağlamayabilir.
    validator = Draft202012Validator(schema, format_checker=FormatChecker())
    validator.validate(fixture)
    if fixture_name == "submit-order.json":
        for change in ({"quantity": "1"}, {"revision": 0}, {"planId": "bozuk"}):
            invalid = dict(fixture, **change)
            assert list(validator.iter_errors(invalid)), change
    if fixture_name == "ledger-transaction-posted.json":
        # JSON schema toplama yapmaz; anlamsal invariant ayrıca denenir.
        assert sum(Decimal(p["amount"]) for p in fixture["payload"]["postings"]) == 0
print("4 fixture ve strict submit negatif kontrolleri geçti")
```

### `contracts/requirements.txt`

```text
jsonschema==4.23.0
```

### `contracts/README.md`

```markdown
# CryptoLab sözleşmeleri
Contract sürümü: 1.0.0; şartname sürümü: 3.0.
Başlangıç: submit, market snapshot, prediction result, ledger event.
Kalan şemaların sahibi: Auth B04; Simulation B05/B07; Market B06;
Reporting/SSE B08; Strategy B09; Training B10; Replay B11.
Decimal JSON string; UUID kimlik; zaman UTC; komutlar strict, event'ler additive.
Değişiklik: gerekçe + üretici/tüketici + migration + fixture test + semver kararı.
```

## B01.6 — Çalıştır ve sonucu yorumla

PowerShell’de proje kökünde:

```powershell
mvn -f backend/pom.xml clean verify
mvn -f backend/pom.xml -pl finance-core dependency:tree
mvn -f backend/pom.xml -pl identity-service spring-boot:run
```

İlk komut tüm modülleri derler. İkinci çıktıda finance-core altında Spring/JPA/Kafka olmamalı. Üçüncü komut açık kalır; başka terminalde `Invoke-RestMethod http://localhost:8081/actuator/health` çalıştır. `UP` yalnız boş servisin açıldığını kanıtlar; finans sistemi hazır demek değildir. Bitirmek için Ctrl+C.

Şema testi için Python mevcutsa ayrı sanal ortam kullan:

```powershell
python -m venv .contracts-venv
.\.contracts-venv\Scripts\python -m pip install -r contracts/requirements.txt
.\.contracts-venv\Scripts\python contracts/check_contracts.py
```

Python yoksa bu araç kurulumunu ayrıca ele alırız; Maven testine geçti diye format testini geçmiş saymayız. Wrapper henüz oluşturulmadıysa `mvnw` kullanma. Maven hazır olduktan sonra `mvn -f backend/pom.xml wrapper:wrapper -Dmaven=3.9.9` ile wrapper üretilebilir; üretilen konumu kontrol et ve sonraki komutları ona göre değiştir.

**En sık hata:** `invalid target release:21` → Maven farklı JDK kullanıyor; `mvn -version` çıktısındaki Java Home’u düzelt. `Non-resolvable parent` → ağ/Maven repository erişimi; rastgele sürüm değiştirme. `Port already in use` → eski Java sürecini/aynı servisi kontrol et.

**Pratik:** identity portunu geçici 8181 yap, health’i çağır, sonra geri al. POM’dan bir modülü çıkarınca onun neden derlenmediğini açıkla. Submit fixture’a quantity ekleyip testin gerçekten hata verdiğini gör.

### `progress/BACKEND.md`

```markdown
# Backend ilerleme kaydı
- Görev: B01.1
- Durum: TODO
- JDK / Maven / Docker: henüz kullanıcı çıktısı yok
- Şartname: 3.0 / Contract: 1.0.0
- Yazdığım dosyalar: henüz yok
- Komut ve gözlenen sonuç: henüz yok
- Derleme/test kanıtı: henüz yok
- Sonraki tek adım: ortam komutlarının çıktısını paylaş
```

### `backend/account-service/pom.xml`

Bu dosya parent ayarlarını devralır; artifactId bu modülün benzersiz adıdır.

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
 <modelVersion>4.0.0</modelVersion>
 <parent><groupId>com.cryptolab</groupId><artifactId>backend</artifactId><version>1.0.0-SNAPSHOT</version><relativePath>../pom.xml</relativePath></parent>
 <artifactId>account-service</artifactId>
 <dependencies><!-- B01'de DB/Kafka bağımlılığı eklemiyoruz; boş uygulama dış servis aramasın. -->
 <dependency><groupId>org.springframework.cloud</groupId><artifactId>spring-cloud-starter-netflix-eureka-client</artifactId></dependency>
 <dependency><groupId>org.springframework.cloud</groupId><artifactId>spring-cloud-starter-loadbalancer</artifactId></dependency>
 <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
 <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
 <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
 <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency></dependencies>
 <build><plugins><plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build>
</project>
```

### `backend/account-service/src/main/java/com/cryptolab/account/AccountServiceApplication.java`

B01'de yalnız başlangıç iskeleti. B08A bu servisin iş/güvenlik katmanlarını ekler.

```java
package com.cryptolab.account;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication
public class AccountServiceApplication {
    public static void main(String[] args) { SpringApplication.run(AccountServiceApplication.class, args); }
}
```

### `backend/account-service/src/main/resources/application.properties`

```properties
spring.application.name=account-service
spring.cloud.loadbalancer.cache.enabled=false
spring.cloud.loadbalancer.retry.enabled=false
server.address=127.0.0.1
server.port=8086
management.endpoints.web.exposure.include=health,info
management.endpoint.health.show-details=never
# B08A'da JWT ve Reporting bağlantı ayarları eklenir.
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

## Kabul: hangi çıktıyı görmelisin?

**Deneme/kabul:** Geçerli submit kabul; body’ye quantity eklenince, revision=0 veya bozuk UUID ile reject. Fixture dosyasında secret yok. finance-core application class veya DB’ye bağımlı değil. Henüz oluşturulmamış wrapper komutunu varmış gibi çalıştırma; doğru komut ortam kontrolü sonrası seçilir.



## Kendi cümlelerinle anlat

“Java entity’sini doğrudan frontend’e göndermek yerine neden sözleşme var?”

## Çalışma sonunda

`progress/BACKEND.md` içine alt görev, değiştirdiğin dosya, komut, gerçek çıktı, fixture/gerçek entegrasyon ayrımı ve sıradaki tek adımı yaz. Çalıştırmadığın test için PASS yazma.

## B01A bağlantısı

B01 iskeletinden sonra B01A-Eureka-ve-Servis-Kesfi.md uygulanır. discovery-server dosyaları orada tam verilir. Tüm iş servislerinin kayıt testi geçmeden Gateway/Account bağlantısını hazır kabul etme.
