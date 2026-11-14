//! Read one Markdown file and write the render result as JSON.
//!
//! The library does no IO. This binary is the only part that touches the
//! filesystem, and it never opens a network connection.

use std::env;
use std::fs;
use std::io::{self, Write};
use std::process::ExitCode;

use md2wx::{render, RenderOptions, Theme};
use serde::Serialize;

#[derive(Serialize)]
struct Output<'a> {
    html: &'a str,
    images: &'a [md2wx::ImageRef],
    #[serde(rename = "charCount")]
    char_count: usize,
    #[serde(rename = "byteLen")]
    byte_len: usize,
}

fn main() -> ExitCode {
    match run() {
        Ok(()) => ExitCode::SUCCESS,
        Err(CliError::Usage(message)) => {
            eprintln!("md2wx: {message}");
            eprintln!("用法: md2wx --input FILE --output FILE [--image-base URL]");
            ExitCode::from(2)
        }
        Err(CliError::Render(err)) => {
            eprintln!("md2wx: {err}");
            ExitCode::from(1)
        }
        Err(CliError::Io(err)) => {
            eprintln!("md2wx: {err}");
            ExitCode::from(1)
        }
    }
}

enum CliError {
    Usage(String),
    Render(md2wx::RenderError),
    Io(io::Error),
}

impl From<md2wx::RenderError> for CliError {
    fn from(err: md2wx::RenderError) -> Self {
        CliError::Render(err)
    }
}

impl From<io::Error> for CliError {
    fn from(err: io::Error) -> Self {
        CliError::Io(err)
    }
}

fn run() -> Result<(), CliError> {
    let args = Args::parse()?;
    let markdown = fs::read_to_string(&args.input)?;
    let theme = Theme::blog();
    let options = RenderOptions {
        theme: &theme,
        image_base_url: &args.image_base,
    };
    let rendered = render(&markdown, &options)?;
    let output = Output {
        html: &rendered.html,
        images: &rendered.images,
        char_count: rendered.char_count,
        byte_len: rendered.byte_len,
    };
    let mut file = fs::File::create(&args.output)?;
    serde_json::to_writer_pretty(&mut file, &output).map_err(io::Error::other)?;
    writeln!(file)?;
    Ok(())
}

struct Args {
    input: String,
    output: String,
    image_base: String,
}

impl Args {
    fn parse() -> Result<Self, CliError> {
        let mut input = None;
        let mut output = None;
        let mut image_base = String::new();
        let mut tokens = env::args().skip(1);
        while let Some(token) = tokens.next() {
            let value = match token.as_str() {
                "--input" => tokens.next(),
                "--output" => tokens.next(),
                "--image-base" => tokens.next(),
                "--help" | "-h" => {
                    return Err(CliError::Usage("查看用法".to_string()));
                }
                other => {
                    return Err(CliError::Usage(format!("无法识别的参数 {other}")));
                }
            };
            let Some(value) = value else {
                return Err(CliError::Usage(format!("{token} 缺少值")));
            };
            match token.as_str() {
                "--input" => input = Some(value),
                "--output" => output = Some(value),
                "--image-base" => image_base = value,
                _ => {}
            }
        }
        let Some(input) = input else {
            return Err(CliError::Usage("缺少 --input".to_string()));
        };
        let Some(output) = output else {
            return Err(CliError::Usage("缺少 --output".to_string()));
        };
        Ok(Self {
            input,
            output,
            image_base,
        })
    }
}
