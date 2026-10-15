# B02 — Saf Java finans çekirdeği

> **DISC-01:** Eureka/local adres çözümlemesi için B01A-Eureka-ve-Servis-Kesfi.md uygulanır. Uygulama ayarları properties olarak kalır; iş kuralları değişmez.

Şartname: 3.0 · Contract: 1.0.0 · Öğretici kod referansı · Durum: BUILD_UNVERIFIED

ACC-01 eki: Account, servis sahipliği ve properties standardı için [güncel mimari eki](03-ACCOUNT-VE-GUVENLIK-EKI.md) geçerlidir.
Bu görevde Spring açmadan finans hesabı yapacağız. Her fonksiyon yalnız aldığı değerlerle çalışır; DB, saat, internet veya kullanıcı arayüzü bilmez. Sınıf sayısını gereksiz büyütmemek için hesaplar Finance sınıfında, para ve miktar doğrulamaları küçük value object’lerde toplanır.

Bu dosyadaki kodları kendi projenize sırayla elle yazın. Burada uygulama reposu kurulmuş veya kabul testleri geçmiş değildir. Dosya yolları CryptoLab köküne göredir. Bir sınıfı yazdıktan sonra küçük deneyi yapın; tüm belgeyi tek hamlede kopyalamayın.

## Bu dersi nasıl okuyacaksın? — Saf hesap ile uygulama akışını ayırmak

**Önce kavram:** Finance yalnız girdilerden sonuç üretir. Bakiye tablosunu değiştirmez; onun sonucu B03/B05 application servisi tarafından ledger kaydına çevrilir. Böylece aynı matematik live paper ve replay için kullanılabilir.

**Yazma sırası:** Decimal → Money/Quantity/PositionSide → MaintenanceTier → Finance → FinanceTest.

**Neden bu ayrım var?** Money bir JDBC entity değildir. Rounding ara hesapta değil doğru sınırda uygulanır. double kullanmak, teminatı notional sanmak veya negatif residual sonucunu gizlemek iş kuralını bozar.

**Küçük pratik:** Aynı girdiyi iki kez ver: aynı sonuç çıkmalı. Ardından yalnız feeRate değiştir ve nakit etkisinin nasıl değiştiğini kendi cümlenle açıkla.

Ortak kurallar: [Paket ve kod standardı](01-PAKET-VE-KOD-STANDARDI.md). Dosyaların tek tek yeni konumları: [Sınıf haritası](02-SINIF-VE-PAKET-HARITASI.md). Bu dosyada verilen yeni package/import/path birlikte kullanılmalıdır; eski ve yeni kopyaları aynı projeye ekleme.

### Bu görevde sınıflar hangi sorumlulukta?

| Sınıf | Paket | Neden burada? |
|---|---|---|
| `Decimal` | `servis kökü` | Saf Java hesabını veya testini temsil eder. |
| `Money` | `servis kökü` | Saf Java hesabını veya testini temsil eder. |
| `Quantity` | `servis kökü` | Saf Java hesabını veya testini temsil eder. |
| `PositionSide` | `servis kökü` | Saf Java hesabını veya testini temsil eder. |
| `MaintenanceTier` | `servis kökü` | Saf Java hesabını veya testini temsil eder. |
| `Finance` | `servis kökü` | Saf Java hesabını veya testini temsil eder. |
| `FinanceTest` | `servis kökü` | Ders davranışını doğrulayan testtir. |

## Kaynak görevlerin eksiksiz kontrol listesi

- [ ] **B02.1** String’den Decimal oluşturmayı, scale/precision farkını ve neden double kullanılmadığını küçük denemeyle göster.
- [ ] **B02.2** Notional, initial margin, long/short PnL ve açık/kapalı fee fonksiyonlarını ayrı yaz; “1000 teminat” ile “1000 notional” ayrımını hesapla.
- [ ] **B02.3** Quantity step-floor, adverse tick rounding ve fee-inclusive bütçe kontrolünü yaz.
- [ ] **B02.4** Maintenance kademesi ve long/short liquidation adayını hesapla; adayın kendi kademe aralığına düştüğünü doğrula.
- [ ] **B02.5** Funding AVAILABLE_FIRST ve kısmi kapanış releasedB hesaplarını yaz; boundary sırası state’i henüz Simulation’da uygulanacak.
- [ ] **B02.6** Negative residual/insurance katkısı hesap sonucunu kaybetmeden temsil et; normal exit ile liquidation fee türünü ayır.
- [ ] **B02.7** Backend F01–F15 ve kademe örneğini bağımsız test girdilerine çevir; normal ve sınır durum sonuçlarını paylaş.

## Yazma sırası ve kavramlar

1. `Decimal`: string → BigDecimal, hassasiyet ve ortak kontroller.
2. `Money`, `Quantity`, `PositionSide`: iş anlamını taşıyan tipler.
3. `MaintenanceTier`: [alt, üst) kademe aralığı.
4. `Finance`: açılış, PnL, funding, kapama, risk ve yuvarlama.
5. `FinanceTest`: sabit örneklerden beklenen değerleri bağımsız doğrulama.

`new BigDecimal("0.1")` ondalık metni aynen alır. `new BigDecimal(0.1)` ikilik kayan noktanın hatasını taşıyabilir. `equals` scale’i de karşılaştırır; sayısal eşitlikte `compareTo` kullan. `DECIMAL128` 34 anlamlı basamak; `scale` virgülden sonraki basamak sayısıdır. Ledger yazım sınırında cashScale=8 HALF_EVEN; ara hesapta ekran için iki haneye indirme.

### `backend/finance-core/src/main/java/com/cryptolab/finance/Decimal.java`

**Sınıfı yazmadan önce:** Saf Java hesabı veya testi. Girdi/çıktı ilişkisini ve kullanılan yuvarlama kararını takip et.

```java
package com.cryptolab.finance;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
/**
 * Sorumluluk: Saf Java hesabı veya testi.
 * Okuma ipucu: Girdi/çıktı ilişkisini ve kullanılan yuvarlama kararını takip et.
 */
public final class Decimal {
 private Decimal() {} // Utility sınıfından nesne üretmeye gerek yok.
 public static final MathContext MC = MathContext.DECIMAL128;
 public static BigDecimal parse(String text) {
  // API sözleşmesi bilimsel gösterimi kabul etmiyor.
  if (text == null || !text.matches("^-?(0|[1-9][0-9]*)(\\.[0-9]+)?$"))
   throw new IllegalArgumentException("INVALID_DECIMAL");
  BigDecimal value = new BigDecimal(text);
  // DB NUMERIC(38,18): en çok 20 tam, 18 kesir basamağı.
  if (value.scale() > 18 || value.precision() - value.scale() > 20)
   throw new IllegalArgumentException("DECIMAL_OUT_OF_RANGE");
  return value;
 }
 public static BigDecimal positive(BigDecimal v) {
  if (v == null || v.signum() <= 0) throw new IllegalArgumentException("POSITIVE_REQUIRED");
  return v;
 }
 public static BigDecimal nonNegative(BigDecimal v) {
  if (v == null || v.signum() < 0) throw new IllegalArgumentException("NON_NEGATIVE_REQUIRED");
  return v;
 }
 public static BigDecimal cash(BigDecimal v) { return v.setScale(8, RoundingMode.HALF_EVEN); }
 public static String wire(BigDecimal v) {
  // 5.000 -> "5"; scientific notation dışarı sızmasın.
  return v.stripTrailingZeros().toPlainString();
 }
}
```

### `backend/finance-core/src/main/java/com/cryptolab/finance/Money.java`

**Sınıfı yazmadan önce:** Saf Java hesabı veya testi. Girdi/çıktı ilişkisini ve kullanılan yuvarlama kararını takip et.

```java
package com.cryptolab.finance;
import java.math.BigDecimal;
/**
 * Sorumluluk: Saf Java hesabı veya testi.
 * Okuma ipucu: Girdi/çıktı ilişkisini ve kullanılan yuvarlama kararını takip et.
 */
public record Money(BigDecimal value) {
 public Money { Decimal.nonNegative(value); }
 public static Money of(String value) { return new Money(Decimal.parse(value)); }
 // Posting tutarı signed olduğu için Money yerine doğrudan doğrulanmış BigDecimal kullanılır.
 @Override public String toString() { return Decimal.wire(value); }
}
```

### `backend/finance-core/src/main/java/com/cryptolab/finance/Quantity.java`

**Sınıfı yazmadan önce:** Saf Java hesabı veya testi. Girdi/çıktı ilişkisini ve kullanılan yuvarlama kararını takip et.

```java
package com.cryptolab.finance;
import java.math.BigDecimal;
/**
 * Sorumluluk: Saf Java hesabı veya testi.
 * Okuma ipucu: Girdi/çıktı ilişkisini ve kullanılan yuvarlama kararını takip et.
 */
public record Quantity(BigDecimal value) {
 public Quantity { Decimal.positive(value); }
 public static Quantity of(String value) { return new Quantity(Decimal.parse(value)); }
 // Posting tutarı signed olduğu için Money yerine doğrudan doğrulanmış BigDecimal kullanılır.
 @Override public String toString() { return Decimal.wire(value); }
}
```

### `backend/finance-core/src/main/java/com/cryptolab/finance/PositionSide.java`

**Sınıfı yazmadan önce:** Saf Java hesabı veya testi. Girdi/çıktı ilişkisini ve kullanılan yuvarlama kararını takip et.

```java
package com.cryptolab.finance;
import java.math.BigDecimal;
/**
 * Sorumluluk: Saf Java hesabı veya testi.
 * Okuma ipucu: Girdi/çıktı ilişkisini ve kullanılan yuvarlama kararını takip et.
 */
public enum PositionSide {
 LONG(1), SHORT(-1);
 private final BigDecimal sign;
 PositionSide(int sign) { this.sign = BigDecimal.valueOf(sign); }
 public BigDecimal sign() { return sign; }
}
```

### `backend/finance-core/src/main/java/com/cryptolab/finance/MaintenanceTier.java`

**Sınıfı yazmadan önce:** Saf Java hesabı veya testi. Girdi/çıktı ilişkisini ve kullanılan yuvarlama kararını takip et.

```java
package com.cryptolab.finance;
import java.math.BigDecimal;
/**
 * Sorumluluk: Saf Java hesabı veya testi.
 * Okuma ipucu: Girdi/çıktı ilişkisini ve kullanılan yuvarlama kararını takip et.
 */
public record MaintenanceTier(BigDecimal floor, BigDecimal cap,
                              BigDecimal rate, BigDecimal amount) {
 public MaintenanceTier {
  Decimal.nonNegative(floor); Decimal.nonNegative(rate); Decimal.nonNegative(amount);
  if (rate.compareTo(BigDecimal.ONE) >= 0 || (cap != null && cap.compareTo(floor) <= 0))
   throw new IllegalArgumentException("INVALID_TIER");
 }
 public boolean contains(BigDecimal notional) {
  // Sınır iki kademeye birden ait olmasın. null cap sonsuz anlamındadır.
  return notional.compareTo(floor) >= 0 && (cap == null || notional.compareTo(cap) < 0);
 }
}
```

### `backend/finance-core/src/main/java/com/cryptolab/finance/Finance.java`

Bu sınıf hesap yapar; DB transaction, limit emir kuyruğu ve kullanıcı onayı B03/B05/B07’nin işidir. Likidasyonun feeRate seçimini çağıran use case yapar; normal kapanış ve liquidation ücretini üst üste uygulama.

**Sınıfı yazmadan önce:** Saf Java hesabı veya testi. Girdi/çıktı ilişkisini ve kullanılan yuvarlama kararını takip et.

```java
package com.cryptolab.finance;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import static com.cryptolab.finance.Decimal.*;
/**
 * Sorumluluk: Saf Java hesabı veya testi.
 * Okuma ipucu: Girdi/çıktı ilişkisini ve kullanılan yuvarlama kararını takip et.
 */
public final class Finance {
 private Finance() {}
 public record Opening(BigDecimal quantity, BigDecimal notional, BigDecimal margin, BigDecimal fee) {
  public BigDecimal requiredCash() { return margin.add(fee); }
 }
 public record Settlement(BigDecimal signedFunding, BigDecimal availableAfter, BigDecimal isolatedAfter) {}
 public record Closing(BigDecimal released, BigDecimal realized, BigDecimal fee,
                       BigDecimal rawResidual, BigDecimal returnedCash,
                       BigDecimal insurance, boolean forceFullClose) {}
 public static BigDecimal pnl(PositionSide side, BigDecimal q, BigDecimal entry, BigDecimal exit) {
  positive(q); positive(entry); positive(exit);
  return side.sign().multiply(q, MC).multiply(exit.subtract(entry, MC), MC);
 }
 public static BigDecimal fee(BigDecimal q, BigDecimal price, BigDecimal rate) {
  positive(q); positive(price); nonNegative(rate);
  return q.multiply(price, MC).multiply(rate, MC);
 }
 public static BigDecimal floorStep(BigDecimal q, BigDecimal step) {
  nonNegative(q); positive(step);
  return q.divide(step, 0, RoundingMode.FLOOR).multiply(step);
 }
 public static Opening open(BigDecimal marginBudget, BigDecimal leverage, BigDecimal entry,
                            BigDecimal step, BigDecimal rate, BigDecimal available) {
  positive(marginBudget); positive(entry); nonNegative(rate); nonNegative(available);
  if (leverage == null || leverage.compareTo(BigDecimal.ONE) < 0)
   throw new IllegalArgumentException("INVALID_LEVERAGE");
  BigDecimal q = floorStep(marginBudget.multiply(leverage, MC).divide(entry, MC), step);
  positive(q); // minimum lota yukarı yuvarlayarak bütçeyi aşmayız.
  BigDecimal n = q.multiply(entry, MC);
  Opening result = new Opening(q, n, n.divide(leverage, MC), fee(q, entry, rate));
  if (result.requiredCash().compareTo(available) > 0)
   throw new IllegalArgumentException("INSUFFICIENT_AVAILABLE_BALANCE");
  return result;
 }
 public static BigDecimal allInQuantity(BigDecimal available, BigDecimal entry,
                                       BigDecimal leverage, BigDecimal rate, BigDecimal step) {
  nonNegative(available); positive(entry); positive(leverage); nonNegative(rate);
  BigDecimal perUnit = entry.multiply(BigDecimal.ONE.divide(leverage, MC).add(rate), MC);
  return floorStep(available.divide(perUnit, MC), step);
 }
 public static BigDecimal maintenance(BigDecimal q, BigDecimal mark, List<MaintenanceTier> tiers) {
  positive(q); positive(mark);
  BigDecimal n = q.multiply(mark, MC);
  MaintenanceTier t = tiers.stream().filter(x -> x.contains(n)).findFirst()
    .orElseThrow(() -> new IllegalArgumentException("MISSING_TIER"));
  return n.multiply(t.rate(), MC).subtract(t.amount(), MC).max(BigDecimal.ZERO);
 }
 public static boolean liquidates(PositionSide side, BigDecimal q, BigDecimal entry,
                                  BigDecimal mark, BigDecimal isolated, List<MaintenanceTier> tiers) {
  // Funding sonrası isolated negatif de olabilir: kaybı gizlemek için sıfıra sıkıştırma.
  return isolated.add(pnl(side,q,entry,mark), MC).compareTo(maintenance(q,mark,tiers)) <= 0;
 }
 public static Optional<BigDecimal> liquidation(PositionSide side, BigDecimal q, BigDecimal entry,
                                               BigDecimal isolated, List<MaintenanceTier> tiers) {
  positive(q); positive(entry);
  for (MaintenanceTier t : tiers) {
   BigDecimal n = q.multiply(entry, MC);
   BigDecimal numerator = side == PositionSide.LONG
     ? n.subtract(isolated).subtract(t.amount()) : n.add(isolated).add(t.amount());
   BigDecimal denominator = q.multiply(side == PositionSide.LONG
     ? BigDecimal.ONE.subtract(t.rate()) : BigDecimal.ONE.add(t.rate()), MC);
   BigDecimal p = numerator.divide(denominator, MC);
   if (p.signum() > 0 && t.contains(q.multiply(p, MC))) {
    // max(0, qPr-c) bölgesinin gerçekten geçerli olduğunu doğrula.
    if (q.multiply(p,MC).multiply(t.rate(),MC).subtract(t.amount()).signum() >= 0)
     return Optional.of(p);
   }
  }
  // MM'nin sıfıra kırpıldığı bölgede B+s*q*(P-E)=0 ayrıca çözülür.
  BigDecimal zeroMm = entry.subtract(isolated.divide(q.multiply(side.sign()), MC));
  if (zeroMm.signum() > 0 && maintenance(q,zeroMm,tiers).signum()==0) return Optional.of(zeroMm);
  return Optional.empty();
 }
 public static Settlement funding(PositionSide side, BigDecimal boundaryQ, BigDecimal mark,
                                  BigDecimal rate, BigDecimal available, BigDecimal isolated) {
  positive(boundaryQ); positive(mark); nonNegative(available);
  // Pozitif funding: long öder, short alır. Negatif rate yönü ters çevirir.
  BigDecimal signed = side.sign().negate().multiply(boundaryQ,MC).multiply(mark,MC).multiply(rate,MC);
  if (signed.signum() >= 0) return new Settlement(signed, available.add(signed), isolated);
  BigDecimal debit = signed.negate();
  BigDecimal fromA = available.min(debit);
  return new Settlement(signed, available.subtract(fromA), isolated.subtract(debit.subtract(fromA)));
 }
 public static Closing close(PositionSide side, BigDecimal qBefore, BigDecimal qClose,
                             BigDecimal entry, BigDecimal exit, BigDecimal isolated,
                             BigDecimal feeRate) {
  positive(qBefore); positive(qClose);
  if (qClose.compareTo(qBefore)>0) throw new IllegalArgumentException("OVER_CLOSE");
  boolean full = qClose.compareTo(qBefore)==0;
  // Son kapanışta tüm B kullanılır; bölme dust'ı hesapta kalmaz.
  BigDecimal released = full ? isolated : isolated.multiply(qClose,MC).divide(qBefore,MC);
  BigDecimal realized = pnl(side,qClose,entry,exit);
  BigDecimal closeFee = fee(qClose,exit,feeRate);
  BigDecimal raw = released.add(realized).subtract(closeFee);
  boolean force = !full && raw.signum()<0;
  // force=true ise bu sonuç uygulanmaz; worker tüm miktarı yeniden hesaplar.
  return new Closing(released,realized,closeFee,raw,raw.max(BigDecimal.ZERO),
                     full ? raw.negate().max(BigDecimal.ZERO) : BigDecimal.ZERO, force);
 }
 public static BigDecimal marketPrice(boolean buy, BigDecimal bid, BigDecimal ask,
                                      BigDecimal tick, BigDecimal slippageBps) {
  positive(bid); positive(ask); positive(tick); nonNegative(slippageBps);
  if (bid.compareTo(ask)>0 || slippageBps.compareTo(new BigDecimal("10000"))>=0)
   throw new IllegalArgumentException("INVALID_QUOTE_OR_SLIPPAGE");
  BigDecimal slip=slippageBps.divide(new BigDecimal("10000"),MC);
  BigDecimal raw=buy ? ask.multiply(BigDecimal.ONE.add(slip),MC)
                    : bid.multiply(BigDecimal.ONE.subtract(slip),MC);
  return raw.divide(tick,0,buy?RoundingMode.CEILING:RoundingMode.FLOOR).multiply(tick);
 }
}
```

### `backend/finance-core/src/test/java/com/cryptolab/finance/FinanceTest.java`

**Sınıfı yazmadan önce:** Davranışı doğrulayan test. Beklenen sonucu bağımsız belirle; testin gerçekten çalıştığını raporla.

```java
package com.cryptolab.finance;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static com.cryptolab.finance.PositionSide.*;
/**
 * Sorumluluk: Davranışı doğrulayan test.
 * Okuma ipucu: Beklenen sonucu bağımsız belirle; testin gerçekten çalıştığını raporla.
 */
class FinanceTest {
 static BigDecimal d(String s) { return Decimal.parse(s); }
 static void eq(String expected, BigDecimal actual) { assertEquals(0,d(expected).compareTo(actual)); }
 final List<MaintenanceTier> tiers=List.of(new MaintenanceTier(d("0"),null,d("0.004"),d("0")));
 @Test void f01ToF04() {
  var o=Finance.open(d("1000"),d("10"),d("50000"),d("0.001"),d("0.0005"),d("10000"));
  eq("0.2",o.quantity()); eq("10000",o.notional()); eq("1000",o.margin());eq("5",o.fee());
  var a=d("10000").subtract(o.requiredCash());eq("8995",a);eq("9995",a.add(o.margin()));
  var u=Finance.pnl(LONG,d("0.2"),d("50000"),d("50500"));eq("100",u);eq("10095",a.add(o.margin()).add(u));
  var lc=Finance.close(LONG,d("0.2"),d("0.2"),d("50000"),d("50500"),d("1000"),d("0.0005"));
  eq("5.05",lc.fee());eq("10089.95",a.add(lc.returnedCash()));
  var sc=Finance.close(SHORT,d("0.2"),d("0.2"),d("50000"),d("49500"),d("1000"),d("0.0005"));
  eq("4.95",sc.fee());eq("10090.05",a.add(sc.returnedCash()));
 }
 @Test void f05ToF07() {
  var l=Finance.funding(LONG,d("0.2"),d("50000"),d("0.0001"),d("8995"),d("1000"));
  eq("8994",l.availableAfter());eq("1000",l.isolatedAfter());
  var s=Finance.funding(SHORT,d("0.2"),d("50000"),d("0.0001"),d("8995"),d("1000"));eq("8996",s.availableAfter());
  var low=Finance.funding(LONG,d("0.2"),d("50000"),d("0.0001"),d("0.25"),d("1000"));
  eq("0",low.availableAfter());eq("999.25",low.isolatedAfter());
 }
 @Test void f08ToF11() {
  assertTrue(Finance.liquidation(LONG,d("0.2"),d("50000"),d("1000"),tiers).orElseThrow().toPlainString().startsWith("45180.722891"));
  assertTrue(Finance.liquidation(SHORT,d("0.2"),d("50000"),d("1000"),tiers).orElseThrow().toPlainString().startsWith("54780.876494"));
  assertTrue(Finance.liquidates(LONG,d("0.2"),d("50000"),d("45000"),d("1000"),tiers));
  eq("36",Finance.maintenance(d("0.2"),d("45000"),tiers));
  var c=Finance.close(LONG,d("0.2"),d("0.2"),d("50000"),d("44000"),d("1000"),d("0"));
  eq("-1200",c.realized());eq("-200",c.rawResidual());eq("200",c.insurance());
 }
 @Test void f12ToF15() {
  var first=Finance.close(LONG,d("0.2"),d("0.1"),d("50000"),d("50500"),d("1000"),d("0.0005"));
  eq("500",first.released());eq("2.525",first.fee());
  var a=d("8995").add(first.returnedCash());eq("9542.475",a);
  var last=Finance.close(LONG,d("0.1"),d("0.1"),d("50000"),d("49500"),d("500"),d("0.0005"));
  eq("9990",a.add(last.returnedCash()));
  assertThrows(IllegalArgumentException.class,()->Finance.open(d("1000"),d("10"),d("50000"),d("0.001"),d("0.0005"),d("1000")));
  var gap=Finance.close(LONG,d("0.2"),d("0.2"),d("50000"),d("44000"),d("1000"),d("0.0005"));
  eq("204.4",gap.insurance());eq("0",gap.returnedCash());eq("4.4",gap.fee());
 }
 @Test void boundaryAndInvalid() {
  for(String s:List.of("NaN","Infinity","1e3","01","0,1")) assertThrows(IllegalArgumentException.class,()->d(s));
  assertThrows(IllegalArgumentException.class,()->Quantity.of("0"));
  var t=List.of(new MaintenanceTier(d("0"),d("5000"),d("0.004"),d("0")),new MaintenanceTier(d("5000"),null,d("0.005"),d("5")));
  eq("20",Finance.maintenance(d("1"),d("5000"),t));
  assertTrue(Finance.liquidation(LONG,d("0.2"),d("50000"),d("1000"),t).orElseThrow().toPlainString().startsWith("45201.005025"));
  eq("0.2",Finance.floorStep(d("0.209"),d("0.01")));
  eq("100.1",Finance.marketPrice(true,d("100"),d("100.01"),d("0.1"),d("0")));
  assertTrue(Finance.close(LONG,d("0.2"),d("0.1"),d("50000"),d("40000"),d("1000"),d("0.0005")).forceFullClose());
 }
}
```

## Deneyler: elle değiştir, sonucu önceden tahmin et

`mvn -f backend/pom.xml -pl finance-core test` çalıştır. Test sonucu görülmeden B02 DONE değildir.

- Aynı q/E/X için LONG ve SHORT PnL’lerini topla: sıfır olmalı. Kaldıraç PnL fonksiyonuna hiç girmiyor; neden?
- B’yi 1000’den 1500’e yükselt. Long eşik düşer, short eşik yükselir.
- qClose=qBefore son kapanışında released tam B olmalı. Oransal yuvarlama artığı bırakma.
- Slippage 10 bps = %0,1; yüzde 10 değildir. Tick’e BUY yukarı, SELL aşağı yuvarlanır.
- Tier listesinin boşluk/çakışma kontrolünü profil yükleme sınırında yap; listedeki ilk eşleşmeye güvenerek hatalı profil kabul etme.

**B03’e devir:** Hesap sonuçları ledger’e yazılırken her posting 8 haneye yuvarlanır; karşı kayıt aynı tutarın negatifi olmalı. Ayrı ayrı hesaplanmış iki yuvarlanmış ifade arasında cent farkı yaratma. NUMERIC(38,18) saklama sınırına giden türetilmiş değerleri kontrollü yuvarla; cash dışı miktar/fiyat alanlarında tick/step kuralını koru.

## Kabul: hangi çıktıyı görmelisin?

**Deneme/kabul:** E=50000,q=.2,L=10,başlangıç=10000 için A=8995; long 50500 kapanışında A=10089.95. Kaldıraç değişip q/E/X aynıysa brüt PnL değişmez. Kademe sınırı, sıfır/negatif değer, invalid string ve rounding testleri var. Kaynak belgedeki tüm altın fixture’lar uygulamada doğrulanır; iki örnek yeterli değildir.



## Kendi cümlelerinle anlat

“Likidasyon fiyatı neden son işlem fiyatından ve gerçekleşen çıkış fiyatından ayrı?”

## Çalışma sonunda

`progress/BACKEND.md` içine alt görev, değiştirdiğin dosya, komut, gerçek çıktı, fixture/gerçek entegrasyon ayrımı ve sıradaki tek adımı yaz. Çalıştırmadığın test için PASS yazma.
