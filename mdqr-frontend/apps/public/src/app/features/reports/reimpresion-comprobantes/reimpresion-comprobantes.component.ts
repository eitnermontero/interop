import { CommonModule } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import {
  AlertComponent,
  ButtonComponent,
  DateMasckPickerComponent,
  InputFieldComponent,
  LabelComponent,
  PageBreadcrumbComponent,
  DataTableComponent, DataTableColumn, } from '@mdqr/ui';
import { SobocApi } from '@mdqr/sdk';

interface ComprobanteRow {
  n: number;
  codigo: string;
  nombre: string;
  nroComprobante: string;
  fecha: string;
  importe: number;
  movKey: string;
  sequential: number;
}

@Component({
  selector: 'app-reimpresion-comprobantes',
  standalone: true,
  imports: [
    CommonModule,
    PageBreadcrumbComponent,
    AlertComponent,
    ButtonComponent,
    DataTableComponent, InputFieldComponent,
    LabelComponent,
    DateMasckPickerComponent,
  ],
  templateUrl: './reimpresion-comprobantes.component.html',
})
export class ReimpresionComprobantesComponent {
  columns: DataTableColumn[] = [
    { key: 'id', label: 'ID', sortable: true },
    { key: 'nombre', label: 'Nombre', sortable: true },
    { key: 'estado', label: 'Estado' },
  ];
  
  private readonly soboce = inject(SobocApi);

  codigoCliente = signal('');
  servicio = signal('2');
  fechaDesde = signal('');
  fechaHasta = signal('');
  buscado = signal(false);
  loading = signal(false);
  error = signal<string | null>(null);

  readonly iconSearch = `<svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"/></svg>`;
  readonly iconPrint = `<svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 17h2a2 2 0 002-2v-4a2 2 0 00-2-2H5a2 2 0 00-2 2v4a2 2 0 002 2h2m2 4h6a2 2 0 002-2v-4a2 2 0 00-2-2H9a2 2 0 00-2 2v4a2 2 0 002 2zm8-12V5a2 2 0 00-2-2H9a2 2 0 00-2 2v4h10z"/></svg>`;

  resultados = signal<ComprobanteRow[]>([]);
  hayResultados = computed(() => this.resultados().length > 0);

  private setDate(setter: (v: string) => void, dateStr: string): void {
    if (!dateStr) return;
    const [day, month, year] = dateStr.split('/');
    if (day && month && year) setter(`${year}-${month}-${day}`);
  }

  onFechaDesdeChange(event: { dateStr: string }): void {
    this.setDate((v) => this.fechaDesde.set(v), event.dateStr);
  }

  onFechaHastaChange(event: { dateStr: string }): void {
    this.setDate((v) => this.fechaHasta.set(v), event.dateStr);
  }

  onBuscar(): void {
    const codigo = this.codigoCliente();
    const from = this.fechaDesde();
    const to = this.fechaHasta();
    if (!codigo || !from || !to) return;

    this.loading.set(true);
    this.error.set(null);
    this.buscado.set(true);

    this.soboce
      .getPayments({ code: codigo, from, to, service: Number(this.servicio()) })
      .subscribe({
        next: (res) => {
          this.resultados.set(
            res.payments.map((p, i) => ({
              n: i + 1,
              codigo: res.clientCode,
              nombre: res.billingName,
              nroComprobante: p.paymentNumber,
              fecha: p.date,
              importe: p.amount,
              movKey: p.movKey,
              sequential: p.sequential,
            })),
          );
          this.loading.set(false);
        },
        error: (err) => {
          this.error.set(err?.error?.detail ?? 'Error al consultar los comprobantes.');
          this.resultados.set([]);
          this.loading.set(false);
        },
      });
  }

  onReimprimir(row: ComprobanteRow): void {
    this.soboce
      .reprintPayments({ code: row.codigo, movement: row.movKey, sequential: row.sequential })
      .subscribe({
        next: (res) => res.pdfs.forEach((pdf) => this.openPdf(pdf)),
        error: (err) =>
          this.error.set(err?.error?.detail ?? 'Error al reimprimir el comprobante.'),
      });
  }

  private openPdf(base64: string): void {
    const binary = atob(base64);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    const blob = new Blob([bytes], { type: 'application/pdf' });
    const url = URL.createObjectURL(blob);
    window.open(url, '_blank');
    setTimeout(() => URL.revokeObjectURL(url), 30000);
  }
}
