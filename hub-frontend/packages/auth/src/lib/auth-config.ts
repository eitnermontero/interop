import { InjectionToken } from '@angular/core';

export interface HubAuthConfig {
  /** URL del servidor Keycloak (ej: https://kc.sintesis.com.bo) */
  authority: string;
  /** Nombre del realm (ej: 'public-clients' o 'sintesis') */
  realm: string;
  /** Client ID configurado en Keycloak */
  clientId: string;
  /** Dominio + paths que reciben automáticamente el bearer token */
  bearerUrls: string[];
  /** Roles requeridos para usar la app (opcional) */
  requiredRoles?: string[];
}

export const HUB_AUTH_CONFIG = new InjectionToken<HubAuthConfig>('HUB_AUTH_CONFIG');
