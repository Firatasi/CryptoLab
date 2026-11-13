import React from 'react';
import { Link, Navigate, useLocation } from 'react-router-dom';
import { ShieldAlert } from 'lucide-react';
import type { UserRole } from '@/shared/contracts/types';
import { Button, Card, Skeleton } from '@/shared/ui/Primitives';
import { useAuth } from './AuthContext';

export function ProtectedRoute({
  allowedRoles,
  children,
}: {
  allowedRoles?: UserRole[];
  children: React.ReactNode;
}) {
  const { status, user, hasRole } = useAuth();
  const location = useLocation();

  if (status === 'BOOTSTRAPPING') {
    return (
      <div className="mx-auto max-w-4xl space-y-4 p-6">
        <Skeleton className="h-10 w-64" />
        <Skeleton className="h-48 w-full" />
      </div>
    );
  }

  if (status !== 'AUTHENTICATED' || !user) {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  }

  if (allowedRoles && allowedRoles.length > 0 && !hasRole(...allowedRoles)) {
    return (
      <div className="mx-auto max-w-xl py-16 px-4">
        <Card className="p-6 text-center space-y-4">
          <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-none border border-[#dc2626] bg-red-50 text-[#dc2626]">
            <ShieldAlert className="h-6 w-6" aria-hidden="true" />
          </div>
          <div className="space-y-1">
            <h1 className="text-lg font-semibold text-[#0a0a0a]">
              Yetkisiz Erişim (403 FORBIDDEN)
            </h1>
            <p className="text-xs leading-relaxed text-[#737373]">
              Bu çalışma alanı yalnızca{' '}
              <span className="font-mono font-semibold text-[#0a0a0a]">
                {allowedRoles.join(' / ')}
              </span>{' '}
              rolüne sahip hesaplar içindir. Aktif rolleriniz:{' '}
              <span className="font-mono font-semibold text-[#0a0a0a]">
                {user.roles.join(', ')}
              </span>
              .
            </p>
          </div>
          <div className="flex justify-center gap-2 pt-2">
            <Link to="/sessions">
              <Button variant="secondary" size="sm">
                Oturumlara Dön
              </Button>
            </Link>
          </div>
        </Card>
      </div>
    );
  }

  return <>{children}</>;
}
