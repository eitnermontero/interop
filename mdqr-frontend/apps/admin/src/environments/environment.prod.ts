import { MdqrAuthConfig } from '@mdqr/auth';

export const environment = {
  production: true,
  apiBaseUrl: 'https://reports-api.sintesis.com.bo',
  auth: {
    authority: 'https://sso.sintesis.com.bo',
    realm: 'middleware-core',
    clientId: 'mdqr-admin-fe',
    bearerUrls: ['^https://reports-api\\.sintesis\\.com\\.bo/.*'],
  } satisfies MdqrAuthConfig,
};
