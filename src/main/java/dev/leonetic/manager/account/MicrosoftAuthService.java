package dev.leonetic.manager.account;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.leonetic.Homovore;
import net.lenni0451.commons.httpclient.HttpClient;
import net.raphimc.minecraftauth.MinecraftAuth;
import net.raphimc.minecraftauth.java.JavaAuthManager;
import net.raphimc.minecraftauth.java.model.MinecraftProfile;
import net.raphimc.minecraftauth.java.model.MinecraftToken;
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode;
import net.raphimc.minecraftauth.msa.service.impl.DeviceCodeMsaAuthService;

import java.awt.Desktop;
import java.net.URI;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class MicrosoftAuthService {
    private static final int MAX_RETRIES = 3;

    private static final HttpClient httpClient = MinecraftAuth.createHttpClient();

    public record AuthResult(String username, String uuid, String accessToken, String authData) {}

    public static CompletableFuture<AuthResult> login() {
        CompletableFuture<AuthResult> future = new CompletableFuture<>();

        Thread.ofVirtual().start(() -> {
            int attempt = 0;
            while (true) {
                try {
                    Consumer<MsaDeviceCode> showCode = deviceCode -> {
                        String url = deviceCode.getDirectVerificationUri();
                        Homovore.LOGGER.info("Microsoft device-code URL: {}", url);
                        try {
                            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                                Desktop.getDesktop().browse(URI.create(url));
                            } else {
                                Runtime.getRuntime().exec(new String[]{"xdg-open", url});
                            }
                        } catch (Exception e) {
                            Homovore.LOGGER.error("Failed to open browser", e);
                        }
                    };

                    JavaAuthManager authManager = JavaAuthManager.create(httpClient)
                            .login(DeviceCodeMsaAuthService::new, showCode);

                    MinecraftToken mcToken = callWithRetry(authManager.getMinecraftToken()::getUpToDate);
                    MinecraftProfile mcProfile = callWithRetry(authManager.getMinecraftProfile()::getUpToDate);
                    String json = JavaAuthManager.toJson(authManager).toString();

                    future.complete(new AuthResult(
                            mcProfile.getName(),
                            mcProfile.getId().toString(),
                            mcToken.getToken(),
                            json
                    ));
                    return;
                } catch (Exception e) {
                    attempt++;
                    Homovore.LOGGER.error("Microsoft auth attempt {} failed: {}", attempt, e.getMessage());
                    if (attempt >= MAX_RETRIES) {
                        future.completeExceptionally(e);
                        return;
                    }
                    try {
                        Thread.sleep(attempt * 2000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        future.completeExceptionally(ie);
                        return;
                    }
                }
            }
        });

        return future;
    }

    public static AuthResult refresh(String authData) throws Exception {
        JsonObject json = JsonParser.parseString(authData).getAsJsonObject();
        JavaAuthManager authManager = JavaAuthManager.fromJson(httpClient, json);

        MinecraftToken mcToken = callWithRetry(authManager.getMinecraftToken()::getUpToDate);
        MinecraftProfile mcProfile = callWithRetry(authManager.getMinecraftProfile()::getUpToDate);
        String newJson = JavaAuthManager.toJson(authManager).toString();

        return new AuthResult(
                mcProfile.getName(),
                mcProfile.getId().toString(),
                mcToken.getToken(),
                newJson
        );
    }

    private static <T> T callWithRetry(Callable<T> callable) throws Exception {
        int attempt = 0;
        while (true) {
            try {
                return callable.call();
            } catch (Exception e) {
                attempt++;
                if (attempt >= MAX_RETRIES) throw e;
                Homovore.LOGGER.warn("Retrying after transient error: {}", e.getMessage());
                Thread.sleep(attempt * 1000L);
            }
        }
    }
}
