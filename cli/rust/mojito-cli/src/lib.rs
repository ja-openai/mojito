mod args;
mod client;
mod commands;
mod config;
mod files;

use std::fmt;

pub use args::{Cli, CommandKind};
pub use config::Config;

pub type Result<T> = std::result::Result<T, Error>;

#[derive(Debug)]
pub struct Error(String);

impl Error {
    pub(crate) fn new(message: impl Into<String>) -> Self {
        Self(message.into())
    }
}

impl fmt::Display for Error {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(&self.0)
    }
}

impl std::error::Error for Error {}

impl From<std::io::Error> for Error {
    fn from(error: std::io::Error) -> Self {
        Self::new(error.to_string())
    }
}

impl From<serde_json::Error> for Error {
    fn from(error: serde_json::Error) -> Self {
        Self::new(error.to_string())
    }
}

impl From<mojito_file_formats::ParseError> for Error {
    fn from(error: mojito_file_formats::ParseError) -> Self {
        Self::new(error.to_string())
    }
}

pub fn run(arguments: &[String]) -> Result<()> {
    let cli = Cli::parse(arguments)?;
    if cli.help {
        print!("{}", Cli::help());
        return Ok(());
    }

    let config = Config::load(&cli)?;
    let mut client = client::Client::new(config)?;
    commands::run(&cli, &mut client)
}
