package com.crypto.reportingservice.domain.service;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
/**
 * Sorumluluk: Girdilerden iş kararı/hesap üretir.
 * Okuma ipucu: Aynı girdinin aynı sonucu üretmesi beklenir; DB/ağ yan etkisi ekleme.
 */
public final class ReportMetrics {
 private static final MathContext MC=MathContext.DECIMAL128;
 private ReportMetrics(){}
 public record Trade(BigDecimal realized,BigDecimal fees,BigDecimal funding,BigDecimal liquidationFees,BigDecimal insurance){
  public BigDecimal net(){return realized.subtract(fees).add(funding).subtract(liquidationFees);}
 }
 public static BigDecimal winRate(List<Trade> closed){
  if(closed.isEmpty())return null;
  long wins=closed.stream().filter(t->t.net().signum()>0).count();
  return BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(closed.size()),MC);
 }
 public static BigDecimal profitFactor(List<Trade> closed){
  BigDecimal gains=BigDecimal.ZERO,losses=BigDecimal.ZERO;
  for(var t:closed)if(t.net().signum()>0)gains=gains.add(t.net());else losses=losses.add(t.net().negate());
  return losses.signum()==0?null:gains.divide(losses,MC); // Infinity JSON üretme.
 }
 public static BigDecimal maxDrawdown(List<BigDecimal> chronologicalEquity){
  if(chronologicalEquity.isEmpty())return null;
  BigDecimal peak=chronologicalEquity.get(0),worst=BigDecimal.ZERO;
  for(var equity:chronologicalEquity){peak=peak.max(equity);if(peak.signum()>0)worst=worst.max(peak.subtract(equity).divide(peak,MC));}
  return worst;
 }
}

