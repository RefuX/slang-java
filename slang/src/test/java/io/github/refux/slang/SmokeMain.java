package io.github.refux.slang;

import io.github.refux.slang.ffi.IGlobalSession;
import io.github.refux.slang.ffi.SlangNative;
import io.github.refux.slang.loader.SlangLibrary;

/**
 * Manual smoke entry point — the same checks as {@link SmokeTest} but runnable without a test
 * framework, e.g. straight after a local Slang build:
 *
 * <pre>
 * java --enable-native-access=ALL-UNNAMED \
 *      -Dio.github.refux.slang.libraryPath=$SLANG_REPO/build/Release/lib \
 *      -cp … io.github.refux.slang.SmokeMain
 * </pre>
 */
public final class SmokeMain {
    public static void main(String[] args) {
        System.out.println("library       : " + SlangLibrary.get().location());
        String cTag = SlangNative.spGetBuildTagString();
        System.out.println("C build tag   : " + cTag);
        try (IGlobalSession session = IGlobalSession.create()) {
            String comTag = session.getBuildTagString();
            System.out.println("COM build tag : " + comTag);
            if (!cTag.equals(comTag)) {
                throw new AssertionError("C and COM build tags differ: " + cTag + " vs " + comTag);
            }
            System.out.println("PASS: C export and COM vtable paths agree");
        }
    }
}
