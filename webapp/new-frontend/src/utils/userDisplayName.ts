type UserNameFields = {
  username?: string | null;
  givenName?: string | null;
  surname?: string | null;
  commonName?: string | null;
};

const normalizeNamePart = (value?: string | null) => {
  const normalized = value?.trim() ?? '';
  return normalized.length > 0 ? normalized : null;
};

export const getUserFullName = (user: UserNameFields) =>
  normalizeNamePart(user.commonName) ||
  [normalizeNamePart(user.givenName), normalizeNamePart(user.surname)].filter(Boolean).join(' ') ||
  null;

export const getUserDisplayName = (user: UserNameFields) =>
  getUserFullName(user) || normalizeNamePart(user.username) || '';

export const getUserLabel = (user: UserNameFields) => {
  const fullName = getUserFullName(user);
  const username = normalizeNamePart(user.username) || '';
  return fullName ? `${fullName} (${username})` : username;
};
