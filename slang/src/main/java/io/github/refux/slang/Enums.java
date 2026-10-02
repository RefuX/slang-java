package io.github.refux.slang;

/** Shared handling for the ABI-mirroring enums, whose {@code UNKNOWN} sentinel has no raw value. */
final class Enums {
    private Enums() {}

    /** {@code constant}'s raw ABI value, refusing the {@code UNKNOWN} sentinel, which carries none. */
    static int raw(Enum<?> constant, int value) {
        if (value == Integer.MIN_VALUE) {
            throw new IllegalArgumentException(
                    constant.getDeclaringClass().getSimpleName() + "." + constant.name() + " has no raw value");
        }
        return value;
    }
}
