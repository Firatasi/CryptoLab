# B03 — PostgreSQL, ledger ve kalıcı session

> **DISC-01:** Eureka/local adres çözümlemesi için B01A-Eureka-ve-Servis-Kesfi.md uygulanır. Uygulama ayarları properties olarak kalır; iş kuralları değişmez.

Şartname: 3.0 · Contract: 1.0.0 · Öğretici kod referansı · Durum: BUILD_UNVERIFIED

ACC-01 eki: Account, servis sahipliği ve properties standardı için [güncel mimari eki](03-ACCOUNT-VE-GUVENLIK-EKI.md) geçerlidir.
Saf hesapların sonucu kalıcı olmalı ve iki istek aynı parayı aynı anda harcayamamalı. Bu ders JDBC ile SQL’i görünür tutar; JPA zorunlu değildir. Entity ilişkilerini ORM arkasına saklamadan transaction, row lock ve constraint öğreneceğiz.

Bu dosyadaki kodları kendi projenize sırayla elle yazın. Burada uygulama reposu kurulmuş veya kabul testleri geçmiş değildir. Dosya yolları CryptoLab köküne göredir. Bir sınıfı yazdıktan sonra küçük deneyi yapın; tüm belgeyi tek hamlede kopyalamayın.

## Bu dersi nasıl okuyacaksın? — Service, repository portu ve JDBC adaptörü

**Önce kavram:** CreateSession işlemin bütününü yönetir. SessionRepository session/hesap iskeletini yazar. LedgerRepository finans kaydını yazar. JdbcLedgerStore bu portun SQL implementasyonudur. Service iki repository çağrısını aynı transaction içinde birleştirir.

**Yazma sırası:** Journal → LedgerRepository/SessionRepository → JdbcLedgerStore/JdbcSessionRepository → EngineConfig → CreateSession → LedgerIT.

**Neden bu ayrım var?** Repository interface uygulama ihtiyacını tarif eder; @Repository annotation ise somut Spring bileşenini işaretler. Aynı şey değillerdir. Bakiye doğrudan SessionRepository içinde artırılmaz; journal karşı kaydı korunur.

**Küçük pratik:** CreateSession constructor’ına neden JdbcLedgerStore değil LedgerRepository verdiğimizi açıkla. Journal yazımında hata oluştuğunda session satırının da rollback olduğunu gerçek DB ile doğrula.

Ortak kurallar: [Paket ve kod standardı](01-PAKET-VE-KOD-STANDARDI.md). Dosyaların tek tek yeni konumları: [Sınıf haritası](02-SINIF-VE-PAKET-HARITASI.md). Bu dosyada verilen yeni package/import/path birlikte kullanılmalıdır; eski ve yeni kopyaları aynı projeye ekleme.

### Bu görevde sınıflar hangi sorumlulukta?

| Sınıf | Paket | Neden burada? |
|---|---|---|
| `Journal` | `domain.model` | İş verisini ve kendi değişmez kurallarını temsil eder. |
| `EngineConfig` | `infrastructure.config` | Teknik nesneleri ve Spring bağlantılarını kurar. |
| `JdbcLedgerStore` | `infrastructure.repository` | Portun SQL veya okuma modeli kalıcılığını uygular. |
| `CreateSession` | `application.service` | Bir kullanım senaryosunun sırasını yönetir. |
| `LedgerIT` | `servis kökü` | Ders davranışını doğrulayan testtir. |
| `LedgerRepository` | `application.repository` | Kalıcılık davranışını arayüz olarak tanımlar. |
| `SessionRepository` | `application.repository` | Kalıcılık davranışını arayüz olarak tanımlar. |
| `JdbcSessionRepository` | `infrastructure.repository` | Portun SQL veya okuma modeli kalıcılığını uygular. |

## Kaynak görevlerin eksiksiz kontrol listesi

- [ ] **B03.1** Yerel PostgreSQL bağlantısını sen kur; Flyway migration ve ddl-auto=validate uygula. docker komutu/volume etkisi açıklansın.
- [ ] **B03.2** Session ve available/reserved/isolated/system hesap modellerini çiz; owner_id testte sabit subject olabilir, public auth bypass oluşturma.
- [ ] **B03.3** Başlangıç sanal sermaye journal’ını yaz; posting toplamı sıfır olsun. Bakiye snapshot’ı ve journal tek transaction.
- [ ] **B03.4** BusinessKey unique ile duplicate finans hareketini önle; exception rollback’i gözlemle.
- [ ] **B03.5** Session row lock ve version alanlarını yaz; iki eşzamanlı test cash’i iki kere tüketemesin.
- [ ] **B03.6** Inbox/outbox ve pending_engine_inputs için migration/sözleşme hazırla; Kafka publish B06’da bağlanacak.
- [ ] **B03.7** Wallet’ı ledger’den yeniden kurup snapshot ile karşılaştır; restart sonrası session kaybolmasın.

## B03.1 — Bağımlılık ve veritabanı

Simulation POM `<dependencies>` içine aşağıdakileri ekle. Aynı bağımlılıklar kendi DB’si gereken Identity/Market/Strategy/Reporting modüllerine ilgili derste eklenecek. `ddl-auto=validate` JPA kullanıldığında geçerlidir; bu örnek JDBC olduğu için Hibernate tablo üretmez. Flyway tek şema sahibidir.

```xml
<!-- JdbcTemplate SQL parametrelerini bağlar; string birleştirerek SQL yazmayız. -->
<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-jdbc</artifactId></dependency>
<dependency><groupId>org.flywaydb</groupId><artifactId>flyway-core</artifactId></dependency>
<dependency><groupId>org.flywaydb</groupId><artifactId>flyway-database-postgresql</artifactId></dependency>
<dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>runtime</scope></dependency>
<dependency><groupId>org.testcontainers</groupId><artifactId>postgresql</artifactId><scope>test</scope></dependency>
<dependency><groupId>org.testcontainers</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
```

Şifreleri `.env` içinde yerel olarak belirle; gerçek değerleri git’e ekleme. Docker volume veriyi tutar; `down -v` bütün geliştirme DB verisini siler. Normal durdurmada `docker compose ... down` yeterli. Migration değiştiyse uygulanmış V1’i düzenleme; V2 ekle.

### `infra/compose.yaml`

```yaml
services:
  postgres:
    image: postgres:17.6
    profiles: [foundation, offline-demo]
    environment:
      POSTGRES_USER: cryptolab_admin
      POSTGRES_PASSWORD: ${POSTGRES_ADMIN_PASSWORD:?Set a local password}
      SIM_PASSWORD: ${SIM_PASSWORD:?Set a local password}
    ports: ["127.0.0.1:5432:5432"]
    volumes:
      - pg-data:/var/lib/postgresql/data
      - ./postgres-init.sh:/docker-entrypoint-initdb.d/01-init.sh:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U cryptolab_admin -d postgres"]
      interval: 5s
      timeout: 3s
      retries: 20
volumes:
  pg-data:
```

### `infra/postgres-init.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail
# psql değişkeni SQL literal olarak quote edilir; parola SQL stringine yapıştırılmaz.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres --set=sim_password="$SIM_PASSWORD" <<'SQL'
CREATE ROLE simulation_app LOGIN PASSWORD :'sim_password';
CREATE DATABASE simulation_db OWNER simulation_app;
REVOKE CONNECT ON DATABASE simulation_db FROM PUBLIC;
GRANT CONNECT ON DATABASE simulation_db TO simulation_app;
SQL
# Diğer servis DB'lerini ilgili derste aynı kalıpla AYRI role/password ile oluştur.
# Var olan volume'de bu init dosyası tekrar çalışmaz; yeni DB için psql ile uygula.
```

### `backend/simulation-service/src/main/resources/application.properties — eklenecek alanlar`

```properties
# B01 dosyasındaki spring altına birleştir; ikinci spring anahtarı oluşturma.
# jpa ayarı JDBC örneğinde etkisizdir; ileride JPA eklersen tablo yaratmayı önler.
spring.datasource.url=${DB_URL:jdbc:postgresql://localhost:5432/simulation_db}
spring.datasource.username=${DB_USER:simulation_app}
spring.datasource.password=${DB_PASSWORD}
spring.flyway.enabled=true
spring.jpa.hibernate.ddl-auto=validate
```

### `backend/simulation-service/src/main/resources/db/migration/V1__ledger.sql`

```sql
-- Session finans yazımlarının ortak kilit noktasıdır.
CREATE TABLE simulation_sessions (
 id uuid PRIMARY KEY, owner_id uuid NOT NULL,
 mode text NOT NULL CHECK(mode IN ('LIVE_PAPER','HISTORICAL_REPLAY')),
 initial_deposit numeric(38,18) NOT NULL CHECK(initial_deposit>0),
 rule_profile_id uuid NOT NULL, risk_policy_id uuid NOT NULL,
 risk_policy_version bigint NOT NULL DEFAULT 1,
 status text NOT NULL CHECK(status IN ('RUNNING','HALTED','COMPLETED')),
 engine_sequence bigint NOT NULL DEFAULT 0, version bigint NOT NULL DEFAULT 1,
 created_at timestamptz NOT NULL
);
CREATE TABLE wallet_accounts (
 id uuid PRIMARY KEY, session_id uuid NOT NULL REFERENCES simulation_sessions(id),
 account_key text NOT NULL, kind text NOT NULL CHECK(kind IN ('AVAILABLE','RESERVED','ISOLATED','SYSTEM')),
 position_id uuid, balance numeric(38,18) NOT NULL DEFAULT 0,
 UNIQUE(session_id,account_key),
 CHECK(kind NOT IN ('AVAILABLE','RESERVED') OR balance>=0)
);
CREATE TABLE journal_transactions (
 id uuid PRIMARY KEY, session_id uuid NOT NULL REFERENCES simulation_sessions(id),
 business_key text NOT NULL UNIQUE, request_hash text NOT NULL,
 effective_at timestamptz NOT NULL, recorded_at timestamptz NOT NULL, reason text NOT NULL
);
CREATE TABLE postings (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 transaction_id uuid NOT NULL REFERENCES journal_transactions(id),
 account_id uuid NOT NULL REFERENCES wallet_accounts(id),
 amount numeric(38,18) NOT NULL, currency text NOT NULL CHECK(currency='USDT')
);
-- DEFERRABLE: transaction bitmeden bütün posting'ler henüz yazılmamış olabilir.
CREATE FUNCTION assert_balanced_journal() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE jid uuid; total numeric; cnt bigint;
BEGIN
 jid := CASE WHEN TG_TABLE_NAME='journal_transactions' THEN NEW.id ELSE NEW.transaction_id END;
 SELECT coalesce(sum(amount),0),count(*) INTO total,cnt FROM postings WHERE transaction_id=jid;
 IF total<>0 OR cnt<2 THEN RAISE EXCEPTION 'UNBALANCED_JOURNAL'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER journal_complete AFTER INSERT ON journal_transactions
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION assert_balanced_journal();
CREATE CONSTRAINT TRIGGER posting_balanced AFTER INSERT ON postings
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION assert_balanced_journal();
-- Audit satırını değiştirmek yerine reversal+replacement gerekir.
CREATE FUNCTION immutable_financial_row() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'APPEND_ONLY'; END $$;
CREATE TRIGGER no_journal_rewrite BEFORE UPDATE OR DELETE ON journal_transactions
 FOR EACH ROW EXECUTE FUNCTION immutable_financial_row();
CREATE TRIGGER no_posting_rewrite BEFORE UPDATE OR DELETE ON postings
 FOR EACH ROW EXECUTE FUNCTION immutable_financial_row();
CREATE TABLE inbox (consumer text NOT NULL,event_id uuid NOT NULL,received_at timestamptz NOT NULL,
 PRIMARY KEY(consumer,event_id));
CREATE TABLE outbox (event_id uuid PRIMARY KEY,topic text NOT NULL,event_key text NOT NULL,
 payload jsonb NOT NULL,created_at timestamptz NOT NULL,published_at timestamptz,
 lease_until timestamptz,attempts integer NOT NULL DEFAULT 0);
CREATE TABLE idempotency_records (
 subject uuid NOT NULL,route text NOT NULL,scope text NOT NULL,key text NOT NULL,
 request_hash text NOT NULL,result_ref uuid NOT NULL,response_json jsonb,
 PRIMARY KEY(subject,route,scope,key)
);
CREATE TABLE market_inputs (id uuid PRIMARY KEY,payload jsonb NOT NULL,received_at timestamptz NOT NULL);
CREATE TABLE pending_engine_inputs (
 session_id uuid NOT NULL REFERENCES simulation_sessions(id),input_id uuid NOT NULL REFERENCES market_inputs(id),
 state text NOT NULL DEFAULT 'PENDING',PRIMARY KEY(session_id,input_id)
);
CREATE TABLE engine_input_journal (
 session_id uuid NOT NULL REFERENCES simulation_sessions(id),accepted_sequence bigint NOT NULL,
 input_type text NOT NULL,source_ref text NOT NULL,payload jsonb NOT NULL,payload_hash text NOT NULL,
 PRIMARY KEY(session_id,accepted_sequence),UNIQUE(session_id,source_ref)
);
```

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/domain/model/Journal.java`

**Sınıfı yazmadan önce:** İş verisini ve kendi değişmez kurallarını temsil eder. Modelin HTTP veya veritabanı biçimine bağımlı olup olmadığını kontrol et.

```java
package com.cryptolab.simulation.domain.model;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
/**
 * Sorumluluk: İş verisini ve kendi değişmez kurallarını temsil eder.
 * Okuma ipucu: Modelin HTTP veya veritabanı biçimine bağımlı olup olmadığını kontrol et.
 */
public record Journal(UUID id, UUID sessionId, String businessKey, String requestHash,
                      String reason, List<Posting> postings) {
 public record Posting(UUID accountId,BigDecimal amount) {}
 public Journal {
  postings=List.copyOf(postings);
  // DB kontrolüne ek olarak hatayı domain sınırında anlaşılır yakalarız.
  if(postings.size()<2 || postings.stream().map(Posting::amount)
    .reduce(BigDecimal.ZERO,BigDecimal::add).signum()!=0)
   throw new IllegalArgumentException("UNBALANCED_JOURNAL");
 }
}
```

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/infrastructure/config/EngineConfig.java`

**Sınıfı yazmadan önce:** Teknik nesneleri ve Spring bağlantılarını kurar. Bean hangi portu karşılıyor ve hangi profile’da etkin sorularını cevapla.

```java
package com.cryptolab.simulation.infrastructure.config;
import com.cryptolab.simulation.application.replay.VirtualClock;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
/**
 * Sorumluluk: Teknik nesneleri ve Spring bağlantılarını kurar.
 * Okuma ipucu: Bean hangi portu karşılıyor ve hangi profile’da etkin sorularını cevapla.
 */
@Configuration
public class EngineConfig {
 @Bean Clock clock() { return Clock.systemUTC(); }
 // Test Clock.fixed verir; replay kendi VirtualClock'ını motor örneğine verir.
}
```

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/infrastructure/repository/JdbcLedgerStore.java`

**Sınıfı yazmadan önce:** Portun SQL veya okuma modeli kalıcılığını uygular. Parametreli SQL, transaction katılımı, owner/unique/lock kapsamını incele.

```java
package com.cryptolab.simulation.infrastructure.repository;
import com.cryptolab.simulation.domain.model.Journal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;
/**
 * Sorumluluk: Portun SQL veya okuma modeli kalıcılığını uygular.
 * Okuma ipucu: Parametreli SQL, transaction katılımı, owner/unique/lock kapsamını incele.
 */
@Repository
public class JdbcLedgerStore implements com.cryptolab.simulation.application.repository.LedgerRepository {
 private final JdbcTemplate db;
 public JdbcLedgerStore(JdbcTemplate db) { this.db=db; }
 @Transactional(propagation=Propagation.MANDATORY)
 public UUID post(Journal j,Instant effectiveAt,Instant recordedAt) {
  // MANDATORY: çağıran use case transaction açmayı unuttuysa sessiz autocommit yok.
  var old=db.query("SELECT id,request_hash FROM journal_transactions WHERE business_key=?",
    (rs,n)->new String[]{rs.getString(1),rs.getString(2)},j.businessKey());
  if(!old.isEmpty()) {
   if(!old.get(0)[1].equals(j.requestHash())) throw new IllegalStateException("BUSINESS_KEY_CONFLICT");
   return UUID.fromString(old.get(0)[0]);
  }
  db.update("INSERT INTO journal_transactions(id,session_id,business_key,request_hash,effective_at,recorded_at,reason) VALUES (?,?,?,?,?,?,?)",
   j.id(),j.sessionId(),j.businessKey(),j.requestHash(),Timestamp.from(effectiveAt),Timestamp.from(recordedAt),j.reason());
  for(var p:j.postings()) {
   // Başka session hesabına posting yazılmasını update sayısıyla engelleriz.
   int changed=db.update("UPDATE wallet_accounts SET balance=balance+? WHERE id=? AND session_id=?",p.amount(),p.accountId(),j.sessionId());
   if(changed!=1) throw new IllegalStateException("ACCOUNT_SCOPE_MISMATCH");
   db.update("INSERT INTO postings(transaction_id,account_id,amount,currency) VALUES (?,?,?,'USDT')",j.id(),p.accountId(),p.amount());
  }
  return j.id();
 }
}
```

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/application/service/CreateSession.java`

**Sınıfı yazmadan önce:** Bir kullanım senaryosunun sırasını yönetir. Constructor bağımlılıklarını ve transaction sınırını önce oku; hesap ve SQL sorumluluklarını ayır.

```java
package com.cryptolab.simulation.application.service;
import com.cryptolab.finance.Decimal;
import com.cryptolab.simulation.domain.model.Journal;
import com.cryptolab.simulation.application.repository.LedgerRepository;
import com.cryptolab.simulation.application.repository.SessionRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Clock;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
/**
 * Sorumluluk: Bir kullanım senaryosunun sırasını yönetir.
 * Okuma ipucu: Constructor bağımlılıklarını ve transaction sınırını önce oku; hesap ve SQL sorumluluklarını ayır.
 */
@Service
public class CreateSession {
 private final SessionRepository sessions; private final LedgerRepository ledger; private final Clock clock;
 public CreateSession(SessionRepository sessions,LedgerRepository ledger,Clock clock) {this.sessions=sessions;this.ledger=ledger;this.clock=clock;}
 @Transactional
 public UUID execute(UUID owner,BigDecimal deposit,UUID rule,UUID risk) {
  Decimal.positive(deposit);
  // Public endpoint B04/B05'te owner'ı yalnız doğrulanmış JWT'den verir.
  UUID session=UUID.randomUUID(),available=UUID.randomUUID(),capital=UUID.randomUUID();
  var now=clock.instant();var cash=Decimal.cash(deposit);
  if(cash.signum()<=0) throw new IllegalArgumentException("DEPOSIT_TOO_SMALL");
  sessions.create(session,owner,cash,rule,risk,now);
  sessions.createInitialAccounts(session,available,capital);
  // Aynı sayının negatifi: ayrı yuvarlama yapıp dengesizlik üretmeyiz.
  ledger.post(new Journal(UUID.randomUUID(),session,"initial:"+session,"deposit:"+cash.toPlainString(),"INITIAL_CAPITAL",
   List.of(new Journal.Posting(available,cash),new Journal.Posting(capital,cash.negate()))),now,now);
  return session;
 }
}
```

## B03.3–B03.7 — Transaction ve kilidi gözlemle

`@Transactional` Spring proxy’si üzerinden çağrılmalı. Aynı sınıfta `this.execute()` çağrısı proxy’den geçmez. `JdbcLedgerStore.post` ayrı bean olduğu için MANDATORY kontrolü çalışır. Hata fırlatıldığında journal, posting ve snapshot birlikte geri alınır. Exception’ı yakalayıp başarılı dönmek rollback’i bozabilir.

Bir session’a yazan her use case transaction başında şu sorguyu çalıştırır:

```sql
-- owner filtresi kaynağın yetki sınırıdır; UUID bilmek erişim yetkisi değildir.
SELECT id,version FROM simulation_sessions WHERE id=:id AND owner_id=:owner FOR UPDATE;
```

Rebuild sorgusu snapshot ile karşılaştırma içindir; sessizce bozuk snapshot’ı değiştirme:

```sql
SELECT a.id,a.balance,coalesce(sum(p.amount),0) AS rebuilt
FROM wallet_accounts a LEFT JOIN postings p ON p.account_id=a.id
WHERE a.session_id=:session GROUP BY a.id,a.balance
HAVING a.balance<>coalesce(sum(p.amount),0);
```

Beklenen sonuç **0 satır**. SYSTEM hesabı negatif olabilir; tüm hesaplara balance>=0 koymak INITIAL_CAPITAL karşı kaydını bozar. Funding B’yi azaltabildiğinden ISOLATED için de aynı genel kısıt doğru değildir.

### `backend/simulation-service/src/test/java/com/cryptolab/simulation/LedgerIT.java`

**Sınıfı yazmadan önce:** Davranışı doğrulayan test. Beklenen sonucu bağımsız belirle; testin gerçekten çalıştığını raporla.

```java
package com.cryptolab.simulation;
import com.cryptolab.simulation.application.service.CreateSession;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.math.BigDecimal;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
/**
 * Sorumluluk: Davranışı doğrulayan test.
 * Okuma ipucu: Beklenen sonucu bağımsız belirle; testin gerçekten çalıştığını raporla.
 */
@Testcontainers
@SpringBootTest
class LedgerIT {
 @Container static PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:17.6");
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
  r.add("spring.datasource.url",pg::getJdbcUrl);r.add("spring.datasource.username",pg::getUsername);r.add("spring.datasource.password",pg::getPassword);
 }
 @Autowired CreateSession create; @Autowired JdbcTemplate db;
 @Test void initialCapitalBalances() {
  UUID id=create.execute(UUID.randomUUID(),new BigDecimal("10000"),UUID.randomUUID(),UUID.randomUUID());
  var balance=db.queryForObject("SELECT balance FROM wallet_accounts WHERE session_id=? AND account_key='AVAILABLE'",BigDecimal.class,id);
  assertEquals(0,new BigDecimal("10000").compareTo(balance));
  var total=db.queryForObject("SELECT sum(p.amount) FROM postings p JOIN journal_transactions j ON j.id=p.transaction_id WHERE j.session_id=?",BigDecimal.class,id);
  assertEquals(0,total.signum());
 }
}
```

`*IT` varsayılan Surefire seçiminde çalışmayabilir. Şimdilik `mvn -f backend/pom.xml -pl simulation-service -am test -Dtest=LedgerIT -Dsurefire.failIfNoSpecifiedTests=false` ile açıkça seç; B12 Failsafe ekler.

**Yarış pratiği:** iki ayrı DB bağlantısında aynı session için BEGIN + FOR UPDATE yap. İkinci bağlantı beklemeli; ilkinde COMMIT sonrası devam etmeli. Üçüncü bağımsız session kilitlenmemeli. Finans testinde iki thread’i latch ile aynı anda başlatıp aynı bakiyenin iki kez harcanmadığını doğrula; sırayla yapılan iki çağrı concurrency testi değildir.

**Rollback pratiği:** test transaction’ında journal ekledikten sonra RuntimeException üret. Transaction bittikten sonra başka bağlantıdan journal ve balance sorgula; hiçbir yarım etki olmamalı. B06’ya kadar outbox tablosu hazırdır, yayıncı yoktur.

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/application/repository/LedgerRepository.java`

Repository portu iş servisinin ihtiyaç duyduğu davranışı tarif eder. Genel save(Object) yerine yapılan işin adı olan post kullanılır.

**Sınıfı yazmadan önce:** Kalıcılık davranışını arayüz olarak tanımlar. Service bu arayüze bağımlıdır; SQL implementasyonu infrastructure altındadır.

```java
package com.cryptolab.simulation.application.repository;
import com.cryptolab.simulation.domain.model.Journal;
import java.time.Instant;
import java.util.UUID;
/**
 * Sorumluluk: Kalıcılık davranışını arayüz olarak tanımlar.
 * Okuma ipucu: Service bu arayüze bağımlıdır; SQL implementasyonu infrastructure altındadır.
 */
public interface LedgerRepository {
    // Hesap hareketi ve snapshot aynı çağıran transaction içinde yazılır.
    // Bu port sayesinde service, JDBC veya JPA implementasyonunu bilmez.
    UUID post(Journal journal, Instant effectiveAt, Instant recordedAt);
}
```

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/application/repository/SessionRepository.java`

Session tablosu ve ilk hesapların SQL ayrıntısı bu arayüzün arkasında kalır. Para tutarını artırmak ise LedgerRepository sorumluluğudur.

**Sınıfı yazmadan önce:** Kalıcılık davranışını arayüz olarak tanımlar. Service bu arayüze bağımlıdır; SQL implementasyonu infrastructure altındadır.

```java
package com.cryptolab.simulation.application.repository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
/**
 * Sorumluluk: Kalıcılık davranışını arayüz olarak tanımlar.
 * Okuma ipucu: Service bu arayüze bağımlıdır; SQL implementasyonu infrastructure altındadır.
 */
public interface SessionRepository {
    // Kimlikleri service üretir: hesap ve journal aynı session kimliğini kullanır.
    void create(UUID session, UUID owner, BigDecimal deposit, UUID rule, UUID risk, Instant now);
    void createInitialAccounts(UUID session, UUID available, UUID capital);
}
```

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/infrastructure/repository/JdbcSessionRepository.java`

MANDATORY yeni bağımsız transaction açmaz. CreateSession transaction açmadıysa işlem hata verir; yarım session oluşmasını engeller.

**Sınıfı yazmadan önce:** Portun SQL veya okuma modeli kalıcılığını uygular. Parametreli SQL, transaction katılımı, owner/unique/lock kapsamını incele.

```java
package com.cryptolab.simulation.infrastructure.repository;
import com.cryptolab.simulation.application.repository.SessionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;
/**
 * Sorumluluk: Portun SQL veya okuma modeli kalıcılığını uygular.
 * Okuma ipucu: Parametreli SQL, transaction katılımı, owner/unique/lock kapsamını incele.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcSessionRepository implements SessionRepository {
    private final JdbcTemplate jdbc;
    public JdbcSessionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override
    public void create(UUID session, UUID owner, BigDecimal deposit, UUID rule, UUID risk, Instant now) {
        // SQL yalnız bu adaptörde; parametreler birleştirilerek SQL üretilmez.
        jdbc.update("""
            INSERT INTO simulation_sessions
            (id,owner_id,mode,initial_deposit,rule_profile_id,risk_policy_id,status,created_at)
            VALUES (?,?,'LIVE_PAPER',?,?,?,'RUNNING',?)
            """, session, owner, deposit, rule, risk, Timestamp.from(now));
    }
    @Override
    public void createInitialAccounts(UUID session, UUID available, UUID capital) {
        // Bakiye burada artırılmaz; sıfırdan başlanır ve journal üzerinden aktarılır.
        jdbc.update("""
            INSERT INTO wallet_accounts(id,session_id,account_key,kind)
            VALUES (?,?,'AVAILABLE','AVAILABLE'),(?,?,'INITIAL_CAPITAL','SYSTEM')
            """, available, session, capital, session);
    }
}
```

## Kabul: hangi çıktıyı görmelisin?

**Deneme/kabul:** Gerçek PostgreSQL entegrasyon testi; H2 ile lock davranışı kanıtlanmış sayılmaz. Duplicate journal tek etki; transaction ortasında hata olursa kısmi bakiye yok. Finans satırı silerek reset yapılmaz. Başlangıç session use case testi dışında public güvensiz para endpoint’i açılmaz.



## Kendi cümlelerinle anlat

“Redis lock neden tek başına iki kere para düşülmesini engelleyen güvence olamaz?”

## Çalışma sonunda

`progress/BACKEND.md` içine alt görev, değiştirdiğin dosya, komut, gerçek çıktı, fixture/gerçek entegrasyon ayrımı ve sıradaki tek adımı yaz. Çalıştırmadığın test için PASS yazma.
