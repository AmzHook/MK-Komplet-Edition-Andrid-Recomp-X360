package org.amzhook.mkimporter;

import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Platform-independent importer. Never executes imported binaries. */
public final class ZipImporter {
    public interface Progress { void update(int files, long bytes, String path); }
    public static final class Limits {
        final long totalBytes, fileBytes;
        final int entries;
        public Limits(long totalBytes, long fileBytes, int entries) {
            this.totalBytes = totalBytes; this.fileBytes = fileBytes; this.entries = entries;
        }
        public static Limits defaults() { return new Limits(24L << 30, 12L << 30, 100000); }
    }
    public static final class Result {
        public final String xexPath, sha256;
        public final int files;
        public final long bytes;
        Result(String path, String hash, int files, long bytes) {
            this.xexPath = path; this.sha256 = hash; this.files = files; this.bytes = bytes;
        }
    }
    public static final class Cancelled extends IOException {
        private static final long serialVersionUID = 1L;
        public Cancelled() { super("Importação cancelada."); }
    }

    private static void checkCancel(AtomicBoolean cancel) throws Cancelled {
        if (cancel.get() || Thread.currentThread().isInterrupted()) throw new Cancelled();
    }

    private static File safeDestination(File root, String name) throws IOException {
        if (name.isEmpty() || name.length() > 4096 || name.startsWith("/")
                || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0 || name.indexOf('\0') >= 0)
            throw new IOException("Caminho inválido no ZIP.");
        for (String segment : name.split("/", -1)) {
            if (segment.equals("..") || segment.equals("."))
                throw new IOException("O ZIP contém um caminho que sai da pasta do aplicativo.");
        }
        File target = new File(root, name).getCanonicalFile();
        if (!target.getPath().startsWith(root.getCanonicalPath() + File.separator))
            throw new IOException("Caminho inválido no ZIP.");
        return target;
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Não foi possível criar a pasta de importação.");
    }

    public static void deleteTree(File target) throws IOException {
        if (!target.exists()) return;
        // Do not follow a symbolic link if an unexpected local file exists.
        if (target.isDirectory() && !Files.isSymbolicLink(target.toPath())) {
            File[] children = target.listFiles();
            if (children == null) throw new IOException("Não foi possível ler a pasta de importação.");
            for (File child : children) deleteTree(child);
        }
        if (!target.delete()) throw new IOException("Não foi possível remover um arquivo local.");
    }

    public static void recover(File appRoot) throws IOException {
        ensureDirectory(appRoot);
        File current = new File(appRoot, "game");
        File backup = new File(appRoot, "game.backup");
        if (!current.exists() && backup.exists()) Files.move(backup.toPath(), current.toPath());
        if (current.exists() && backup.exists()) deleteTree(backup);
        deleteTree(new File(appRoot, "game.staging"));
    }

    public static Result readInstalled(File appRoot) throws IOException {
        File game = new File(appRoot, "game");
        File metadata = new File(game, ".mk-import.properties");
        if (!metadata.isFile()) return null;
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(metadata)) { p.load(in); }
        try {
            String path = p.getProperty("xex");
            String hash = p.getProperty("sha256");
            if (path == null || hash == null || !hash.matches("[0-9a-f]{64}")
                    || !safeDestination(game, path).isFile())
                throw new IOException("Registro de importação inválido.");
            return new Result(path, hash, Integer.parseInt(p.getProperty("files")),
                    Long.parseLong(p.getProperty("bytes")));
        } catch (NumberFormatException e) { throw new IOException("Registro de importação inválido.", e); }
    }

    public static Result install(InputStream source, File appRoot, Limits limits,
                                 AtomicBoolean cancel, Progress progress) throws IOException {
        recover(appRoot);
        File staging = new File(appRoot, "game.staging").getCanonicalFile();
        ensureDirectory(staging);
        boolean committed = false;
        try {
            long total = 0;
            int files = 0, entries = 0;
            Set<String> seen = new HashSet<>();
            List<File> executables = new ArrayList<>();
            byte[] buffer = new byte[256 * 1024];
            long lastReport = 0;
            try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(source))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    checkCancel(cancel);
                    if (++entries > limits.entries) throw new IOException("O ZIP contém arquivos demais.");
                    File target = safeDestination(staging, entry.getName());
                    String relative = staging.toPath().relativize(target.toPath()).toString();
                    if (target.getName().equals(".mk-import.properties"))
                        throw new IOException("O ZIP contém um nome reservado pelo aplicativo.");
                    // Case-only collisions are rejected for Windows-derived game layouts.
                    if (!seen.add(relative.toLowerCase(Locale.ROOT)))
                        throw new IOException("O ZIP contém caminhos duplicados.");
                    if (entry.isDirectory()) {
                        if (entry.getSize() > 0 || zip.read() != -1)
                            throw new IOException("O ZIP contém dados em uma entrada de pasta.");
                        ensureDirectory(target); zip.closeEntry(); continue;
                    }
                    if (entry.getSize() > limits.fileBytes)
                        throw new IOException("Um arquivo ultrapassa o limite de importação.");
                    ensureDirectory(target.getParentFile());
                    long available = staging.getUsableSpace() - (64L << 20);
                    if (available <= 0) throw new IOException("Espaço livre insuficiente. Reserve pelo menos 64 MB adicionais.");
                    long written = 0;
                    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
                        int count;
                        while ((count = zip.read(buffer)) != -1) {
                            checkCancel(cancel);
                            written += count; total += count;
                            if (written > limits.fileBytes || total > limits.totalBytes)
                                throw new IOException("O conteúdo extraído ultrapassa o limite de importação.");
                            if (written > available) throw new IOException("Espaço livre insuficiente para o conteúdo extraído.");
                            out.write(buffer, 0, count);
                            long now = System.nanoTime();
                            if (now - lastReport > 200000000L) {
                                progress.update(files, total, entry.getName()); lastReport = now;
                            }
                        }
                    }
                    zip.closeEntry();
                    files++;
                    if (target.getName().equalsIgnoreCase("default.xex")) executables.add(target);
                }
            }
            if (files == 0) throw new IOException("O ZIP está vazio ou não é um arquivo ZIP válido.");
            if (executables.size() != 1)
                throw new IOException("O ZIP deve conter exatamente um default.xex e os arquivos do jogo.");
            checkCancel(cancel);
            File executable = executables.get(0);
            if (executable.length() < 24) throw new IOException("default.xex está incompleto.");
            MessageDigest digest;
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException e) { throw new IOException(e); }
            try (InputStream in = new FileInputStream(executable)) {
                byte[] header = new byte[4];
                if (in.read(header) != 4 || !Arrays.equals(header, new byte[]{'X','E','X','2'}))
                    throw new IOException("default.xex não possui o cabeçalho XEX2 esperado.");
                digest.update(header);
                int count;
                while ((count = in.read(buffer)) != -1) { checkCancel(cancel); digest.update(buffer, 0, count); }
            }
            StringBuilder hash = new StringBuilder();
            for (byte b : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
            String path = staging.toPath().relativize(executable.toPath()).toString();
            Result result = new Result(path, hash.toString(), files, total);
            Properties metadata = new Properties();
            metadata.setProperty("xex", path); metadata.setProperty("sha256", result.sha256);
            metadata.setProperty("files", Integer.toString(files)); metadata.setProperty("bytes", Long.toString(total));
            try (OutputStream out = new FileOutputStream(new File(staging, ".mk-import.properties"))) {
                metadata.store(out, "MK Importador: metadata only, no game execution");
            }
            checkCancel(cancel);
            File current = new File(appRoot, "game"), backup = new File(appRoot, "game.backup");
            if (current.exists()) Files.move(current.toPath(), backup.toPath());
            try { Files.move(staging.toPath(), current.toPath()); }
            catch (IOException e) {
                if (backup.exists()) Files.move(backup.toPath(), current.toPath());
                throw e;
            }
            committed = true;
            // Backup cleanup can be retried at startup without invalidating the successful import.
            try { deleteTree(backup); } catch (IOException ignored) { }
            progress.update(files, total, path);
            return result;
        } finally {
            if (!committed) deleteTree(staging);
        }
    }
}
