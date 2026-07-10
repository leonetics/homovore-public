package dev.leonetic.features.settings;

import com.google.common.base.Converter;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

@SuppressWarnings("unchecked")
public class EnumConverter<T extends Enum<T>> extends Converter<T, JsonElement> {
    private final Class<T> clazz;

    public EnumConverter(Class<T> clazz) {
        this.clazz = clazz;
    }

    public static <T extends Enum<?>> int currentEnum(T clazz) {
        for (int i = 0; i < clazz.getDeclaringClass().getEnumConstants().length; ++i) {
            T e = (T) clazz.getDeclaringClass().getEnumConstants()[i];
            if (!e.name().equalsIgnoreCase(clazz.name())) continue;
            return i;
        }
        return -1;
    }

    public static <T extends Enum<?>> T increaseEnum(T clazz) {
        int index = EnumConverter.currentEnum(clazz);
        for (int i = 0; i < clazz.getDeclaringClass().getEnumConstants().length; ++i) {
            T e = (T) clazz.getDeclaringClass().getEnumConstants()[i];
            if (i != index + 1) continue;
            return e;
        }
        return (T) clazz.getDeclaringClass().getEnumConstants()[0];
    }

    public static <T extends Enum<?>> String getProperName(T clazz) {
        String[] parts = clazz.name().toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    public JsonElement doForward(Enum anEnum) {
        return new JsonPrimitive(anEnum.toString());
    }

    public T doBackward(JsonElement jsonElement) {
        try {
            return Enum.valueOf(this.clazz, jsonElement.getAsString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
