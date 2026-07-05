import { Routes } from '@angular/router';
import { ForbiddenPageComponent, LoginPageComponent } from '@hub/ui';
import { hubAuthGuard } from '@hub/auth';
import { PublicShellComponent } from './public-shell.component';

export const routes: Routes = [
  { path: 'auth/login', component: LoginPageComponent, title: 'Iniciar sesión' },
  { path: 'forbidden', component: ForbiddenPageComponent, title: 'Acceso denegado' },
  {
    path: '',
    component: PublicShellComponent,
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
        path: 'reportes',
        loadChildren: () =>
          import('./features/reports/reports.routes').then(
            (m) => m.REPORTS_ROUTES,
          ),
      },
    ],
  },
];
