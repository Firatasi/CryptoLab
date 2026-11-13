/**
 * İstemci tarafı Idempotency-Key yöneticisi.
 *
 * KURAL:
 * - Aynı mantıksal kullanıcı eylemi (örn. belirli bir planın submit edilmesi) ağ hatası
 *   veya belirsiz zaman aşımı yaşadığında AYNI Idempotency-Key korunur; böylece sunucu
 *   mükerrer emir oluşturmaz.
 * - Kullanıcı formu değiştirdiğinde veya yeni bir işlem başlattığında YENİ anahtar üretilir.
 */
export function createIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  // Yalnız eski test ortamları için RFC4122 v4 yedek üretici
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (ch) => {
    const r = (Math.random() * 16) | 0;
    const v = ch === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

export class LogicalActionKeyTracker {
  private currentSignature: string | null = null;
  private currentKey: string | null = null;

  public getOrCreate(signature: string): string {
    if (this.currentSignature === signature && this.currentKey) {
      return this.currentKey;
    }
    this.currentSignature = signature;
    this.currentKey = createIdempotencyKey();
    return this.currentKey;
  }

  public reset(): void {
    this.currentSignature = null;
    this.currentKey = null;
  }
}

