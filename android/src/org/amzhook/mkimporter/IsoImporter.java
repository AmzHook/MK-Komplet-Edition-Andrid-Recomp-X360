package org.amzhook.mkimporter;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reads Xbox 360 XDVDFS images; unrelated ISO 9660 files are not supported. */
public final class IsoImporter {
    /** A random-access source whose lifetime remains owned by the caller. */
    public interface SeekableSource {
        long length() throws IOException;
        void readFully(long offset, byte[] data, int start, int count) throws IOException;
    }

    private static final int SECTOR_BYTES = 2048;
    private static final int HEADER_BYTES = 14;
    private static final long DESCRIPTOR_OFFSET = 32L * SECTOR_BYTES;
    private static final long[] PARTITION_BASES = {0, 0x0FD90000L, 0x02080000L};
    private static final byte[] MAGIC = "MICROSOFT*XBOX*MEDIA".getBytes(StandardCharsets.US_ASCII);
    private static final Charset NAME_CHARSET = Charset.forName("windows-1252");
    private static final long MAX_DIRECTORY_BYTES = 32L << 20;
    private static final int MAX_DIRECTORY_DEPTH = 128;
    private static final long MAX_PATH_CHARACTERS = 16L << 20;
    private static final long MAX_METADATA_READ_BYTES = 64L << 20;
    private static final int MAX_METADATA_READS = 1000000;
    private static final long FREE_SPACE_RESERVE = 64L << 20;

    private IsoImporter() { }

    private static final class Directory {
        final long offset, length;
        final String path;
        final int depth;
        final Directory parent;
        Directory(long offset, long length, String path, int depth, Directory parent) {
            this.offset = offset; this.length = length; this.path = path; this.depth = depth;
            this.parent = parent;
        }
    }

    private static final class Item {
        final String path;
        final long offset, length;
        final boolean directory;
        Item(String path, long offset, long length, boolean directory) {
            this.path = path; this.offset = offset; this.length = length; this.directory = directory;
        }
    }

    private static final class Record {
        final int offset, left, right;
        final long sector, length;
        final String name;
        final boolean directory;
        final int occupiedBytes;
        Record(int offset, byte[] header, String name) {
            this.offset = offset;
            left = u16(header, 0); right = u16(header, 2);
            sector = u32(header, 4); length = u32(header, 8);
            directory = (header[12] & 0x10) != 0;
            this.name = name;
            occupiedBytes = (HEADER_BYTES + (header[13] & 255) + 3) & ~3;
        }
    }

    private static final class Reader {
        final SeekableSource source;
        final long sourceLength;
        final AtomicBoolean cancel;
        final ZipImporter.Limits limits;
        final ZipImporter.Progress progress;
        final Set<String> paths = new HashSet<>();
        final List<Item> items = new ArrayList<>();
        long partitionBase, totalBytes, pathCharacters, lastReport, metadataReadBytes;
        int entries, files, executables, metadataReads;

        Reader(SeekableSource source, ZipImporter.Limits limits, AtomicBoolean cancel,
               ZipImporter.Progress progress) throws IOException {
            this.source = source; this.limits = limits; this.cancel = cancel; this.progress = progress;
            sourceLength = source.length();
            if (sourceLength < 0) throw new IOException("Não foi possível determinar o tamanho da ISO.");
            if (limits.totalBytes < 0 || limits.fileBytes < 0 || limits.entries < 1)
                throw new IOException("Limites de importação inválidos.");
        }

        void checkRange(long offset, long count) throws IOException {
            if (offset < 0 || count < 0 || offset > sourceLength || count > sourceLength - offset)
                throw new IOException("A ISO está incompleta ou contém dados fora dos limites da imagem.");
        }

        void read(long offset, byte[] bytes, int start, int count) throws IOException {
            ZipImporter.checkCancel(cancel);
            checkRange(offset, count);
            source.readFully(offset, bytes, start, count);
        }

        void readMetadata(long offset, byte[] bytes) throws IOException {
            if (++metadataReads > MAX_METADATA_READS
                    || (metadataReadBytes += bytes.length) > MAX_METADATA_READ_BYTES)
                throw new IOException("Os metadados de pastas da ISO ultrapassam o limite de segurança.");
            read(offset, bytes, 0, bytes.length);
        }

        long dataOffset(long sector) throws IOException {
            long offset = partitionBase + sector * SECTOR_BYTES;
            checkRange(offset, 0);
            return offset;
        }

        Directory findVolume() throws IOException {
            boolean damagedDescriptor = false;
            byte[] descriptor = new byte[SECTOR_BYTES];
            for (long base : PARTITION_BASES) {
                ZipImporter.checkCancel(cancel);
                long location = base + DESCRIPTOR_OFFSET;
                if (location > sourceLength || SECTOR_BYTES > sourceLength - location) continue;
                readMetadata(location, descriptor);
                if (!matches(descriptor, 0, MAGIC)) continue;
                if (!matches(descriptor, SECTOR_BYTES - MAGIC.length, MAGIC)) {
                    damagedDescriptor = true; continue;
                }
                partitionBase = base;
                long size = u32(descriptor, 24);
                if (size > MAX_DIRECTORY_BYTES)
                    throw new IOException("A tabela de pastas da ISO ultrapassa o limite de segurança.");
                long offset = dataOffset(u32(descriptor, 20));
                checkRange(offset, size);
                checkDirectory(offset, size);
                return new Directory(offset, size, "", 0, null);
            }
            if (damagedDescriptor)
                throw new IOException("A ISO contém um descritor XDVDFS incompleto ou corrompido.");
            throw new IOException("A ISO não contém uma partição Xbox 360 XDVDFS reconhecida. "
                    + "Use uma ISO de setores de 2048 bytes, XGD2, XGD3 ou apenas a partição do jogo.");
        }

        void checkDirectory(long offset, long length) throws IOException {
            if (length == 0) return;
            long end = offset + length;
            long descriptorStart = partitionBase + DESCRIPTOR_OFFSET;
            if (offset < descriptorStart + SECTOR_BYTES && descriptorStart < end)
                throw new IOException("A ISO contém uma tabela de pastas sobreposta ao descritor XDVDFS.");
        }

        Record readRecord(Directory table, int requestedOffset) throws IOException {
            int offset = requestedOffset;
            byte[] header = new byte[HEADER_BYTES];
            while (true) {
                ZipImporter.checkCancel(cancel);
                if (offset < 0 || offset > table.length || HEADER_BYTES > table.length - offset)
                    throw new IOException("A ISO contém um índice fora da tabela de pasta.");
                readMetadata(table.offset + offset, header);
                if (!uniform(header, (byte)0) && !uniform(header, (byte)0xFF)) break;
                if (requestedOffset == 0) return null; // Empty directory, including padded empty tables.
                // Some writers point a child at padding before the next directory sector.
                long nextSector = ((long)offset / SECTOR_BYTES + 1) * SECTOR_BYTES;
                if (nextSector >= table.length)
                    throw new IOException("A ISO contém um índice de pasta sem uma entrada válida.");
                offset = (int)nextSector;
            }
            int nameLength = header[13] & 255;
            if (nameLength == 0 || HEADER_BYTES + nameLength > table.length - offset
                    || offset % SECTOR_BYTES + HEADER_BYTES + nameLength > SECTOR_BYTES)
                throw new IOException("A ISO contém uma entrada de pasta truncada ou inválida.");
            byte[] nameBytes = new byte[nameLength];
            readMetadata(table.offset + offset + HEADER_BYTES, nameBytes);
            String name;
            try {
                name = NAME_CHARSET.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(nameBytes)).toString();
            } catch (CharacterCodingException e) {
                throw new IOException("A ISO contém um nome de arquivo com codificação inválida.", e);
            }
            validateName(name);
            return new Record(offset, header, name);
        }

        void enumerate() throws IOException {
            ArrayDeque<Directory> directories = new ArrayDeque<>();
            directories.add(findVolume());
            while (!directories.isEmpty()) {
                Directory table = directories.removeFirst();
                if (table.length == 0) continue;
                if (table.length < HEADER_BYTES)
                    throw new IOException("A ISO contém uma tabela de pasta incompleta.");
                ArrayDeque<Integer> nodes = new ArrayDeque<>();
                Set<Integer> visited = new HashSet<>();
                TreeMap<Integer, Integer> records = new TreeMap<>();
                nodes.push(0);
                while (!nodes.isEmpty()) {
                    ZipImporter.checkCancel(cancel);
                    Record record = readRecord(table, nodes.pop());
                    if (record == null) continue;
                    if (!visited.add(record.offset))
                        throw new IOException("A ISO contém um ciclo ou índices de pasta duplicados.");
                    int occupiedEnd = (int)Math.min(table.length, (long)record.offset + record.occupiedBytes);
                    Map.Entry<Integer, Integer> previous = records.floorEntry(record.offset);
                    Map.Entry<Integer, Integer> next = records.ceilingEntry(record.offset);
                    if ((previous != null && previous.getValue() > record.offset)
                            || (next != null && next.getKey() < occupiedEnd))
                        throw new IOException("A ISO contém entradas de pasta sobrepostas.");
                    records.put(record.offset, occupiedEnd);
                    if (++entries > limits.entries) throw new IOException("A ISO contém arquivos demais.");
                    String path = table.path.isEmpty() ? record.name : table.path + "/" + record.name;
                    if (path.length() > 4096 || (pathCharacters += path.length()) > MAX_PATH_CHARACTERS)
                        throw new IOException("Os caminhos da ISO ultrapassam o limite de segurança.");
                    if (!paths.add(path.toLowerCase(Locale.ROOT)))
                        throw new IOException("A ISO contém caminhos duplicados.");
                    long offset = dataOffset(record.sector);
                    checkRange(offset, record.length);
                    if (record.directory) {
                        if (table.depth + 1 > MAX_DIRECTORY_DEPTH)
                            throw new IOException("A ISO contém pastas aninhadas além do limite de segurança.");
                        if (record.length > MAX_DIRECTORY_BYTES)
                            throw new IOException("A tabela de pastas da ISO ultrapassa o limite de segurança.");
                        checkDirectory(offset, record.length);
                        // Sibling tables and file data may alias. Reusing an ancestor's
                        // start is an actual recursive directory graph, regardless of size.
                        if (record.length != 0) {
                            for (Directory ancestor = table; ancestor != null; ancestor = ancestor.parent) {
                                if (ancestor.offset == offset)
                                    throw new IOException("A ISO contém um ciclo entre pastas.");
                            }
                        }
                        directories.addLast(new Directory(offset, record.length, path,
                                table.depth + 1, table));
                    } else {
                        if (record.length > limits.fileBytes || record.length > limits.totalBytes - totalBytes)
                            throw new IOException("O conteúdo da ISO ultrapassa o limite de importação.");
                        totalBytes += record.length;
                        files++;
                        if (record.name.equalsIgnoreCase("default.xex")) executables++;
                    }
                    items.add(new Item(path, offset, record.length, record.directory));
                    long now = System.nanoTime();
                    if (now - lastReport > 200000000L) {
                        progress.update(0, 0, "Validando ISO: " + path); lastReport = now;
                    }
                    if (record.right != 0 && record.right != 0xFFFF) nodes.push(record.right * 4);
                    if (record.left != 0 && record.left != 0xFFFF) nodes.push(record.left * 4);
                }
            }
        }
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 255) | ((data[offset + 1] & 255) << 8);
    }

    private static long u32(byte[] data, int offset) {
        return (data[offset] & 255L) | ((data[offset + 1] & 255L) << 8)
                | ((data[offset + 2] & 255L) << 16) | ((data[offset + 3] & 255L) << 24);
    }

    private static boolean matches(byte[] data, int offset, byte[] expected) {
        for (int i = 0; i < expected.length; i++) if (data[offset + i] != expected[i]) return false;
        return true;
    }

    private static boolean uniform(byte[] data, byte expected) {
        for (byte value : data) if (value != expected) return false;
        return true;
    }

    private static void validateName(String name) throws IOException {
        if (name.isEmpty() || name.equals(".") || name.equals(".."))
            throw new IOException("A ISO contém um caminho de arquivo inválido.");
        if (name.equalsIgnoreCase(".mk-import.properties"))
            throw new IOException("A ISO contém um nome reservado pelo aplicativo.");
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (ch == '/' || ch == '\\' || ch == ':' || Character.isISOControl(ch))
                throw new IOException("A ISO contém um caminho de arquivo inválido.");
        }
    }

    /** Extracts and validates first; only a successful import replaces the installed game. */
    public static ZipImporter.Result install(SeekableSource source, File appRoot,
                                             ZipImporter.Limits limits, AtomicBoolean cancel,
                                             ZipImporter.Progress progress) throws IOException {
        ZipImporter.checkCancel(cancel);
        ZipImporter.recover(appRoot);
        File staging = new File(appRoot, "game.staging").getCanonicalFile();
        ZipImporter.ensureDirectory(staging);
        boolean committed = false;
        try {
            Reader reader = new Reader(source, limits, cancel, progress);
            reader.enumerate();
            if (reader.files == 0) throw new IOException("A ISO não contém arquivos do jogo.");
            if (reader.executables != 1)
                throw new IOException("A ISO deve conter exatamente um default.xex e os arquivos do jogo.");
            long available = staging.getUsableSpace() - FREE_SPACE_RESERVE;
            if (available <= 0 || reader.totalBytes > available)
                throw new IOException("Espaço livre insuficiente para extrair a ISO. "
                        + "Reserve espaço para os arquivos do jogo e mais 64 MB.");
            List<File> executables = new ArrayList<>();
            byte[] buffer = new byte[256 * 1024];
            long total = 0, lastReport = 0;
            int files = 0;
            for (Item item : reader.items) {
                ZipImporter.checkCancel(cancel);
                File target = ZipImporter.safeDestination(staging, item.path);
                if (item.directory) {
                    ZipImporter.ensureDirectory(target); continue;
                }
                ZipImporter.ensureDirectory(target.getParentFile());
                if (item.length > staging.getUsableSpace() - FREE_SPACE_RESERVE)
                    throw new IOException("Espaço livre insuficiente para o conteúdo extraído da ISO.");
                try (OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
                    long written = 0;
                    while (written < item.length) {
                        int count = (int)Math.min(buffer.length, item.length - written);
                        reader.read(item.offset + written, buffer, 0, count);
                        ZipImporter.checkCancel(cancel);
                        out.write(buffer, 0, count);
                        written += count; total += count;
                        long now = System.nanoTime();
                        if (now - lastReport > 200000000L) {
                            progress.update(files, total, item.path); lastReport = now;
                        }
                    }
                }
                files++;
                if (target.getName().equalsIgnoreCase("default.xex")) executables.add(target);
            }
            ZipImporter.Result result = ZipImporter.commitStaging(appRoot, staging, files, total,
                    executables, cancel, progress, "A ISO");
            committed = true;
            return result;
        } finally {
            if (!committed) ZipImporter.deleteTree(staging);
        }
    }
}
