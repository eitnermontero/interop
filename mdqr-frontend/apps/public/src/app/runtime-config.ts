export interface RuntimeConfig {
  apiUrl: string;
  appName: string;
  basePath: string;
  keycloak: {
    url: string;
    realm: string;
    clientId: string;
  };
}

export function escapeRegExp(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
