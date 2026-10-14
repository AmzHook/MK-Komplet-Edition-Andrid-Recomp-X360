package org.amzhook.mkimporter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;

/** Host tests use synthetic libraries and never execute imported code. */
public final class DriverManagerTest {
    private interface Checked { void run() throws Exception; }
    private static int passed;
    private static File root;
    private static byte[] baseline;
    private static String baselineId;

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void test(String name, Checked action) throws Exception {
        action.run(); passed++; System.out.println("PASS " + name);
    }
    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static byte[] elf() {
        byte[] header = new byte[256];
        header[0] = 0x7f; header[1] = 'E'; header[2] = 'L'; header[3] = 'F';
        header[4] = 2; header[5] = 1; header[6] = 1;
        header[16] = 3; header[18] = (byte)183; header[20] = 1; header[52] = 64;
        return header;
    }
    private static byte[] zip(Object... entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            for (int i = 0; i < entries.length; i += 2) {
                String name = (String)entries[i]; byte[] content = (byte[])entries[i + 1];
                ZipEntry entry = new ZipEntry(name); entry.setTime(0); output.putNextEntry(entry);
                if (content != null) output.write(content);
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
    private static byte[] driver(String metadata) throws IOException {
        return zip("meta.json", utf8(metadata), "vulkan.adreno.so", elf());
    }
    private static DriverManager.Driver install(byte[] archive) throws IOException {
        return DriverManager.install(new ByteArrayInputStream(archive), root, DriverManager.Limits.defaults(),
                new AtomicBoolean(), (f, b, p) -> { });
    }
    private static void fails(byte[] archive, String expected) throws Exception {
        Set<String> before = ids();
        try { install(archive); throw new AssertionError("Accepted invalid driver: " + expected); }
        catch (IOException error) {
            require(error.getMessage() != null && error.getMessage().contains(expected), "Unexpected error: " + error);
        }
        require(before.equals(ids()), "Import changed an existing driver catalogue");
        require(DriverManager.findInstalled(root, baselineId) != null, "Previous driver lost");
        require(noStaging(), "Staging was not cleaned");
    }
    private static Set<String> ids() throws IOException {
        Set<String> result = new TreeSet<>();
        for (DriverManager.Driver driver : DriverManager.listInstalled(root)) result.add(driver.id);
        return result;
    }
    private static boolean noStaging() {
        File[] children = root.listFiles();
        if (children == null) return false;
        for (File child : children) if (child.getName().startsWith(".import-")) return false;
        return true;
    }
    private static int locate(byte[] bytes, long signature, int start) {
        for (int at = start; at <= bytes.length - 4; at++) {
            long found = (bytes[at] & 255L) | ((bytes[at + 1] & 255L) << 8)
                    | ((bytes[at + 2] & 255L) << 16) | ((bytes[at + 3] & 255L) << 24);
            if (found == signature) return at;
        }
        throw new AssertionError("Signature not found");
    }
    private static void removeTree(Path directory) throws IOException {
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            for (Path path : (Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
        }
    }

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("mk-driver-tests-"); root = temporary.resolve("drivers").toFile();
        baseline = driver("{\"schemaVersion\":1,\"name\":\"Mesa Turnip\",\"libName\":\"vulkan.adreno.so\",\"minAPI\":27,\"author\":\"Example\",\"driverVersion\":\"26.0\"}");
        try {
            test("standard metadata and ARM64 library installed without execution", () -> {
                DriverManager.Driver result = install(baseline); baselineId = result.id;
                require(result.name.equals("Mesa Turnip") && result.minApi == 27, "Metadata mismatch");
                require(result.id.matches("[0-9a-f]{64}"), "Package ID malformed");
                require(result.libraryName.equals("vulkan.adreno.so"), "Library basename changed");
                require(result.directory.equals(result.installationDirectory), "Library directory mismatch");
                require(Arrays.equals(Files.readAllBytes(result.libraryFile.toPath()), elf()), "Driver bytes changed");
                require(result.author.equals("Example") && result.driverVersion.equals("26.0"), "Display metadata lost");
                require(DriverManager.findInstalled(root, result.id) != null, "Driver record did not persist");
            });
            test("duplicate package is idempotent", () -> {
                Set<String> before = ids(); require(install(baseline).id.equals(baselineId), "Package ID changed");
                require(ids().equals(before), "Duplicate added an extra catalogue entry");
            });
            test("nested metadata retains library companion directory", () -> {
                DriverManager.Driver result = install(zip("Turnip/", null, "Turnip/meta.json", utf8("{\"name\":\"Nested\",\"libraryName\":\"vulkan.adreno.so\",\"minApi\":28,\"version\":\"R2\"}"),
                        "Turnip/vulkan.adreno.so", elf(), "Turnip/libcompanion.so", elf(), "Turnip/license.txt", utf8("Notice")));
                require(result.directory.getName().equals("Turnip"), "Native companion search directory changed");
                require(new File(result.directory, "libcompanion.so").isFile(), "Companion was not retained");
                require(result.minApi == 28 && result.packageVersion.equals("R2"), "Metadata alias lost");
            });
            test("library declared relative to package root", () -> {
                DriverManager.Driver result = install(zip("meta.json", utf8("{\"name\":\"Relative\",\"libraryName\":\"lib/vulkan.adreno.so\"}"), "lib/vulkan.adreno.so", elf()));
                require(result.directory.getName().equals("lib") && result.libraryName.equals("vulkan.adreno.so"), "Relative path not resolved");
            });
            test("unambiguous basename and Unicode name", () -> {
                DriverManager.Driver result = install(zip("info/meta.json", utf8("{\"name\":\"Vulkan \uD83D\uDC09\",\"libName\":\"vulkan.adreno.so\",\"unknown\":{\"array\":[true,false,null,-1.5e2]}}"), "lib/vulkan.adreno.so", elf()));
                require(result.name.equals("Vulkan \uD83D\uDC09") && result.minApi == 0, "Unicode or optional fields broken");
            });
            test("missing display name falls back to basename", () -> require(install(driver("{\"libName\":\"vulkan.adreno.so\"}")).name.equals("vulkan.adreno.so"), "Missing name fallback failed"));
            test("equal metadata aliases accepted", () -> require(install(driver("{\"libName\":\"vulkan.adreno.so\",\"libraryName\":\"vulkan.adreno.so\",\"minApi\":28,\"minAPI\":28}")).minApi == 28, "Aliases rejected"));
            test("UTF-8 BOM accepted", () -> install(driver("\ufeff{\"libName\":\"vulkan.adreno.so\",\"name\":\"BOM\"}")));
            test("missing metadata rejected", () -> fails(zip("vulkan.adreno.so", elf()), "exatamente um meta.json"));
            test("multiple metadata rejected", () -> fails(zip("meta.json", utf8("{}"), "nested/meta.json", utf8("{}"), "vulkan.adreno.so", elf()), "exatamente um meta.json"));
            test("missing library declaration rejected", () -> fails(driver("{\"name\":\"No library\"}"), "não informa"));
            test("missing declared library rejected", () -> fails(driver("{\"libName\":\"missing.so\"}"), "não foi encontrada"));
            test("ambiguous basename rejected", () -> fails(zip("meta.json", utf8("{\"libName\":\"vulkan.adreno.so\"}"), "one/vulkan.adreno.so", elf(), "two/vulkan.adreno.so", elf()), "ambíguas"));
            test("wrong architecture rejected", () -> {
                byte[] wrong = elf(); wrong[18] = 62;
                fails(zip("meta.json", utf8("{\"libName\":\"vulkan.adreno.so\"}"), "vulkan.adreno.so", wrong), "ELF64 ARM64");
            });
            test("ELF32 rejected", () -> { byte[] wrong = elf(); wrong[4] = 1; fails(zip("meta.json", utf8("{\"libName\":\"vulkan.adreno.so\"}"), "vulkan.adreno.so", wrong), "ELF64 ARM64"); });
            test("big endian ELF rejected", () -> { byte[] wrong = elf(); wrong[5] = 2; fails(zip("meta.json", utf8("{\"libName\":\"vulkan.adreno.so\"}"), "vulkan.adreno.so", wrong), "ELF64 ARM64"); });
            test("ELF executable rejected", () -> { byte[] wrong = elf(); wrong[16] = 2; fails(zip("meta.json", utf8("{\"libName\":\"vulkan.adreno.so\"}"), "vulkan.adreno.so", wrong), "ELF64 ARM64"); });
            test("incomplete library rejected", () -> fails(zip("meta.json", utf8("{\"libName\":\"vulkan.adreno.so\"}"), "vulkan.adreno.so", new byte[16]), "incompleta"));
            test("wrong architecture companion rejected", () -> {
                byte[] wrong = elf(); wrong[18] = 62;
                fails(zip("meta.json", utf8("{\"libName\":\"vulkan.adreno.so\"}"), "vulkan.adreno.so", elf(), "libother.so", wrong), "ELF64 ARM64");
            });
            for (String bad : new String[]{"../escape.so", "folder/../../escape.so", "./escape.so", "folder//escape.so"}) {
                test("unsafe ZIP path rejected: " + bad, () -> fails(zip("meta.json", utf8("{}"), bad, elf()), "sai da pasta"));
            }
            for (String bad : new String[]{"/escape.so", "C:/escape.so", "folder\\escape.so"}) {
                test("absolute or Windows ZIP path rejected: " + bad, () -> fails(zip("meta.json", utf8("{}"), bad, elf()), "Caminho inválido"));
            }
            test("metadata library traversal rejected", () -> fails(driver("{\"libName\":\"../vulkan.adreno.so\"}"), "sai da pasta"));
            test("case-only ZIP collision rejected", () -> fails(zip("meta.json", utf8("{}"), "A.so", elf(), "a.so", elf()), "duplicados"));
            test("reserved manifest rejected", () -> fails(zip("meta.json", utf8("{}"), ".mk-driver.properties", utf8("id=evil")), "reservado"));
            test("directory with payload rejected", () -> fails(zip("meta.json", utf8("{}"), "folder/", new byte[]{1}), "dados em uma pasta"));
            test("Unix symbolic link rejected from central directory", () -> {
                byte[] broken = baseline.clone(); int central = locate(broken, 0x02014b50L, 0);
                broken[central + 5] = 3; broken[central + 40] = (byte)0xff; broken[central + 41] = (byte)0xa1;
                fails(broken, "links simbólicos");
            });
            test("duplicate JSON fields rejected including escaped keys", () -> fails(driver("{\"libName\":\"vulkan.adreno.so\",\"lib\\u004eame\":\"other.so\"}"), "JSON inválido"));
            test("malformed JSON rejected", () -> fails(driver("{\"libName\":\"vulkan.adreno.so\",}"), "JSON inválido"));
            test("trailing JSON rejected", () -> fails(driver("{\"libName\":\"vulkan.adreno.so\"}{}"), "JSON inválido"));
            test("non-object JSON rejected", () -> fails(driver("[]"), "objeto JSON"));
            test("invalid UTF-8 metadata rejected", () -> fails(zip("meta.json", new byte[]{'{','"',(byte)0xff,'"',':','1','}'}, "vulkan.adreno.so", elf()), "UTF-8"));
            test("unpaired surrogate rejected", () -> fails(driver("{\"libName\":\"vulkan.adreno.so\",\"name\":\"\\ud800\"}"), "JSON inválido"));
            test("nested JSON depth bounded", () -> {
                StringBuilder deep = new StringBuilder("{\"libName\":\"vulkan.adreno.so\",\"extra\":");
                for (int i = 0; i < 20; i++) deep.append('['); deep.append("true");
                for (int i = 0; i < 20; i++) deep.append(']'); deep.append('}'); fails(driver(deep.toString()), "JSON inválido");
            });
            test("decimal API requirement rejected", () -> fails(driver("{\"libName\":\"vulkan.adreno.so\",\"minAPI\":28.0}"), "numérico inválido"));
            test("string API requirement rejected", () -> fails(driver("{\"libName\":\"vulkan.adreno.so\",\"minAPI\":\"28\"}"), "numérico inválido"));
            test("conflicting library aliases rejected", () -> fails(driver("{\"libName\":\"vulkan.adreno.so\",\"libraryName\":\"other.so\"}"), "conflitantes"));
            test("conflicting API aliases rejected", () -> fails(driver("{\"libName\":\"vulkan.adreno.so\",\"minAPI\":28,\"minApi\":29}"), "conflitantes"));
            test("unsupported metadata schema rejected", () -> fails(driver("{\"libName\":\"vulkan.adreno.so\",\"schemaVersion\":2}"), "schemaVersion"));
            test("non-ASCII JSON number rejected", () -> fails(driver("{\"libName\":\"vulkan.adreno.so\",\"extra\":1\u0661}"), "JSON inválido"));
            test("metadata size bounded", () -> fails(zip("meta.json", new byte[65537], "vulkan.adreno.so", elf()), "grande demais"));
            test("compressed input size bounded and closes source", () -> {
                final boolean[] closed = {false}; InputStream input = new ByteArrayInputStream(baseline) {
                    @Override public void close() { closed[0] = true; }
                };
                try { DriverManager.install(input, root, new DriverManager.Limits(16, 4096, 4096, 10), new AtomicBoolean(), (f,b,p) -> { }); throw new AssertionError("Archive size bypassed"); }
                catch (IOException expected) { require(expected.getMessage().contains("limite"), "Wrong limit error"); }
                require(closed[0] && noStaging(), "Input or staging not cleaned");
            });
            test("source close failure occurs before commit", () -> {
                Set<String> before = ids();
                InputStream input = new ByteArrayInputStream(driver("{\"libName\":\"vulkan.adreno.so\",\"name\":\"Close failed\"}")) {
                    @Override public void close() throws IOException { throw new IOException("Synthetic source close failure"); }
                };
                try { DriverManager.install(input, root, DriverManager.Limits.defaults(), new AtomicBoolean(), (f,b,p) -> { }); throw new AssertionError("Close failure ignored"); }
                catch (IOException expected) { require(expected.getMessage().contains("close failure"), "Wrong source close error"); }
                require(ids().equals(before) && noStaging(), "Close failure committed a new driver");
            });
            test("progress callback failure occurs before commit", () -> {
                Set<String> before = ids();
                try { DriverManager.install(new ByteArrayInputStream(driver("{\"libName\":\"vulkan.adreno.so\",\"name\":\"Progress failed\"}")), root, DriverManager.Limits.defaults(), new AtomicBoolean(), (f,b,p) -> { throw new IllegalStateException("Synthetic progress failure"); }); throw new AssertionError("Callback failure ignored"); }
                catch (IllegalStateException expected) { }
                require(ids().equals(before) && noStaging(), "Callback failure committed a new driver");
            });
            test("expanded size bounded", () -> {
                try { DriverManager.install(new ByteArrayInputStream(baseline), root, new DriverManager.Limits(4096, 128, 4096, 10), new AtomicBoolean(), (f,b,p) -> { }); throw new AssertionError("Expanded size bypassed"); }
                catch (IOException expected) { require(expected.getMessage().contains("limite"), "Wrong limit error"); }
                require(noStaging() && DriverManager.findInstalled(root, baselineId) != null, "Previous installation changed");
            });
            test("entry count bounded before extraction", () -> {
                try { DriverManager.install(new ByteArrayInputStream(baseline), root, new DriverManager.Limits(4096, 4096, 4096, 1), new AtomicBoolean(), (f,b,p) -> { }); throw new AssertionError("Entry limit bypassed"); }
                catch (IOException expected) { require(expected.getMessage().contains("demais"), "Wrong entry error"); }
                require(noStaging(), "Staging remained");
            });
            test("pre-cancelled import leaves previous drivers", () -> {
                Set<String> before = ids();
                try { DriverManager.install(new ByteArrayInputStream(baseline), root, DriverManager.Limits.defaults(), new AtomicBoolean(true), (f,b,p) -> { }); throw new AssertionError("Cancellation ignored"); }
                catch (DriverManager.Cancelled expected) { }
                require(ids().equals(before), "Previous catalogue changed");
            });
            test("cancellation during extraction leaves previous drivers", () -> {
                Set<String> before = ids(); AtomicBoolean cancelled = new AtomicBoolean();
                try { DriverManager.install(new ByteArrayInputStream(driver("{\"libName\":\"vulkan.adreno.so\",\"name\":\"Cancelled\"}")), root, DriverManager.Limits.defaults(), cancelled, (f,b,p) -> cancelled.set(true)); throw new AssertionError("Cancellation ignored"); }
                catch (DriverManager.Cancelled expected) { }
                require(ids().equals(before) && noStaging(), "Cancellation changed previous installation");
            });
            test("stored entry CRC corruption detected", () -> {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] data = elf();
                try (ZipOutputStream output = new ZipOutputStream(bytes)) {
                    output.putNextEntry(new ZipEntry("meta.json")); output.write(utf8("{\"libName\":\"vulkan.adreno.so\"}")); output.closeEntry();
                    ZipEntry entry = new ZipEntry("vulkan.adreno.so"); entry.setMethod(ZipEntry.STORED); entry.setSize(data.length); entry.setCompressedSize(data.length);
                    CRC32 crc = new CRC32(); crc.update(data); entry.setCrc(crc.getValue()); output.putNextEntry(entry); output.write(data); output.closeEntry();
                }
                byte[] broken = bytes.toByteArray(); int first = locate(broken, 0x04034b50L, 0), second = locate(broken, 0x04034b50L, first + 4);
                int names = (broken[second + 26] & 255) | ((broken[second + 27] & 255) << 8);
                int extras = (broken[second + 28] & 255) | ((broken[second + 29] & 255) << 8);
                broken[second + 30 + names + extras + 128] ^= 1; fails(broken, "CRC inválido");
            });
            test("truncated ZIP directory rejected", () -> fails(Arrays.copyOf(baseline, baseline.length - 10), "incompleto"));
            test("non-ZIP input rejected", () -> fails(new byte[]{1,2,3,4}, "ZIP de driver válido"));
            test("catalogue rejects arbitrary selected identifiers", () -> require(DriverManager.findInstalled(root, "../../escape") == null, "Identifier escaped root"));
            test("selected library mutation rejected by checksum", () -> {
                DriverManager.Driver d = install(driver("{\"name\":\"Mutated\",\"libName\":\"vulkan.adreno.so\"}")); byte[] altered = elf(); altered[128] = 42; Files.write(d.libraryFile.toPath(), altered);
                try { DriverManager.findInstalled(root, d.id); throw new AssertionError("Mutation accepted"); }
                catch (IOException expected) { require(expected.getMessage().contains("alterada"), "Wrong integrity error"); }
                DriverManager.remove(root, d.id);
            });
            test("symlinked catalogue library rejected", () -> {
                DriverManager.Driver d = install(driver("{\"name\":\"Symlink\",\"libName\":\"vulkan.adreno.so\"}"));
                Path other = temporary.resolve("outside.so"); Files.write(other, elf()); Files.delete(d.libraryFile.toPath()); Files.createSymbolicLink(d.libraryFile.toPath(), other);
                try { DriverManager.findInstalled(root, d.id); throw new AssertionError("Library symlink accepted"); }
                catch (IOException expected) { }
                require(!ids().contains(d.id), "Invalid symlink still listed"); DriverManager.remove(root, d.id); require(Files.isRegularFile(other), "Removal followed a link");
            });
            test("symlinked drivers root rejected", () -> {
                Path linked = temporary.resolve("linked-root"); Files.createSymbolicLink(linked, root.toPath());
                try { DriverManager.listInstalled(linked.toFile()); throw new AssertionError("Root symlink accepted"); }
                catch (IOException expected) { require(expected.getMessage().contains("link simbólico"), "Wrong root error"); }
            });
            test("malformed catalogue record ignored", () -> {
                File malformed = new File(root, String.join("", Collections.nCopies(64, "a"))); require(malformed.mkdir(), "Cannot create synthetic record");
                Files.write(new File(malformed, ".mk-driver.properties").toPath(), utf8("id=bad\nlibrary=../escape\n"));
                require(!ids().contains(malformed.getName()), "Malformed record shown"); DriverManager.remove(root, malformed.getName());
            });
            test("malformed Properties Unicode escape does not crash catalogue", () -> {
                File malformed = new File(root, String.join("", Collections.nCopies(64, "b"))); require(malformed.mkdir(), "Cannot create synthetic record");
                Files.write(new File(malformed, ".mk-driver.properties").toPath(), utf8("name=\\uZZZZ\n"));
                require(!ids().contains(malformed.getName()), "Malformed Unicode record shown"); DriverManager.remove(root, malformed.getName());
            });
            test("driver removal preserves other installations", () -> {
                DriverManager.Driver d = install(driver("{\"name\":\"To remove\",\"libName\":\"vulkan.adreno.so\"}")); DriverManager.remove(root, d.id);
                require(DriverManager.findInstalled(root, d.id) == null && DriverManager.findInstalled(root, baselineId) != null, "Removing driver changed another installation");
            });
            test("successful and failed imports leave no staging or escapes", () -> require(noStaging() && !temporary.resolve("escape.so").toFile().exists(), "Archive escaped or staging remained"));
            System.out.println("RESULT: " + passed + " passed, 0 failed");
        } finally { removeTree(temporary); }
    }
}
