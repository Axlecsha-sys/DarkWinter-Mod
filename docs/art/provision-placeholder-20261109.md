# Общая заглушка предметов провизии — 09.11.2026

По поручению автора создана одна общая иконка для 40 новых предметов из задачи wiki «Расширение ассортимента еды и напитков». Индивидуальные модели и оформление продуктов не создавались.

## Ресурс

- PNG: `src/client/resources/assets/darkwinter/textures/item/untextured_item.png`.
- Одна общая модель: `src/client/resources/assets/darkwinter/models/item/untextured_item.json`.
- Размер PNG: 1254 × 1254, RGBA, прозрачность сохранена.
- SHA-256: `87463db27657c79a6e59e4c260549074c10f36c6b1c58fef670bc9456e75064d`.
- Метод: встроенный image_gen, новый оригинальный рисунок. Исходное изображение перенесено побайтно; художественная постобработка не выполнялась.
- Все 40 item definitions ссылаются на общую модель, поэтому PNG не дублируется для каждого предмета.

## Промпт

Use case: stylized-concept. Asset type: one small inventory placeholder icon for the DarkWinter Minecraft survival mod. Create a single clean readable pixel-art icon denoting a generic item whose individual texture has not been created yet. A simple solid pale cool-grey object silhouette, like an unlabelled square parcel viewed almost straight on, with one large dark charcoal question mark centered on it. Very restrained icy blue edge highlight, dark outline, broad flat colour clusters. Neutral untextured surface, no material grain, no brand, no wording. Single centered object fills 82% of a square canvas, generous transparent padding around all sides. Pixel art designed to remain legible at 32x32 and 64x64 inventory display, exact hard stepped pixel edges, very small limited palette. This is a flat game sprite, not a photographed object, not a 3D rendered scene, no ground, no shadow outside the silhouette, no decorative border around canvas, no glow, no blur, no gritty noise, no gradient background, no watermark, no sheet of variations. The background must be genuinely transparent alpha. The only symbol is the large question mark. Output one PNG game asset.
