use crate::{Error, Result};
use std::collections::BTreeMap;
use std::path::{Component, Path, PathBuf};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum CommandKind {
    Push,
    Pull,
    Import,
}

#[derive(Clone, Eq, PartialEq)]
pub struct Cli {
    pub command: CommandKind,
    pub repository: String,
    pub source_directory: Option<PathBuf>,
    pub target_directory: Option<PathBuf>,
    pub source_locale: String,
    pub source_regex: Option<String>,
    pub file_types: Vec<String>,
    pub filter_options: Option<Vec<String>>,
    pub include_patterns: Vec<String>,
    pub exclude_patterns: Vec<String>,
    pub locale_mapping: BTreeMap<String, String>,
    pub locale_mapping_type: LocaleMappingType,
    pub asset_mapping: BTreeMap<String, String>,
    pub branch: Option<String>,
    pub branch_created_by: Option<String>,
    pub branch_notifiers: Vec<String>,
    pub push_type: PushType,
    pub leveraging_type: String,
    pub commit_hash: Option<String>,
    pub record_push_run: bool,
    pub record_pull_run: bool,
    pub parallel: bool,
    pub async_ws: bool,
    pub inheritance_mode: String,
    pub status: String,
    pub status_equal_target: String,
    pub continue_on_error: bool,
    pub fully_translated: bool,
    pub skip_empty_output: bool,
    pub pull_with_no_source: bool,
    pub pull_with_no_source_branches: Vec<String>,
    pub pull_with_no_source_null_branch: bool,
    pub migrate_legacy_json_comments: bool,
    pub config_file: Option<PathBuf>,
    pub url: Option<String>,
    pub username: Option<String>,
    pub password: Option<String>,
    pub token: Option<String>,
    pub headers: Vec<(String, String)>,
    pub help: bool,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum LocaleMappingType {
    WithRepository,
    MapOnly,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum PushType {
    Normal,
    NoDelete,
    SendAssetNoWaitNoDelete,
}

impl Cli {
    pub fn parse(arguments: &[String]) -> Result<Self> {
        if arguments.iter().any(|argument| argument.starts_with("-D")) {
            return Err(Error::new(
                "JVM -D properties are not supported; use --config, MOJITO_CONFIG, \
                 or L10N_RESTTEMPLATE_* environment variables",
            ));
        }

        if arguments.is_empty()
            || arguments
                .iter()
                .any(|argument| matches!(argument.as_str(), "--help" | "-h"))
        {
            return Ok(Self::defaults(CommandKind::Push, true));
        }

        let (command_index, command) = find_command(arguments)?;
        let mut cli = Self::defaults(command, false);
        let mut remaining = arguments.to_vec();
        remaining.remove(command_index);

        let mut index = 0;
        while index < remaining.len() {
            let argument = &remaining[index];
            match argument.as_str() {
                "-r" | "--repository" => cli.repository = take_value(&remaining, &mut index)?,
                "-s" | "--source-directory" => {
                    cli.source_directory = Some(take_value(&remaining, &mut index)?.into());
                }
                "-t" | "--target-directory" => {
                    cli.target_directory = Some(take_value(&remaining, &mut index)?.into());
                }
                "-sl" | "--source-locale" => {
                    cli.source_locale = take_value(&remaining, &mut index)?;
                }
                "-sr" | "--source-regex" => {
                    cli.source_regex = Some(take_value(&remaining, &mut index)?);
                }
                "-ft" | "--file-type" => {
                    cli.file_types.extend(take_values(&remaining, &mut index)?);
                }
                "-fo" | "--filter-options" => {
                    cli.filter_options
                        .get_or_insert_with(Vec::new)
                        .extend(take_values(&remaining, &mut index)?);
                }
                "--dir-path-include-patterns" => {
                    cli.include_patterns
                        .extend(take_values(&remaining, &mut index)?);
                }
                "--dir-path-exclude-patterns" => {
                    cli.exclude_patterns
                        .extend(take_values(&remaining, &mut index)?);
                }
                "-lm" | "--locale-mapping" => {
                    cli.locale_mapping =
                        parse_mapping(&take_value(&remaining, &mut index)?, ',', "locale mapping")?;
                    validate_locale_mapping_outputs(&cli.locale_mapping)?;
                }
                "-lmt" | "--locale-mapping-type" => {
                    cli.locale_mapping_type = match take_value(&remaining, &mut index)?.as_str() {
                        "WITH_REPOSITORY" => LocaleMappingType::WithRepository,
                        "MAP_ONLY" => LocaleMappingType::MapOnly,
                        value => {
                            return Err(Error::new(format!(
                                "unsupported locale mapping type: {value}"
                            )))
                        }
                    };
                }
                "-am" | "--asset-mapping" => {
                    cli.asset_mapping =
                        parse_mapping(&take_value(&remaining, &mut index)?, ';', "asset mapping")?;
                }
                "-b" | "--branch" => cli.branch = Some(take_value(&remaining, &mut index)?),
                "-bc" | "--branch-createdby" => {
                    cli.branch_created_by = Some(take_value(&remaining, &mut index)?);
                }
                "-bn" | "--branch-notifiers" => {
                    cli.branch_notifiers
                        .extend(take_values(&remaining, &mut index)?);
                }
                "--push-type" => {
                    cli.push_type = match take_value(&remaining, &mut index)?.as_str() {
                        "NORMAL" => PushType::Normal,
                        "NO_DELETE" => PushType::NoDelete,
                        "SEND_ASSET_NO_WAIT_NO_DELETE" => PushType::SendAssetNoWaitNoDelete,
                        value => return Err(Error::new(format!("unsupported push type: {value}"))),
                    };
                }
                "--leveraging-type" => {
                    cli.leveraging_type = take_value(&remaining, &mut index)?;
                    if !matches!(
                        cli.leveraging_type.as_str(),
                        "LEGACY_SOURCE" | "ASSET_SOURCE_AND_COMMENT" | "CROSS_ASSET_FALLBACK"
                    ) {
                        return Err(Error::new(format!(
                            "unsupported leveraging type: {}",
                            cli.leveraging_type
                        )));
                    }
                }
                "-c" | "--commit-hash" => {
                    cli.commit_hash = Some(take_value(&remaining, &mut index)?);
                }
                "-rp" | "--record-push-run" => cli.record_push_run = true,
                "--record-pull-run" => cli.record_pull_run = true,
                "--parallel" => cli.parallel = true,
                "--async-ws" => cli.async_ws = true,
                "--inheritance-mode" => {
                    cli.inheritance_mode = take_value(&remaining, &mut index)?;
                    if !matches!(
                        cli.inheritance_mode.as_str(),
                        "USE_PARENT" | "REMOVE_UNTRANSLATED"
                    ) {
                        return Err(Error::new(format!(
                            "unsupported inheritance mode: {}",
                            cli.inheritance_mode
                        )));
                    }
                }
                "--status" => {
                    cli.status = take_value(&remaining, &mut index)?;
                    if !matches!(
                        cli.status.as_str(),
                        "ALL" | "ACCEPTED_OR_NEEDS_REVIEW" | "ACCEPTED"
                    ) {
                        return Err(Error::new(format!("unsupported status: {}", cli.status)));
                    }
                }
                "--status-equal-target" => {
                    cli.status_equal_target = take_value(&remaining, &mut index)?;
                    if !matches!(
                        cli.status_equal_target.as_str(),
                        "SKIPPED" | "REVIEW_NEEDED" | "TRANSLATION_NEEDED" | "APPROVED"
                    ) {
                        return Err(Error::new(format!(
                            "unsupported equal-target status: {}",
                            cli.status_equal_target
                        )));
                    }
                }
                "--continue-on-error" => cli.continue_on_error = true,
                "--fully-translated" => cli.fully_translated = true,
                "--skip-empty-output" => cli.skip_empty_output = true,
                "--pull-with-no-source" => cli.pull_with_no_source = true,
                "--pull-with-no-source-branches" => {
                    let branches = take_values(&remaining, &mut index)?;
                    extend_unique_trimmed_branches(
                        &mut cli.pull_with_no_source_branches,
                        branches,
                    )?;
                    cli.pull_with_no_source = true;
                }
                "--pull-with-no-source-null-branch" => {
                    cli.pull_with_no_source_null_branch = true;
                    cli.pull_with_no_source = true;
                }
                "--migrate-legacy-json-comments" => cli.migrate_legacy_json_comments = true,
                "--converter" => {
                    let converter = take_value(&remaining, &mut index)?;
                    if !converter.eq_ignore_ascii_case("portable") {
                        return Err(Error::new(format!(
                            "the native CLI only supports --converter portable; got {converter}"
                        )));
                    }
                }
                "--config" => cli.config_file = Some(take_value(&remaining, &mut index)?.into()),
                "--url" => cli.url = Some(take_value(&remaining, &mut index)?),
                "--username" => cli.username = Some(take_value(&remaining, &mut index)?),
                "--password" => cli.password = Some(take_value(&remaining, &mut index)?),
                "--token" => cli.token = Some(take_value(&remaining, &mut index)?),
                "--header" => {
                    let header = take_value(&remaining, &mut index)?;
                    let (name, value) = header
                        .split_once(':')
                        .ok_or_else(|| Error::new("headers must use the format 'Name: value'"))?;
                    if name.trim().is_empty() || value.trim().is_empty() {
                        return Err(Error::new("headers require a non-empty name and value"));
                    }
                    cli.headers
                        .push((name.trim().to_owned(), value.trim().to_owned()));
                }
                unknown => return Err(Error::new(format!("unknown option: {unknown}"))),
            }
            index += 1;
        }

        if cli.repository.is_empty() {
            return Err(Error::new("the -r/--repository option is required"));
        }
        if cli.locale_mapping_type == LocaleMappingType::MapOnly && cli.locale_mapping.is_empty() {
            return Err(Error::new("MAP_ONLY requires -lm/--locale-mapping"));
        }
        if cli.record_push_run != cli.commit_hash.is_some() {
            return Err(Error::new(
                "--record-push-run and --commit-hash must be supplied together",
            ));
        }
        if cli.record_push_run && cli.push_type == PushType::SendAssetNoWaitNoDelete {
            return Err(Error::new(
                "SEND_ASSET_NO_WAIT_NO_DELETE cannot be combined with --record-push-run",
            ));
        }
        Ok(cli)
    }

    fn defaults(command: CommandKind, help: bool) -> Self {
        Self {
            command,
            repository: String::new(),
            source_directory: None,
            target_directory: None,
            source_locale: "en".to_owned(),
            source_regex: None,
            file_types: Vec::new(),
            filter_options: None,
            include_patterns: Vec::new(),
            exclude_patterns: Vec::new(),
            locale_mapping: BTreeMap::new(),
            locale_mapping_type: LocaleMappingType::WithRepository,
            asset_mapping: BTreeMap::new(),
            branch: None,
            branch_created_by: None,
            branch_notifiers: Vec::new(),
            push_type: PushType::Normal,
            leveraging_type: "LEGACY_SOURCE".to_owned(),
            commit_hash: None,
            record_push_run: false,
            record_pull_run: false,
            parallel: false,
            async_ws: false,
            inheritance_mode: "USE_PARENT".to_owned(),
            status: "ALL".to_owned(),
            status_equal_target: "APPROVED".to_owned(),
            continue_on_error: false,
            fully_translated: false,
            skip_empty_output: false,
            pull_with_no_source: false,
            pull_with_no_source_branches: Vec::new(),
            pull_with_no_source_null_branch: false,
            migrate_legacy_json_comments: false,
            config_file: None,
            url: None,
            username: None,
            password: None,
            token: None,
            headers: Vec::new(),
            help,
        }
    }

    pub fn help() -> &'static str {
        "Native Mojito localization CLI\n\n\
         Usage: mojito [connection options] <push|pull|import> -r <repository> [options]\n\n\
         Existing Java options are preserved, including -ft, -fo, -lm, -am, -b,\n\
         --parallel, --inheritance-mode, and source/target directory options.\n\n\
         Connection options:\n\
           --config <file>       Existing Mojito application.properties\n\
           --url <url>           Override the Mojito server URL\n\
           --username <name>     Stateful form-login username\n\
           --password <password> Stateful form-login password\n\
           --token <token>       Bearer token for stateless authentication\n\
           --header 'Name: val'  Add a request header\n\n\
         Configuration can also use MOJITO_CONFIG, MOJITO_URL, and existing\n\
         L10N_RESTTEMPLATE_* environment variables. JVM -D flags are not supported.\n"
    }
}

fn find_command(arguments: &[String]) -> Result<(usize, CommandKind)> {
    let mut index = 0;
    while let Some(argument) = arguments.get(index) {
        let command = match argument.as_str() {
            "push" | "p" => Some(CommandKind::Push),
            "pull" | "l" => Some(CommandKind::Pull),
            "import" => Some(CommandKind::Import),
            _ => None,
        };
        if let Some(command) = command {
            return Ok((index, command));
        }

        if matches!(
            argument.as_str(),
            "--config" | "--url" | "--username" | "--password" | "--token" | "--header"
        ) {
            take_value(arguments, &mut index)?;
            index += 1;
            continue;
        }

        return Err(Error::new(format!(
            "expected a push, pull, or import command; found {argument}"
        )));
    }

    Err(Error::new("expected a push, pull, or import command"))
}

fn take_value(arguments: &[String], index: &mut usize) -> Result<String> {
    *index += 1;
    arguments
        .get(*index)
        .filter(|value| !is_option(value))
        .cloned()
        .ok_or_else(|| Error::new(format!("{} requires a value", arguments[*index - 1])))
}

fn take_values(arguments: &[String], index: &mut usize) -> Result<Vec<String>> {
    let option = arguments[*index].clone();
    let mut values = Vec::new();
    while let Some(value) = arguments.get(*index + 1) {
        if is_option(value) {
            break;
        }
        values.push(value.clone());
        *index += 1;
    }
    if values.is_empty() {
        return Err(Error::new(format!("{option} requires at least one value")));
    }
    Ok(values)
}

fn is_option(value: &str) -> bool {
    value.starts_with('-')
}

fn extend_unique_trimmed_branches(target: &mut Vec<String>, branches: Vec<String>) -> Result<()> {
    for branch in branches {
        let branch = branch.trim();
        if branch.is_empty() {
            return Err(Error::new(
                "--pull-with-no-source-branches cannot contain a blank branch name",
            ));
        }
        if !target.iter().any(|existing| existing == branch) {
            target.push(branch.to_owned());
        }
    }
    Ok(())
}

fn parse_mapping(
    input: &str,
    item_separator: char,
    description: &str,
) -> Result<BTreeMap<String, String>> {
    let mut mapping = BTreeMap::new();
    for item in input.split(item_separator) {
        let Some((key, value)) = item.split_once(':') else {
            return Err(Error::new(format!("invalid {description}: {input}")));
        };
        if key.is_empty()
            || value.is_empty()
            || mapping.insert(key.to_owned(), value.to_owned()).is_some()
        {
            return Err(Error::new(format!("invalid {description}: {input}")));
        }
    }
    Ok(mapping)
}

fn validate_locale_mapping_outputs(mapping: &BTreeMap<String, String>) -> Result<()> {
    for output in mapping.keys() {
        let path = Path::new(output);
        if output.is_empty()
            || path.is_absolute()
            || output
                .chars()
                .any(|character| matches!(character, '/' | '\\'))
            || path.components().any(|component| {
                matches!(
                    component,
                    Component::Prefix(_)
                        | Component::RootDir
                        | Component::ParentDir
                        | Component::CurDir
                )
            })
        {
            return Err(Error::new(format!(
                "invalid locale mapping output `{output}`: expected one relative path component"
            )));
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parse(arguments: &[&str]) -> Result<Cli> {
        Cli::parse(
            &arguments
                .iter()
                .map(|value| (*value).to_owned())
                .collect::<Vec<_>>(),
        )
    }

    #[test]
    fn accepts_real_monorepo_pull_arguments() {
        let cli = parse(&[
            "pull",
            "-r",
            "chatgpt-web",
            "-ft",
            "JSON_NOBASENAME",
            "--parallel",
            "--inheritance-mode",
            "REMOVE_UNTRANSLATED",
            "-fo",
            "noteKeyPattern=description",
            "extractAllPairs=false",
            "exceptions=defaultMessage",
            "removeKeySuffix=/defaultMessage",
            "--dir-path-include-patterns",
            "src/locales-mojito",
            "-lm",
            "fr-FR:fr,zh-TW:zh-Hant",
        ])
        .unwrap();

        assert_eq!(cli.command, CommandKind::Pull);
        assert!(cli.parallel);
        assert_eq!(cli.filter_options.as_ref().unwrap().len(), 4);
        assert_eq!(cli.locale_mapping["zh-TW"], "zh-Hant");
    }

    #[test]
    fn rejects_jvm_properties_with_actionable_error() {
        let error = parse(&["-Dl10n.resttemplate.host=example", "push", "-r", "repo"])
            .err()
            .expect("JVM properties should be rejected");
        assert!(error.to_string().contains("MOJITO_CONFIG"));
        assert!(error.to_string().contains("L10N_RESTTEMPLATE_"));
    }

    #[test]
    fn validates_record_push_run_flags() {
        assert!(parse(&["push", "-r", "repo", "--record-push-run"]).is_err());
        assert!(parse(&["push", "-r", "repo", "-c", "abcdef"]).is_err());
        assert!(parse(&["push", "-r", "repo", "--record-push-run", "-c", "abcdef",]).is_ok());
    }

    #[test]
    fn supports_global_connection_options_before_the_command() {
        let cli = parse(&["--url", "https://mojito.example", "p", "-r", "repo"]).unwrap();
        assert_eq!(cli.url.as_deref(), Some("https://mojito.example"));
    }

    #[test]
    fn does_not_treat_global_option_values_as_commands() {
        let cli = parse(&["--token", "push", "pull", "-r", "repo"]).unwrap();
        assert_eq!(cli.command, CommandKind::Pull);
        assert_eq!(cli.token.as_deref(), Some("push"));

        let cli = parse(&["--username", "import", "push", "-r", "repo"]).unwrap();
        assert_eq!(cli.command, CommandKind::Push);
        assert_eq!(cli.username.as_deref(), Some("import"));
    }

    #[test]
    fn supports_current_master_pull_with_no_source_options() {
        let cli = parse(&[
            "pull",
            "-r",
            "repo",
            "--pull-with-no-source-branches",
            " feature/a ",
            "feature/b",
            "feature/a",
            "--pull-with-no-source-null-branch",
        ])
        .unwrap();

        assert!(cli.pull_with_no_source);
        assert!(cli.pull_with_no_source_null_branch);
        assert_eq!(cli.pull_with_no_source_branches, ["feature/a", "feature/b"]);

        let cli = parse(&["pull", "-r", "repo", "--pull-with-no-source"]).unwrap();
        assert!(cli.pull_with_no_source);
    }

    #[test]
    fn rejects_blank_pull_with_no_source_branches() {
        let error = parse(&[
            "pull",
            "-r",
            "repo",
            "--pull-with-no-source-branches",
            "   ",
        ])
        .err()
        .expect("blank branch names should be rejected");
        assert!(error.to_string().contains("blank branch name"));
    }

    #[test]
    fn accepts_legacy_json_comment_migration_flag() {
        let cli = parse(&["push", "-r", "repo", "--migrate-legacy-json-comments"]).unwrap();
        assert!(cli.migrate_legacy_json_comments);
    }

    #[test]
    fn rejects_duplicate_locale_mapping_keys() {
        assert!(parse(&["pull", "-r", "repo", "-lm", "fr:fr-FR,fr:fr-CA"]).is_err());
    }

    #[test]
    fn rejects_locale_mapping_outputs_that_can_escape_the_target_directory() {
        for output in ["../outside", "/absolute", r"..\outside", ".", ".."] {
            let error = parse(&["pull", "-r", "repo", "-lm", &format!("{output}:fr")])
                .err()
                .expect("unsafe locale mapping outputs should be rejected");
            assert!(
                error.to_string().contains("one relative path component"),
                "unexpected error for {output}: {error}"
            );
        }
    }

    #[test]
    fn accepts_java_compatible_path_safe_locale_mapping_outputs() {
        for output in ["fr.FR", "sr@latin", "custom locale"] {
            assert!(parse(&["pull", "-r", "repo", "-lm", &format!("{output}:fr")]).is_ok());
        }
    }
}
