import { Routes } from '@angular/router';

export const REPORTS_ROUTES: Routes = [
  {
    path: 'pendientes',
    loadComponent: () =>
      import('./consulta-pendientes/consulta-pendientes.component').then(
        (m) => m.ConsultaPendientesComponent,
      ),
    title: 'Mis Pendientes',
  },
  {
    path: 'pagos',
    loadComponent: () =>
      import('./pagos-contado/pagos-contado.component').then(
        (m) => m.PagosContadoComponent,
      ),
    title: 'Mis Pagos',
  },
  {
    path: 'comprobantes',
    loadComponent: () =>
      import('./reimpresion-comprobantes/reimpresion-comprobantes.component').then(
        (m) => m.ReimpresionComprobantesComponent,
      ),
    title: 'Mis Comprobantes',
  },
  {
    path: 'cobranza',
    loadComponent: () =>
      import('./reporte-cobranza/reporte-cobranza.component').then(
        (m) => m.ReporteCobranzaComponent,
      ),
    title: 'Reporte Cobranza - SOBOCE',
  },
  {
    path: 'conciliacion',
    loadComponent: () =>
      import('./conciliacion/conciliacion.component').then(
        (m) => m.ConciliacionComponent,
      ),
    title: 'Conciliación - SOBOCE',
  },
];
