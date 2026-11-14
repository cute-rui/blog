//! Markdown to WeChat-ready HTML.
//!
//! [`render`] is a pure function: it does not read files, write files, or
//! open network connections. Callers upload images and assemble copy pages.

#![forbid(unsafe_code)]

mod render;
mod theme;

pub use render::{render, ImageRef, RenderError, RenderOptions, Rendered};
pub use theme::Theme;
