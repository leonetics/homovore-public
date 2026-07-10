package dev.leonetic.features.commands.impl;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.leonetic.Homovore;
import dev.leonetic.features.commands.Command;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.modules.hud.ActiveModulesHudModule;
import dev.leonetic.manager.CommandManager;

import java.util.List;
import java.util.StringJoiner;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;

public class ActiveModulesCommand extends Command {
    public ActiveModulesCommand() {
        super("activemodules", "am");
        setDescription("Manages the ActiveModules HUD list");
    }

    @Override
    public void createArgumentBuilder(LiteralArgumentBuilder<CommandManager> builder) {
        builder.then(literal("add")
                        .then(argument("module", word())
                                .suggests(addSuggestions())
                                .executes((ctx) -> {
                                    String name = getString(ctx, "module");
                                    Module module = Homovore.moduleManager.getModuleByName(name);
                                    if (module == null) return fail("No module named {green} %s", name);
                                    ActiveModulesHudModule h = ActiveModulesHudModule.getInstance();
                                    if (h == null) return fail("ActiveModules HUD element not found");
                                    if (!h.add(name)) {
                                        return success("{green} %s {reset} is already in the list", module.getDisplayName());
                                    }
                                    return success("Added {green} %s {reset} to ActiveModules", module.getDisplayName());
                                })))
                .then(literal("remove")
                        .then(argument("module", word())
                                .suggests(removeSuggestions())
                                .executes((ctx) -> {
                                    String name = getString(ctx, "module");
                                    ActiveModulesHudModule h = ActiveModulesHudModule.getInstance();
                                    if (h == null) return fail("ActiveModules HUD element not found");
                                    if (!h.remove(name)) {
                                        return success("{green} %s {reset} is not in the list", name);
                                    }
                                    return success("Removed {green} %s {reset} from ActiveModules", name);
                                })))
                .then(literal("clear")
                        .executes((ctx) -> {
                            ActiveModulesHudModule h = ActiveModulesHudModule.getInstance();
                            if (h == null) return fail("ActiveModules HUD element not found");
                            h.clear();
                            return success("Cleared ActiveModules list");
                        }))
                .then(literal("list")
                        .executes((ctx) -> {
                            ActiveModulesHudModule h = ActiveModulesHudModule.getInstance();
                            if (h == null) return fail("ActiveModules HUD element not found");
                            List<String> entries = h.getEntries();
                            if (entries.isEmpty()) return success("ActiveModules list is empty");
                            StringJoiner joiner = new StringJoiner(", ");
                            entries.forEach(joiner::add);
                            return success("ActiveModules (%s): %s", entries.size(), joiner);
                        }));
    }

    private static SuggestionProvider<CommandManager> addSuggestions() {
        return (ctx, builder) -> {
            String input = builder.getRemainingLowerCase();
            ActiveModulesHudModule h = ActiveModulesHudModule.getInstance();
            List<String> entries = h != null ? h.getEntries() : List.of();
            for (Module module : Homovore.moduleManager.getModules()) {
                String name = module.getName();
                if (entries.stream().anyMatch(e -> e.equalsIgnoreCase(name))) continue;
                if (name.toLowerCase().contains(input) || module.getDisplayName().toLowerCase().contains(input)) {
                    builder.suggest(name);
                }
            }
            return builder.buildFuture();
        };
    }

    private static SuggestionProvider<CommandManager> removeSuggestions() {
        return (ctx, builder) -> {
            String input = builder.getRemainingLowerCase();
            ActiveModulesHudModule h = ActiveModulesHudModule.getInstance();
            if (h == null) return builder.buildFuture();
            for (String entry : h.getEntries()) {
                if (entry.toLowerCase().contains(input)) {
                    builder.suggest(entry);
                }
            }
            return builder.buildFuture();
        };
    }
}
