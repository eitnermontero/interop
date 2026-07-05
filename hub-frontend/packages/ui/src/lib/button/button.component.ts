import { CommonModule } from '@angular/common';
import { Component, Input, Output, EventEmitter } from '@angular/core';
import { SafeHtmlPipe } from '../pipes/safe-html.pipe';

@Component({
  selector: 'app-button',
  imports: [
    CommonModule,
    SafeHtmlPipe,
  ],
  templateUrl: './button.component.html',
  styles: ``,
  host: {

  },
})
export class ButtonComponent {

  @Input() size: 'sm' | 'md' = 'md';
  @Input() variant: 'primary' | 'outline' | 'success' | 'danger' | 'info' = 'primary';
  @Input() disabled = false;
  @Input() className = '';
  @Input() startIcon?: string;
  @Input() endIcon?: string;

  @Output() btnClick = new EventEmitter<Event>();

  get sizeClasses(): string {
    return this.size === 'sm'
      ? 'px-4 py-3 text-sm'
      : 'px-5 py-3.5 text-sm';
  }

  get variantClasses(): string {
    const classes: Record<string, string> = {
      primary: 'bg-brand-500 text-white shadow-theme-xs hover:bg-brand-600 disabled:bg-brand-300',
      outline: 'bg-white text-gray-700 ring-1 ring-inset ring-gray-300 hover:bg-gray-50 dark:bg-gray-800 dark:text-gray-400 dark:ring-gray-700 dark:hover:bg-white/[0.03] dark:hover:text-gray-300',
      success: 'bg-success-500 text-white hover:bg-success-600 disabled:opacity-50',
      danger:  'bg-error-500 text-white hover:bg-error-600 disabled:opacity-50',
      info:    'bg-blue-500 text-white hover:bg-blue-600 disabled:opacity-50',
    };
    return classes[this.variant] ?? classes['primary'];
  }

  get disabledClasses(): string {
    return this.disabled ? 'cursor-not-allowed opacity-50' : '';
  }

  onClick(event: Event) {
    if (!this.disabled) {
      this.btnClick.emit(event);
    }
  }
}
