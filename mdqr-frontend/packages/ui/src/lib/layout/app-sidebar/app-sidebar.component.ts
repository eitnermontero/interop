import { CommonModule } from '@angular/common';
import {
  ChangeDetectorRef,
  Component,
  ElementRef,
  QueryList,
  ViewChildren,
  inject,
  input,
} from '@angular/core';
import { NavigationEnd, Router, RouterModule } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { combineLatest, Subscription } from 'rxjs';
import { SidebarService } from '../../services/sidebar.service';
import { SafeHtmlPipe } from '../../pipes/safe-html.pipe';
import { SidebarWidgetComponent } from './app-sidebar-widget.component';

export interface NavSubItem {
  name: string;
  path: string;
  pro?: boolean;
  new?: boolean;
}

export interface NavItem {
  name: string;
  icon: string;
  path?: string;
  new?: boolean;
  subItems?: NavSubItem[];
}

@Component({
  selector: 'app-sidebar',
  imports: [CommonModule, RouterModule, SafeHtmlPipe, SidebarWidgetComponent, TranslateModule],
  templateUrl: './app-sidebar.component.html',
})
export class AppSidebarComponent {
  navItems = input<NavItem[]>([]);
  othersItems = input<NavItem[]>([]);
  appName = input<string>('Síntesis');
  version = input<string>('1.0.0');
  loading = input<boolean>(false);

  readonly skeletonRows = [0, 1, 2, 3];

  openSubmenu: string | null | number = null;
  subMenuHeights: Record<string, number> = {};
  @ViewChildren('subMenu') subMenuRefs!: QueryList<ElementRef>;

  readonly sidebarService = inject(SidebarService);
  private readonly router = inject(Router);
  private readonly cdr = inject(ChangeDetectorRef);

  readonly isExpanded$ = this.sidebarService.isExpanded$;
  readonly isMobileOpen$ = this.sidebarService.isMobileOpen$;
  readonly isHovered$ = this.sidebarService.isHovered$;

  private subscription = new Subscription();

  ngOnInit() {
    this.subscription.add(
      this.router.events.subscribe((event) => {
        if (event instanceof NavigationEnd) {
          this.setActiveMenuFromRoute(this.router.url);
        }
      }),
    );

    this.subscription.add(
      combineLatest([this.isExpanded$, this.isMobileOpen$, this.isHovered$]).subscribe(
        ([isExpanded, isMobileOpen, isHovered]) => {
          if (!isExpanded && !isMobileOpen && !isHovered) {
            this.cdr.detectChanges();
          }
        },
      ),
    );

    this.setActiveMenuFromRoute(this.router.url);
  }

  ngOnDestroy() {
    this.subscription.unsubscribe();
  }

  isActive(path: string): boolean {
    return this.router.url === path;
  }

  toggleSubmenu(section: string, index: number) {
    const key = `${section}-${index}`;
    if (this.openSubmenu === key) {
      this.openSubmenu = null;
      this.subMenuHeights[key] = 0;
    } else {
      this.openSubmenu = key;
      setTimeout(() => {
        const el = document.getElementById(key);
        if (el) {
          this.subMenuHeights[key] = el.scrollHeight;
          this.cdr.detectChanges();
        }
      });
    }
  }

  onSidebarMouseEnter() {
    this.isExpanded$
      .subscribe((expanded) => {
        if (!expanded) this.sidebarService.setHovered(true);
      })
      .unsubscribe();
  }

  onSubmenuClick() {
    this.isMobileOpen$
      .subscribe((isMobile) => {
        if (isMobile) this.sidebarService.setMobileOpen(false);
      })
      .unsubscribe();
  }

  private setActiveMenuFromRoute(currentUrl: string) {
    const menuGroups = [
      { items: this.navItems(), prefix: 'main' },
      { items: this.othersItems(), prefix: 'others' },
    ];

    menuGroups.forEach((group) => {
      group.items.forEach((nav, i) => {
        nav.subItems?.forEach((subItem) => {
          if (currentUrl === subItem.path) {
            const key = `${group.prefix}-${i}`;
            this.openSubmenu = key;
            setTimeout(() => {
              const el = document.getElementById(key);
              if (el) {
                this.subMenuHeights[key] = el.scrollHeight;
                this.cdr.detectChanges();
              }
            });
          }
        });
      });
    });
  }
}
