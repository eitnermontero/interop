import { CommonModule } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import {
  AlertComponent,
  ButtonComponent,
  CardComponent,
  InputFieldComponent,
  LabelComponent,
  PageBreadcrumbComponent,
  SpinnerComponent,
  DataTableComponent, DataTableColumn, } from '@mdqr/ui';
import { SobocApi } from '@mdqr/sdk';

interface PendienteRow {
  n: number;
  nroFactura: string;
  fechaFactura: string;
  fechaVencimiento: string;
  nombre: string;
  nit: string;
  llave: string;
  monto: number;
}

@Component({
  selector: 'app-consulta-pendientes',
  standalone: true,
  imports: [
    CommonModule,
    PageBreadcrumbComponent,
    AlertComponent,
    ButtonComponent,
    CardComponent,
    InputFieldComponent,
    LabelComponent,
    SpinnerComponent,
    DataTableComponent, ],
  templateUrl: './consulta-pendientes.component.html',
})
export class ConsultaPendientesComponent {
  columns: DataTableColumn[] = [
    { key: 'id', label: 'ID', sortable: true },
    { key: 'nombre', label: 'Nombre', sortable: true },
    { key: 'estado', label: 'Estado' },
  ];
  
  private readonly soboce = inject(SobocApi);

  codigoCliente = signal('');
  buscado = signal(false);
  loading = signal(false);
  error = signal<string | null>(null);

  readonly iconSearch = `<svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"/></svg>`;

  resultados = signal<PendienteRow[]>([]);
  hayResultados = computed(() => this.resultados().length > 0);
  total = computed(() => this.resultados().reduce((s, r) => s + r.monto, 0));

  private parseDescripcion(desc: string): Record<string, string> {
    const result: Record<string, string> = {};
    desc.split('; ').forEach((part) => {
      const idx = part.indexOf(':');
      if (idx > -1) result[part.slice(0, idx).trim()] = part.slice(idx + 1).trim();
    });
    return result;
  }

  onBuscar(): void {
    const codigo = this.codigoCliente();
    if (!codigo) return;

    this.loading.set(true);
    this.error.set(null);
    this.buscado.set(true);

    this.soboce.getDebts({ code: codigo }).subscribe({
      next: (res) => {
        this.resultados.set(
          res.debts.map((d, i) => {
            const p = this.parseDescripcion(d.descripcion);
            return {
              n: i + 1,
              nroFactura: p['Nro. Factura'] ?? '',
              fechaFactura: p['Fecha Factura'] ?? '',
              fechaVencimiento: p['Fecha Vencimiento'] ?? '',
              nombre: p['Nombre'] ?? '',
              nit: p['NIT'] ?? '',
              llave: d.llave,
              monto: d.monto,
            };
          }),
        );
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err?.error?.detail ?? 'Error al consultar los pendientes.');
        this.resultados.set([]);
        this.loading.set(false);
      },
    });
  }
}
