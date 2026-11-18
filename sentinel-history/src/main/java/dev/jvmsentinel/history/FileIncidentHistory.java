package dev.jvmsentinel.history;

import dev.jvmsentinel.ai.EvidenceBundle;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.DirectoryStream;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class FileIncidentHistory implements IncidentHistoryRepository {
    public static final String DEFAULT_DIRECTORY = ".local-build/history";
    static final String SUFFIX = ".incident.json";
    static final int MAX_BYTES = 16 * 1024;
    static final int SCAN_LIMIT = 512;
    private static final int DEFAULT_RETENTION = 200;
    private static final Object WRITER_MONITOR = new Object();
    private static final Comparator<StoredReport> NEWEST_FIRST =
            Comparator.comparing((StoredReport report) -> report.evidence().capturedAt()).reversed()
                    .thenComparing(StoredReport::reportId);

    private final Path root;
    private final int retention;

    public FileIncidentHistory(Path root) {
        this(root, DEFAULT_RETENTION);
    }

    public FileIncidentHistory(Path root, int retention) {
        this.root = root.toAbsolutePath().normalize();
        if (this.root.getParent() == null || retention < 1 || retention > DEFAULT_RETENTION) {
            throw new IllegalArgumentException("Invalid history configuration");
        }
        this.retention = retention;
    }

    @Override
    public StoredReport save(EvidenceBundle evidence, StoredReport.Origin origin, StoredReport.Advisory advisory)
            throws IOException {
        var report = new StoredReport(UUID.randomUUID(), StoredReport.VERSION, Instant.now(), origin, evidence, advisory);
        byte[] bytes = ReportJson.write(report).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("History report too large");
        synchronized (WRITER_MONITOR) {
            Files.createDirectories(root);
            var dir = open();
            try (var channel = lockChannel(dir); var lock = channel.lock()) {
                var current = scan(dir);
                if (current.truncated()) throw new IOException("History scan limit reached");
                Path target = dir.path().resolve(name(report.reportId()));
                dir.check();
                Path temporary = Files.createTempFile(dir.path(), ".report-", ".tmp");
                try {
                    writeFile(dir, temporary, bytes);
                    dir.check();
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Report ID already exists");
                    }
                    try {
                        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException unsupported) {
                        // A same-directory move preserves a complete staging document when supported.
                        Files.move(temporary, target);
                    }
                    dir.check();
                } finally {
                    dir.check();
                    Files.deleteIfExists(temporary);
                }
                var retained = new ArrayList<>(current.reports());
                retained.add(report);
                retained.sort(NEWEST_FIRST);
                trim(dir, retained);
            }
        }
        return report;
    }

    @Override
    public Optional<StoredReport> findById(UUID id) throws IOException {
        try {
            var dir = open();
            return read(dir, name(id), id);
        } catch (NoSuchFileException | Corrupt | Oversized | ReportJson.UnsupportedVersion failure) {
            return Optional.empty();
        }
    }

    @Override
    public QueryResult query() throws IOException {
        try {
            var dir = open();
            return scan(dir);
        } catch (NoSuchFileException missing) {
            return new QueryResult(List.of(), 0, 0, 0, false);
        }
    }

    @Override
    public int enforceRetention() throws IOException {
        synchronized (WRITER_MONITOR) {
            try {
                var dir = open();
                try (var channel = lockChannel(dir); var lock = channel.lock()) {
                    var current = scan(dir);
                    if (current.truncated()) throw new IOException("History scan limit reached");
                    return trim(dir, current.reports());
                }
            } catch (NoSuchFileException missing) {
                return 0;
            }
        }
    }

    private int trim(Directory dir, List<StoredReport> ordered) throws IOException {
        int removed = 0;
        for (int i = retention; i < ordered.size(); i++) {
            dir.check();
            if (Files.deleteIfExists(dir.path().resolve(name(ordered.get(i).reportId())))) removed++;
        }
        return removed;
    }

    private Directory open() throws IOException {
        Path path = root.getParent().toRealPath().resolve(root.getFileName());
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory()) throw new IOException("History root is not a real directory");
        var directory = new Directory(path, attributes.fileKey());
        directory.check();
        return directory;
    }

    private record Directory(Path path, Object key) {
        void check() throws IOException {
            var current = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!current.isDirectory() || !Objects.equals(current.fileKey(), key)
                    || !path.equals(path.toRealPath())) {
                throw new IOException("History directory changed during access");
            }
        }
    }

    private static FileChannel lockChannel(Directory dir) throws IOException {
        dir.check();
        Path lockFile = dir.path().resolve(".history.lock");
        if (Files.exists(lockFile, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(lockFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Invalid history lock");
        }
        return FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
    }

    private static void writeFile(Directory dir, Path file, byte[] bytes) throws IOException {
        dir.check();
        var options = Set.of(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try (var channel = FileChannel.open(file, options)) {
            dir.check();
            var buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    private QueryResult scan(Directory dir) throws IOException {
        var reports = new ArrayList<StoredReport>();
        int corrupt = 0;
        int unsupported = 0;
        int oversized = 0;
        int entries = 0;
        dir.check();
        try (DirectoryStream<Path> entriesInDirectory = Files.newDirectoryStream(dir.path())) {
            for (Path entry : entriesInDirectory) {
                if (++entries > SCAN_LIMIT) break;
                Path file = entry.getFileName();
                String filename = file.toString();
                if (!filename.endsWith(SUFFIX)) continue;
                try {
                    String rawId = filename.substring(0, filename.length() - SUFFIX.length());
                    UUID id = UUID.fromString(rawId);
                    if (!id.toString().equals(rawId)) throw new Corrupt();
                    read(dir, file, id).ifPresent(reports::add);
                } catch (ReportJson.UnsupportedVersion version) {
                    unsupported++;
                } catch (Oversized tooLarge) {
                    oversized++;
                } catch (IllegalArgumentException | Corrupt | AccessDeniedException malformed) {
                    corrupt++;
                } catch (NoSuchFileException removed) {
                    // A concurrent writer may have pruned this file after enumeration.
                }
            }
        }
        dir.check();
        reports.sort(NEWEST_FIRST);
        return new QueryResult(reports, corrupt, unsupported, oversized, entries > SCAN_LIMIT);
    }

    private static Optional<StoredReport> read(Directory dir, Path filename, UUID id)
            throws IOException, Corrupt, Oversized {
        dir.check();
        Path file = dir.path().resolve(filename);
        var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) throw new Corrupt();
        if (attributes.size() > MAX_BYTES) throw new Oversized();
        byte[] bytes;
        try (var channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             var input = Channels.newInputStream(channel)) {
            dir.check();
            var current = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!current.isRegularFile() || !Objects.equals(attributes.fileKey(), current.fileKey())) {
                throw new Corrupt();
            }
            bytes = input.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) throw new Oversized();
        try {
            var report = ReportJson.read(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
            if (!report.reportId().equals(id)) throw new Corrupt();
            return Optional.of(report);
        } catch (ReportJson.UnsupportedVersion unsupported) {
            throw unsupported;
        } catch (IllegalArgumentException | ArithmeticException | java.time.DateTimeException
                 | java.nio.charset.CharacterCodingException malformed) {
            throw new Corrupt();
        }
    }

    private static Path name(UUID id) { return Path.of(id + SUFFIX); }

    private static final class Corrupt extends Exception { }
    private static final class Oversized extends Exception { }
}
