import { compareDecimalStrings } from '@/shared/formatting/decimalParser';

export const DEFAULT_RULE_PROFILE_ID = '88888888-8888-4888-8888-888888888888';
export const DEFAULT_RISK_POLICY_ID = '99999999-9999-4999-8999-999999999999';

export interface RuleProfileOption {
  id: string;
  title: string;
  badge?: string;
  makerFee: string;
  takerFee: string;
  summary: string;
  shortLabel: string;
}

export interface RiskPolicyOption {
  id: string;
  title: string;
  badge?: string;
  maxLeverage: string;
  dailyLossBrake: string;
  summary: string;
  shortLabel: string;
}

export const RULE_PROFILES: readonly RuleProfileOption[] = [
  {
    id: DEFAULT_RULE_PROFILE_ID,
    title: 'Standart USD-M',
    badge: 'Önerilen',
    makerFee: '%0,02 Yapıcı',
    takerFee: '%0,04 Alıcı',
    summary: 'Standart Binance vadeli piyasa komisyonları uygulanır.',
    shortLabel: 'Standart USD-M (%0,04 Alıcı / %0,02 Yapıcı)',
  },
  {
    id: '88888888-8888-4888-8888-888888888889',
    title: 'VIP Kurumsal',
    makerFee: '%0,01 Yapıcı',
    takerFee: '%0,025 Alıcı',
    summary: 'Yüksek hacimli testler için indirimli komisyon kademesi.',
    shortLabel: 'VIP Kurumsal (%0,025 Alıcı / %0,01 Yapıcı)',
  },
  {
    id: '88888888-8888-4888-8888-888888888890',
    title: 'Scalping & Algo',
    makerFee: '%0,00 Yapıcı',
    takerFee: '%0,03 Alıcı',
    summary: 'Limit emirlerde sıfır komisyon, sık al-sat stratejilerine uygun.',
    shortLabel: 'Scalping & Algo (%0,03 Alıcı / %0,00 Yapıcı)',
  },
];

export const RISK_POLICIES: readonly RiskPolicyOption[] = [
  {
    id: '99999999-9999-4999-8999-999999999998',
    title: 'Muhafazakâr Koruma',
    maxLeverage: '5',
    dailyLossBrake: '%2 Günlük Fren',
    summary: 'Düşük kaldıraç ve sıkı kayıp koruması ile güvenli testler.',
    shortLabel: 'Muhafazakâr (Maks. 5x · %2 Fren)',
  },
  {
    id: DEFAULT_RISK_POLICY_ID,
    title: 'Dengeli İzole',
    badge: 'Önerilen',
    maxLeverage: '20',
    dailyLossBrake: '%5 Günlük Fren',
    summary: 'Standart vadeli işlemler için dengeli kaldıraç ve risk sınırı.',
    shortLabel: 'Dengeli İzole (Maks. 20x · %5 Fren)',
  },
  {
    id: '99999999-9999-4999-8999-999999999997',
    title: 'İleri Seviye 50x',
    maxLeverage: '50',
    dailyLossBrake: '%10 Günlük Fren',
    summary: '50x kaldıraç desteği ve geniş günlük marjin toleransı.',
    shortLabel: 'İleri Seviye (Maks. 50x · %10 Fren)',
  },
];

export function getRuleProfile(id?: string | null): RuleProfileOption {
  return RULE_PROFILES.find((r) => r.id === id) ?? RULE_PROFILES[0];
}

export function getRiskPolicy(id?: string | null): RiskPolicyOption {
  return RISK_POLICIES.find((p) => p.id === id) ?? RISK_POLICIES[1];
}

export function getEffectiveMaxLeverage(
  instrumentMaxLeverage?: string | null,
  riskPolicyId?: string | null
): { maxLeverage: string; cappedByPolicy: boolean; policy: RiskPolicyOption } {
  const policy = getRiskPolicy(riskPolicyId);
  if (!instrumentMaxLeverage) {
    return { maxLeverage: policy.maxLeverage, cappedByPolicy: true, policy };
  }
  const policyLowerThanInstrument =
    compareDecimalStrings(policy.maxLeverage, instrumentMaxLeverage) <= 0;
  return {
    maxLeverage: policyLowerThanInstrument ? policy.maxLeverage : instrumentMaxLeverage,
    cappedByPolicy: policyLowerThanInstrument,
    policy,
  };
}
