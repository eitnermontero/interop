import { HubAuthConfig } from '@hub/auth';

export const environment = {
  production: true,
  apiBaseUrl: 'https://reports-api.sintesis.com.bo',
  auth: {
    authority: 'https://sso.sintesis.com.bo',
    realm: 'hub-admin',
    clientId: 'hub-admin-fe',
    bearerUrls: ['^https://reports-api\\.sintesis\\.com\\.bo/.*'],
  } satisfies HubAuthConfig,
};
