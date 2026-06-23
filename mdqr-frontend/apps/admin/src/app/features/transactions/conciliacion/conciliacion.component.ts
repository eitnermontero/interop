import { CommonModule } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import * as XLSX from 'xlsx';
import {
  AlertComponent,
  ButtonComponent,
  CardComponent,
  DateMasckPickerComponent,
  PageBreadcrumbComponent,
  SpinnerComponent,
  DataTableComponent, DataTableColumn, } from '@mdqr/ui';
import { SobocApi } from '@mdqr/sdk';

@Component({
  selector: 'app-conciliacion',
  standalone: true,
  imports: [
    CommonModule,
    PageBreadcrumbComponent,
    AlertComponent,
    ButtonComponent,
    CardComponent,
    DateMasckPickerComponent,
    SpinnerComponent],
  templateUrl: './conciliacion.component.html',
})
export class ConciliacionComponent {
  columns: DataTableColumn[] = [
    { key: 'id', label: 'ID', sortable: true },
    { key: 'nombre', label: 'Nombre', sortable: true },
    { key: 'estado', label: 'Estado' },
  ];
  
  private readonly soboce = inject(SobocApi);

  fechaDesde = signal('');
  fechaHasta = signal('');
  generado = signal(false);
  loading = signal(false);
  error = signal<string | null>(null);

  headers = signal<string[]>([]);
  resultados = signal<string[][]>([]);

  readonly iconDownload = `<svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"/></svg>`;

  montoIndex = computed(() => this.headers().findIndex((h) => /monto/i.test(h)));
  estadoIndex = computed(() => this.headers().findIndex((h) => /estado/i.test(h)));
  hayResultados = computed(() => this.resultados().length > 0);

  totalMonto = computed(() => {
    const idx = this.montoIndex();
    if (idx < 0) return 0;
    return this.resultados().reduce((s, r) => s + (parseFloat(r[idx]) || 0), 0);
  });

  totalPagado = computed(() => {
    const idx = this.estadoIndex();
    if (idx < 0) return 0;
    return this.resultados().filter((r) => r[idx]?.toUpperCase() === 'PAGADO').length;
  });

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

  private parseLines(lines: string[]): { headers: string[]; rows: string[][] } {
    if (!lines.length) return { headers: [], rows: [] };
    const headers = lines[0]
      .split('|')
      .map((h) => h.trim())
      .filter((h) => h !== '');
    const rows = lines.slice(1).map((line) =>
      line
        .split('|')
        .map((v) => v.trim())
        .slice(0, headers.length),
    );
    return { headers, rows };
  }

  onGenerarExcel(): void {
    const from = this.fechaDesde();
    const to = this.fechaHasta();
    if (!from || !to) return;

    this.loading.set(true);
    this.error.set(null);

    this.soboce.getReconciliationReport({ from, to }).subscribe({
      next: (res) => {
        const { headers, rows } = this.parseLines(res.lines ?? []);
        this.headers.set(headers);
        this.resultados.set(rows);
        this.generado.set(true);
        this.loading.set(false);

        if (rows.length) {
          const ws = XLSX.utils.aoa_to_sheet([headers, ...rows]);
          const wb = XLSX.utils.book_new();
          XLSX.utils.book_append_sheet(wb, ws, 'Conciliacion');
          XLSX.writeFile(wb, `conciliacion_${from}_${to}.xlsx`);
        }
      },
      error: (err) => {
        this.error.set(err?.error?.detail ?? 'Error al generar el reporte de conciliación.');
        this.headers.set([]);
        this.resultados.set([]);
        this.generado.set(false);
        this.loading.set(false);
      },
    });
  }
}
