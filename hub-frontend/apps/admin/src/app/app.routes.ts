import { Routes } from '@angular/router';
import { ForbiddenPageComponent, LoginPageComponent } from '@hub/ui';
import { hubAuthGuard } from '@hub/auth';
import { AdminShellComponent } from './admin-shell.component';

export const routes: Routes = [
  { path: 'auth/login', component: LoginPageComponent, title: 'Iniciar sesión' },
  { path: 'forbidden', component: ForbiddenPageComponent, title: 'Acceso denegado' },
  {
    path: '',
    component: AdminShellComponent,
    canActivate: [hubAuthGuard],
    children: [
      {
        path: '',
        loadChildren: () =>
          import('./features/dashboard/dashboard.routes').then(
            (m) => m.DASHBOARD_ROUTES,
          ),
      },
      {
        path: 'soboce',
        loadChildren: () =>
          import('./features/transactions/transactions.routes').then(
            (m) => m.TRANSACTIONS_ROUTES,
          ),
      },
      {
        path: 'admin',
        loadChildren: () =>
          import('./features/admin/admin.routes').then((m) => m.ADMIN_ROUTES),
      },
      {
        path: 'settings',
        loadChildren: () =>
          import('./features/settings/settings.routes').then(
            (m) => m.SETTINGS_ROUTES,
          ),
      },
    ],
  },
];
