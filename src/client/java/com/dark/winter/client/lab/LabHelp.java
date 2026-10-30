// Describes registered laboratory controls and their implemented effects.
package com.dark.winter.client.lab;

import java.util.Map;

/** Descriptions are facts about existing controls, not guessed callback semantics. */
public final class LabHelp {
    private LabHelp() { }
    private static final Map<String,String> EFFECTS = Map.ofEntries(
        Map.entry("menu.singleplayer", "Открывает список одиночных миров."),
        Map.entry("menu.darkwinter.play", "Открывает список одиночных миров."),
        Map.entry("menu.options", "Открывает настройки игры."),
        Map.entry("menu.darkwinter.interface", "Открывает настройки интерфейса DarkWinter."),
        Map.entry("menu.returnToGame", "Закрывает паузу и возвращает управление персонажу."),
        Map.entry("menu.returnToMenu", "Сохраняет и закрывает текущий мир."),
        Map.entry("menu.quit", "Завершает клиент Minecraft обычным способом."),
        Map.entry("selectWorld.create", "Открывает создание нового мира и выбор режима DarkWinter."),
        Map.entry("selectWorld.select", "Открывает выбранный мир; могут появиться предупреждения совместимости."),
        Map.entry("selectWorld.edit", "Открывает настройки выбранного сохранения."),
        Map.entry("selectWorld.delete", "Запрашивает удаление выбранного сохранения. Не подтверждать без задачи пользователя."),
        Map.entry("selectWorld.recreate", "Открывает создание мира с параметрами выбранного сохранения."),
        Map.entry("menu.darkwinter.story", "Открывает выбор сложности северного режима; сохранение не создаётся."),
        Map.entry("menu.darkwinter.free", "Открывает стандартную форму создания свободного мира; сохранение ещё не создаётся."),
        Map.entry("menu.darkwinter.configure", "Открывает вкладку параметров нового северного мира, не создавая сохранение."),
        Map.entry("createWorld.tab.game.title", "Переключает форму на имя, режим, сложность и команды."),
        Map.entry("createWorld.tab.world.title", "Переключает форму на seed и параметры генерации."),
        Map.entry("createWorld.tab.more.title", "Переключает форму на дополнительные параметры нового мира."),
        Map.entry("gui.cancel", "Отменяет текущую форму и возвращает на предыдущий экран."),
        Map.entry("gui.back", "Возвращает на предыдущий экран."),
        Map.entry("gui.done", "Применяет текущие настройки и возвращает назад."),
        Map.entry("selectWorld.enterName", "Имя создаваемого мира."),
        Map.entry("selectWorld.enterSeed", "Seed генерации создаваемого мира."),
        Map.entry("menu.darkwinter.continue", "Открывает форму создания мира с выбранным режимом."),
        Map.entry("key.darkwinter.wall_menu", "Открывает выбор формы стены, если в основной руке сегмент стены."),
        Map.entry("key.darkwinter.switch_shoulder", "Переключает плечо камеры."),
        Map.entry("key.forward", "Движение вперёд с обычными коллизиями и правилами DarkWinter."),
        Map.entry("key.back", "Движение назад; имеет приоритет над автоматическим движением вперёд."),
        Map.entry("key.sprint", "Спринт/движение вперёд по существующей логике DarkWinter; зависит от выносливости."),
        Map.entry("key.jump", "Прыжок с существующими ограничениями выносливости."),
        Map.entry("key.inventory", "Открывает инвентарь игрока."),
        Map.entry("key.attack", "Атака/разрушение по текущему прицелу и обычной дистанции."),
        Map.entry("key.use", "Использование предмета/блока по текущему прицелу."),
        Map.entry("key.togglePerspective", "Меняет перспективу камеры по правилам DarkWinter.")
    );
    public static String effect(String key, String label) {
        if(key.startsWith("menu.darkwinter.motion."))return "Переключает анимацию интерфейса: FULL → REDUCED → OFF → FULL; сохраняет настройку.";
        if(key.equals("menu.darkwinter.fx.strength"))return "Переключает интенсивность оформления: 50%, 75%, 100%, 125%; сохраняет настройку.";
        if(key.startsWith("menu.darkwinter.fx.mist."))return "Включает или выключает декоративный туман меню; сохраняет настройку.";
        if(key.startsWith("menu.darkwinter.pause."))return "Переключает фон паузы между миром и рисунком укрытия; сохраняет настройку.";
        return EFFECTS.getOrDefault(key, "Элемент «" + label + "». Вызвать его штатный обработчик и проверить новое состояние; отдельное описание эффекта не зарегистрировано.");
    }
    public static String effect(String key,String label,String screen) {
        if(screen.equals("CreateWorldScreen")&&key.equals("selectWorld.create"))return "Создаёт новое сохранение и запускает мир. Нажимать только если задача требует создания мира.";
        if(screen.equals("DarkWinterInventoryScreen")) {
            if(label.equals("Инвентарь"))return "Показывает личные вещи и быстрые слоты; предметы не перемещаются.";
            if(label.equals("Снаряжение"))return "Показывает экипировку, броню, вторую руку и крафт; предметы не перемещаются.";
            if(label.equals("Творчество"))return "Открывает творческий каталог, только для игрока в creative.";
        }
        return effect(key,label);
    }
    public static String screen(String type) {
        return switch(type) {
            case "TitleScreen" -> "Главное меню: запуск мира, настройки, завершение клиента.";
            case "SelectWorldScreen" -> "Список сохранений: выбор, открытие, создание и управление мирами.";
            case "UiPreferencesScreen" -> "Настройки оформления: анимация, интенсивность, туман и фон паузы; изменения сохраняются сразу.";
            case "WorldModeScreen" -> "Выбор режима нового мира DarkWinter; мир ещё не создаётся.";
            case "CreateWorldScreen" -> "Настройка нового мира: имя, seed, режим, вкладки. Не нажимать создание, если задача — только осмотр интерфейса.";
            case "PauseScreen" -> "Меню паузы текущего мира.";
            case "WallBuilderScreen" -> "Выбор формы модуля стены; карточки имеют штатные обработчики клика.";
            case "DarkWinterInventoryScreen" -> "Инвентарь и снаряжение: вкладки и реальные серверные слоты.";
            case "ConfirmScreen" -> "Подтверждение действия: прочитать заголовок и кнопки перед выбором.";
            case "" -> "Игровая сцена. Список привязок доступен через controls; ESC открывает паузу.";
            default -> "Текущий экран: " + type + ". Использовать доступные элементы и проверять результат действий.";
        };
    }
}
