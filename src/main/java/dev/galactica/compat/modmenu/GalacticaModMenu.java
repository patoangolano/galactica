package dev.galactica.compat.modmenu;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.galactica.compat.OptionalMods;
import dev.galactica.config.client.GalacticaConfigScreen;

/**
 * Mod Menu entrypoint ({@code "modmenu"} in fabric.mod.json). Mod Menu only instantiates it when Mod
 * Menu itself is installed, so the compileOnly dependency never leaks. Opens the YACL screen when
 * YACL is present, else the vanilla {@link GalacticaConfigScreen}. The YACL class is referenced
 * only inside the branch, so it is not linked without YACL.
 */
public final class GalacticaModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        if (OptionalMods.yacl()) {
            return parent -> dev.galactica.compat.yacl.GalacticaYaclScreen.create(parent);
        }
        return GalacticaConfigScreen::new;
    }
}
