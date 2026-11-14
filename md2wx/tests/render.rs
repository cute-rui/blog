use std::fs;
use std::path::PathBuf;

use md2wx::{render, RenderOptions, Theme};

fn fixture(name: &str) -> String {
    let file = if name.ends_with(".md") {
        name.to_string()
    } else {
        format!("{name}.md")
    };
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("tests/fixtures")
        .join(file);
    fs::read_to_string(&path).unwrap_or_else(|err| panic!("read {}: {err}", path.display()))
}

fn render_fixture(name: &str) -> md2wx::Rendered {
    let theme = Theme::blog();
    let options = RenderOptions::new(&theme);
    render(&fixture(name), &options).unwrap_or_else(|err| panic!("{name}: {err}"))
}

fn assert_snapshot(name: &str) {
    let rendered = render_fixture(name);
    assert_eq!(rendered.char_count, rendered.html.chars().count());
    assert_eq!(rendered.byte_len, rendered.html.len());
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("tests/fixtures")
        .join(format!("{name}.html"));
    if std::env::var_os("UPDATE_SNAPSHOTS").is_some() {
        fs::write(&path, &rendered.html).unwrap();
    }
    let expected = fs::read_to_string(&path).unwrap_or_else(|_| String::new());
    assert_eq!(rendered.html, expected, "snapshot {name}");
}

#[test]
fn theme_matches_blog_tokens() {
    let theme = Theme::blog();
    assert_eq!(theme.accent, "#2a5a86");
    assert_eq!(theme.ink, "#1b2430");
    assert_eq!(theme.muted, "#4f5f70");
    assert_eq!(theme.line, "#c5d1de");
    assert_eq!(theme.surface, "#f2f6fa");
    assert_eq!(theme.canvas, "#e6edf4");
}

#[test]
fn headings() {
    assert_snapshot("headings");
}

#[test]
fn paragraphs() {
    assert_snapshot("paragraphs");
}

#[test]
fn lists() {
    assert_snapshot("lists");
}

#[test]
fn quote() {
    assert_snapshot("quote");
}

#[test]
fn code() {
    let rendered = render_fixture("code");
    assert!(rendered.html.contains("&nbsp;&nbsp;keep"));
    assert!(rendered.html.contains("overflow-x:auto"));
    assert!(rendered.html.contains("display:block"));
    assert_snapshot("code");
}

#[test]
fn table() {
    let rendered = render_fixture("table");
    assert!(rendered.html.contains("<th"));
    assert!(rendered.html.contains("text-align:right"));
    assert_snapshot("table");
}

#[test]
fn image_placeholder_and_base_url() {
    let rendered = render_fixture("image");
    assert_eq!(rendered.images.len(), 1);
    assert_eq!(rendered.images[0].path, "./cover.png");
    assert_eq!(rendered.images[0].placeholder, "{{wx-img:0}}");
    assert!(rendered.html.contains("src=\"{{wx-img:0}}\""));
    assert!(rendered.html.contains("alt=\"封面\""));
    assert_snapshot("image");

    let theme = Theme::blog();
    let options = RenderOptions {
        theme: &theme,
        image_base_url: "https://example.com/wx/demo/img",
    };
    let rendered = render(&fixture("image.md"), &options).unwrap();
    assert!(rendered
        .html
        .contains("src=\"https://example.com/wx/demo/img/cover.png\""));
    assert!(!rendered.html.contains("{{wx-img:"));
    assert_eq!(rendered.images[0].placeholder, "{{wx-img:0}}");
}

#[test]
fn links_become_footnotes() {
    let rendered = render_fixture("links");
    assert!(!rendered.html.contains("href="));
    assert!(rendered.html.contains("参考链接"));
    assert!(rendered.html.contains("[1] https://example.com/docs"));
    assert!(rendered.html.contains("[2] https://example.com/auto"));
    assert_eq!(rendered.html.matches("[1]").count(), 3);
    assert_snapshot("links");
}

#[test]
fn mermaid_and_html_are_errors() {
    let theme = Theme::blog();
    let options = RenderOptions::new(&theme);
    let mermaid = render("```mermaid\ngraph TD\n```\n", &options).unwrap_err();
    let message = mermaid.to_string();
    assert!(message.contains("diagram"), "{message}");

    let html = render("<script>alert(1)</script>\n", &options).unwrap_err();
    assert!(html.to_string().contains("html"), "{html}");
}

#[test]
fn lengths_match_the_fragment() {
    let rendered = render_fixture("paragraphs");
    assert!(rendered.char_count < 20_000);
    assert!(rendered.byte_len < 1_000_000);
    assert!(rendered.html.starts_with("<section"));
}
