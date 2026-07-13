// Prints the struct layouts, enum widths, and default field values that the hand-written M1
// Java layouts (io.github.refux.slang.ffi.TargetDesc / SessionDesc) hard-code, so those numbers
// are compiler-verified rather than hand-counted. The M2 bindgen extractor supersedes this with
// libclang-derived layouts validated across all six target ABIs (DESIGN.md §9).
//
// Build & run (any host in the support matrix; layouts must agree with the Java constants):
//   clang++ -std=c++17 -I <slang-repo>/include tools/abi-probe.cpp -o /tmp/abi-probe
//   /tmp/abi-probe
#include <slang.h>

#include <cstddef>
#include <cstdio>

#define FIELD(S, f) \
    printf("  %-28s off=%3zu size=%zu\n", #f, offsetof(S, f), sizeof(S{}.f))

int main()
{
    using namespace slang;

    printf("TargetDesc size=%zu\n", sizeof(TargetDesc));
    FIELD(TargetDesc, structureSize);
    FIELD(TargetDesc, format);
    FIELD(TargetDesc, profile);
    FIELD(TargetDesc, flags);
    FIELD(TargetDesc, floatingPointMode);
    FIELD(TargetDesc, lineDirectiveMode);
    FIELD(TargetDesc, forceGLSLScalarBufferLayout);
    FIELD(TargetDesc, compilerOptionEntries);
    FIELD(TargetDesc, compilerOptionEntryCount);
    TargetDesc td;
    printf("  defaults: format=%d profile=%u flags=%u fp=%u line=%u\n",
        (int)td.format, (unsigned)td.profile, (unsigned)td.flags,
        (unsigned)td.floatingPointMode, (unsigned)td.lineDirectiveMode);

    printf("SessionDesc size=%zu\n", sizeof(SessionDesc));
    FIELD(SessionDesc, structureSize);
    FIELD(SessionDesc, targets);
    FIELD(SessionDesc, targetCount);
    FIELD(SessionDesc, flags);
    FIELD(SessionDesc, defaultMatrixLayoutMode);
    FIELD(SessionDesc, searchPaths);
    FIELD(SessionDesc, searchPathCount);
    FIELD(SessionDesc, preprocessorMacros);
    FIELD(SessionDesc, preprocessorMacroCount);
    FIELD(SessionDesc, fileSystem);
    FIELD(SessionDesc, enableEffectAnnotations);
    FIELD(SessionDesc, allowGLSLSyntax);
    FIELD(SessionDesc, compilerOptionEntries);
    FIELD(SessionDesc, compilerOptionEntryCount);
    FIELD(SessionDesc, skipSPIRVValidation);
    SessionDesc sd;
    printf("  defaults: flags=%u matrixLayout=%d\n",
        (unsigned)sd.flags, (int)sd.defaultMatrixLayoutMode);

    printf("PreprocessorMacroDesc size=%zu (name off=%zu, value off=%zu)\n",
        sizeof(PreprocessorMacroDesc),
        offsetof(PreprocessorMacroDesc, name),
        offsetof(PreprocessorMacroDesc, value));

    printf("widths: SlangCompileTarget=%zu SlangProfileID=%zu SlangTargetFlags=%zu "
           "SlangFloatingPointMode=%zu SlangLineDirectiveMode=%zu SlangMatrixLayoutMode=%zu "
           "SessionFlags=%zu SlangResult=%zu\n",
        sizeof(SlangCompileTarget), sizeof(SlangProfileID), sizeof(SlangTargetFlags),
        sizeof(SlangFloatingPointMode), sizeof(SlangLineDirectiveMode),
        sizeof(SlangMatrixLayoutMode), sizeof(SessionFlags), sizeof(SlangResult));

    printf("targets: UNKNOWN=%d SPIRV=%d SPIRV_ASM=%d HLSL=%d GLSL=%d WGSL=%d METAL=%d "
           "METAL_LIB=%d DXIL=%d CUDA=%d\n",
        SLANG_TARGET_UNKNOWN, SLANG_SPIRV, SLANG_SPIRV_ASM, SLANG_HLSL, SLANG_GLSL, SLANG_WGSL,
        SLANG_METAL, SLANG_METAL_LIB, SLANG_DXIL, SLANG_CUDA_SOURCE);
    return 0;
}
