import type { ApiErrorField, ApiErrorPayload } from '@/shared/contracts/types';

export class ApiError extends Error {
  public readonly status: number;
  public readonly code: string;
  public readonly traceId: string | null;
  public readonly retryable: boolean;
  public readonly fields: ApiErrorField[];
  public readonly details: Record<string, unknown>;
  public readonly isNetworkError: boolean;

  constructor(params: {
    status: number;
    code: string;
    message: string;
    traceId?: string | null;
    retryable?: boolean;
    fields?: ApiErrorField[];
    details?: Record<string, unknown>;
    isNetworkError?: boolean;
  }) {
    super(params.message);
    this.name = 'ApiError';
    this.status = params.status;
    this.code = params.code;
    this.traceId = params.traceId ?? null;
    this.retryable = params.retryable ?? false;
    this.fields = params.fields ?? [];
    this.details = params.details ?? {};
    this.isNetworkError = params.isNetworkError ?? false;
  }
}

const CODE_MESSAGES_TR: Record<string, string> = {
  UNAUTHORIZED: 'Oturum doğrulanamadı veya süresi doldu. Lütfen yeniden giriş yapın.',
  INVALID_CREDENTIALS: 'E-posta adresi veya parola hatalı.',
  EMAIL_ALREADY_EXISTS: 'Bu e-posta adresi ile kayıtlı bir hesap zaten mevcut.',
  CSRF_REJECTED: 'Güvenlik doğrulaması tamamlanamadı. Lütfen sayfayı yenileyip tekrar deneyin.',
  FORBIDDEN: 'Bu işlem veya sayfa için hesabınızda gerekli yetki bulunmuyor.',
  NOT_FOUND: 'Aradığınız kayıt bulunamadı veya bu oturuma ait değil.',
  SESSION_NOT_FOUND: 'Sanal işlem oturumu bulunamadı.',
  NO_ROUTE: 'Aradığınız sayfa veya işlem bulunamadı.',
  VALIDATION_FAILED: 'Girdiğiniz bilgilerde eksik veya hatalı alanlar var. Lütfen kontrol edin.',
  INVALID_REQUEST: 'Gönderilen bilgiler geçersiz. Lütfen formu kontrol edip tekrar deneyin.',
  INVALID_QUERY: 'Seçilen filtre veya sorgu bilgileri geçersiz.',
  INVALID_KEY: 'İşlem güvenlik anahtarı doğrulanamadı. Lütfen sayfayı yenileyip tekrar deneyin.',
  IDEMPOTENCY_CONFLICT:
    'İşlem bilgileri değiştiği için önceki istek tamamlanamadı. Lütfen formu kontrol edip tekrar deneyin.',
  CONFLICT: 'Bu kayıt mevcut verilerle çakışıyor. Lütfen güncel bilgileri kontrol edin.',
  PLAN_EXPIRED:
    'Emir önizleme süresinin geçerliliği doldu. Güncel piyasa fiyatıyla yeni bir önizleme alın.',
  PLAN_REVOKED: 'Emir önizlemesi güncelliğini yitirdi. Lütfen yeniden önizleme oluşturun.',
  REVALIDATION_REQUIRED:
    'Piyasa fiyatı veya risk parametreleri onay sınırlarının dışına çıktı. Güncel planı inceleyip yeniden onaylayın.',
  VERSION_CONFLICT:
    'Bilgiler başka bir işlemle güncellendiği için ekran otomatik yenileniyor. Lütfen tekrar deneyin.',
  ALIAS_VERSION_CONFLICT:
    'Aktif model durumu az önce güncellendi. Listeyi yenileyip tekrar deneyin.',
  STALE_MARK: 'Anlık piyasa fiyatı gecikmeli olduğu için işleminiz güvenlik amacıyla durduruldu.',
  STALE_QUOTE: 'Piyasa alış/satış fiyatı güncelliğini yitirdiği için işlem gerçekleştirilemedi.',
  INSUFFICIENT_AVAILABLE_BALANCE:
    'Kullanılabilir sanal bakiyeniz (işlem komisyonu ve teminat dahil) bu işlem için yeterli değil.',
  LIQUIDITY_LIMIT: 'Emir tutarı bu varlık için izin verilen en yüksek işlem sınırını aşıyor.',
  SESSION_HALTED: 'Bu sanal oturum şu anda duraklatılmış durumda; yeni pozisyon açılamaz.',
  MIN_NOTIONAL: 'Emir tutarı bu sözleşme için gereken en düşük işlem tutarının altında.',
  INVALID_LEVERAGE: 'Seçilen kaldıraç oranı seçili varlık veya risk sınırlarınızın dışında.',
  INVALID_STOP: 'Zarar Durdur (SL) seviyesi mevcut piyasa fiyatına ve işlem yönüne göre geçersiz.',
  INVALID_TARGET: 'Kâr Al (TP) seviyesi mevcut piyasa fiyatına ve işlem yönüne göre geçersiz.',
  STOP_LOSS_PAST_LIQUIDATION:
    'Zarar Durdur (SL) seviyesi pozisyonun tasfiye (likidasyon) sınırını aşıyor. Likid olmadan kapanacak daha düşük bir zarar tutarı veya güvenli tetik fiyatı girin.',
  BRACKET_REQUIRED: 'Bu emir türü için Zarar Durdur ve Kâr Al seviyelerinin ikisi de zorunludur.',
  UPLOAD_SIZE: 'Yüklenen PDF dosyası 20 MB boyut sınırını aşıyor veya dosya boş.',
  CAPTCHA_INVALID: 'Güvenlik doğrulama kodu (CAPTCHA) hatalı. Lütfen kontrol edip tekrar deneyin.',
  CAPTCHA_EXPIRED: 'Güvenlik doğrulama kodunun süresi doldu veya kullanıldı. Yeni kod ile tekrar deneyin.',
  PAYLOAD_TOO_LARGE: 'Gönderilen dosya veya veri boyutu izin verilen sınırı aşıyor.',
  TRAINING_UNAVAILABLE: 'Yapay zekâ eğitim sistemi şu anda yoğun. Lütfen biraz sonra tekrar deneyin.',
  MODEL_UNAVAILABLE: 'Yapay zekâ analiz motoru şu anda yanıt veremiyor. Lütfen birkaç saniye sonra tekrar deneyin.',
  MODEL_NOT_READY: 'Yapay zekâ analiz modeli şu anda hazırlanıyor. Lütfen kısa bir süre sonra tekrar deneyin.',
  RATE_LIMITED: 'Çok fazla deneme yaptınız. Lütfen kısa bir süre bekleyip tekrar deneyin.',
  SERVICE_UNAVAILABLE: 'Sistem şu anda yoğun veya geçici olarak yanıt veremiyor. Lütfen biraz sonra tekrar deneyin.',
  INTERNAL_ERROR: 'İşleminiz şu anda tamamlanamadı. Lütfen birkaç saniye sonra tekrar deneyin.',
  NETWORK_ERROR:
    'Sunucu ile bağlantı kurulamadı veya istek zaman aşımına uğradı. İşlem durumunuzu kontrol ettikten sonra tekrar deneyin.',
};

const TECHNICAL_LEAK_PATTERN =
  /\b(http\s*\d{3}|status\s*code|gateway|controller|backend|trace\s*id|expectedVersion|expectedAliasVersion|idempotency-key|x-csrf|sql|jdbc|exception|stack|localhost|econnrefused|typeerror|syntaxerror|referenceerror|unexpected token|failed to fetch|networkerror|load failed|nullpointer)\b|^[A-Z0-9_]{4,}(?::.*)?$/i;

export function sanitizeUserMessage(
  raw: string | null | undefined,
  fallback = 'İşleminiz şu anda tamamlanamadı. Lütfen tekrar deneyin.',
): string {
  if (!raw || typeof raw !== 'string') {
    return fallback;
  }
  const trimmed = raw.trim();
  if (!trimmed) {
    return fallback;
  }

  const codeMatch = /^([A-Z0-9_]{3,})(?::\s*(.*))?$/.exec(trimmed);
  if (codeMatch) {
    const codeKey = codeMatch[1];
    if (CODE_MESSAGES_TR[codeKey]) {
      return CODE_MESSAGES_TR[codeKey];
    }
    if (codeKey === 'FEATURE_SCHEMA_MISMATCH' || codeKey === 'COVERAGE_INSUFFICIENT') {
      return 'Bu varlık için seçilen zaman diliminde yeterli güncel piyasa verisi henüz hazır değil.';
    }
    if (codeKey === 'OUT_OF_DOMAIN') {
      return 'Piyasadaki olağan dışı hareket nedeniyle güvenli analiz oluşturulamadı. Lütfen biraz sonra tekrar deneyin.';
    }
    if (codeKey === 'STALE_SESSION_GENERATION' || codeKey === 'SESSION_NOT_RUNNING') {
      return 'Oturum durumunuz analiz sırasında güncellendi. Lütfen yeni bir analiz başlatın.';
    }
    return fallback;
  }

  if (/failed to fetch|networkerror|load failed|network request failed/i.test(trimmed)) {
    return CODE_MESSAGES_TR.NETWORK_ERROR;
  }

  if (TECHNICAL_LEAK_PATTERN.test(trimmed)) {
    return fallback;
  }

  return trimmed;
}

export function toUserFacingErrorMessage(
  error: unknown,
  fallback = 'İşleminiz şu anda tamamlanamadı. Lütfen biraz sonra tekrar deneyin.',
): string {
  if (isApiError(error)) {
    if (CODE_MESSAGES_TR[error.code]) {
      return CODE_MESSAGES_TR[error.code];
    }
    return sanitizeUserMessage(error.message, defaultMessageForStatus(error.status) || fallback);
  }
  if (error instanceof Error) {
    return sanitizeUserMessage(error.message, fallback);
  }
  if (typeof error === 'string') {
    return sanitizeUserMessage(error, fallback);
  }
  return fallback;
}

export function toApiErrorFromPayload(status: number, payload: ApiErrorPayload | null): ApiError {
  const code = payload?.code || defaultCodeForStatus(status);
  const rawFields = Array.isArray(payload?.details?.fields) ? payload.details.fields : [];
  const fields: ApiErrorField[] = rawFields.filter(
    (item): item is ApiErrorField =>
      Boolean(item && typeof item.field === 'string' && typeof item.message === 'string')
  );

  const friendlyMessage =
    CODE_MESSAGES_TR[code] ||
    sanitizeUserMessage(
      payload?.message && payload.message !== 'İstek tamamlanamadı.' ? payload.message : null,
      defaultMessageForStatus(status),
    );

  return new ApiError({
    status,
    code,
    message: friendlyMessage,
    traceId: payload?.traceId ?? null,
    retryable: Boolean(payload?.retryable ?? (status === 502 || status === 503 || status === 504)),
    fields,
    details: payload?.details ?? {},
  });
}

function defaultCodeForStatus(status: number): string {
  switch (status) {
    case 400:
      return 'BAD_REQUEST';
    case 401:
      return 'UNAUTHORIZED';
    case 403:
      return 'FORBIDDEN';
    case 404:
      return 'NOT_FOUND';
    case 409:
      return 'CONFLICT';
    case 413:
      return 'PAYLOAD_TOO_LARGE';
    case 422:
      return 'UNPROCESSABLE_ENTITY';
    case 429:
      return 'RATE_LIMITED';
    case 502:
    case 503:
    case 504:
      return 'SERVICE_UNAVAILABLE';
    default:
      return `HTTP_${status}`;
  }
}

function defaultMessageForStatus(status: number): string {
  if (status === 404) {
    return 'Aradığınız sayfa veya kayıt bulunamadı.';
  }
  if (status === 413) {
    return 'Gönderilen içerik izin verilen boyut sınırını aşıyor.';
  }
  if (status === 429) {
    return 'Çok fazla deneme yaptınız. Lütfen biraz bekleyip tekrar deneyin.';
  }
  if (status >= 500 || status === 0) {
    return 'İşleminiz şu anda gerçekleştirilemiyor. Lütfen biraz sonra tekrar deneyin.';
  }
  return 'İstek tamamlanamadı. Lütfen bilgilerinizi kontrol edip tekrar deneyin.';
}

export function isApiError(err: unknown): err is ApiError {
  return err instanceof ApiError;
}

