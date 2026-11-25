//! The "Lanes" design system, ported 1:1 from the Android app
//! (ui/theme/LanesTheme.kt + ui/components/LanesComponents.kt): same
//! palette in light and dark, same radii, same components — screen
//! header, surface cards, badges, section labels, and the signature
//! segmented transfer ring whose arcs are the download's segments.

use rune::color::hex;
use rune::{canvas, col, el, row, text, Color, El, Family, LineCap, Shape, TextStyle};

fn c(v: &str) -> Color {
    hex(v).unwrap_or(Color { r: 0.0, g: 0.0, b: 0.0, a: 1.0 })
}

// ------------------------------------------------------------------ palette

#[derive(Clone, Copy)]
pub struct Palette {
    pub canvas: Color,
    pub surface: Color,
    pub raised: Color,
    pub hairline: Color,
    pub ink: Color,
    pub ink2: Color,
    pub ink3: Color,
    pub accent: Color,
    pub on_accent: Color,
    pub accent_container: Color,
    pub on_accent_container: Color,
    pub paused: Color,
    pub paused_container: Color,
    pub failed: Color,
    pub failed_container: Color,
    pub done: Color,
}

pub fn palette(dark: bool) -> Palette {
    if dark {
        Palette {
            canvas: c("#101214"),
            surface: c("#17191C"),
            raised: c("#202327"),
            hairline: c("#2A2E32"),
            ink: c("#ECEFEF"),
            ink2: c("#A5ADAC"),
            ink3: c("#8A9392"),
            accent: c("#8E98FF"),
            on_accent: c("#0B1038"),
            accent_container: c("#1E2470"),
            on_accent_container: c("#D9DDFF"),
            paused: c("#F2B632"),
            paused_container: c("#493A19"),
            failed: c("#FF7B72"),
            failed_container: c("#4B211F"),
            done: c("#4CC380"),
        }
    } else {
        Palette {
            canvas: c("#F2F4F3"),
            surface: c("#FFFFFF"),
            raised: c("#E7EBEA"),
            hairline: c("#D5DBD9"),
            ink: c("#14181A"),
            ink2: c("#4A5457"),
            ink3: c("#5F6A69"),
            accent: c("#2B39F0"),
            on_accent: c("#FFFFFF"),
            accent_container: c("#E1E4FF"),
            on_accent_container: c("#171F99"),
            paused: c("#8A5F00"),
            paused_container: c("#F2B632"),
            failed: c("#B8322C"),
            failed_container: c("#FFE3E0"),
            done: c("#1B7340"),
        }
    }
}

/// Subdued studio tones for poster-less fallback tiles (same six, same
/// hash-to-tone idea as the Android `fallbackPosterTone`).
pub fn poster_tone(title: &str) -> Color {
    const TONES: [&str; 6] = ["#102027", "#141B2D", "#1A182B", "#1C2321", "#211924", "#16222C"];
    let h: usize = title.bytes().map(|b| b as usize).sum();
    c(TONES[h % TONES.len()])
}

/// Up-to-two-letter initials for a fallback tile, as on Android.
pub fn initials(title: &str) -> String {
    let s: String = title
        .split_whitespace()
        .filter(|w| !w.is_empty())
        .take(2)
        .filter_map(|w| w.chars().next())
        .map(|ch| ch.to_uppercase().next().unwrap_or(ch))
        .collect();
    if s.is_empty() {
        "LF".to_string()
    } else {
        s
    }
}

// -------------------------------------------------------------------- fonts
// Anek Bangla (the Android app's family) is registered in main() from the
// bundled TTFs; these styles name it, falling back to sans if missing.

const ANEK: Family = Family::Named("Anek Bangla");

pub const BRAND: TextStyle = TextStyle::new(22.0, 600.0).family(ANEK).tracking(-0.4);
pub const TITLE: TextStyle = TextStyle::new(17.0, 600.0).family(ANEK);
pub const HEADLINE: TextStyle = TextStyle::new(14.0, 600.0).family(ANEK);
pub const LABEL: TextStyle = TextStyle::new(12.5, 500.0).family(ANEK);
pub const BODY: TextStyle = TextStyle::new(13.0, 400.0).family(ANEK);
pub const SMALL: TextStyle = TextStyle::new(11.5, 400.0).family(ANEK);
pub const SECTION: TextStyle = TextStyle::new(11.0, 600.0).family(ANEK).tracking(0.9);
pub const GLYPH: TextStyle = TextStyle::new(13.0, 600.0).family(ANEK);

// --------------------------------------------------------------- components

/// LanesSurface: rounded card on Surface with a hairline rule border.
/// (A column box — Rune's plain `el()` lays children out in a row.)
pub fn card(p: &Palette) -> El {
    col().fill(p.surface).radius(16.0).border(1.0, p.hairline)
}

/// LanesScreenHeader: title + optional subtitle, actions right, rule under.
pub fn screen_header(p: &Palette, title: El, subtitle: Option<String>, actions: Option<El>) -> El {
    let mut left = col().grow(1.0).gap(2.0).child(title);
    if let Some(sub) = subtitle {
        left = left.child(text(sub).font(SMALL).color(p.ink2));
    }
    let mut head = row().w_full().px(16.0).py(10.0).gap(8.0).child(left);
    if let Some(a) = actions {
        head = head.child(a);
    }
    col()
        .w_full()
        .fill(p.canvas)
        .child(head)
        .child(el().w_full().h(1.0).fill(p.hairline))
}

/// LanesSectionLabel: uppercase, muted, letter-spaced.
pub fn section_label(p: &Palette, s: &str) -> El {
    text(s.to_uppercase()).font(SECTION).color(p.ink3)
}

/// LanesBadge: small rounded label chip.
pub fn badge(p: &Palette, s: String, bg: Color, fg: Color) -> El {
    el()
        .radius(8.0)
        .fill(bg)
        .px(6.0)
        .py(2.0)
        .child(text(s).font(SMALL).color(fg))
}

/// Accent-filled pill button (primary action).
pub fn btn_primary<S: 'static>(label: &str, p: &Palette, f: impl Fn(&mut S) + 'static) -> El {
    el()
        .h(30.0)
        .px(14.0)
        .center()
        .radius(999.0)
        .fill(p.accent)
        .child(text(label).font(LABEL).color(p.on_accent))
        .on_press(f)
}

/// Raised neutral button (secondary action).
pub fn btn_tonal<S: 'static>(label: &str, p: &Palette, f: impl Fn(&mut S) + 'static) -> El {
    el()
        .h(30.0)
        .px(14.0)
        .center()
        .radius(999.0)
        .fill(p.raised)
        .child(text(label).font(LABEL).color(p.ink))
        .on_press(f)
}

/// A selectable choice chip (Settings): accent container when selected.
pub fn chip<S: 'static>(label: &str, selected: bool, p: &Palette, f: impl Fn(&mut S) + 'static) -> El {
    let (bg, fg) = if selected {
        (p.accent_container, p.on_accent_container)
    } else {
        (p.raised, p.ink2)
    };
    el()
        .h(30.0)
        .px(14.0)
        .center()
        .radius(999.0)
        .fill(bg)
        .child(text(label).font(LABEL).color(fg))
        .on_press(f)
}

/// Thin progress bar (per-lane bars in the expanded transfer view).
pub fn progress_bar(frac: f64, color: Color, track: Color) -> El {
    let f = frac.clamp(0.0, 1.0) as f32;
    row()
        .w_full()
        .h(5.0)
        .radius(3.0)
        .fill(track)
        .child(el().h(5.0).radius(3.0).fill(color).grow(f.max(0.0001)))
        .child(el().h(5.0).grow((1.0 - f).max(0.0001)))
}

// ---------------------------------------------------------- segmented ring

fn arc_path(cx: f32, cy: f32, r: f32, from_deg: f32, sweep_deg: f32) -> String {
    if sweep_deg <= 0.0 {
        return String::new();
    }
    let sweep = sweep_deg.min(359.9);
    let a0 = from_deg.to_radians();
    let a1 = (from_deg + sweep).to_radians();
    let (x0, y0) = (cx + r * a0.cos(), cy + r * a0.sin());
    let (x1, y1) = (cx + r * a1.cos(), cy + r * a1.sin());
    let large = if sweep > 180.0 { 1 } else { 0 };
    format!("M {x0:.2} {y0:.2} A {r:.2} {r:.2} 0 {large} 1 {x1:.2} {y1:.2}")
}

/// The signature SegmentedRing from the Android app: one arc per download
/// segment (6° gaps), each filled by that segment's own progress; a full
/// circle when completed; a 100° arc when the total size is unknown; the
/// state glyph (Ⅱ ▶ ↻ ✓ ↓ ·) sits in the middle. Tapping toggles
/// pause/resume/retry, exactly like Android.
///
/// `segments` is a list of (done, len) pairs; len may be u64::MAX for the
/// unknown-total single segment.
pub fn segmented_ring<S: 'static>(
    p: &Palette,
    state_color: Color,
    glyph: &str,
    segments: &[(u64, u64)],
    completed: bool,
    unknown_total: bool,
    on_press: impl Fn(&mut S) + 'static,
) -> El {
    const SIZE: f32 = 48.0;
    const STROKE: f32 = 3.0;
    let r = (SIZE - STROKE) / 2.0;
    let mid = SIZE / 2.0;
    let mut shape = Shape::new();
    if completed {
        shape = shape.stroke_svg(&arc_path(mid, mid, r, -90.0, 359.9), STROKE, LineCap::Round, state_color);
    } else if unknown_total {
        shape = shape
            .stroke_svg(&arc_path(mid, mid, r, 0.0, 359.9), STROKE, LineCap::Round, p.raised)
            .stroke_svg(&arc_path(mid, mid, r, -90.0, 100.0), STROKE, LineCap::Round, state_color);
    } else {
        let n = segments.len().max(1);
        let gap = if n == 1 { 0.0 } else { 6.0 };
        let step = 360.0 / n as f32;
        for i in 0..n {
            let start = -90.0 + i as f32 * step + gap / 2.0;
            let sweep = step - gap;
            shape = shape.stroke_svg(&arc_path(mid, mid, r, start, sweep), STROKE, LineCap::Round, p.raised);
            let fill = segments
                .get(i)
                .map(|(done, len)| {
                    if *len == 0 || *len == u64::MAX {
                        0.0
                    } else {
                        (*done as f32 / *len as f32).clamp(0.0, 1.0)
                    }
                })
                .unwrap_or(0.0);
            if fill > 0.0 {
                shape = shape.stroke_svg(
                    &arc_path(mid, mid, r, start, sweep * fill),
                    STROKE,
                    LineCap::Round,
                    state_color,
                );
            }
        }
    }
    el()
        .size(SIZE, SIZE)
        .child(canvas(shape.view_box(SIZE, SIZE)).size(SIZE, SIZE).absolute(0.0, 0.0))
        .child(
            el()
                .size(SIZE, SIZE)
                .absolute(0.0, 0.0)
                .center()
                .child(text(glyph).font(GLYPH).color(state_color)),
        )
        .on_press(on_press)
}
