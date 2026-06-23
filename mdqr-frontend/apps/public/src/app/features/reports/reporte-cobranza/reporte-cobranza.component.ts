import { CommonModule } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import * as XLSX from 'xlsx';
import {
  AlertComponent,
  ButtonComponent,
  DateMasckPickerComponent,
  InputFieldComponent,
  LabelComponent,
  Option,
  PageBreadcrumbComponent,
  SelectComponent,
  DataTableComponent, DataTableColumn, } from '@mdqr/ui';
import { SobocApi } from '@mdqr/sdk';

interface ParsedLines {
  headers: string[];
  rows: string[][];
}

@Component({
  selector: 'app-reporte-cobranza',
  standalone: true,
  imports: [
    CommonModule,
    PageBreadcrumbComponent,
    AlertComponent,
    ButtonComponent,
    DataTableComponent, SelectComponent,
    InputFieldComponent,
    LabelComponent,
    DateMasckPickerComponent,
  ],
  templateUrl: './reporte-cobranza.component.html',
})
export class ReporteCobranzaComponent {
  columns: DataTableColumn[] = [
    { key: 'id', label: 'ID', sortable: true },
    { key: 'nombre', label: 'Nombre', sortable: true },
    { key: 'estado', label: 'Estado' },
  ];
  
  private readonly soboce = inject(SobocApi);

  fecha = signal('');
  sociedad = signal('');
  canal = signal('T');
  servicio = signal('1');
  buscado = signal(false);
  loading = signal(false);
  error = signal<string | null>(null);

  headers = signal<string[]>([]);
  resultados = signal<string[][]>([]);

  readonly canalesOptions: Option[] = [
    { value: 'T', label: 'TODOS' },
    { value: 'F', label: 'FISICO' },
    { value: 'D', label: 'DIGITAL' },
  ];

  readonly iconSearch = `<svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"/></svg>`;
  readonly iconDownload = `<svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"/></svg>`;
  readonly iconPdf = `<svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 21h10a2 2 0 002-2V9.414a1 1 0 00-.293-.707l-5.414-5.414A1 1 0 0012.586 3H7a2 2 0 00-2 2v14a2 2 0 002 2z"/></svg>`;

  readonly importeIndex = computed(() => this.headers().findIndex((h) => /importe/i.test(h)));
  readonly hayResultados = computed(() => this.resultados().length > 0);
  readonly total = computed(() => {
    const idx = this.importeIndex();
    if (idx < 0) return 0;
    return this.resultados().reduce(
      (s, row) => s + (parseFloat((row[idx] ?? '0').replace(/,/g, '')) || 0),
      0,
    );
  });

  isImporteCol(colIdx: number): boolean {
    return colIdx === this.importeIndex();
  }

  parseImporte(val: string): number {
    return parseFloat(val.replace(/,/g, '')) || 0;
  }

  onFechaChange(event: { dateStr: string }): void {
    if (!event.dateStr) return;
    const [day, month, year] = event.dateStr.split('/');
    if (day && month && year) {
      this.fecha.set(`${year}-${month}-${day}`);
    }
  }

  private parseLines(lines: string[]): ParsedLines {
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

  onBuscar(): void {
    const fecha = this.fecha();
    if (!fecha) return;

    this.loading.set(true);
    this.error.set(null);
    this.buscado.set(true);

    this.soboce
      .getCollectionReport({ date: fecha, type: this.canal(), company: 'SOB' })
      .subscribe({
        next: (res) => {
          const { headers, rows } = this.parseLines(res.lines);
          this.headers.set(headers);
          this.resultados.set(rows);
          this.loading.set(false);
        },
        error: (err) => {
          this.error.set(err?.error?.detail ?? 'Error al consultar el reporte de cobranzas.');
          this.headers.set([]);
          this.resultados.set([]);
          this.loading.set(false);
        },
      });
  }

  private buscarParaExportar(
    company: string,
    onSuccess: (headers: string[], rows: string[][]) => void,
  ): void {
    const fecha = this.fecha();
    if (!fecha) return;
    this.soboce.getCollectionReport({ date: fecha, type: this.canal(), company }).subscribe({
      next: (res) => {
        const { headers, rows } = this.parseLines(res.lines);
        onSuccess(headers, rows);
      },
      error: (err) => this.error.set(err?.error?.detail ?? 'Error al generar el reporte.'),
    });
  }

  private toSheetData(headers: string[], rows: string[][]): Record<string, unknown>[] {
    return rows.map((row, i) => {
      const obj: Record<string, unknown> = { 'N°': i + 1 };
      headers.forEach((h, j) => {
        obj[h] = row[j] ?? '';
      });
      return obj;
    });
  }

  private descargarExcel(headers: string[], rows: string[][], filename: string): void {
    const ws = XLSX.utils.json_to_sheet(this.toSheetData(headers, rows));
    const wb = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(wb, ws, 'Cobranzas');
    XLSX.writeFile(wb, `${filename}-${this.fecha()}.xlsx`);
  }

  private descargarCsv(headers: string[], rows: string[][], filename: string): void {
    const ws = XLSX.utils.json_to_sheet(this.toSheetData(headers, rows));
    const wb = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(wb, ws, 'Cobranzas');
    XLSX.writeFile(wb, `${filename}-${this.fecha()}.csv`, { bookType: 'csv' });
  }

  onGenerarExcelSoboce(): void {
    this.buscarParaExportar('SOB', (h, r) => this.descargarExcel(h, r, 'cobranza-soboce'));
  }

  onGenerarCsvSoboce(): void {
    this.buscarParaExportar('SOB', (h, r) => this.descargarCsv(h, r, 'cobranza-soboce'));
  }

  onGenerarComprobantePdf(): void {
    const fecha = this.fecha();
    if (!fecha || !this.servicio()) return;
    this.soboce.getComprobantePdf({ code: this.sociedad(), from: fecha, to: fecha }).subscribe({
      next: (res) => this.openPdf(res.pdf),
      error: (err) =>
        this.error.set(err?.error?.detail ?? 'Error al generar el comprobante PDF.'),
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

  onGenerarExcelSintesis(): void {
    this.buscarParaExportar('SIN', (h, r) => this.descargarExcel(h, r, 'cobranza-sintesis'));
  }
}
