import { Component, signal } from '@angular/core';
import { PageBreadcrumbComponent, BadgeComponent } from '@hub/ui';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

interface KPI {
  label: string;
  value: number;
  icon: string;
  color: string;
  trend?: { label: string; value: number; isPositive: boolean };
  status?: 'success' | 'warning' | 'error';
  statusLabel?: string;
  route?: string;
}

@Component({
  selector: 'app-home',
  standalone: true,
  imports: [CommonModule, RouterModule, PageBreadcrumbComponent, BadgeComponent, TranslateModule],
  template: `
    <app-page-breadcrumb [pageTitle]="'DASHBOARD.TITLE' | translate" />

    <!-- KPI Cards -->
    <div class="mb-8 grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-4">
      <div
        *ngFor="let kpi of kpis()"
        [routerLink]="kpi.route"
        class="rounded-2xl border border-gray-200 bg-white p-6 dark:border-gray-800 dark:bg-white/[0.03]"
        [ngClass]="{'cursor-pointer transition-all hover:shadow-lg hover:border-gray-300 dark:hover:border-gray-700': kpi.route}"
      >
        <div class="flex items-start justify-between">
          <div class="flex-1">
            <div class="flex items-center gap-2">
              <p class="text-sm font-medium text-gray-600 dark:text-gray-400">{{ kpi.label | translate }}</p>
              <app-badge
                *ngIf="kpi.status && kpi.statusLabel"
                [color]="kpi.status"
                class="text-xs"
              >
                {{ kpi.statusLabel | translate }}
              </app-badge>
            </div>
            <p class="mt-2 text-3xl font-bold text-gray-900 dark:text-white">{{ kpi.value.toLocaleString() }}</p>
            <div class="mt-4 space-y-2">
              <div *ngIf="kpi.trend" class="flex items-center gap-2">
                <span class="text-xs font-medium" [ngClass]="kpi.trend.isPositive ? 'text-green-600' : 'text-red-600'">
                  {{ kpi.trend.isPositive ? '↑' : '↓' }} {{ kpi.trend.value }}%
                </span>
                <span class="text-xs text-gray-500 dark:text-gray-400">{{ kpi.trend.label | translate }}</span>
              </div>
              <p class="text-xs text-gray-400 dark:text-gray-500">{{ 'COMMON.LOADING' | translate }}</p>
            </div>
          </div>
          <div [ngClass]="'rounded-lg p-3 ' + kpi.color">
            <span class="text-2xl">{{ kpi.icon }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- Quick Actions -->
    <div class="rounded-2xl border border-gray-200 bg-white p-6 dark:border-gray-800 dark:bg-white/[0.03]">
        <h3 class="mb-4 text-lg font-semibold text-gray-900 dark:text-white">{{ 'DASHBOARD.QUICK_ACCESS' | translate }}</h3>
        <nav class="space-y-3">
          <a
            routerLink="/admin/usuarios"
            class="group flex items-center justify-between rounded-lg border border-transparent px-4 py-3 text-sm font-medium text-gray-700 transition-all hover:border-gray-300 hover:bg-gray-50 dark:text-gray-300 dark:hover:border-gray-600 dark:hover:bg-gray-800/50"
          >
            <span>👥 {{ 'ADMIN.MANAGE_USERS' | translate }}</span>
            <span class="text-gray-400 transition-transform group-hover:translate-x-1">→</span>
          </a>
          <a
            routerLink="/admin/roles"
            class="group flex items-center justify-between rounded-lg border border-transparent px-4 py-3 text-sm font-medium text-gray-700 transition-all hover:border-gray-300 hover:bg-gray-50 dark:text-gray-300 dark:hover:border-gray-600 dark:hover:bg-gray-800/50"
          >
            <span>🔐 {{ 'ADMIN.MANAGE_ROLES' | translate }}</span>
            <span class="text-gray-400 transition-transform group-hover:translate-x-1">→</span>
          </a>
          <a
            routerLink="/admin/permisos"
            class="group flex items-center justify-between rounded-lg border border-transparent px-4 py-3 text-sm font-medium text-gray-700 transition-all hover:border-gray-300 hover:bg-gray-50 dark:text-gray-300 dark:hover:border-gray-600 dark:hover:bg-gray-800/50"
          >
            <span>🛡️ {{ 'ADMIN.MANAGE_PERMISSIONS' | translate }}</span>
            <span class="text-gray-400 transition-transform group-hover:translate-x-1">→</span>
          </a>
          <a
            routerLink="/admin/auditoria"
            class="group flex items-center justify-between rounded-lg border border-transparent px-4 py-3 text-sm font-medium text-gray-700 transition-all hover:border-gray-300 hover:bg-gray-50 dark:text-gray-300 dark:hover:border-gray-600 dark:hover:bg-gray-800/50"
          >
            <span>📋 {{ 'DASHBOARD.AUDIT_LOGS' | translate }}</span>
            <span class="text-gray-400 transition-transform group-hover:translate-x-1">→</span>
          </a>
        </nav>
      </div>
  `,
})
export class HomeComponent {
  kpis = signal<KPI[]>([
    {
      label: 'DASHBOARD.ACTIVE_USERS',
      value: 24,
      icon: '👥',
      color: 'bg-blue-100 dark:bg-blue-900/30',
      trend: { label: 'COMMON.VS_LAST_WEEK', value: 12, isPositive: true },
      status: 'success',
      statusLabel: 'COMMON.HEALTHY',
      route: '/admin/usuarios',
    },
    {
      label: 'DASHBOARD.TOTAL_ROLES',
      value: 8,
      icon: '🔐',
      color: 'bg-purple-100 dark:bg-purple-900/30',
      trend: { label: 'COMMON.VS_LAST_WEEK', value: 0, isPositive: true },
      status: 'warning',
      statusLabel: 'COMMON.NO_CHANGE',
      route: '/admin/roles',
    },
    {
      label: 'DASHBOARD.REPORTS_GENERATED',
      value: 156,
      icon: '📊',
      color: 'bg-green-100 dark:bg-green-900/30',
      trend: { label: 'COMMON.VS_LAST_WEEK', value: 24, isPositive: true },
      status: 'success',
      statusLabel: 'COMMON.ON_TRACK',
      route: '/admin/reportes',
    },
    {
      label: 'DASHBOARD.TODAYS_AUDITS',
      value: 42,
      icon: '📋',
      color: 'bg-orange-100 dark:bg-orange-900/30',
      trend: { label: 'COMMON.VS_YESTERDAY', value: 8, isPositive: true },
      status: 'success',
      statusLabel: 'COMMON.ACTIVE',
      route: '/admin/auditoria',
    },
  ]);
}
