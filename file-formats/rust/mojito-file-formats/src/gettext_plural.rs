use crate::model::ParseError;

pub(crate) const LAST_SAMPLE: i64 = 1_000;
const EXTENDED_SAMPLES: [i64; 13] = [
    1_001,
    1_002,
    1_010,
    1_011,
    1_100,
    10_000,
    100_000,
    999_999,
    1_000_000,
    1_000_001,
    1_000_002,
    2_000_000,
    1_000_000_000,
];

pub(crate) fn plural_samples() -> impl Iterator<Item = i64> {
    (0..=LAST_SAMPLE).chain(EXTENDED_SAMPLES)
}

#[derive(Clone, Debug)]
pub(crate) struct PluralForms {
    pub count: usize,
    pub source: String,
    expression: Expression,
}

impl PluralForms {
    pub fn parse(header: &str) -> Result<Self, ParseError> {
        let fields: Vec<_> = header.split(';').collect();
        let mut counts = fields
            .iter()
            .filter_map(|field| field.split_once('='))
            .filter(|(name, _)| name.trim_start() == "nplurals");
        let count = counts
            .next()
            .and_then(|(_, value)| {
                let digits = value.trim_matches(|character: char| character.is_ascii_whitespace());
                if digits.is_empty() || !digits.bytes().all(|digit| digit.is_ascii_digit()) {
                    return None;
                }
                significant_decimal(digits).parse::<usize>().ok()
            })
            .filter(|value| (1..=100).contains(value))
            .ok_or_else(|| invalid("Gettext nplurals must be between 1 and 100"))?;
        if counts.next().is_some() {
            return Err(invalid("Plural-Forms contains a duplicate declaration"));
        }
        let mut expressions = fields
            .iter()
            .filter_map(|field| field.split_once('='))
            .filter(|(name, _)| name.trim_start() == "plural");
        let source = expressions
            .next()
            .map(|(_, value)| value.trim_matches([' ', '\t']).to_owned())
            .ok_or_else(|| invalid("Plural-Forms must declare a plural expression"))?;
        if expressions.next().is_some() {
            return Err(invalid("Plural-Forms contains a duplicate declaration"));
        }
        let mut parser = Parser {
            source: &source,
            index: 0,
        };
        let expression = parser.conditional()?;
        parser.whitespace();
        if parser.index != source.len() {
            return Err(invalid("Unexpected gettext plural expression token"));
        }
        let result = Self {
            count,
            source,
            expression,
        };
        for sample in plural_samples() {
            result.evaluate(sample)?;
        }
        Ok(result)
    }

    pub fn evaluate(&self, n: i64) -> Result<usize, ParseError> {
        let value = self.expression.evaluate(n)?;
        usize::try_from(value)
            .ok()
            .filter(|value| *value < self.count)
            .ok_or_else(|| invalid("Gettext plural expression produced an invalid index"))
    }
}

#[derive(Clone, Debug)]
enum Expression {
    Number(i64),
    Variable,
    Not(Box<Self>),
    Binary(Box<Self>, Operator, Box<Self>),
    Conditional(Box<Self>, Box<Self>, Box<Self>),
}

impl Expression {
    fn evaluate(&self, n: i64) -> Result<i64, ParseError> {
        match self {
            Self::Number(value) => Ok(*value),
            Self::Variable => Ok(n),
            Self::Not(value) => Ok(i64::from(value.evaluate(n)? == 0)),
            Self::Conditional(condition, yes, no) => {
                if condition.evaluate(n)? != 0 {
                    yes.evaluate(n)
                } else {
                    no.evaluate(n)
                }
            }
            Self::Binary(left, operator, right) => {
                let first = left.evaluate(n)?;
                if matches!(operator, Operator::And) && first == 0 {
                    return Ok(0);
                }
                if matches!(operator, Operator::Or) && first != 0 {
                    return Ok(1);
                }
                let second = right.evaluate(n)?;
                match operator {
                    Operator::Or => Ok(i64::from(first != 0 || second != 0)),
                    Operator::And => Ok(i64::from(first != 0 && second != 0)),
                    Operator::Equal => Ok(i64::from(first == second)),
                    Operator::NotEqual => Ok(i64::from(first != second)),
                    Operator::Less => Ok(i64::from(first < second)),
                    Operator::LessEqual => Ok(i64::from(first <= second)),
                    Operator::Greater => Ok(i64::from(first > second)),
                    Operator::GreaterEqual => Ok(i64::from(first >= second)),
                    Operator::Add => first.checked_add(second).ok_or_else(arithmetic_error),
                    Operator::Subtract => first.checked_sub(second).ok_or_else(arithmetic_error),
                    Operator::Multiply => first.checked_mul(second).ok_or_else(arithmetic_error),
                    Operator::Divide => first.checked_div(second).ok_or_else(arithmetic_error),
                    Operator::Modulo => first.checked_rem(second).ok_or_else(arithmetic_error),
                }
            }
        }
    }
}

#[derive(Clone, Copy, Debug)]
enum Operator {
    Or,
    And,
    Equal,
    NotEqual,
    Less,
    LessEqual,
    Greater,
    GreaterEqual,
    Add,
    Subtract,
    Multiply,
    Divide,
    Modulo,
}

struct Parser<'a> {
    source: &'a str,
    index: usize,
}

impl Parser<'_> {
    fn conditional(&mut self) -> Result<Expression, ParseError> {
        let condition = self.logical_or()?;
        if self.consume("?") {
            let yes = self.conditional()?;
            self.require(":")?;
            let no = self.conditional()?;
            Ok(Expression::Conditional(
                Box::new(condition),
                Box::new(yes),
                Box::new(no),
            ))
        } else {
            Ok(condition)
        }
    }

    fn logical_or(&mut self) -> Result<Expression, ParseError> {
        self.binary(Self::logical_and, &[("||", Operator::Or)])
    }

    fn logical_and(&mut self) -> Result<Expression, ParseError> {
        self.binary(Self::equality, &[("&&", Operator::And)])
    }

    fn equality(&mut self) -> Result<Expression, ParseError> {
        self.binary(
            Self::comparison,
            &[("==", Operator::Equal), ("!=", Operator::NotEqual)],
        )
    }

    fn comparison(&mut self) -> Result<Expression, ParseError> {
        self.binary(
            Self::addition,
            &[
                (">=", Operator::GreaterEqual),
                ("<=", Operator::LessEqual),
                (">", Operator::Greater),
                ("<", Operator::Less),
            ],
        )
    }

    fn addition(&mut self) -> Result<Expression, ParseError> {
        self.binary(
            Self::multiplication,
            &[("+", Operator::Add), ("-", Operator::Subtract)],
        )
    }

    fn multiplication(&mut self) -> Result<Expression, ParseError> {
        self.binary(
            Self::unary,
            &[
                ("*", Operator::Multiply),
                ("/", Operator::Divide),
                ("%", Operator::Modulo),
            ],
        )
    }

    fn binary(
        &mut self,
        next: fn(&mut Self) -> Result<Expression, ParseError>,
        operators: &[(&str, Operator)],
    ) -> Result<Expression, ParseError> {
        let mut result = next(self)?;
        loop {
            let Some((_, operator)) = operators.iter().find(|(token, _)| self.consume(token))
            else {
                return Ok(result);
            };
            let right = next(self)?;
            result = Expression::Binary(Box::new(result), *operator, Box::new(right));
        }
    }

    fn unary(&mut self) -> Result<Expression, ParseError> {
        if self.consume("!") {
            return Ok(Expression::Not(Box::new(self.unary()?)));
        }
        if self.consume("(") {
            let value = self.conditional()?;
            self.require(")")?;
            return Ok(value);
        }
        if self.consume("n") {
            return Ok(Expression::Variable);
        }
        self.whitespace();
        let start = self.index;
        while self
            .source
            .as_bytes()
            .get(self.index)
            .is_some_and(u8::is_ascii_digit)
        {
            self.index += 1;
        }
        if start == self.index {
            return Err(invalid("Expected gettext plural expression operand"));
        }
        let value = significant_decimal(&self.source[start..self.index])
            .parse()
            .map_err(|_| invalid("Gettext plural expression number is out of range"))?;
        Ok(Expression::Number(value))
    }

    fn consume(&mut self, token: &str) -> bool {
        self.whitespace();
        if self.source[self.index..].starts_with(token) {
            self.index += token.len();
            true
        } else {
            false
        }
    }

    fn require(&mut self, token: &str) -> Result<(), ParseError> {
        if self.consume(token) {
            Ok(())
        } else {
            Err(invalid("Missing gettext plural expression token"))
        }
    }

    fn whitespace(&mut self) {
        while self
            .source
            .as_bytes()
            .get(self.index)
            .is_some_and(|value| matches!(value, b' ' | b'\t'))
        {
            self.index += 1;
        }
    }
}

fn significant_decimal(digits: &str) -> &str {
    let significant = digits.trim_start_matches('0');
    if significant.is_empty() {
        "0"
    } else {
        significant
    }
}

fn invalid(message: &str) -> ParseError {
    ParseError::new("INVALID_GETTEXT_PLURAL_FORMS", message)
}

fn arithmetic_error() -> ParseError {
    invalid("Gettext plural expression arithmetic is invalid")
}
