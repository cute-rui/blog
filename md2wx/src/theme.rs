/// Inline colors for the WeChat fragment.
///
/// [`Theme::blog`] matches the light tokens in `src/styles/global.css`.
/// WeChat articles are read on a white page, so the renderer uses the light
/// palette even when the site is in dark mode.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Theme {
    pub accent: String,
    pub ink: String,
    pub muted: String,
    pub line: String,
    pub surface: String,
    pub canvas: String,
}

impl Theme {
    /// Light tokens from the blog stylesheet (`--accent` and its neighbors).
    pub fn blog() -> Self {
        Self {
            accent: "#2a5a86".to_string(),
            ink: "#1b2430".to_string(),
            muted: "#4f5f70".to_string(),
            line: "#c5d1de".to_string(),
            surface: "#f2f6fa".to_string(),
            canvas: "#e6edf4".to_string(),
        }
    }
}
