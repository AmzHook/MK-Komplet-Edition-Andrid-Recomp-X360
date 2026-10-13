import org.amzhook.mkimporter.IsoImporter;
import org.amzhook.mkimporter.ZipImporter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;

/** Binary fixtures describe XDVDFS independently of the importer under test. */
public final class IsoImporterTest {
    interface Checked { void run() throws Exception; }
    static int passed;
    static final int SECTOR = 2048;
    static final long ROOT_SECTOR = 40;
    static final byte[] MAGIC = "MICROSOFT*XBOX*MEDIA".getBytes(StandardCharsets.US_ASCII);
    static final byte[] XEX = new byte[128];
    static final byte[] CONFIG = "[Engine.Engine]\nSyntheticFixture=1\n".getBytes(StandardCharsets.US_ASCII);
    static final byte[] ASSET = new byte[]{12, 34, 56, 78};
    static {
        System.arraycopy(new byte[]{'X','E','X','2'}, 0, XEX, 0, 4);
        for (int i = 4; i < XEX.length; i++) XEX[i] = (byte)(i * 7);
    }
    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static void test(String name, Checked action) throws Exception {
        action.run(); passed++; System.out.println("PASS ISO " + name);
    }
    static void u16(byte[] bytes, int offset, long value) {
        bytes[offset] = (byte)value; bytes[offset + 1] = (byte)(value >>> 8);
    }
    static void u32(byte[] bytes, int offset, long value) {
        for (int i = 0; i < 4; i++) bytes[offset + i] = (byte)(value >>> (i * 8));
    }
    static void record(byte[] table, int offset, String name, long sector, long size,
                       int attributes, int leftOffset, int rightOffset) {
        byte[] encoded = name.getBytes(StandardCharsets.ISO_8859_1);
        require(encoded.length <= 255 && offset + 14 + encoded.length <= table.length,
                "Invalid test fixture record");
        u16(table, offset, leftOffset / 4); u16(table, offset + 2, rightOffset / 4);
        u32(table, offset + 4, sector); u32(table, offset + 8, size);
        table[offset + 12] = (byte)attributes; table[offset + 13] = (byte)encoded.length;
        System.arraycopy(encoded, 0, table, offset + 14, encoded.length);
    }

    /** Missing ranges read as zero, so a multi-gigabyte image costs only fixture bytes. */
    static final class SparseSource implements IsoImporter.SeekableSource, AutoCloseable {
        final TreeMap<Long, byte[]> ranges = new TreeMap<>();
        long imageLength, bytesRead, failAt = -1;
        boolean closed;
        SparseSource(long length) { imageLength = length; }
        void put(long offset, byte[] value) { ranges.put(offset, value); }
        @Override public long length() throws IOException {
            if (closed) throw new IOException("Closed fixture");
            return imageLength;
        }
        @Override public void readFully(long offset, byte[] data, int start, int count) throws IOException {
            if (closed) throw new IOException("Closed fixture");
            if (offset < 0 || count < 0 || start < 0 || start > data.length - count
                    || offset > imageLength || count > imageLength - offset)
                throw new EOFException("Synthetic image is truncated");
            if (failAt >= 0 && offset <= failAt && offset + count > failAt)
                throw new IOException("Injected source read failure");
            Arrays.fill(data, start, start + count, (byte)0);
            Map.Entry<Long, byte[]> range = ranges.floorEntry(offset);
            if (range == null || range.getKey() + range.getValue().length <= offset)
                range = ranges.ceilingEntry(offset);
            while (range != null && range.getKey() < offset + count) {
                long begin = Math.max(offset, range.getKey());
                long end = Math.min(offset + count, range.getKey() + range.getValue().length);
                if (end > begin) System.arraycopy(range.getValue(), (int)(begin - range.getKey()),
                        data, start + (int)(begin - offset), (int)(end - begin));
                range = ranges.higherEntry(range.getKey());
            }
            bytesRead += count;
        }
        @Override public void close() { closed = true; }
    }
    static final class Image {
        final long base;
        final SparseSource source;
        final byte[] descriptor = new byte[SECTOR];
        byte[] root;
        Image(long base, byte[] root) {
            this.base = base; this.root = root;
            source = new SparseSource(base + 200L * SECTOR);
            System.arraycopy(MAGIC, 0, descriptor, 0, MAGIC.length);
            System.arraycopy(MAGIC, 0, descriptor, SECTOR - MAGIC.length, MAGIC.length);
            u32(descriptor, 20, ROOT_SECTOR); u32(descriptor, 24, root.length);
            source.put(base + 0x10000L, descriptor);
            source.put(address(ROOT_SECTOR), root);
        }
        long address(long sector) { return base + sector * SECTOR; }
        void payload(long sector, byte[] bytes) { source.put(address(sector), bytes); }
    }
    static Image simple(long base) {
        byte[] root = new byte[64];
        record(root, 0, "default.xex", 100, XEX.length, 0, 0, 0);
        Image image = new Image(base, root); image.payload(100, XEX);
        return image;
    }
    static Image nested(long base) {
        byte[] root = new byte[160];
        record(root, 0, "Config", 42, 64, 0x10, 64, 128);
        record(root, 64, "Asset", 44, 64, 0x10, 0, 0);
        record(root, 128, "DEFAULT.XEX", 100, XEX.length, 0, 0, 0);
        Image image = new Image(base, root);
        byte[] config = new byte[64], asset = new byte[64];
        record(config, 0, "Xe-MK9Engine.ini", 102, CONFIG.length, 0, 0, 0);
        record(asset, 0, "data.bin", 104, ASSET.length, 0, 0, 0);
        image.payload(42, config); image.payload(44, asset);
        image.payload(100, XEX); image.payload(102, CONFIG); image.payload(104, ASSET);
        return image;
    }
    static ZipImporter.Result install(Image image, File root) throws IOException {
        return IsoImporter.install(image.source, root, ZipImporter.Limits.defaults(),
                new AtomicBoolean(), (files, bytes, path) -> {});
    }
    static String hash(byte[] bytes) throws Exception {
        StringBuilder text = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
            text.append(String.format(Locale.ROOT, "%02x", b & 255));
        return text.toString();
    }
    static void seedZip(File root) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("previous/default.xex")); zip.write(XEX); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("previous/keep.bin")); zip.write(ASSET); zip.closeEntry();
        }
        ZipImporter.install(new ByteArrayInputStream(bytes.toByteArray()), root,
                ZipImporter.Limits.defaults(), new AtomicBoolean(), (f,b,p) -> {});
    }
    static void preserved(File root, String before) throws Exception {
        require(ZipImporter.readInstalled(root).sha256.equals(before), "Previous metadata changed");
        require(Arrays.equals(Files.readAllBytes(new File(root, "game/previous/keep.bin").toPath()), ASSET),
                "Previous game content changed");
        require(!new File(root, "game.staging").exists(), "Failed ISO staging retained");
        require(!new File(root, "game.backup").exists(), "Unexpected backup after failed import");
    }
    static void rejected(Image image, File root) throws Exception {
        seedZip(root);
        String before = ZipImporter.readInstalled(root).sha256;
        try { install(image, root); throw new AssertionError("Malformed image accepted"); }
        catch (IOException expected) { require(expected.getMessage() != null, "Missing import error"); }
        require(!image.source.closed, "Importer closed caller-owned image source");
        preserved(root, before);
    }
    static void rejectedWithLimits(Image image, File root, ZipImporter.Limits limits) throws Exception {
        seedZip(root);
        String before = ZipImporter.readInstalled(root).sha256;
        try {
            IsoImporter.install(image.source, root, limits, new AtomicBoolean(), (f,b,p) -> {});
            throw new AssertionError("ISO import limit bypassed");
        } catch (IOException expected) { }
        preserved(root, before);
    }
    static Image named(String name) {
        byte[] root = new byte[Math.max(64, 14 + name.length())];
        record(root, 0, name, 100, XEX.length, 0, 0, 0);
        Image image = new Image(0, root); image.payload(100, XEX);
        return image;
    }
    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("mk-iso-tests-");
        File root = temporary.resolve("library").toFile();
        try {
            for (long base : new long[]{0, 0x0FD90000L, 0x02080000L}) {
                final long partitionBase = base;
                test("nested game import at partition base " + Long.toHexString(base), () -> {
                    Image image = nested(partitionBase);
                    ZipImporter.Result result = install(image, root);
                    require(result.files == 3 && result.bytes == XEX.length + CONFIG.length + ASSET.length,
                            "Incorrect extracted file or byte count");
                    require(result.xexPath.equals("default.xex"), "XEX name was not normalized");
                    require(result.sha256.equals(hash(XEX)), "XEX checksum mismatch");
                    require(Arrays.equals(Files.readAllBytes(new File(root, "game/default.xex").toPath()), XEX),
                            "XEX payload changed");
                    require(Arrays.equals(Files.readAllBytes(new File(root, "game/Config/Xe-MK9Engine.ini").toPath()), CONFIG),
                            "Configuration payload changed");
                    require(Arrays.equals(Files.readAllBytes(new File(root, "game/Asset/data.bin").toPath()), ASSET),
                            "Nested asset payload changed");
                    require(ZipImporter.readInstalled(root).sha256.equals(result.sha256), "Metadata incompatible with ZIP");
                    require(!image.source.closed, "Caller-owned image source was closed");
                });
            }
            test("file offsets beyond 4 GiB without image allocation", () -> {
                Image image = simple(0);
                long highSector = 0x00200064L;
                u32(image.root, 4, highSector);
                image.payload(highSector, XEX);
                image.source.imageLength = image.address(highSector) + XEX.length;
                ZipImporter.Result result = install(image, root);
                require(result.sha256.equals(hash(XEX)), "High-address payload changed");
                require(image.source.bytesRead < (1L << 20), "Importer scanned the complete sparse image");
            });
            test("sector number with its high bit set is unsigned", () -> {
                Image image = simple(0);
                long highSector = 0x80000064L;
                u32(image.root, 4, highSector); image.payload(highSector, XEX);
                image.source.imageLength = image.address(highSector) + XEX.length;
                require(install(image, root).sha256.equals(hash(XEX)), "Unsigned sector was sign-extended");
                require(image.source.bytesRead < (1L << 20), "Sparse fixture was scanned instead of seeking");
            });
            test("BST child pointers skip unreachable directory bytes", () -> {
                byte[] table = new byte[320];
                record(table, 0, "default.xex", 100, XEX.length, 0, 0, 256);
                Arrays.fill(table, 64, 160, (byte)0xAB);
                record(table, 256, "z.bin", 102, ASSET.length, 0, 0, 0);
                Image image = new Image(0, table); image.payload(100, XEX); image.payload(102, ASSET);
                require(install(image, root).files == 2, "BST gap was scanned as a record");
                require(new File(root, "game/z.bin").isFile(), "Indexed right child missing");
            });
            test("0xFFFF null children retain a valid root record", () -> {
                Image image = simple(0); u16(image.root, 0, 0xFFFF); u16(image.root, 2, 0xFFFF);
                require(install(image, root).sha256.equals(hash(XEX)), "FFFF null children were misread as empty");
            });
            test("directory record may end without alignment padding", () -> {
                byte[] table = new byte[25];
                record(table, 0, "default.xex", 100, XEX.length, 0, 0, 0);
                Image image = new Image(0, table); image.payload(100, XEX);
                require(install(image, root).files == 1, "Unrounded directory size rejected");
            });
            test("all-FF sector padding reached by child pointer is skipped", () -> {
                byte[] table = new byte[SECTOR + 64];
                record(table, 0, "default.xex", 100, XEX.length, 0, 0, 64);
                Arrays.fill(table, 64, SECTOR, (byte)0xFF);
                record(table, SECTOR, "z.bin", 102, ASSET.length, 0, 0, 0);
                Image image = new Image(0, table); image.payload(100, XEX); image.payload(102, ASSET);
                require(install(image, root).files == 2, "Sector padding hid the indexed child");
            });
            test("bad leading descriptor magic preserves previous ZIP", () -> {
                Image image = simple(0); image.descriptor[0] ^= 1; rejected(image, root);
            });
            test("bad trailing descriptor magic preserves previous ZIP", () -> {
                Image image = simple(0); image.descriptor[2028] ^= 1; rejected(image, root);
            });
            test("truncated descriptor rejected", () -> {
                Image image = simple(0); image.source.imageLength = 0x10000L + 1000; rejected(image, root);
            });
            test("unrecognized partition offset rejected", () -> {
                rejected(simple(0x00100000L), root);
            });
            test("root directory outside image rejected", () -> {
                Image image = simple(0); u32(image.descriptor, 20, 0xFFFFFFFFL); rejected(image, root);
            });
            test("oversized directory metadata rejected before allocation", () -> {
                Image image = simple(0); u32(image.descriptor, 24, 0xFFFFFFFFL);
                image.source.imageLength = 8L << 30; rejected(image, root);
            });
            test("file extent beyond image rejected", () -> {
                Image image = simple(0); u32(image.root, 4, 201); rejected(image, root);
            });
            test("truncated payload rejected", () -> {
                Image image = simple(0); image.source.imageLength = image.address(100) + XEX.length - 1;
                rejected(image, root);
            });
            test("child offset outside directory rejected", () -> {
                Image image = simple(0); u16(image.root, 2, 100); rejected(image, root);
            });
            test("record name exceeding declared directory rejected", () -> {
                Image image = simple(0); image.root[13] = (byte)255; rejected(image, root);
            });
            test("record cannot straddle directory sector boundary", () -> {
                byte[] table = new byte[SECTOR + 64];
                record(table, 0, "default.xex", 100, XEX.length, 0, 0, SECTOR - 8);
                record(table, SECTOR - 8, "z.bin", 102, ASSET.length, 0, 0, 0);
                Image image = new Image(0, table); image.payload(100, XEX); image.payload(102, ASSET);
                rejected(image, root);
            });
            test("self-referential BST child rejected", () -> {
                byte[] table = new byte[128];
                record(table, 0, "default.xex", 100, XEX.length, 0, 0, 64);
                record(table, 64, "z.bin", 102, ASSET.length, 0, 0, 64);
                Image image = new Image(0, table); image.payload(100, XEX); image.payload(102, ASSET);
                rejected(image, root);
            });
            test("same BST record reached twice rejected", () -> {
                byte[] table = new byte[128];
                record(table, 0, "default.xex", 100, XEX.length, 0, 64, 64);
                record(table, 64, "z.bin", 102, ASSET.length, 0, 0, 0);
                Image image = new Image(0, table); image.payload(100, XEX); image.payload(102, ASSET);
                rejected(image, root);
            });
            test("overlapping directory record bodies rejected", () -> {
                byte[] table = new byte[128];
                record(table, 0, "abcdefghijklmnopqrstuvwx", 100, ASSET.length, 0, 0, 32);
                record(table, 32, "default.xex", 102, XEX.length, 0, 0, 0);
                Image image = new Image(0, table); image.payload(100, ASSET); image.payload(102, XEX);
                rejected(image, root);
            });
            test("recursive directory extent rejected", () -> {
                byte[] table = new byte[128];
                record(table, 0, "Loop", ROOT_SECTOR, table.length, 0x10, 0, 64);
                record(table, 64, "default.xex", 100, XEX.length, 0, 0, 0);
                Image image = new Image(0, table); image.payload(100, XEX); rejected(image, root);
            });
            test("deduplicated file extents may share the same image payload", () -> {
                byte[] table = new byte[128];
                record(table, 0, "default.xex", 100, XEX.length, 0, 0, 64);
                record(table, 64, "z.bin", 100, ASSET.length, 0, 0, 0);
                Image image = new Image(0, table); image.payload(100, XEX);
                require(install(image, root).files == 2, "Shared file extent was rejected");
                require(Arrays.equals(Files.readAllBytes(new File(root, "game/z.bin").toPath()),
                        Arrays.copyOf(XEX, ASSET.length)), "Shared file payload changed");
            });
            test("directory table cannot overlap volume descriptor", () -> {
                Image image = simple(0); u32(image.descriptor, 20, 32); rejected(image, root);
            });
            test("sibling directory aliases expand to independent output paths", () -> {
                Image image = nested(0); u32(image.root, 64 + 4, 42);
                ZipImporter.Result result = install(image, root);
                require(result.files == 3 && result.bytes == XEX.length + 2 * CONFIG.length,
                        "Directory alias file or byte count changed");
                require(Arrays.equals(Files.readAllBytes(new File(root, "game/Config/Xe-MK9Engine.ini").toPath()), CONFIG),
                        "Original directory alias payload changed");
                require(Arrays.equals(Files.readAllBytes(new File(root, "game/Asset/Xe-MK9Engine.ini").toPath()), CONFIG),
                        "Second directory alias was not expanded independently");
                require(result.sha256.equals(hash(XEX)), "Directory alias changed the executable");
            });
            test("directory ancestry cycle is rejected even with changed declared length", () -> {
                byte[] table = new byte[128];
                record(table, 0, "A", 42, 64, 0x10, 0, 64);
                record(table, 64, "default.xex", 100, XEX.length, 0, 0, 0);
                Image image = new Image(0, table);
                byte[] a = new byte[64], b = new byte[96];
                record(a, 0, "B", 44, b.length, 0x10, 0, 0);
                record(b, 0, "AgainA", 42, 32, 0x10, 0, 0);
                image.payload(42, a); image.payload(44, b); image.payload(100, XEX);
                rejected(image, root);
            });
            test("all-zero root is an empty filesystem", () -> rejected(new Image(0, new byte[64]), root));
            test("all-FF root is an empty filesystem", () -> {
                byte[] table = new byte[64]; Arrays.fill(table, (byte)0xFF);
                rejected(new Image(0, table), root);
            });
            for (String name : new String[]{"..", ".", "../escaped.bin", "dir/file", "dir\\file",
                    "C:escaped.bin", "bad\u0000name", "bad\u0001name", ".mk-import.properties", ".MK-IMPORT.PROPERTIES"}) {
                final String invalidName = name;
                test("unsafe or reserved filename " + name.replace('\0', '?').replace('\1', '?'),
                        () -> rejected(named(invalidName), root));
            }
            test("undefined CP1252 filename byte rejected", () -> rejected(named("bad\u0081name"), root));
            test("CP1252 accented filename preserves its contents", () -> {
                byte[] table = new byte[128];
                record(table, 0, "default.xex", 100, XEX.length, 0, 0, 64);
                record(table, 64, "caf\u00e9.bin", 102, ASSET.length, 0, 0, 0);
                Image image = new Image(0, table); image.payload(100, XEX); image.payload(102, ASSET);
                require(install(image, root).files == 2, "Valid CP1252 filename rejected");
                require(Arrays.equals(Files.readAllBytes(new File(root, "game/caf\u00e9.bin").toPath()), ASSET),
                        "Accented filename or payload changed");
            });
            test("case-insensitive duplicate file names rejected", () -> {
                byte[] table = new byte[128];
                record(table, 0, "default.xex", 100, XEX.length, 0, 0, 64);
                record(table, 64, "DEFAULT.XEX", 102, XEX.length, 0, 0, 0);
                Image image = new Image(0, table); image.payload(100, XEX); image.payload(102, XEX);
                rejected(image, root);
            });
            test("missing executable rejected", () -> rejected(named("data.bin"), root));
            test("two executables in separate directories rejected", () -> {
                Image image = nested(0);
                byte[] table = new byte[64];
                record(table, 0, "default.xex", 104, XEX.length, 0, 0, 0);
                image.payload(44, table); image.payload(104, XEX);
                rejected(image, root);
            });
            test("bad XEX header rejected before commit", () -> {
                Image image = simple(0); byte[] bad = XEX.clone(); bad[0] = 'P'; image.payload(100, bad);
                rejected(image, root);
            });
            test("incomplete XEX rejected before commit", () -> {
                Image image = simple(0); u32(image.root, 8, 4); image.payload(100, new byte[]{'X','E','X','2'});
                rejected(image, root);
            });
            test("per-file byte limit enforced", () -> rejectedWithLimits(simple(0), root,
                    new ZipImporter.Limits(1000, 64, 10)));
            test("total extracted byte limit enforced", () -> rejectedWithLimits(nested(0), root,
                    new ZipImporter.Limits(XEX.length + CONFIG.length + ASSET.length - 1, 1000, 10)));
            test("directory entries included in entry limit", () -> rejectedWithLimits(nested(0), root,
                    new ZipImporter.Limits(1000, 1000, 3)));
            test("directory nesting limit rejects hostile hierarchy", () -> {
                Image image = new Image(0, new byte[64]);
                for (int i = 0; i < 132; i++) {
                    long sector = ROOT_SECTOR + i;
                    byte[] directory = i == 0 ? image.root : new byte[64];
                    if (i == 131) record(directory, 0, "default.xex", 1000, XEX.length, 0, 0, 0);
                    else record(directory, 0, "d", sector + 1, 64, 0x10, 0, 0);
                    image.payload(sector, directory);
                }
                image.payload(1000, XEX); image.source.imageLength = 1001L * SECTOR;
                rejected(image, root);
            });
            test("cancellation before extraction preserves current ZIP", () -> {
                seedZip(root); String before = ZipImporter.readInstalled(root).sha256;
                Image image = nested(0);
                try {
                    IsoImporter.install(image.source, root, ZipImporter.Limits.defaults(),
                            new AtomicBoolean(true), (f,b,p) -> {});
                    throw new AssertionError("Pre-cancelled import accepted");
                } catch (ZipImporter.Cancelled expected) { }
                preserved(root, before); require(!image.source.closed, "Cancellation closed image source");
            });
            test("cancellation during extraction preserves current ZIP", () -> {
                seedZip(root); String before = ZipImporter.readInstalled(root).sha256;
                Image image = nested(0); AtomicBoolean cancellation = new AtomicBoolean();
                try {
                    IsoImporter.install(image.source, root, ZipImporter.Limits.defaults(), cancellation,
                            (f,b,p) -> { if (b > 0) cancellation.set(true); });
                    throw new AssertionError("Cancellation during extraction ignored");
                } catch (ZipImporter.Cancelled expected) { }
                preserved(root, before); require(!image.source.closed, "Cancellation closed image source");
            });
            test("source I/O failure after XEX extraction preserves current ZIP", () -> {
                Image image = nested(0); image.source.failAt = image.address(102) + 17;
                rejected(image, root);
            });
            test("interrupted ISO staging uses shared ZIP recovery", () -> {
                seedZip(root);
                Files.move(new File(root, "game").toPath(), new File(root, "game.backup").toPath());
                File staging = new File(root, "game.staging"); require(staging.mkdirs(), "Could not create interrupted staging");
                Files.write(new File(staging, "partial.bin").toPath(), new byte[]{9});
                ZipImporter.recover(root);
                require(ZipImporter.readInstalled(root).xexPath.equals("previous/default.xex"), "Previous ZIP was not recovered");
                require(!staging.exists() && !new File(root, "game.backup").exists(), "Recovery retained interrupted state");
                require(install(simple(0), root).xexPath.equals("default.xex"), "Recovered ZIP could not be replaced by ISO");
            });
            test("no malformed ISO escapes the application root", () -> {
                require(!temporary.resolve("escaped.bin").toFile().exists(), "ISO escaped the application root");
                require(!new File(root, "escaped.bin").exists(), "ISO escaped game extraction directory");
            });
            System.out.println("RESULT ISO: " + passed + " passed, 0 failed");
        } finally { ZipImporter.deleteTree(temporary.toFile()); }
    }
}
