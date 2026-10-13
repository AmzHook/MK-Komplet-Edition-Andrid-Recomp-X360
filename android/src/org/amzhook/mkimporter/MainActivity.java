package org.amzhook.mkimporter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import java.io.*;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final int PICK_ZIP = 10;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancel = new AtomicBoolean();
    private TextView status, summary, path, note;
    private Button importButton, cancelButton, detailsButton, removeButton, playButton, logButton;
    private ProgressBar progress;
    private ZipImporter.Result installed;
    private boolean busy, destroyed;
    private File storage;
    private String expectedHash;
    private boolean nativeAvailable;
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
        Properties runtime = new Properties();
        try (InputStream input = getAssets().open("runtime.properties")) {
            runtime.load(input);
            String hash = runtime.getProperty("xex_sha256", "");
            if (hash.matches("[0-9a-f]{64}")) expectedHash = hash;
        } catch (IOException ignored) { }
        nativeAvailable = expectedHash != null && new File(getApplicationInfo().nativeLibraryDir, "libmk_komplete.so").isFile();
        getWindow().setStatusBarColor(Color.rgb(15,17,23));
        getWindow().setNavigationBarColor(Color.rgb(15,17,23));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(15,17,23));
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(26), dp(24), dp(24));
        scroll.addView(content);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        content.addView(text(nativeAvailable ? "MK  /  EXPERIMENTAL" : "MK  /  IMPORTADOR", 13, gold));
        content.addView(text("Sua cópia.\nNo seu aparelho.", 32, Color.WHITE));
        content.addView(text("Importe um ZIP com o default.xex e as pastas extraídas da sua cópia de Mortal Kombat Komplete Edition para Xbox 360.", 16, muted));
        TextView limitation = text(nativeAvailable
                ? "Versão experimental. Importe sua cópia para testar a inicialização; funcionamento e desempenho ainda precisam ser verificados."
                : "Esta versão importa os arquivos. A execução de Mortal Kombat ainda não está disponível.", 16, gold);
        limitation.setPadding(dp(16), dp(16), dp(16), dp(16));
        limitation.setBackgroundColor(Color.rgb(40,34,24)); content.addView(limitation);
        status = text("Nenhuma cópia importada", 21, Color.WHITE); content.addView(status);
        summary = text("", 15, muted); content.addView(summary);
        importButton = button("Importar ZIP"); importButton.setTextColor(Color.BLACK);
        importButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(gold)); content.addView(importButton);
        progress = new ProgressBar(this); progress.setVisibility(View.GONE); content.addView(progress);
        path = text("", 13, muted); path.setMaxLines(3); content.addView(path);
        cancelButton = button("Cancelar importação"); cancelButton.setVisibility(View.GONE); content.addView(cancelButton);
        detailsButton = button("Detalhes da importação"); content.addView(detailsButton);
        playButton = button("Testar inicialização do jogo");
        playButton.setVisibility(nativeAvailable ? View.VISIBLE : View.GONE); content.addView(playButton);
        logButton = button("Ver log de inicialização");
        logButton.setVisibility(nativeAvailable ? View.VISIBLE : View.GONE); content.addView(logButton);
        removeButton = button("Remover arquivos importados"); content.addView(removeButton);
        note = text("Os arquivos ficam no armazenamento privado deste aplicativo. Não há envio pela rede. Desinstalar o APK remove a cópia importada.\n\nReserve espaço para o conteúdo extraído; o ZIP original permanece no local escolhido.", 13, muted);
        content.addView(note);
        TextView footer = text(nativeAvailable ? "VERSÃO 0.1.1  ·  INICIALIZAÇÃO EXPERIMENTAL" : "VERSÃO 0.1.0  ·  IMPORTAÇÃO LOCAL", 12, muted);
        footer.setGravity(Gravity.CENTER); content.addView(footer);
        setContentView(scroll);
        importButton.setOnClickListener(view -> pickZip());
        cancelButton.setOnClickListener(view -> { cancel.set(true); cancelButton.setEnabled(false); status.setText("Cancelando…"); });
        detailsButton.setOnClickListener(view -> showDetails());
        playButton.setOnClickListener(view -> {
            if (!nativeAvailable || installed == null || !installed.sha256.equals(expectedHash)) return;
            File gameRoot = new File(new File(storage, "game"), installed.xexPath).getParentFile();
            Intent game = new Intent().setClassName(getPackageName(), getPackageName() + ".GameActivity");
            game.putExtra("game_root", gameRoot.getAbsolutePath());
            game.putExtra("cache_root", new File(getCacheDir(), "mk-runtime").getAbsolutePath());
            game.putExtra("user_root", new File(getFilesDir(), "mk-user").getAbsolutePath());
            startActivity(game);
        });
        logButton.setOnClickListener(view -> showRuntimeLog());
        removeButton.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle("Remover a cópia importada?")
                .setMessage("Os arquivos dentro do aplicativo serão apagados. O ZIP original será preservado.")
                .setNegativeButton("Manter", null).setPositiveButton("Remover", (dialog, which) -> removeGame()).show());
        setBusy(true);
        worker.execute(() -> {
            try {
                ZipImporter.recover(storage);
                ZipImporter.Result result = ZipImporter.readInstalled(storage);
                post(() -> { installed = result; setBusy(false); refresh(); });
            } catch (IOException error) { post(() -> { setBusy(false); showFailure(error); }); }
        });
    }

    private void post(Runnable action) { ui.post(() -> { if (!destroyed) action.run(); }); }
    private void setBusy(boolean value) {
        busy = value; importButton.setEnabled(!value);
        detailsButton.setEnabled(!value && installed != null); removeButton.setEnabled(!value && installed != null);
        playButton.setEnabled(!value && nativeAvailable && installed != null && installed.sha256.equals(expectedHash));
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
        cancelButton.setVisibility(View.GONE);
        if (value) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private static String size(long bytes) { return String.format(Locale.getDefault(), "%.1f GB", bytes / 1073741824.0); }
    private void refresh() {
        status.setText(installed == null ? "Nenhuma cópia importada" : "Arquivos importados");
        summary.setText(installed == null ? "Selecione o ZIP para começar." : installed.files + " arquivos · " + size(installed.bytes));
        if (nativeAvailable && installed != null && !installed.sha256.equals(expectedHash))
            summary.append("\nEste executável não corresponde à versão usada nesta build. Importe a cópia com o XEX compatível.");
        path.setText("");
        detailsButton.setEnabled(!busy && installed != null); removeButton.setEnabled(!busy && installed != null);
        playButton.setEnabled(!busy && nativeAvailable && installed != null && installed.sha256.equals(expectedHash));
    }
    private void pickZip() {
        if (installed != null) {
            new AlertDialog.Builder(this).setTitle("Substituir a cópia importada?")
                    .setMessage("A cópia atual será substituída somente se a nova importação terminar com sucesso.")
                    .setNegativeButton("Cancelar", null).setPositiveButton("Escolher ZIP", (dialog, which) -> openPicker()).show();
        } else openPicker();
    }
    private void openPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, PICK_ZIP);
    }
    @Override protected void onActivityResult(int request, int result, Intent intent) {
        super.onActivityResult(request, result, intent);
        if (request == PICK_ZIP && result == RESULT_OK && intent != null && intent.getData() != null)
            importZip(intent.getData());
    }
    private void importZip(Uri uri) {
        if (busy) return;
        cancel.set(false); setBusy(true);
        cancelButton.setVisibility(View.VISIBLE); cancelButton.setEnabled(true);
        status.setText("Importando…"); summary.setText("Mantenha o aplicativo aberto até concluir.");
        worker.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("Não foi possível abrir o arquivo selecionado.");
                ZipImporter.Result result = ZipImporter.install(input, storage, ZipImporter.Limits.defaults(), cancel,
                        (files, bytes, name) -> post(() -> {
                            if (!cancel.get()) { summary.setText(files + " arquivos · " + size(bytes)); path.setText(name); }
                        }));
                post(() -> { installed = result; setBusy(false); refresh(); });
            } catch (IOException | SecurityException error) {
                post(() -> { setBusy(false); refresh(); showFailure(error); });
            }
        });
    }
    private void showFailure(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) message = "Não foi possível importar esse ZIP. Verifique o arquivo e o espaço livre.";
        new AlertDialog.Builder(this).setTitle(error instanceof ZipImporter.Cancelled ? "Importação cancelada" : "Importação não concluída")
                .setMessage(message).setPositiveButton("OK", null).show();
    }
    private void showDetails() {
        if (installed == null) return;
        String report = (nativeAvailable ? "MK Experimental 0.1.1" : "MK Importador 0.1.0") + "\nArquivos: " + installed.files + "\nTamanho: " + installed.bytes
                + " bytes\nXEX: " + installed.xexPath + "\nSHA-256: " + installed.sha256
                + "\n\nCabeçalho XEX2 verificado. Isso não confirma que o executável é do MK ou que é compatível.\nRuntime: "
                + (nativeAvailable ? "experimental; inicialização não validada em aparelho." : "não integrado nesta build.");
        new AlertDialog.Builder(this).setTitle("Detalhes da importação").setMessage(report)
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
        new AlertDialog.Builder(this).setTitle("Log de inicialização").setView(scroll)
                .setPositiveButton("Fechar", null).setNeutralButton("Copiar", (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("Log MK", report));
                }).show();
    }
    private void removeGame() {
        setBusy(true);
        worker.execute(() -> {
            try {
                ZipImporter.deleteTree(new File(storage, "game"));
                ZipImporter.deleteTree(new File(storage, "game.backup"));
                post(() -> { installed = null; setBusy(false); refresh(); });
            } catch (IOException error) { post(() -> { setBusy(false); showFailure(error); }); }
        });
    }
    @Override public void onBackPressed() {
        if (busy) {
            new AlertDialog.Builder(this).setTitle("Cancelar e sair?")
                    .setMessage("Uma importação incompleta será descartada.")
                    .setNegativeButton("Continuar", null)
                    .setPositiveButton("Sair", (dialog, which) -> { cancel.set(true); finish(); }).show();
        } else super.onBackPressed();
    }
    @Override protected void onDestroy() {
        destroyed = true; cancel.set(true); worker.shutdownNow(); ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
