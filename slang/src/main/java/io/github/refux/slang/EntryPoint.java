package io.github.refux.slang;

import io.github.refux.slang.ffi.IEntryPoint;

/**
 * A shader entry point, obtained from {@link Module#entryPoint(String)} and composed into a
 * program via {@link Session#composite}. Caller-owned; like every wrapper, an unclosed
 * EntryPoint is eventually released by the Cleaner, so inline use — as in
 * {@code session.composite(module, module.entryPoint("main"))} — is safe.
 */
public final class EntryPoint extends ComponentType {

    EntryPoint(Session session, IEntryPoint entryPoint) {
        super(session, entryPoint);
    }
}
