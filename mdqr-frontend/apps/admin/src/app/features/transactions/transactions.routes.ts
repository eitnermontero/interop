import { Routes } from '@angular/router';

export const TRANSACTIONS_ROUTES: Routes = [
  {
    path: 'reporte-cobranza',
    loadComponent: () =>
      import('./reporte-cobranza/reporte-cobranza.component').then(
        (m) => m.ReporteCobranzaComponent,
      ),
    title: 'Reporte Cobranza - SOBOCE',
  },
  {
    path: 'pagos-contado',
    loadComponent: () =>
      import('./pagos-contado/pagos-contado.component').then(
        (m) => m.PagosContadoComponent,
      ),
    title: 'Pagos al Contado - SOBOCE',
  },
  {
    path: 'consulta-pendientes',
    loadComponent: () =>
      import('./consulta-pendientes/consulta-pendientes.component').then(
        (m) => m.ConsultaPendientesComponent,
      ),
    title: 'Consulta Pendientes - SOBOCE',
  },
  {
    path: 'reimpresion-comprobantes',
    loadComponent: () =>
      import('./reimpresion-comprobantes/reimpresion-comprobantes.component').then(
        (m) => m.ReimpresionComprobantesComponent,
      ),
    title: 'Reimpresión Comprobantes - SOBOCE',
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
