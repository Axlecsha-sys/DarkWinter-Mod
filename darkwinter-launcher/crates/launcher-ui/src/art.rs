// Loads and renders the journal artwork and shared launcher drawing primitives.
use anyhow::{Context, Result};
use eframe::egui::{self, Color32, FontFamily, FontId, Rect, Stroke, TextureHandle, pos2, vec2};
use std::sync::Arc;

pub(crate) const INK: Color32 = Color32::from_rgb(8, 40, 75);
pub(crate) const MUTED: Color32 = Color32::from_rgb(57, 91, 122);
pub(crate) const BLUE: Color32 = Color32::from_rgb(8, 118, 203);
pub(crate) const LINE: Color32 = Color32::from_rgb(105, 146, 181);
pub(crate) const NAVY: Color32 = Color32::from_rgb(5, 35, 70);
pub(crate) const WHITE: Color32 = Color32::from_rgb(229, 243, 254);

pub(crate) struct DecodedArt {
    paper: egui::ColorImage,
    panorama: egui::ColorImage,
    wordmark: egui::ColorImage,
    serif: Option<Vec<u8>>,
}

pub(crate) struct JournalArt {
    paper: TextureHandle,
    panorama: TextureHandle,
    wordmark: TextureHandle,
    wordmark_uv: Rect,
    pub panorama_aspect: f32,
}

impl DecodedArt {
    /// Decode embedded PNGs and read an optional OS font before creating the window.
    pub fn load() -> Result<Self> {
        fn png(bytes: &[u8]) -> Result<egui::ColorImage> {
            let image = image::load_from_memory_with_format(bytes, image::ImageFormat::Png)
                .context("Не удалось прочитать ресурс оформления")?;
            // Bound upload size for integrated/older GPUs; source PNGs remain untouched.
            let image = if image.width() > 2048 || image.height() > 2048 {
                image.resize(2048, 2048, image::imageops::FilterType::Triangle)
            } else {
                image
            }
            .into_rgba8();
            Ok(egui::ColorImage::from_rgba_unmultiplied(
                [image.width() as usize, image.height() as usize],
                image.as_raw(),
            ))
        }
        let serif = std::env::var_os("SystemRoot").and_then(|root| {
            std::fs::read(std::path::PathBuf::from(root).join("Fonts/georgiab.ttf")).ok()
        });
        Ok(Self {
            paper: png(include_bytes!("../../../assets/journal-blue/paper.png"))?,
            panorama: png(include_bytes!("../../../assets/journal-blue/panorama.png"))?,
            wordmark: png(include_bytes!("../../../assets/journal-blue/wordmark.png"))?,
            serif,
        })
    }

    pub fn upload(self, ctx: &egui::Context) -> JournalArt {
        let mut fonts = egui::FontDefinitions::default();
        let fallback = fonts.families[&FontFamily::Proportional].clone();
        let mut serif_family = fallback;
        if let Some(bytes) = self.serif {
            fonts.font_data.insert(
                "journal-serif".into(),
                Arc::new(egui::FontData::from_owned(bytes)),
            );
            serif_family.insert(0, "journal-serif".into());
        }
        fonts
            .families
            .insert(FontFamily::Name("journal".into()), serif_family);
        ctx.set_fonts(fonts);
        let wordmark_uv = visible_uv(&self.wordmark);
        let panorama_aspect = self.panorama.size[0] as f32 / self.panorama.size[1] as f32;
        JournalArt {
            paper: ctx.load_texture("journal-paper", self.paper, egui::TextureOptions::LINEAR),
            panorama: ctx.load_texture(
                "journal-panorama",
                self.panorama,
                egui::TextureOptions::LINEAR,
            ),
            wordmark: ctx.load_texture(
                "journal-wordmark",
                self.wordmark,
                egui::TextureOptions::LINEAR,
            ),
            wordmark_uv,
            panorama_aspect,
        }
    }
}

fn visible_uv(image: &egui::ColorImage) -> Rect {
    let [width, height] = image.size;
    let (mut left, mut top, mut right, mut bottom) = (width, height, 0, 0);
    for (index, pixel) in image.pixels.iter().enumerate() {
        if pixel.a() > 8 {
            let (x, y) = (index % width, index / width);
            left = left.min(x);
            top = top.min(y);
            right = right.max(x + 1);
            bottom = bottom.max(y + 1);
        }
    }
    Rect::from_min_max(
        pos2(left as f32 / width as f32, top as f32 / height as f32),
        pos2(right as f32 / width as f32, bottom as f32 / height as f32),
    )
}

impl JournalArt {
    pub fn paper(&self, painter: &egui::Painter, bounds: Rect) {
        painter.image(
            self.paper.id(),
            bounds,
            Rect::from_min_max(pos2(0.0, 0.0), pos2(1.0, 1.0)),
            Color32::WHITE,
        );
    }

    pub fn wordmark(&self, painter: &egui::Painter, bounds: Rect) {
        let size = self.wordmark.size_vec2() * self.wordmark_uv.size();
        let scale = (bounds.width() / size.x).min(bounds.height() / size.y);
        let target = Rect::from_min_size(
            bounds.left_center() - vec2(0.0, size.y * scale * 0.5),
            size * scale,
        );
        painter.image(self.wordmark.id(), target, self.wordmark_uv, Color32::WHITE);
    }

    pub fn panorama(&self, painter: &egui::Painter, bounds: Rect) {
        // Preserve proportions at short window heights by cropping the texture view.
        let target_aspect = bounds.width() / bounds.height();
        let uv = if target_aspect > self.panorama_aspect {
            let height = self.panorama_aspect / target_aspect;
            Rect::from_min_max(
                pos2(0.0, (1.0 - height) * 0.58),
                pos2(1.0, (1.0 - height) * 0.58 + height),
            )
        } else {
            let width = target_aspect / self.panorama_aspect;
            Rect::from_min_max(
                pos2((1.0 - width) * 0.5, 0.0),
                pos2((1.0 + width) * 0.5, 1.0),
            )
        };
        painter.image(self.panorama.id(), bounds, uv, Color32::WHITE);
    }

    pub fn footer(&self, painter: &egui::Painter, bounds: Rect) {
        painter.rect_filled(bounds, 0.0, NAVY);
        painter.image(
            self.paper.id(),
            bounds,
            Rect::from_min_max(pos2(0.0, 0.7), pos2(1.0, 1.0)),
            Color32::from_rgba_unmultiplied(32, 84, 137, 48),
        );
        painter.line_segment(
            [bounds.left_top(), bounds.right_top()],
            Stroke::new(1.0, Color32::from_rgb(58, 101, 143)),
        );
    }
}

pub(crate) fn serif(size: f32) -> FontId {
    FontId::new(size, FontFamily::Name("journal".into()))
}

/// Decorative fictional contour pattern, with no real geographic coordinates.
pub(crate) fn contours(painter: &egui::Painter, bounds: Rect) {
    let painter = painter.with_clip_rect(bounds);
    for ring in 0..15 {
        let rx = 35.0 + ring as f32 * 12.0;
        let ry = 24.0 + ring as f32 * 7.0;
        let center = bounds.min + vec2(bounds.width() * 0.3, bounds.height() * 0.6);
        let points: Vec<_> = (0..=100)
            .map(|step| {
                let angle = step as f32 * std::f32::consts::TAU / 100.0;
                let wave = 1.0
                    + 0.10 * (angle * 3.0 + ring as f32 * 0.18).sin()
                    + 0.05 * (angle * 7.0).cos();
                center + vec2(rx * angle.cos() * wave, ry * angle.sin() * wave)
            })
            .collect();
        painter.add(egui::Shape::line(
            points,
            Stroke::new(0.65, Color32::from_rgba_unmultiplied(58, 113, 157, 40)),
        ));
    }
}

pub(crate) fn cross(painter: &egui::Painter, at: egui::Pos2, color: Color32) {
    painter.line_segment(
        [at - vec2(5.0, 0.0), at + vec2(5.0, 0.0)],
        Stroke::new(1.0, color),
    );
    painter.line_segment(
        [at - vec2(0.0, 5.0), at + vec2(0.0, 5.0)],
        Stroke::new(1.0, color),
    );
}

pub(crate) fn configure(ctx: &egui::Context) {
    ctx.set_theme(egui::Theme::Light);
    let mut style = (*ctx.global_style()).clone();
    style.visuals = egui::Visuals::light();
    style.visuals.override_text_color = Some(INK);
    style.visuals.panel_fill = Color32::from_rgb(207, 229, 246);
    style.visuals.window_fill = Color32::from_rgb(218, 236, 249);
    style.visuals.faint_bg_color = Color32::from_rgba_unmultiplied(108, 159, 201, 24);
    style.visuals.selection.bg_fill = Color32::from_rgb(142, 198, 238);
    style.visuals.selection.stroke = Stroke::new(1.0, INK);
    for widgets in [
        &mut style.visuals.widgets.noninteractive,
        &mut style.visuals.widgets.inactive,
        &mut style.visuals.widgets.hovered,
        &mut style.visuals.widgets.active,
        &mut style.visuals.widgets.open,
    ] {
        widgets.fg_stroke = Stroke::new(1.0, INK);
        widgets.corner_radius = egui::CornerRadius::same(2);
    }
    style.visuals.widgets.inactive.bg_fill = Color32::from_rgba_unmultiplied(170, 206, 233, 100);
    style.visuals.widgets.hovered.bg_fill = Color32::from_rgb(174, 213, 241);
    style.visuals.widgets.active.bg_fill = Color32::from_rgb(152, 202, 238);
    style.spacing.item_spacing = vec2(12.0, 10.0);
    style.spacing.button_padding = vec2(16.0, 9.0);
    ctx.set_global_style(style);
}
