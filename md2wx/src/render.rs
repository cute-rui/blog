//! Event walk from `pulldown-cmark` to a WeChat HTML fragment.

use std::collections::HashMap;
use std::sync::OnceLock;

use pulldown_cmark::{Alignment, CodeBlockKind, Event, HeadingLevel, Options, Parser, Tag, TagEnd};
use syntect::easy::HighlightLines;
use syntect::highlighting::{Style, ThemeSet};
use syntect::parsing::{SyntaxReference, SyntaxSet};

use crate::Theme;

/// One image the caller still has to place.
///
/// `placeholder` is the `src` token used when [`RenderOptions::image_base_url`]
/// is empty (`{{wx-img:N}}`). `path` is the destination as written in Markdown.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize)]
pub struct ImageRef {
    pub placeholder: String,
    pub path: String,
}

/// Options for one [`render`] call. The theme is borrowed; nothing is loaded
/// from disk here (syntax definitions are embedded in the binary).
pub struct RenderOptions<'a> {
    pub theme: &'a Theme,
    /// When empty, each local image `src` is its [`ImageRef::placeholder`].
    /// Otherwise `src` is `{image_base_url}/{file name}` and the placeholder
    /// stays in [`Rendered::images`] for the caller that already knows the name.
    pub image_base_url: &'a str,
}

impl<'a> RenderOptions<'a> {
    pub fn new(theme: &'a Theme) -> Self {
        Self {
            theme,
            image_base_url: "",
        }
    }
}

/// HTML fragment plus the image list and the lengths WeChat limits use.
#[derive(Debug)]
pub struct Rendered {
    /// A single `<section>` of inline-styled HTML. Not a full document.
    pub html: String,
    pub images: Vec<ImageRef>,
    /// Unicode scalar count of `html`. Compared with the 20_000 character cap.
    pub char_count: usize,
    /// UTF-8 byte length of `html`. Compared with the 1 MB cap.
    pub byte_len: usize,
}

/// Failure from [`render`]. The detail is safe to show to the author.
#[derive(Debug)]
pub enum RenderError {
    Unsupported { kind: &'static str, detail: String },
    Highlight(String),
    Theme(String),
}

impl std::fmt::Display for RenderError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            RenderError::Unsupported { kind, detail } => write!(f, "{kind}: {detail}"),
            RenderError::Highlight(detail) => write!(f, "highlight: {detail}"),
            RenderError::Theme(detail) => write!(f, "theme: {detail}"),
        }
    }
}

impl std::error::Error for RenderError {}

/// Render `markdown` to WeChat inline HTML.
///
/// The input is the Markdown body only. YAML front matter is not stripped.
/// Enabled syntax is CommonMark plus GFM tables, strikethrough, task lists,
/// and footnotes. Math, Mermaid, raw HTML, and other extensions return
/// [`RenderError::Unsupported`] instead of being dropped.
pub fn render(markdown: &str, options: &RenderOptions<'_>) -> Result<Rendered, RenderError> {
    validate_theme(options.theme)?;
    let mut renderer = Renderer::new(options);
    let parser = Parser::new_ext(markdown, parser_options());
    for event in parser {
        renderer.on_event(event)?;
    }
    renderer.finish()
}

fn parser_options() -> Options {
    let mut options = Options::empty();
    options.insert(Options::ENABLE_TABLES);
    options.insert(Options::ENABLE_FOOTNOTES);
    options.insert(Options::ENABLE_STRIKETHROUGH);
    options.insert(Options::ENABLE_TASKLISTS);
    options
}

fn validate_theme(theme: &Theme) -> Result<(), RenderError> {
    for (name, value) in [
        ("accent", theme.accent.as_str()),
        ("ink", theme.ink.as_str()),
        ("muted", theme.muted.as_str()),
        ("line", theme.line.as_str()),
        ("surface", theme.surface.as_str()),
        ("canvas", theme.canvas.as_str()),
    ] {
        if !is_hex_color(value) {
            return Err(RenderError::Theme(format!(
                "{name} 必须是 #RGB、#RRGGBB 或 #RRGGBBAA，收到 {value}"
            )));
        }
    }
    Ok(())
}

fn is_hex_color(value: &str) -> bool {
    let Some(hex) = value.strip_prefix('#') else {
        return false;
    };
    matches!(hex.len(), 3 | 6 | 8) && hex.bytes().all(|byte| byte.is_ascii_hexdigit())
}

struct Renderer<'a> {
    theme: &'a Theme,
    image_base: &'a str,
    stack: Vec<Frame>,
    images: Vec<ImageRef>,
    link_notes: Vec<String>,
    footnote_order: Vec<String>,
    footnote_bodies: HashMap<String, String>,
}

struct Frame {
    kind: Kind,
    html: String,
}

enum Kind {
    Root,
    Paragraph {
        in_list: bool,
        in_quote: bool,
    },
    Heading(u8),
    Quote,
    List {
        ordered: bool,
        start: u64,
    },
    Item,
    Code {
        lang: String,
    },
    Table {
        alignments: Vec<Alignment>,
        head: bool,
        col: usize,
    },
    Row,
    Cell {
        header: bool,
        align: &'static str,
    },
    Link {
        href: String,
    },
    Image {
        path: String,
    },
    Emphasis,
    Strong,
    Strike,
    Footnote(String),
}

impl<'a> Renderer<'a> {
    fn new(options: &'a RenderOptions<'a>) -> Self {
        Self {
            theme: options.theme,
            image_base: options.image_base_url,
            stack: vec![Frame {
                kind: Kind::Root,
                html: String::new(),
            }],
            images: Vec::new(),
            link_notes: Vec::new(),
            footnote_order: Vec::new(),
            footnote_bodies: HashMap::new(),
        }
    }

    fn on_event(&mut self, event: Event<'_>) -> Result<(), RenderError> {
        match event {
            Event::Start(tag) => self.start_tag(tag),
            Event::End(tag) => self.end_tag(tag),
            Event::Text(text) => {
                self.push_text(&text);
                Ok(())
            }
            Event::Code(code) => {
                let body = escape_code_fragment(&code);
                let style = format!(
                    "font-family:ui-monospace,Menlo,Consolas,monospace;font-size:0.92em;background:{surface};padding:0 4px;border-radius:4px;",
                    surface = self.theme.surface
                );
                self.push_html(&format!("<code style=\"{style}\">{body}</code>"));
                Ok(())
            }
            Event::SoftBreak => {
                if self.top_is_code() {
                    self.push_raw("\n");
                } else {
                    self.push_raw(" ");
                }
                Ok(())
            }
            Event::HardBreak => {
                self.push_html("<br />");
                Ok(())
            }
            Event::Rule => {
                let style = format!(
                    "border:none;border-top:1px solid {line};margin:20px 0;",
                    line = self.theme.line
                );
                self.push_html(&format!("<hr style=\"{style}\" />"));
                Ok(())
            }
            Event::TaskListMarker(checked) => {
                let mark = if checked { "☑" } else { "☐" };
                self.push_html(&format!("{mark} "));
                Ok(())
            }
            Event::FootnoteReference(name) => {
                let number = self.footnote_number(&name);
                let style = format!(
                    "font-size:12px;line-height:0;color:{accent};",
                    accent = self.theme.accent
                );
                self.push_html(&format!("<sup style=\"{style}\">[注{number}]</sup>"));
                Ok(())
            }
            Event::Html(_) | Event::InlineHtml(_) => {
                Err(unsupported("html", "正文只接受 Markdown，不接受内嵌 HTML"))
            }
            Event::InlineMath(_) | Event::DisplayMath(_) => Err(unsupported(
                "math",
                "数学公式不能直接放进公众号，先渲染成图片",
            )),
        }
    }

    fn start_tag(&mut self, tag: Tag<'_>) -> Result<(), RenderError> {
        if self.top_is_code() {
            return Err(unsupported("parser", "代码块里出现了嵌套结构"));
        }
        let kind = match tag {
            Tag::Paragraph => {
                let in_list = self.in_list();
                let in_quote = self.in_quote();
                Kind::Paragraph { in_list, in_quote }
            }
            Tag::Heading { level, .. } => Kind::Heading(heading_number(level)),
            Tag::BlockQuote(_) => Kind::Quote,
            Tag::CodeBlock(kind) => {
                let lang = match kind {
                    CodeBlockKind::Indented => String::new(),
                    CodeBlockKind::Fenced(info) => {
                        info.split_whitespace().next().unwrap_or("").to_string()
                    }
                };
                reject_diagram_lang(&lang)?;
                Kind::Code { lang }
            }
            Tag::List(start) => Kind::List {
                ordered: start.is_some(),
                start: start.unwrap_or(1),
            },
            Tag::Item => Kind::Item,
            Tag::Table(alignments) => Kind::Table {
                alignments,
                head: false,
                col: 0,
            },
            Tag::TableHead => {
                // The header is not wrapped in a TableRow event. Open the row here.
                self.set_table_head(true);
                self.reset_table_col();
                self.stack.push(Frame {
                    kind: Kind::Row,
                    html: String::new(),
                });
                return Ok(());
            }
            Tag::TableRow => {
                self.reset_table_col();
                Kind::Row
            }
            Tag::TableCell => {
                let (header, align) = self.take_cell_meta();
                Kind::Cell { header, align }
            }
            Tag::Emphasis => Kind::Emphasis,
            Tag::Strong => Kind::Strong,
            Tag::Strikethrough => Kind::Strike,
            Tag::Link { dest_url, .. } => Kind::Link {
                href: dest_url.to_string(),
            },
            Tag::Image { dest_url, .. } => Kind::Image {
                path: dest_url.to_string(),
            },
            Tag::FootnoteDefinition(name) => Kind::Footnote(name.to_string()),
            Tag::HtmlBlock => {
                return Err(unsupported("html", "正文只接受 Markdown，不接受内嵌 HTML"))
            }
            Tag::MetadataBlock(_) => {
                return Err(unsupported("metadata", "正文里的元数据块不会进入公众号"))
            }
            Tag::DefinitionList | Tag::DefinitionListTitle | Tag::DefinitionListDefinition => {
                return Err(unsupported(
                    "definition-list",
                    "定义列表不在公众号支持的语法里",
                ))
            }
        };
        self.stack.push(Frame {
            kind,
            html: String::new(),
        });
        Ok(())
    }

    fn end_tag(&mut self, tag: TagEnd) -> Result<(), RenderError> {
        match tag {
            TagEnd::TableHead => {
                self.pop_frame()?;
                self.set_table_head(false);
                Ok(())
            }
            TagEnd::HtmlBlock => Err(unsupported("html", "正文只接受 Markdown，不接受内嵌 HTML")),
            TagEnd::MetadataBlock(_) => {
                Err(unsupported("metadata", "正文里的元数据块不会进入公众号"))
            }
            TagEnd::DefinitionList
            | TagEnd::DefinitionListTitle
            | TagEnd::DefinitionListDefinition => Err(unsupported(
                "definition-list",
                "定义列表不在公众号支持的语法里",
            )),
            _ => self.pop_frame(),
        }
    }

    fn pop_frame(&mut self) -> Result<(), RenderError> {
        let frame = self
            .stack
            .pop()
            .ok_or_else(|| unsupported("parser", "标签不配对"))?;
        let piece = self.render_frame(frame)?;
        if let Some(piece) = piece {
            self.stack
                .last_mut()
                .ok_or_else(|| unsupported("parser", "缺少根节点"))?
                .html
                .push_str(&piece);
        }
        Ok(())
    }

    fn render_frame(&mut self, frame: Frame) -> Result<Option<String>, RenderError> {
        let theme = self.theme;
        let Frame { kind, html } = frame;
        let piece = match kind {
            Kind::Root => return Err(unsupported("parser", "根节点被提前关闭")),
            Kind::Footnote(name) => {
                self.footnote_bodies.insert(name, html);
                return Ok(None);
            }
            Kind::Paragraph { in_list, in_quote } => {
                let margin = if in_list { "0 0 8px" } else { "0 0 16px" };
                let color = if in_quote { &theme.muted } else { &theme.ink };
                format!(
                    "<p style=\"margin:{margin};font-size:16px;line-height:1.75;color:{color};\">{html}</p>"
                )
            }
            Kind::Heading(level) => {
                let (size, margin) = heading_metrics(level);
                format!(
                    "<h{level} style=\"margin:{margin};font-size:{size};line-height:1.35;font-weight:700;color:{ink};\">{html}</h{level}>",
                    ink = theme.ink
                )
            }
            Kind::Quote => format!(
                "<blockquote style=\"margin:0 0 16px;padding:8px 14px;border-left:3px solid {accent};background:{surface};color:{muted};\">{html}</blockquote>",
                accent = theme.accent,
                surface = theme.surface,
                muted = theme.muted
            ),
            Kind::List { ordered, start } => {
                let style = format!(
                    "margin:0 0 16px;padding-left:1.4em;color:{ink};",
                    ink = theme.ink
                );
                if ordered && start != 1 {
                    format!("<ol start=\"{start}\" style=\"{style}\">{html}</ol>")
                } else if ordered {
                    format!("<ol style=\"{style}\">{html}</ol>")
                } else {
                    format!("<ul style=\"{style}\">{html}</ul>")
                }
            }
            Kind::Item => format!(
                "<li style=\"margin:0 0 6px;font-size:16px;line-height:1.75;color:{ink};\">{html}</li>",
                ink = theme.ink
            ),
            Kind::Code { lang } => render_code(&lang, &html, theme)?,
            Kind::Table { .. } => format!(
                "<section style=\"overflow-x:auto;margin:0 0 16px;\"><table style=\"border-collapse:collapse;width:100%;color:{ink};\">{html}</table></section>",
                ink = theme.ink
            ),
            Kind::Row => format!("<tr>{html}</tr>"),
            Kind::Cell { header, align } => {
                let tag = if header { "th" } else { "td" };
                let bg = if header {
                    format!("background:{};", theme.surface)
                } else {
                    String::new()
                };
                format!(
                    "<{tag} style=\"border:1px solid {line};padding:6px 8px;text-align:{align};{bg}font-size:15px;line-height:1.6;color:{ink};\">{html}</{tag}>",
                    line = theme.line,
                    ink = theme.ink
                )
            }
            Kind::Emphasis => format!("<em style=\"font-style:italic;\">{html}</em>"),
            Kind::Strong => format!("<strong style=\"font-weight:700;\">{html}</strong>"),
            Kind::Strike => format!("<del style=\"text-decoration:line-through;\">{html}</del>"),
            Kind::Link { href } => {
                let label = if html.is_empty() {
                    escape_text(&href)
                } else {
                    html
                };
                if href.is_empty() {
                    label
                } else {
                    let number = self.link_number(&href);
                    let style = format!(
                        "font-size:12px;line-height:0;color:{accent};",
                        accent = theme.accent
                    );
                    format!("{label}<sup style=\"{style}\">[{number}]</sup>")
                }
            }
            Kind::Image { path } => self.render_image(&path, &html)?,
        };
        Ok(Some(piece))
    }

    fn render_image(&mut self, path: &str, alt_html: &str) -> Result<String, RenderError> {
        if path.is_empty() {
            return Err(unsupported("image", "图片缺少地址"));
        }
        let lower = path.to_ascii_lowercase();
        if lower.starts_with("data:") {
            return Err(unsupported(
                "image",
                "不接受 data: 图片，请改成文章目录里的文件",
            ));
        }
        let alt = escape_attr(&strip_tags(alt_html));
        let src = if lower.starts_with("https://") || lower.starts_with("http://") {
            escape_attr(path)
        } else {
            let index = self.images.len();
            let placeholder = format!("{{{{wx-img:{index}}}}}");
            let src = if self.image_base.is_empty() {
                placeholder.clone()
            } else {
                let name = file_name(path);
                format!("{}/{}", self.image_base.trim_end_matches('/'), name)
            };
            self.images.push(ImageRef {
                placeholder,
                path: path.to_string(),
            });
            escape_attr(&src)
        };
        Ok(format!(
            "<img src=\"{src}\" alt=\"{alt}\" style=\"max-width:100%;height:auto;display:block;margin:0 auto 16px;\" />"
        ))
    }

    fn finish(mut self) -> Result<Rendered, RenderError> {
        if self.stack.len() != 1 {
            return Err(unsupported("parser", "文档结束时还有未闭合的块"));
        }
        let body = self.stack.pop().expect("root").html;
        let notes = self.notes_html();
        let html = format!(
            "<section style=\"font-size:16px;line-height:1.75;color:{ink};\">{body}{notes}</section>",
            ink = self.theme.ink
        );
        let char_count = html.chars().count();
        let byte_len = html.len();
        Ok(Rendered {
            html,
            images: self.images,
            char_count,
            byte_len,
        })
    }

    fn notes_html(&self) -> String {
        let mut out = String::new();
        if !self.link_notes.is_empty() {
            let mut items = String::new();
            for (index, href) in self.link_notes.iter().enumerate() {
                items.push_str(&format!(
                    "<p style=\"margin:0 0 6px;font-size:14px;line-height:1.6;color:{muted};word-break:break-all;\">[{n}] {href}</p>",
                    muted = self.theme.muted,
                    n = index + 1,
                    href = escape_text(href)
                ));
            }
            out.push_str(&self.note_section("参考链接", &items));
        }
        if !self.footnote_order.is_empty() {
            let mut items = String::new();
            for (index, name) in self.footnote_order.iter().enumerate() {
                let body = self
                    .footnote_bodies
                    .get(name)
                    .map(String::as_str)
                    .unwrap_or("（未定义）");
                items.push_str(&format!(
                    "<div style=\"margin:0 0 8px;font-size:14px;line-height:1.6;color:{muted};\">[注{n}] {body}</div>",
                    muted = self.theme.muted,
                    n = index + 1
                ));
            }
            out.push_str(&self.note_section("脚注", &items));
        }
        out
    }

    fn note_section(&self, title: &str, items: &str) -> String {
        format!(
            "<section style=\"margin:24px 0 0;padding-top:12px;border-top:1px solid {line};\"><p style=\"margin:0 0 8px;font-size:15px;font-weight:700;color:{ink};\">{title}</p>{items}</section>",
            line = self.theme.line,
            ink = self.theme.ink
        )
    }

    fn link_number(&mut self, href: &str) -> usize {
        if let Some(index) = self.link_notes.iter().position(|note| note == href) {
            return index + 1;
        }
        self.link_notes.push(href.to_string());
        self.link_notes.len()
    }

    fn footnote_number(&mut self, name: &str) -> usize {
        if let Some(index) = self.footnote_order.iter().position(|note| note == name) {
            return index + 1;
        }
        self.footnote_order.push(name.to_string());
        self.footnote_order.len()
    }

    fn in_list(&self) -> bool {
        self.stack
            .iter()
            .any(|frame| matches!(frame.kind, Kind::List { .. } | Kind::Item))
    }

    fn in_quote(&self) -> bool {
        self.stack
            .iter()
            .any(|frame| matches!(frame.kind, Kind::Quote))
    }

    fn top_is_code(&self) -> bool {
        matches!(
            self.stack.last().map(|frame| &frame.kind),
            Some(Kind::Code { .. })
        )
    }

    fn push_text(&mut self, text: &str) {
        if self.top_is_code() {
            self.push_raw(text);
        } else {
            let escaped = escape_text(text);
            self.push_raw(&escaped);
        }
    }

    fn push_html(&mut self, html: &str) {
        self.push_raw(html);
    }

    fn push_raw(&mut self, text: &str) {
        self.stack
            .last_mut()
            .expect("root frame")
            .html
            .push_str(text);
    }

    fn set_table_head(&mut self, head: bool) {
        for frame in self.stack.iter_mut().rev() {
            if let Kind::Table { head: slot, .. } = &mut frame.kind {
                *slot = head;
                return;
            }
        }
    }

    fn reset_table_col(&mut self) {
        for frame in self.stack.iter_mut().rev() {
            if let Kind::Table { col, .. } = &mut frame.kind {
                *col = 0;
                return;
            }
        }
    }

    fn take_cell_meta(&mut self) -> (bool, &'static str) {
        for frame in self.stack.iter_mut().rev() {
            if let Kind::Table {
                alignments,
                head,
                col,
            } = &mut frame.kind
            {
                let align = alignments.get(*col).copied().unwrap_or(Alignment::None);
                *col += 1;
                return (*head, align_css(align));
            }
        }
        (false, "left")
    }
}

fn heading_number(level: HeadingLevel) -> u8 {
    match level {
        HeadingLevel::H1 => 1,
        HeadingLevel::H2 => 2,
        HeadingLevel::H3 => 3,
        HeadingLevel::H4 => 4,
        HeadingLevel::H5 => 5,
        HeadingLevel::H6 => 6,
    }
}

fn heading_metrics(level: u8) -> (&'static str, &'static str) {
    match level {
        1 => ("22px", "0 0 16px"),
        2 => ("20px", "22px 0 10px"),
        3 => ("18px", "18px 0 8px"),
        4 => ("17px", "16px 0 8px"),
        5 => ("16px", "14px 0 6px"),
        _ => ("15px", "12px 0 6px"),
    }
}

fn align_css(alignment: Alignment) -> &'static str {
    match alignment {
        Alignment::None | Alignment::Left => "left",
        Alignment::Center => "center",
        Alignment::Right => "right",
    }
}

fn reject_diagram_lang(lang: &str) -> Result<(), RenderError> {
    const DENIED: &[&str] = &["mermaid", "math", "latex", "katex", "tex", "plantuml"];
    let token = lang.to_ascii_lowercase();
    if DENIED.contains(&token.as_str()) {
        return Err(unsupported(
            "diagram",
            format!("代码块语言 {token} 不能直接放进公众号，先渲染成图片"),
        ));
    }
    Ok(())
}

fn unsupported(kind: &'static str, detail: impl Into<String>) -> RenderError {
    RenderError::Unsupported {
        kind,
        detail: detail.into(),
    }
}

fn file_name(path: &str) -> &str {
    path.rsplit(['/', '\\']).next().unwrap_or(path)
}

fn escape_text(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for ch in text.chars() {
        match ch {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            other => out.push(other),
        }
    }
    out
}

fn escape_attr(text: &str) -> String {
    escape_text(text).replace('"', "&quot;")
}

fn escape_code_fragment(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for ch in text.chars() {
        match ch {
            ' ' => out.push_str("&nbsp;"),
            '\t' => out.push_str("&nbsp;&nbsp;&nbsp;&nbsp;"),
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '\n' | '\r' => {}
            other => out.push(other),
        }
    }
    if out.is_empty() {
        out.push_str("&nbsp;");
    }
    out
}

fn strip_tags(html: &str) -> String {
    let mut out = String::new();
    let mut in_tag = false;
    for ch in html.chars() {
        match ch {
            '<' => in_tag = true,
            '>' => in_tag = false,
            _ if !in_tag => out.push(ch),
            _ => {}
        }
    }
    out
}

fn render_code(lang: &str, text: &str, theme: &Theme) -> Result<String, RenderError> {
    let lines = split_code_lines(text);
    let (syntax_set, highlight_theme) = syntax_assets();
    let syntax = syntax_for(syntax_set, lang);
    let mut body = String::new();
    if let Some(syntax) = syntax {
        let mut highlighter = HighlightLines::new(syntax, highlight_theme);
        for line in lines {
            body.push_str(&highlight_line(&mut highlighter, syntax_set, line, theme)?);
        }
    } else {
        for line in lines {
            body.push_str(&plain_line(line, theme));
        }
    }
    Ok(format!(
        "<section style=\"overflow-x:auto;margin:0 0 16px;background:{surface};border:1px solid {line};border-radius:6px;\"><pre style=\"margin:0;padding:12px 14px;\"><code style=\"font-family:ui-monospace,Menlo,Consolas,monospace;font-size:13px;line-height:1.6;\">{body}</code></pre></section>",
        surface = theme.surface,
        line = theme.line
    ))
}

fn split_code_lines(text: &str) -> Vec<&str> {
    let text = text.strip_suffix('\n').unwrap_or(text);
    if text.is_empty() {
        vec![""]
    } else {
        text.split('\n').collect()
    }
}

fn plain_line(line: &str, theme: &Theme) -> String {
    format!(
        "<span style=\"display:block;color:{ink};\">{text}</span>",
        ink = theme.ink,
        text = escape_code_fragment(line)
    )
}

fn highlight_line(
    highlighter: &mut HighlightLines<'_>,
    syntax_set: &SyntaxSet,
    line: &str,
    theme: &Theme,
) -> Result<String, RenderError> {
    let mut owned = String::with_capacity(line.len() + 1);
    owned.push_str(line);
    owned.push('\n');
    let ranges = highlighter
        .highlight_line(&owned, syntax_set)
        .map_err(|err| RenderError::Highlight(err.to_string()))?;
    let mut inner = String::new();
    for (style, fragment) in ranges {
        let fragment = fragment.trim_end_matches(['\n', '\r']);
        if fragment.is_empty() {
            continue;
        }
        inner.push_str(&format!(
            "<span style=\"color:{color};\">{text}</span>",
            color = foreground_css(style, &theme.ink),
            text = escape_code_fragment(fragment)
        ));
    }
    if inner.is_empty() {
        inner.push_str("&nbsp;");
    }
    Ok(format!("<span style=\"display:block;\">{inner}</span>"))
}

fn foreground_css(style: Style, fallback: &str) -> String {
    let color = style.foreground;
    if color.a == 0 {
        fallback.to_string()
    } else {
        format!("#{:02x}{:02x}{:02x}", color.r, color.g, color.b)
    }
}

fn syntax_for<'b>(syntax_set: &'b SyntaxSet, lang: &str) -> Option<&'b SyntaxReference> {
    let token = lang.split_whitespace().next().unwrap_or("");
    if token.is_empty() {
        None
    } else {
        syntax_set.find_syntax_by_token(token)
    }
}

fn syntax_assets() -> &'static (SyntaxSet, syntect::highlighting::Theme) {
    static ASSETS: OnceLock<(SyntaxSet, syntect::highlighting::Theme)> = OnceLock::new();
    ASSETS.get_or_init(|| {
        let syntax_set = SyntaxSet::load_defaults_newlines();
        let theme_set = ThemeSet::load_defaults();
        let theme = theme_set
            .themes
            .get("InspiredGitHub")
            .cloned()
            .or_else(|| theme_set.themes.into_values().next())
            .expect("syntect default themes include InspiredGitHub");
        (syntax_set, theme)
    })
}
