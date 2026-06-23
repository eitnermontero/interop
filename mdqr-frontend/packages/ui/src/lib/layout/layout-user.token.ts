import { InjectionToken, Signal } from '@angular/core';

export interface LayoutUser {
  username: string;
  email?: string;
  firstName?: string;
  lastName?: string;
}

export interface LayoutUserProvider {
  user: Signal<LayoutUser | null>;
  logout: () => void;
  login?: () => void;
}

export const LAYOUT_USER_PROVIDER = new InjectionToken<LayoutUserProvider>('LAYOUT_USER_PROVIDER');
