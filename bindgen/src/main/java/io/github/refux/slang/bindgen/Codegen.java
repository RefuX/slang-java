package io.github.refux.slang.bindgen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * slang-bindgen Stage B: reads the committed API model (api/slang-api.json, produced by
 * extract_api.py) and emits the {@code io.github.refux.slang.ffi.gen} package — COM interface
 * dispatch, struct accessors, enum constants, and C-export downcalls (DESIGN.md §9).
 *
 * <p>Generated raw methods are <em>static</em>, jextract-style, taking the COM object pointer as
 * the leading {@code self} argument. That keeps the generated layer mechanically faithful and
 * collision-free; the hand-written classes in {@code ffi} layer ownership and ergonomics on top.
 *
 * <p>Usage: {@code ./gradlew :bindgen:run --args="api/slang-api.json slang/src/main/java"}
 */
public final class Codegen {
    static final String PACKAGE = "io.github.refux.slang.ffi.gen";

    static final Set<String> JAVA_KEYWORDS = Set.of(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
        "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
        "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
        "interface", "long", "native", "new", "package", "private", "protected", "public",
        "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
        "throw", "throws", "transient", "try", "void", "volatile", "while", "record", "sealed",
        "yields", "var");

    final Map<String, Object> model;
    final Path outDir;
    final Map<String, Long> structSizes = new LinkedHashMap<>();
    final List<String> emitted = new ArrayList<>();
    final String slangVersion;

    Codegen(Map<String, Object> model, Path outDir) {
        this.model = model;
        this.outDir = outDir;
        this.slangVersion = (String) model.get("slangVersion");
        for (Object s : Json.array(model.get("structs"))) {
            Map<String, Object> struct = Json.object(s);
            structSizes.put((String) struct.get("name"), (Long) struct.get("size"));
        }
    }

    static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: Codegen <api/slang-api.json> <java-source-root>");
            System.exit(2);
        }
        Map<String, Object> model = Json.object(Json.parse(Files.readString(Path.of(args[0]))));
        Path outDir = Path.of(args[1]).resolve(PACKAGE.replace('.', '/'));
        Files.createDirectories(outDir);

        Codegen g = new Codegen(model, outDir);
        g.emitFfiSupport();
        for (Object e : Json.array(model.get("enums"))) {
            g.emitEnum(Json.object(e));
        }
        for (Object s : Json.array(model.get("structs"))) {
            g.emitStruct(Json.object(s));
        }
        for (Object i : Json.array(model.get("interfaces"))) {
            g.emitInterface(Json.object(i));
        }
        g.emitFunctions("SlangAPI", "core",
            "Downcalls for slang.h's modern extern-C entry points.");
        g.emitFunctions("SlangReflectionAPI", "reflection",
            "Downcalls for the spReflection* C ABI — the live surface behind slang.h's inline "
                + "C++ reflection wrapper classes (DESIGN.md §3.3).");
        g.emitReflectionWrappers(
            Path.of(args[1]).resolve("io/github/refux/slang"));
        System.out.println("generated " + g.emitted.size() + " files");
    }

    // ---------------------------------------------------------------- type vocabulary mapping

    /** One entry of the extractor's type vocabulary mapped to Java. */
    record JType(String carrier, String layout, char sig, boolean isCLong) {}

    JType jtype(Map<String, Object> t) {
        String k = (String) t.get("k");
        return switch (k) {
            case "void" -> new JType("void", null, 'V', false);
            case "bool" -> new JType("boolean", "JAVA_BOOLEAN", 'Z', false);
            case "i8", "u8" -> new JType("byte", "JAVA_BYTE", 'B', false);
            case "i16", "u16" -> new JType("short", "JAVA_SHORT", 'S', false);
            case "i32", "u32" -> new JType("int", "JAVA_INT", 'I', false);
            case "i64", "u64" -> new JType("long", "JAVA_LONG", 'J', false);
            case "f32" -> new JType("float", "JAVA_FLOAT", 'F', false);
            case "f64" -> new JType("double", "JAVA_DOUBLE", 'D', false);
            case "usize" -> new JType("long", "FfiSupport.C_SIZE_T", 'z', false);
            // The one platform-width scalar (4 bytes on LLP64 Windows, 8 elsewhere): carried as
            // a Java long, with the handle adapted via explicitCastArguments.
            case "long", "ulong" -> new JType("long", "FfiSupport.C_LONG", 'l', true);
            case "ptr" -> new JType("MemorySegment", "ADDRESS", 'A', false);
            case "enum" -> jtype(Json.object(t.get("repr")));
            default -> throw new IllegalArgumentException("no carrier for vocab kind: " + k);
        };
    }

    long byteSize(Map<String, Object> t) {
        String k = (String) t.get("k");
        return switch (k) {
            case "bool", "i8", "u8" -> 1;
            case "i16", "u16" -> 2;
            case "i32", "u32", "f32" -> 4;
            case "i64", "u64", "f64", "usize", "ptr" -> 8;
            case "enum" -> byteSize(Json.object(t.get("repr")));
            case "array" -> byteSize(Json.object(t.get("elem"))) * (Long) t.get("n");
            case "struct" -> structSizes.get((String) t.get("name"));
            default -> throw new IllegalArgumentException("no size for vocab kind: " + k);
        };
    }

    // -------------------------------------------------------------------------- file plumbing

    void write(String className, String body) throws IOException {
        write(outDir, PACKAGE, className, body);
    }

    void write(Path dir, String pkg, String className, String body) throws IOException {
        if (emitted.contains(pkg + "." + className)) {
            throw new IllegalStateException("duplicate generated class name: " + className);
        }
        emitted.add(pkg + "." + className);
        String content = "// GENERATED by slang-bindgen from api/slang-api.json (Slang "
            + slangVersion + "). DO NOT EDIT.\n"
            + "// Regenerate: see CLAUDE.md / DESIGN.md §9.\n"
            + "package " + pkg + ";\n\n" + body;
        Files.writeString(dir.resolve(className + ".java"), content);
    }

    static String javadoc(String indent, String... paragraphs) {
        StringBuilder sb = new StringBuilder(indent + "/**\n");
        boolean first = true;
        for (String p : paragraphs) {
            if (p == null || p.isBlank()) {
                continue;
            }
            if (!first) {
                sb.append(indent).append(" *\n");
            }
            first = false;
            for (String line : escapeDoc(p).split("\n")) {
                sb.append(indent).append(" * ").append(line).append('\n');
            }
        }
        if (first) {
            return "";
        }
        return sb.append(indent).append(" */\n").toString();
    }

    static String escapeDoc(String doc) {
        return doc.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("*/", "*&#47;").replace("@", "&#64;");
    }

    static String identifier(String name) {
        return JAVA_KEYWORDS.contains(name) ? name + "_" : name;
    }

    // --------------------------------------------------------------------------------- pieces

    void emitFfiSupport() throws IOException {
        write("FfiSupport", """
            import java.lang.foreign.FunctionDescriptor;
            import java.lang.foreign.Linker;
            import java.lang.foreign.MemorySegment;
            import java.lang.foreign.ValueLayout;
            import java.lang.invoke.MethodHandle;
            import java.lang.invoke.MethodHandles;
            import java.lang.invoke.MethodType;
            import io.github.refux.slang.loader.SlangLibrary;

            /** Shared plumbing for the generated bindings: linker, canonical layouts, COM vtable
             * dispatch, and downcall handle construction. */
            public final class FfiSupport {
                private FfiSupport() {}

                public static final Linker LINKER = Linker.nativeLinker();

                /** size_t — 8 bytes on every supported ABI, resolved rather than assumed. */
                public static final ValueLayout.OfLong C_SIZE_T =
                    (ValueLayout.OfLong) LINKER.canonicalLayouts().get("size_t");

                /** C long — the one platform-width scalar in the Slang API (4 bytes on LLP64
                 * Windows, 8 elsewhere). Handles touching it are adapted to Java long. */
                public static final ValueLayout C_LONG =
                    (ValueLayout) LINKER.canonicalLayouts().get("long");

                /** Reads the function pointer in vtable slot {@code slot} of COM object
                 * {@code self} (whose first field is the vtable pointer). */
                public static MemorySegment fnPtr(MemorySegment self, int slot) {
                    MemorySegment vtable = self
                        .reinterpret(ValueLayout.ADDRESS.byteSize())
                        .get(ValueLayout.ADDRESS, 0)
                        .reinterpret((slot + 1L) * ValueLayout.ADDRESS.byteSize());
                    return vtable.getAtIndex(ValueLayout.ADDRESS, slot);
                }

                /** Bound downcall handle for an exported C function. */
                public static MethodHandle downcall(String symbol, FunctionDescriptor descriptor) {
                    return LINKER.downcallHandle(SlangLibrary.get().find(symbol), descriptor);
                }

                /** Unbound downcall handle (leading argument = target function address), used
                 * for vtable dispatch. */
                public static MethodHandle unbound(FunctionDescriptor descriptor) {
                    return LINKER.downcallHandle(descriptor);
                }

                /** Adapts a handle whose native shape involves platform-width C long so that its
                 * Java type uses long uniformly on every platform. */
                public static MethodHandle adaptCLongs(MethodHandle handle, MethodType target) {
                    return MethodHandles.explicitCastArguments(handle, target);
                }

                public static RuntimeException rethrow(Throwable t) {
                    if (t instanceof RuntimeException e) {
                        return e;
                    }
                    if (t instanceof Error e) {
                        throw e;
                    }
                    return new IllegalStateException("Unexpected exception from native call", t);
                }
            }
            """);
    }

    void emitEnum(Map<String, Object> e) throws IOException {
        String name = (String) e.get("name");
        JType repr = jtype(Json.object(e.get("repr")));
        StringBuilder sb = new StringBuilder();
        sb.append(javadoc("", (String) e.get("doc"),
            "Values of C enum {@code " + name + "} (" + repr.carrier() + "-sized)."));
        sb.append("public final class ").append(name).append(" {\n");
        sb.append("    private ").append(name).append("() {}\n\n");
        for (Object vo : Json.array(e.get("values"))) {
            Map<String, Object> v = Json.object(vo);
            long value = (Long) v.get("value");
            sb.append(javadoc("    ", (String) v.get("doc")));
            if ("long".equals(repr.carrier())) {
                sb.append("    public static final long ").append(v.get("name"))
                    .append(" = ").append(value).append("L;\n");
            } else {
                sb.append("    public static final int ").append(v.get("name"))
                    .append(" = ").append((int) value).append(";\n");
            }
        }
        sb.append("}\n");
        write(name, sb.toString());
    }

    void emitStruct(Map<String, Object> s) throws IOException {
        String name = (String) s.get("name");
        long size = (Long) s.get("size");
        long align = (Long) s.get("align");
        StringBuilder sb = new StringBuilder();
        sb.append("import java.lang.foreign.Arena;\n");
        sb.append("import java.lang.foreign.MemorySegment;\n");
        sb.append("import static java.lang.foreign.ValueLayout.*;\n\n");
        sb.append(javadoc("", (String) s.get("doc"),
            "Layout of C struct {@code " + name + "} (size " + size + ", alignment " + align
                + "; offsets clang-verified across all supported ABIs). Accessors are static and"
                + " offset-based, so overlapping union members need no special casing."));
        sb.append("public final class ").append(name).append(" {\n");
        sb.append("    private ").append(name).append("() {}\n\n");
        sb.append("    public static final long SIZE = ").append(size).append(";\n");
        sb.append("    public static final long ALIGNMENT = ").append(align).append(";\n\n");

        StringBuilder accessors = new StringBuilder();
        String structureSizeInit = "";
        for (Object fo : Json.array(s.get("fields"))) {
            Map<String, Object> f = Json.object(fo);
            String fname = (String) f.get("name");
            long offset = (Long) f.get("offset");
            Map<String, Object> ft = Json.object(f.get("type"));
            String upper = fname.substring(0, 1).toUpperCase() + fname.substring(1);
            String offsetName = "OFFSET_" + fname;
            sb.append("    public static final long ").append(offsetName)
                .append(" = ").append(offset).append(";\n");

            String kind = (String) ft.get("k");
            accessors.append(javadoc("    ", (String) f.get("doc")));
            if (kind.equals("array") || kind.equals("struct")) {
                long fieldSize = byteSize(ft);
                accessors.append("    public static MemorySegment ").append(identifier(fname))
                    .append("(MemorySegment self) {\n")
                    .append("        return self.asSlice(").append(offsetName).append(", ")
                    .append(fieldSize).append(");\n    }\n\n");
            } else {
                JType jt = jtype(ft);
                accessors.append("    public static ").append(jt.carrier()).append(" get")
                    .append(upper).append("(MemorySegment self) {\n")
                    .append("        return self.get(").append(jt.layout()).append(", ")
                    .append(offsetName).append(");\n    }\n\n");
                accessors.append("    public static void set").append(upper)
                    .append("(MemorySegment self, ").append(jt.carrier()).append(" value) {\n")
                    .append("        self.set(").append(jt.layout()).append(", ")
                    .append(offsetName).append(", value);\n    }\n\n");
                if (fname.equals("structureSize")) {
                    // SIZE is a long constant; cast only when the field's carrier is narrower
                    // (a cast to long would be redundant and trips -Xlint:cast).
                    String sizeExpr = jt.carrier().equals("long") ? "SIZE" : "(" + jt.carrier() + ") SIZE";
                    structureSizeInit =
                        "        set" + upper + "(self, " + sizeExpr + "); // versioned-struct convention\n";
                }
            }
        }
        sb.append('\n');
        boolean versioned = !structureSizeInit.isEmpty();
        sb.append("    /** Allocates one zero-initialized instance")
            .append(versioned ? " (structureSize prefilled, per the versioned-struct convention)"
                : "")
            .append(". */\n");
        sb.append("    public static MemorySegment allocate(Arena arena) {\n");
        sb.append("        MemorySegment self = arena.allocate(SIZE, ALIGNMENT);\n");
        sb.append(structureSizeInit);
        sb.append("        return self;\n    }\n\n");
        sb.append("    /** Allocates a contiguous zero-initialized array of {@code count} "
            + "instances. */\n");
        sb.append("    public static MemorySegment allocateArray(Arena arena, int count) {\n");
        sb.append("        MemorySegment array = arena.allocate(SIZE * count, ALIGNMENT);\n");
        if (versioned) {
            sb.append("        for (int i = 0; i < count; i++) {\n");
            sb.append("    ").append(structureSizeInit.replace("(self,", "(element(array, i),"));
            sb.append("        }\n");
        }
        sb.append("        return array;\n    }\n\n");
        sb.append("    /** The {@code index}-th element of an {@link #allocateArray} array. */\n");
        sb.append("    public static MemorySegment element(MemorySegment array, int index) {\n");
        sb.append("        return array.asSlice(SIZE * index, SIZE);\n    }\n\n");
        sb.append(accessors);
        sb.append("}\n");
        write(name, sb.toString());
    }

    void emitInterface(Map<String, Object> iface) throws IOException {
        String name = (String) iface.get("name");
        String base = (String) iface.get("base");
        String uuid = (String) iface.get("uuid");
        StringBuilder sb = new StringBuilder();
        sb.append("import java.lang.foreign.FunctionDescriptor;\n");
        sb.append("import java.lang.foreign.MemorySegment;\n");
        sb.append("import java.lang.invoke.MethodHandle;\n");
        sb.append("import java.lang.invoke.MethodType;\n");
        sb.append("import static java.lang.foreign.ValueLayout.*;\n\n");
        boolean experimental = Boolean.TRUE.equals(iface.get("experimental"));
        sb.append(javadoc("", (String) iface.get("doc"),
            "Raw vtable dispatch for COM interface {@code " + name + "}"
                + (base != null ? " (base {@code " + base + "}" : " (root interface"
                ) + ", vtable size " + iface.get("vtableSize") + "). Methods are static and take"
                + " the COM object pointer as {@code self}; inherited slots live on the base"
                + " interface's class. The hand-written wrappers in {@code ffi} add ownership"
                + " and ergonomics.",
            experimental ? "EXPERIMENTAL: Slang does not promise stability for this interface."
                : null));
        sb.append("public final class ").append(name).append(" {\n");
        sb.append("    private ").append(name).append("() {}\n\n");
        if (uuid != null) {
            sb.append("    /** SLANG_COM_INTERFACE uuid, for queryInterface. */\n");
            sb.append("    public static final String IID = \"").append(uuid).append("\";\n\n");
        }

        Map<String, String> handles = new TreeMap<>();
        StringBuilder methods = new StringBuilder();
        for (Object mo : Json.array(iface.get("methods"))) {
            Map<String, Object> m = Json.object(mo);
            String mname = (String) m.get("name");
            long slot = (Long) m.get("slot");
            JType ret = jtype(Json.object(m.get("ret")));
            List<JType> params = new ArrayList<>();
            List<String> paramNames = new ArrayList<>();
            for (Object po : Json.array(m.get("params"))) {
                Map<String, Object> p = Json.object(po);
                params.add(jtype(Json.object(p.get("type"))));
                paramNames.add(identifier((String) p.get("name")));
            }
            String handle = handleFor(handles, ret, params, true);

            sb.append("    public static final int VT_").append(mname)
                .append(" = ").append(slot).append(";\n");

            methods.append(javadoc("    ", (String) m.get("doc"),
                "Vtable slot {@value #VT_" + mname + "}."));
            methods.append("    public static ").append(ret.carrier()).append(' ')
                .append(identifier(mname)).append("(MemorySegment self");
            for (int i = 0; i < params.size(); i++) {
                methods.append(", ").append(params.get(i).carrier()).append(' ')
                    .append(paramNames.get(i));
            }
            methods.append(") {\n        try {\n            ");
            if (!ret.carrier().equals("void")) {
                methods.append("return (").append(ret.carrier()).append(") ");
            }
            methods.append(handle).append(".invokeExact(FfiSupport.fnPtr(self, VT_")
                .append(mname).append("), self");
            for (String p : paramNames) {
                methods.append(", ").append(p);
            }
            methods.append(");\n        } catch (Throwable t) {\n")
                .append("            throw FfiSupport.rethrow(t);\n        }\n    }\n\n");
        }
        sb.append('\n');
        for (String decl : handles.values()) {
            sb.append(decl);
        }
        sb.append('\n').append(methods).append("}\n");
        write(name, sb.toString());
    }

    void emitFunctions(String className, String group, String doc) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("import java.lang.foreign.FunctionDescriptor;\n");
        sb.append("import java.lang.foreign.MemorySegment;\n");
        sb.append("import java.lang.invoke.MethodHandle;\n");
        sb.append("import java.lang.invoke.MethodType;\n");
        sb.append("import static java.lang.foreign.ValueLayout.*;\n\n");
        sb.append(javadoc("", doc,
            "Downcall handles bind lazily (per-function holder classes): a symbol slang.h "
                + "declares but the loaded library does not export fails only when that "
                + "function is first called."));
        sb.append("public final class ").append(className).append(" {\n");
        sb.append("    private ").append(className).append("() {}\n\n");

        StringBuilder methods = new StringBuilder();
        int count = 0;
        for (Object fo : Json.array(model.get("functions"))) {
            Map<String, Object> f = Json.object(fo);
            if (!group.equals(f.get("group"))) {
                continue;
            }
            count++;
            String fname = (String) f.get("name");
            JType ret = jtype(Json.object(f.get("ret")));
            List<JType> params = new ArrayList<>();
            List<String> paramNames = new ArrayList<>();
            for (Object po : Json.array(f.get("params"))) {
                Map<String, Object> p = Json.object(po);
                Map<String, Object> pt = Json.object(p.get("type"));
                if ("struct".equals(pt.get("k"))) {
                    throw new IllegalStateException(fname + ": by-value struct params on C "
                        + "functions are not emitted yet; extend Codegen when Slang adds one");
                }
                params.add(jtype(pt));
                paramNames.add(identifier((String) p.get("name")));
            }
            // Lazy holder per function: slang.h declares a few functions the shipped library
            // does not export (e.g. slang_getEmbeddedCoreModule in v2026.13), so binding must
            // not happen until first call.
            String handleName = "H_" + fname;
            sb.append("    private static final class ").append(handleName)
                .append(" {\n        static final MethodHandle MH =\n            ")
                .append(boundHandleExpr(fname, ret, params)).append(";\n    }\n");

            methods.append(javadoc("    ", (String) f.get("doc")));
            methods.append("    public static ").append(ret.carrier()).append(' ')
                .append(identifier(fname)).append('(');
            for (int i = 0; i < params.size(); i++) {
                if (i > 0) {
                    methods.append(", ");
                }
                methods.append(params.get(i).carrier()).append(' ').append(paramNames.get(i));
            }
            methods.append(") {\n        try {\n            ");
            if (!ret.carrier().equals("void")) {
                methods.append("return (").append(ret.carrier()).append(") ");
            }
            methods.append(handleName).append(".MH.invokeExact(")
                .append(String.join(", ", paramNames))
                .append(");\n        } catch (Throwable t) {\n")
                .append("            throw FfiSupport.rethrow(t);\n        }\n    }\n\n");
        }
        sb.append('\n').append(methods).append("}\n");
        write(className, sb.toString());
        System.out.println("  " + className + ": " + count + " functions");
    }

    // ------------------------------------------------------------- reflection wrapper classes

    static final String REFLECTION_GEN_PACKAGE = "io.github.refux.slang.gen";
    static final String VENEER_PACKAGE = "io.github.refux.slang";

    /**
     * Emits one generated instance class per C++ reflection wrapper class into the
     * {@code .gen} package (mirroring the {@code ffi}/{@code ffi.gen} layering), derived from
     * the model's {@code reflectionWrappers} mapping (DESIGN.md §9): each single-call inline
     * method of slang.h's reflection classes becomes a typed Java method forwarding to the
     * corresponding {@code SlangReflectionAPI} downcall. Reflection pointers are typed via the
     * self-derived pointee→class table; {@code const char*} becomes String; every wrapper
     * carries an {@code owner} reference that keeps the native data's owner reachable.
     * Hand-written subclasses in the parent package (same simple name) add ergonomics.
     */
    void emitReflectionWrappers(Path rootPkgDir) throws IOException {
        rootPkgDir = rootPkgDir.resolve("gen");
        Files.createDirectories(rootPkgDir);
        Map<String, Map<String, Object>> fnByName = new LinkedHashMap<>();
        for (Object fo : Json.array(model.get("functions"))) {
            Map<String, Object> f = Json.object(fo);
            fnByName.put((String) f.get("name"), f);
        }

        // Group mappings per class as (method, callee) pairs. C++ overloads (e.g.
        // getBindingSpace() vs getBindingSpace(category)) become Java overloads; only exact
        // duplicates (same method mapped to the same callee via a default-arg convenience)
        // collapse. True Java-signature collisions are deduped at emission time.
        Map<String, Map<String, String>> classes = new TreeMap<>();
        // pointee typedef name -> wrapper class, derived from each callee's self parameter.
        Map<String, String> pointeeToClass = new LinkedHashMap<>();
        for (Object wo : Json.array(model.get("reflectionWrappers"))) {
            Map<String, Object> w = Json.object(wo);
            String cls = (String) w.get("class");
            String method = (String) w.get("method");
            String callee = (String) w.get("callee");
            Map<String, Object> fn = fnByName.get(callee);
            if (fn == null) {
                continue; // callee not in the bound function set
            }
            List<Object> params = Json.array(fn.get("params"));
            if (params.isEmpty()) {
                continue;
            }
            Map<String, Object> selfType = Json.object(Json.object(params.get(0)).get("type"));
            if ("ptr".equals(selfType.get("k"))) {
                pointeeToClass.putIfAbsent(pointeeName(selfType), cls);
            }
            // Key by method + callee so overload variants coexist; TreeMap keeps output stable.
            classes.computeIfAbsent(cls, k -> new TreeMap<>())
                .putIfAbsent(method + " " + callee, callee);
        }

        for (Map.Entry<String, Map<String, String>> entry : classes.entrySet()) {
            emitWrapperClass(rootPkgDir, entry.getKey(), entry.getValue(), fnByName, pointeeToClass);
        }
        System.out.println("  reflection wrappers: " + classes.size() + " classes, "
            + classes.values().stream().mapToInt(Map::size).sum() + " methods");
    }

    static String pointeeName(Map<String, Object> ptrType) {
        return ((String) ptrType.get("to")).replace("const", "").replace("struct", "").trim();
    }

    void emitWrapperClass(Path dir, String cls, Map<String, String> methods,
            Map<String, Map<String, Object>> fnByName, Map<String, String> pointeeToClass)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("import io.github.refux.slang.ffi.SlangNative;\n");
        sb.append("import io.github.refux.slang.ffi.gen.SlangReflectionAPI;\n");
        sb.append("import java.lang.foreign.Arena;\n");
        sb.append("import java.lang.foreign.MemorySegment;\n\n");
        sb.append(javadoc("",
            "Generated base of {@code slang::" + cls + "}: each method forwards to the "
                + "spReflection* downcall that slang.h's inline C++ wrapper compiles to. "
                + "Reflection data is owned by the component/session it came from; the "
                + "{@code owner} reference keeps that owner reachable. The hand-written "
                + "subclass {@code " + VENEER_PACKAGE + "." + cls + "} adds ergonomics."));
        sb.append("public abstract class ").append(cls).append(" {\n");
        sb.append("    protected final MemorySegment self;\n");
        sb.append("    protected final Object owner;\n\n");
        sb.append("    protected ").append(cls).append("(MemorySegment self, Object owner) {\n");
        sb.append("        this.self = self;\n        this.owner = owner;\n    }\n\n");
        sb.append("    /** The raw native reflection pointer (borrowed; owned by the program). */\n");
        sb.append("    public final MemorySegment segment() {\n        return self;\n    }\n\n");

        Set<String> emittedSignatures = new java.util.HashSet<>();
        for (Map.Entry<String, String> m : methods.entrySet()) {
            String method = m.getKey().substring(0, m.getKey().indexOf(' '));
            Map<String, Object> fn = fnByName.get(m.getValue());
            List<Object> params = Json.array(fn.get("params"));
            Map<String, Object> retType = Json.object(fn.get("ret"));

            List<String> sigParams = new ArrayList<>();
            List<String> callArgs = new ArrayList<>();
            boolean needsArena = false;
            callArgs.add("self");
            for (int i = 1; i < params.size(); i++) {
                Map<String, Object> p = Json.object(params.get(i));
                String pname = identifier((String) p.get("name"));
                Map<String, Object> pt = Json.object(p.get("type"));
                String wrapped = "ptr".equals(pt.get("k")) ? wrapperFor(pt, pointeeToClass) : null;
                if (wrapped != null && wrapped.equals("String")) {
                    sigParams.add("String " + pname);
                    callArgs.add("arena.allocateFrom(" + pname + ")");
                    needsArena = true;
                } else if (wrapped != null && !wrapped.equals("MemorySegment")) {
                    // Veneer types by FQN: their simple names shadow ours in this package.
                    sigParams.add(VENEER_PACKAGE + "." + wrapped + " " + pname);
                    // The C ABI accepts null pointers (default-arg conveniences rely on it).
                    callArgs.add("(" + pname + " == null ? MemorySegment.NULL : " + pname + ".self)");
                } else {
                    JType jt = jtype(pt);
                    sigParams.add(jt.carrier() + " " + pname);
                    callArgs.add(pname);
                }
            }

            String call = "SlangReflectionAPI." + m.getValue() + "(" + String.join(", ", callArgs) + ")";
            String retWrapper = "ptr".equals(retType.get("k"))
                ? wrapperFor(retType, pointeeToClass) : null;
            String retDecl;
            String body;
            if (retWrapper != null && retWrapper.equals("String")) {
                retDecl = "String";
                body = "        return SlangNative.readUtf8(" + call + ");\n";
            } else if (retWrapper != null && !retWrapper.equals("MemorySegment")) {
                retDecl = VENEER_PACKAGE + "." + retWrapper;
                body = "        MemorySegment result = " + call + ";\n"
                    + "        return result.address() == 0 ? null : new " + retDecl
                    + "(result, owner);\n";
            } else {
                JType jt = jtype(retType);
                retDecl = jt.carrier();
                body = "        " + ("void".equals(jt.carrier()) ? "" : "return ") + call + ";\n";
            }
            if (needsArena) {
                body = "        try (Arena arena = Arena.ofConfined()) {\n"
                    + body.indent(4).stripTrailing() + "\n        }\n";
            }

            String javaSignature = method + "(" + String.join(",", sigParams) + ")";
            if (!emittedSignatures.add(javaSignature)) {
                System.out.println("  note: skipped colliding overload " + cls + "."
                    + javaSignature + " -> " + m.getValue());
                continue;
            }
            sb.append("    /** Calls {@code ").append(m.getValue()).append("}. */\n");
            sb.append("    public ").append(retDecl).append(' ').append(identifier(method))
                .append('(').append(String.join(", ", sigParams)).append(") {\n")
                .append(body).append("    }\n\n");
        }
        sb.append("}\n");
        write(dir, REFLECTION_GEN_PACKAGE, cls, sb.toString());
    }

    /** String for char pointers, a wrapper class name for reflection pointers, else raw. */
    String wrapperFor(Map<String, Object> ptrType, Map<String, String> pointeeToClass) {
        String pointee = pointeeName(ptrType);
        if (pointee.equals("char")) {
            return "String";
        }
        return pointeeToClass.getOrDefault(pointee, "MemorySegment");
    }

    // ------------------------------------------------------------------------ handle building

    /** Returns the field name of the (deduplicated) unbound handle for this shape, adding its
     * declaration to {@code handles} on first use. */
    String handleFor(Map<String, String> handles, JType ret, List<JType> params, boolean vtable) {
        StringBuilder sig = new StringBuilder("MH_").append(ret.sig()).append('_').append('A');
        for (JType p : params) {
            sig.append(p.sig());
        }
        String fieldName = sig.toString();
        handles.computeIfAbsent(fieldName, key -> {
            List<JType> full = new ArrayList<>();
            full.add(new JType("MemorySegment", "ADDRESS", 'A', false)); // self
            full.addAll(params);
            String expr = "FfiSupport.unbound(" + descriptorExpr(ret, full) + ")";
            if (ret.isCLong() || full.stream().anyMatch(JType::isCLong)) {
                expr = "FfiSupport.adaptCLongs(\n            " + expr + ",\n            "
                    + methodTypeExpr(ret, full, true) + ")";
            }
            return "    private static final MethodHandle " + key + " =\n        " + expr
                + ";\n";
        });
        return fieldName;
    }

    String boundHandleExpr(String symbol, JType ret, List<JType> params) {
        String expr = "FfiSupport.downcall(\"" + symbol + "\", "
            + descriptorExpr(ret, params) + ")";
        if (ret.isCLong() || params.stream().anyMatch(JType::isCLong)) {
            expr = "FfiSupport.adaptCLongs(\n            " + expr + ",\n            "
                + methodTypeExpr(ret, params, false) + ")";
        }
        return expr;
    }

    String descriptorExpr(JType ret, List<JType> params) {
        List<String> layouts = new ArrayList<>();
        for (JType p : params) {
            layouts.add(p.layout());
        }
        String joined = String.join(", ", layouts);
        return ret.carrier().equals("void")
            ? "FunctionDescriptor.ofVoid(" + joined + ")"
            : "FunctionDescriptor.of(" + ret.layout() + (joined.isEmpty() ? "" : ", " + joined)
                + ")";
    }

    /** MethodType matching the adapted handle: unbound handles carry a leading target address. */
    String methodTypeExpr(JType ret, List<JType> params, boolean leadingTarget) {
        List<String> types = new ArrayList<>();
        if (leadingTarget) {
            types.add("MemorySegment.class");
        }
        for (JType p : params) {
            types.add(classOf(p.carrier()));
        }
        return "MethodType.methodType(" + classOf(ret.carrier())
            + (types.isEmpty() ? "" : ", " + String.join(", ", types)) + ")";
    }

    static String classOf(String carrier) {
        return carrier.equals("MemorySegment") ? "MemorySegment.class" : carrier + ".class";
    }
}
