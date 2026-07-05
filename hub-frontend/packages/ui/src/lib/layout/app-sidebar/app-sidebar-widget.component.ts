import { Component } from '@angular/core';

@Component({
  selector: 'app-sidebar-widget',
  template: `
    <div
      class="mx-auto mb-10 w-full max-w-60 rounded-2xl bg-gray-50 px-4 py-5 text-center dark:bg-white/[0.03]"
    >
      <p class="text-xs font-semibold text-gray-500 dark:text-gray-400">
        v1.0.3
      </p>
      <p class="mt-1 text-xs text-gray-400 dark:text-gray-500">
        &copy; {{ year }} Síntesis Bolivia
      </p>
      <p class="mt-1 text-xs text-gray-400 dark:text-gray-500">
        Todos los derechos reservados
      </p>
    </div>
  `
})
export class SidebarWidgetComponent {
  readonly year = new Date().getFullYear();
} 