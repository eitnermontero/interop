import { CommonModule } from '@angular/common';
import { Component, Input } from '@angular/core';

@Component({
  selector: 'app-spinner',
  standalone: true,
  imports: [CommonModule],
  template: `<span
    role="status"
    [ngClass]="spinnerClasses"
    aria-label="Cargando..."
    [attr.aria-hidden]="ariaHidden"
  ></span>`,
  styles: `
    :host ::ng-deep {
      @media (prefers-reduced-motion: reduce) {
        span {
          animation: none !important;
          border-top-color: currentColor;
        }
      }
    }
  `,
})
export class SpinnerComponent {
  @Input() size: 'sm' | 'md' | 'lg' = 'md';
  @Input() color: 'brand' | 'gray' | 'white' = 'brand';
  @Input() ariaHidden: boolean = false;

  get spinnerClasses(): string {
    const sizeClasses = {
      sm: 'w-4 h-4',
      md: 'w-6 h-6',
      lg: 'w-10 h-10',
    };

    const colorClasses = {
      brand: 'border-brand-200 border-t-brand-500 dark:border-brand-800 dark:border-t-brand-400',
      gray: 'border-gray-200 border-t-gray-500 dark:border-gray-800 dark:border-t-gray-400',
      white: 'border-white/20 border-t-white dark:border-white/10 dark:border-t-white/80',
    };

    return `inline-block ${sizeClasses[this.size]} rounded-full border-2 animate-spin ${colorClasses[this.color]}`;
  }
}
