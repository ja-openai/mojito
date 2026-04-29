import '../settings/settings-page.css';
import './glossaries-page.css';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { Link, Navigate, useNavigate, useParams, useSearchParams } from 'react-router-dom';

import { exportGlossary, fetchGlossary, importGlossary } from '../../api/glossaries';
import { useUser } from '../../components/RequireUser';
import { useRepositories } from '../../hooks/useRepositories';
import { canAccessGlossaries } from '../../utils/permissions';
import { useRepositorySelectionOptions } from '../../utils/repositorySelection';
import { AdminGlossaryTermsPanel } from '../settings/AdminGlossaryTermsPanel';
import { SettingsSubpageHeader } from '../settings/SettingsSubpageHeader';

const normalizeGlossaryName = (value: string) => value.trim().replace(/\s+/g, ' ');

export function GlossaryWorkspacePage() {
  const user = useUser();
  const canExportGlossary = user.role === 'ROLE_ADMIN';
  const queryClient = useQueryClient();
  const params = useParams<{ glossaryId?: string }>();
  const navigate = useNavigate();
  const { data: repositories } = useRepositories();
  const repositoryOptions = useRepositorySelectionOptions(repositories ?? []);
  const glossaryId = Number(params.glossaryId);
  const [searchParams] = useSearchParams();
  const requestedTermId = Number(searchParams.get('termId'));
  const initialOpenTermId =
    Number.isInteger(requestedTermId) && requestedTermId > 0 ? requestedTermId : null;
  const [viewState, setViewState] = useState<{
    mode: 'terms' | 'extract' | 'editor';
    title?: string | null;
  }>({
    mode: initialOpenTermId != null ? 'editor' : 'terms',
    title: null,
  });
  const [backRequestNonce, setBackRequestNonce] = useState(0);
  const [workspaceNotice, setWorkspaceNotice] = useState<{
    kind: 'success' | 'error';
    message: string;
  } | null>(null);
  const importInputRef = useRef<HTMLInputElement | null>(null);

  const glossaryQuery = useQuery({
    queryKey: ['glossary-workspace', glossaryId],
    queryFn: () => fetchGlossary(glossaryId),
    enabled: Number.isInteger(glossaryId) && glossaryId > 0,
    staleTime: 30_000,
  });

  const exportMutation = useMutation({
    mutationFn: async () => exportGlossary(glossaryId),
    onSuccess: (blob) => {
      const glossaryName = glossaryQuery.data?.name || `glossary-${glossaryId}`;
      const downloadUrl = window.URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = downloadUrl;
      anchor.download = `${normalizeGlossaryName(glossaryName).replace(/\s+/g, '-').toLowerCase()}.json`;
      anchor.click();
      window.URL.revokeObjectURL(downloadUrl);
      setWorkspaceNotice({
        kind: 'success',
        message: 'Exported glossary as JSON.',
      });
    },
    onError: (error: Error) => {
      setWorkspaceNotice({
        kind: 'error',
        message: error.message || 'Failed to export glossary.',
      });
    },
  });

  const importMutation = useMutation({
    mutationFn: async (file: File) => {
      const content = await file.text();
      return importGlossary(glossaryId, { content });
    },
    onSuccess: async (result) => {
      await queryClient.invalidateQueries({ queryKey: ['glossary-workspace', glossaryId] });
      await queryClient.invalidateQueries({ queryKey: ['glossary-terms', glossaryId] });
      setWorkspaceNotice({
        kind: 'success',
        message: `Imported glossary (${result.createdTermCount} created, ${result.updatedTermCount} updated).`,
      });
    },
    onError: (error: Error) => {
      setWorkspaceNotice({
        kind: 'error',
        message: error.message || 'Failed to import glossary.',
      });
    },
  });

  if (!canAccessGlossaries(user)) {
    return <Navigate to="/repositories" replace />;
  }

  if (!Number.isInteger(glossaryId) || glossaryId <= 0) {
    return <Navigate to="/glossaries" replace />;
  }

  return (
    <div className="settings-subpage settings-subpage--glossary-workspace">
      <SettingsSubpageHeader
        backTo={viewState.mode === 'terms' ? '/glossaries' : `/glossaries/${glossaryId}`}
        backLabel={viewState.mode === 'terms' ? 'Back to glossaries' : 'Back to glossary'}
        onBack={() => {
          if (viewState.mode === 'terms') {
            void navigate('/glossaries');
            return;
          }
          setBackRequestNonce((current) => current + 1);
        }}
        context={
          glossaryQuery.data
            ? viewState.mode === 'terms'
              ? 'Glossaries'
              : `Glossaries > ${glossaryQuery.data.name}`
            : 'Glossaries'
        }
        title={
          glossaryQuery.data
            ? viewState.mode === 'terms'
              ? glossaryQuery.data.name
              : viewState.mode === 'extract'
                ? 'Extract candidates'
                : viewState.title || 'Edit term'
            : 'Glossaries'
        }
        rightContent={
          canExportGlossary && viewState.mode === 'terms' ? (
            <Link
              className="glossary-workspace__settings-link"
              to={`/glossaries/${glossaryId}/settings`}
            >
              Settings
            </Link>
          ) : null
        }
      />
      <div className="settings-page settings-page--wide glossaries-page">
        <div>
          {workspaceNotice ? (
            <p className={`settings-hint${workspaceNotice.kind === 'error' ? ' is-error' : ''}`}>
              {workspaceNotice.message}
            </p>
          ) : null}
          {glossaryQuery.isError ? (
            <section className="settings-card">
              <p className="settings-hint is-error">
                {glossaryQuery.error instanceof Error
                  ? glossaryQuery.error.message
                  : 'Could not load glossary workspace.'}
              </p>
            </section>
          ) : glossaryQuery.isLoading || !glossaryQuery.data ? (
            <section className="settings-card">
              <p className="settings-hint">Loading glossary workspace…</p>
            </section>
          ) : (
            <>
              <AdminGlossaryTermsPanel
                glossary={glossaryQuery.data}
                repositoryOptions={repositoryOptions}
                initialOpenTermId={initialOpenTermId}
                backRequestNonce={backRequestNonce}
                canImport={canExportGlossary && !importMutation.isPending}
                onOpenImport={() => {
                  setWorkspaceNotice(null);
                  importInputRef.current?.click();
                }}
                canExport={canExportGlossary && !exportMutation.isPending}
                onOpenExport={() => {
                  setWorkspaceNotice(null);
                  exportMutation.mutate();
                }}
                onViewStateChange={setViewState}
              />
            </>
          )}
        </div>
      </div>
      <input
        ref={importInputRef}
        type="file"
        accept=".json,application/json"
        style={{ display: 'none' }}
        onChange={(event) => {
          const file = event.target.files?.[0];
          if (file) {
            importMutation.mutate(file);
          }
          event.currentTarget.value = '';
        }}
      />
    </div>
  );
}
