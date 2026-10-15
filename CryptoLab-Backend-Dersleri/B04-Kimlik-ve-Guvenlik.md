# B04 — Identity, JWT ve kaynak sahipliği

> **DISC-01:** Eureka/local adres çözümlemesi için B01A-Eureka-ve-Servis-Kesfi.md uygulanır. Uygulama ayarları properties olarak kalır; iş kuralları değişmez.

Şartname: 3.0 · Contract: 1.0.0 · Öğretici kod referansı · Durum: BUILD_UNVERIFIED

ACC-01 eki: Account, servis sahipliği ve properties standardı için [güncel mimari eki](03-ACCOUNT-VE-GUVENLIK-EKI.md) geçerlidir.
Kimlik doğrulama “kimsin?”, kaynak sahipliği “bu session senin mi?” sorusudur. İkisini ayrı uygularız. USER kendini TRAINER yapamaz; JWT’yi Base64 açmak imza doğrulaması değildir.

Bu dosyadaki kodları kendi projenize sırayla elle yazın. Burada uygulama reposu kurulmuş veya kabul testleri geçmiş değildir. Dosya yolları CryptoLab köküne göredir. Bir sınıfı yazdıktan sonra küçük deneyi yapın; tüm belgeyi tek hamlede kopyalamayın.

## Bu dersi nasıl okuyacaksın? — Kimlik akışında DTO ve güvenlik sınırları

**Önce kavram:** AuthController HTTP/cookie işini, AuthMapper veri dönüşümünü, IdentityService register/login/refresh sırasını, IdentityRepository kalıcılık ihtiyacını yönetir. JdbcIdentityRepository SQL’i tutar. TokenIssuer ve LoginLimiter dış teknik davranışların portlarıdır.

**Yazma sırası:** IdentityRepository → JdbcIdentityRepository → RegisterCommand ve request/response DTO → AuthMapper → TokenIssuer/LoginLimiter ve adaptörleri → IdentityService/UserQueryService → AuthController → security config.

**Neden bu ayrım var?** Mapper parola hashlemez. UserResponse parola taşımaz. Refresh replay’de family revoke commit olmadan exception fırlatılmaz. UserQueryService’in readOnly transaction’ı erişim izni yerine geçmez; subject doğrulanmış kimlikten gelir.

**Küçük pratik:** Register response’unda passwordHash olmadığını kontrol et. Eski refresh token’ı tekrar kullan; revoke kaydının transaction sonrasında kaldığını doğrula.

Ortak kurallar: [Paket ve kod standardı](01-PAKET-VE-KOD-STANDARDI.md). Dosyaların tek tek yeni konumları: [Sınıf haritası](02-SINIF-VE-PAKET-HARITASI.md). Bu dosyada verilen yeni package/import/path birlikte kullanılmalıdır; eski ve yeni kopyaları aynı projeye ekleme.

### Bu görevde sınıflar hangi sorumlulukta?

| Sınıf | Paket | Neden burada? |
|---|---|---|
| `RegisterRequest` | `api.dto.request` | İstemciden kabul edilen alanları tarif eder. |
| `Crypto` | `application.security` | Kimlik akışının saf teknik yardımcılarını sağlar. |
| `TokenConfig` | `infrastructure.config` | Teknik nesneleri ve Spring bağlantılarını kurar. |
| `AccessTokens` | `infrastructure.security` | Dış sistem veya teknik sınırı uygular. |
| `LocalLoginLimiter` | `infrastructure.ratelimit` | Dış sistem veya teknik sınırı uygular. |
| `IdentityService` | `application.service` | Bir kullanım senaryosunun sırasını yönetir. |
| `AuthController` | `api.controller` | HTTP isteğini uygulama akışına bağlar. |
| `SecurityConfig` | `infrastructure.config` | Teknik nesneleri ve Spring bağlantılarını kurar. |
| `IdentityRepository` | `application.repository` | Kalıcılık davranışını arayüz olarak tanımlar. |
| `JdbcIdentityRepository` | `infrastructure.repository` | Portun SQL veya okuma modeli kalıcılığını uygular. |
| `RegisterCommand` | `application.command` | Kullanım senaryosunun girdisini taşır. |
| `UserQueryService` | `application.service` | Bir kullanım senaryosunun sırasını yönetir. |
| `UserResponse` | `api.dto.response` | Dış API alanlarını sabit tutar. |
| `AuthMapper` | `api.mapper` | HTTP modeli ile uygulama modelini dönüştürür. |
| `LoginLimiter` | `application.port` | Dış teknik yeteneği soyutlar. |
| `TokenIssuer` | `application.port` | Dış teknik yeteneği soyutlar. |
| `LoginRequest` | `api.dto.request` | Login request sözleşmesi; hassas toString maskelenir. |
| `TokenResponse` | `api.dto.response` | Access token response sözleşmesi. |

## Kaynak görevlerin eksiksiz kontrol listesi

- [ ] **B04.1** User/refresh schema; normalized email unique, status/role. Kayıt DTO’sunda rol seçimi yok.
- [ ] **B04.2** Register validation/hash/insert ve duplicate email davranışını yaz; şifreyi loglama.
- [ ] **B04.3** Login ve token üretimini yaz; issuer/audience/expiry/kid kavramlarını test et.
- [ ] **B04.4** Refresh cookie+DB hash/rotation/family revoke ve logout akışını yaz; CSRF/Origin’ı açıkça uygula.
- [ ] **B04.5** Her servis için doğrulanmış subject→owner sorgusu; X-User-Id/body userId’ye güvenme.
- [ ] **B04.6** USER/TRAINER/ADMIN ve MODEL_PUBLISH authority politikasını oluştur; controlled dev seed’i ayrı tut.
- [ ] **B04.7** Login limiter portunu hazırla; Redis adapter B08’de tamamlanana kadar sınırlı local dev/test adapter kullan, sınırsız login açma.

## B04.1 — Hazırlık

Identity modülüne B03 JDBC/Flyway/PostgreSQL bağımlılıklarını, Identity ve bütün downstream servislere aşağıdaki bağımlılığı ekle. Identity DB için ayrı `identity_app` kullanıcısı ve `identity_db` oluştur; Simulation DB kimliğini paylaşma. B03 init SQL kalıbını ayrı parola ile uygula. DB_URL/DB_USER/DB_PASSWORD her süreçte kendi DB’sini göstermeli.

```xml
<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-oauth2-resource-server</artifactId></dependency>
```

Geliştirme RSA anahtarını proje dışında üret. Private key git’e girmez. JDK keytool yerine aşağıdaki OpenSSL komutu kullanılabilir; OpenSSL yoksa kurulu JDK ile anahtar üretme adımını beraber yaparız. Uygulama her restart’ta rastgele anahtar üretmez; aksi halde eski tokenlar beklenmedik şekilde bozulur.

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out identity-private.pem
openssl pkey -in identity-private.pem -pubout -out identity-public.pem
```

JWT_ISSUER örneği `http://localhost:8081`, JWT_AUDIENCE `cryptolab-api`; JWK_KID `dev-2026-01`. Erişim tokenı 5 dakika, refresh 7 gün bu dersin açık konfigürasyonudur. Üretim HTTPS’te refresh cookie Secure=true; localhost HTTP için yalnız dev profilde false.

### `backend/identity-service/src/main/resources/db/migration/V1__identity.sql`

```sql
CREATE TABLE users(id uuid PRIMARY KEY,normalized_email text NOT NULL UNIQUE,
 display_name text NOT NULL,password_hash text NOT NULL,status text NOT NULL DEFAULT 'ACTIVE',
 created_at timestamptz NOT NULL,version bigint NOT NULL DEFAULT 1);
CREATE TABLE user_roles(user_id uuid NOT NULL REFERENCES users(id),role text NOT NULL,
 CHECK(role IN ('USER','TRAINER','ADMIN')),PRIMARY KEY(user_id,role));
CREATE TABLE user_authorities(user_id uuid NOT NULL REFERENCES users(id),authority text NOT NULL,
 PRIMARY KEY(user_id,authority));
CREATE TABLE refresh_sessions(id uuid PRIMARY KEY,user_id uuid NOT NULL REFERENCES users(id),
 token_hash text NOT NULL UNIQUE,family_id uuid NOT NULL,expires_at timestamptz NOT NULL,
 revoked_at timestamptz,replaced_by uuid,version bigint NOT NULL DEFAULT 1);
CREATE INDEX refresh_family ON refresh_sessions(family_id);
CREATE TABLE security_audit(id uuid PRIMARY KEY,actor_id uuid,action text NOT NULL,result text NOT NULL,
 trace_id text NOT NULL,created_at timestamptz NOT NULL);
-- Register da mutasyondur: Idempotency-Key + normalize request hash gerekir.
CREATE TABLE registration_requests(key text PRIMARY KEY,request_hash text NOT NULL,user_id uuid NOT NULL REFERENCES users(id));
```

### `backend/identity-service/src/main/java/com/cryptolab/identity/api/dto/request/RegisterRequest.java`

**Sınıfı yazmadan önce:** İstemciden kabul edilen alanları tarif eder. Annotation biçimi kontrol eder; owner ve finans iş kuralı kontrolü sonraki katmandadır.

```java
package com.cryptolab.identity.api.dto.request;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
/**
 * Sorumluluk: İstemciden kabul edilen alanları tarif eder.
 * Okuma ipucu: Annotation biçimi kontrol eder; owner ve finans iş kuralı kontrolü sonraki katmandadır.
 */
public record RegisterRequest(@NotBlank @Email @Size(max=254) String email,
 @NotBlank @Size(min=12,max=72) String password,@NotBlank @Size(max=80) String displayName) {
 // role/userId alanı yok: kullanıcı kendi yetkisini belirleyemez.
 @Override public String toString() { return "RegisterRequest[REDACTED]"; }
}
```

### `backend/identity-service/src/main/java/com/cryptolab/identity/application/security/Crypto.java`

**Sınıfı yazmadan önce:** Kimlik akışının saf teknik yardımcılarını sağlar. Rastgele token hash’i ile insan parolası hash’ini aynı yöntem sanma.

```java
package com.cryptolab.identity.application.security;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
/**
 * Sorumluluk: Kimlik akışının saf teknik yardımcılarını sağlar.
 * Okuma ipucu: Rastgele token hash’i ile insan parolası hash’ini aynı yöntem sanma.
 */
public final class Crypto {
 private static final SecureRandom RANDOM=new SecureRandom();
 private Crypto() {}
 public static String randomToken() {byte[] b=new byte[32];RANDOM.nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
 public static String hash(String raw) {
  try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));}
  catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
 }
 // Rastgele refresh token yüksek entropili olduğu için SHA-256 ile saklanır.
 // İnsan parolasında bu yöntem kullanılmaz; BCrypt/Argon2 gerekir.
}
```

### `backend/identity-service/src/main/java/com/cryptolab/identity/infrastructure/config/TokenConfig.java`

**Sınıfı yazmadan önce:** Teknik nesneleri ve Spring bağlantılarını kurar. Bean hangi portu karşılıyor ve hangi profile’da etkin sorularını cevapla.

```java
package com.cryptolab.identity.infrastructure.config;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.time.Clock;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
/**
 * Sorumluluk: Teknik nesneleri ve Spring bağlantılarını kurar.
 * Okuma ipucu: Bean hangi portu karşılıyor ve hangi profile’da etkin sorularını cevapla.
 */
@Configuration
public class TokenConfig {
 static byte[] pem(String file)throws Exception {
  String text=Files.readString(Path.of(file)).replaceAll("-----[^-]+-----", "").replaceAll("\\s", "");
  return Base64.getDecoder().decode(text);
 }
 @Bean RSAKey signingKey(@Value("${jwt.private-key-path}")String privatePath,
  @Value("${jwt.public-key-path}")String publicPath,@Value("${jwt.kid}")String kid)throws Exception {
  var k=KeyFactory.getInstance("RSA");
  var pub=(RSAPublicKey)k.generatePublic(new X509EncodedKeySpec(pem(publicPath)));
  var pri=(RSAPrivateKey)k.generatePrivate(new PKCS8EncodedKeySpec(pem(privatePath)));
  return new RSAKey.Builder(pub).privateKey(pri).keyID(kid).build();
 }
 @Bean JwtEncoder encoder(RSAKey key){return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));}
 @Bean PasswordEncoder passwords(){return new BCryptPasswordEncoder(12);}
 @Bean Clock clock(){return Clock.systemUTC();}
}
```

### `backend/identity-service/src/main/java/com/cryptolab/identity/infrastructure/security/AccessTokens.java`

**Sınıfı yazmadan önce:** Dış sistem veya teknik sınırı uygular. Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.

```java
package com.cryptolab.identity.infrastructure.security;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import com.cryptolab.identity.application.repository.IdentityRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
/**
 * Sorumluluk: Dış sistem veya teknik sınırı uygular.
 * Okuma ipucu: Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.
 */
@Service
public class AccessTokens implements com.cryptolab.identity.application.port.TokenIssuer {
 private final JwtEncoder encoder;private final RSAKey key;private final IdentityRepository db;private final Clock clock;
 private final String issuer,audience;
 public AccessTokens(JwtEncoder encoder,RSAKey key,IdentityRepository db,Clock clock,
  @Value("${jwt.issuer}")String issuer,@Value("${jwt.audience}")String audience){this.encoder=encoder;this.key=key;this.db=db;this.clock=clock;this.issuer=issuer;this.audience=audience;}
 public String issue(UUID user){
  var roles=db.roles(user);
  var authorities=db.authorities(user);
  var now=clock.instant();
  var claims=JwtClaimsSet.builder().issuer(issuer).subject(user.toString()).audience(List.of(audience))
   .issuedAt(now).expiresAt(now.plusSeconds(300)).id(UUID.randomUUID().toString())
   .claim("roles",roles).claim("authorities",authorities).build();
  // kid hangi anahtarın kullanılacağını söyler; private key JWKS ile yayımlanmaz.
  return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).build(),claims)).getTokenValue();
 }
}
```

### `backend/identity-service/src/main/java/com/cryptolab/identity/infrastructure/ratelimit/LocalLoginLimiter.java`

**Sınıfı yazmadan önce:** Dış sistem veya teknik sınırı uygular. Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.

```java
package com.cryptolab.identity.infrastructure.ratelimit;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.util.LinkedHashMap;
/**
 * Sorumluluk: Dış sistem veya teknik sınırı uygular.
 * Okuma ipucu: Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.
 */
@Component
public class LocalLoginLimiter implements com.cryptolab.identity.application.port.LoginLimiter {
 private record Window(long minute,int count){}
 private final LinkedHashMap<String,Window> buckets=new LinkedHashMap<>();private final Clock clock;
 public LocalLoginLimiter(Clock clock){this.clock=clock;}
 public synchronized void check(String key){
  long minute=clock.instant().getEpochSecond()/60;
  buckets.entrySet().removeIf(e->e.getValue().minute()<minute);
  // Bellek sınırsız büyümesin; yeni anahtarları kabul ederek saldırıya açık kalma.
  if(!buckets.containsKey(key)&&buckets.size()>=10000)throw new IllegalStateException("RATE_LIMITED");
  var old=buckets.getOrDefault(key,new Window(minute,0));
  if(old.count()>=10)throw new IllegalStateException("RATE_LIMITED");
  buckets.put(key,new Window(minute,old.count()+1));
 }
}
```

### `backend/identity-service/src/main/java/com/cryptolab/identity/application/service/IdentityService.java`

**Sınıfı yazmadan önce:** Bir kullanım senaryosunun sırasını yönetir. Constructor bağımlılıklarını ve transaction sınırını önce oku; hesap ve SQL sorumluluklarını ayır.

```java
package com.cryptolab.identity.application.service;
import com.cryptolab.identity.application.command.RegisterCommand;
import com.cryptolab.identity.application.repository.IdentityRepository;
import com.cryptolab.identity.application.security.Crypto;
import org.springframework.stereotype.Service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.sql.Timestamp;
import java.util.Locale;
import java.util.UUID;
/**
 * Sorumluluk: Bir kullanım senaryosunun sırasını yönetir.
 * Okuma ipucu: Constructor bağımlılıklarını ve transaction sınırını önce oku; hesap ve SQL sorumluluklarını ayır.
 */
@Service
public class IdentityService {
 public record Issued(String accessToken,String refreshToken,long expiresIn){
  @Override public String toString(){return "Issued[REDACTED]";}
 }
 private final IdentityRepository db;private final PasswordEncoder passwords;private final com.cryptolab.identity.application.port.TokenIssuer access;
 private final com.cryptolab.identity.application.port.LoginLimiter limiter;private final Clock clock;private final String dummyHash;
 public IdentityService(IdentityRepository db,PasswordEncoder passwords,com.cryptolab.identity.application.port.TokenIssuer access,com.cryptolab.identity.application.port.LoginLimiter limiter,Clock clock){
  this.db=db;this.passwords=passwords;this.access=access;this.limiter=limiter;this.clock=clock;this.dummyHash=passwords.encode(Crypto.randomToken());
 }
 @Transactional public UUID register(RegisterCommand r,String key){
  String email=r.email().strip().toLowerCase(Locale.ROOT);
  // Parola hash'ini request hash'e dahil ediyoruz, düz metni saklamıyoruz.
  // Uzunluk önekleri concat belirsizliğini önler.
  String digest=Crypto.hash(email.length()+":"+email+r.displayName().length()+":"+r.displayName()+Crypto.hash(r.password()));
  // Register scope'ta henüz subject yok; key bazlı DB advisory lock yarışları seri yapar.
  db.lock(key);
  var previous=db.registration(key);
  if(previous.isPresent()){
   if(!previous.get().hash().equals(digest))throw new IllegalStateException("IDEMPOTENCY_CONFLICT");
   return previous.get().userId();
  }
  // BCrypt sınırı karakter değil byte'dır; Türkçe karakterleri de hesaba kat.
  if(r.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)throw new IllegalArgumentException("PASSWORD_TOO_LONG");
  UUID id=UUID.randomUUID();
  db.register(id,email,r.displayName().strip(),passwords.encode(r.password()),clock.instant(),key,digest);
  return id;
 }
 @Transactional public Issued login(String email,String password,String clientIp){
  String normalized=email.strip().toLowerCase(Locale.ROOT);
  limiter.check("ip:"+clientIp);limiter.check("email:"+Crypto.hash(normalized));
  var users=db.credentials(normalized);
  String hash=users.isEmpty()?dummyHash:users.get().passwordHash();
  boolean valid=passwords.matches(password,hash); // Kullanıcı yoksa da pahalı verify yapılır.
  if(!valid||users.isEmpty()||!users.get().status().equals("ACTIVE"))throw new IllegalArgumentException("INVALID_CREDENTIALS");
  return issue(users.get().id(),UUID.randomUUID());
 }
 private Issued issue(UUID user,UUID family){
  String raw=Crypto.randomToken();
  db.insertRefresh(UUID.randomUUID(),user,Crypto.hash(raw),family,clock.instant().plusSeconds(604800));
  return new Issued(access.issue(user),raw,300);
 }
 public record RefreshResult(Issued issued,String error){}
 @Transactional public RefreshResult rotate(String raw){
  // Önce family bilgisini bul, sonra bütün aile işlemlerini tek DB lock ile seri yap.
  var families=db.family(Crypto.hash(raw));
  if(families.isEmpty())return new RefreshResult(null,"INVALID_REFRESH");
  db.lock(families.get().toString());
  var row=db.lockRefresh(Crypto.hash(raw));UUID family=row.familyId();
  if(row.revokedAt()!=null){
   db.revokeFamily(family,clock.instant());
   // Exception fırlatmıyoruz: aksi halde family revoke rollback olur!
   return new RefreshResult(null,"REFRESH_REPLAY");
  }
  if(!row.expiresAt().isAfter(clock.instant()))return new RefreshResult(null,"EXPIRED_REFRESH");
  String status=db.status(row.userId());
  if(!"ACTIVE".equals(status))return new RefreshResult(null,"INVALID_REFRESH");
  var next=issue(row.userId(),family);
  UUID nextId=db.refreshId(Crypto.hash(next.refreshToken()));
  db.replaceRefresh(row.id(),nextId,clock.instant());
  return new RefreshResult(next,null);
 }
 @Transactional public void logout(String raw){
  var families=db.family(Crypto.hash(raw));
  if(families.isEmpty())return;
  UUID family=families.get();db.lock(family.toString());
  db.revokeFamily(family,clock.instant());
 }
}
```

## B04.4 — HTTP katmanı ve CSRF

Refresh cookie tarayıcı tarafından otomatik gönderildiği için CSRF koruması gerekir. Bu ders refresh/logout için **tam Origin allowlist + JSON dışı çağrı reddi + özel X-CSRF header + SameSite Strict** kullanır. X-CSRF değeri gizli değildir; amaç cross-origin basit form isteğinin header üretememesidir. CORS yalnız kendi UI origin’ine izin verir; `*` ve credentials birlikte kullanılmaz. İzinli origin üzerinde XSS olması ayrı bir güvenlik sorunudur.

### `backend/identity-service/src/main/java/com/cryptolab/identity/api/controller/AuthController.java`

**Sınıfı yazmadan önce:** HTTP isteğini uygulama akışına bağlar. DTO doğrulamasını, principal kaynağını ve HTTP cevabını takip et; SQL burada bulunmaz.

```java
package com.cryptolab.identity.api.controller;
import com.cryptolab.identity.api.dto.request.LoginRequest;
import com.cryptolab.identity.api.dto.response.TokenResponse;
import com.cryptolab.identity.api.dto.request.RegisterRequest;
import com.cryptolab.identity.application.service.IdentityService;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.JWKSet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.beans.factory.annotation.Value;
import com.cryptolab.identity.application.service.UserQueryService;
import com.cryptolab.identity.api.mapper.AuthMapper;
import com.cryptolab.identity.api.dto.response.UserResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import java.util.*;
/**
 * Sorumluluk: HTTP isteğini uygulama akışına bağlar.
 * Okuma ipucu: DTO doğrulamasını, principal kaynağını ve HTTP cevabını takip et; SQL burada bulunmaz.
 */
@RestController
public class AuthController {
 private final IdentityService service;private final RSAKey key;private final UserQueryService users; private final AuthMapper mapper;
 private final String origin;private final boolean secure;
 public AuthController(IdentityService service,RSAKey key,UserQueryService users,AuthMapper mapper,@Value("${web.origin}")String origin,@Value("${cookie.secure:true}")boolean secure){this.service=service;this.key=key;this.users=users;this.mapper=mapper;this.origin=origin;this.secure=secure;}
 private UserResponse view(UUID id){return mapper.toResponse(users.find(id));}
 @PostMapping(value="/api/v1/auth/register",consumes="application/json") ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest r,@RequestHeader("Idempotency-Key")String idem){
  if(idem.isBlank()||idem.length()>128)throw new IllegalArgumentException("INVALID_KEY");
  return ResponseEntity.status(201).body(view(service.register(mapper.toCommand(r),idem)));
 }
 @PostMapping(value="/api/v1/auth/login",consumes="application/json") ResponseEntity<?> login(@Valid @RequestBody LoginRequest r,HttpServletRequest request){
  // Proxy varsa yalnız güvenilen proxy'den gelen IP çözülür; X-Forwarded-For'a kör güven yok.
  return tokens(service.login(r.email(),r.password(),request.getRemoteAddr()));
 }
 private void csrf(String suppliedOrigin,String header){
  if(!origin.equals(suppliedOrigin)||!"1".equals(header))throw new org.springframework.security.access.AccessDeniedException("CSRF_REJECTED");
 }
 private ResponseCookie cookie(String value,long seconds){return ResponseCookie.from("refresh",value).httpOnly(true).secure(secure).sameSite("Strict").path("/api/v1/auth").maxAge(seconds).build();}
 private ResponseEntity<?> tokens(IdentityService.Issued value){return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,cookie(value.refreshToken(),604800).toString()).body(new TokenResponse(value.accessToken(),value.expiresIn()));}
 @PostMapping(value="/api/v1/auth/refresh",consumes="application/json") ResponseEntity<?> refresh(@CookieValue("refresh")String refresh,@RequestHeader("Origin")String o,@RequestHeader("X-CSRF")String csrf){
  csrf(o,csrf);var result=service.rotate(refresh);
  // rotate transaction'ı bu noktada commit oldu; replay revoke kaybolmaz.
  if(result.error()!=null)return ResponseEntity.status(401).header(HttpHeaders.SET_COOKIE,cookie("",0).toString()).body(Map.of("code",result.error(),"message","Oturumu yenileyin.","traceId",UUID.randomUUID().toString(),"retryable",false,"details",Map.of()));
  return tokens(result.issued());
 }
 @PostMapping(value="/api/v1/auth/logout",consumes="application/json") ResponseEntity<Void> logout(@CookieValue("refresh")String refresh,@RequestHeader("Origin")String o,@RequestHeader("X-CSRF")String csrf,@RequestHeader("Idempotency-Key")String idem){
  csrf(o,csrf);service.logout(refresh);return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE,cookie("",0).toString()).build();
 }
 @GetMapping("/api/v1/auth/me") UserResponse me(@AuthenticationPrincipal Jwt jwt){return view(UUID.fromString(jwt.getSubject()));}
 @GetMapping("/.well-known/jwks.json") Map<String,Object> jwks(){return new JWKSet(key.toPublicJWK()).toJSONObject();}
}
```

### `backend/identity-service/src/main/java/com/cryptolab/identity/infrastructure/config/SecurityConfig.java`

**Sınıfı yazmadan önce:** Teknik nesneleri ve Spring bağlantılarını kurar. Bean hangi portu karşılıyor ve hangi profile’da etkin sorularını cevapla.

```java
package com.cryptolab.identity.infrastructure.config;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.ArrayList;
import java.util.List;
/**
 * Sorumluluk: Teknik nesneleri ve Spring bağlantılarını kurar.
 * Okuma ipucu: Bean hangi portu karşılıyor ve hangi profile’da etkin sorularını cevapla.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {
 @Bean JwtDecoder decoder(@Value("${jwt.jwks-url}")String url,@Value("${jwt.issuer}")String issuer,@Value("${jwt.audience}")String audience){
  NimbusJwtDecoder decoder=NimbusJwtDecoder.withJwkSetUri(url).build();
  OAuth2TokenValidator<Jwt> aud=jwt->jwt.getAudience().contains(audience)
   ?OAuth2TokenValidatorResult.success():OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
  decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),aud));return decoder;
 }
 @Bean SecurityFilterChain security(HttpSecurity http)throws Exception {
  var converter=new JwtAuthenticationConverter();
  converter.setJwtGrantedAuthoritiesConverter(jwt->{
   var out=new ArrayList<org.springframework.security.core.GrantedAuthority>();
   List<String> roles=jwt.getClaimAsStringList("roles");if(roles!=null)roles.forEach(r->out.add(new SimpleGrantedAuthority("ROLE_"+r)));
   List<String> authorities=jwt.getClaimAsStringList("authorities");if(authorities!=null)authorities.forEach(a->out.add(new SimpleGrantedAuthority(a)));
   return out;
  });
  // Stateless bearer API; cookie kullanan iki endpoint controller'da Origin/header doğrular.
  return http.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
   .authorizeHttpRequests(a->a.requestMatchers("/actuator/health","/.well-known/jwks.json","/api/v1/auth/register","/api/v1/auth/login","/api/v1/auth/refresh","/api/v1/auth/logout").permitAll()
    .requestMatchers("/internal/**").denyAll().anyRequest().authenticated())
   .oauth2ResourceServer(o->o.jwt(j->j.jwtAuthenticationConverter(converter))).build();
 }
}
```

## B04.5–B04.7 — Diğer servislere uyarlama

Bu SecurityConfig’in package’ını hedef servise göre değiştirerek **servis içinde** oluştur. Identity’nin login/register permitAll listesini downstream’e taşıma: downstream’de yalnız health public, `/api/v1/**` authenticated olmalı. İç servis endpoint’lerini B06’da ayrı service authentication zinciri açılana kadar denyAll bırak. Gateway geçildi diye downstream doğrulamayı kapatma.

```properties
jwt.issuer=${JWT_ISSUER:http://localhost:8081}
jwt.audience=${JWT_AUDIENCE:cryptolab-api}
jwt.jwks-url=${JWT_JWKS_URL:http://localhost:8081/.well-known/jwks.json}
jwt.private-key-path=${JWT_PRIVATE_KEY_PATH}
jwt.public-key-path=${JWT_PUBLIC_KEY_PATH}
jwt.kid=${JWT_KID:dev-2026-01}
web.origin=${WEB_ORIGIN:http://localhost:5173}
cookie.secure=${COOKIE_SECURE:true}
```

Private/public key dosya ayarları yalnız Identity’de gereklidir; downstream yalnız JWKS URL kullanır. B08 gateway CORS ekler; o zamana kadar HTTP istemcisiyle dene.

**Kontrollü dev seed:** Kullanıcı önce normal register olur. Sonra yalnız yerel DBA bağlantısında belirli user UUID için `INSERT INTO user_roles VALUES (...,'TRAINER')` uygulanır. MODEL_PUBLISH ayrı user_authorities satırıdır. Public “rol değiştir” endpoint’i açma. Seed’i production profile’da çalıştırma.

**Sahiplik:** `SELECT ... WHERE id=? AND owner_id=?`; controller’daki subject `UUID.fromString(jwt.getSubject())`. Body’den owner alma. Report projection henüz owner bilgisi almamışsa erişimi reddet; tahmin etme.

## Test sırası

1. Register DTO’da rol göndermeyi dene: strict JSON ayarıyla 400. `spring.jackson.deserialization.fail-on-unknown-properties=true` tüm command servislerinde açık olmalı; event mapper ayrı tolerant olabilir.
2. Aynı email’i iki eşzamanlı request ile gönder: biri başarı, diğeri 409. DuplicateKeyException → ortak error handler 409; SQL exception mesajını dışarı verme.
3. Yanlış parola ve olmayan kullanıcı aynı 401 gövdesini vermeli. 11. denemede 429.
4. JWT’nin tek byte’ını değiştir; issuer/audience yanlış ve expired token dene: 401.
5. Refresh token A → B üret. A’yı yeniden kullan: 401. B de iptal olmalı. Revoke transaction’ı rollback olmamalı.
6. Yanlış Origin veya X-CSRF eksik: 403. Logout sonrası refresh yok; eski access token kendi 5 dakikalık süresi dolana kadar geçerli olabilir.
7. A’nın tokenıyla B’nin session/stream/export isteği: 404 veya 403; hiçbir finans verisi dönmemeli.

Bu testler çalıştırılmadan auth hazır kabul edilmez. B05 ortak error handler eşlemesini tamamlar; 401/403 için Spring Security entrypoint/accessDeniedHandler da aynı zarfı kullanmalıdır.

### `backend/identity-service/src/main/java/com/cryptolab/identity/application/repository/IdentityRepository.java`

Kayıtlar repository tarafından dönen uygulama verileridir; HTTP DTO veya JPA entity değildir. Password hash hiçbir User response kaydına dahil edilmez.

**Sınıfı yazmadan önce:** Kalıcılık davranışını arayüz olarak tanımlar. Service bu arayüze bağımlıdır; SQL implementasyonu infrastructure altındadır.

```java
package com.cryptolab.identity.application.repository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
/**
 * Sorumluluk: Kalıcılık davranışını arayüz olarak tanımlar.
 * Okuma ipucu: Service bu arayüze bağımlıdır; SQL implementasyonu infrastructure altındadır.
 */
public interface IdentityRepository {
    record Registration(String hash, UUID userId) {}
    record Credentials(UUID id, String passwordHash, String status) {}
    record Refresh(UUID id, UUID userId, UUID familyId, Instant expiresAt, Instant revokedAt) {}
    record User(UUID id, String displayName, List<String> roles) {}
    void lock(String scope);
    Optional<Registration> registration(String key);
    void register(UUID id, String email, String name, String passwordHash, Instant now, String key, String digest);
    Optional<Credentials> credentials(String email);
    void insertRefresh(UUID id, UUID user, String hash, UUID family, Instant expiry);
    Optional<UUID> family(String hash);
    Refresh lockRefresh(String hash);
    void revokeFamily(UUID family, Instant now);
    String status(UUID user);
    UUID refreshId(String hash);
    void replaceRefresh(UUID previous, UUID next, Instant now);
    User user(UUID id);
    List<String> roles(UUID user);
    List<String> authorities(UUID user);
}
```

### `backend/identity-service/src/main/java/com/cryptolab/identity/infrastructure/repository/JdbcIdentityRepository.java`

Register/refresh/logout atomik kalır. Repository transaction gerektirir; read-only UserQueryService de kendi transaction sınırını açar.

**Sınıfı yazmadan önce:** Portun SQL veya okuma modeli kalıcılığını uygular. Parametreli SQL, transaction katılımı, owner/unique/lock kapsamını incele.

```java
package com.cryptolab.identity.infrastructure.repository;
import com.cryptolab.identity.application.repository.IdentityRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
/**
 * Sorumluluk: Portun SQL veya okuma modeli kalıcılığını uygular.
 * Okuma ipucu: Parametreli SQL, transaction katılımı, owner/unique/lock kapsamını incele.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcIdentityRepository implements IdentityRepository {
    private final JdbcTemplate db;
    public JdbcIdentityRepository(JdbcTemplate db) { this.db = db; }
    public void lock(String scope) {
        // Transaction sonlanınca advisory lock otomatik bırakılır.
        db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", scope);
    }
    public Optional<Registration> registration(String key) {
        return db.query("SELECT request_hash,user_id FROM registration_requests WHERE key=?",
                (rs,n) -> new Registration(rs.getString(1),rs.getObject(2,UUID.class)),key).stream().findFirst();
    }
    public void register(UUID id,String email,String name,String passwordHash,Instant now,String key,String digest) {
        db.update("INSERT INTO users(id,normalized_email,display_name,password_hash,created_at) VALUES (?,?,?,?,?)",
                id,email,name,passwordHash,Timestamp.from(now));
        db.update("INSERT INTO user_roles VALUES (?,'USER')",id);
        db.update("INSERT INTO registration_requests VALUES (?,?,?)",key,digest,id);
    }
    public Optional<Credentials> credentials(String email) {
        return db.query("SELECT id,password_hash,status FROM users WHERE normalized_email=?",
                (rs,n) -> new Credentials(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3)),email).stream().findFirst();
    }
    public void insertRefresh(UUID id,UUID user,String hash,UUID family,Instant expiry) {
        db.update("INSERT INTO refresh_sessions(id,user_id,token_hash,family_id,expires_at) VALUES (?,?,?,?,?)",
                id,user,hash,family,Timestamp.from(expiry));
    }
    public Optional<UUID> family(String hash) {
        return db.queryForList("SELECT family_id FROM refresh_sessions WHERE token_hash=?",UUID.class,hash).stream().findFirst();
    }
    public Refresh lockRefresh(String hash) {
        return db.queryForObject("SELECT id,user_id,family_id,expires_at,revoked_at FROM refresh_sessions WHERE token_hash=? FOR UPDATE",
                (rs,n) -> new Refresh(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                        rs.getTimestamp(4).toInstant(),rs.getTimestamp(5)==null?null:rs.getTimestamp(5).toInstant()),hash);
    }
    public void revokeFamily(UUID family,Instant now) {
        db.update("UPDATE refresh_sessions SET revoked_at=coalesce(revoked_at,?),version=version+1 WHERE family_id=?",Timestamp.from(now),family);
    }
    public String status(UUID user) { return db.queryForObject("SELECT status FROM users WHERE id=?",String.class,user); }
    public UUID refreshId(String hash) { return db.queryForObject("SELECT id FROM refresh_sessions WHERE token_hash=?",UUID.class,hash); }
    public void replaceRefresh(UUID previous,UUID next,Instant now) {
        db.update("UPDATE refresh_sessions SET revoked_at=?,replaced_by=?,version=version+1 WHERE id=?",Timestamp.from(now),next,previous);
    }
    public User user(UUID id) {
        return new User(id,db.queryForObject("SELECT display_name FROM users WHERE id=?",String.class,id),roles(id));
    }
    public List<String> roles(UUID user) { return db.queryForList("SELECT role FROM user_roles WHERE user_id=?",String.class,user); }
    public List<String> authorities(UUID user) { return db.queryForList("SELECT authority FROM user_authorities WHERE user_id=?",String.class,user); }
}
```

### `backend/identity-service/src/main/java/com/cryptolab/identity/application/command/RegisterCommand.java`

DTO → command eşlemesi HTTP katmanında yapılır. Parola yalnız bu kısa işlem kapsamında encoder'a taşınır, loglanmaz.

**Sınıfı yazmadan önce:** Kullanım senaryosunun girdisini taşır. HTTP nesnesi taşımadan gereken alanları ver; hassas değerleri loglama.

```java
package com.cryptolab.identity.application.command;
/**
 * Sorumluluk: Kullanım senaryosunun girdisini taşır.
 * Okuma ipucu: HTTP nesnesi taşımadan gereken alanları ver; hassas değerleri loglama.
 */
public record RegisterCommand(String email, String displayName, String password) {
    public RegisterCommand {
        // HTTP dışı çağrılarda da boş/gereğinden uzun değerler service'e ulaşmasın.
        if (email == null || email.isBlank() || email.length() > 254
                || displayName == null || displayName.isBlank() || displayName.length() > 80
                || password == null || password.isBlank() || password.length() < 12 || password.length() > 72) {
            throw new IllegalArgumentException("INVALID_REGISTRATION");
        }
    }
    // Parola taşıyan record'un varsayılan toString'i kullanılırsa loga parola sızar.
    @Override public String toString() { return "RegisterCommand[REDACTED]"; }
}
```

### `backend/identity-service/src/main/java/com/cryptolab/identity/application/service/UserQueryService.java`

Controller doğrudan SQL çalıştırmaz. Mevcut /me ve register cevapları bu servis üzerinden okunur.

**Sınıfı yazmadan önce:** Bir kullanım senaryosunun sırasını yönetir. Constructor bağımlılıklarını ve transaction sınırını önce oku; hesap ve SQL sorumluluklarını ayır.

```java
package com.cryptolab.identity.application.service;
import com.cryptolab.identity.application.repository.IdentityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
/**
 * Sorumluluk: Bir kullanım senaryosunun sırasını yönetir.
 * Okuma ipucu: Constructor bağımlılıklarını ve transaction sınırını önce oku; hesap ve SQL sorumluluklarını ayır.
 */
@Service
public class UserQueryService {
    private final IdentityRepository users;
    public UserQueryService(IdentityRepository users) { this.users = users; }
    @Transactional(readOnly = true)
    public IdentityRepository.User find(UUID subject) {
        // subject doğrulanmış JWT'den veya yeni kaydı oluşturan service sonucundan gelir.
        return users.user(subject);
    }
}
```

### `backend/identity-service/src/main/java/com/cryptolab/identity/api/dto/response/UserResponse.java`

Önceki UserView ile aynı JSON alanlarını taşır; sınıf adı değişirken API sözleşmesi değişmez.

**Sınıfı yazmadan önce:** Dış API alanlarını sabit tutar. Alan adları sözleşmedir; domain iç alanlarını veya hassas veriyi kendiliğinden ekleme.

```java
package com.cryptolab.identity.api.dto.response;
import java.util.List;
import java.util.UUID;
/**
 * Sorumluluk: Dış API alanlarını sabit tutar.
 * Okuma ipucu: Alan adları sözleşmedir; domain iç alanlarını veya hassas veriyi kendiliğinden ekleme.
 */
public record UserResponse(UUID id, String displayName, List<String> roles) {
    public UserResponse { roles = List.copyOf(roles); }
    // passwordHash ve refreshToken dış response'a dahil edilmez.
}
```

### `backend/identity-service/src/main/java/com/cryptolab/identity/api/mapper/AuthMapper.java`

Mapper, HTTP DTO ile uygulama verisini ayırır. Kullanıcıya rol atamaz; kayıt rolü repository işleminde USER olarak korunur.

**Sınıfı yazmadan önce:** HTTP modeli ile uygulama modelini dönüştürür. Mapper’ın yan etkisi olmamalı; DB, ağ, ücret hesabı veya izin kararı çağırma.

```java
package com.cryptolab.identity.api.mapper;
import com.cryptolab.identity.api.dto.request.RegisterRequest;
import com.cryptolab.identity.api.dto.response.UserResponse;
import com.cryptolab.identity.application.command.RegisterCommand;
import com.cryptolab.identity.application.repository.IdentityRepository;
import org.springframework.stereotype.Component;
/**
 * Sorumluluk: HTTP modeli ile uygulama modelini dönüştürür.
 * Okuma ipucu: Mapper’ın yan etkisi olmamalı; DB, ağ, ücret hesabı veya izin kararı çağırma.
 */
@Component
public class AuthMapper {
    public RegisterCommand toCommand(RegisterRequest request) {
        // Normalizasyon service'in kuralıdır; mapper parola hashlemez.
        return new RegisterCommand(request.email(), request.displayName(), request.password());
    }
    public UserResponse toResponse(IdentityRepository.User user) {
        return new UserResponse(user.id(), user.displayName(), user.roles());
    }
}
```

### `backend/identity-service/src/main/java/com/cryptolab/identity/application/port/LoginLimiter.java`

Bir interface'i alternatif implementasyon veya dış sınır varsa açarız. Her service için sırf alışkanlıkla Service + ServiceImpl üretmeyiz.

**Sınıfı yazmadan önce:** Dış teknik yeteneği soyutlar. Fixture ve gerçek adaptör aynı sözleşmeyi uygular; service somut client bilmez.

```java
package com.cryptolab.identity.application.port;
/**
 * Sorumluluk: Dış teknik yeteneği soyutlar.
 * Okuma ipucu: Fixture ve gerçek adaptör aynı sözleşmeyi uygular; service somut client bilmez.
 */
public interface LoginLimiter {
    // İhlalde izin vermek yerine RATE_LIMITED üretir. Redis hatası ayrı yönetilir.
    void check(String key);
}
```

### `backend/identity-service/src/main/java/com/cryptolab/identity/application/port/TokenIssuer.java`

IdentityService token imzalama kütüphanesine bağlanmaz; AccessTokens bu portun RSA/JWT adaptörüdür.

**Sınıfı yazmadan önce:** Dış teknik yeteneği soyutlar. Fixture ve gerçek adaptör aynı sözleşmeyi uygular; service somut client bilmez.

```java
package com.cryptolab.identity.application.port;
import java.util.UUID;
/**
 * Sorumluluk: Dış teknik yeteneği soyutlar.
 * Okuma ipucu: Fixture ve gerçek adaptör aynı sözleşmeyi uygular; service somut client bilmez.
 */
public interface TokenIssuer {
    String issue(UUID user);
}
```

### `backend/identity-service/src/main/java/com/cryptolab/identity/api/dto/request/LoginRequest.java`

**Sınıfı yazmadan önce:** Login DTO controller içine gömülü değildir. JSON alan adları email/password olarak korunur.

```java
package com.cryptolab.identity.api.dto.request;
import jakarta.validation.constraints.NotBlank;
/** HTTP login girdisi; parola yalnız giriş doğrulamasında kullanılır. */
public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    @Override public String toString() { return "LoginRequest[REDACTED]"; }
}
```

### `backend/identity-service/src/main/java/com/cryptolab/identity/api/dto/response/TokenResponse.java`

**Sınıfı yazmadan önce:** Map yerine tipli response ile dışarı çıkan alanlar görünür olur. Refresh token bu sınıfa eklenmez.

```java
package com.cryptolab.identity.api.dto.response;
/** Refresh token HttpOnly cookie'de kalır; bu JSON yalnız access token taşır. */
public record TokenResponse(String accessToken, long expiresIn) {
    @Override public String toString() { return "TokenResponse[REDACTED]"; }
}
```

## Kabul: hangi çıktıyı görmelisin?

**Deneme/kabul:** Yanlış parola, duplicate email yarışı, expired/sahte audience JWT, refresh replay, logout ve cross-user erişim testleri. Auth/session DTO fixture’ları F02’ye verilir. Kısa token TTL ve logout sınırı açıklanır; access token bir anda her yerde iptal olmuş sayılmaz.



## Kendi cümlelerinle anlat

“JWT’yi decode etmek ile doğrulamak arasındaki fark ne?”

## Çalışma sonunda

`progress/BACKEND.md` içine alt görev, değiştirdiğin dosya, komut, gerçek çıktı, fixture/gerçek entegrasyon ayrımı ve sıradaki tek adımı yaz. Çalıştırmadığın test için PASS yazma.

## ACC-01 — JWT nerede kalır?

Identity kayıt/giriş/parola/refresh/JWKS ve imzalama anahtarının sahibidir. Account JWT üretmez. Account, Simulation, Reporting, Strategy ve diğer korunan API'ler resource server olarak token'ı kendileri doğrular; Gateway tek güvenlik kontrolü değildir. Account'un örnek filter chain'i B08A'dadır.

Her serviste `infrastructure.config` altında kendi SecurityConfig'i; `api.exception` altında kendi HTTP hata eşlemesi bulunur. Ortaklaştırılan şey issuer/audience/algoritma politikası ve test sözleşmesidir; kullanıcı entity/repository'si paylaşılmaz. Parola hash'i ve refresh session'lar Identity DB dışına taşınmaz.

B04 security checklist ekleri: eksik exp veya bozuk UUID sub reddi, sabit izinli RS256 algoritması, güvenilir JWKS adresi, 401/403 filter hata zarfı, authenticated owner filtresi. TRAINER/ADMIN rolü `/account/me` owner kapsamını genişletmez. Cookie kullanılan refresh/logout'ta mevcut Origin/özel CSRF header kontrolünü koru; bearer-only Account csrf.disable örneğini cookie endpoint'lerine kopyalama.

Register/login/refresh/logout endpoint'leri JSON content type kabul edecek şekilde `consumes="application/json"` ile sınırlandırılır. Refresh/logout body kullanılmasa da istemci boş `{}` JSON ve `Content-Type: application/json` gönderir; Origin ve X-CSRF kontrolleri yine zorunludur.

## Eureka güven sınırı

Identity bir Eureka client’tır; JWT/refresh sahibi olmaya devam eder. Registry’nin localhost dashboard’u uygulama login’i değildir. `jwt.issuer` ve `jwt.jwks-url` değerlerine lb:// yazılmaz; B01A’nın sabit güven adresi kuralı uygulanır. Bütün downstream JWT/owner kontrolleri sürer.
