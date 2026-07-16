package dev.leonetic.event.impl.network;

import dev.leonetic.event.Event;

import java.util.UUID;

public class IncomingChatEvent extends Event {
    private final String content;
    private final String rendered;
    private final UUID sender;
    private final Type type;

    public IncomingChatEvent(String content, String rendered, UUID sender, Type type) {
        this.content = content;
        this.rendered = rendered;
        this.sender = sender;
        this.type = type;
    }

    public String getContent() {
        return content;
    }

    public String getRendered() {
        return rendered;
    }

    public UUID getSender() {
        return sender;
    }

    public Type getType() {
        return type;
    }

    public enum Type {
        PLAYER,
        SYSTEM,
        DISGUISED
    }
}
