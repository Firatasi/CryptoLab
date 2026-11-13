import type { MouseEvent } from 'react';
import { Link, NavLink, Outlet, useLocation, useNavigate, useParams } from 'react-router-dom';
import { useAuth } from '@/features/auth/AuthContext';
import { loadPersistedJobs } from '@/shared/storage/jobPersistence';
import { SimulationDisclaimerBar } from '@/shared/ui/Feedback';

const FALLBACK_SESSION_UUID = '00000000-0000-4000-8000-000000000001';

function isUuid(val?: string | null): val is string {
  if (!val) return false;
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(val);
}

export function AppShell() {
  const { user, status, hasRole, hasAuthority, logout } = useAuth();
  const { sessionId } = useParams<{ sessionId?: string }>();
  const navigate = useNavigate();
  const location = useLocation();

  const persistedSessionId = loadPersistedJobs(user?.id).activeSessionId;
  const activeSessionId = isUuid(sessionId)
    ? sessionId
    : isUuid(persistedSessionId)
      ? persistedSessionId
      : FALLBACK_SESSION_UUID;
  const isAuthenticated = status === 'AUTHENTICATED';
  const isTrainerOrAdmin = hasRole('TRAINER', 'ADMIN');
  const isAdmin = hasRole('ADMIN');
  const canPublish = hasAuthority('MODEL_PUBLISH');
  const apiMode = import.meta.env.VITE_API_MODE ?? 'live';
  const isLanding = location.pathname === '/';

  const publicNavItems = [
    { hash: '#terminal', label: 'Canlı Grafik' },
    { hash: '#hakkimizda', label: 'Hakkımızda' },
    { hash: '#vizyon-misyon', label: 'Vizyon & Misyon' },
    { hash: '#iletisim', label: 'İletişim' },
  ];

  const navItems = [
    { to: '/sessions', label: 'Oturumlar', show: isAuthenticated },
    {
      to: `/trade/${encodeURIComponent(activeSessionId)}`,
      label: 'Emir Terminali',
      show: isAuthenticated,
    },
    {
      to: `/analysis/${encodeURIComponent(activeSessionId)}`,
      label: 'AI Analiz',
      show: isAuthenticated,
    },
    {
      to: `/history/${encodeURIComponent(activeSessionId)}`,
      label: 'İşlem Geçmişi',
      show: isAuthenticated,
    },
    {
      to: '/training/sources',
      label: 'Eğitici Alanı (Trainer)',
      show: isAuthenticated && isTrainerOrAdmin,
    },
    {
      to: '/admin',
      label: 'Admin Paneli',
      show: isAuthenticated && isAdmin,
    },
  ];

  const handlePublicNavClick = (hash: string) => (e: MouseEvent<HTMLAnchorElement>) => {
    e.preventDefault();
    if (location.pathname !== '/') {
      navigate(`/${hash}`);
      window.setTimeout(() => {
        const el = document.querySelector(hash);
        el?.scrollIntoView({ behavior: 'smooth', block: 'start' });
      }, 100);
    } else {
      window.history.replaceState(null, '', hash);
      const el = document.querySelector(hash);
      el?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  };

  return (
    <div className="min-h-screen flex flex-col bg-[#ffffff] text-[#0a0a0a]">
      <SimulationDisclaimerBar apiMode={apiMode} />

      <header className="sticky top-0 z-30 border-b border-[#262626] bg-[#0a0a0a] text-white">
        <div className="mx-auto flex max-w-7xl items-center justify-between gap-4 px-6 py-3.5">
          <div className="flex shrink-0 items-center">
            <Link
              to="/"
              className="group relative flex items-center font-handwriting text-2xl font-bold tracking-normal text-white transition-transform duration-200 hover:scale-105"
            >
              <span>CryptoLab</span>
              <svg
                aria-hidden="true"
                viewBox="0 0 120 12"
                className="pointer-events-none absolute -bottom-1 left-0 h-2.5 w-full stroke-white/80 transition-all duration-300 group-hover:stroke-white"
                fill="none"
              >
                <path
                  d="M2,8 Q38,2 76,7 T118,5"
                  strokeWidth="1.8"
                  strokeLinecap="round"
                  className=" [stroke-dasharray:130] [stroke-dashoffset:0] group-hover:animate-[signature-flourish_0.6s_ease-out_forwards]"
                />
              </svg>
            </Link>
          </div>

          <nav
            aria-label="Ana Gezinme"
            className="hidden flex-1 flex-nowrap items-center justify-center gap-5 whitespace-nowrap lg:flex"
          >
            {!isAuthenticated &&
              publicNavItems.map((item) => (
                <a
                  key={item.hash}
                  href={`/${item.hash}`}
                  onClick={handlePublicNavClick(item.hash)}
                  className="whitespace-nowrap text-xs font-normal text-[#a3a3a3] transition-colors hover:text-white"
                >
                  {item.label}
                </a>
              ))}
            {navItems
              .filter((item) => item.show)
              .map((item) => (
                <NavLink
                  key={item.to}
                  to={item.to}
                  className={({ isActive }) =>
                    `whitespace-nowrap text-xs transition-colors ${
                      isActive
                        ? 'font-semibold text-white underline underline-offset-8 decoration-white'
                        : 'font-normal text-[#a3a3a3] hover:text-white'
                    }`
                  }
                >
                  {item.label}
                </NavLink>
              ))}
          </nav>

          <div className="flex shrink-0 items-center justify-end gap-3">
            {isAuthenticated && user ? (
              <>
                <div className="hidden items-center gap-2 sm:flex">
                  <span className="text-xs font-medium text-white">
                    {user.displayName}
                  </span>
                  {canPublish && (
                    <span className="border border-white bg-white px-2 py-0.5 font-mono text-[10px] font-semibold uppercase tracking-wider text-[#0a0a0a]">
                      MODEL_PUBLISH
                    </span>
                  )}
                </div>
                <Link
                  to="/settings"
                  className="px-2.5 py-1.5 text-xs text-[#a3a3a3] transition-colors hover:text-white"
                >
                  Ayarlar
                </Link>
                <button
                  type="button"
                  data-testid="header-logout-btn"
                  onClick={async () => {
                    await logout();
                    navigate('/login');
                  }}
                  className="rounded-full bg-white px-4 py-1.5 text-xs font-medium text-[#0a0a0a] transition-colors hover:bg-[#e5e5e5]"
                >
                  Çıkış
                </button>
              </>
            ) : (
              <div className="flex items-center gap-3">
                <Link
                  to="/login"
                  className="px-3 py-1.5 text-xs font-normal text-[#a3a3a3] transition-colors hover:text-white"
                >
                  Giriş Yap
                </Link>
                <Link
                  to="/register"
                  className="rounded-full bg-white px-4 py-1.5 text-xs font-medium text-[#0a0a0a] transition-colors hover:bg-[#e5e5e5]"
                >
                  Kayıt Ol
                </Link>
              </div>
            )}
          </div>
        </div>

        {/* Mobil / Küçük Ekran Yatay Gezinme */}
        <nav
          aria-label="Mobil Gezinme"
          className="flex justify-center overflow-x-auto border-t border-[#262626] px-6 py-2 lg:hidden"
        >
          <div className="flex items-center gap-5">
            {!isAuthenticated &&
              publicNavItems.map((item) => (
                <a
                  key={item.hash}
                  href={`/${item.hash}`}
                  onClick={handlePublicNavClick(item.hash)}
                  className="whitespace-nowrap text-xs text-[#a3a3a3] hover:text-white"
                >
                  {item.label}
                </a>
              ))}
            {navItems
              .filter((item) => item.show)
              .map((item) => (
                <NavLink
                  key={item.to}
                  to={item.to}
                  className={({ isActive }) =>
                    `whitespace-nowrap text-xs ${
                      isActive
                        ? 'font-semibold text-white underline underline-offset-4'
                        : 'text-[#a3a3a3] hover:text-white'
                    }`
                  }
                >
                  {item.label}
                </NavLink>
              ))}
          </div>
        </nav>
      </header>

      <main
        className={
          isLanding
            ? 'w-full flex-1'
            : location.pathname.startsWith('/trade')
              ? 'mx-auto w-full max-w-[1600px] flex-1 px-4 py-4 sm:px-6'
              : 'mx-auto w-full max-w-7xl flex-1 px-6 py-6'
        }
      >
        <Outlet />
      </main>
    </div>
  );
}
