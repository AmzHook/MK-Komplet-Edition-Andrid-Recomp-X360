package org.amzhook.mkimporter;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Imports an AdrenoTools package without loading or executing its libraries. */
public final class DriverManager {
    private static final Object OPERATIONS_LOCK = new Object();
    private static final String RECORD = ".mk-driver.properties";
    private static final int META_LIMIT = 64 * 1024;

    private DriverManager() { }

    public interface Progress { void update(int files, long bytes, String path); }

    public static final class Limits {
        public final long archiveBytes, totalBytes, fileBytes;
        public final int entries;
        public Limits(long archiveBytes, long totalBytes, long fileBytes, int entries) {
            if (archiveBytes <= 0 || totalBytes <= 0 || fileBytes <= 0 || entries <= 0)
                throw new IllegalArgumentException("Os limites devem ser positivos.");
            this.archiveBytes = archiveBytes; this.totalBytes = totalBytes;
            this.fileBytes = fileBytes; this.entries = entries;
        }
        public static Limits defaults() { return new Limits(64L << 20, 128L << 20, 64L << 20, 100); }
    }

    public static final class Driver {
        public final String id, name, description, author, vendor, driverVersion, packageVersion;
        public final String libraryName, libraryPath, librarySha256;
        public final int minApi;
        public final File installationDirectory, directory, libraryFile;
        private Driver(String id, File installationDirectory, File libraryFile,
                       Properties record) {
            this.id = id; this.installationDirectory = installationDirectory;
            this.libraryFile = libraryFile; this.directory = libraryFile.getParentFile();
            this.libraryName = libraryFile.getName(); this.libraryPath = libraryFile.getAbsolutePath();
            this.librarySha256 = record.getProperty("librarySha256");
            this.name = record.getProperty("name"); this.description = record.getProperty("description", "");
            this.author = record.getProperty("author", ""); this.vendor = record.getProperty("vendor", "");
            this.driverVersion = record.getProperty("driverVersion", "");
            this.packageVersion = record.getProperty("packageVersion", "");
            this.minApi = Integer.parseInt(record.getProperty("minApi", "0"));
        }
        @Override public String toString() { return name; }
    }

    public static final class Cancelled extends IOException {
        private static final long serialVersionUID = 1L;
        public Cancelled() { super("Importação do driver cancelada."); }
    }

    private static void checkCancel(AtomicBoolean cancel) throws Cancelled {
        if (cancel.get() || Thread.currentThread().isInterrupted()) throw new Cancelled();
    }

    private static File prepareRoot(File supplied) throws IOException {
        if (Files.isSymbolicLink(supplied.toPath()))
            throw new IOException("A pasta de drivers não pode ser um link simbólico.");
        File root = supplied.getCanonicalFile();
        if (!root.isDirectory() && !root.mkdirs())
            throw new IOException("Não foi possível criar a pasta de drivers.");
        return root;
    }

    private static File safeDestination(File root, String path) throws IOException {
        if (path == null || path.isEmpty() || path.length() > 1024 || path.startsWith("/")
                || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0 || path.indexOf('\0') >= 0)
            throw new IOException("Caminho inválido no ZIP do driver.");
        String withoutSlash = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        for (String part : withoutSlash.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals(".."))
                throw new IOException("O ZIP do driver contém um caminho que sai da pasta do aplicativo.");
            for (int i = 0; i < part.length(); i++) {
                if (part.charAt(i) < 32 || part.charAt(i) == 127)
                    throw new IOException("Caminho inválido no ZIP do driver.");
            }
        }
        File destination = new File(root, withoutSlash).getCanonicalFile();
        if (!destination.getPath().startsWith(root.getCanonicalPath() + File.separator))
            throw new IOException("Caminho inválido no ZIP do driver.");
        return destination;
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Não foi possível criar a pasta do driver.");
    }

    private static void deleteTree(File target) throws IOException {
        if (!target.exists() && !Files.isSymbolicLink(target.toPath())) return;
        if (target.isDirectory() && !Files.isSymbolicLink(target.toPath())) {
            File[] children = target.listFiles();
            if (children == null) throw new IOException("Não foi possível ler a pasta do driver.");
            for (File child : children) deleteTree(child);
        }
        if (!target.delete()) throw new IOException("Não foi possível remover um arquivo do driver.");
    }

    private static MessageDigest sha256() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IOException(e); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) result.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return result.toString();
    }

    private static String hashFile(File file, AtomicBoolean cancel) throws IOException {
        MessageDigest digest = sha256(); byte[] buffer = new byte[64 * 1024];
        try (InputStream input = new FileInputStream(file)) {
            int count;
            while ((count = input.read(buffer)) != -1) { checkCancel(cancel); digest.update(buffer, 0, count); }
        }
        return hex(digest.digest());
    }

    /** Owns and closes source. A failed import never replaces an installed driver. */
    public static Driver install(InputStream source, File driversRoot, Limits limits,
                                 AtomicBoolean cancel, Progress progress) throws IOException {
        Objects.requireNonNull(source); Objects.requireNonNull(limits); Objects.requireNonNull(cancel);
        Objects.requireNonNull(progress);
        synchronized (OPERATIONS_LOCK) {
            File temporary = null; boolean sourceClosed = false;
            try {
                checkCancel(cancel);
                File root = prepareRoot(driversRoot);
                temporary = Files.createTempDirectory(root.toPath(), ".import-").toFile();
                File archive = new File(temporary, "package.zip");
                File staging = new File(temporary, "contents"); ensureDirectory(staging);
                MessageDigest packageDigest = sha256(); byte[] buffer = new byte[64 * 1024];
                long copied = 0;
                try (InputStream input = source;
                     OutputStream output = new BufferedOutputStream(new FileOutputStream(archive))) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        checkCancel(cancel); copied += count;
                        if (copied > limits.archiveBytes) throw new IOException("O ZIP do driver ultrapassa o limite de tamanho.");
                        packageDigest.update(buffer, 0, count); output.write(buffer, 0, count);
                    }
                } finally { sourceClosed = true; }
                String id = hex(packageDigest.digest());
                validateCentralDirectory(archive, limits.entries);
                List<File> metadata = new ArrayList<>(), files = new ArrayList<>();
                Set<String> seen = new HashSet<>(); int entries = 0; long total = 0, lastReport = 0;
                try (ZipFile zip = new ZipFile(archive, java.nio.charset.Charset.forName("Cp437"))) {
                    Enumeration<? extends ZipEntry> iterator = zip.entries();
                    while (iterator.hasMoreElements()) {
                        checkCancel(cancel); ZipEntry entry = iterator.nextElement();
                        if (++entries > limits.entries) throw new IOException("O ZIP do driver contém arquivos demais.");
                        File destination = safeDestination(staging, entry.getName());
                        String relative = staging.toPath().relativize(destination.toPath()).toString();
                        if (!seen.add(relative.toLowerCase(Locale.ROOT)))
                            throw new IOException("O ZIP do driver contém caminhos duplicados.");
                        if (destination.getName().equalsIgnoreCase(RECORD))
                            throw new IOException("O ZIP do driver contém um nome reservado pelo aplicativo.");
                        if (entry.isDirectory()) {
                            if (entry.getSize() != 0) throw new IOException("O ZIP do driver contém dados em uma pasta.");
                            ensureDirectory(destination); continue;
                        }
                        if (entry.getSize() < 0 || entry.getSize() > limits.fileBytes)
                            throw new IOException("Um arquivo do driver ultrapassa o limite de tamanho.");
                        ensureDirectory(destination.getParentFile()); CRC32 crc = new CRC32(); long written = 0;
                        try (InputStream entryInput = zip.getInputStream(entry);
                             OutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
                            int count;
                            while ((count = entryInput.read(buffer)) != -1) {
                                checkCancel(cancel); written += count; total += count;
                                if (written > limits.fileBytes || total > limits.totalBytes)
                                    throw new IOException("O conteúdo extraído do driver ultrapassa o limite de tamanho.");
                                crc.update(buffer, 0, count); output.write(buffer, 0, count);
                                long now = System.nanoTime();
                                if (now - lastReport > 200000000L) {
                                    progress.update(files.size(), total, entry.getName()); lastReport = now;
                                }
                            }
                        }
                        if (written != entry.getSize() || crc.getValue() != entry.getCrc())
                            throw new IOException("O ZIP do driver está corrompido (tamanho ou CRC inválido).");
                        files.add(destination);
                        if (destination.getName().equalsIgnoreCase("meta.json")) metadata.add(destination);
                        long now = System.nanoTime();
                        if (now - lastReport > 200000000L) {
                            progress.update(files.size(), total, entry.getName()); lastReport = now;
                        }
                    }
                }
                if (metadata.size() != 1)
                    throw new IOException("O ZIP deve conter exatamente um meta.json de driver AdrenoTools.");
                Map<String, Object> meta = readMetadata(metadata.get(0));
                String requested = aliasText(meta, "libName", "libraryName", 1024);
                if (requested.isEmpty()) throw new IOException("meta.json não informa libName ou libraryName.");
                // Validate a declared relative library path before resolving a basename fallback.
                safeDestination(staging, requested);
                File library = chooseLibrary(staging, metadata.get(0).getParentFile(), requested, files);
                validateElf(library);
                for (File companion : files) {
                    if (companion.getName().endsWith(".so") && !companion.equals(library)) validateElf(companion);
                }
                Properties record = new Properties();
                record.setProperty("id", id);
                record.setProperty("library", staging.toPath().relativize(library.toPath()).toString().replace(File.separatorChar, '/'));
                record.setProperty("librarySha256", hashFile(library, cancel));
                String name = text(meta, "name", 160);
                record.setProperty("name", name.isEmpty() ? library.getName() : name);
                record.setProperty("description", text(meta, "description", 2048));
                record.setProperty("author", text(meta, "author", 256));
                record.setProperty("vendor", text(meta, "vendor", 128));
                record.setProperty("driverVersion", text(meta, "driverVersion", 128));
                record.setProperty("packageVersion", aliasText(meta, "packageVersion", "version", 128));
                record.setProperty("minApi", Integer.toString(aliasInteger(meta, "minAPI", "minApi", 0, 10000)));
                if (meta.containsKey("schemaVersion")) integer(meta.get("schemaVersion"), "schemaVersion", 1, 1);
                try (OutputStream output = new FileOutputStream(new File(staging, RECORD))) {
                    record.store(output, "Validated MK AdrenoTools driver package");
                }
                progress.update(files.size(), total, library.getName());
                checkCancel(cancel);
                File installed = new File(root, id);
                if (installed.exists()) {
                    Driver existing = readInstalled(root, id, true);
                    if (existing == null) throw new IOException("Já existe um registro inválido deste driver. Remova-o antes de importar novamente.");
                    return existing;
                }
                Files.move(staging.toPath(), installed.toPath(), StandardCopyOption.ATOMIC_MOVE);
                return new Driver(id, installed, new File(installed, record.getProperty("library")), record);
            } finally {
                try { if (!sourceClosed) source.close(); }
                finally {
                    // A cleanup failure must not turn an already committed import into a UI failure.
                    // Hidden temporary directories are never exposed by the installed catalogue.
                    if (temporary != null) {
                        try { deleteTree(temporary); } catch (IOException ignored) { }
                    }
                }
            }
        }
    }

    private static File chooseLibrary(File root, File metaParent, String requested, List<File> files) throws IOException {
        File relativeToMeta = safeDestination(metaParent, requested);
        File relativeToRoot = safeDestination(root, requested);
        if (relativeToMeta.isFile()) return relativeToMeta;
        if (relativeToRoot.isFile()) return relativeToRoot;
        if (requested.indexOf('/') >= 0) throw new IOException("A biblioteca informada no meta.json não foi encontrada.");
        File match = null;
        for (File candidate : files) {
            if (candidate.getName().equals(requested)) {
                if (match != null) throw new IOException("O ZIP contém bibliotecas de driver ambíguas.");
                match = candidate;
            }
        }
        if (match == null) throw new IOException("A biblioteca informada no meta.json não foi encontrada.");
        return match;
    }

    /** Lists only complete records; hidden or malformed staging directories are ignored. */
    public static List<Driver> listInstalled(File driversRoot) throws IOException {
        synchronized (OPERATIONS_LOCK) {
            File root = prepareRoot(driversRoot); File[] children = root.listFiles();
            if (children == null) throw new IOException("Não foi possível ler a pasta de drivers.");
            List<Driver> result = new ArrayList<>();
            for (File child : children) {
                if (!child.getName().matches("[0-9a-f]{64}")) continue;
                try { Driver driver = readInstalled(root, child.getName(), false); if (driver != null) result.add(driver); }
                catch (IOException ignored) { }
            }
            result.sort(Comparator.comparing((Driver d) -> d.name, String.CASE_INSENSITIVE_ORDER).thenComparing(d -> d.id));
            return result;
        }
    }

    /** Rechecks the library checksum when selecting a driver for execution. */
    public static Driver findInstalled(File driversRoot, String id) throws IOException {
        synchronized (OPERATIONS_LOCK) { return readInstalled(prepareRoot(driversRoot), id, true); }
    }

    public static void remove(File driversRoot, String id) throws IOException {
        synchronized (OPERATIONS_LOCK) {
            File root = prepareRoot(driversRoot);
            if (id == null || !id.matches("[0-9a-f]{64}")) throw new IOException("Identificador de driver inválido.");
            deleteTree(new File(root, id));
        }
    }

    private static Driver readInstalled(File root, String id, boolean verifyHash) throws IOException {
        if (id == null || !id.matches("[0-9a-f]{64}")) return null;
        File directory = new File(root, id);
        if (Files.isSymbolicLink(directory.toPath())) throw new IOException("Registro de driver inválido.");
        File metadata = new File(directory, RECORD);
        if (!metadata.isFile()) return null;
        if (metadata.length() > META_LIMIT || Files.isSymbolicLink(metadata.toPath()))
            throw new IOException("Registro de driver inválido.");
        Properties p = new Properties();
        try (InputStream input = new FileInputStream(metadata)) {
            try { p.load(input); }
            catch (IllegalArgumentException e) { throw new IOException("Registro de driver inválido.", e); }
        }
        String hash = p.getProperty("librarySha256");
        File library = safeDestination(directory, p.getProperty("library"));
        if (!id.equals(p.getProperty("id")) || hash == null || !hash.matches("[0-9a-f]{64}")
                || !library.isFile() || p.getProperty("name", "").isEmpty())
            throw new IOException("Registro de driver inválido.");
        // Canonical containment prevents a replaced library or parent link escaping its package.
        File cursor = new File(directory, p.getProperty("library"));
        while (!cursor.equals(directory)) {
            if (Files.isSymbolicLink(cursor.toPath())) throw new IOException("Registro de driver inválido.");
            cursor = cursor.getParentFile();
        }
        try {
            int minApi = Integer.parseInt(p.getProperty("minApi", "0"));
            if (minApi < 0 || minApi > 10000) throw new NumberFormatException();
        } catch (NumberFormatException e) { throw new IOException("Registro de driver inválido.", e); }
        validateElf(library);
        if (verifyHash && !hash.equals(hashFile(library, new AtomicBoolean())))
            throw new IOException("A biblioteca do driver foi alterada. Importe o ZIP novamente.");
        return new Driver(id, directory.getCanonicalFile(), library, p);
    }

    private static int u16(byte[] b, int at) { return (b[at] & 255) | ((b[at + 1] & 255) << 8); }
    private static long u32(byte[] b, int at) { return (b[at] & 255L) | ((b[at + 1] & 255L) << 8) | ((b[at + 2] & 255L) << 16) | ((b[at + 3] & 255L) << 24); }

    private static void validateElf(File library) throws IOException {
        byte[] header = new byte[64];
        try (DataInputStream input = new DataInputStream(new FileInputStream(library))) {
            try { input.readFully(header); }
            catch (EOFException e) { throw new IOException("A biblioteca do driver está incompleta.", e); }
        }
        if (header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F'
                || header[4] != 2 || header[5] != 1 || header[6] != 1
                || u16(header, 16) != 3 || u16(header, 18) != 183 || u32(header, 20) != 1
                || u16(header, 52) != 64)
            throw new IOException("O driver deve conter uma biblioteca ELF64 ARM64 compartilhada para Android.");
    }

    // Inspect the ZIP directory because java.util.zip does not expose Unix symlink attributes.
    private static void validateCentralDirectory(File archive, int maximumEntries) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(archive, "r")) {
            long length = file.length();
            if (length < 22) throw new IOException("O arquivo não é um ZIP de driver válido.");
            int tailLength = (int)Math.min(length, 65557); byte[] tail = new byte[tailLength];
            file.seek(length - tailLength); file.readFully(tail); int end = -1;
            for (int at = tail.length - 22; at >= 0; at--) {
                if (u32(tail, at) == 0x06054b50L && at + 22 + u16(tail, at + 20) == tail.length) { end = at; break; }
            }
            if (end < 0) throw new IOException("O ZIP do driver está incompleto.");
            int count = u16(tail, end + 10);
            long size = u32(tail, end + 12), start = u32(tail, end + 16), endPosition = length - tailLength + end;
            if (u16(tail, end + 4) != 0 || u16(tail, end + 6) != 0 || u16(tail, end + 8) != count
                    || count == 65535 || start == 0xffffffffL || size == 0xffffffffL || start + size != endPosition)
                throw new IOException("ZIP dividido ou ZIP64 de driver não suportado.");
            if (count == 0) throw new IOException("O ZIP do driver está vazio.");
            if (count > maximumEntries) throw new IOException("O ZIP do driver contém arquivos demais.");
            file.seek(start); byte[] header = new byte[46];
            for (int i = 0; i < count; i++) {
                if (file.getFilePointer() + header.length > endPosition) throw new IOException("Diretório do ZIP de driver inválido.");
                file.readFully(header);
                if (u32(header, 0) != 0x02014b50L) throw new IOException("Diretório do ZIP de driver inválido.");
                int flags = u16(header, 8), method = u16(header, 10), mode = (int)(u32(header, 38) >>> 16);
                int kind = mode & 0170000;
                if ((flags & 65) != 0) throw new IOException("ZIP de driver criptografado não suportado.");
                if (method != 0 && method != 8) throw new IOException("Método de compressão do driver não suportado.");
                if (kind != 0 && kind != 0100000 && kind != 0040000)
                    throw new IOException("O ZIP do driver contém links simbólicos ou arquivos especiais.");
                if (u16(header, 34) != 0 || u32(header, 42) >= start)
                    throw new IOException("Diretório do ZIP de driver inválido.");
                long next = file.getFilePointer() + u16(header, 28) + u16(header, 30) + u16(header, 32);
                if (next > endPosition) throw new IOException("Diretório do ZIP de driver inválido.");
                file.seek(next);
            }
            if (file.getFilePointer() != endPosition) throw new IOException("Diretório do ZIP de driver inválido.");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readMetadata(File file) throws IOException {
        if (file.length() > META_LIMIT) throw new IOException("meta.json do driver é grande demais.");
        byte[] bytes = Files.readAllBytes(file.toPath()); String json;
        try {
            json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) { throw new IOException("meta.json do driver não é UTF-8 válido.", e); }
        Object parsed = new Json(json).parse();
        if (!(parsed instanceof Map)) throw new IOException("meta.json do driver deve ser um objeto JSON.");
        return (Map<String, Object>)parsed;
    }

    private static String text(Map<String, Object> meta, String key, int limit) throws IOException {
        Object value = meta.get(key);
        if (value == null) return "";
        if (!(value instanceof String)) throw new IOException("Campo inválido no meta.json: " + key + ".");
        String result = ((String)value).trim();
        if (result.length() > limit || result.indexOf('\0') >= 0) throw new IOException("Campo inválido no meta.json: " + key + ".");
        return result;
    }

    private static String aliasText(Map<String, Object> meta, String first, String second, int limit) throws IOException {
        String a = text(meta, first, limit), b = text(meta, second, limit);
        if (!a.isEmpty() && !b.isEmpty() && !a.equals(b)) throw new IOException("Campos conflitantes no meta.json: " + first + " e " + second + ".");
        return a.isEmpty() ? b : a;
    }

    private static int aliasInteger(Map<String, Object> meta, String first, String second, int minimum, int maximum) throws IOException {
        if (!meta.containsKey(first) && !meta.containsKey(second)) return minimum;
        int a = meta.containsKey(first) ? integer(meta.get(first), first, minimum, maximum) : -1;
        int b = meta.containsKey(second) ? integer(meta.get(second), second, minimum, maximum) : -1;
        if (a >= 0 && b >= 0 && a != b) throw new IOException("Campos conflitantes no meta.json: " + first + " e " + second + ".");
        return a >= 0 ? a : b;
    }

    private static int integer(Object value, String key, int minimum, int maximum) throws IOException {
        if (!(value instanceof JsonNumber) || !((JsonNumber)value).value.matches("0|[1-9][0-9]*"))
            throw new IOException("Campo numérico inválido no meta.json: " + key + ".");
        try {
            int number = Integer.parseInt(((JsonNumber)value).value);
            if (number < minimum || number > maximum) throw new NumberFormatException();
            return number;
        } catch (NumberFormatException e) { throw new IOException("Campo numérico inválido no meta.json: " + key + ".", e); }
    }

    private static final class JsonNumber {
        final String value;
        JsonNumber(String value) { this.value = value; }
    }

    /** Small bounded strict JSON reader; keeps numeric tokens exact without floating-point conversion. */
    private static final class Json {
        final String source; int at;
        Json(String source) { this.source = source; }
        IOException invalid() { return new IOException("meta.json do driver contém JSON inválido."); }
        void whitespace() { while (at < source.length() && " \t\r\n".indexOf(source.charAt(at)) >= 0) at++; }
        boolean take(char c) { whitespace(); if (at < source.length() && source.charAt(at) == c) { at++; return true; } return false; }
        Object parse() throws IOException {
            if (!source.isEmpty() && source.charAt(0) == '\ufeff') at++;
            Object result = value(0); whitespace(); if (at != source.length()) throw invalid(); return result;
        }
        Object value(int depth) throws IOException {
            if (depth > 16) throw invalid(); whitespace(); if (at == source.length()) throw invalid();
            char c = source.charAt(at);
            if (c == '"') return string();
            if (take('{')) {
                Map<String, Object> object = new LinkedHashMap<>(); if (take('}')) return object;
                do {
                    whitespace(); if (at == source.length() || source.charAt(at) != '"') throw invalid();
                    String key = string(); if (object.containsKey(key) || !take(':')) throw invalid();
                    object.put(key, value(depth + 1)); if (take('}')) return object;
                } while (take(','));
                throw invalid();
            }
            if (take('[')) {
                List<Object> array = new ArrayList<>(); if (take(']')) return array;
                do { array.add(value(depth + 1)); if (take(']')) return array; } while (take(','));
                throw invalid();
            }
            for (String literal : new String[]{"true", "false", "null"}) {
                if (source.startsWith(literal, at)) { at += literal.length(); return literal.equals("null") ? null : Boolean.valueOf(literal); }
            }
            int start = at;
            if (c == '-') at++;
            if (at == source.length()) throw invalid();
            if (source.charAt(at) == '0') at++;
            else {
                if (source.charAt(at) < '1' || source.charAt(at) > '9') throw invalid();
                while (at < source.length() && source.charAt(at) >= '0' && source.charAt(at) <= '9') at++;
            }
            if (at < source.length() && source.charAt(at) == '.') {
                at++; int digits = at;
                while (at < source.length() && source.charAt(at) >= '0' && source.charAt(at) <= '9') at++;
                if (digits == at) throw invalid();
            }
            if (at < source.length() && (source.charAt(at) == 'e' || source.charAt(at) == 'E')) {
                at++; if (at < source.length() && (source.charAt(at) == '+' || source.charAt(at) == '-')) at++;
                int digits = at;
                while (at < source.length() && source.charAt(at) >= '0' && source.charAt(at) <= '9') at++;
                if (digits == at) throw invalid();
            }
            if (at - start > 64) throw invalid();
            return new JsonNumber(source.substring(start, at));
        }
        String string() throws IOException {
            if (source.charAt(at++) != '"') throw invalid(); StringBuilder result = new StringBuilder();
            while (at < source.length()) {
                char c = source.charAt(at++);
                if (c == '"') {
                    for (int i = 0; i < result.length(); i++) {
                        char character = result.charAt(i);
                        if (Character.isHighSurrogate(character)) {
                            if (++i == result.length() || !Character.isLowSurrogate(result.charAt(i))) throw invalid();
                        } else if (Character.isLowSurrogate(character)) throw invalid();
                    }
                    return result.toString();
                }
                if (c < 32) throw invalid();
                if (c == '\\') {
                    if (at == source.length()) throw invalid(); char escaped = source.charAt(at++);
                    switch (escaped) {
                        case '"': case '\\': case '/': c = escaped; break;
                        case 'b': c = '\b'; break; case 'f': c = '\f'; break;
                        case 'n': c = '\n'; break; case 'r': c = '\r'; break; case 't': c = '\t'; break;
                        case 'u':
                            if (at + 4 > source.length()) throw invalid(); int number = 0;
                            for (int i = 0; i < 4; i++) {
                                char hex = source.charAt(at++);
                                int digit = "0123456789abcdef".indexOf(Character.toLowerCase(hex));
                                if (digit < 0) throw invalid(); number = (number << 4) | digit;
                            }
                            c = (char)number; break;
                        default: throw invalid();
                    }
                }
                result.append(c);
            }
            throw invalid();
        }
    }
}
