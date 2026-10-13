import org.amzhook.mkimporter.ZipImporter;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;

public final class ZipImporterTest {
    interface Checked { void run() throws Exception; }
    static int passed;
    static final byte[] XEX = new byte[128];
    static {
        System.arraycopy(new byte[]{'X','E','X','2'}, 0, XEX, 0, 4);
        for (int i = 4; i < XEX.length; i++) XEX[i] = (byte)i;
    }
    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static void test(String name, Checked action) throws Exception {
        action.run(); passed++; System.out.println("PASS " + name);
    }
    static byte[] zip(String... names) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(data)) {
            for (String name : names) {
                zip.putNextEntry(new ZipEntry(name));
                if (!name.endsWith("/")) zip.write(name.toLowerCase(Locale.ROOT).endsWith("default.xex") ? XEX : new byte[]{4,5,6});
                zip.closeEntry();
            }
        }
        return data.toByteArray();
    }
    static ZipImporter.Result install(byte[] data, File root) throws IOException {
        return ZipImporter.install(new ByteArrayInputStream(data), root, ZipImporter.Limits.defaults(),
                new AtomicBoolean(), (f,b,p) -> {});
    }
    static void failsPreserving(File root, byte[] data, String expected) throws Exception {
        String before = ZipImporter.readInstalled(root).sha256;
        try { install(data, root); throw new AssertionError("Accepted invalid archive: " + expected); }
        catch (IOException e) {
            require(e.getMessage().contains(expected), "Unexpected error: " + e);
        }
        require(ZipImporter.readInstalled(root).sha256.equals(before), "Previous copy changed");
        require(!new File(root, "game.staging").exists(), "Staging was not cleaned");
    }
    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("mk-import-tests-");
        File root = temporary.resolve("library").toFile();
        try {
            test("nested ZIP and installed metadata", () -> {
                ZipImporter.Result result = install(zip("MK/", "MK/default.xex", "MK/Content/data.bin"), root);
                require(result.files == 2 && result.bytes == XEX.length + 3, "Incorrect file/byte count");
                require(result.xexPath.equals("MK/default.xex"), "Nested root not preserved");
                require(Arrays.equals(Files.readAllBytes(new File(root,"game/MK/default.xex").toPath()), XEX), "XEX bytes changed");
                require(Arrays.equals(Files.readAllBytes(new File(root,"game/MK/Content/data.bin").toPath()), new byte[]{4,5,6}), "Asset bytes changed");
                StringBuilder expected = new StringBuilder();
                for(byte b: MessageDigest.getInstance("SHA-256").digest(XEX)) expected.append(String.format("%02x", b & 255));
                require(result.sha256.equals(expected.toString()), "Incorrect checksum");
                require(ZipImporter.readInstalled(root).sha256.equals(result.sha256), "Metadata did not persist");
            });
            test("replacement commits new copy", () -> {
                install(zip("default.xex", "new.bin"), root);
                require(new File(root,"game/new.bin").isFile(), "New copy not installed");
                require(!new File(root,"game/MK").exists(), "Old copy still current");
                require(!new File(root,"game.backup").exists(), "Old backup retained");
            });
            test("case-insensitive XEX name normalized for native launch", () -> {
                ZipImporter.Result result = install(zip("MK/DEFAULT.XEX", "MK/data.bin"), root);
                require(result.xexPath.equals("MK/default.xex"), "XEX name was not normalized");
                require(new File(root,"game/MK/default.xex").isFile(), "Native launch XEX is missing");
                require(Arrays.equals(Files.readAllBytes(new File(root,"game/MK/default.xex").toPath()), XEX), "Normalization changed XEX bytes");
            });
            test("relative path traversal rejected", () -> failsPreserving(root, zip("default.xex", "../escaped.bin"), "sai da pasta"));
            test("nested path traversal rejected", () -> failsPreserving(root, zip("default.xex", "dir/../../escaped.bin"), "sai da pasta"));
            test("absolute path rejected", () -> failsPreserving(root, zip("default.xex", "/escaped.bin"), "Caminho inválido"));
            test("Windows path rejected", () -> failsPreserving(root, zip("default.xex", "C:\\escaped.bin"), "Caminho inválido"));
            test("dot segments rejected", () -> failsPreserving(root, zip("default.xex", "dir/./file.bin"), "sai da pasta"));
            test("case-collision rejected", () -> failsPreserving(root, zip("default.xex", "DATA.bin", "data.bin"), "duplicados"));
            test("missing executable rejected", () -> failsPreserving(root, zip("data.bin"), "exatamente um default.xex"));
            test("multiple executables rejected", () -> failsPreserving(root, zip("default.xex", "update/default.xex"), "exatamente um default.xex"));
            test("empty archive rejected", () -> failsPreserving(root, zip(), "vazio"));
            test("non-ZIP rejected", () -> failsPreserving(root, new byte[]{1,2,3,4}, "vazio"));
            test("reserved metadata rejected", () -> failsPreserving(root, zip("default.xex", ".mk-import.properties"), "reservado"));
            test("directory payload rejected without unlimited decompression", () -> {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try(ZipOutputStream z = new ZipOutputStream(bytes)) {
                    z.putNextEntry(new ZipEntry("payload/")); z.write(new byte[1024 * 1024]); z.closeEntry();
                }
                failsPreserving(root, bytes.toByteArray(), "entrada de pasta");
            });
            test("wrong XEX header rejected", () -> {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try(ZipOutputStream z = new ZipOutputStream(bytes)) { z.putNextEntry(new ZipEntry("default.xex")); z.write(new byte[128]); z.closeEntry(); }
                failsPreserving(root, bytes.toByteArray(), "cabeçalho XEX2");
            });
            test("incomplete XEX rejected", () -> {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try(ZipOutputStream z = new ZipOutputStream(bytes)) { z.putNextEntry(new ZipEntry("default.xex")); z.write(new byte[]{'X','E','X','2'}); z.closeEntry(); }
                failsPreserving(root, bytes.toByteArray(), "incompleto");
            });
            test("expanded size limit enforced", () -> {
                try { ZipImporter.install(new ByteArrayInputStream(zip("default.xex")), root,
                        new ZipImporter.Limits(64, 64, 10), new AtomicBoolean(), (f,b,p)->{}); throw new AssertionError("Size limit bypassed"); }
                catch(IOException expected) { require(expected.getMessage().contains("limite"), "Wrong size-limit error"); }
                require(ZipImporter.readInstalled(root) != null, "Previous copy lost");
            });
            test("entry limit enforced", () -> {
                try { ZipImporter.install(new ByteArrayInputStream(zip("default.xex","a","b")), root,
                        new ZipImporter.Limits(1000, 1000, 1), new AtomicBoolean(), (f,b,p)->{}); throw new AssertionError("Entry limit bypassed"); }
                catch(IOException expected) { require(expected.getMessage().contains("demais"), "Wrong entry-limit error"); }
                require(ZipImporter.readInstalled(root) != null, "Previous copy lost");
            });
            test("cancellation preserves installed copy", () -> {
                AtomicBoolean cancellation = new AtomicBoolean();
                try { ZipImporter.install(new ByteArrayInputStream(zip("default.xex","data.bin")), root,
                        ZipImporter.Limits.defaults(), cancellation, (f,b,p)->cancellation.set(true)); throw new AssertionError("Cancellation ignored"); }
                catch(ZipImporter.Cancelled expected) { }
                require(ZipImporter.readInstalled(root) != null, "Previous copy lost");
                require(!new File(root,"game.staging").exists(), "Cancelled staging retained");
            });
            test("ZIP CRC corruption rejected", () -> {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try(ZipOutputStream z = new ZipOutputStream(bytes)) {
                    ZipEntry e = new ZipEntry("default.xex"); e.setMethod(ZipEntry.STORED); e.setSize(XEX.length);
                    CRC32 crc = new CRC32(); crc.update(XEX); e.setCrc(crc.getValue()); z.putNextEntry(e); z.write(XEX); z.closeEntry();
                }
                byte[] broken = bytes.toByteArray(); broken[30 + "default.xex".length() + 7] ^= 1;
                failsPreserving(root, broken, "CRC");
            });
            test("interrupted commit recovery restores previous copy", () -> {
                File current = new File(root,"game"), backup = new File(root,"game.backup");
                Files.move(current.toPath(), backup.toPath());
                File staging = new File(root,"game.staging"); staging.mkdirs(); Files.write(new File(staging,"partial").toPath(),new byte[]{1});
                ZipImporter.recover(root);
                require(ZipImporter.readInstalled(root) != null, "Backup not restored");
                require(!staging.exists() && !backup.exists(), "Interrupted state not cleaned");
            });
            test("no archive escaped extraction root", () -> require(!temporary.resolve("escaped.bin").toFile().exists(), "ZIP escaped extraction root"));
            System.out.println("RESULT: " + passed + " passed, 0 failed");
        } finally { ZipImporter.deleteTree(temporary.toFile()); }
    }
}
