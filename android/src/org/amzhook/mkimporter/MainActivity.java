package org.amzhook.mkimporter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final int PICK_GAME = 10, PICK_DRIVER = 11;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancel = new AtomicBoolean();
    private TextView status, summary, path, note;
    private Button importButton, cancelButton, detailsButton, removeButton, playButton, logButton;
    private ProgressBar progress;
    private ZipImporter.Result installed;
    private boolean busy, destroyed;
    private LauncherUi launcher;
    private RuntimeSettings settings;
    private File driversRoot;
    private java.util.List<DriverManager.Driver> drivers = new java.util.ArrayList<>();
    private boolean driverCatalogReady;
    private File storage;
    private String expectedHash;
    private boolean nativeAvailable;
    private String appVersion;
    private volatile ArchiveInput activeInput;
    private volatile CancellationSignal sourceCancellation;
    private final int gold = Color.rgb(238, 184, 73);
    private final int muted = Color.rgb(181, 184, 192);

    private int dp(int size) { return Math.round(size * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setPadding(0, dp(10), 0, dp(10)); view.setLineSpacing(dp(3), 1); return view;
    }
    private Button button(String label) {
        Button view = new Button(this); view.setText(label); view.setAllCaps(false); view.setTextSize(16);
        view.setMinHeight(dp(54)); return view;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        storage = new File(getFilesDir(), "mk-library");
        try { appVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (android.content.pm.PackageManager.NameNotFoundException error) { appVersion = "experimental"; }
        Properties runtime = new Properties();
        try (InputStream input = getAssets().open("runtime.properties")) {
            runtime.load(input);
            String hash = runtime.getProperty("xex_sha256", "");
            if (hash.matches("[0-9a-f]{64}")) expectedHash = hash;
        } catch (IOException ignored) { }
        nativeAvailable = expectedHash != null && new File(getApplicationInfo().nativeLibraryDir, "libmk_komplete.so").isFile();
        settings = new RuntimeSettings(this);
        driversRoot = new File(getFilesDir(), "graphics-drivers");
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        launcher = new LauncherUi(this, nativeAvailable, appVersion, new LauncherUi.Callbacks() {
            public void onPlay() { launchGame(); }
            public void onOptions() { launcher.showOptions(!launcher.isOptionsVisible()); }
            public void onImport() { pickArchive(); }
            public void onExit() { if (busy) onBackPressed(); else finish(); }
            public void onDetails() { showDetails(); }
            public void onLog() { showRuntimeLog(); }
            public void onRemove() { confirmRemoveGame(); }
            public void onChooseDriver() { chooseDriver(); }
            public void onDriverManage() { manageDrivers(); }
            public void onSetting(String key) { changeSetting(key); }
        });
        status = launcher.status; summary = launcher.summary; path = launcher.path; note = launcher.note;
        importButton = launcher.importButton; cancelButton = launcher.cancelButton;
        detailsButton = launcher.detailsButton; removeButton = launcher.removeButton;
        playButton = launcher.playButton; logButton = launcher.logButton; progress = launcher.progress;
        cancelButton.setOnClickListener(view -> { cancelImport(); cancelButton.setEnabled(false); status.setText("Cancelando…"); });
        launcher.addOption("fill_screen", "Preencher a tela", "Desligado: proporção do jogo com barras.", "Desligado");
        launcher.addOption("resolution", "Resolução interna", "1× original; 2× aumenta a carga da GPU. Aplica ao iniciar.", "1×");
        launcher.addOption("vsync", "Sincronização vertical", "Sincroniza a apresentação com a tela. Aplica ao iniciar.", "Ligado");
        launcher.addOption("trace_assets", "Registro de arquivos", "Ajuda a identificar a última tentativa antes de uma falha.", "Ligado");
        launcher.addOption("reset", "Restaurar opções", "Volta aos ajustes padrão e ao driver do aparelho.", "Restaurar");
        setContentView(launcher.root());
        refreshSettings();
        setBusy(true);
        worker.execute(() -> {
            try {
                ZipImporter.Result result = GameImporter.recover(storage, new File(getCacheDir(), "mk-iso-import"));
                java.util.List<DriverManager.Driver> catalog;
                try { catalog = DriverManager.listInstalled(driversRoot); }
                catch (IOException ignored) { catalog = new java.util.ArrayList<>(); }
                final java.util.List<DriverManager.Driver> recoveredDrivers = catalog;
                post(() -> { installed = result; drivers = recoveredDrivers; driverCatalogReady = true; setBusy(false); refresh(); refreshSettings(); });
            } catch (IOException error) { post(() -> { setBusy(false); showFailure(error); }); }
        });
    }

    private void launchGame() {
        if (busy || !nativeAvailable || installed == null || !installed.sha256.equals(expectedHash)) return;
        File gameRoot = new File(new File(storage, "game"), installed.xexPath).getParentFile();
        Intent game = new Intent().setClassName(getPackageName(), getPackageName() + ".GameActivity");
        game.putExtra("game_root", gameRoot.getAbsolutePath());
        game.putExtra("cache_root", new File(getCacheDir(), "mk-runtime").getAbsolutePath());
        game.putExtra("user_root", new File(getFilesDir(), "mk-user").getAbsolutePath());
        game.putExtra("driver_id", settings.driverId());
        game.putExtra("fill_screen", settings.fillScreen());
        game.putExtra("resolution", settings.resolution());
        game.putExtra("vsync", settings.vsync());
        game.putExtra("trace_assets", settings.traceAssets());
        startActivity(game);
    }
    private void refreshSettings() {
        launcher.setOptionValue("fill_screen", settings.fillScreen() ? "Ligado" : "Desligado");
        launcher.setOptionValue("resolution", settings.resolution() + "×");
        launcher.setOptionValue("vsync", settings.vsync() ? "Ligado" : "Desligado");
        launcher.setOptionValue("trace_assets", settings.traceAssets() ? "Ligado" : "Desligado");
        DriverManager.Driver selected = null;
        for (DriverManager.Driver driver : drivers) if (driver.id.equals(settings.driverId())) selected = driver;
        if (selected == null) {
            if (driverCatalogReady) settings.setDriver("");
            launcher.setDriverLabel("Padrão do aparelho");
            launcher.setDriverDescription("Driver Vulkan do sistema. Importe um ZIP Turnip para escolher outro.");
        } else {
            launcher.setDriverLabel(selected.name);
            launcher.setDriverDescription(selected.driverVersion + " · Aplica ao iniciar o jogo.");
        }
    }
    private void changeSetting(String key) {
        if (busy) return;
        switch (key) {
            case "fill_screen": settings.setBoolean(key, !settings.fillScreen()); break;
            case "vsync": settings.setBoolean(key, !settings.vsync()); break;
            case "trace_assets": settings.setBoolean(key, !settings.traceAssets()); break;
            case "resolution":
                dialogBuilder().setTitle("Resolução interna")
                        .setSingleChoiceItems(new String[]{"1× · original", "2× · maior detalhe"}, settings.resolution() - 1,
                                (dialog, which) -> { settings.setResolution(which + 1); refreshSettings(); dialog.dismiss(); })
                        .setNegativeButton("Voltar", null).show(); return;
            case "reset": settings.reset(); break;
            default: return;
        }
        refreshSettings();
    }
    private void chooseDriver() {
        if (busy) return;
        String[] choices = new String[drivers.size() + 1]; choices[0] = "Padrão do aparelho";
        int selected = 0;
        for (int i = 0; i < drivers.size(); ++i) {
            DriverManager.Driver driver = drivers.get(i);
            choices[i + 1] = driver.name + (driver.driverVersion.isEmpty() ? "" : " · " + driver.driverVersion);
            if (driver.id.equals(settings.driverId())) selected = i + 1;
        }
        dialogBuilder().setTitle("Driver gráfico")
                .setSingleChoiceItems(choices, selected, (dialog, which) -> {
                    if (which > 0 && drivers.get(which - 1).minApi > android.os.Build.VERSION.SDK_INT) {
                        Toast.makeText(this, "Este driver exige uma versão mais recente do Android.", Toast.LENGTH_LONG).show(); return;
                    }
                    settings.setDriver(which == 0 ? "" : drivers.get(which - 1).id); refreshSettings(); dialog.dismiss();
                }).setPositiveButton("Importar ZIP", (dialog, which) -> pickDriver())
                .setNeutralButton("Remover driver", (dialog, which) -> chooseDriverToRemove())
                .setNegativeButton("Voltar", null).show();
    }
    private void pickDriver() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivityForResult(intent, PICK_DRIVER);
    }
    private void manageDrivers() {
        if (busy) return;
        dialogBuilder().setTitle("Drivers personalizados")
                .setItems(new String[]{"Importar driver ZIP", "Remover driver instalado"}, (dialog, which) -> {
                    if (which == 0) pickDriver();
                    else if (drivers.isEmpty()) Toast.makeText(this, "Nenhum driver importado.", Toast.LENGTH_SHORT).show();
                    else chooseDriverToRemove();
                }).setNegativeButton("Voltar", null).show();
    }
    private void importDriver(Uri uri) {
        if (busy) return;
        cancel.set(false); setBusy(true); status.setText("Importando driver…");
        CancellationSignal signal = new CancellationSignal(); sourceCancellation = signal;
        cancelButton.setVisibility(View.VISIBLE); cancelButton.setEnabled(true);
        worker.execute(() -> {
            try (ArchiveInput input = openArchive(uri, signal)) {
                activeInput = input;
                DriverManager.Driver imported = DriverManager.install(input.stream, driversRoot,
                        DriverManager.Limits.defaults(), cancel,
                        (files, bytes, name) -> post(() -> { summary.setText(files + " arquivos · " + size(bytes)); path.setText(name); }));
                java.util.List<DriverManager.Driver> catalog = DriverManager.listInstalled(driversRoot);
                post(() -> {
                    drivers = catalog; driverCatalogReady = true;
                    if (imported.minApi <= android.os.Build.VERSION.SDK_INT) settings.setDriver(imported.id);
                    setBusy(false); refresh(); refreshSettings(); launcher.showOptions(true);
                    Toast.makeText(this, "Driver importado: " + imported.name, Toast.LENGTH_LONG).show();
                });
            } catch (IOException | SecurityException | android.os.OperationCanceledException error) {
                Exception failure = cancel.get() ? new DriverManager.Cancelled() : error;
                post(() -> { setBusy(false); refresh(); showFailure(failure); });
            } finally { activeInput = null; if (sourceCancellation == signal) sourceCancellation = null; }
        });
    }
    private void chooseDriverToRemove() {
        if (busy || drivers.isEmpty()) return;
        String[] choices = new String[drivers.size()];
        for (int i = 0; i < choices.length; ++i) choices[i] = drivers.get(i).name;
        dialogBuilder().setTitle("Remover driver importado").setItems(choices, (dialog, which) -> {
            DriverManager.Driver driver = drivers.get(which);
            dialogBuilder().setTitle("Remover " + driver.name + "?")
                    .setMessage("Os arquivos do jogo serão preservados.")
                    .setNegativeButton("Manter", null).setPositiveButton("Remover", (confirmation, button) -> {
                        setBusy(true); worker.execute(() -> {
                            try {
                                DriverManager.remove(driversRoot, driver.id);
                                java.util.List<DriverManager.Driver> catalog = DriverManager.listInstalled(driversRoot);
                                post(() -> { drivers = catalog; setBusy(false); refresh(); refreshSettings(); });
                            } catch (IOException error) { post(() -> { setBusy(false); showFailure(error); }); }
                        });
                    }).show();
        }).setNegativeButton("Voltar", null).show();
    }
    private void confirmRemoveGame() {
        if (busy || installed == null) return;
        dialogBuilder().setTitle("Remover a cópia importada?")
                .setMessage("Os arquivos dentro do aplicativo serão apagados. O arquivo original será preservado.")
                .setNegativeButton("Manter", null).setPositiveButton("Remover", (dialog, which) -> removeGame()).show();
    }
    private AlertDialog.Builder dialogBuilder() {
        return new AlertDialog.Builder(this).setOnKeyListener((interfaceDialog, key, event) -> {
            if (event.getAction() != android.view.KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) return false;
            if (key == android.view.KeyEvent.KEYCODE_BUTTON_B) { interfaceDialog.cancel(); return true; }
            if (key == android.view.KeyEvent.KEYCODE_BUTTON_A && interfaceDialog instanceof AlertDialog) {
                AlertDialog dialog = (AlertDialog) interfaceDialog;
                View focus = dialog.getCurrentFocus();
                if (focus instanceof ListView) {
                    ListView list = (ListView) focus; int selected = list.getSelectedItemPosition();
                    if (selected >= 0) return list.performItemClick(list.getSelectedView(), selected, list.getSelectedItemId());
                }
                return focus != null && focus.isEnabled() && focus.performClick();
            }
            return false;
        });
    }
    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_BUTTON_A) {
                View focus = getCurrentFocus(); if (focus != null && focus.isEnabled()) return focus.performClick();
            } else if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_BUTTON_B) { onBackPressed(); return true; }
        }
        return super.dispatchKeyEvent(event);
    }
    private void post(Runnable action) { ui.post(() -> { if (!destroyed) action.run(); }); }
    private void setBusy(boolean value) {
        busy = value; importButton.setEnabled(!value);
        launcher.setSettingsEnabled(!value);
        detailsButton.setEnabled(!value && installed != null); removeButton.setEnabled(!value && installed != null);
        playButton.setEnabled(!value && nativeAvailable && installed != null && installed.sha256.equals(expectedHash));
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
        cancelButton.setVisibility(View.GONE);
        if (value) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private static String size(long bytes) { return String.format(Locale.getDefault(), "%.1f GB", bytes / 1073741824.0); }
    private void refresh() {
        boolean ready = nativeAvailable && installed != null && installed.sha256.equals(expectedHash);
        launcher.setLibraryReady(ready);
        status.setText(installed == null ? "Nenhuma cópia importada" : ready ? "✓ Jogo importado" : "Arquivos importados");
        summary.setText(installed == null ? "Selecione um ZIP ou uma ISO para começar." : installed.files + " arquivos · " + size(installed.bytes));
        if (nativeAvailable && installed != null && !installed.sha256.equals(expectedHash))
            summary.append("\nEste executável não corresponde à versão usada nesta build. Importe a cópia com o XEX compatível.");
        path.setText("");
        detailsButton.setEnabled(!busy && installed != null); removeButton.setEnabled(!busy && installed != null);
        playButton.setEnabled(!busy && nativeAvailable && installed != null && installed.sha256.equals(expectedHash));
    }
    private void pickArchive() {
        if (installed != null) {
            dialogBuilder().setTitle("Substituir a cópia importada?")
                    .setMessage("A cópia atual será substituída somente se a nova importação terminar com sucesso.")
                    .setNegativeButton("Cancelar", null).setPositiveButton("Escolher arquivo", (dialog, which) -> openPicker()).show();
        } else openPicker();
    }
    private void openPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, PICK_GAME);
    }
    @Override protected void onActivityResult(int request, int result, Intent intent) {
        super.onActivityResult(request, result, intent);
        if (request == PICK_GAME && result == RESULT_OK && intent != null && intent.getData() != null)
            importArchive(intent.getData());
        else if (request == PICK_DRIVER && result == RESULT_OK && intent != null && intent.getData() != null)
            importDriver(intent.getData());
    }
    private static final class ArchiveInput implements Closeable {
        final InputStream stream;
        final FileChannel channel;
        ArchiveInput(InputStream stream, FileChannel channel) { this.stream = stream; this.channel = channel; }
        @Override public void close() throws IOException { stream.close(); }
    }
    private ArchiveInput openArchive(Uri uri, CancellationSignal signal) throws IOException {
        try {
            ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(uri, "r", signal);
            if (descriptor != null) {
                FileInputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
                return new ArchiveInput(stream, stream.getChannel());
            }
        } catch (FileNotFoundException | UnsupportedOperationException error) {
            // Some document providers only expose a sequential stream.
        }
        signal.throwIfCanceled();
        AssetFileDescriptor asset = getContentResolver().openAssetFileDescriptor(uri, "r", signal);
        if (asset == null) throw new IOException("Não foi possível abrir o arquivo selecionado.");
        try {
            // This stream may describe only a segment of its underlying file.
            // Keep its offset/length semantics and use the sequential adapter.
            return new ArchiveInput(asset.createInputStream(), null);
        } catch (IOException | RuntimeException error) {
            try { asset.close(); } catch (IOException closeError) { error.addSuppressed(closeError); }
            throw error;
        }
    }
    private void importArchive(Uri uri) {
        if (busy) return;
        cancel.set(false); setBusy(true);
        CancellationSignal signal = new CancellationSignal();
        sourceCancellation = signal;
        cancelButton.setVisibility(View.VISIBLE); cancelButton.setEnabled(true);
        status.setText("Importando…"); summary.setText("Mantenha o aplicativo aberto até concluir.");
        worker.execute(() -> {
            try (ArchiveInput input = openArchive(uri, signal)) {
                activeInput = input;
                if (cancel.get()) throw new ZipImporter.Cancelled();
                ZipImporter.Result result = GameImporter.install(input.stream, input.channel, storage,
                        new File(getCacheDir(), "mk-iso-import"), ZipImporter.Limits.defaults(), cancel,
                        (files, bytes, name) -> post(() -> {
                            if (!cancel.get()) { summary.setText(files + " arquivos · " + size(bytes)); path.setText(name); }
                        }));
                post(() -> { installed = result; setBusy(false); refresh(); });
            } catch (IOException | SecurityException | android.os.OperationCanceledException error) {
                Exception failure = cancel.get() ? new ZipImporter.Cancelled() : error;
                post(() -> { setBusy(false); refresh(); showFailure(failure); });
            } finally {
                activeInput = null;
                if (sourceCancellation == signal) sourceCancellation = null;
            }
        });
    }
    private void cancelImport() {
        cancel.set(true);
        CancellationSignal signal = sourceCancellation;
        if (signal != null) signal.cancel();
        ArchiveInput input = activeInput;
        if (input != null) {
            Thread closer = new Thread(() -> { try { input.close(); } catch (IOException ignored) { } }, "mk-import-cancel");
            closer.setDaemon(true); closer.start();
        }
    }
    private void showFailure(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) message = "Não foi possível importar o ZIP ou a ISO. Verifique o arquivo e o espaço livre.";
        dialogBuilder().setTitle((error instanceof ZipImporter.Cancelled || error instanceof DriverManager.Cancelled) ? "Importação cancelada" : "Importação não concluída")
                .setMessage(message).setPositiveButton("OK", null).show();
    }
    private void showDetails() {
        if (installed == null) return;
        String report = (nativeAvailable ? "MK Experimental " : "MK Importador ") + appVersion + "\nArquivos: " + installed.files + "\nTamanho: " + installed.bytes
                + " bytes\nXEX: " + installed.xexPath + "\nSHA-256: " + installed.sha256
                + "\n\nCabeçalho XEX2 verificado. Isso não confirma que o executável é do MK ou que é compatível.\nRuntime: "
                + (nativeAvailable ? "experimental; inicialização não validada em aparelho." : "não integrado nesta build.");
        dialogBuilder().setTitle("Detalhes da importação").setMessage(report)
                .setPositiveButton("Fechar", null).setNeutralButton("Copiar", (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("MK Importador", report));
                }).show();
    }
    private void showRuntimeLog() {
        File log = new File(getFilesDir(), "mk-user/logs/mk_komplete.log");
        String content;
        try (RandomAccessFile input = new RandomAccessFile(log, "r")) {
            int length = (int)Math.min(input.length(), 128 * 1024);
            input.seek(input.length() - length);
            byte[] bytes = new byte[length]; input.readFully(bytes);
            content = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException error) {
            content = "Ainda não há log salvo. Tente iniciar o jogo e volte a esta tela.";
        }
        final String report = content;
        TextView viewer = text(report, 12, Color.WHITE); viewer.setTextIsSelectable(true);
        viewer.setPadding(dp(16), dp(12), dp(16), dp(12));
        ScrollView scroll = new ScrollView(this); scroll.addView(viewer);
        dialogBuilder().setTitle("Log de inicialização").setView(scroll)
                .setPositiveButton("Fechar", null).setNeutralButton("Copiar", (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("Log MK", report));
                }).show();
    }
    private void removeGame() {
        setBusy(true);
        worker.execute(() -> {
            try {
                GameImporter.remove(storage);
                post(() -> { installed = null; setBusy(false); refresh(); });
            } catch (IOException error) { post(() -> { setBusy(false); showFailure(error); }); }
        });
    }
    @Override public void onBackPressed() {
        if (!busy && launcher != null && launcher.isOptionsVisible()) { launcher.showOptions(false); return; }
        if (busy) {
            dialogBuilder().setTitle("Cancelar e sair?")
                    .setMessage("Uma importação incompleta será descartada.")
                    .setNegativeButton("Continuar", null)
                    .setPositiveButton("Sair", (dialog, which) -> { cancelImport(); finish(); }).show();
        } else super.onBackPressed();
    }
    @Override protected void onDestroy() {
        destroyed = true; cancelImport(); worker.shutdownNow(); ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
