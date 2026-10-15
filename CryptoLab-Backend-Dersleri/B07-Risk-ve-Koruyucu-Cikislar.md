# B07 — Risk yaşam döngüsü, funding ve koruyucu çıkışlar

> **DISC-01:** Eureka/local adres çözümlemesi için B01A-Eureka-ve-Servis-Kesfi.md uygulanır. Uygulama ayarları properties olarak kalır; iş kuralları değişmez.

Şartname: 3.0 · Contract: 1.0.0 · Öğretici kod referansı · Durum: BUILD_UNVERIFIED

ACC-01 eki: Account, servis sahipliği ve properties standardı için [güncel mimari eki](03-ACCOUNT-VE-GUVENLIK-EKI.md) geçerlidir.
Açık pozisyonun risk denetimi kullanıcı ekranından bağımsız çalışır. Funding, SL/TP, manuel close ve liquidation aynı session kilidi altında seri uygulanır. Burada tek finans çekirdeği kullanılır; yeni bir PnL hesabı yazılmaz.

Bu dosyadaki kodları kendi projenize sırayla elle yazın. Burada uygulama reposu kurulmuş veya kabul testleri geçmiş değildir. Dosya yolları CryptoLab köküne göredir. Bir sınıfı yazdıktan sonra küçük deneyi yapın; tüm belgeyi tek hamlede kopyalamayın.

## Bu dersi nasıl okuyacaksın? — Karar vermek ile kararı kalıcı uygulamak

**Önce kavram:** RiskDecision/ExitDecision saf kararı verir. FundingBoundary olay anındaki miktarı temsil eder. DeadlineScheduler service’i DeadlineRepository portunu kullanır. JdbcDeadlineRepository yalnız kalıcı due kayıtlarını yönetir. Para hareketi ortak session writer transaction’ına bağlanır.

**Yazma sırası:** RiskDecision/RiskPolicyEvaluator → FundingBoundary/ExitDecision → DeadlineRepository/JdbcDeadlineRepository → DeadlineScheduler → writer bağlantısı ve yarış deneyleri.

**Neden bu ayrım var?** Stop tetiklenmesi fill fiyatı garantisi değildir. HALTED yeni girişleri keser; azaltıcı çıkış/funding akışı devam eder. Due claim ayrı commit edilip unutulursa restart sonrası iş kaybolabilir.

**Küçük pratik:** Tek pozisyona aynı anda manual close ve stop gönder. Kalan miktar ve ledger tek atomik kapanış göstermeli. Deadline tekrarında ikinci komut oluşmamalı.

Ortak kurallar: [Paket ve kod standardı](01-PAKET-VE-KOD-STANDARDI.md). Dosyaların tek tek yeni konumları: [Sınıf haritası](02-SINIF-VE-PAKET-HARITASI.md). Bu dosyada verilen yeni package/import/path birlikte kullanılmalıdır; eski ve yeni kopyaları aynı projeye ekleme.

### Bu görevde sınıflar hangi sorumlulukta?

| Sınıf | Paket | Neden burada? |
|---|---|---|
| `RiskDecision` | `domain.service` | Girdilerden iş kararı/hesap üretir. |
| `RiskPolicyEvaluator` | `domain.service` | Girdilerden iş kararı/hesap üretir. |
| `FundingBoundary` | `domain.model` | İş verisini ve kendi değişmez kurallarını temsil eder. |
| `ExitDecision` | `domain.service` | Girdilerden iş kararı/hesap üretir. |
| `DeadlineScheduler` | `application.service` | Bir kullanım senaryosunun sırasını yönetir. |
| `DeadlineRepository` | `application.repository` | Kalıcılık davranışını arayüz olarak tanımlar. |
| `JdbcDeadlineRepository` | `infrastructure.repository` | Portun SQL veya okuma modeli kalıcılığını uygular. |

## Kaynak görevlerin eksiksiz kontrol listesi

- [ ] **B07.1** Mark risk kontrolünü UI’dan bağımsız işlet; maintenance ve B02 liquidation fonksiyonlarını uygula.
- [ ] **B07.2** Funding boundary qty snapshot + kesin rate geç gelmesi + AVAILABLE_FIRST ledger; unique(position,time).
- [ ] **B07.3** SL/TP mark trigger ve kardeş exit iptali; same step liquidation önceliği.
- [ ] **B07.4** Kısmi manuel close, ReleasedB, kalan bracket ve son dust; negatif partial residual politikasını şartnameye göre uygula.
- [ ] **B07.5** LIQUIDATING→LIQUIDATED ve quote yoksa pending; normal gap/forced gap sigorta karşı kaydı, A’yı sessiz tüketme yok.
- [ ] **B07.6** REEVALUATE/CLOSE_AT_DEADLINE due queue, first fill+ufuk, restart/double timer; eski generation re-entry reddi.
- [ ] **B07.7** Günlük kayıp/toplam notional/leverage/giriş sınırı; halt yeni girişleri durdursun, risk çıkışlarını durdurmasın. Close-all ayrı komut.

## B07.1 — Yazma sırası

`RiskDecision` → `RiskPolicyEvaluator` → `FundingBoundary` → `DeadlineRepository`/`JdbcDeadlineRepository` → `DeadlineScheduler` → session writer bağlantısı → yarış testleri. Risk sınıfları karar verir, repository transaction içinde uygulamayı yapar. Kararı veren fonksiyonla kalıcı kayıt yazan sınıfın ayrılması testleri okunabilir kılar.

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/domain/service/RiskDecision.java`

**Sınıfı yazmadan önce:** Girdilerden iş kararı/hesap üretir. Aynı girdinin aynı sonucu üretmesi beklenir; DB/ağ yan etkisi ekleme.

```java
package com.cryptolab.simulation.domain.service;
import com.cryptolab.finance.Finance;
import com.cryptolab.finance.PositionSide;
import com.cryptolab.finance.MaintenanceTier;
import java.math.BigDecimal;
import java.util.List;
/**
 * Sorumluluk: Girdilerden iş kararı/hesap üretir.
 * Okuma ipucu: Aynı girdinin aynı sonucu üretmesi beklenir; DB/ağ yan etkisi ekleme.
 */
public final class RiskDecision {
 public enum Action {NONE,LIQUIDATE,STOP_LOSS,TAKE_PROFIT}
 private RiskDecision(){}
 public static Action onMark(PositionSide side,BigDecimal q,BigDecimal entry,BigDecimal mark,
  BigDecimal isolated,BigDecimal stop,BigDecimal target,List<MaintenanceTier> tiers){
  // Aynı engine adımında liquidation stop'tan önce gelir: proje tie politikası.
  if(Finance.liquidates(side,q,entry,mark,isolated,tiers))return Action.LIQUIDATE;
  boolean longSide=side==PositionSide.LONG;
  if(stop!=null&&(longSide?mark.compareTo(stop)<=0:mark.compareTo(stop)>=0))return Action.STOP_LOSS;
  if(target!=null&&(longSide?mark.compareTo(target)>=0:mark.compareTo(target)<=0))return Action.TAKE_PROFIT;
  return Action.NONE;
 }
}
```

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/domain/service/RiskPolicyEvaluator.java`

**Sınıfı yazmadan önce:** Girdilerden iş kararı/hesap üretir. Aynı girdinin aynı sonucu üretmesi beklenir; DB/ağ yan etkisi ekleme.

```java
package com.cryptolab.simulation.domain.service;
import com.cryptolab.finance.Decimal;
import com.cryptolab.finance.Finance;
import java.math.BigDecimal;
import static com.cryptolab.finance.Decimal.MC;
/**
 * Sorumluluk: Girdilerden iş kararı/hesap üretir.
 * Okuma ipucu: Aynı girdinin aynı sonucu üretmesi beklenir; DB/ağ yan etkisi ekleme.
 */
public final class RiskPolicyEvaluator {
 public record Limits(BigDecimal maxLeverage,int maxPositions,BigDecimal maxPositionNotional,
  BigDecimal maxTotalNotional,BigDecimal maxMarginPerTrade,BigDecimal riskFraction,BigDecimal maxDailyLossFraction) {
  public Limits {
   Decimal.positive(maxLeverage);Decimal.positive(maxPositionNotional);Decimal.positive(maxTotalNotional);Decimal.positive(maxMarginPerTrade);
   if(maxLeverage.compareTo(BigDecimal.ONE)<0||maxPositions<1||riskFraction.signum()<=0||riskFraction.compareTo(BigDecimal.ONE)>0
    ||maxDailyLossFraction.signum()<=0||maxDailyLossFraction.compareTo(BigDecimal.ONE)>0)throw new IllegalArgumentException("INVALID_RISK_POLICY");
  }
 }
 private RiskPolicyEvaluator(){}
 public static BigDecimal size(BigDecimal entry,BigDecimal stop,BigDecimal leverage,BigDecimal equity,
  BigDecimal available,BigDecimal existingNotional,BigDecimal marginBudget,BigDecimal feeRate,
  BigDecimal unitCostBuffer,BigDecimal step,Limits limits){
  if(leverage.compareTo(BigDecimal.ONE)<0||leverage.compareTo(limits.maxLeverage())>0)throw new IllegalArgumentException("LEVERAGE_LIMIT");
  Decimal.positive(entry);Decimal.nonNegative(unitCostBuffer);
  BigDecimal budget=marginBudget.min(limits.maxMarginPerTrade());
  BigDecimal qMargin=Finance.floorStep(budget.multiply(leverage,MC).divide(entry,MC),step);
  BigDecimal qCash=Finance.allInQuantity(available,entry,leverage,feeRate,step);
  BigDecimal remainingNotional=limits.maxTotalNotional().subtract(existingNotional).max(BigDecimal.ZERO);
  BigDecimal qNotional=limits.maxPositionNotional().min(remainingNotional).divide(entry,MC);
  BigDecimal result=qMargin.min(qCash).min(qNotional);
  if(stop!=null){
   BigDecimal distance=entry.subtract(stop).abs().add(unitCostBuffer);
   Decimal.positive(distance);
   BigDecimal riskBudget=equity.max(BigDecimal.ZERO).multiply(limits.riskFraction(),MC);
   result=result.min(riskBudget.divide(distance,MC));
  }
  return Finance.floorStep(result,step); // zero sonuç WAIT/reject; minimum lota yükseltme yok.
 }
 public static boolean dailyHalt(BigDecimal startEquity,BigDecimal equity,BigDecimal netFlows,BigDecimal limitFraction){
  if(startEquity.signum()<=0)return true;
  BigDecimal loss=startEquity.add(netFlows).subtract(equity);
  return loss.compareTo(startEquity.multiply(limitFraction,MC))>=0;
 }
}
```

### `backend/simulation-service/src/main/resources/db/migration/V3__risk.sql`

```sql
CREATE TABLE funding_boundaries(position_id uuid NOT NULL REFERENCES positions(id),funding_time timestamptz NOT NULL,
 q_at_boundary numeric(38,18) NOT NULL,side text NOT NULL,instrument_id uuid NOT NULL,
 PRIMARY KEY(position_id,funding_time));
CREATE TABLE funding_settlements(position_id uuid NOT NULL REFERENCES positions(id),funding_time timestamptz NOT NULL,
 q_at_boundary numeric(38,18) NOT NULL,rate numeric(38,18) NOT NULL,mark numeric(38,18) NOT NULL,
 amount numeric(38,18) NOT NULL,from_a numeric(38,18) NOT NULL,from_b numeric(38,18) NOT NULL,
 late boolean NOT NULL,journal_id uuid NOT NULL REFERENCES journal_transactions(id),PRIMARY KEY(position_id,funding_time));
CREATE TABLE due_actions(position_id uuid NOT NULL REFERENCES positions(id),generation bigint NOT NULL,
 deadline_at timestamptz NOT NULL,kind text NOT NULL,status text NOT NULL,command_id uuid,
 PRIMARY KEY(position_id,generation,kind));
CREATE TABLE daily_risk(session_id uuid NOT NULL REFERENCES simulation_sessions(id),utc_day date NOT NULL,
 start_equity numeric(38,18) NOT NULL,net_flows numeric(38,18) NOT NULL DEFAULT 0,
 PRIMARY KEY(session_id,utc_day));
CREATE TABLE halt_reasons(session_id uuid NOT NULL REFERENCES simulation_sessions(id),reason text NOT NULL,
 created_at timestamptz NOT NULL,PRIMARY KEY(session_id,reason));
```

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/domain/model/FundingBoundary.java`

**Sınıfı yazmadan önce:** İş verisini ve kendi değişmez kurallarını temsil eder. Modelin HTTP veya veritabanı biçimine bağımlı olup olmadığını kontrol et.

```java
package com.cryptolab.simulation.domain.model;
import java.time.Instant;
import java.math.BigDecimal;
/**
 * Sorumluluk: İş verisini ve kendi değişmez kurallarını temsil eder.
 * Okuma ipucu: Modelin HTTP veya veritabanı biçimine bağımlı olup olmadığını kontrol et.
 */
public final class FundingBoundary {
 private FundingBoundary(){}
 public static boolean eligible(Instant openedAt,Instant closedAt,Instant boundary){
  // Tam boundary'de açılan dahil değil; o anda kapanan eski pozisyon dahil.
  return openedAt.isBefore(boundary)&&(closedAt==null||!closedAt.isBefore(boundary));
 }
 public record Allocation(BigDecimal availableDelta,BigDecimal isolatedDelta,BigDecimal poolDelta){}
 public static Allocation allocate(BigDecimal signed,BigDecimal available,boolean closed){
  if(signed.signum()>=0)return new Allocation(signed,BigDecimal.ZERO,signed.negate());
  BigDecimal paid=available.min(signed.negate());
  if(closed){
   // Geç settlement kapalı pozisyonun eski B'sini hayali olarak açmaz.
   // Kalan borç için açık realized-adjustment/insurance muhasebesi uygulanmalıdır.
   return new Allocation(paid.negate(),BigDecimal.ZERO,paid);
  }
  BigDecimal remainder=signed.negate().subtract(paid);
  return new Allocation(paid.negate(),remainder.negate(),signed.negate());
 }
}
```

## B07.2 — Funding transaction’ını yaz

1. Boundary zamanında position açık miktarını sakla; rate gelmesini bekleme. `(positionId,fundingTime)` unique.
2. Aynı zamandaki pozisyonları instrumentId **string alfabetik** sırayla işle; SQL UUID byte sırasına gelişigüzel güvenme.
3. Kesin funding event’i geldiğinde session lock; settlement varsa dön. Rate ve mark kaydı olmadan tahmini stream rate’i tahsil etme.
4. `Finance.funding(side,qAtBoundary,settlementMark,rate,A,B)` çağır. Signed amount cashScale’e yuvarlanır; A’dan alınan ve B’den alınan tutarın toplamı kesin borca eşit olmalı.
5. Posting: deltaA, deltaB, SIM_FUNDING_POOL=-signed. Journal + settlement + snapshots + outbox aynı TX.
6. B azaldıysa son geçerli mark ile maintenance tekrar kontrol edilir. Quote yoksa liquidation fill üretme.
7. Rate geç geldi ve pozisyon kapalıysa ayrıca realized adjustment yaz. Kaynak şartname geç settlement nakit yetersizliği için ayrıntılı policy vermiyor: **açık tasarım kararı** olarak available’a kadar debit + kalan explicit SIM_INSURANCE karşı kaydı seçilir ve `lateSettlement=true` raporlanır. Bu karar kaynak belgeye ek onaylanmadan üretim kabulü yapma. Eski pozisyonu geçmişte likide olmuş gibi değiştirme.

## B07.3–B07.5 — Tek çıkış yolu

Her risk veya close talebi aynı `ClosePosition` application use case’ine gelir. Önce session, sonra position kilitlenir; ters kilit sırası deadlock yaratabilir. `state!=OPEN/LIQUIDATING` ise eski olay idempotent olarak etkisizdir. Liquidation state’i `LIQUIDATING` olur; normal closeRequested ayrı alandır.

Aşağıdaki yardımcı sınıf çıkış fiyatı ve miktar kararını verir; kaydı B05 worker transaction’ına bağla.

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/domain/service/ExitDecision.java`

**Sınıfı yazmadan önce:** Girdilerden iş kararı/hesap üretir. Aynı girdinin aynı sonucu üretmesi beklenir; DB/ağ yan etkisi ekleme.

```java
package com.cryptolab.simulation.domain.service;
import com.cryptolab.finance.Finance;
import com.cryptolab.finance.PositionSide;
import java.math.BigDecimal;
/**
 * Sorumluluk: Girdilerden iş kararı/hesap üretir.
 * Okuma ipucu: Aynı girdinin aynı sonucu üretmesi beklenir; DB/ağ yan etkisi ekleme.
 */
public final class ExitDecision {
 public record Result(String status,String reason,BigDecimal quantity,BigDecimal price,
  Finance.Closing closing,boolean liquidityApproximation){}
 private ExitDecision(){}
 public static Result decide(PositionSide side,BigDecimal q,BigDecimal requested,BigDecimal entry,
  BigDecimal isolated,BigDecimal bid,BigDecimal ask,BigDecimal bidQty,BigDecimal askQty,
  BigDecimal tick,BigDecimal slippageBps,BigDecimal participation,BigDecimal takerRate,
  BigDecimal liquidationRate,boolean liquidating,boolean quoteFresh){
  if(!quoteFresh)return new Result("EXIT_PENDING_DATA","STALE_QUOTE",requested,null,null,false);
  boolean buy=side==PositionSide.SHORT;
  BigDecimal amount=liquidating?q:requested;
  if(amount.signum()<=0||amount.compareTo(q)>0)throw new IllegalArgumentException("INVALID_CLOSE_QUANTITY");
  BigDecimal visible=buy?askQty:bidQty;
  if(!liquidating&&amount.compareTo(visible.multiply(participation))>0)
   return new Result("NEW","LIQUIDITY_LIMIT",amount,null,null,false);
  BigDecimal price=Finance.marketPrice(buy,bid,ask,tick,slippageBps);
  var close=Finance.close(side,q,amount,entry,price,isolated,liquidating?liquidationRate:takerRate);
  boolean forced=false;
  if(close.forceFullClose()){
   // Negatif partial residual uygulanmaz; tüm kalan q için risk close hesaplanır.
   amount=q;forced=true;close=Finance.close(side,q,q,entry,price,isolated,takerRate);
  }
  return new Result("FILLED",liquidating?"LIQUIDATION":close.insurance().signum()>0?"INSOLVENT_EXIT":forced?"FORCED_RISK_CLOSE":"USER_CLOSE",
   amount,price,close,liquidating||forced);
 }
}
```

`Result` normal SL/TP/deadline çağrısında USER_CLOSE yerine çağıran exitReason’ı taşır; signature’a explicit reason ekleyerek domain’de izinli enumla doğrula. liquidityApproximation flag’i yalnız hacimden bağımsız zorunlu model kullanıldığında rapora gider. Forced risk close için priceModelVersion ve slippage policy açıkça kaydedilir.

Kısmi kapanışta:

```java
// B05 close transaction'ında, session lock altında:
var remainingQ = qBefore.subtract(result.quantity());
var remainingB = isolatedBefore.subtract(result.closing().released());
var remainingInitial = remainingQ.signum()==0 ? java.math.BigDecimal.ZERO
    : initialMarginRemaining.multiply(remainingQ, com.cryptolab.finance.Decimal.MC)
      .divide(qBefore, com.cryptolab.finance.Decimal.MC);
// B kaynak otoritesi wallet_accounts; position'da ikinci bağımsız B üretme.
// entryPrice aynı kalır; originalMargin lifetime ROI paydasıdır, değiştirilmez.
// bracket quantity remainingQ olur; full close kardeş exit'leri iptal eder.
```

## B07.6 — Deadline ve generation

Due queue kaydı **firstFillAt+horizon**. Preview createdAt+horizon yanlış olur. `REEVALUATE` bildirim+analysis işi; yeni entry yetkisi değildir. `CLOSE_AT_DEADLINE` azaltıcı close komutu. Aynı position/generation/kind unique ile iki timer tek etki.

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/application/service/DeadlineScheduler.java`

**Sınıfı yazmadan önce:** Bir kullanım senaryosunun sırasını yönetir. Constructor bağımlılıklarını ve transaction sınırını önce oku; hesap ve SQL sorumluluklarını ayır.

```java
package com.cryptolab.simulation.application.service;
import org.springframework.stereotype.Service;
import com.cryptolab.simulation.application.repository.DeadlineRepository;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.sql.Timestamp;
import java.util.UUID;
/**
 * Sorumluluk: Bir kullanım senaryosunun sırasını yönetir.
 * Okuma ipucu: Constructor bağımlılıklarını ve transaction sınırını önce oku; hesap ve SQL sorumluluklarını ayır.
 */
@Service
public class DeadlineScheduler {
 private final DeadlineRepository db;private final Clock clock;
 public DeadlineScheduler(DeadlineRepository db,Clock clock){this.db=db;this.clock=clock;}
 @Transactional public void schedule(UUID position,UUID session,long generation,int horizon,String kind){
  // Kayıt ilk fill transaction'ının içinden çağrılır; opened_at first fill'dir.
  db.schedule(position,session,generation,horizon,kind);
 }
 @Transactional public boolean claim(UUID position,long generation,String kind){
  // Poller önce session lock almalı; due ve position kilit sırası ortak kalmalı.
  return db.claim(position,generation,kind,clock.instant());
 }
}
```

`claim` tek başına commit edilip iş sonra yapılırsa crash’te RUNNING kaybolur. Claim + command/outbox + due DONE **aynı dış transaction** içinde olmalı. Alternatif lease/heartbeat kullanılır; kalıcı RUNNING’i sonsuza kadar bırakma. Manuel close instrument_generation’ı artırır; eski proposal/preview reddedilir. Kısmi kapanışta mevcut pozisyonun deadline/bracket takibi korunmalı; entry generation ile pozisyonun yaşam döngüsü generation’ını birbirine karıştırma.

## B07.7 — Halt/resume/close-all

HALTED yeni girişleri durdurur; mark/funding/exit sürer. Günlük UTC equity açılışı Simulation kaydıdır. Resume bütün engeller kalktıysa; manual halt kaldırmak DATA_GAP’ı kaldırmaz. Close-all her açık position için ayrı durable command yaratır; kısmi başarısızlığı tek “hepsi kapandı” cevabıyla gizleme.

**Yarış testi:** üç thread manual close, stop ve liq için aynı position’a girsin. Session row lock + remaining q kontrolüyle tek kapanış ve tek journal olmalı. Funding duplicate event aynı `(position,time)` nedeniyle ikinci ücret yaratmamalı. Timer iki kez aynı deadline’da: tek command. Quote yok: LIQUIDATING + pending order, FILLED yok.

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/application/repository/DeadlineRepository.java`

Deadline kararı uygulama servisinde, SQL infrastructure içindedir. first-fill zamanı DB'deki opened_at alanından alınır.

**Sınıfı yazmadan önce:** Kalıcılık davranışını arayüz olarak tanımlar. Service bu arayüze bağımlıdır; SQL implementasyonu infrastructure altındadır.

```java
package com.cryptolab.simulation.application.repository;
import java.time.Instant;
import java.util.UUID;
/**
 * Sorumluluk: Kalıcılık davranışını arayüz olarak tanımlar.
 * Okuma ipucu: Service bu arayüze bağımlıdır; SQL implementasyonu infrastructure altındadır.
 */
public interface DeadlineRepository {
    void schedule(UUID position, UUID session, long generation, int horizon, String kind);
    boolean claim(UUID position, long generation, String kind, Instant now);
}
```

### `backend/simulation-service/src/main/java/com/cryptolab/simulation/infrastructure/repository/JdbcDeadlineRepository.java`

Claim, command ve DONE güncellemesi aynı dış transaction'da kalmalı. Repository'ye ayırmak crash recovery gereksinimini ortadan kaldırmaz.

**Sınıfı yazmadan önce:** Portun SQL veya okuma modeli kalıcılığını uygular. Parametreli SQL, transaction katılımı, owner/unique/lock kapsamını incele.

```java
package com.cryptolab.simulation.infrastructure.repository;
import com.cryptolab.simulation.application.repository.DeadlineRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
/**
 * Sorumluluk: Portun SQL veya okuma modeli kalıcılığını uygular.
 * Okuma ipucu: Parametreli SQL, transaction katılımı, owner/unique/lock kapsamını incele.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcDeadlineRepository implements DeadlineRepository {
    private final JdbcTemplate db;
    public JdbcDeadlineRepository(JdbcTemplate db) { this.db = db; }
    public void schedule(UUID position, UUID session, long generation, int horizon, String kind) {
        db.update("""
            INSERT INTO due_actions(position_id,generation,deadline_at,kind,status)
            SELECT id,?,opened_at+(? * interval '1 second'),?,'PENDING' FROM positions
            WHERE id=? AND session_id=? ON CONFLICT DO NOTHING
            """,generation,horizon,kind,position,session);
    }
    public boolean claim(UUID position,long generation,String kind,Instant now) {
        return db.update("""
            UPDATE due_actions SET status='RUNNING'
            WHERE position_id=? AND generation=? AND kind=? AND status='PENDING' AND deadline_at<=?
            """,position,generation,kind,Timestamp.from(now))==1;
    }
}
```

## Kabul: hangi çıktıyı görmelisin?

**Deneme/kabul:** Manual close/stop/liq yarışı tek q kapanışı; funding iki kez tek hareket; mark-last ayrımı; quote yokken sahte fill yok; deadline iki kez tek komut; eski proposal generation ile giriş reddi. Risk servisi Reporting kapanınca çalışır. Quantity ve ledger invariant’ları bütün yollar için sağlanır.



## Kendi cümlelerinle anlat

“Stop fiyatını gördük diye aynı fiyattan fill olduğumuzu neden söyleyemeyiz?”

## Çalışma sonunda

`progress/BACKEND.md` içine alt görev, değiştirdiğin dosya, komut, gerçek çıktı, fixture/gerçek entegrasyon ayrımı ve sıradaki tek adımı yaz. Çalıştırmadığın test için PASS yazma.
