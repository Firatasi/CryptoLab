import { createBrowserRouter, Navigate, type RouteObject } from 'react-router-dom';
import { AppShell } from './AppShell';
import { LandingPage } from '@/features/landing/LandingPage';
import {
  LoginPage,
  RegisterPage,
  SettingsPage,
} from '@/features/auth/AuthPages';
import { ProtectedRoute } from '@/features/auth/ProtectedRoute';
import { AdminPage } from '@/features/admin/AdminPage';
import { MarketPage } from '@/features/market/MarketPage';
import { SessionsPage } from '@/features/sessions/SessionsPage';
import { TradeWorkspacePage } from '@/features/trading/TradeWorkspacePage';
import { AnalysisPage } from '@/features/strategy/AnalysisPage';
import { ScanPage } from '@/features/strategy/ScanPage';
import { ReportsPage } from '@/features/reports/ReportsPage';
import { HistoryPage } from '@/features/reports/HistoryPage';
import { ReplayPage } from '@/features/replay/ReplayPage';
import {
  TrainingDatasetsPage,
  TrainingLayout,
  TrainingModelsPage,
  TrainingRunsPage,
  TrainingSourcesPage,
} from '@/features/training/TrainingPages';

export const appRoutes: RouteObject[] = [
  {
    path: '/',
    element: <AppShell />,
    children: [
      { index: true, element: <LandingPage /> },
      { path: 'login', element: <LoginPage /> },
      { path: 'register', element: <RegisterPage /> },
      { path: 'market', element: <MarketPage /> },
      {
        path: 'sessions',
        element: (
          <ProtectedRoute>
            <SessionsPage />
          </ProtectedRoute>
        ),
      },
      {
        path: 'trade/:sessionId',
        element: (
          <ProtectedRoute>
            <TradeWorkspacePage />
          </ProtectedRoute>
        ),
      },
      {
        path: 'analysis/:sessionId',
        element: (
          <ProtectedRoute>
            <AnalysisPage />
          </ProtectedRoute>
        ),
      },
      {
        path: 'scan/:sessionId',
        element: (
          <ProtectedRoute>
            <ScanPage />
          </ProtectedRoute>
        ),
      },
      {
        path: 'reports/:sessionId',
        element: (
          <ProtectedRoute>
            <ReportsPage />
          </ProtectedRoute>
        ),
      },
      {
        path: 'history/:sessionId',
        element: (
          <ProtectedRoute>
            <HistoryPage />
          </ProtectedRoute>
        ),
      },
      {
        path: 'replay',
        element: (
          <ProtectedRoute>
            <ReplayPage />
          </ProtectedRoute>
        ),
      },
      {
        path: 'settings',
        element: (
          <ProtectedRoute>
            <SettingsPage />
          </ProtectedRoute>
        ),
      },
      {
        path: 'admin',
        element: (
          <ProtectedRoute allowedRoles={['ADMIN']}>
            <AdminPage />
          </ProtectedRoute>
        ),
      },
      {
        path: 'training',
        element: (
          <ProtectedRoute allowedRoles={['TRAINER', 'ADMIN']}>
            <TrainingLayout />
          </ProtectedRoute>
        ),
        children: [
          { index: true, element: <Navigate to="/training/sources" replace /> },
          { path: 'sources', element: <TrainingSourcesPage /> },
          { path: 'datasets', element: <TrainingDatasetsPage /> },
          { path: 'runs', element: <TrainingRunsPage /> },
          { path: 'models', element: <TrainingModelsPage /> },
        ],
      },
    ],
  },
];

export function createAppRouter() {
  return createBrowserRouter(appRoutes);
}
