#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct Diagnostic {
    pub code: String,
    pub message: String,
    pub start: usize,
    pub end: usize,
}

impl Diagnostic {
    pub(crate) fn new(
        code: impl Into<String>,
        message: impl Into<String>,
        start: usize,
        end: usize,
    ) -> Self {
        Self {
            code: code.into(),
            message: message.into(),
            start,
            end,
        }
    }
}
