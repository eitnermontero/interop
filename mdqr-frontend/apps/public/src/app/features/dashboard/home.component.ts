import { Component, signal } from '@angular/core';
import { PageBreadcrumbComponent, BadgeComponent } from '@mdqr/ui';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

interface ReportCard {
  title: string;
  description: string;
  icon: string;
  path: string;
  color: string;
}

@Component({
  selector: 'app-home',
  standalone: true,
  imports: [CommonModule, RouterModule, PageBreadcrumbComponent, BadgeComponent, TranslateModule],
  template: `
    <app-page-breadcrumb [pageTitle]="'DASHBOARD.TITLE' | translate" />

    <!-- Welcome Section -->
    <div class="mb-8 rounded-2xl border border-gray-200 bg-white p-8 dark:border-gray-800 dark:bg-white/[0.03]">
      <h2 class="text-2xl font-bold text-gray-900 dark:text-white">{{ 'DASHBOARD.SUBTITLE' | translate }}</h2>
      <p class="mt-2 text-gray-600 dark:text-gray-400">{{ 'DASHBOARD.SUBTITLE' | translate }}</p>
    </div>

    <!-- Summary Cards -->
    <div class="mb-8 grid grid-cols-1 gap-4 md:grid-cols-3">
      <div class="rounded-2xl border border-gray-200 bg-white p-6 dark:border-gray-800 dark:bg-white/[0.03]">
        <div class="flex items-center gap-2 mb-2">
          <p class="text-sm font-medium text-gray-600 dark:text-gray-400">{{ 'DASHBOARD.PENDING_DEBTS' | translate }}</p>
          <app-badge color="warning" class="text-xs">{{ 'COMMON.WARNING' | translate }}</app-badge>
        </div>
        <p class="mt-2 text-3xl font-bold text-orange-600 dark:text-orange-400">12</p>
        <p class="mt-1 text-xs text-gray-500 dark:text-gray-400">{{ 'DASHBOARD.PENDING_DEBTS' | translate }}</p>
        <p class="mt-4 text-xs text-gray-400 dark:text-gray-500">{{ 'COMMON.LOADING' | translate }}</p>
      </div>
      <div class="rounded-2xl border border-gray-200 bg-white p-6 dark:border-gray-800 dark:bg-white/[0.03]">
        <div class="flex items-center gap-2 mb-2">
          <p class="text-sm font-medium text-gray-600 dark:text-gray-400">{{ 'DASHBOARD.RECENT_PAYMENTS' | translate }}</p>
          <app-badge color="success" class="text-xs">{{ 'COMMON.SUCCESS' | translate }}</app-badge>
        </div>
        <p class="mt-2 text-3xl font-bold text-green-600 dark:text-green-400">8</p>
        <p class="mt-1 text-xs text-gray-500 dark:text-gray-400">{{ 'DASHBOARD.RECENT_PAYMENTS' | translate }}</p>
        <p class="mt-4 text-xs text-gray-400 dark:text-gray-500">{{ 'COMMON.LOADING' | translate }}</p>
      </div>
      <div class="rounded-2xl border border-gray-200 bg-white p-6 dark:border-gray-800 dark:bg-white/[0.03]">
        <div class="flex items-center gap-2 mb-2">
          <p class="text-sm font-medium text-gray-600 dark:text-gray-400">{{ 'DASHBOARD.AVAILABLE_RECEIPTS' | translate }}</p>
          <app-badge color="info" class="text-xs">{{ 'COMMON.INFO' | translate }}</app-badge>
        </div>
        <p class="mt-2 text-3xl font-bold text-blue-600 dark:text-blue-400">24</p>
        <p class="mt-1 text-xs text-gray-500 dark:text-gray-400">{{ 'DASHBOARD.AVAILABLE_RECEIPTS' | translate }}</p>
        <p class="mt-4 text-xs text-gray-400 dark:text-gray-500">{{ 'COMMON.LOADING' | translate }}</p>
      </div>
    </div>

    <!-- Report Navigation -->
    <div>
      <h3 class="mb-4 text-lg font-semibold text-gray-900 dark:text-white">{{ 'REPORTS.TITLE' | translate }}</h3>
      <div class="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3">
        <a
          *ngFor="let report of reports()"
          [routerLink]="report.path"
          class="group rounded-2xl border border-gray-200 bg-white p-6 transition-all hover:border-gray-300 hover:shadow-lg dark:border-gray-800 dark:bg-white/[0.03] dark:hover:border-gray-700"
        >
          <div class="flex items-start justify-between">
            <div>
              <p class="text-sm text-gray-600 dark:text-gray-400">{{ report.description | translate }}</p>
              <h4 class="mt-2 text-lg font-semibold text-gray-900 group-hover:text-blue-600 dark:text-white dark:group-hover:text-blue-400">
                {{ report.title | translate }}
              </h4>
            </div>
            <span class="text-3xl">{{ report.icon }}</span>
          </div>
          <div class="mt-4 flex items-center text-sm font-medium text-blue-600 dark:text-blue-400">
            {{ 'COMMON.INFO' | translate }} <span class="ml-1 transition-transform group-hover:translate-x-1">→</span>
          </div>
        </a>
      </div>
    </div>
  `,
})
export class HomeComponent {
  reports = signal<ReportCard[]>([
    {
      title: 'REPORTS.COLLECTION_TITLE',
      description: 'SIDEBAR.COLLECTION',
      icon: '📊',
      path: '/reportes/cobranza',
      color: 'blue',
    },
    {
      title: 'REPORTS.PAYMENTS_TITLE',
      description: 'SIDEBAR.PAYMENTS',
      icon: '💰',
      path: '/reportes/pagos',
      color: 'green',
    },
    {
      title: 'REPORTS.PENDING_TITLE',
      description: 'SIDEBAR.PENDING',
      icon: '⚠️',
      path: '/reportes/pendientes',
      color: 'orange',
    },
    {
      title: 'REPORTS.RECEIPTS_TITLE',
      description: 'SIDEBAR.RECEIPTS',
      icon: '📄',
      path: '/reportes/comprobantes',
      color: 'purple',
    },
    {
      title: 'REPORTS.RECONCILIATION_TITLE',
      description: 'SIDEBAR.RECONCILIATION',
      icon: '✓',
      path: '/reportes/conciliacion',
      color: 'indigo',
    },
  ]);
}
