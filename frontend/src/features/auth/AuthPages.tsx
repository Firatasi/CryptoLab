import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { Check, Clock, Copy, LogOut, RefreshCw, Shield, UserCheck } from 'lucide-react';
import type { CaptchaChallengeResponse } from '@/shared/contracts/types';
import { authApi } from './api';
import { LogicalActionKeyTracker } from '@/shared/api/idempotency';
import { formatTimestamp, type TimezonePreference } from '@/shared/formatting/formatters';
import { Badge, Button, Card, CardHeader } from '@/shared/ui/Primitives';
import { FieldGroup, SelectInput, TextInput } from '@/shared/ui/FormControls';
import { ErrorBanner } from '@/shared/ui/Feedback';
import { useAuth } from './AuthContext';

export function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const explicitFromPath =
    (location.state as { from?: string } | null)?.from &&
    !(location.state as { from?: string }).from?.startsWith('/login')
      ? (location.state as { from: string }).from
      : null;

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!email.trim() || !password) return;
    setLoading(true);
    setError(null);
    try {
      const loggedIn = await login({ email: email.trim(), password });
      const target =
        explicitFromPath ?? (loggedIn.roles.includes('ADMIN') ? '/admin' : '/sessions');
      navigate(target, { replace: true });
    } catch (err) {
      // Hata durumunda kullanıcının e-posta girdisi korunur
      setError(err);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="mx-auto flex min-h-[calc(100vh-9rem)] max-w-md flex-col justify-center px-4 py-10">
      <Card className="p-6 space-y-5">
        <div className="space-y-1">
          <h1 className="text-xl font-semibold tracking-tight text-[#0a0a0a]">
            Hesabınıza Giriş Yapın
          </h1>
          <p className="text-xs text-[#737373]">
            Sanal vadeli işlem oturumlarınıza, AI analizlerine ve tarihsel replay kayıtlarınıza erişin.
          </p>
        </div>

        {error !== null && <ErrorBanner error={error} title="Giriş başarısız" />}

        <form onSubmit={handleSubmit} className="space-y-4" noValidate>
          <FieldGroup label="E-posta Adresi" required>
            {(id) => (
              <TextInput
                id={id}
                type="text"
                autoComplete="username"
                placeholder="ornek@cryptolab.local veya admin.com"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                required
              />
            )}
          </FieldGroup>

          <FieldGroup label="Parola" required>
            {(id) => (
              <TextInput
                id={id}
                type="password"
                autoComplete="current-password"
                placeholder="••••••••••••"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
              />
            )}
          </FieldGroup>

          <Button
            type="submit"
            variant="primary"
            size="lg"
            loading={loading}
            disabled={!email.trim() || !password}
            className="w-full"
          >
            Giriş Yap
          </Button>
        </form>

        <div className="border-t border-[#e5e5e5] pt-4 text-center text-xs text-[#737373]">
          Henüz hesabınız yok mu?{' '}
          <Link to="/register" className="font-semibold text-[#0a0a0a] underline hover:opacity-80">
            Yeni Sanal Hesap Oluşturun
          </Link>
        </div>
      </Card>
    </div>
  );
}

export function RegisterPage() {
  const { register, login } = useAuth();
  const navigate = useNavigate();
  const keyTrackerRef = useRef(new LogicalActionKeyTracker());

  const [displayName, setDisplayName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [captcha, setCaptcha] = useState<CaptchaChallengeResponse | null>(null);
  const [captchaAnswer, setCaptchaAnswer] = useState('');
  const [captchaLoading, setCaptchaLoading] = useState(false);
  const [captchaLoadError, setCaptchaLoadError] = useState<string | null>(null);
  const [validationError, setValidationError] = useState<string | null>(null);
  const [apiError, setApiError] = useState<unknown>(null);
  const [loading, setLoading] = useState(false);

  const loadCaptcha = useCallback(async () => {
    setCaptchaLoading(true);
    setCaptchaLoadError(null);
    try {
      const challenge = await authApi.getCaptcha();
      setCaptcha(challenge);
      setCaptchaAnswer('');
    } catch {
      setCaptchaLoadError('Doğrulama görseli yüklenemedi. Lütfen yenileyin.');
    } finally {
      setCaptchaLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadCaptcha();
  }, [loadCaptcha]);

  const validateClient = (): boolean => {
    if (!displayName.trim() || displayName.trim().length > 80) {
      setValidationError('Görünen ad 1 ile 80 karakter arasında olmalıdır.');
      return false;
    }
    if (!email.trim() || !email.includes('@') || email.trim().length > 254) {
      setValidationError('Geçerli bir e-posta adresi girin (en fazla 254 karakter).');
      return false;
    }
    if (password.length < 12 || password.length > 72) {
      setValidationError(
        'Parola sözleşme kuralı gereği en az 12, en fazla 72 karakter olmalıdır.'
      );
      return false;
    }
    setValidationError(null);
    return true;
  };

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!validateClient()) return;

    setLoading(true);
    setApiError(null);

    const trimmedEmail = email.trim();
    const trimmedName = displayName.trim();
    const idemKey = keyTrackerRef.current.getOrCreate(`${trimmedEmail}:${trimmedName}`);

    try {
      if (!captcha?.captchaId || !captchaAnswer.trim()) {
        setValidationError('Lütfen güvenlik doğrulama kodunu girin.');
        return;
      }
      await register(
        {
          email: trimmedEmail,
          password,
          displayName: trimmedName,
          captchaId: captcha.captchaId,
          captchaAnswer: captchaAnswer.trim(),
        },
        idemKey
      );
      keyTrackerRef.current.reset();
      await login({ email: trimmedEmail, password });
      navigate('/sessions', { replace: true });
    } catch (err) {
      setApiError(err);
      void loadCaptcha();
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="mx-auto flex min-h-[calc(100vh-9rem)] max-w-md flex-col justify-center px-4 py-10">
      <Card className="p-6 space-y-5">
        <div className="space-y-1">
          <h1 className="text-xl font-semibold tracking-tight text-[#0a0a0a]">
            Yeni Hesap Oluştur
          </h1>
        </div>

        {validationError && (
          <div
            role="alert"
            className="rounded-none border border-[#0a0a0a] bg-[#f5f5f5] px-3 py-2 text-xs text-[#0a0a0a]"
          >
            {validationError}
          </div>
        )}

        {apiError !== null && <ErrorBanner error={apiError} title="Kayıt tamamlanamadı" />}

        <form onSubmit={handleSubmit} className="space-y-4" noValidate>
          <FieldGroup label="Görünen Ad" hint="En fazla 80 karakter" required>
            {(id) => (
              <TextInput
                id={id}
                type="text"
                autoComplete="name"
                maxLength={80}
                placeholder="Fırat Araştırmacı"
                value={displayName}
                onChange={(e) => setDisplayName(e.target.value)}
                required
              />
            )}
          </FieldGroup>

          <FieldGroup label="E-posta Adresi" required>
            {(id) => (
              <TextInput
                id={id}
                type="email"
                autoComplete="email"
                maxLength={254}
                placeholder="ornek@cryptolab.local"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                required
              />
            )}
          </FieldGroup>

          <FieldGroup
            label="Parola"
            hint={`En az 12, en fazla 72 karakter (${password.length}/72)`}
            required
          >
            {(id) => (
              <TextInput
                id={id}
                type="password"
                autoComplete="new-password"
                minLength={12}
                maxLength={72}
                placeholder="En az 12 karakterli güçlü parola"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
              />
            )}
          </FieldGroup>

          <div className="space-y-3 border border-[#e5e5e5] bg-[#fafafa] p-3">
            <div className="flex items-center justify-between gap-2">
              <span className="font-mono text-[11px] font-semibold uppercase tracking-wider text-[#0a0a0a]">
                Güvenlik Doğrulaması (CAPTCHA)
              </span>
              <Button
                type="button"
                variant="secondary"
                size="sm"
                onClick={() => void loadCaptcha()}
                disabled={captchaLoading}
                aria-label="Kodu Yenile"
              >
                <RefreshCw className={`h-3.5 w-3.5 ${captchaLoading ? 'animate-spin' : ''}`} />
                <span>Yenile</span>
              </Button>
            </div>

            {captcha ? (
              <div className="flex items-center justify-center border border-[#d4d4d4] bg-white p-2">
                <img
                  src={captcha.imageDataUri}
                  alt="Güvenlik doğrulama kodu"
                  className="h-14 w-full max-w-[200px] select-none object-contain"
                />
              </div>
            ) : (
              <div className="flex h-14 items-center justify-center border border-[#d4d4d4] bg-white font-mono text-xs text-[#737373]">
                {captchaLoadError ?? 'Doğrulama kodu yükleniyor...'}
              </div>
            )}

            <FieldGroup
              label="Güvenlik Kodu"
              hint="Görseldeki 5 karakteri büyük/küçük harf fark etmeksizin girin."
              required
            >
              {(id) => (
                <TextInput
                  id={id}
                  type="text"
                  autoComplete="off"
                  spellCheck={false}
                  maxLength={10}
                  placeholder="Örn: A7K3M"
                  value={captchaAnswer}
                  onChange={(e) => setCaptchaAnswer(e.target.value.toUpperCase())}
                  className="font-mono uppercase tracking-widest"
                  required
                />
              )}
            </FieldGroup>
          </div>

          <Button type="submit" variant="primary" size="lg" loading={loading} className="w-full">
            Hesap Oluştur ve Başla
          </Button>
        </form>

        <div className="border-t border-[#e5e5e5] pt-4 text-center text-xs text-[#737373]">
          Zaten hesabınız var mı?{' '}
          <Link to="/login" className="font-semibold text-[#0a0a0a] underline hover:opacity-80">
            Giriş Yapın
          </Link>
        </div>
      </Card>
    </div>
  );
}

const ROLE_LABELS: Record<string, string> = {
  ADMIN: 'Sistem Yöneticisi',
  TRAINER: 'Model Eğitici',
  USER: 'Standart Kullanıcı',
};

export function SettingsPage() {
  const { user, timezone, setTimezone, hasRole, logout } = useAuth();
  const navigate = useNavigate();
  const [loggingOut, setLoggingOut] = useState(false);
  const [copiedId, setCopiedId] = useState(false);
  const [nowIso, setNowIso] = useState(() => new Date().toISOString());

  useEffect(() => {
    const timer = window.setInterval(() => {
      setNowIso(new Date().toISOString());
    }, 1000);
    return () => window.clearInterval(timer);
  }, []);

  const handleLogout = async () => {
    setLoggingOut(true);
    await logout();
    navigate('/login', { replace: true });
  };

  const handleCopyId = async () => {
    if (!user?.id || !navigator.clipboard?.writeText) return;
    try {
      await navigator.clipboard.writeText(user.id);
      setCopiedId(true);
      window.setTimeout(() => setCopiedId(false), 2000);
    } catch {
      setCopiedId(false);
    }
  };

  if (!user) return null;

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <div className="border-b border-[#e5e5e5] pb-4">
        <h1 className="text-xl font-semibold tracking-tight text-[#0a0a0a]">
          Hesap ve Görünüm Ayarları
        </h1>
        <p className="mt-1 text-xs text-[#737373]">
          Hesap bilgilerinizi, saat dilimi tercihlerinizi ve aktif oturum işlemlerinizi yönetin.
        </p>
      </div>

      <Card>
        <CardHeader
          title="Hesap Profili"
          subtitle="Aktif kullanıcı bilgileri ve erişim düzeyi"
          action={<Badge tone="success">Aktif</Badge>}
        />
        <div className="grid gap-4 p-5 sm:grid-cols-2">
          <div className="rounded-none border border-[#e5e5e5] bg-[#f5f5f5] p-3.5 space-y-2">
            <div className="flex items-center gap-2 text-xs text-[#737373]">
              <UserCheck className="h-4 w-4 text-[#0a0a0a]" aria-hidden="true" />
              Kullanıcı Adı
            </div>
            <div className="text-sm font-semibold text-[#0a0a0a]">{user.displayName}</div>
            <div className="flex items-center justify-between gap-2 pt-0.5">
              <span className="truncate font-mono text-[11px] text-[#737373]">
                Hesap No: {user.id}
              </span>
              <button
                type="button"
                onClick={() => void handleCopyId()}
                className="inline-flex shrink-0 items-center gap-1 border border-[#d4d4d4] bg-white px-2 py-0.5 font-mono text-[10px] text-[#0a0a0a] transition-colors hover:bg-[#0a0a0a] hover:text-white"
              >
                {copiedId ? (
                  <>
                    <Check className="h-3 w-3" aria-hidden="true" />
                    <span>Kopyalandı</span>
                  </>
                ) : (
                  <>
                    <Copy className="h-3 w-3" aria-hidden="true" />
                    <span>Kopyala</span>
                  </>
                )}
              </button>
            </div>
          </div>

          <div className="rounded-none border border-[#e5e5e5] bg-[#f5f5f5] p-3.5 space-y-2">
            <div className="flex items-center gap-2 text-xs text-[#737373]">
              <Shield className="h-4 w-4 text-[#0a0a0a]" aria-hidden="true" />
              Erişim Düzeyi
            </div>
            <div className="flex flex-wrap items-center gap-2 pt-1">
              {user.roles.map((role) => (
                <div key={role} className="flex items-center gap-1.5">
                  <Badge tone={role === 'USER' ? 'neutral' : 'paper'}>{role}</Badge>
                  <span className="text-xs font-medium text-[#0a0a0a]">
                    {ROLE_LABELS[role] ?? role}
                  </span>
                </div>
              ))}
            </div>
          </div>
        </div>
      </Card>

      <Card>
        <CardHeader
          title="Zaman Dilimi ve Gösterim Tercihi"
          subtitle="Tablo, grafik, rapor ve replay zaman damgalarının görünüm biçimi"
        />
        <div className="grid gap-4 p-5 sm:grid-cols-[1.4fr_1fr] sm:items-end">
          <FieldGroup
            label="Zaman Dilimi"
            hint="Bu tercih yalnızca ekrandaki tarih ve saat gösterimini etkiler."
          >
            {(id) => (
              <SelectInput
                id={id}
                value={timezone}
                onChange={(e) => setTimezone(e.target.value as TimezonePreference)}
              >
                <option value="Europe/Istanbul">Türkiye Saati — Europe/Istanbul (TSİ, UTC+3)</option>
                <option value="UTC">Evrensel Eşgüdümlü Saat — UTC</option>
              </SelectInput>
            )}
          </FieldGroup>

          <div className="rounded-none border border-[#e5e5e5] bg-[#f5f5f5] p-3.5">
            <div className="flex items-center gap-1.5 font-mono text-[11px] uppercase tracking-wider text-[#737373]">
              <Clock className="h-3.5 w-3.5 text-[#0a0a0a]" aria-hidden="true" />
              <span>Canlı Saat Önizlemesi</span>
            </div>
            <div
              className="mt-1.5 font-mono text-xs font-semibold text-[#0a0a0a]"
              data-testid="timezone-preview"
            >
              {formatTimestamp(nowIso, timezone)}
            </div>
          </div>
        </div>
      </Card>

      <Card>
        <CardHeader
          title="Çalışma Alanı ve Oturum"
          subtitle="Hızlı geçiş bağlantıları ve aktif hesap çıkışı"
        />
        <div className="flex flex-wrap items-center justify-between gap-4 p-5">
          <div className="flex flex-wrap items-center gap-2">
            <Link to="/sessions">
              <Button type="button" variant="secondary" size="sm">
                İşlem Oturumları
              </Button>
            </Link>
            <Link to="/">
              <Button type="button" variant="secondary" size="sm">
                Piyasa Genel Bakış
              </Button>
            </Link>
            {hasRole('TRAINER', 'ADMIN') && (
              <Link to="/training/sources">
                <Button type="button" variant="secondary" size="sm">
                  Eğitici Alanı
                </Button>
              </Link>
            )}
            {hasRole('ADMIN') && (
              <Link to="/admin">
                <Button type="button" variant="secondary" size="sm">
                  Admin Paneli
                </Button>
              </Link>
            )}
          </div>

          <Button
            variant="danger"
            onClick={handleLogout}
            loading={loggingOut}
            leftIcon={<LogOut className="h-4 w-4" aria-hidden="true" />}
          >
            Çıkış Yap
          </Button>
        </div>
      </Card>
    </div>
  );
}
