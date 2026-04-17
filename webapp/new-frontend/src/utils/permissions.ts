import type { ApiUserProfile } from '../api/users';

const TRANSLATION_ROLES = new Set(['ROLE_TRANSLATOR', 'ROLE_PM', 'ROLE_ADMIN']);
const GLOSSARY_MANAGER_ROLES = new Set(['ROLE_PM', 'ROLE_ADMIN']);

function canEditTranslations(role: string | null | undefined): boolean {
  if (!role) {
    return false;
  }
  return TRANSLATION_ROLES.has(role);
}

export function canEditLocale(user: ApiUserProfile | undefined, localeTag: string): boolean {
  if (!user || !canEditTranslations(user.role)) {
    return false;
  }
  if (user.canTranslateAllLocales) {
    return true;
  }
  const target = localeTag.toLowerCase();
  return (user.userLocales || []).some((tag) => tag.toLowerCase() === target);
}

export function canAccessGlossaries(user: ApiUserProfile | undefined): boolean {
  return Boolean(user && TRANSLATION_ROLES.has(user.role));
}

export function canManageGlossaryTerms(user: ApiUserProfile | undefined): boolean {
  return Boolean(user && GLOSSARY_MANAGER_ROLES.has(user.role));
}
