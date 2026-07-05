export const CLIENT_IDS = {
  public: 'hub-public-fe',
  admin: 'hub-admin-fe',
} as const;

export type ProjectType = keyof typeof CLIENT_IDS;
