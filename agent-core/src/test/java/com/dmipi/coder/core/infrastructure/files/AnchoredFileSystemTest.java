package com.dmipi.coder.core.infrastructure.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnchoredFileSystemTest {

    @TempDir
    private Path project;

    @Test
    @DisplayName("relative paths resolve inside the project; escapes are refused")
    void should_confine_paths_to_the_project() {
        // Given
        final AnchoredFileSystem files = new AnchoredFileSystem(project);

        // Then
        assertThat(files.resolve("src/Main.java")).isEqualTo(project.resolve("src/Main.java"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> files.resolve("../outside.txt"))
                .withMessageContaining("escapes");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> files.resolve("/etc/passwd"))
                .withMessageContaining("escapes");
    }

    @Test
    @DisplayName("write creates parent directories; read returns what was written")
    void should_write_and_read_back() {
        // Given
        final AnchoredFileSystem files = new AnchoredFileSystem(project);
        final Path path = files.resolve("deep/nested/file.txt");

        // When
        files.write(path, "content");

        // Then
        assertThat(files.read(path)).isEqualTo("content");
        assertThat(files.exists(path)).isTrue();
    }

    @Test
    @DisplayName("listing marks directories with a trailing slash, sorted")
    void should_list_sorted_with_directory_markers() throws IOException {
        // Given
        Files.createDirectory(project.resolve("src"));
        Files.writeString(project.resolve("a.txt"), "");

        // When / Then
        assertThat(new AnchoredFileSystem(project).list(project)).containsExactly("a.txt", "src/");
    }

    @Test
    @DisplayName("delete removes a directory with everything beneath it; a missing path is a no-op")
    void should_delete_a_directory_tree() {
        // Given
        final AnchoredFileSystem files = new AnchoredFileSystem(project);
        files.write(files.resolve("tree/nested/file.txt"), "content");

        // When
        files.delete(files.resolve("tree"));
        files.delete(files.resolve("already-gone"));

        // Then
        assertThat(files.exists(files.resolve("tree"))).isFalse();
    }

    @Test
    @DisplayName("reading a missing file fails with a message naming the path")
    void should_fail_reading_a_missing_file() {
        // Given
        final AnchoredFileSystem files = new AnchoredFileSystem(project);

        // When / Then
        assertThatExceptionOfType(UncheckedIOException.class)
                .isThrownBy(() -> files.read(project.resolve("missing.txt")))
                .withMessageContaining("missing.txt");
    }
}
