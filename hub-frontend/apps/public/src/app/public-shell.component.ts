import { Component, computed, effect, inject, signal } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { AuthFacade } from '@hub/auth';
import { AuthMeApi, MenuNode } from '@hub/sdk';
import { AppLayoutComponent, NavItem, iconToSvg } from '@hub/ui';

@Component({
  selector: 'app-public-shell',
  standalone: true,
  imports: [AppLayoutComponent, RouterOutlet],
  template: `<app-layout [navItems]="navItems()" [loading]="loading()"
    ><router-outlet
  /></app-layout>`,
})
export class PublicShellComponent {
  private readonly authMe = inject(AuthMeApi);
  private readonly auth = inject(AuthFacade);

  private readonly menus = signal<MenuNode[]>([]);
  readonly loading = signal(false);

  readonly navItems = computed<NavItem[]>(() => this.menus().map(toNavItem));

  constructor() {
    effect(() => {
      if (this.auth.authenticated()) {
        this.loading.set(true);
        this.authMe.mePermissions().subscribe({
          next: (res) => {
            this.menus.set(res.menus);
            this.loading.set(false);
          },
          error: () => {
            this.menus.set([]);
            this.loading.set(false);
          },
        });
      } else {
        this.menus.set([]);
        this.loading.set(false);
      }
    });
  }
}

function toNavItem(node: MenuNode): NavItem {
  return {
    name: node.name,
    icon: iconToSvg(node.icon),
    path: node.route ?? undefined,
    subItems: node.children.map((child) => ({
      name: child.name,
      path: child.route ?? '',
    })),
  };
}
