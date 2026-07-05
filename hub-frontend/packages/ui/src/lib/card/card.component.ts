import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-card',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './card.component.html',
  styles: ``,
})
export class CardComponent {
  @Input() title: string = '';
  @Input() subtitle: string = '';
  @Input() padding: 'sm' | 'md' | 'lg' = 'md';
  @Input() headingLevel: 'h1' | 'h2' | 'h3' | 'h4' | 'h5' | 'h6' = 'h3';

  get paddingClasses(): string {
    const paddings = {
      sm: 'p-3',
      md: 'p-5',
      lg: 'p-6',
    };
    return paddings[this.padding];
  }

  get containerClasses(): string {
    return `rounded-2xl border border-gray-200 bg-white ${this.paddingClasses} dark:border-gray-800 dark:bg-white/5`;
  }
}
