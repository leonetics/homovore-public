package dev.leonetic.manager;

import com.google.gson.*;
import dev.leonetic.Homovore;
import dev.leonetic.manager.account.Account;
import dev.leonetic.manager.account.MicrosoftAuthService;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class AccountManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path rootDir;
    private final Path accountsFile;
    private final Path clientStateFile;
    private final List<Account> accounts = new ArrayList<>();
    private Account currentAccount;

    public AccountManager() {
        this.rootDir = FabricLoader.getInstance().getGameDir().resolve("homovore");
        this.accountsFile = rootDir.resolve("accounts.json");
        this.clientStateFile = rootDir.resolve("client-state.json");
        load();
        autoSwitchLastAccount();
    }

    public void add(Account account) {
        accounts.removeIf(a -> a.getName().equalsIgnoreCase(account.getName()));
        accounts.add(account);
        save();
    }

    public boolean remove(String name) {
        boolean removed = accounts.removeIf(a -> a.getName().equalsIgnoreCase(name.trim()));
        if (removed) {
            if (currentAccount != null && currentAccount.getName().equalsIgnoreCase(name.trim())) {
                currentAccount = null;
            }
            save();
        }
        return removed;
    }

    public List<Account> getAccounts() {
        return Collections.unmodifiableList(new ArrayList<>(accounts));
    }

    public Account getCurrentAccount() {
        return currentAccount;
    }

    public void setCurrentAccount(Account account) {
        this.currentAccount = account;
    }

    public Account getByName(String name) {
        for (Account a : accounts) {
            if (a.getName().equalsIgnoreCase(name.trim())) return a;
        }
        return null;
    }

    public boolean switchToAccount(Account account) {
        if (account == null || account.getAccessToken() == null || account.getAccessToken().isEmpty()) {
            return false;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            User newUser = new User(
                    account.getName(),
                    java.util.UUID.fromString(account.getUuid()),
                    account.getAccessToken(),
                    Optional.empty(),
                    Optional.empty()
            );
            setMinecraftUser(mc, newUser);
            currentAccount = account;
            saveLastAccount(account.getName());
            Homovore.LOGGER.info("Switched to account: {}", account.getName());
            return true;
        } catch (Exception e) {
            Homovore.LOGGER.error("Failed to switch account", e);
            return false;
        }
    }

    private void autoSwitchLastAccount() {
        String lastName = loadLastAccount();
        if (lastName == null || lastName.isEmpty()) return;
        Account account = getByName(lastName);
        if (account == null) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            refreshAndSwitch(account);
        } catch (Exception e) {
            Homovore.LOGGER.error("Failed to auto-switch to last account", e);
        }
    }

    public boolean refreshAndSwitch(Account account) {
        if (account == null || account.getAuthData() == null || account.getAuthData().isEmpty()) {
            return switchToAccount(account);
        }
        try {
            MicrosoftAuthService.AuthResult profile = MicrosoftAuthService.refresh(account.getAuthData());
            Account refreshed = new Account(
                    profile.username(), profile.uuid(),
                    profile.accessToken(), profile.authData(),
                    account.getAddedAt()
            );
            accounts.removeIf(a -> a.getName().equalsIgnoreCase(account.getName()));
            accounts.add(refreshed);
            save();
            return switchToAccount(refreshed);
        } catch (Exception e) {
            Homovore.LOGGER.error("Failed to refresh account {}", account.getName(), e);
            return switchToAccount(account);
        }
    }

    private void setMinecraftUser(Minecraft mc, User user) throws Exception {
        Field userField = Minecraft.class.getDeclaredField("user");
        userField.setAccessible(true);
        userField.set(mc, user);
    }

    public void load() {
        accounts.clear();
        try {
            JsonElement root = readJson(accountsFile);
            JsonArray array = getAccountsArray(root);
            if (array == null) {
                save();
                return;
            }
            for (JsonElement element : array) {
                if (!element.isJsonObject()) continue;
                Account account = Account.fromJson(element.getAsJsonObject());
                if (account != null && !account.getName().isEmpty()) {
                    accounts.add(account);
                }
            }
        } catch (IOException | RuntimeException exception) {
            Homovore.LOGGER.error("Failed to load accounts", exception);
        }
    }

    public void save() {
        mkdirs();
        JsonArray array = new JsonArray();
        for (Account account : accounts) {
            array.add(account.toJson());
        }
        try {
            writeJson(accountsFile, array);
        } catch (IOException exception) {
            Homovore.LOGGER.error("Failed to save accounts", exception);
        }
    }

    private JsonArray getAccountsArray(JsonElement root) {
        if (root == null) return new JsonArray();
        if (root.isJsonArray()) return root.getAsJsonArray();
        if (root.isJsonObject() && root.getAsJsonObject().has("accounts")) {
            JsonElement arr = root.getAsJsonObject().get("accounts");
            if (arr.isJsonArray()) return arr.getAsJsonArray();
        }
        return null;
    }

    private JsonElement readJson(Path path) throws IOException {
        if (!Files.exists(path) || Files.size(path) == 0) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader);
        }
    }

    private void writeJson(Path path, JsonElement value) throws IOException {
        mkdirs();
        Path tempPath = path.resolveSibling(path.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tempPath, StandardCharsets.UTF_8)) {
            GSON.toJson(value, writer);
            writer.write(System.lineSeparator());
        }
        try {
            Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void saveLastAccount(String accountName) {
        try {
            JsonObject state = readObject(clientStateFile);
            state.addProperty("lastAccount", accountName);
            writeJson(clientStateFile, state);
        } catch (IOException | RuntimeException exception) {
            Homovore.LOGGER.error("Failed to save last account", exception);
        }
    }

    private String loadLastAccount() {
        try {
            JsonObject state = readObject(clientStateFile);
            if (state.has("lastAccount") && state.get("lastAccount").isJsonPrimitive()) {
                return state.get("lastAccount").getAsString();
            }
        } catch (IOException | RuntimeException exception) {
            Homovore.LOGGER.error("Failed to load last account", exception);
        }
        return null;
    }

    private JsonObject readObject(Path path) throws IOException {
        JsonElement element = readJson(path);
        if (element != null && element.isJsonObject()) {
            return element.getAsJsonObject();
        }
        return new JsonObject();
    }

    private void mkdirs() {
        if (!rootDir.toFile().exists()) {
            rootDir.toFile().mkdirs();
        }
    }
}
