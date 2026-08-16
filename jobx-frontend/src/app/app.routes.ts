import { Routes } from '@angular/router';
import { authGuard, guestGuard } from './core/guards/auth.guard';

/**
 * /dashboard is the focused feed and the default redirect (uiux_plan.md §6).
 * Everything except the two auth pages sits behind authGuard and inside the shell.
 */
export const routes: Routes = [
  {
    path: 'login',
    canActivate: [guestGuard],
    title: 'Sign in · Jobx',
    loadComponent: () => import('./features/auth/login.page').then((m) => m.LoginPage),
  },
  {
    path: 'register',
    canActivate: [guestGuard],
    title: 'Create account · Jobx',
    loadComponent: () => import('./features/auth/register.page').then((m) => m.RegisterPage),
  },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./shared/layout/app-shell').then((m) => m.AppShell),
    children: [
      {
        path: 'dashboard',
        title: 'Dashboard · Jobx',
        loadComponent: () =>
          import('./features/dashboard/dashboard.page').then((m) => m.DashboardPage),
      },
      {
        path: 'matches',
        title: 'Matches · Jobx',
        loadComponent: () => import('./features/matches/matches.page').then((m) => m.MatchesPage),
      },
      {
        path: 'watchlist',
        title: 'Watchlist · Jobx',
        loadComponent: () =>
          import('./features/watchlist/watchlist.page').then((m) => m.WatchlistPage),
      },
      {
        path: 'profile',
        title: 'Profile · Jobx',
        loadComponent: () => import('./features/profile/profile.page').then((m) => m.ProfilePage),
      },
      { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
    ],
  },
  { path: '**', redirectTo: '' },
];
