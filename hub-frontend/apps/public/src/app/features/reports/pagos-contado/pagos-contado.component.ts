import { CommonModule } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import * as XLSX from 'xlsx';
import {
  AlertComponent,
  ButtonComponent,
  DateMasckPickerComponent,
  InputFieldComponent,
  LabelComponent,
  PageBreadcrumbComponent,
  DataTableComponent, DataTableColumn, } from '@hub/ui';
import { SobocApi } from '@hub/sdk';

interface PagoContadoRow {
  n: number;
  codigo: string;
  nroRecibo: string;
  fecha: string;
  hora: string;
  importe: number;
  estado: string;
  movKey: string;
  sequential: number;
}

@Component({
  selector: 'app-pagos-contado',
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
  templateUrl: './pagos-contado.component.html',
})
export class PagosContadoComponent {
  columns: DataTableColumn[] = [
    { key: 'id', label: 'ID', sortable: true },
    { key: 'nombre', label: 'Nombre', sortable: true },
    { key: 'estado', label: 'Estado' },
  ];
  
  private readonly soboce = inject(SobocApi);

  codigo = signal('');
  fechaDesde = signal('');
  fechaHasta = signal('');
  servicio = signal('2');
  buscado = signal(false);
  loading = signal(false);
  error = signal<string | null>(null);

  readonly iconSearch = `<svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"/></svg>`;
  readonly iconDownload = `<svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"/></svg>`;
  readonly iconRecibo = `<svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 21h10a2 2 0 002-2V9.414a1 1 0 00-.293-.707l-5.414-5.414A1 1 0 0012.586 3H7a2 2 0 00-2 2v14a2 2 0 002 2z"/></svg>`;

  resultados = signal<PagoContadoRow[]>([]);
  hayResultados = computed(() => this.resultados().length > 0);
  total = computed(() => this.resultados().reduce((s, r) => s + r.importe, 0));

  badgeColor(estado: string): 'success' | 'warning' | 'error' {
    const e = (estado ?? '').toUpperCase();
    if (e === 'PAGADO') return 'success';
    if (e === 'PENDIENTE') return 'warning';
    return 'error';
  }

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
    const codigo = this.codigo();
    const from = this.fechaDesde();
    const to = this.fechaHasta();
    if (!codigo || !from || !to) return;

    this.loading.set(true);
    this.error.set(null);
    this.buscado.set(true);

    this.soboce.getCashPayments({ code: codigo, from, to }).subscribe({
      next: (res) => {
        this.resultados.set(
          res.payments.map((p, i) => ({
            n: i + 1,
            codigo: codigo,
            nroRecibo: p.paymentNumber,
            fecha: p.date,
            hora: p.time,
            importe: p.amount,
            estado: p.status ?? '',
            movKey: p.movKey ?? '',
            sequential: p.sequential,
          })),
        );
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err?.error?.detail ?? 'Error al consultar los pagos.');
        this.resultados.set([]);
        this.loading.set(false);
      },
    });
  }

  onDescargarExcel(): void {
    const from = this.fechaDesde();
    const codigo = this.codigo();
    if (!from || !codigo) return;
    this.soboce.getAdvancePaymentsReport({ date: from, type: codigo }).subscribe({
      next: (res) => {
        if (!res.lines.length) {
          this.error.set('No hay datos para exportar.');
          return;
        }
        const headers = res.lines[0]
          .split('|')
          .map((h) => h.trim())
          .filter((h) => h !== '');
        const sheetData = res.lines.slice(1).map((line, i) => {
          const parts = line.split('|').map((v) => v.trim()).slice(0, headers.length);
          const obj: Record<string, unknown> = { 'N°': i + 1 };
          headers.forEach((h, j) => {
            obj[h] = parts[j] ?? '';
          });
          return obj;
        });
        const ws = XLSX.utils.json_to_sheet(sheetData);
        const wb = XLSX.utils.book_new();
        XLSX.utils.book_append_sheet(wb, ws, 'Pagos Contado');
        XLSX.writeFile(wb, `pagos-contado-${codigo}-${from}.xlsx`);
      },
      error: (err) => this.error.set(err?.error?.detail ?? 'Error al generar el Excel.'),
    });
  }

  onDescargarRecibos(): void {
    const codigo = this.codigo();
    const from = this.fechaDesde();
    const to = this.fechaHasta();
    if (!codigo || !from || !to) return;
    this.soboce.getComprobantePdf({ code: codigo, from, to }).subscribe({
      next: (res) => this.openPdf(res.pdf),
      error: (err) =>
        this.error.set(err?.error?.detail ?? 'Error al generar los recibos PDF.'),
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
