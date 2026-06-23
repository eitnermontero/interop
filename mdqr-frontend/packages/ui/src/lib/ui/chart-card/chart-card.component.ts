import { Component, input } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-chart-card',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="rounded-2xl border border-gray-200 bg-white p-6 dark:border-gray-800 dark:bg-white/[0.03]">
      <div *ngIf="title()" class="mb-6">
        <h3 class="text-lg font-semibold text-gray-900 dark:text-white">{{ title() }}</h3>
        <p *ngIf="subtitle()" class="text-sm text-gray-600 dark:text-gray-400">{{ subtitle() }}</p>
      </div>
      <div class="flex items-center justify-center rounded-lg bg-gray-50 py-12 dark:bg-gray-900">
        <p class="text-sm text-gray-500 dark:text-gray-400">Charts temporarily disabled</p>
      </div>
    </div>
  `,
})
export class ChartCardComponent {
  title = input<string | undefined>(undefined);
  subtitle = input<string | undefined>(undefined);
  type = input<'line' | 'bar' | 'area' | 'donut' | 'radialBar' | 'pie'>('area');
  series = input<any[]>([{ name: 'Data', data: [] }]);
  categories = input<string[]>([]);
  height = input<number | string>(300);
  colors = input<string[]>(['#465fff']);

  chart = () => ({
    type: this.type(),
    toolbar: { show: true },
    zoom: { enabled: true },
    animations: { enabled: true, speed: 800 },
    height: this.height(),
  });

  xaxis = () => ({
    categories: this.categories(),
  });
}
