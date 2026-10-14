package org.amzhook.mkimporter;

import android.os.Bundle;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

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
        int resolution = getIntent().getIntExtra("resolution", 1) == 2 ? 2 : 1;
        ArrayList<String> arguments = new ArrayList<>(Arrays.asList(
            "--game_data_root=" + gameRoot.getPath(),
            "--user_data_root=" + userRoot.getPath(),
            "--cache_root=" + cacheRoot.getPath(),
            "--log_file=" + new File(logs, "mk_komplete.log").getPath(),
            "--input_backend=sdl",
            "--present_letterbox=" + !getIntent().getBooleanExtra("fill_screen", false),
            "--resolution_scale=" + resolution,
            // Explicit per-axis values also override older runtime TOML settings.
            "--draw_resolution_scale_x=" + resolution,
            "--draw_resolution_scale_y=" + resolution,
            "--vulkan_allow_present_mode_immediate=" + !getIntent().getBooleanExtra("vsync", true),
            "--vulkan_allow_present_mode_mailbox=" + !getIntent().getBooleanExtra("vsync", true),
            "--vulkan_allow_present_mode_fifo_relaxed=" + !getIntent().getBooleanExtra("vsync", true),
            "--mk_trace_assets=" + getIntent().getBooleanExtra("trace_assets", true)
        ));
        // Override any old runtime TOML driver choice even when System is
        // selected or a selected package fails revalidation.
        int driverArgument = arguments.size();
        arguments.add("--adreno_driver_path=");
        String driverId = getIntent().getStringExtra("driver_id");
        if (driverId != null && !driverId.isEmpty()) {
            try {
                DriverManager.Driver driver = DriverManager.findInstalled(new File(getFilesDir(), "graphics-drivers"), driverId);
                if (driver == null || driver.minApi > android.os.Build.VERSION.SDK_INT) throw new IOException("driver indisponível ou incompatível com esta versão do Android");
                File temporary = privatePath(new File(getCacheDir(), "adreno-loader")); ensureDirectory(temporary);
                arguments.set(driverArgument, "--adreno_driver_path=" + privatePath(driver.directory).getPath() + "/");
                arguments.add("--adreno_driver_name=" + driver.libraryName);
                arguments.add("--adreno_hook_path=" + getApplicationInfo().nativeLibraryDir);
                arguments.add("--adreno_tmp_path=" + temporary.getPath());
            } catch (IOException | IllegalArgumentException error) {
                Toast.makeText(this, "Driver padrão: " + error.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
        return arguments.toArray(new String[0]);
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
