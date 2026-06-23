import { Component, inject, input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { SidebarService } from '../../services/sidebar.service';
import { AppSidebarComponent, NavItem } from '../app-sidebar/app-sidebar.component';
import { BackdropComponent } from '../backdrop/backdrop.component';
import { AppHeaderComponent } from '../app-header/app-header.component';

@Component({
  selector: 'app-layout',
  imports: [CommonModule, RouterModule, AppHeaderComponent, AppSidebarComponent, BackdropComponent],
  templateUrl: './app-layout.component.html',
})
export class AppLayoutComponent {
  navItems = input<NavItem[]>([]);
  othersItems = input<NavItem[]>([]);
  loading = input<boolean>(false);

  readonly sidebarService = inject(SidebarService);
  readonly isExpanded$ = this.sidebarService.isExpanded$;
  readonly isHovered$ = this.sidebarService.isHovered$;
  readonly isMobileOpen$ = this.sidebarService.isMobileOpen$;
}
