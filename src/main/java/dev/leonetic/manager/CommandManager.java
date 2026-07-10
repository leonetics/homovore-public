package dev.leonetic.manager;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.leonetic.Homovore;
import dev.leonetic.features.Feature;
import dev.leonetic.features.commands.Command;
import dev.leonetic.features.commands.impl.*;
import dev.leonetic.util.traits.Jsonable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;
import static dev.leonetic.features.commands.Command.SINGLE_FAILURE;

public class CommandManager extends Feature implements Jsonable {
    private static final Logger LOGGER = LogManager.getLogger("Commands");

    private final CommandDispatcher<CommandManager> dispatcher = new CommandDispatcher<>();
    private final Map<String, Command> commandAliasMap = new LinkedHashMap<>();
    private final List<Command> commandList = new LinkedList<>();

    private String commandPrefix = ".";
    private boolean funnyVisible = false;

    public CommandManager() {
        super("Commands");
    }

    public void init() {
        register(new BindCommand());
        register(new FriendCommand());
        register(new EnemyCommand());
        register(new HelpCommand());
        register(new PrefixCommand());
        register(new ToggleCommand());
        register(new ActiveModulesCommand());
        register(new FunnyCommand());
        register(new SaveKitCommand());
        register(new LoadKitCommand());
        register(new FakePlayerCommand());
        register(new DebugLimitsCommand());
        dev.leonetic.features.debug.MoveLimitDebug.get();

        LOGGER.info("Registered {} commands", commandList.size());
        Homovore.configManager.addConfig(this);
    }

    public void onChatSent(String message) {
        try {
            int result = dispatcher.execute(message.substring(
                    commandPrefix.length()).trim(), this);
            if (result == SINGLE_SUCCESS) {
                Command.sendMessage("{green} Command executed successfully");
            } else if (result == SINGLE_FAILURE) {
                Command.sendMessage("{red} Failed to execute command");
            }
        } catch (CommandSyntaxException e) {
            LOGGER.error("Failed to execute command", e);
            Command.sendMessage("{red} %s", e.getMessage());
        }
    }

    public void register(Command command) {
        commandList.add(command);
        for (String alias : command.getAliases()) {
            commandAliasMap.put(alias, command);
            LiteralArgumentBuilder<CommandManager> builder = Command.literal(alias);
            command.createArgumentBuilder(builder);
            dispatcher.register(builder);
        }
    }

    public void setCommandPrefix(String commandPrefix) {
        this.commandPrefix = commandPrefix;
    }

    public String getCommandPrefix() {
        return commandPrefix;
    }

    public boolean isFunnyVisible() {
        return funnyVisible;
    }

    public void setFunnyVisible(boolean funnyVisible) {
        this.funnyVisible = funnyVisible;
    }

    public Command getCommand(String alias) {
        return commandAliasMap.get(alias);
    }

    public Set<String> getCommandAliases() {
        return commandAliasMap.keySet();
    }

    public List<Command> getCommands() {
        return commandList;
    }

    public CommandDispatcher<CommandManager> getDispatcher() {
        return dispatcher;
    }

    @Override
    public JsonElement toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("prefix", commandPrefix);
        object.addProperty("funnyVisible", funnyVisible);
        return object;
    }

    @Override
    public void fromJson(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return;
        }
        JsonObject object = element.getAsJsonObject();
        if (object.has("prefix")) {
            setCommandPrefix(object.get("prefix").getAsString());
        }
        if (object.has("funnyVisible")) {
            setFunnyVisible(object.get("funnyVisible").getAsBoolean());
        }
    }

    @Override
    public String getFileName() {
        return "commands.json";
    }
}
