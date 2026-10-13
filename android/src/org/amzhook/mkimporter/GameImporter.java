package org.amzhook.mkimporter;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;

/** Routes ZIP/ISO imports; the caller retains ownership of the selected input. */
public final class GameImporter {
    private static final long MAX_CACHED_IMAGE_BYTES = 24L << 30;
    private static final long SPACE_RESERVE = 64L << 20;
    private static final Object OPERATIONS_LOCK = new Object();

    private GameImporter() { }

    private static void checkCancel(AtomicBoolean cancel) throws ZipImporter.Cancelled {
        if (cancel.get() || Thread.currentThread().isInterrupted()) throw new ZipImporter.Cancelled();
    }

    private static final class ChannelSource implements IsoImporter.SeekableSource {
        private final FileChannel channel;
        private final long length;
        private final AtomicBoolean cancel;

        ChannelSource(FileChannel channel, long length, AtomicBoolean cancel) {
            this.channel = channel; this.length = length; this.cancel = cancel;
        }
        @Override public long length() { return length; }
        @Override public void readFully(long offset, byte[] data, int start, int count) throws IOException {
            if (offset < 0 || count < 0 || start < 0 || start > data.length - count || offset > length - count)
                throw new EOFException("A ISO está incompleta ou contém um endereço inválido.");
            ByteBuffer buffer = ByteBuffer.wrap(data, start, count);
            long position = offset;
            while (buffer.hasRemaining()) {
                checkCancel(cancel);
                int read = channel.read(buffer, position);
                if (read < 0) throw new EOFException("A ISO terminou antes dos dados esperados.");
                if (read == 0) throw new IOException("Não foi possível ler os dados da ISO.");
                position += read;
            }
        }
    }

    private static IsoImporter.SeekableSource seekable(FileChannel channel, AtomicBoolean cancel) {
        if (channel == null) return null;
        try {
            long length = channel.size();
            if (length <= 0) return null;
            // A provider may return a pipe instead of a file. Positional reads
            // leave the buffered stream's position intact when they succeed.
            if (channel.read(ByteBuffer.allocate(1), 0) != 1) return null;
            return new ChannelSource(channel, length, cancel);
        } catch (IOException | UnsupportedOperationException error) {
            return null;
        }
    }

    public static ZipImporter.Result install(InputStream source, FileChannel channel,
            File appRoot, File cacheRoot, ZipImporter.Limits limits, AtomicBoolean cancel,
            ZipImporter.Progress progress) throws IOException {
        synchronized (OPERATIONS_LOCK) {
            return installLocked(source, channel, appRoot, cacheRoot, limits, cancel, progress);
        }
    }

    public static ZipImporter.Result recover(File appRoot, File cacheRoot) throws IOException {
        synchronized (OPERATIONS_LOCK) {
            try { ZipImporter.deleteTree(cacheRoot); } catch (IOException ignored) { }
            ZipImporter.recover(appRoot);
            return ZipImporter.readInstalled(appRoot);
        }
    }

    public static void remove(File appRoot) throws IOException {
        synchronized (OPERATIONS_LOCK) {
            ZipImporter.deleteTree(new File(appRoot, "game"));
            ZipImporter.deleteTree(new File(appRoot, "game.backup"));
        }
    }

    private static ZipImporter.Result installLocked(InputStream source, FileChannel channel,
            File appRoot, File cacheRoot, ZipImporter.Limits limits, AtomicBoolean cancel,
            ZipImporter.Progress progress) throws IOException {
        checkCancel(cancel);
        // ZipImporter closes its stream. Keep the selected document open until
        // its Android owner closes the descriptor, for both archive branches.
        InputStream borrowed = new FilterInputStream(source) {
            @Override public void close() { }
        };
        BufferedInputStream input = new BufferedInputStream(borrowed);
        input.mark(4);
        int a = input.read(), b = input.read(), c = input.read(), d = input.read();
        input.reset();
        if (a == 'P' && b == 'K' && ((c == 3 && d == 4) || (c == 5 && d == 6) || (c == 7 && d == 8)))
            return ZipImporter.install(input, appRoot, limits, cancel, progress);

        IsoImporter.SeekableSource direct = seekable(channel, cancel);
        if (direct != null) return IsoImporter.install(direct, appRoot, limits, cancel, progress);

        checkCancel(cancel);
        if (Files.isSymbolicLink(cacheRoot.toPath()))
            throw new IOException("A pasta temporária de importação é inválida.");
        if (!cacheRoot.isDirectory() && !cacheRoot.mkdirs())
            throw new IOException("Não foi possível preparar a importação da ISO.");
        File cached = File.createTempFile("source-", ".iso.tmp", cacheRoot);
        try {
            long available = cacheRoot.getUsableSpace() - SPACE_RESERVE;
            if (available <= 0) throw new IOException("Espaço livre insuficiente para preparar a ISO.");
            progress.update(0, 0, "Preparando ISO para importação…");
            long written = 0, lastReport = 0;
            byte[] buffer = new byte[256 * 1024];
            try (OutputStream output = new BufferedOutputStream(new FileOutputStream(cached))) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    checkCancel(cancel);
                    if (read == 0) {
                        int one = input.read();
                        if (one == -1) break;
                        buffer[0] = (byte) one; read = 1;
                    }
                    written += read;
                    if (written > MAX_CACHED_IMAGE_BYTES)
                        throw new IOException("A ISO ultrapassa o limite de 24 GB para preparação temporária.");
                    if (written > available)
                        throw new IOException("Espaço livre insuficiente para preparar a ISO. Salve o arquivo localmente e tente novamente.");
                    output.write(buffer, 0, read);
                    long now = System.nanoTime();
                    if (now - lastReport > 200000000L) {
                        progress.update(0, written, "Preparando ISO para importação…"); lastReport = now;
                    }
                }
            }
            checkCancel(cancel);
            try (RandomAccessFile file = new RandomAccessFile(cached, "r")) {
                return IsoImporter.install(new ChannelSource(file.getChannel(), file.length(), cancel),
                        appRoot, limits, cancel, progress);
            }
        } finally {
            // Cleanup is retried at startup. A cache deletion failure must not
            // turn an already committed installation into a reported failure.
            try { Files.deleteIfExists(cached.toPath()); } catch (IOException ignored) { }
        }
    }
}
