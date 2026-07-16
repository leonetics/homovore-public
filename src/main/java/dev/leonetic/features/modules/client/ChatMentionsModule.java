package dev.leonetic.features.modules.client;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.network.IncomingChatEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Setting;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatMentionsModule extends Module {
    private static final int INFO_COLOR = 0xFFFF55;
    private static final int SENDER_COLOR = 0xFFFFFF;
    private static final int MENTION_COLOR = 0xBE4852;

    private final Setting<Boolean> ownName = bool("OwnName", true);
    private final Setting<String> words = str("Words", "");
    private final Setting<Boolean> wholeWord = bool("WholeWord", true);
    private final Setting<Boolean> highlight = bool("Highlight", true);
    private final Setting<Sound> sound = mode("Sound", Sound.ORB);
    private final Setting<Float> volume = num("Volume", 2.0f, 0.1f, 4.0f);
    private final Setting<Boolean> ignoreSelf = bool("IgnoreSelf", true);

    public ChatMentionsModule() {
        super("ChatMentions", "Alerts you when chat mentions your name or watched words.", Category.CLIENT);
    }

    @Subscribe
    private void onIncomingChat(IncomingChatEvent event) {
        if (mc.player == null) return;
        if (ignoreSelf.getValue() && sameSender(event.getSender(), mc.player.getUUID())) return;

        String message = event.getRendered();
        if (message == null || message.isBlank()) message = event.getContent();
        if (message == null || message.isBlank()) return;

        String sender = senderName(event.getSender(), message);
        if (ignoreSelf.getValue() && isOwnName(sender)) return;

        String matched = findMention(message);
        if (matched == null) return;

        playSound();
    }

    private String findMention(String message) {
        Match senderSpan = senderSpan(message);
        for (Match match : findMatches(message, senderSpan)) {
            String matched = message.substring(match.start, match.end);
            for (String token : tokens()) {
                if (matched.equalsIgnoreCase(token)) return token;
            }
        }
        return null;
    }

    private List<String> tokens() {
        List<String> out = new ArrayList<>();
        if (ownName.getValue() && mc.player != null) {
            out.add(mc.player.getGameProfile().name());
        }

        String value = words.getValue();
        if (value != null && !value.isBlank()) {
            for (String word : value.split(",")) {
                String trimmed = word.trim();
                if (!trimmed.isEmpty()) out.add(trimmed);
            }
        }
        return out;
    }

    private boolean matches(String message, String token) {
        if (token == null || token.isBlank()) return false;
        if (!wholeWord.getValue()) {
            return message.toLowerCase(Locale.ROOT).contains(token.toLowerCase(Locale.ROOT));
        }

        Pattern pattern = Pattern.compile("(?i)(?<![A-Za-z0-9_])" + Pattern.quote(token) + "(?![A-Za-z0-9_])");
        return pattern.matcher(message).find();
    }

    private boolean sameSender(UUID a, UUID b) {
        return a != null && b != null && a.equals(b);
    }

    private String senderName(UUID sender, String message) {
        if (sender != null && mc.getConnection() != null) {
            var info = mc.getConnection().getPlayerInfo(sender);
            if (info != null) return info.getProfile().name();
        }

        Matcher matcher = Pattern.compile("<([^>]+)>").matcher(message);
        if (matcher.find()) return matcher.group(1);

        return "Chat";
    }

    private boolean isOwnName(String name) {
        return mc.player != null && name != null
                && name.equalsIgnoreCase(mc.player.getGameProfile().name());
    }

    private void playSound() {
        SoundEvent event = switch (sound.getValue()) {
            case ORB -> SoundEvents.EXPERIENCE_ORB_PICKUP;
            case LEVEL -> SoundEvents.PLAYER_LEVELUP;
            case BELL -> SoundEvents.BELL_BLOCK;
            case NONE -> null;
        };
        if (event != null) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(event, 1.0f, volume.getValue()));
        }
    }

    public static Component decorate(Component original) {
        ChatMentionsModule module = getInstance();
        if (module == null || !module.isEnabled() || !module.highlight.getValue() || mc.player == null) {
            return original;
        }

        String message = original.getString();
        if (message == null || message.isBlank()) return original;

        String sender = module.senderName(null, message);
        if (module.ignoreSelf.getValue() && module.isOwnName(sender)) return original;

        Match senderSpan = module.senderSpan(message);
        List<Match> matches = module.findMatches(message, senderSpan);
        if (matches.isEmpty()) return original;

        MutableComponent decorated = Component.empty()
                .append(Component.literal("[").withColor(MENTION_COLOR))
                .append(Component.literal("ℹ").withColor(INFO_COLOR))
                .append(Component.literal("] ").withColor(MENTION_COLOR));

        List<Span> spans = module.spans(senderSpan, matches);

        final int[] offset = {0};
        original.visit((style, text) -> {
            module.appendStyled(decorated, text, style, offset[0], spans);
            offset[0] += text.length();
            return java.util.Optional.empty();
        }, Style.EMPTY);

        return decorated;
    }

    private List<Match> findMatches(String message, Match excluded) {
        List<Match> matches = new ArrayList<>();
        for (String token : tokens()) {
            if (token == null || token.isBlank()) continue;
            if (wholeWord.getValue()) {
                Matcher matcher = Pattern.compile("(?i)(?<![A-Za-z0-9_])" + Pattern.quote(token) + "(?![A-Za-z0-9_])")
                        .matcher(message);
                while (matcher.find()) addMatch(matches, matcher.start(), matcher.end(), excluded);
            } else {
                String lowerMessage = message.toLowerCase(Locale.ROOT);
                String lowerToken = token.toLowerCase(Locale.ROOT);
                int index = lowerMessage.indexOf(lowerToken);
                while (index >= 0) {
                    addMatch(matches, index, index + token.length(), excluded);
                    index = lowerMessage.indexOf(lowerToken, index + token.length());
                }
            }
        }
        matches.sort(Comparator.comparingInt(Match::start));
        return matches;
    }

    private void appendStyled(MutableComponent out, String text, Style style, int globalStart, List<Span> spans) {
        int cursor = 0;
        int globalEnd = globalStart + text.length();

        for (Span span : spans) {
            if (span.end <= globalStart) continue;
            if (span.start >= globalEnd) break;

            int localStart = Math.max(0, span.start - globalStart);
            int localEnd = Math.min(text.length(), span.end - globalStart);

            if (localStart > cursor) {
                out.append(Component.literal(text.substring(cursor, localStart)).withStyle(style));
            }
            if (localEnd > localStart) {
                out.append(Component.literal(text.substring(localStart, localEnd)).withStyle(style.withColor(span.color)));
            }
            cursor = Math.max(cursor, localEnd);
        }

        if (cursor < text.length()) {
            out.append(Component.literal(text.substring(cursor)).withStyle(style));
        }
    }

    private void addMatch(List<Match> matches, int start, int end, Match excluded) {
        if (excluded != null && start < excluded.end && end > excluded.start) return;
        for (Match match : matches) {
            if (start < match.end && end > match.start) return;
        }
        matches.add(new Match(start, end));
    }

    private Match senderSpan(String message) {
        Matcher matcher = Pattern.compile("<([^>]+)>").matcher(message);
        if (!matcher.find()) return null;
        return new Match(matcher.start(1), matcher.end(1));
    }

    private List<Span> spans(Match senderSpan, List<Match> mentions) {
        List<Span> spans = new ArrayList<>();
        if (senderSpan != null) spans.add(new Span(senderSpan.start, senderSpan.end, SENDER_COLOR));
        for (Match mention : mentions) spans.add(new Span(mention.start, mention.end, MENTION_COLOR));
        spans.sort(Comparator.comparingInt(Span::start));
        return spans;
    }

    private static ChatMentionsModule getInstance() {
        if (Homovore.moduleManager == null) return null;
        return Homovore.moduleManager.getModuleByClass(ChatMentionsModule.class);
    }

    private record Match(int start, int end) {}
    private record Span(int start, int end, int color) {}

    public enum Sound {
        NONE,
        ORB,
        LEVEL,
        BELL
    }
}
