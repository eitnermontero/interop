export const CLIENT_IDS = {
  public: 'mdqr-public-fe',
  admin: 'mdqr-admin-fe',
} as const;

export type ProjectType = keyof typeof CLIENT_IDS;
