// Computes responsive page rectangles for the launcher journal layout.
use eframe::egui::{Rect, pos2, vec2};

/// Geometry is in egui points after the user's zoom and Windows DPI transform.
#[derive(Clone, Copy, Debug)]
pub(crate) struct JournalLayout {
    pub bounds: Rect,
    pub header: Rect,
    pub body: Rect,
    pub sidebar: Option<Rect>,
    pub footer: Rect,
    pub logo: Rect,
    pub navigation: Rect,
    pub profile: Rect,
    pub play: Rect,
}

impl JournalLayout {
    pub fn new(bounds: Rect) -> Self {
        let sidebar_width = if bounds.width() >= 1080.0 {
            (bounds.width() * 0.245).clamp(245.0, 350.0)
        } else {
            0.0
        };
        let main_right = bounds.right() - sidebar_width;
        let main_width = main_right - bounds.left();
        let stacked_header = sidebar_width == 0.0 || main_width < 800.0;
        let short_header = stacked_header && bounds.height() < 500.0;
        let header_height = if short_header {
            104.0
        } else if stacked_header {
            154.0
        } else {
            112.0
        };
        let footer_height = if bounds.width() < 800.0 { 112.0 } else { 104.0 };
        let header = Rect::from_min_max(bounds.min, pos2(main_right, bounds.top() + header_height));
        let footer = Rect::from_min_max(
            pos2(bounds.left(), bounds.bottom() - footer_height),
            bounds.max,
        );
        let body = Rect::from_min_max(
            pos2(bounds.left(), header.bottom()),
            pos2(main_right, footer.top()),
        );
        let sidebar = (sidebar_width > 0.0).then(|| {
            Rect::from_min_max(
                pos2(main_right, bounds.top()),
                pos2(bounds.right(), footer.top()),
            )
        });
        let logo_width = if stacked_header {
            (main_width * 0.57).min(435.0)
        } else {
            main_width * 0.49
        };
        let logo = Rect::from_min_size(
            header.min + vec2(30.0, if short_header { 10.0 } else { 22.0 }),
            vec2(logo_width, if short_header { 42.0 } else { 54.0 }),
        );
        let navigation = if stacked_header {
            Rect::from_min_size(
                pos2(
                    header.left() + 28.0,
                    header.bottom() - if short_header { 42.0 } else { 48.0 },
                ),
                vec2(main_width - 56.0, if short_header { 32.0 } else { 38.0 }),
            )
        } else {
            Rect::from_min_max(
                pos2(logo.right() + 23.0, header.top() + 39.0),
                pos2(main_right - 24.0, header.top() + 77.0),
            )
        };
        let profile = if let Some(sidebar) = sidebar {
            Rect::from_min_max(
                sidebar.min + vec2(22.0, 22.0),
                pos2(sidebar.right() - 22.0, header.top() + 85.0),
            )
        } else {
            Rect::from_min_max(
                pos2(
                    logo.right() + 18.0,
                    header.top() + if short_header { 10.0 } else { 26.0 },
                ),
                pos2(
                    header.right() - 22.0,
                    header.top() + if short_header { 54.0 } else { 76.0 },
                ),
            )
        };
        let play_width = if bounds.width() < 800.0 {
            160.0
        } else {
            (bounds.width() * 0.225).clamp(230.0, 330.0)
        };
        let play = Rect::from_min_size(
            pos2(bounds.right() - play_width - 25.0, footer.top() + 19.0),
            vec2(play_width, footer.height() - 38.0),
        );
        Self {
            bounds,
            header,
            body,
            sidebar,
            footer,
            logo,
            navigation,
            profile,
            play,
        }
    }

    pub fn nav_item(&self, index: usize) -> Rect {
        let width = self.navigation.width() / 3.0;
        Rect::from_min_size(
            self.navigation.min + vec2(index as f32 * width, 0.0),
            vec2(width - 5.0, self.navigation.height()),
        )
    }

    pub fn page_content(&self) -> Rect {
        self.body.shrink2(vec2(28.0, 16.0))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn important_controls_fit_at_minimum_size_and_maximum_zoom() {
        for size in [
            vec2(1280.0, 800.0),
            vec2(960.0, 680.0),
            vec2(860.0, 620.0),
            vec2(860.0 / 1.5, 620.0 / 1.5),
        ] {
            let bounds = Rect::from_min_size(pos2(0.0, 0.0), size);
            let layout = JournalLayout::new(bounds);
            assert!(layout.body.height() > 100.0);
            for control in [layout.logo, layout.navigation, layout.profile, layout.play] {
                assert!(control.is_positive());
                assert!(bounds.contains_rect(control), "{size:?}: {control:?}");
            }
            assert!(!layout.logo.intersects(layout.profile));
            assert!(!layout.logo.intersects(layout.navigation));
            assert!(!layout.profile.intersects(layout.navigation));
            assert!(layout.body.bottom() <= layout.footer.top());
            for index in 0..3 {
                assert!(layout.header.contains_rect(layout.nav_item(index)));
            }
            if let Some(sidebar) = layout.sidebar {
                assert!(sidebar.left() >= layout.body.right());
            }
        }
    }
}
