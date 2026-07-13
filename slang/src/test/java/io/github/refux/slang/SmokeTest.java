package io.github.refux.slang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import io.github.refux.slang.ffi.IGlobalSession;
import io.github.refux.slang.ffi.SlangNative;
import io.github.refux.slang.loader.SlangLibrary;

/**
 * M0 exit criterion (DESIGN.md §16): the C export path and the COM vtable path must report the
 * same build tag on every platform — one assertion that proves library loading, C downcalls,
 * struct marshaling, object creation, vtable slot arithmetic, and refcounting all at once.
 */
class SmokeTest {

    @Test
    void comVtablePathAgreesWithCExportPath() {
        String cTag = SlangNative.spGetBuildTagString();
        assertNotNull(cTag);
        assertFalse(cTag.isBlank());
        System.out.println("[slang-java] library       : " + SlangLibrary.get().location());
        System.out.println("[slang-java] C build tag   : " + cTag);

        try (IGlobalSession session = IGlobalSession.create()) {
            String comTag = session.getBuildTagString();
            System.out.println("[slang-java] COM build tag : " + comTag);
            assertEquals(cTag, comTag,
                "IGlobalSession::getBuildTagString (vtable slot 8) must match spGetBuildTagString");
        }
    }

    @Test
    void refCountsBehaveLikeCom() {
        try (IGlobalSession session = IGlobalSession.create()) {
            long afterAddRef = session.addRef();
            long afterRelease = session.release();
            assertEquals(afterAddRef - 1, afterRelease,
                "release must undo addRef through vtable slots 1 and 2");
        }
    }
}
