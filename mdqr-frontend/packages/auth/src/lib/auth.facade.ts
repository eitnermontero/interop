import { computed, effect, inject, Injectable, signal } from '@angular/core';
import {
  KEYCLOAK_EVENT_SIGNAL,
  KeycloakEventType,
  typeEventArgs,
  ReadyArgs,
} from 'keycloak-angular';
import Keycloak from 'keycloak-js';

export interface AuthUser {
  username: string;
  email?: string;
  firstName?: string;
  lastName?: string;
  roles: string[];
}

@Injectable({ providedIn: 'root' })
export class AuthFacade {
  private readonly keycloak = inject(Keycloak);
  private readonly events = inject(KEYCLOAK_EVENT_SIGNAL);

  readonly authenticated = signal(false);
  readonly user = signal<AuthUser | null>(null);

  readonly isLoggedIn = computed(() => this.authenticated());

  constructor() {
    effect(() => {
      const event = this.events();
      if (event.type === KeycloakEventType.Ready) {
        const ready = typeEventArgs<ReadyArgs>(event.args);
        this.authenticated.set(ready);
        if (ready) this.refreshUser();
      }
      if (event.type === KeycloakEventType.AuthLogout) {
        this.authenticated.set(false);
        this.user.set(null);
      }
      if (event.type === KeycloakEventType.AuthRefreshSuccess) {
        this.refreshUser();
      }
    });
  }

  login(redirectUri?: string) {
    return this.keycloak.login({ redirectUri });
  }

  logout(redirectUri?: string) {
    return this.keycloak.logout({ redirectUri });
  }

  accountManagement() {
    return this.keycloak.accountManagement();
  }

  hasRole(role: string): boolean {
    return this.keycloak.hasRealmRole(role);
  }

  hasAnyRole(roles: string[]): boolean {
    return roles.some((r) => this.hasRole(r));
  }

  async getToken(): Promise<string | undefined> {
    await this.keycloak.updateToken(30);
    return this.keycloak.token;
  }

  private refreshUser() {
    const parsed = this.keycloak.tokenParsed as
      | {
          preferred_username?: string;
          email?: string;
          given_name?: string;
          family_name?: string;
          realm_access?: { roles: string[] };
        }
      | undefined;

    if (!parsed) {
      this.user.set(null);
      return;
    }

    this.user.set({
      username: parsed.preferred_username ?? '',
      email: parsed.email,
      firstName: parsed.given_name,
      lastName: parsed.family_name,
      roles: parsed.realm_access?.roles ?? [],
    });
  }
}
