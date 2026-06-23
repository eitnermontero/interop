import { CommonModule } from '@angular/common';
import { Component, effect, inject, signal } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';

@Component({
  selector: 'app-language-switcher',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="flex items-center gap-2 px-2 py-1 rounded-md border border-gray-200 dark:border-gray-700">
      <button
        [class.font-bold]="currentLanguage() === 'es'"
        [class.text-blue-600]="currentLanguage() === 'es'"
        [class.dark:text-blue-400]="currentLanguage() === 'es'"
        class="text-sm transition-colors hover:text-blue-600 dark:hover:text-blue-400"
        (click)="switchLanguage('es')"
        aria-label="Cambiar a español"
      >
        ES
      </button>
      <span class="text-gray-300 dark:text-gray-600">|</span>
      <button
        [class.font-bold]="currentLanguage() === 'en'"
        [class.text-blue-600]="currentLanguage() === 'en'"
        [class.dark:text-blue-400]="currentLanguage() === 'en'"
        class="text-sm transition-colors hover:text-blue-600 dark:hover:text-blue-400"
        (click)="switchLanguage('en')"
        aria-label="Change to English"
      >
        EN
      </button>
    </div>
  `,
})
export class LanguageSwitcherComponent {
  private translateService = inject(TranslateService);
  currentLanguage = signal<string>('es');

  constructor() {
    effect(() => {
      const lang = this.currentLanguage();
      this.translateService.use(lang);
      localStorage.setItem('language', lang);
    });
  }

  ngOnInit() {
    const saved = localStorage.getItem('language') || 'es';
    this.currentLanguage.set(saved);
    this.translateService.use(saved);
  }

  switchLanguage(lang: string) {
    this.currentLanguage.set(lang);
  }
}
