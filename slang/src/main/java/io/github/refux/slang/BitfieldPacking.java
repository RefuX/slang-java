package io.github.refux.slang;

import io.github.refux.slang.ffi.gen.BitfieldPackingRules;
import java.util.HashMap;
import java.util.Map;

/**
 * How bitfield members are packed into storage ({@code slang::BitfieldPackingRules}), set with
 * {@link SessionBuilder#bitfieldPacking}. {@link #value()} / {@link #of(int)} are escape hatches to
 * the raw ABI values; unmapped (newer) values return {@link #UNKNOWN}.
 */
public enum BitfieldPacking {
    /** Sentinel for ABI values this enum does not know (carries no raw value). */
    UNKNOWN(Integer.MIN_VALUE),
    /** Least-significant bit first; fields may share storage across underlying type sizes. */
    DEFAULT(BitfieldPackingRules.Default),
    /**
     * As MSVC packs on little-endian platforms: least-significant bit first, and a change of
     * underlying type size starts new storage. Rejects zero-width fields.
     */
    MSVC(BitfieldPackingRules.MSVC),
    /**
     * What Slang's older {@code -msvc-style-bitfield-packing} flag did: most-significant bit first,
     * new storage on a type-size change. Not what MSVC does; kept for existing data layouts only.
     */
    LEGACY_MSB_FIRST_MSVC(BitfieldPackingRules.LegacyMSBFirstMSVC);

    private static final Map<Integer, BitfieldPacking> BY_VALUE = new HashMap<>();

    static {
        for (BitfieldPacking rules : values()) {
            if (rules != UNKNOWN) {
                BY_VALUE.put(rules.value, rules);
            }
        }
    }

    private final int value;

    BitfieldPacking(int value) {
        this.value = value;
    }

    /** The raw {@code slang::BitfieldPackingRules} ABI value ({@code Integer.MIN_VALUE} for UNKNOWN). */
    public int value() {
        return value;
    }

    /** Maps a raw ABI value; unmapped (newer) values return {@link #UNKNOWN}. */
    public static BitfieldPacking of(int value) {
        return BY_VALUE.getOrDefault(value, UNKNOWN);
    }
}
