// Invokes native temporary datapack validation and fresh-world creation only inside the isolated worker.
package com.dark.winter.biomeeditor.mixin;
import com.mojang.datafixers.util.Pair;
import java.nio.file.Path;
import java.util.function.Consumer;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.WorldDataConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(CreateWorldScreen.class)
public interface EditorCreateAccess {
    @Invoker("getOrCreateTempDataPackDir") Path editor$temp();
    @Invoker("getDataPackSelectionSettings") Pair<Path,PackRepository> editor$selection(WorldDataConfiguration config);
    @Invoker("tryApplyNewDataPacks") void editor$apply(PackRepository repository,boolean screen,Consumer<WorldDataConfiguration> onAbort);
    @Invoker("onCreate") void editor$create();
}
