# CryptoLab — Paket düzeni ve geliştirme standardı

Revizyon 4 · 14.10.2026 · Şartname 3.0 ve Contract 1.0.0 korunur.

Bu belge 14 dersin ortak kuralıdır. Kaynak şartname dört ana katman istiyor: `api`, `application`, `domain`, `infrastructure`. DTO, mapper, repository, config ve exception bu katmanların altında açık sorumluluklarla yer alır. Bütün sınıfları aynı seviyede sekiz büyük klasöre doldurmak yerine mevcut mimarinin içinde düzenliyoruz.

Spring Boot tek bir zorunlu klasör ağacı dayatmaz. Buradaki seçim projenin servis ve transaction sınırlarını anlaşılır kılmak içindir. Her yeni sınıfı sırf klasör dolsun diye oluşturma; gerçek bir sorumluluğu varsa ekle.

## 1. Servis kökü ve paket tablosu

Örnek servis kökü `com.cryptolab.simulation`. `SimulationServiceApplication` bu kökte durur; alt paketler taranır. Identity, Market, Strategy, Reporting ve Gateway kendi köklerine sahiptir. `com.cryptolab` yerine mevcut projenin kökünü kullanacaksan bütün dosya yollarını/importları birlikte uyarlamalısın.

| Paket | Ne içerir? | Ne içermez? |
|---|---|---|
| `api.controller` | HTTP route, @Valid, doğrulanmış principal, HTTP status | SQL, para hesabı, veritabanı transaction orkestrasyonu |
| `api.dto.request` | İstemciden kabul edilen alanlar ve biçim doğrulaması | Entity, sunucunun hesaplaması gereken fee/PnL, yetki veren userId |
| `api.dto.response` | Dış sözleşmenin alanları, hata zarfı, sayfalama DTO'su | Password hash, refresh token'ın JSON kopyası, iç SQL verisi |
| `api.mapper` | Request → command, result → response | Repository çağrısı, dış HTTP, yetkilendirme, risk kararı |
| `api.exception` | Domain/application hatası → mevcut API hata zarfı | İş kuralı kararı, SQL/stack trace sızdırma |
| `application.command` | Bir kullanım senaryosunun doğrulanabilir girdisi | HTTP annotation zorunluluğu, servlet/JWT nesnesi |
| `application.service` | İşlem sırası, owner kontrolünün orkestrasyonu, transaction sınırı | JdbcTemplate, provider JSON parsing, controller DTO'su |
| `application.repository` | Kalıcılık için uygulamanın ihtiyaç duyduğu arayüzler | JdbcTemplate, ResultSet, SQL, başka servisin entity'si |
| `application.port` | ModelClient, MarketSource, TokenIssuer, LoginLimiter gibi dış yetenek arayüzleri | HTTP istemcisinin veya Redis'in implementasyonu |
| `application.replay` | Replay orkestrasyonu, sanal saat ve çalışma kapsamı | Canlı kullanıcı cüzdanına dolaylı erişim |
| `application.security` | Bu derslerdeki saf token üretme/hash yardımcıları | HTTP güvenlik zinciri, JDBC veya JWT kütüphane yapılandırması |
| `domain.model` | ExecutionPlan, Journal ve iş verisinin anlamlı tipleri | Spring, HTTP response, JDBC annotation'ı |
| `domain.service` | ExecutionGate, RiskDecision, PreviewFactory gibi saf iş kararları | DB/ağ çağrısı, gerçek saat okuma, framework bağımlılığı |
| `domain.exception` | HTTP'den bağımsız BusinessException ve hata kodu | ResponseEntity/HttpStatus |
| `infrastructure.repository` | JDBC adaptörü, SQL, row mapping ve projection saklama adaptörü | Başka servisin DB'sini okuma |
| `infrastructure.config` | Bean, Clock, JSON, security ve teknik yapılandırma | İşlem açma kararı veya hesap bakiyesi |
| `infrastructure.client` | Timeout'lu HTTP/AI/provider adaptörü | Controller response modeli veya ledger yazımı |
| `infrastructure.client.fixture` | Aynı portun açıkça etiketli test/demo implementasyonu | Gerçek piyasa veya gerçek modelmiş gibi sonuç |
| `infrastructure.mapper` | Dış provider veri formatı → kendi normalize modelimiz | Emir onayı ve nakit hareketi |
| `infrastructure.messaging` | Kafka listener, ingress adaptörü, outbox yayımlayıcı | İstemciye erken başarı ilanı, Redis'e finans otoritesi verme |
| `infrastructure.cache` | Redis TTL, cache serialization ve fallback | Kalıcı bakiye/tek işlem kilidi |
| `infrastructure.ratelimit` | Local/Redis limiter implementasyonları | Kullanıcı giriş kullanım senaryosunun tamamı |
| `infrastructure.streaming` | SSE buffer ve bağlantı kaynak yönetimi | Kullanıcının para durumunu sahiplenme |
| `infrastructure.artifact` / `export` | Hash kontrollü dosya okuma, CSV escaping | AI eğitimi veya finans kararları |

Alt paket adı bir sınıfı otomatik olarak doğru katmana sokmaz. `service` içinde hâlâ SQL varsa sorumluluk ayrılmamıştır. Bu revizyonda CreateSession, SubmitOrder, IdentityService ve DeadlineScheduler'ın SQL'i repository adaptörlerine ayrılmıştır.

## 2. Katmanlar birbirini nasıl kullanır?

HTTP girişinde controller request DTO'yu alır. Mapper onu application command'e çevirir. Service işlemin sırasını belirler; domain modellerini ve hesaplarını kullanır, kalıcı işlem için repository portuna gider. Spring, bu porta aynı servisin JDBC adaptörünü enjekte eder. Sonuç mapper ile response'a dönüşür.

**Kaynak kod bağımlılık yönü:** api → application → domain. Infrastructure → application portları/domain. Application, somut JdbcXRepository'yi import etmez. Runtime'da service'in çağrısı JDBC nesnesinde çalışır; arayüz sayesinde kaynak kod bağımlılığı ters yönde kurulmaz.

Örneğin SubmitOrder, OrderSubmissionRepository arayüzüne bağlıdır. JdbcOrderSubmissionRepository arayüzü uygular. Controller'ın hangi SQL'in çalıştığını bilmesi gerekmez. JDBC'den farklı bir kalıcılık aracına geçmek, endpoint'in veya risk hesabının yeniden yazılmasını gerektirmemelidir.

**Asenkron giriş:** Kafka listener HTTP controller değildir. Payload'ı doğrular/normalize eder ve uygulama akışını çağırır. Inbox + projection gibi yalnız adaptörün kalıcılığını ilgilendiren kısa transaction'lar infrastructure bileşeninde bulunabilir. İş kuralı büyürse saf domain kararına veya application servisine ayrılır. Bu istisna emir/risk hesabını listener'a taşımak için kullanılmaz.

## 3. DTO, model, entity ve command farkı

- **Request DTO:** dışarıdan hangi alanları kabul ettiğimizi söyler. SubmitOrderRequest yalnız planId, revision, planHash alır. Quantity eklemek sadece Java refactor'u değildir; sözleşme ihlalidir.
- **Command:** service'in girdisidir. HTTP dışındaki bir iş de aynı kullanım senaryosunu çağırabilir. JWT/HttpServletRequest taşımak yerine doğrulanmış owner UUID ayrı argümandır.
- **Domain model:** uygulamanın iş kavramıdır. Journal posting toplamının sıfır olmasını bilir; hangi HTTP status'un dönüleceğini bilmez.
- **Persistence entity/row:** veritabanı temsilidir. Bu pakette JDBC kullanıldığı için her tabloya @Entity üretmek gerekmez. JDBC RowMapper ile kendi tipimize dönüşür. JPA'ya geçilirse entity `infrastructure.persistence.entity` altında kalır ve API'ye doğrudan dönülmez.
- **Response DTO:** istemciye verdiğimiz sözleşmedir. Domain'e yeni iç alan eklenince dış response kendiliğinden değişmemelidir.

Immutable veri taşıyıcılarında record kullanılabilir. Ancak parola/token taşıyan record'un varsayılan toString'i hassas değerleri yazabilir. RegisterCommand örneğinde toString maskelidir; yine de request/command/token nesnesini loglamıyoruz.

## 4. Mapper neden ayrı?

OrderMapper.toCommand alanları bir kez eşler; OrderMapper.toResponse sonucu dış sözleşmeye çevirir. Mapper'ın yan etkisi yoktur. Veritabanından plan bulmaz, fee hesaplamaz, principal'dan yetki icat etmez. Bu işleri mapper'a koymak testleri ve hata takibini zorlaştırır.

Basit map için elle kod daha öğreticidir. Otomatik eşleme eklenecekse Decimal'ın string oluşu, UTC Instant ve enum değerleri açık testlerle korunur. MapStruct kullanmak projenin zorunlu mimari şartı değildir. Domain constructor invariant kontrolü yapabilir; bu mapper'a iş kuralı eklemek değildir.

## 5. Service ve repository sınırı

Service bir kullanım senaryosunu temsil eder. CreateSession, SubmitOrder ve UserQueryService adları yaptıkları işi anlatır. Her sınıfa ServiceImpl eklemek gerekmez; dış bağımlılık/değiştirilebilir implementasyon için interface kullanılır. Bir service yalnız tek başka service'i aynen delegasyonla sarmalıyorsa gerekliliğini sorgula.

Repository'ye `executeSql(String)` gibi genel kapı açma. `lockOwnedSession`, `lockPlan`, `createInitialAccounts`, `revokeFamily` gibi amaca uygun metotlar kullan. Repository iş kararını vermez: planın geçerli olup olmadığına SubmitOrder karar verir; `validUntil` değerini okuyan SQL adaptörüdür. DB unique/check/foreign key kısıtları son savunmadır; Java doğrulaması bunları kaldırmaz.

Constructor injection bağımlılıkları görünür yapar. Zorunlu alanlar final olur. Bir test yalnız service'i kurmak için on beş mock gerektiriyorsa service'in fazla sorumluluk üstlenip üstlenmediğini gözden geçir. Field injection veya her yere static singleton kullanmak yerine nesne ilişkilerini constructor'da açık kur.

## 6. Transaction yaşam döngüsü

Transaction bir kullanım senaryosunun atomik sınırıdır; her repository metodu için ayrı commit değildir. CreateSession içinde session + hesaplar + initial journal aynı transaction'dır. SubmitOrder içinde session lock + idempotency + approval + command + consumed plan aynı transaction'dır. Controller'a geri dönmeden commit tamamlanır; ancak 202 hâlâ yalnız komut kabulüdür.

Bu derslerde kritik yazan JDBC repository'ler MANDATORY kullanır: dış transaction yoksa hata verir. Yanlışlıkla REQUIRES_NEW eklemek journal/command'ın ayrı commit olmasına neden olabilir. `readOnly=true` optimizasyon niyetidir; kullanıcıya erişim yetkisi sağlamaz.

Spring proxy üzerinden girilen @Transactional metoda dikkat et. Aynı sınıfın içinden this.metot() çağırmak yeni proxy sınırı oluşturmaz. Poller ayrı bean, transaction açan service ayrı bean olarak düzenlenir. `private issue` gibi bir yardımcı mevcut dış login transaction'ına katılır; üzerine annotation koyarak ayrı transaction oluşturulduğu varsayılmaz.

Refresh replay kritik istisnadır: family revoke yazıldıktan sonra RuntimeException fırlatılırsa revoke geri alınabilir. IdentityService.rotate bu nedenle sonuç/error kaydı döner. Controller transaction tamamlandıktan sonra 401 üretir. Paketleri taşırken bu akış değiştirilmedi.

## 7. Doğrulama ve exception

Üç farklı kontrol vardır: DTO biçimi (UUID, boş değer, uzunluk), domain kuralı (pozitif miktar, balanced journal), kalıcılık/yetki (owner, unique, version, row lock). Birinin olması diğerini kaldırmaz. @Valid, signed-in kullanıcının kaynağın sahibi olduğunu kanıtlamaz.

BusinessException yalnız güvenli hata kodu taşır. api.exception bunu mevcut `code,message,traceId,retryable,details` zarfına mapler. Beklenmeyen hata 500 olur; ham exception mesajı dışarı dönmez. Validation detayında yalnız alan adı/güvenli açıklama bulunur; reddedilen parola veya SQL yoktur.

401/403'ün bir kısmı controller'dan önce Security filter zincirinde oluşur. @RestControllerAdvice tek başına bunları yakalamaz. AuthenticationEntryPoint ve AccessDeniedHandler aynı JSON sözleşmesini üretmelidir; JWT/CSRF testlerinde bu sınır ayrıca doğrulanır. Bu ek adaptörlerin tam çalışan entegrasyonu önceki açık işler kapsamındadır.

404 owner filtreli sorgularda kaynağı ifşa etmemek için kullanılabilir. 409 idempotency/version/plan çatışmasıdır. 422 kural reddidir. 503 geçici veri erişilemezliğidir. Retry yalnız aynı idempotency key ve aynı istekle güvenli protokole göre yapılır; retryable=true yeni emir üretme izni değildir.

## 8. Config ve bean seçimi

Clock ve HTTP client gibi teknik bağımlılıklar infrastructure.config içinde kurulur. Config sınıfı nakit güncellemez. Constructor'da dış HTTP çağrısı yapılmaz. URL, timeout ve boyut sınırları yapılandırmadan gelir; secret kaynak koda veya örnek .env'ye gerçek değer olarak yazılmaz.

B04 LocalLoginLimiter portun tek implementasyonudur. B08 RedisLoginLimiter eklenince @Primary ile seçilir; local nesne Redis adaptörünün fallback'idir. Service'in constructor tipi LoginLimiter olarak aynı kalır. Aynı porta iki bean eklemek ve seçimi belirsiz bırakmak uygulamanın açılmasını engeller.

Fixture ve gerçek client aynı anda etkinleşiyorsa explicit profile/qualifier seçimi gerekir. Üretimde fixture sonuçları gerçek model veya canlı fiyat gibi gösterilemez. `application.properties` eklerini birleştir; aynı property anahtarını tekrar yazarak önceki ayarı gölgeleme.

## 9. finance-core özel sınırı

finance-core küçük, framework bağımsız hesap modülüdür. HTTP/DB olmadığı için controller/dto/config/repository klasörleri oluşturulmaz. Decimal, Money, Quantity ve Finance mevcut küçük paketinde kalır. “Her yerde aynı mantık” her modüle kullanılmayan aynı klasörleri eklemek değildir; sorumlulukları aynı ilkeye göre ayırmaktır.

Para BigDecimal, JSON Decimal string, zaman UTC Instant, kimlik UUID olarak korunur. Finans hesaplarının sırf mapper'a taşınması veya double'a çevrilmesi yapılmaz. Simulation, hesabın tek yazarı olmaya devam eder; Reporting yalnız okuma modelidir.

## 10. Testleri hangi sınıf için yazacağız?

| Hedef | Ne kanıtlanır? | Araç / sınır |
|---|---|---|
| Domain/finance | Yuvarlama, funding, kısmi kapanış, invariants | Spring açmadan saf unit test |
| Mapper | Alan kaybı yok, Decimal string, hassas alan sızmıyor | Küçük saf test; her getter için test yazılmaz |
| Service | Akış, owner/expiry/idempotency davranışı | Port fake/mock; SQL'i taklit etmez |
| JDBC repository | SQL/migration/unique/lock gerçekten doğru | PostgreSQL entegrasyonu |
| Controller | JSON biçimi, @Valid, status, auth wiring | MVC/HTTP sınırı; sürüme uygun test modülü |
| Mesaj adaptörü | Duplicate, commit/ACK, restart | Kafka + DB entegrasyonu |
| Sistem | Auth → onay → fill → close → ledger → report | Gerçek servislerle E2E |

Hedef doğrulamayı yapmadan sırf dosya oluşturuldu diye test PASS yazılmaz. Bu revizyonda paket/import ve saf sınıflar kontrol edilir; tam Spring build ve dış servis testlerinin sınırlaması DOGRULAMA.md'dedir.

## 11. Yeni bir özellik yazma sırası

1. Önce kaynak sözleşmedeki endpoint/event ve iş kuralını oku; yeni alan eklemeyi varsayma.
2. Domain modelini ve invariant'ı belirle. Finans hesabı varsa finance-core mevcut fonksiyonunu kullan.
3. Service'in ne okumaya/yazmaya ihtiyacı olduğunu yaz; repository/port arayüzünü buna göre oluştur.
4. Application command/result ve service akışını yaz. SQL'i buraya koyma.
5. Repository/client adaptörünü yaz. Parametreli SQL, timeout ve hata politikasını uygula.
6. Request/response DTO, mapper, controller ve exception eşlemesini ekle.
7. Config ve bean seçimini bağla. Portun en az bir ve belirsiz olmayan implementasyonu olsun.
8. Kuralı test et; gerçek DB/ağ sınırı varsa entegrasyon kontrolünü ayrıca yap.
9. İlgili Bxx checklist'ini kanıta göre güncelle. Kod, test, belge değişikliğini aynı mantıklı commit'te tut.

Ders içindeki kodların basılı sırası her zaman dosya yazma sırası değildir. Her dersin başındaki “Yazma sırası” ve sınıf tablosunu izle. Aynı yeni sınıf iki örnekte anlatılıyorsa ikinci kez oluşturma; var olan dosyayı güncelle.

## 12. Değişmeyen sınırlar ve geçiş

ACC-01 ekiyle on Maven modülü ve sekiz çalışan süreç vardır; önceki servislerin veri sahipliği korunur. Her servis kendi DB'sinin sahibidir. Wallet/order/risk aynı Simulation transaction alanındadır. Domain entity/repository'leri servisler arasında paylaşılmaz. PDF/training inference yoluna taşınmaz. AI önerisi kullanıcı onayı yerine geçmez. Kaynak JSON şemaları ve 84 görev kimliği korunur.

Bu revizyon framework yükseltmesi değildir. Eski örneklerin Boot 3.5/Jackson 2 tabanı ve mevcut Boot 4.1.1 projesine uyarlama ihtiyacı devam eder; kendi POM'unu eski örnekle düşürme. Paket taşıma + framework göçünü aynı kontrolsüz adımda yapmak hatanın kaynağını ayırmayı zorlaştırır.

Önce 02-SINIF-VE-PAKET-HARITASI.md üzerinden Move refactor uygula. Sonra importları, constructor bağımlılıklarını, test package'larını ve Spring scan kapsamını kontrol et. Java package taşınması Flyway migration'larını yeniden numaralandırmayı veya DB'yi resetlemeyi gerektirmez.

## Resmi kaynaklar

14.10.2026 tarihinde kontrol edilen ilkeler; paket tablosu projenin kendi mimari kararıdır:

- [Spring Boot — kod yerleşimi](https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html): zorunlu tek ağaç yok; application class üst pakette.
- [Spring Framework — dependency injection](https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-collaborators.html): constructor ile zorunlu bağımlılıkları görünür kurma.
- [Spring Framework — @Transactional](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html): proxy ve transaction çağrı sınırı.

## ACC-01 ek kuralı

Account stateless okuma servisidir: DTO/mapper/service/port/client/config/exception vardır; kalıcılık repository’si Reporting’de kalır. Yeni modül/süreç sayıları ve security sahipliği [ACC-01](03-ACCOUNT-VE-GUVENLIK-EKI.md) içindedir. YAML anlatımları yalnız Compose/CI içindir; Spring yapılandırması application.properties.

## DISC-01 — Discovery sınırı

DiscoveryServerApplication `com.cryptolab.discovery` kökündedir. Registry’ye domain/repository eklenmez. GatewayTargetResolver `apigateway.infrastructure.client`, GatewayErrors `apigateway.api.exception` altındadır. Eureka/LoadBalancer tipi domain veya application kullanım senaryolarına sızmaz. AccountReportReader portu değişmez; HttpAccountReportReader teknik hedef seçer. finance-core/test-support client olmaz.
