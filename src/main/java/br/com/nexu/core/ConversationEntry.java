package br.com.nexu.core;

import java.time.LocalTime;

/** Item do histórico de conversa. */
public record ConversationEntry(Speaker speaker, String text, LocalTime time) {

    public enum Speaker {
        USER, NEXU, SYSTEM
    }

    public static ConversationEntry user(String text) {
        return new ConversationEntry(Speaker.USER, text, LocalTime.now());
    }

    public static ConversationEntry nexu(String text) {
        return new ConversationEntry(Speaker.NEXU, text, LocalTime.now());
    }

    public static ConversationEntry system(String text) {
        return new ConversationEntry(Speaker.SYSTEM, text, LocalTime.now());
    }
}
