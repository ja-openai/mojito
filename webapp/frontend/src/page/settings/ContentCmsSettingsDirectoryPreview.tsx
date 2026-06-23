import '../../app.css';

import { MemoryRouter } from 'react-router-dom';

import type { ApiUserProfile } from '../../api/users';
import { UserContext } from '../../hooks/useUser';
import { AdminSettingsPage } from './AdminSettingsPage';

const previewAdminUser = {
  username: 'preview-admin',
  role: 'ROLE_ADMIN',
  canTranslateAllLocales: true,
  userLocales: [],
} satisfies ApiUserProfile;

export function ContentCmsSettingsDirectoryPreview() {
  return (
    <MemoryRouter initialEntries={['/settings/system?cmsPreview=settings-directory']}>
      <UserContext.Provider value={previewAdminUser}>
        <div className="app-shell app-shell--bare">
          <main className="app-shell__main">
            <AdminSettingsPage />
          </main>
        </div>
      </UserContext.Provider>
    </MemoryRouter>
  );
}
