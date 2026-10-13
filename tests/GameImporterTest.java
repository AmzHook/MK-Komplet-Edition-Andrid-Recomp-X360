import org.amzhook.mkimporter.GameImporter;
import org.amzhook.mkimporter.ZipImporter;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;

/** Host tests of archive routing, stream ownership, caching, and transaction behavior. */
public final class GameImporterTest {
    interface Checked { void run() throws Exception; }
    static int passed;
    static final int SECTOR = 2048;
    static final byte[] MAGIC = "MICROSOFT*XBOX*MEDIA".getBytes(StandardCharsets.US_ASCII);
    static final byte[] XEX = new byte[128];
    static final byte[] ASSET = new byte[]{91, 82, 73, 64, 55};
    static {
        System.arraycopy(new byte[]{'X','E','X','2'}, 0, XEX, 0, 4);
        for (int i = 4; i < XEX.length; i++) XEX[i] = (byte)(i * 3);
    }
    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static void test(String name, Checked action) throws Exception {
        action.run(); passed++; System.out.println("PASS ROUTER " + name);
    }
    static void u32(byte[] bytes, int offset, long value) {
        for (int i = 0; i < 4; i++) bytes[offset + i] = (byte)(value >>> (i * 8));
    }
    static void record(byte[] bytes, int offset, String name, long sector, long size,
                       boolean directory, int rightOffset) {
        byte[] encoded = name.getBytes(StandardCharsets.US_ASCII);
        bytes[offset + 2] = (byte)(rightOffset / 4);
        bytes[offset + 3] = (byte)(rightOffset / 4 >>> 8);
        u32(bytes, offset + 4, sector); u32(bytes, offset + 8, size);
        bytes[offset + 12] = (byte)(directory ? 0x10 : 0);
        bytes[offset + 13] = (byte)encoded.length;
        System.arraycopy(encoded, 0, bytes, offset + 14, encoded.length);
    }
    static byte[] image(long xexSector) {
        byte[] bytes = new byte[110 * SECTOR];
        System.arraycopy(MAGIC, 0, bytes, 32 * SECTOR, MAGIC.length);
        System.arraycopy(MAGIC, 0, bytes, 33 * SECTOR - MAGIC.length, MAGIC.length);
        u32(bytes, 32 * SECTOR + 20, 40); u32(bytes, 32 * SECTOR + 24, 128);
        record(bytes, 40 * SECTOR, "Asset", 42, 64, true, 64);
        record(bytes, 40 * SECTOR + 64, "DEFAULT.XEX", xexSector, XEX.length, false, 0);
        record(bytes, 42 * SECTOR, "data.bin", 102, ASSET.length, false, 0);
        if (xexSector < 110) System.arraycopy(XEX, 0, bytes, (int)xexSector * SECTOR, XEX.length);
        System.arraycopy(ASSET, 0, bytes, 102 * SECTOR, ASSET.length);
        return bytes;
    }
    static byte[] zip(boolean previous) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(previous ? "previous/default.xex" : "DEFAULT.XEX"));
            zip.write(XEX); zip.closeEntry();
            zip.putNextEntry(new ZipEntry(previous ? "previous/keep.bin" : "Asset/data.bin"));
            zip.write(ASSET); zip.closeEntry();
        }
        return bytes.toByteArray();
    }
    static final class OwnedStream extends ByteArrayInputStream {
        boolean closed;
        OwnedStream(byte[] bytes) { super(bytes); }
        @Override public void close() { closed = true; }
    }
    static final class FailingStream extends InputStream {
        final byte[] bytes;
        final int failAt;
        int position;
        boolean closed;
        FailingStream(byte[] bytes, int failAt) { this.bytes = bytes; this.failAt = failAt; }
        @Override public int read() throws IOException {
            if (position >= failAt) throw new IOException("Injected stream read failure");
            return position >= bytes.length ? -1 : bytes[position++] & 255;
        }
        @Override public int read(byte[] target, int offset, int count) throws IOException {
            if (position >= failAt) throw new IOException("Injected stream read failure");
            if (position >= bytes.length) return -1;
            int copied = Math.min(count, Math.min(bytes.length - position, failAt - position));
            System.arraycopy(bytes, position, target, offset, copied); position += copied;
            return copied;
        }
        @Override public void close() { closed = true; }
    }
    static ZipImporter.Result install(InputStream source, FileChannel channel, File root, File cache) throws IOException {
        return GameImporter.install(source, channel, root, cache, ZipImporter.Limits.defaults(),
                new AtomicBoolean(), (f,b,p) -> {});
    }
    static void seed(File root) throws IOException {
        ZipImporter.install(new ByteArrayInputStream(zip(true)), root, ZipImporter.Limits.defaults(),
                new AtomicBoolean(), (f,b,p) -> {});
    }
    static void verifyGame(File root, ZipImporter.Result result) throws Exception {
        require(result.xexPath.equals("default.xex"), "Executable was not normalized");
        require(result.files == 2 && result.bytes == XEX.length + ASSET.length, "Extracted counts changed");
        require(Arrays.equals(Files.readAllBytes(new File(root, "game/default.xex").toPath()), XEX), "XEX content changed");
        require(Arrays.equals(Files.readAllBytes(new File(root, "game/Asset/data.bin").toPath()), ASSET), "Asset content changed");
        StringBuilder expected = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(XEX)) expected.append(String.format(Locale.ROOT, "%02x", b & 255));
        require(result.sha256.equals(expected.toString()), "XEX checksum changed");
        require(ZipImporter.readInstalled(root).sha256.equals(result.sha256), "Installed metadata incompatible");
    }
    static void emptyCache(File cache) {
        if (!cache.exists()) return;
        String[] files = cache.list();
        require(files != null && files.length == 0, "Temporary ISO cache retained");
    }
    static void preserved(File root, File cache, String before) throws Exception {
        require(ZipImporter.readInstalled(root).sha256.equals(before), "Existing metadata changed");
        require(Arrays.equals(Files.readAllBytes(new File(root, "game/previous/keep.bin").toPath()), ASSET),
                "Previous installation was replaced");
        require(!new File(root, "game.staging").exists(), "Failed import staging retained");
        require(!new File(root, "game.backup").exists(), "Failed import backup retained");
        emptyCache(cache);
    }
    static final class PausedImport implements ZipImporter.Progress {
        final CountDownLatch paused = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicBoolean once = new AtomicBoolean(), cancel;
        final boolean cancelAfterRelease;
        PausedImport(AtomicBoolean cancel, boolean cancelAfterRelease) {
            this.cancel = cancel; this.cancelAfterRelease = cancelAfterRelease;
        }
        @Override public void update(int files, long bytes, String path) {
            if (!path.equals("DEFAULT.XEX") || bytes <= 0 || !once.compareAndSet(false, true)) return;
            paused.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Paused import was never released");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); throw new AssertionError("Paused import was interrupted", e);
            }
            if (cancelAfterRelease) cancel.set(true);
        }
    }
    static void await(CountDownLatch latch, String message) throws InterruptedException {
        require(latch.await(5, TimeUnit.SECONDS), message);
    }
    static void requireWaiting(Future<?> operation, String message) throws Exception {
        try { operation.get(250, TimeUnit.MILLISECONDS); throw new AssertionError(message); }
        catch (TimeoutException expected) { }
    }
    static void activeImportUnchanged(File root, File cache) throws Exception {
        require(Arrays.equals(Files.readAllBytes(new File(root, "game/previous/keep.bin").toPath()), ASSET),
                "Active operation changed the previous installation before import finished");
        require(new File(root, "game.staging/DEFAULT.XEX").isFile(), "Active import staging was removed");
        File[] files = cache.listFiles();
        require(files != null && files.length == 1 && files[0].isFile(), "Active ISO cache was removed");
    }
    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("mk-game-router-tests-");
        File root = temporary.resolve("library").toFile();
        try {
            test("ZIP content is detected without a filename and retains stream ownership", () -> {
                OwnedStream input = new OwnedStream(zip(false)); File cache = temporary.resolve("zip-cache").toFile();
                verifyGame(root, install(input, null, root, cache));
                require(!input.closed, "ZIP branch closed the selected stream");
                require(!cache.exists(), "ZIP branch unnecessarily created an ISO cache");
            });
            test("regular FileChannel ISO import preserves positional offsets after sniffing", () -> {
                Path file = temporary.resolve("seekable.iso"); Files.write(file, image(100));
                File cache = temporary.resolve("direct-cache").toFile();
                try (FileInputStream input = new FileInputStream(file.toFile())) {
                    FileChannel channel = input.getChannel();
                    verifyGame(root, install(input, channel, root, cache));
                    require(channel.isOpen(), "Direct branch closed the document channel");
                    require(channel.position() > 0 && channel.position() < 32L * SECTOR,
                            "ISO positional reads changed the sniffed stream position");
                    require(!cache.exists(), "Seekable ISO was copied to the cache");
                }
            });
            test("sequential ISO source is cached and cleaned after installation", () -> {
                OwnedStream input = new OwnedStream(image(100)); File cache = temporary.resolve("sequential-cache").toFile();
                AtomicBoolean sawCache = new AtomicBoolean();
                ZipImporter.Result result = GameImporter.install(input, null, root, cache,
                        ZipImporter.Limits.defaults(), new AtomicBoolean(), (f,b,p) -> {
                            File[] files = cache.listFiles();
                            if (files != null && files.length > 0) sawCache.set(true);
                        });
                verifyGame(root, result); emptyCache(cache);
                require(sawCache.get(), "Sequential image did not use an import cache");
                require(!input.closed, "Cached branch closed the selected stream");
            });
            test("unusable FileChannel probe falls back to sequential ISO caching", () -> {
                Path file = temporary.resolve("closed-probe.bin"); Files.write(file, new byte[]{1});
                FileChannel closed;
                try (FileInputStream channelOwner = new FileInputStream(file.toFile())) { closed = channelOwner.getChannel(); }
                OwnedStream input = new OwnedStream(image(100)); File cache = temporary.resolve("fallback-cache").toFile();
                verifyGame(root, install(input, closed, root, cache));
                require(cache.isDirectory(), "Failed channel probe did not fall back to caching");
                emptyCache(cache); require(!input.closed, "Probe fallback closed selected stream");
            });
            test("ZIP routing ignores an unusable optional ISO channel", () -> {
                Path file = temporary.resolve("closed-zip-probe.bin"); Files.write(file, new byte[]{1});
                FileChannel closed;
                try (FileInputStream owner = new FileInputStream(file.toFile())) { closed = owner.getChannel(); }
                OwnedStream input = new OwnedStream(zip(false)); File cache = temporary.resolve("zip-probe-cache").toFile();
                verifyGame(root, install(input, closed, root, cache));
                require(!cache.exists() && !input.closed, "ZIP router used the ISO route");
            });
            test("cached ISO and ZIP produce equivalent metadata and extracted contents", () -> {
                File zipRoot = temporary.resolve("zip-comparison").toFile(), isoRoot = temporary.resolve("iso-comparison").toFile();
                File cache = temporary.resolve("comparison-cache").toFile();
                ZipImporter.Result zipResult = install(new OwnedStream(zip(false)), null, zipRoot, cache);
                ZipImporter.Result isoResult = install(new OwnedStream(image(100)), null, isoRoot, cache);
                verifyGame(zipRoot, zipResult); verifyGame(isoRoot, isoResult);
                require(zipResult.xexPath.equals(isoResult.xexPath) && zipResult.sha256.equals(isoResult.sha256)
                        && zipResult.files == isoResult.files && zipResult.bytes == isoResult.bytes,
                        "Routing changed the installed metadata");
                emptyCache(cache);
            });
            test("direct sparse FileChannel reads game data beyond 4 GiB", () -> {
                long highSector = 0x00200064L;
                Path file = temporary.resolve("sparse.iso");
                try (RandomAccessFile output = new RandomAccessFile(file.toFile(), "rw")) {
                    output.write(image(highSector)); output.seek(highSector * SECTOR); output.write(XEX);
                }
                File cache = temporary.resolve("sparse-cache").toFile();
                try (FileInputStream input = new FileInputStream(file.toFile())) {
                    verifyGame(root, install(input, input.getChannel(), root, cache));
                    require(input.getChannel().isOpen(), "Sparse document channel was closed");
                }
                require(!cache.exists(), "Sparse ISO was unnecessarily cached");
            });
            test("cancellation during ISO caching preserves the installed ZIP", () -> {
                seed(root); String before = ZipImporter.readInstalled(root).sha256;
                OwnedStream input = new OwnedStream(image(100)); File cache = temporary.resolve("cancel-cache").toFile();
                AtomicBoolean cancellation = new AtomicBoolean();
                try {
                    GameImporter.install(input, null, root, cache, ZipImporter.Limits.defaults(), cancellation,
                            (f,b,p) -> { if (b > 0) cancellation.set(true); });
                    throw new AssertionError("Caching cancellation was ignored");
                } catch (ZipImporter.Cancelled expected) { }
                preserved(root, cache, before); require(!input.closed, "Caching cancellation closed input");
            });
            test("cancellation during cached ISO extraction preserves the installed ZIP", () -> {
                seed(root); String before = ZipImporter.readInstalled(root).sha256;
                OwnedStream input = new OwnedStream(image(100)); File cache = temporary.resolve("cancel-extraction-cache").toFile();
                AtomicBoolean cancellation = new AtomicBoolean();
                try {
                    GameImporter.install(input, null, root, cache, ZipImporter.Limits.defaults(), cancellation,
                            (f,b,p) -> { if (p.equals("DEFAULT.XEX") && b > 0) cancellation.set(true); });
                    throw new AssertionError("Extraction cancellation was ignored");
                } catch (ZipImporter.Cancelled expected) { }
                preserved(root, cache, before); require(!input.closed, "Extraction cancellation closed input");
            });
            test("cancellation during direct ISO extraction retains the channel", () -> {
                seed(root); String before = ZipImporter.readInstalled(root).sha256;
                Path file = temporary.resolve("direct-cancel.iso"); Files.write(file, image(100));
                File cache = temporary.resolve("direct-cancel-cache").toFile(); AtomicBoolean cancellation = new AtomicBoolean();
                try (FileInputStream input = new FileInputStream(file.toFile())) {
                    try {
                        GameImporter.install(input, input.getChannel(), root, cache, ZipImporter.Limits.defaults(), cancellation,
                                (f,b,p) -> { if (b > 0) cancellation.set(true); });
                        throw new AssertionError("Direct extraction cancellation was ignored");
                    } catch (ZipImporter.Cancelled expected) { }
                    require(input.getChannel().isOpen(), "Direct cancellation closed selected document");
                }
                preserved(root, cache, before); require(!cache.exists(), "Direct cancellation created a cache");
            });
            test("pre-cancelled import leaves input and cache untouched", () -> {
                seed(root); String before = ZipImporter.readInstalled(root).sha256;
                OwnedStream input = new OwnedStream(image(100)); File cache = temporary.resolve("pre-cancel-cache").toFile();
                try {
                    GameImporter.install(input, null, root, cache, ZipImporter.Limits.defaults(), new AtomicBoolean(true), (f,b,p) -> {});
                    throw new AssertionError("Pre-cancellation was ignored");
                } catch (ZipImporter.Cancelled expected) { }
                require(input.available() == image(100).length && !input.closed, "Pre-cancelled import consumed or closed input");
                preserved(root, cache, before); require(!cache.exists(), "Pre-cancellation created a cache");
            });
            test("malformed cached ISO is rejected without replacing current game", () -> {
                seed(root); String before = ZipImporter.readInstalled(root).sha256;
                byte[] bad = image(100); bad[32 * SECTOR] ^= 1;
                OwnedStream input = new OwnedStream(bad); File cache = temporary.resolve("bad-cache").toFile();
                try { install(input, null, root, cache); throw new AssertionError("Malformed ISO was accepted"); }
                catch (IOException expected) { }
                preserved(root, cache, before); require(!input.closed, "Failed cached import closed input");
            });
            test("input read failure during caching preserves current game and cleans temporary data", () -> {
                seed(root); String before = ZipImporter.readInstalled(root).sha256;
                FailingStream input = new FailingStream(image(100), 12000); File cache = temporary.resolve("io-error-cache").toFile();
                try { install(input, null, root, cache); throw new AssertionError("Input read failure was ignored"); }
                catch (IOException expected) { require(expected.getMessage().contains("Injected"), "Wrong source failure"); }
                preserved(root, cache, before); require(!input.closed, "Read failure closed selected stream");
            });
            test("short nonarchive input is rejected and its cache removed", () -> {
                seed(root); String before = ZipImporter.readInstalled(root).sha256;
                OwnedStream input = new OwnedStream(new byte[]{1, 2, 3}); File cache = temporary.resolve("short-cache").toFile();
                try { install(input, null, root, cache); throw new AssertionError("Nonarchive input was accepted"); }
                catch (IOException expected) { }
                preserved(root, cache, before); require(!input.closed, "Short input was closed");
            });
            test("malformed ZIP rejection also retains caller stream ownership", () -> {
                seed(root); String before = ZipImporter.readInstalled(root).sha256;
                OwnedStream input = new OwnedStream(new byte[]{'P','K',3,4,0,0});
                File cache = temporary.resolve("bad-zip-cache").toFile();
                try { install(input, null, root, cache); throw new AssertionError("Malformed ZIP was accepted"); }
                catch (IOException expected) { }
                preserved(root, cache, before); require(!input.closed, "Bad ZIP closed caller stream");
                require(!cache.exists(), "Malformed ZIP created an ISO cache");
            });
            test("recovery waits for a paused import and preserves its active cache and staging", () -> {
                seed(root);
                OwnedStream input = new OwnedStream(image(100)); File cache = temporary.resolve("recovery-lock-cache").toFile();
                PausedImport pause = new PausedImport(new AtomicBoolean(), false);
                ExecutorService workers = Executors.newFixedThreadPool(2);
                try {
                    Future<ZipImporter.Result> importing = workers.submit(() -> GameImporter.install(input, null, root, cache,
                            ZipImporter.Limits.defaults(), pause.cancel, pause));
                    await(pause.paused, "Import never reached the extraction pause");
                    activeImportUnchanged(root, cache);
                    CountDownLatch recoveryStarted = new CountDownLatch(1);
                    Future<ZipImporter.Result> recovery = workers.submit(() -> {
                        recoveryStarted.countDown(); return GameImporter.recover(root, cache);
                    });
                    await(recoveryStarted, "Recovery task never started");
                    requireWaiting(recovery, "Recovery finished while import was still paused");
                    activeImportUnchanged(root, cache);
                    pause.release.countDown();
                    ZipImporter.Result installed = importing.get(5, TimeUnit.SECONDS);
                    ZipImporter.Result recovered = recovery.get(5, TimeUnit.SECONDS);
                    verifyGame(root, installed);
                    require(recovered != null && recovered.xexPath.equals(installed.xexPath)
                            && recovered.sha256.equals(installed.sha256), "Recovery returned an obsolete installation");
                    emptyCache(cache); require(!input.closed, "Concurrent recovery closed selected stream");
                } finally {
                    pause.release.countDown(); workers.shutdownNow();
                    require(workers.awaitTermination(5, TimeUnit.SECONDS), "Recovery test workers did not stop");
                }
            });
            test("removal waits for a paused import and applies to the completed installation", () -> {
                seed(root);
                OwnedStream input = new OwnedStream(image(100)); File cache = temporary.resolve("removal-lock-cache").toFile();
                PausedImport pause = new PausedImport(new AtomicBoolean(), false);
                ExecutorService workers = Executors.newFixedThreadPool(2);
                try {
                    Future<ZipImporter.Result> importing = workers.submit(() -> GameImporter.install(input, null, root, cache,
                            ZipImporter.Limits.defaults(), pause.cancel, pause));
                    await(pause.paused, "Import never reached the extraction pause");
                    CountDownLatch removalStarted = new CountDownLatch(1);
                    Future<Void> removing = workers.submit(() -> {
                        removalStarted.countDown(); GameImporter.remove(root); return null;
                    });
                    await(removalStarted, "Removal task never started");
                    requireWaiting(removing, "Removal finished while import was still paused");
                    activeImportUnchanged(root, cache);
                    pause.release.countDown();
                    ZipImporter.Result installed = importing.get(5, TimeUnit.SECONDS);
                    removing.get(5, TimeUnit.SECONDS);
                    require(installed.xexPath.equals("default.xex") && installed.files == 2,
                            "Import failed before the queued removal");
                    require(ZipImporter.readInstalled(root) == null && !new File(root, "game").exists(),
                            "Queued removal left the newly imported game installed");
                    require(!new File(root, "game.staging").exists() && !new File(root, "game.backup").exists(),
                            "Queued removal retained transaction artifacts");
                    emptyCache(cache); require(!input.closed, "Concurrent removal closed selected stream");
                } finally {
                    pause.release.countDown(); workers.shutdownNow();
                    require(workers.awaitTermination(5, TimeUnit.SECONDS), "Removal test workers did not stop");
                }
            });
            test("recovery waits for cancellation to roll back before restoring the previous game", () -> {
                seed(root); String before = ZipImporter.readInstalled(root).sha256;
                OwnedStream input = new OwnedStream(image(100)); File cache = temporary.resolve("cancel-recovery-lock-cache").toFile();
                PausedImport pause = new PausedImport(new AtomicBoolean(), true);
                ExecutorService workers = Executors.newFixedThreadPool(2);
                try {
                    Future<ZipImporter.Result> importing = workers.submit(() -> GameImporter.install(input, null, root, cache,
                            ZipImporter.Limits.defaults(), pause.cancel, pause));
                    await(pause.paused, "Import never reached the extraction pause");
                    CountDownLatch recoveryStarted = new CountDownLatch(1);
                    Future<ZipImporter.Result> recovery = workers.submit(() -> {
                        recoveryStarted.countDown(); return GameImporter.recover(root, cache);
                    });
                    await(recoveryStarted, "Recovery task never started");
                    requireWaiting(recovery, "Recovery finished before import cancellation could roll back");
                    activeImportUnchanged(root, cache);
                    pause.release.countDown();
                    try { importing.get(5, TimeUnit.SECONDS); throw new AssertionError("Import cancellation was ignored"); }
                    catch (ExecutionException expected) {
                        require(expected.getCause() instanceof ZipImporter.Cancelled, "Wrong import cancellation failure");
                    }
                    ZipImporter.Result recovered = recovery.get(5, TimeUnit.SECONDS);
                    require(recovered != null && recovered.xexPath.equals("previous/default.xex"),
                            "Recovery did not retain the previous installation");
                    preserved(root, cache, before); require(!input.closed, "Cancellation recovery closed selected stream");
                } finally {
                    pause.release.countDown(); workers.shutdownNow();
                    require(workers.awaitTermination(5, TimeUnit.SECONDS), "Cancellation recovery workers did not stop");
                }
            });
            System.out.println("RESULT ROUTER: " + passed + " passed, 0 failed");
        } finally { ZipImporter.deleteTree(temporary.toFile()); }
    }
}
