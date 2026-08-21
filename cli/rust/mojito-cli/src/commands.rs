use crate::args::{LocaleMappingType, PushType};
use crate::client::{Client, LocalizedAsset, Repository, RepositoryLocale};
use crate::files::{self, SourceFile, TextFile};
use crate::{Cli, CommandKind, Error, Result};
use serde_json::{json, Map, Value};
use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::path::PathBuf;
use uuid::Uuid;

pub fn run(cli: &Cli, client: &mut Client) -> Result<()> {
    match cli.command {
        CommandKind::Push => push(cli, client),
        CommandKind::Pull => pull(cli, client),
        CommandKind::Import => import(cli, client),
    }
}

fn push(cli: &Cli, client: &mut Client) -> Result<()> {
    println!("Push assets to repository: {}", cli.repository);
    let repository = client.repository(&cli.repository)?;
    let sources = files::discover(cli)?;
    let push_run = cli.record_push_run.then(|| Uuid::new_v4().to_string());
    let mut tasks = Vec::new();
    let mut used_assets = BTreeSet::new();

    for source in sources {
        let mut filter_options = source
            .file_type
            .filter_options(cli.filter_options.as_deref())?;
        let content = source.read(true, &filter_options)?;
        if cli.migrate_legacy_json_comments {
            filter_options.push("mojito.migrateLegacyJsonComments=true".to_owned());
        }
        let remote_path = mapped_path(cli, &source.source_path);
        let mut body = Map::from_iter([
            ("repositoryId".to_owned(), json!(repository.id)),
            ("path".to_owned(), json!(remote_path)),
            ("content".to_owned(), json!(content.text)),
            ("extractedContent".to_owned(), json!(false)),
            ("branchNotifiers".to_owned(), json!(cli.branch_notifiers)),
            ("filterOptions".to_owned(), json!(filter_options)),
            ("leveragingType".to_owned(), json!(cli.leveraging_type)),
        ]);
        insert_optional(&mut body, "branch", cli.branch.as_deref());
        insert_optional(
            &mut body,
            "branchCreatedByUsername",
            cli.branch_created_by.as_deref(),
        );
        insert_optional(&mut body, "pushRunName", push_run.as_deref());
        insert_optional(
            &mut body,
            "filterConfigIdOverride",
            source.file_type.filter_override(),
        );

        println!(" - Uploading: {remote_path}");
        let uploaded = client.push_asset(&Value::Object(body))?;
        println!(
            " --> asset id: {}, task: {}",
            uploaded.added_asset_id, uploaded.pollable_task.id
        );
        used_assets.insert(uploaded.added_asset_id);
        tasks.push(uploaded.pollable_task.id);
    }

    if cli.push_type == PushType::SendAssetNoWaitNoDelete {
        println!(
            "Warning: asset processing will not be awaited and unused assets will not be deleted"
        );
    } else {
        for task in tasks {
            client.wait_for_task(task)?;
        }
        if cli.push_type == PushType::Normal {
            delete_unused_assets(cli, client, &repository, &used_assets)?;
        }
    }

    if let (Some(commit_hash), Some(push_run)) = (&cli.commit_hash, push_run) {
        client.associate_push_run(repository.id, commit_hash, &push_run)?;
    }
    println!("Finished");
    Ok(())
}

fn delete_unused_assets(
    cli: &Cli,
    client: &mut Client,
    repository: &Repository,
    used_assets: &BTreeSet<u64>,
) -> Result<()> {
    let Some(branch) = client.branch(repository.id, cli.branch.as_deref())? else {
        return Ok(());
    };
    let unused: Vec<u64> = client
        .asset_ids(repository.id, branch.id)?
        .into_iter()
        .filter(|id| !used_assets.contains(id))
        .collect();
    if unused.is_empty() {
        return Ok(());
    }
    println!("Delete assets from repository, ids: {unused:?}");
    let task = client.delete_assets(branch.id, &unused)?;
    client.wait_for_task(task.id)?;
    Ok(())
}

fn pull(cli: &Cli, client: &mut Client) -> Result<()> {
    println!("Pull localized asset from repository: {}", cli.repository);
    let repository = client.repository(&cli.repository)?;
    let locales = output_locales(cli, &repository)?;
    let sources = files::discover(cli)?;
    let pull_run = cli.record_pull_run.then(|| Uuid::new_v4().to_string());

    if cli.parallel {
        pull_parallel(
            cli,
            client,
            &repository,
            &sources,
            &locales,
            pull_run.as_deref(),
        )?;
    } else {
        for source in sources {
            let filter_options = source
                .file_type
                .filter_options(cli.filter_options.as_deref())?;
            let source_content = source.read(true, &filter_options)?;
            let asset = client.asset(repository.id, mapped_path(cli, &source.source_path))?;
            println!("Localizing: {}", source.source_path);

            for (output_tag, repository_locale) in &locales {
                if !should_generate(cli, &repository, repository_locale) {
                    println!(
                        " - Skipping locale: {} --> not fully translated",
                        repository_locale.locale.bcp47_tag
                    );
                    continue;
                }
                let body = localized_request(
                    cli,
                    asset.id,
                    repository_locale.locale.id,
                    &source_content.text,
                    output_tag,
                    &filter_options,
                    source.file_type.filter_override(),
                    pull_run.as_deref(),
                );
                let localized = if cli.async_ws {
                    let task = client.localized_asset_async(asset.id, &body)?;
                    client.wait_for_task(task.id)?;
                    serde_json::from_str::<LocalizedAsset>(&client.task_output(task.id)?)?
                } else {
                    localized_asset_with_retry(
                        client,
                        asset.id,
                        repository_locale.locale.id,
                        &body,
                    )?
                };
                write_localized(cli, &source, &source_content, &localized)?;
            }
        }
    }

    if let Some(pull_run) = pull_run {
        let path = target_root(cli)?.join("pull-run-name.txt");
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)?;
        }
        fs::write(&path, pull_run)?;
        println!("Writing pull run name to file: {}", path.display());
    }

    println!("Finished");
    Ok(())
}

fn pull_parallel(
    cli: &Cli,
    client: &mut Client,
    repository: &Repository,
    sources: &[SourceFile],
    locales: &BTreeMap<String, RepositoryLocale>,
    pull_run: Option<&str>,
) -> Result<()> {
    println!("Pulling localized assets in parallel");
    let mut tasks = Vec::new();

    for source in sources {
        let filter_options = source
            .file_type
            .filter_options(cli.filter_options.as_deref())?;
        let source_content = source.read(true, &filter_options)?;
        let asset = client.asset(repository.id, mapped_path(cli, &source.source_path))?;
        let locale_infos: Vec<Value> = locales
            .iter()
            .filter(|(_, locale)| should_generate(cli, repository, locale))
            .map(|(output_tag, locale)| {
                json!({"localeId": locale.locale.id, "outputBcp47tag": output_tag})
            })
            .collect();

        let mut body = Map::from_iter([
            ("assetId".to_owned(), json!(asset.id)),
            ("sourceContent".to_owned(), json!(source_content.text)),
            ("localeInfos".to_owned(), json!(locale_infos)),
            ("generateLocalizedAssetJobIds".to_owned(), json!({})),
            ("filterOptions".to_owned(), json!(filter_options)),
            ("inheritanceMode".to_owned(), json!(cli.inheritance_mode)),
            ("status".to_owned(), json!(cli.status)),
            (
                "pullWithNoSource".to_owned(),
                json!(cli.pull_with_no_source),
            ),
            (
                "pullWithNoSourceBranches".to_owned(),
                json!(pull_with_no_source_branches(cli)),
            ),
        ]);
        insert_optional(
            &mut body,
            "filterConfigIdOverride",
            source.file_type.filter_override(),
        );
        insert_optional(&mut body, "pullRunName", pull_run);

        println!("Sending localize request for: {}", source.source_path);
        let task = client.localized_assets_parallel(asset.id, &Value::Object(body))?;
        tasks.push((task.id, source.clone(), source_content));
    }

    println!("Generating localized files");
    for (task_id, source, source_content) in tasks {
        client.wait_for_task(task_id)?;
        let output: Value = serde_json::from_str(&client.task_output(task_id)?)?;
        let jobs = output["generateLocalizedAssetJobIds"]
            .as_object()
            .ok_or_else(|| Error::new("parallel localization response has no generated job IDs"))?;
        for (output_tag, job_id) in jobs {
            let id = job_id
                .as_u64()
                .ok_or_else(|| Error::new("parallel localization job ID is not numeric"))?;
            let mut localized: LocalizedAsset = serde_json::from_str(&client.task_output(id)?)?;
            if localized.bcp47_tag.is_empty() {
                localized.bcp47_tag = output_tag.clone();
            }
            write_localized(cli, &source, &source_content, &localized)?;
        }
    }
    Ok(())
}

fn localized_asset_with_retry(
    client: &mut Client,
    asset_id: u64,
    locale_id: u64,
    body: &Value,
) -> Result<LocalizedAsset> {
    for attempt in 1..=5 {
        match client.localized_asset(asset_id, locale_id, body) {
            Ok(localized) => return Ok(localized),
            Err(error) if attempt < 5 => {
                eprintln!("Attempt {attempt}/5 for locale id {locale_id} failed: {error}");
            }
            Err(error) => return Err(error),
        }
    }
    unreachable!("the final localization attempt always returns")
}

#[allow(clippy::too_many_arguments)]
fn localized_request(
    cli: &Cli,
    asset_id: u64,
    locale_id: u64,
    source_content: &str,
    output_tag: &str,
    filter_options: &[String],
    filter_override: Option<&str>,
    pull_run: Option<&str>,
) -> Value {
    let mut body = Map::from_iter([
        ("assetId".to_owned(), json!(asset_id)),
        ("localeId".to_owned(), json!(locale_id)),
        ("content".to_owned(), json!(source_content)),
        ("outputBcp47tag".to_owned(), json!(output_tag)),
        ("filterOptions".to_owned(), json!(filter_options)),
        ("inheritanceMode".to_owned(), json!(cli.inheritance_mode)),
        ("status".to_owned(), json!(cli.status)),
        (
            "pullWithNoSource".to_owned(),
            json!(cli.pull_with_no_source),
        ),
        (
            "pullWithNoSourceBranches".to_owned(),
            json!(pull_with_no_source_branches(cli)),
        ),
    ]);
    insert_optional(&mut body, "filterConfigIdOverride", filter_override);
    insert_optional(&mut body, "pullRunName", pull_run);
    Value::Object(body)
}

fn pull_with_no_source_branches(cli: &Cli) -> Vec<Option<&str>> {
    cli.pull_with_no_source_branches
        .iter()
        .map(|branch| Some(branch.as_str()))
        .chain(cli.pull_with_no_source_null_branch.then_some(None))
        .collect()
}

fn write_localized(
    cli: &Cli,
    source: &SourceFile,
    source_content: &TextFile,
    localized: &LocalizedAsset,
) -> Result<()> {
    let relative = source.target_path(&localized.bcp47_tag, &cli.source_locale)?;
    let target = target_root(cli)?.join(&relative);
    if cli.skip_empty_output && localized.content.trim().is_empty() {
        if target.exists() {
            fs::remove_file(&target)?;
        }
        println!(" --> skipped empty content: {}", target.display());
        return Ok(());
    }
    source_content.write_like(&target, &localized.content)?;
    println!(
        " - Generated file for locale {}: {} --> {}",
        localized.bcp47_tag,
        source.source_path,
        target.display()
    );
    Ok(())
}

fn import(cli: &Cli, client: &mut Client) -> Result<()> {
    println!(
        "Start importing localized files for repository: {}",
        cli.repository
    );
    let repository = client.repository(&cli.repository)?;
    let locales = import_locales(cli, &repository)?;
    let sources = files::discover(cli)?;

    for source in sources {
        let filter_options = source
            .file_type
            .filter_options(cli.filter_options.as_deref())?;
        for (output_locale, repository_locale) in &locales {
            let result = import_file(
                cli,
                client,
                &repository,
                &source,
                output_locale,
                repository_locale,
                &filter_options,
            );
            if let Err(error) = result {
                if cli.continue_on_error {
                    eprintln!("   Error while importing {}: {error}", source.source_path);
                } else {
                    return Err(error);
                }
            }
        }
    }

    println!("Finished");
    Ok(())
}

fn import_file(
    cli: &Cli,
    client: &mut Client,
    repository: &Repository,
    source: &SourceFile,
    output_locale: &str,
    repository_locale: &RepositoryLocale,
    filter_options: &[String],
) -> Result<()> {
    let relative = source.target_path(output_locale, &cli.source_locale)?;
    let path = target_root(cli)?.join(relative);
    println!(" - Importing file: {}", path.display());
    let localized = TextFile::read(&path)?;
    let options: Vec<String> = filter_options
        .iter()
        .filter(|option| option.as_str() != "mojito.converter=portable")
        .cloned()
        .collect();
    mojito_file_formats::parse_for_mojito_import(
        source.file_type.format(),
        localized.text.as_bytes(),
        &options,
        &repository_locale.locale.bcp47_tag,
        false,
    )?;
    let asset = client.asset(repository.id, mapped_path(cli, &source.source_path))?;
    let mut body = Map::from_iter([
        ("content".to_owned(), json!(localized.text)),
        (
            "statusForEqualTarget".to_owned(),
            json!(cli.status_equal_target),
        ),
        ("filterOptions".to_owned(), json!(filter_options)),
    ]);
    insert_optional(
        &mut body,
        "filterConfigIdOverride",
        source.file_type.filter_override(),
    );
    let imported =
        client.import_asset(asset.id, repository_locale.locale.id, &Value::Object(body))?;
    client.wait_for_task(imported.pollable_task.id)?;
    Ok(())
}

fn output_locales(
    cli: &Cli,
    repository: &Repository,
) -> Result<BTreeMap<String, RepositoryLocale>> {
    let root = repository
        .repository_locales
        .iter()
        .find(|locale| locale.parent_locale.is_none())
        .ok_or_else(|| Error::new("repository does not have a root locale"))?;
    let available: BTreeMap<String, RepositoryLocale> = repository
        .repository_locales
        .iter()
        .filter(|locale| locale.parent_locale.is_some())
        .map(|locale| (locale.locale.bcp47_tag.clone(), locale.clone()))
        .collect();
    let mut result = if cli.locale_mapping_type == LocaleMappingType::MapOnly {
        BTreeMap::new()
    } else {
        available
            .iter()
            .filter(|(tag, _)| !cli.locale_mapping.values().any(|mapped| mapped == *tag))
            .map(|(tag, locale)| (tag.clone(), locale.clone()))
            .collect()
    };

    for (output, target) in &cli.locale_mapping {
        let locale = if target == &root.locale.bcp47_tag {
            root.clone()
        } else {
            available.get(target).cloned().ok_or_else(|| {
                Error::new(format!(
                    "invalid locale mapping for tag {output}: locale {target} is unavailable"
                ))
            })?
        };
        result.insert(output.clone(), locale);
    }
    Ok(result)
}

fn import_locales(cli: &Cli, repository: &Repository) -> Result<Vec<(String, RepositoryLocale)>> {
    let mut inverse = BTreeMap::new();
    for (output, repository_locale) in &cli.locale_mapping {
        if inverse
            .insert(repository_locale.as_str(), output.as_str())
            .is_some()
        {
            return Err(Error::new(
                "import locale mappings must map each repository locale to one output locale",
            ));
        }
    }

    let mut remaining: Vec<RepositoryLocale> = repository
        .repository_locales
        .iter()
        .filter(|locale| locale.parent_locale.is_some())
        .cloned()
        .collect();
    let mut processed = BTreeSet::new();
    let mut result = Vec::new();
    while !remaining.is_empty() {
        let previous = remaining.len();
        let mut next = Vec::new();
        for locale in remaining {
            let parent = locale.parent_locale.as_ref().expect("filtered parent");
            if parent.parent_locale.is_some() && !processed.contains(&parent.locale.id) {
                next.push(locale);
                continue;
            }
            processed.insert(locale.locale.id);
            let output = inverse
                .get(locale.locale.bcp47_tag.as_str())
                .copied()
                .unwrap_or(&locale.locale.bcp47_tag)
                .to_owned();
            if cli.locale_mapping_type != LocaleMappingType::MapOnly
                || inverse.contains_key(locale.locale.bcp47_tag.as_str())
            {
                result.push((output, locale));
            }
        }
        if next.len() == previous {
            return Err(Error::new("repository locale parents contain a cycle"));
        }
        remaining = next;
    }
    Ok(result)
}

fn should_generate(cli: &Cli, repository: &Repository, locale: &RepositoryLocale) -> bool {
    if !cli.fully_translated {
        return true;
    }
    let statistics: BTreeMap<&str, u64> = repository
        .repository_statistic
        .as_ref()
        .map(|statistics| {
            statistics
                .repository_locale_statistics
                .iter()
                .map(|statistic| {
                    (
                        statistic.locale.bcp47_tag.as_str(),
                        statistic.for_translation_count,
                    )
                })
                .collect()
        })
        .unwrap_or_default();
    if locale.to_be_fully_translated {
        statistics
            .get(locale.locale.bcp47_tag.as_str())
            .is_some_and(|count| *count == 0)
    } else {
        locale
            .parent_locale
            .as_ref()
            .and_then(|parent| statistics.get(parent.locale.bcp47_tag.as_str()))
            .is_none_or(|count| *count == 0)
    }
}

fn mapped_path<'a>(cli: &'a Cli, path: &'a str) -> &'a str {
    cli.asset_mapping
        .get(path)
        .map(String::as_str)
        .unwrap_or(path)
}

fn target_root(cli: &Cli) -> Result<PathBuf> {
    cli.target_directory
        .clone()
        .or_else(|| cli.source_directory.clone())
        .map_or_else(|| std::env::current_dir().map_err(Into::into), Ok)
}

fn insert_optional(body: &mut Map<String, Value>, name: &str, value: Option<&str>) {
    if let Some(value) = value {
        body.insert(name.to_owned(), json!(value));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cli(arguments: &[&str]) -> Cli {
        Cli::parse(
            &arguments
                .iter()
                .map(|value| (*value).to_owned())
                .collect::<Vec<_>>(),
        )
        .unwrap()
    }

    fn repository() -> Repository {
        serde_json::from_value(json!({
            "id": 1,
            "repositoryLocales": [
                {"id": 10, "locale": {"id": 10, "bcp47Tag": "en"}},
                {
                    "id": 20,
                    "locale": {"id": 20, "bcp47Tag": "fr"},
                    "parentLocale": {"id": 10, "locale": {"id": 10, "bcp47Tag": "en"}}
                },
                {
                    "id": 30,
                    "locale": {"id": 30, "bcp47Tag": "fr-CA"},
                    "parentLocale": {
                        "id": 20,
                        "locale": {"id": 20, "bcp47Tag": "fr"},
                        "parentLocale": {"id": 10, "locale": {"id": 10, "bcp47Tag": "en"}}
                    }
                }
            ]
        }))
        .unwrap()
    }

    #[test]
    fn mapping_replaces_mapped_repository_tag_and_keeps_other_locales() {
        let cli = cli(&["pull", "-r", "repo", "-lm", "fr-FR:fr"]);
        let locales = output_locales(&cli, &repository()).unwrap();
        assert!(locales.contains_key("fr-FR"));
        assert!(locales.contains_key("fr-CA"));
        assert!(!locales.contains_key("fr"));
    }

    #[test]
    fn map_only_keeps_exact_requested_locales() {
        let cli = cli(&[
            "pull",
            "-r",
            "repo",
            "-lm",
            "fr-FR:fr",
            "--locale-mapping-type",
            "MAP_ONLY",
        ]);
        assert_eq!(
            output_locales(&cli, &repository())
                .unwrap()
                .keys()
                .cloned()
                .collect::<Vec<_>>(),
            vec!["fr-FR"]
        );
    }

    #[test]
    fn imports_parent_before_child_and_inverts_locale_mapping() {
        let cli = cli(&["import", "-r", "repo", "-lm", "fr-FR:fr"]);
        let locales = import_locales(&cli, &repository()).unwrap();
        assert_eq!(locales[0].0, "fr-FR");
        assert_eq!(locales[1].0, "fr-CA");
    }

    #[test]
    fn rejects_invalid_locale_mappings_before_requests() {
        let cli = cli(&["pull", "-r", "repo", "-lm", "de:de"]);
        assert!(output_locales(&cli, &repository()).is_err());
    }

    #[test]
    fn mapped_paths_preserve_java_asset_mapping_behavior() {
        let cli = cli(&["push", "-r", "repo", "-am", "local/en.json:remote/en.json"]);
        assert_eq!(mapped_path(&cli, "local/en.json"), "remote/en.json");
        assert_eq!(mapped_path(&cli, "other/en.json"), "other/en.json");
    }

    #[test]
    fn localized_request_preserves_source_less_pull_selectors() {
        let cli = cli(&[
            "pull",
            "-r",
            "repo",
            "--pull-with-no-source-branches",
            "feature/a",
            "--pull-with-no-source-null-branch",
        ]);
        let body = localized_request(&cli, 1, 2, "{}", "fr", &[], None, None);

        assert_eq!(body["pullWithNoSource"], true);
        assert_eq!(body["pullWithNoSourceBranches"], json!(["feature/a", null]));
    }
}
