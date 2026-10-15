# B10 — Eğitim yönetimi facade’ı

> **DISC-01:** Eureka/local adres çözümlemesi için B01A-Eureka-ve-Servis-Kesfi.md uygulanır. Uygulama ayarları properties olarak kalır; iş kuralları değişmez.

Şartname: 3.0 · Contract: 1.0.0 · Öğretici kod referansı · Durum: BUILD_UNVERIFIED

ACC-01 eki: Account, servis sahipliği ve properties standardı için [güncel mimari eki](03-ACCOUNT-VE-GUVENLIK-EKI.md) geçerlidir.
TrainingFacade eğitim yetkisini kontrol edip isteği AI eğitim servisine taşır. Java PDF parse etmez, embedding çıkarmaz, model eğitmez. 202 yalnız AI tarafında kalıcı job kabulünün gerçekleştiğini gösterir.

Bu dosyadaki kodları kendi projenize sırayla elle yazın. Burada uygulama reposu kurulmuş veya kabul testleri geçmiş değildir. Dosya yolları CryptoLab köküne göredir. Bir sınıfı yazdıktan sonra küçük deneyi yapın; tüm belgeyi tek hamlede kopyalamayın.

## Bu dersi nasıl okuyacaksın? — Training facade sınırını korumak

**Önce kavram:** TrainingController ve TrainingJsonController dış HTTP yollarını tutar. TrainingProvider portu iş yeteneğini tarif eder. HttpTrainingProvider sınırlı timeout/boyut ile AI servisine gider. Generic JSON geçişi ContractGate doğrulamasını kaldırmaz.

**Yazma sırası:** TrainingProvider → HTTP adaptörü → controller DTO/şema doğrulaması → role/authority guard → projection/job bağlantısı.

**Neden bu ayrım var?** Facade Python eğitim işini Java içine taşımaz. USER için trainer endpoint’leri açılmaz. MODEL_PUBLISH, TRAINER rolüyle aynı yetki değildir. URL import kendi başına Java HTTP fetch’ine çevrilmez.

**Küçük pratik:** USER ile 403, TRAINER ile uygun işlem, MODEL_PUBLISH olmadan promotion reddi dene. Training servisi kapalıyken hazır inference/manual işlem bağımsız sürmeli.

Ortak kurallar: [Paket ve kod standardı](01-PAKET-VE-KOD-STANDARDI.md). Dosyaların tek tek yeni konumları: [Sınıf haritası](02-SINIF-VE-PAKET-HARITASI.md). Bu dosyada verilen yeni package/import/path birlikte kullanılmalıdır; eski ve yeni kopyaları aynı projeye ekleme.

### Bu görevde sınıflar hangi sorumlulukta?

| Sınıf | Paket | Neden burada? |
|---|---|---|
| `TrainingProvider` | `application.port` | Dış teknik yeteneği soyutlar. |
| `TrainingController` | `api.controller` | HTTP isteğini uygulama akışına bağlar. |
| `HttpTrainingProvider` | `infrastructure.client` | Dış sistem veya teknik sınırı uygular. |
| `TrainingJsonController` | `api.controller` | HTTP isteğini uygulama akışına bağlar. |
| `ContractGate` | `api.validation` | HTTP sözleşmesi doğrulama portu; adaptörü açık iş. |

## Kaynak görevlerin eksiksiz kontrol listesi

- [ ] **B10.1** README 7.4 endpoint’lerini source/example/dataset/run/model gruplarına eşleştir; A06–A09 owner’larını not et.
- [ ] **B10.2** USER 403; TRAINER ingest/train; MODEL_PUBLISH promotion/rollback kontrolünü uygula.
- [ ] **B10.3** Bounded upload/import isteğini internal AI’ye taşı; durable job sonrası 202; uzun parse bekleme yok.
- [ ] **B10.4** Review/freeze/train/cancel/detail façade; idempotency scope ve error mapping aynı olsun.
- [ ] **B10.5** training.jobs.v1 projection; status ve stage’i birbirine karıştırma; actual detail AI otoritesinden.
- [ ] **B10.6** Promotion expectedAliasVersion ve audit; eski planın model/evidence geçmişini değiştirme.
- [ ] **B10.7** Eğitim servisi kapalıyken hazır model/manual trade etkilenmesin; gerçek provider bağlantısı hazır oldukça fake’in yerine tak.

## B10.1 — Endpoint haritası

Her satır `/api/v1/strategy/training` dış prefix’inden `/internal/training` iç prefix’ine taşınır. DTO anlamı README7.4’tür; rastgele catch-all proxy ile tüm internal API açılmaz.

| Dış işlem | İç işlem | Yetki / cevap |
|---|---|---|
| POST sources | POST sources | TRAINER/ADMIN, 202 SourceJob |
| POST source-imports | POST source-imports | TRAINER/ADMIN, 202 |
| GET sources ve sources/{id} | aynı yol | actor/owner filtreli |
| GET sources/{id}/pages/{page} | aynı yol | owner, güvenli preview |
| DELETE sources/{id} | aynı yol | expectedVersion, 202 |
| POST example-jobs | aynı yol | 202 |
| GET examples | aynı yol | dataset/cursor |
| PATCH examples/{id} | aynı yol | expectedVersion, 200 |
| POST datasets | aynı yol | immutable manifest, 201 |
| POST runs | aynı yol | task/dataset/config/seed, 202 |
| GET runs/{id}, POST runs/{id}/cancel | aynı yol | owner / 202 |
| GET models ve models/{id} | aynı yol | ModelCard |
| POST models/{id}/promote | aynı yol | MODEL_PUBLISH ek yetki |
| POST models/{id}/rollback | aynı yol | MODEL_PUBLISH ek yetki |

A06 kaynak hazırlığı, A07 reviewed dataset, A08 training, A09 eval/promotion sahibidir. Backend bu sonuçları taklit ederek gerçek eğitim ilan etmez.

### `backend/strategy-service/src/main/java/com/cryptolab/strategy/application/port/TrainingProvider.java`

**Sınıfı yazmadan önce:** Dış teknik yeteneği soyutlar. Fixture ve gerçek adaptör aynı sözleşmeyi uygular; service somut client bilmez.

```java
package com.cryptolab.strategy.application.port;
import java.util.UUID;
import java.io.InputStream;
/**
 * Sorumluluk: Dış teknik yeteneği soyutlar.
 * Okuma ipucu: Fixture ve gerçek adaptör aynı sözleşmeyi uygular; service somut client bilmez.
 */
public interface TrainingProvider {
 record Actor(UUID id,String roles,String authorities){}
 record Reply(int status,String contentType,byte[] body){}
 Reply json(Actor actor,String method,String relativePath,String idempotencyKey,byte[] body);
 Reply upload(Actor actor,String idempotencyKey,String title,String author,String rights,
              String filename,long length,InputStream content);
 // Internal sağlayıcı actor scope'u yeniden doğrular. Gateway'den gelen actor header kullanılmaz.
}
```

### `backend/strategy-service/src/main/java/com/cryptolab/strategy/api/controller/TrainingController.java`

**Sınıfı yazmadan önce:** HTTP isteğini uygulama akışına bağlar. DTO doğrulamasını, principal kaynağını ve HTTP cevabını takip et; SQL burada bulunmaz.

```java
package com.cryptolab.strategy.api.controller;
import com.cryptolab.strategy.api.validation.ContractGate;
import com.cryptolab.strategy.application.port.TrainingProvider;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.*;
import org.springframework.web.multipart.MultipartFile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
/**
 * Sorumluluk: HTTP isteğini uygulama akışına bağlar.
 * Okuma ipucu: DTO doğrulamasını, principal kaynağını ve HTTP cevabını takip et; SQL burada bulunmaz.
 */
@RestController
@RequestMapping("/api/v1/strategy/training")
@PreAuthorize("hasAnyRole('TRAINER','ADMIN')")
public class TrainingController {
 private final TrainingProvider provider;private final ObjectMapper json;
 public TrainingController(TrainingProvider provider,ObjectMapper json){this.provider=provider;this.json=json;}
 private TrainingProvider.Actor actor(Jwt jwt){
  var roles=jwt.getClaimAsStringList("roles");var rights=jwt.getClaimAsStringList("authorities");
  return new TrainingProvider.Actor(UUID.fromString(jwt.getSubject()),roles==null?"":String.join(",",roles),rights==null?"":String.join(",",rights));
 }
 private ResponseEntity<byte[]> response(TrainingProvider.Reply reply){
  // Status/DTO schema doğrulaması adapter'da; arbitrary header veya signed URL geçirme.
  return ResponseEntity.status(reply.status()).contentType(MediaType.parseMediaType(reply.contentType())).body(reply.body());
 }
 @PostMapping(value="/sources",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
 ResponseEntity<byte[]> upload(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key")String key,
  @RequestPart("file")MultipartFile file,@RequestParam String title,@RequestParam(required=false)String author,@RequestParam String rightsDeclaration)throws Exception{
  if(file.isEmpty()||file.getSize()>20L*1024*1024)throw new IllegalArgumentException("UPLOAD_SIZE");
  if(title.isBlank()||title.length()>200||rightsDeclaration.isBlank()||rightsDeclaration.length()>4000)throw new IllegalArgumentException("INVALID_SOURCE_METADATA");
  // Filename kullanıcı girdisidir; path olarak kullanılmaz. Sunucu sabit aktarım adı seçer.
  try(var stream=file.getInputStream()){return response(provider.upload(actor(jwt),key,title,author,rightsDeclaration,"source.pdf",file.getSize(),stream));}
 }
 @PostMapping("/models/{id}/promote")
 @PreAuthorize("hasAnyRole('TRAINER','ADMIN') and hasAuthority('MODEL_PUBLISH')")
 ResponseEntity<byte[]> promote(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestHeader("Idempotency-Key")String key,@RequestBody JsonNode body)throws Exception{
  requireFields(body,Set.of("expectedAliasVersion","evaluationId"));
  return response(provider.json(actor(jwt),"POST","/models/"+id+"/promote",key,json.writeValueAsBytes(body)));
 }
 @PostMapping("/models/{id}/rollback")
 @PreAuthorize("hasAnyRole('TRAINER','ADMIN') and hasAuthority('MODEL_PUBLISH')")
 ResponseEntity<byte[]> rollback(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestHeader("Idempotency-Key")String key,@RequestBody JsonNode body)throws Exception{
  requireFields(body,Set.of("expectedAliasVersion","reason"));
  return response(provider.json(actor(jwt),"POST","/models/"+id+"/rollback",key,json.writeValueAsBytes(body)));
 }
 static void requireFields(JsonNode node,Set<String> allowed){
  if(!node.isObject())throw new IllegalArgumentException("INVALID_BODY");
  node.fieldNames().forEachRemaining(f->{if(!allowed.contains(f))throw new IllegalArgumentException("UNKNOWN_FIELD");});
  for(String f:allowed)if(!node.hasNonNull(f))throw new IllegalArgumentException("MISSING_FIELD");
 }
}
```

Diğer controller metotlarını tabloya göre explicit yaz. JSON body’yi JsonNode ile taşısan bile ilgili contract schema ile type/required/unknown alan doğrulaması gerekir. `requireFields` yalnız alan kümesi örneğidir; expectedAliasVersion≥1, UUID/evaluationId ve reason uzunluğu ayrıca doğrulanır. JsonNode kullanımı strict DTO kontrolünü kendiliğinden sağlamaz.

## B10.2–B10.4 — İç istemci, timeout ve idempotency

Client yalnız sabit AI base URL’e gider; dış request’te host seçilemez. Secret + actor + requestId + deadline server tarafından eklenir. Mutation key aynen korunur. İç servis `(actor,route,scope,key,requestHash)` ile dedupe eder. Gateway/facade timeout olsa bile AI job kalıcı olabilir; yeni key ile tekrar üretme.

### `backend/strategy-service/src/main/java/com/cryptolab/strategy/infrastructure/client/HttpTrainingProvider.java`

**Sınıfı yazmadan önce:** Dış sistem veya teknik sınırı uygular. Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.

```java
package com.cryptolab.strategy.infrastructure.client;
import com.cryptolab.strategy.application.port.TrainingProvider;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
/**
 * Sorumluluk: Dış sistem veya teknik sınırı uygular.
 * Okuma ipucu: Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.
 */
public class HttpTrainingProvider implements TrainingProvider {
 private final URI base;private final String secret;
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
 public HttpTrainingProvider(URI base,String secret){this.base=base;this.secret=secret;}
 private HttpRequest.Builder request(Actor actor,String path,String key){
  if(!path.startsWith("/")||path.contains("..")||path.contains("://"))throw new IllegalArgumentException("INVALID_INTERNAL_PATH");
  var b=HttpRequest.newBuilder(base.resolve("/internal/training"+path)).timeout(Duration.ofSeconds(30))
   .header("X-Service-Secret",secret).header("X-Actor-Id",actor.id().toString())
   .header("X-Actor-Roles",actor.roles()).header("X-Actor-Authorities",actor.authorities())
   .header("X-Request-Id",UUID.randomUUID().toString()).header("X-Deadline",java.time.Instant.now().plusSeconds(30).toString());
  if(key!=null){if(key.isBlank()||key.length()>128)throw new IllegalArgumentException("INVALID_KEY");b.header("Idempotency-Key",key);}return b;
 }
 private Reply send(HttpRequest request){
  try{
   var r=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
   try(var in=r.body()){
    byte[] body=in.readNBytes(1_048_577);if(body.length>1_048_576)throw new IllegalStateException("TRAINING_RESPONSE_TOO_LARGE");
    // İç hata stack trace'ini dışarı taşımak yerine API zarfına map et.
    if(r.statusCode()>=500)throw new IllegalStateException("TRAINING_UNAVAILABLE");
    return new Reply(r.statusCode(),r.headers().firstValue("Content-Type").orElse("application/json"),body);
   }
  }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("TRAINING_CANCELLED",e);}
  catch(IOException e){throw new IllegalStateException("TRAINING_UNAVAILABLE",e);}
 }
 public Reply json(Actor actor,String method,String path,String key,byte[] body){
  if(body!=null&&body.length>1_048_576)throw new IllegalArgumentException("BODY_TOO_LARGE");
  return send(request(actor,path,key).header("Content-Type","application/json")
   .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body)).build());
 }
 private static byte[] field(String boundary,String name,String value){
  return ("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+name+"\"\r\n\r\n"+value+"\r\n").getBytes(StandardCharsets.UTF_8);
 }
 public Reply upload(Actor actor,String key,String title,String author,String rights,String filename,long length,InputStream content){
  String boundary="cryptolab-"+UUID.randomUUID();
  if(length<=0||length>20L*1024*1024)throw new IllegalArgumentException("UPLOAD_SIZE");
  List<InputStream> parts=new ArrayList<>();
  parts.add(new ByteArrayInputStream(field(boundary,"title",title)));
  if(author!=null)parts.add(new ByteArrayInputStream(field(boundary,"author",author)));
  parts.add(new ByteArrayInputStream(field(boundary,"rightsDeclaration",rights)));
  String head="--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"source.pdf\"\r\nContent-Type: application/pdf\r\n\r\n";
  parts.add(new ByteArrayInputStream(head.getBytes(StandardCharsets.UTF_8)));
  // Dosyayı getBytes() ile belleğe toplamayız. Servlet ve facade sınırı önceden uygulanır.
  parts.add(content);parts.add(new ByteArrayInputStream(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8)));
  var stream=new SequenceInputStream(Collections.enumeration(parts));
  var used=new java.util.concurrent.atomic.AtomicBoolean();
  var publisher=HttpRequest.BodyPublishers.ofInputStream(()->{if(!used.compareAndSet(false,true))throw new IllegalStateException("NON_REPEATABLE_UPLOAD");return stream;});
  return send(request(actor,"/sources",key).header("Content-Type","multipart/form-data; boundary="+boundary).POST(publisher).build());
 }
}
```

### `backend/strategy-service/src/main/resources/application.properties — multipart ekleri`

```properties
# Multipart disk'e spool edilir; temp alanına kota/temizlik uygulanır.
# Gateway toplam body limitini metadata payı nedeniyle 21MB ile uyumlu tut.
spring.servlet.multipart.max-file-size=20MB
spring.servlet.multipart.max-request-size=21MB
spring.servlet.multipart.file-size-threshold=0
```

Facade stream’i iletmeden dosya tipi/hash kontrolünün asıl sahibi AI importer’dır; sadece Content-Type=application/pdf güvence değildir. URL import SSRF koruması (private/link-local ağ reddi, redirect ve resolved IP tekrar kontrolü, içerik sınırı) AI importer’da uygulanır. Java kendi HTTP fetch’iyle bu sınırı delmez.

## B10.5 — Projection

`training.jobs.v1` eventId inbox dedupe + job projection aynı TX. Job status QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED; stage EXTRACTING/REVIEW_REQUIRED gibi alt aşamadır. Metadata extraction SUCCEEDED olsa bile model eğitimi yapılmış değildir. Kafka event’i PDF text/model binary içermez. Progress yoksa yüzde uydurma; null göster.

```sql
CREATE TABLE training_job_projection(job_id uuid PRIMARY KEY,status text NOT NULL,stage text NOT NULL,
 progress jsonb,updated_at timestamptz NOT NULL);
-- Önce inbox unique kontrolü. Daha eski projection yeni state'i ezmesin.
INSERT INTO training_job_projection VALUES (:id,:status,:stage,:progress,:updated)
ON CONFLICT(job_id) DO UPDATE SET status=EXCLUDED.status,stage=EXCLUDED.stage,
 progress=EXCLUDED.progress,updated_at=EXCLUDED.updated_at
WHERE training_job_projection.updated_at<=EXCLUDED.updated_at;
```

Aynı timestamp farklı sürüm mümkünse event aggregateVersion ile optimistic gate ekle; timestamp tek başına kesin sıra değildir. Detay sorgusu AI otoritesinden gelir; projection yalnız hızlı durum görünümüdür.

## B10.6–B10.7 — Promotion ve dayanıklılık

Promotion expectedAliasVersion ile compare-and-swap; evaluationId A09 kalite gate’ini referanslar. Alias başka istekçe değiştiyse409. Mevcut ACTIVE model başarısız adaya geçmez. Rollback de MODEL_PUBLISH ve audit gerektirir. Eski proposal modelBundleId/featureSnapshotId değişmeden kalır.

Training provider kapalı: training503; hazır model inference ve manuel işlem devam eder. Fake provider profile=fixture açık etiketli SourceJob döndürebilir; task NUMERIC/SFT eğitimi yaptığı iddia edilmez.

**Test:** USER her route403; TRAINER promote403; publish authority + eski alias409. Büyük dosya413; key tekrar aynı job; farklı body409. AI202 almadan dış202 yok. Cancel durable yanıt. Source owner A, B erişimi reddedilir. Eğitim worker kapatıldığında manual close hâlâ çalışır.

## Ek kod — Diğer JSON endpoint eşlemeleri

### `backend/strategy-service/src/main/java/com/cryptolab/strategy/api/controller/TrainingJsonController.java`

Bu controller promotion/upload sınıfıyla çakışmaz. ContractGate ayrı schema validator adapter’ıdır; validation yapılmadan endpoint açılmaz. Sources/{id}/pages/{page} binary response olduğu için bounded streaming download adapter’ıyla ayrıca taşınır.

**Sınıfı yazmadan önce:** HTTP isteğini uygulama akışına bağlar. DTO doğrulamasını, principal kaynağını ve HTTP cevabını takip et; SQL burada bulunmaz.

```java
package com.cryptolab.strategy.api.controller;
import com.cryptolab.strategy.api.validation.ContractGate;
import com.cryptolab.strategy.application.port.TrainingProvider;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.*;
import java.util.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
/**
 * Sorumluluk: HTTP isteğini uygulama akışına bağlar.
 * Okuma ipucu: DTO doğrulamasını, principal kaynağını ve HTTP cevabını takip et; SQL burada bulunmaz.
 */
@RestController
@RequestMapping("/api/v1/strategy/training")
@PreAuthorize("hasAnyRole('TRAINER','ADMIN')")
public class TrainingJsonController {
 private final TrainingProvider provider;private final ContractGate contracts;
 public TrainingJsonController(TrainingProvider provider,ContractGate contracts){this.provider=provider;this.contracts=contracts;}
 private ResponseEntity<byte[]> forward(Jwt jwt,String method,String path,String key,byte[] body,Map<String,String> query){
  // ContractGate ilgili JSON Schema'yı çalıştırır; generic proxy doğrulamayı kaldırmaz.
  contracts.validate(method,path,body);
  var roles=jwt.getClaimAsStringList("roles");var auth=jwt.getClaimAsStringList("authorities");
  var actor=new TrainingProvider.Actor(UUID.fromString(jwt.getSubject()),roles==null?"":String.join(",",roles),auth==null?"":String.join(",",auth));
  var allowed=Set.of("cursor","datasetId","expectedVersion");
  var parts=new ArrayList<String>();
  query.forEach((k,v)->{if(!allowed.contains(k))throw new IllegalArgumentException("UNKNOWN_QUERY");parts.add(URLEncoder.encode(k,StandardCharsets.UTF_8)+"="+URLEncoder.encode(v,StandardCharsets.UTF_8));});
  var result=provider.json(actor,method,path+(parts.isEmpty()?"":"?"+String.join("&",parts)),key,body);
  return ResponseEntity.status(result.status()).contentType(MediaType.parseMediaType(result.contentType())).body(result.body());
 }
 @PostMapping("/source-imports") ResponseEntity<byte[]> sourcesImport(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key")String key,@RequestBody(required=false)byte[] body){
  return forward(jwt,"POST","/source-imports",key,body,Map.of());
 }
 @GetMapping("/sources") ResponseEntity<byte[]> sources(@AuthenticationPrincipal Jwt jwt,@RequestParam Map<String,String> query){
  return forward(jwt,"GET","/sources",null,null,query);
 }
 @GetMapping("/sources/{id}") ResponseEntity<byte[]> source(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestParam Map<String,String> query){
  return forward(jwt,"GET","/sources/"+id+"",null,null,query);
 }
 @DeleteMapping("/sources/{id}") ResponseEntity<byte[]> deleteSource(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestHeader("Idempotency-Key")String key,@RequestBody(required=false)byte[] body){
  return forward(jwt,"DELETE","/sources/"+id+"",key,body,Map.of());
 }
 @PostMapping("/example-jobs") ResponseEntity<byte[]> examplesJob(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key")String key,@RequestBody(required=false)byte[] body){
  return forward(jwt,"POST","/example-jobs",key,body,Map.of());
 }
 @GetMapping("/examples") ResponseEntity<byte[]> examples(@AuthenticationPrincipal Jwt jwt,@RequestParam Map<String,String> query){
  return forward(jwt,"GET","/examples",null,null,query);
 }
 @PatchMapping("/examples/{id}") ResponseEntity<byte[]> review(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestHeader("Idempotency-Key")String key,@RequestBody(required=false)byte[] body){
  return forward(jwt,"PATCH","/examples/"+id+"",key,body,Map.of());
 }
 @PostMapping("/datasets") ResponseEntity<byte[]> dataset(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key")String key,@RequestBody(required=false)byte[] body){
  return forward(jwt,"POST","/datasets",key,body,Map.of());
 }
 @PostMapping("/runs") ResponseEntity<byte[]> run(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key")String key,@RequestBody(required=false)byte[] body){
  return forward(jwt,"POST","/runs",key,body,Map.of());
 }
 @GetMapping("/runs/{id}") ResponseEntity<byte[]> runDetail(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestParam Map<String,String> query){
  return forward(jwt,"GET","/runs/"+id+"",null,null,query);
 }
 @PostMapping("/runs/{id}/cancel") ResponseEntity<byte[]> cancel(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestHeader("Idempotency-Key")String key,@RequestBody(required=false)byte[] body){
  return forward(jwt,"POST","/runs/"+id+"/cancel",key,body,Map.of());
 }
 @GetMapping("/models") ResponseEntity<byte[]> models(@AuthenticationPrincipal Jwt jwt,@RequestParam Map<String,String> query){
  return forward(jwt,"GET","/models",null,null,query);
 }
 @GetMapping("/models/{id}") ResponseEntity<byte[]> model(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestParam Map<String,String> query){
  return forward(jwt,"GET","/models/"+id+"",null,null,query);
 }
}
```



### `backend/strategy-service/src/main/java/com/cryptolab/strategy/api/validation/ContractGate.java`

**Sınıfı yazmadan önce:** Nested controller interface yerine bağımsız doğrulama kontraktı. Alan/kural uydurmaz; mevcut schema dosyalarını uygular.

```java
package com.cryptolab.strategy.api.validation;
/** Bir route'un mevcut JSON Schema sözleşmesini uygulayan doğrulama sınırı. */
public interface ContractGate {
    void validate(String method, String path, byte[] body);
    // Schema validator adaptörü yazılmadan endpoint hazır sayılmaz.
    // Bu interface örneği tek başına çalışan bean üretmez.
}
```

## Kabul: hangi çıktıyı görmelisin?

**Deneme/kabul:** Büyük/bozuk body route limit, cross-user source erişimi, unauthorized publish, duplicate request, cancel ve job failure. Trading request’inde PDF alanı yok. Spring PDF extraction veya model fit yapmaz. Fake eğitim sonucu gerçek eğitim diye raporlanmaz.



## Kendi cümlelerinle anlat

“Dosya yüklemede 202 aldık; bu neden model eğitildi anlamına gelmez?”

## Çalışma sonunda

`progress/BACKEND.md` içine alt görev, değiştirdiğin dosya, komut, gerçek çıktı, fixture/gerçek entegrasyon ayrımı ve sıradaki tek adımı yaz. Çalıştırmadığın test için PASS yazma.
