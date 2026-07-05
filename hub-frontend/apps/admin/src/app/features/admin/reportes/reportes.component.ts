import { CommonModule } from '@angular/common';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import {
  AlertComponent,
  BadgeComponent,
  ButtonComponent,
  InputFieldComponent,
  LabelComponent,
  ModalComponent,
  Option,
  PageBreadcrumbComponent,
  SelectComponent,
  DataTableComponent, DataTableColumn, } from '@hub/ui';
import { ReportsApi, ReportResponse, ReportStatus, ReportType } from '@hub/sdk';

@Component({
  selector: 'app-reportes-admin',
  standalone: true,
  imports: [
    CommonModule,
    PageBreadcrumbComponent,
    AlertComponent,
    BadgeComponent,
    ButtonComponent,
    ModalComponent,
    SelectComponent,
    InputFieldComponent,
    LabelComponent,
    DataTableComponent, ],
  templateUrl: './reportes.component.html',
})
export class ReportesAdminComponent implements OnInit {
  columns: DataTableColumn[] = [
    { key: 'id', label: 'ID', sortable: true },
    { key: 'nombre', label: 'Nombre', sortable: true },
    { key: 'estado', label: 'Estado' },
  ];
  
  private readonly reportsApi = inject(ReportsApi);

  reports = signal<ReportResponse[]>([]);
  page = signal(0);
  size = signal(20);
  totalPages = signal(0);
  totalElements = signal(0);
  isLast = signal(true);

  loading = signal(false);
  error = signal<string | null>(null);

  // Nuevo reporte
  newOpen = signal(false);
  newTitle = signal('');
  newType = signal<ReportType>('TRANSACTIONS');
  newParameters = signal('');
  newSubmitting = signal(false);
  newError = signal<string | null>(null);

  // Detalle
  detailOpen = signal(false);
  detail = signal<ReportResponse | null>(null);

  readonly typeOptions: Option[] = [
    { value: 'TRANSACTIONS', label: 'TRANSACTIONS' },
    { value: 'AUDIT', label: 'AUDIT' },
    { value: 'SUMMARY', label: 'SUMMARY' },
    { value: 'CUSTOM', label: 'CUSTOM' },
  ];

  readonly iconPlus = `<svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4"/></svg>`;
  readonly iconEye = `<svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"/><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"/></svg>`;
  readonly iconRefresh = `<svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"/></svg>`;

  readonly hayResultados = computed(() => this.reports().length > 0);
  readonly pageDisplay = computed(() => this.page() + 1);

  ngOnInit(): void {
    this.loadReports();
  }

  loadReports(): void {
    this.loading.set(true);
    this.error.set(null);
    this.reportsApi.list({ page: this.page(), size: this.size() }).subscribe({
      next: (res) => {
        this.reports.set(res.content);
        this.totalPages.set(res.totalPages);
        this.totalElements.set(res.totalElements);
        this.isLast.set(res.last);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err?.error?.detail ?? 'Error al cargar los reportes.');
        this.reports.set([]);
        this.loading.set(false);
      },
    });
  }

  onPrev(): void {
    if (this.page() <= 0) return;
    this.page.set(this.page() - 1);
    this.loadReports();
  }

  onNext(): void {
    if (this.isLast()) return;
    this.page.set(this.page() + 1);
    this.loadReports();
  }

  badgeColor(status: ReportStatus): 'warning' | 'info' | 'success' | 'error' {
    switch (status) {
      case 'PENDING':
        return 'warning';
      case 'PROCESSING':
        return 'info';
      case 'COMPLETED':
        return 'success';
      case 'FAILED':
        return 'error';
    }
  }

  formatDate(iso: string | null): string {
    if (!iso) return '—';
    try {
      const d = new Date(iso);
      return d.toLocaleString('es-BO', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
      });
    } catch {
      return iso;
    }
  }

  // Nuevo reporte
  openNew(): void {
    this.newTitle.set('');
    this.newType.set('TRANSACTIONS');
    this.newParameters.set('');
    this.newError.set(null);
    this.newOpen.set(true);
  }

  closeNew(): void {
    this.newOpen.set(false);
  }

  submitNew(): void {
    const title = this.newTitle().trim();
    if (!title) {
      this.newError.set('El título es obligatorio.');
      return;
    }
    const params = this.newParameters().trim();
    if (params) {
      try {
        JSON.parse(params);
      } catch {
        this.newError.set('Los parámetros deben ser JSON válido.');
        return;
      }
    }

    this.newSubmitting.set(true);
    this.newError.set(null);
    this.reportsApi
      .create({
        title,
        type: this.newType(),
        parameters: params || undefined,
      })
      .subscribe({
        next: () => {
          this.newSubmitting.set(false);
          this.newOpen.set(false);
          this.page.set(0);
          this.loadReports();
        },
        error: (err) => {
          this.newSubmitting.set(false);
          this.newError.set(err?.error?.detail ?? 'Error al crear el reporte.');
        },
      });
  }

  // Detalle
  openDetail(r: ReportResponse): void {
    this.detail.set(r);
    this.detailOpen.set(true);
  }

  closeDetail(): void {
    this.detailOpen.set(false);
    this.detail.set(null);
  }
}
