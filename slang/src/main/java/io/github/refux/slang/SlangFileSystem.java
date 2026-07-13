package io.github.refux.slang;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Serves {@code import}/{@code #include} resolution for a {@link Session} from Java —
 * an engine's pack files, classpath resources, an in-memory map, whatever. Install via
 * {@link SessionBuilder#fileSystem}.
 *
 * <p>Slang probes candidate paths while resolving imports, so {@link #loadFile} is routinely
 * called for paths that don't exist — return null for those. Calls arrive on the compiling
 * thread; an instance shared across sessions must tolerate concurrent calls.
 */
@FunctionalInterface
public interface SlangFileSystem {

    /** Returns the file's contents, or null when no such file exists. */
    byte[] loadFile(String path) throws Exception;

    /** Serves UTF-8 sources from an in-memory map keyed by path (e.g. {@code "common.slang"}). */
    static SlangFileSystem ofMap(Map<String, String> files) {
        return path -> {
            String source = files.get(path);
            return source == null ? null : source.getBytes(StandardCharsets.UTF_8);
        };
    }

    /** Serves files from a directory tree rooted at {@code root}. */
    static SlangFileSystem ofPath(Path root) {
        return path -> {
            Path file = root.resolve(path).normalize();
            if (!file.startsWith(root.normalize()) || !Files.isRegularFile(file)) {
                return null; // outside the root, or absent
            }
            return Files.readAllBytes(file);
        };
    }
}
