use std::fs;
use std::path::PathBuf;

use md2wx_android::{blog_theme, render, RenderError, RenderOptions};

fn fixture(name: &str) -> String {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../tests/fixtures")
        .join(name);
    fs::read_to_string(&path).unwrap_or_else(|err| panic!("read {}: {err}", path.display()))
}

fn blog_options(image_base_url: &str) -> RenderOptions {
    RenderOptions {
        theme: blog_theme(),
        image_base_url: image_base_url.to_string(),
    }
}

#[test]
fn blog_theme_matches_the_library() {
    let theme = blog_theme();
    assert_eq!(theme.accent, "#2a5a86");
    assert_eq!(theme.ink, "#1b2430");
    assert_eq!(theme.muted, "#4f5f70");
    assert_eq!(theme.line, "#c5d1de");
    assert_eq!(theme.surface, "#f2f6fa");
    assert_eq!(theme.canvas, "#e6edf4");
}

#[test]
fn fixtures_match_the_committed_snapshots() {
    for name in [
        "headings",
        "paragraphs",
        "lists",
        "quote",
        "code",
        "table",
        "image",
        "links",
    ] {
        let rendered = render(fixture(&format!("{name}.md")), blog_options("")).unwrap();
        assert_eq!(rendered.html, fixture(&format!("{name}.html")), "{name}");
        assert_eq!(rendered.char_count, rendered.html.chars().count() as u64);
        assert_eq!(rendered.byte_len, rendered.html.len() as u64);
    }
}

#[test]
fn image_base_url_matches_the_library() {
    let markdown = fixture("image.md");
    let via_ffi = render(markdown.clone(), blog_options("https://example.com/wx/demo/img")).unwrap();
    let theme = md2wx::Theme::blog();
    let via_lib = md2wx::render(
        &markdown,
        &md2wx::RenderOptions {
            theme: &theme,
            image_base_url: "https://example.com/wx/demo/img",
        },
    )
    .unwrap();
    assert_eq!(via_ffi.html, via_lib.html);
    assert_eq!(via_ffi.images.len(), 1);
    assert_eq!(via_ffi.images[0].path, "./cover.png");
    assert_eq!(via_ffi.images[0].placeholder, "{{wx-img:0}}");
    assert!(!via_ffi.html.contains("{{wx-img:"));
}

#[test]
fn mermaid_is_the_same_error_as_the_library() {
    let err = render("```mermaid\ngraph TD\n```\n".to_string(), blog_options("")).unwrap_err();
    match err {
        RenderError::Unsupported { kind, detail } => {
            assert_eq!(kind, "diagram");
            assert!(detail.contains("mermaid"), "{detail}");
        }
        other => panic!("unexpected {other:?}"),
    }
}

#[test]
fn kotlin_bindings_expose_the_render_api() {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../bindings/kotlin/uniffi/md2wx/md2wx.kt");
    let kotlin = fs::read_to_string(&path).unwrap_or_else(|err| panic!("read {}: {err}", path.display()));
    assert!(kotlin.contains("fun `render`("), "{path:?}");
    assert!(kotlin.contains("fun `blogTheme`("));
    assert!(kotlin.contains("imageBaseUrl"));
    assert!(kotlin.contains("charCount"));
    assert!(kotlin.contains("byteLen"));
    assert!(kotlin.contains("findLibraryName("));
    assert!(kotlin.contains("\"md2wx_android\""));
}
