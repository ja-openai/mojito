import { createContext, useContext } from 'react';

import type { ApiUserProfile } from '../api/users';

export const UserContext = createContext<ApiUserProfile | null>(null);

export function useUser(): ApiUserProfile {
  const user = useContext(UserContext);
  if (!user) {
    throw new Error('useUser must be used within RequireUser');
  }
  return user;
}
