package org.amzhook.mkimporter;

import android.os.Bundle;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;

import org.libsdl.app.SDLActivity;

/** Hosts the experimental native runtime; SDL supplies its Activity lifecycle. */
public final class GameActivity extends SDLActivity {
    private String[] launchArguments;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            launchArguments = prepareArguments();
        } catch (IOException | IllegalArgumentException failure) {
            Toast.makeText(this, "Não foi possível iniciar: " + failure.getMessage(),
                    Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected String[] getLibraries() {
        // SDL3 and its JNI entry points live in the runtime shared library.
        return new String[] { "rexruntime", "mk_komplete" };
    }

    @Override
    protected String getMainSharedObject() {
        return new File(getApplicationInfo().nativeLibraryDir,
                "libmk_komplete.so").getAbsolutePath();
    }

    @Override
    protected String[] getArguments() {
        if (launchArguments == null) {
            throw new IllegalStateException("Importação do jogo não foi validada");
        }
        return launchArguments.clone();
    }

    private String[] prepareArguments() throws IOException {
        String gameExtra = getIntent().getStringExtra("game_root");
        if (gameExtra == null || gameExtra.isEmpty()) {
            throw new IllegalArgumentException("importe o ZIP do jogo primeiro");
        }
        File gameRoot = privatePath(new File(gameExtra));
        if (!gameRoot.isDirectory() || !new File(gameRoot, "default.xex").isFile()) {
            throw new IllegalArgumentException("default.xex não encontrado na importação");
        }

        File userRoot = privatePath(extraPath("user_root",
                new File(getFilesDir(), "runtime/user")));
        File cacheRoot = privatePath(extraPath("cache_root",
                new File(getCacheDir(), "mk-runtime")));
        File logs = privatePath(new File(userRoot, "logs"));
        ensureDirectory(userRoot);
        ensureDirectory(cacheRoot);
        ensureDirectory(logs);

        // Each value is a single argument, preserving spaces in imported paths.
        // No user-supplied flags or arbitrary shared-library paths are accepted.
        return new String[] {
            "--game_data_root=" + gameRoot.getPath(),
            "--user_data_root=" + userRoot.getPath(),
            "--cache_root=" + cacheRoot.getPath(),
            "--log_file=" + new File(logs, "mk_komplete.log").getPath(),
            "--input_backend=sdl"
        };
    }

    private File extraPath(String name, File fallback) {
        String value = getIntent().getStringExtra(name);
        return value == null || value.isEmpty() ? fallback : new File(value);
    }

    private File privatePath(File path) throws IOException {
        File canonical = path.getCanonicalFile();
        File[] allowedRoots = { getFilesDir().getCanonicalFile(),
                getCacheDir().getCanonicalFile() };
        for (File root : allowedRoots) {
            if (canonical.equals(root)
                    || canonical.getPath().startsWith(root.getPath() + File.separator)) {
                return canonical;
            }
        }
        throw new IllegalArgumentException("caminho fora do armazenamento privado do aplicativo");
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("não foi possível criar " + directory.getName());
        }
    }
}
