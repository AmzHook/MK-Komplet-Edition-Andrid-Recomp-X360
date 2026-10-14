package org.amzhook.mkimporter;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.LinkedHashMap;
import java.util.Map;

/** Local launcher presentation. Importing and runtime configuration remain owned by the activity. */
public final class LauncherUi {
    public interface Callbacks {
        void onPlay();
        void onOptions();
        void onImport();
        void onExit();
        void onDetails();
        void onLog();
        void onRemove();
        void onChooseDriver();
        void onDriverManage();
        void onSetting(String key);
    }

    private static final int WHITE = Color.rgb(243, 240, 236);
    private static final int MUTED = Color.rgb(174, 171, 175);
    private static final int RED = Color.rgb(200, 20, 33);
    private static final int BLUE = Color.rgb(99, 139, 202);
    private final Activity activity;
    private final Callbacks actions;
    private final ResponsiveRoot root;
    private final LinearLayout body;
    private final ScrollView menuScroll;
    private final LinearLayout menu;
    private final LinearLayout optionsPane;
    private final ScrollView optionsScroll;
    private final LinearLayout optionsRows;
    private final Map<String, Button> values = new LinkedHashMap<>();
    private final Button optionsButton;
    private final Button driverButton;
    private final Button driverManageButton;
    private final TextView driverCaption;
    private int optionInsertIndex;
    private boolean optionsVisible;
    private boolean landscape;

    // These views deliberately match the existing importer's progress/lifecycle contract.
    public final TextView status;
    public final TextView summary;
    public final TextView path;
    public final TextView note;
    public final Button importButton;
    public final Button cancelButton;
    public final Button detailsButton;
    public final Button removeButton;
    public final Button playButton;
    public final Button logButton;
    public final ProgressBar progress;

    public LauncherUi(Activity activity, boolean nativeAvailable, String version, Callbacks actions) {
        this.activity = activity;
        this.actions = actions;
        root = new ResponsiveRoot(activity);
        root.setBackground(new Backdrop());
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            updateArrangement(view.getWidth(), view.getHeight());
            return insets;
        });
        body = new LinearLayout(activity);
        body.setPadding(dp(20), dp(16), dp(20), dp(16));
        body.setGravity(Gravity.CENTER);
        root.addView(body, new FrameLayout.LayoutParams(-1, -1));

        menuScroll = new ScrollView(activity);
        menuScroll.setFillViewport(true);
        menuScroll.setClipToPadding(false);
        menuScroll.setVerticalScrollBarEnabled(false);
        menu = new LinearLayout(activity);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setGravity(Gravity.CENTER_VERTICAL);
        menu.setPadding(dp(4), dp(10), dp(4), dp(10));
        menuScroll.addView(menu, new ScrollView.LayoutParams(-1, -2));
        body.addView(menuScroll);

        TextView eyebrow = label("MK  /  ANDROID", 11, MUTED);
        eyebrow.setLetterSpacing(0.24f);
        menu.addView(eyebrow);
        LinearLayout brand = new LinearLayout(activity);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.setPadding(0, dp(12), 0, dp(12));
        SigilView sigil = new SigilView(activity);
        sigil.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        brand.addView(sigil, new LinearLayout.LayoutParams(dp(70), dp(70)));
        LinearLayout titleBlock = new LinearLayout(activity);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.setPadding(dp(12), 0, 0, 0);
        TextView title = label("MORTAL KOMBAT", 30, WHITE);
        title.setTypeface(condensed());
        title.setLetterSpacing(0.02f);
        title.setMaxLines(2);
        title.setShadowLayer(dp(3), 0, dp(2), Color.BLACK);
        titleBlock.addView(title);
        TextView edition = label("KOMPLETE EDITION", 11, MUTED);
        edition.setLetterSpacing(0.18f);
        titleBlock.addView(edition);
        brand.addView(titleBlock, new LinearLayout.LayoutParams(0, -2, 1));
        menu.addView(brand);
        TextView backend = label(nativeAvailable ? "Vulkan nativo  ·  Android ARM64" : "Biblioteca local  ·  Xbox 360", 12, Color.rgb(149, 171, 210));
        backend.setLetterSpacing(0.05f);
        LinearLayout.LayoutParams backendLayout = new LinearLayout.LayoutParams(-1, -2);
        backendLayout.bottomMargin = dp(14);
        menu.addView(backend, backendLayout);

        LinearLayout mainActions = new LinearLayout(activity);
        mainActions.setOrientation(LinearLayout.VERTICAL);
        mainActions.setPadding(dp(10), dp(10), dp(10), dp(10));
        mainActions.setBackground(new PanelDrawable(false));
        playButton = mainButton("JOGAR", actions::onPlay);
        playButton.setVisibility(nativeAvailable ? View.VISIBLE : View.GONE);
        mainActions.addView(playButton, mainButtonLayout());
        optionsButton = mainButton("OPÇÕES", actions::onOptions);
        mainActions.addView(optionsButton, mainButtonLayout());
        importButton = mainButton("ARQUIVOS DO JOGO", actions::onImport);
        importButton.setContentDescription("Arquivos do jogo: importar ZIP ou ISO");
        mainActions.addView(importButton, mainButtonLayout());
        Button exit = mainButton("SAIR", actions::onExit);
        mainActions.addView(exit, mainButtonLayout());
        menu.addView(mainActions);

        LinearLayout library = new LinearLayout(activity);
        library.setOrientation(LinearLayout.VERTICAL);
        library.setPadding(dp(16), dp(13), dp(16), dp(13));
        library.setBackground(new PanelDrawable(true));
        LinearLayout.LayoutParams libraryLayout = new LinearLayout.LayoutParams(-1, -2);
        libraryLayout.topMargin = dp(16);
        status = label("Nenhuma cópia importada", 18, WHITE);
        status.setTypeface(condensed());
        library.addView(status);
        summary = label("Selecione um ZIP ou uma ISO para começar.", 12, MUTED);
        summary.setPadding(0, dp(5), 0, 0);
        summary.setLineSpacing(dp(2), 1);
        library.addView(summary);
        path = label("", 10, Color.rgb(145, 167, 204));
        path.setPadding(0, dp(5), 0, 0);
        path.setMaxLines(2);
        path.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        library.addView(path);
        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(ColorStateList.valueOf(RED));
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressLayout = new LinearLayout.LayoutParams(-1, dp(4));
        progressLayout.topMargin = dp(9);
        library.addView(progress, progressLayout);
        cancelButton = valueButton("Cancelar importação");
        cancelButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams cancelLayout = new LinearLayout.LayoutParams(-1, -2);
        cancelLayout.topMargin = dp(9);
        library.addView(cancelButton, cancelLayout);
        menu.addView(library, libraryLayout);

        note = label(nativeAvailable
                ? "Versão experimental. Inicialização e desempenho ainda precisam ser verificados no aparelho."
                : "Importe os arquivos da sua cópia de Xbox 360. A execução não está disponível nesta build.", 11, MUTED);
        note.setLineSpacing(dp(2), 1);
        note.setPadding(dp(2), dp(13), dp(2), dp(7));
        menu.addView(note);
        TextView footer = label("VERSÃO " + version + (nativeAvailable ? "  ·  EXPERIMENTAL" : "  ·  IMPORTAÇÃO LOCAL"), 9, Color.rgb(124, 122, 129));
        footer.setLetterSpacing(0.07f);
        menu.addView(footer);

        optionsPane = new LinearLayout(activity);
        optionsPane.setOrientation(LinearLayout.VERTICAL);
        optionsPane.setPadding(dp(16), dp(14), dp(16), dp(10));
        optionsPane.setBackground(new PanelDrawable(false));
        optionsPane.setVisibility(View.GONE);
        body.addView(optionsPane);
        LinearLayout heading = new LinearLayout(activity);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView optionsHeading = label("OPÇÕES", 28, WHITE);
        optionsHeading.setTypeface(condensed());
        heading.addView(optionsHeading, new LinearLayout.LayoutParams(0, -2, 1));
        TextView hint = label("A  Escolher   ·   B  Voltar", 10, MUTED);
        heading.addView(hint);
        optionsPane.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        View headingRule = new View(activity);
        headingRule.setBackgroundColor(Color.rgb(107, 27, 36));
        LinearLayout.LayoutParams ruleLayout = new LinearLayout.LayoutParams(-1, dp(1));
        ruleLayout.topMargin = dp(10);
        ruleLayout.bottomMargin = dp(8);
        optionsPane.addView(headingRule, ruleLayout);
        optionsScroll = new ScrollView(activity);
        optionsScroll.setFillViewport(false);
        optionsScroll.setVerticalScrollBarEnabled(true);
        optionsRows = new LinearLayout(activity);
        optionsRows.setOrientation(LinearLayout.VERTICAL);
        optionsScroll.addView(optionsRows, new ScrollView.LayoutParams(-1, -2));
        optionsPane.addView(optionsScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        driverButton = valueButton("Sistema");
        driverButton.setOnClickListener(view -> actions.onChooseDriver());
        OptionRow driverRow = new OptionRow("Driver gráfico", "Vulkan · escolha antes de iniciar o jogo", driverButton);
        driverCaption = driverRow.caption;
        optionsRows.addView(driverRow);
        values.put("graphics_driver", driverButton);
        driverManageButton = valueButton("Gerenciar");
        driverManageButton.setOnClickListener(view -> actions.onDriverManage());
        optionsRows.addView(new OptionRow("Drivers personalizados", "Importe um pacote compatível com sua GPU.", driverManageButton));
        optionInsertIndex = 2;

        TextView utilities = label("BIBLIOTECA E DIAGNÓSTICO", 10, Color.rgb(153, 136, 141));
        utilities.setLetterSpacing(0.12f);
        utilities.setPadding(dp(8), dp(20), dp(8), dp(7));
        optionsRows.addView(utilities);
        detailsButton = valueButton("Ver detalhes");
        detailsButton.setOnClickListener(view -> actions.onDetails());
        optionsRows.addView(new OptionRow("Arquivos importados", "Veja tamanho, executável e identificação da cópia.", detailsButton));
        logButton = valueButton("Abrir log");
        logButton.setOnClickListener(view -> actions.onLog());
        OptionRow logRow = new OptionRow("Log de inicialização", "Consulte ou copie o registro do último teste.", logButton);
        logRow.setVisibility(nativeAvailable ? View.VISIBLE : View.GONE);
        optionsRows.addView(logRow);
        removeButton = valueButton("Remover");
        removeButton.setOnClickListener(view -> actions.onRemove());
        optionsRows.addView(new OptionRow("Remover a cópia", "Apaga os arquivos importados dentro do aplicativo.", removeButton));
        TextView privateNote = label("Sua ISO/ZIP original é preservada. Desinstalar o aplicativo remove a biblioteca e as configurações locais.", 10, MUTED);
        privateNote.setPadding(dp(8), dp(16), dp(8), dp(10));
        privateNote.setLineSpacing(dp(2), 1);
        optionsRows.addView(privateNote);
        root.requestApplyInsets();
    }

    public View root() { return root; }

    public boolean isOptionsVisible() { return optionsVisible; }

    public void showOptions(boolean visible) {
        optionsVisible = visible;
        optionsPane.setVisibility(visible ? View.VISIBLE : View.GONE);
        optionsButton.setSelected(visible);
        updateArrangement(root.getWidth(), root.getHeight());
        if (visible) driverButton.requestFocus();
        else optionsButton.requestFocus();
    }

    /** Add a setting only after its runtime implementation is available. */
    public Button addOption(String key, String title, String caption, String value) {
        if (values.containsKey(key)) throw new IllegalArgumentException("Duplicate launcher setting: " + key);
        Button button = valueButton(value);
        button.setOnClickListener(view -> actions.onSetting(key));
        optionsRows.addView(new OptionRow(title, caption, button), optionInsertIndex++);
        values.put(key, button);
        return button;
    }

    public void setOptionValue(String key, String value) {
        Button button = values.get(key);
        if (button != null) button.setText(value);
    }

    public void setDriverLabel(String label) { driverButton.setText(label); }

    public void setDriverDescription(String description) { driverCaption.setText(description); }

    public void setSettingsEnabled(boolean enabled) {
        for (Button button : values.values()) button.setEnabled(enabled);
        driverManageButton.setEnabled(enabled);
    }

    public void setLibraryReady(boolean ready) {
        status.setTextColor(ready ? Color.rgb(172, 204, 131) : WHITE);
    }

    private Typeface condensed() { return Typeface.create("sans-serif-condensed", Typeface.BOLD); }

    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }

    private TextView label(String text, float size, int color) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setFontFeatureSettings("kern");
        view.setIncludeFontPadding(false);
        return view;
    }

    private Button mainButton(String text, Runnable action) {
        Button button = valueButton(text);
        button.setTextSize(23);
        button.setTypeface(condensed());
        button.setLetterSpacing(0.025f);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private LinearLayout.LayoutParams mainButtonLayout() {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = dp(3);
        layout.bottomMargin = dp(3);
        return layout;
    }

    private Button valueButton(String text) {
        Button button = new Button(activity);
        button.setId(View.generateViewId());
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        button.setTextColor(new ColorStateList(new int[][]{
                new int[]{-android.R.attr.state_enabled}, new int[]{}
        }, new int[]{Color.rgb(118, 115, 121), WHITE}));
        button.setBackground(new ActionDrawable());
        button.setPadding(dp(10), dp(5), dp(10), dp(5));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setMaxLines(2);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setGravity(Gravity.CENTER);
        button.setStateListAnimator(null);
        button.setShadowLayer(dp(1), 0, dp(1), Color.BLACK);
        return button;
    }

    private void updateArrangement(int width, int height) {
        if (width <= 0 || height <= 0) return;
        int availableWidth = width - root.getPaddingLeft() - root.getPaddingRight();
        int availableHeight = height - root.getPaddingTop() - root.getPaddingBottom();
        landscape = availableWidth >= dp(620) && availableWidth > availableHeight;
        body.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        body.setPadding(dp(landscape ? 24 : 14), dp(landscape ? 16 : 12),
                dp(landscape ? 24 : 14), dp(landscape ? 16 : 12));
        if (landscape) {
            LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -1, optionsVisible ? 0.43f : 1);
            left.rightMargin = optionsVisible ? dp(24) : 0;
            menuScroll.setLayoutParams(left);
            optionsPane.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 0.57f));
        } else {
            LinearLayout.LayoutParams top = new LinearLayout.LayoutParams(-1, optionsVisible ? 0 : -1, optionsVisible ? 0.47f : 0);
            top.bottomMargin = optionsVisible ? dp(12) : 0;
            menuScroll.setLayoutParams(top);
            optionsPane.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 0.53f));
        }
        int menuWidth = landscape && optionsVisible
                ? Math.round((availableWidth - dp(72)) * 0.43f)
                : availableWidth - dp(landscape ? 48 : 28);
        int sidePadding = Math.max(0, (menuWidth - dp(440)) / 2);
        menuScroll.setPadding(sidePadding, 0, sidePadding, 0);
    }

    private final class ResponsiveRoot extends FrameLayout {
        ResponsiveRoot(Context context) { super(context); }
        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            updateArrangement(width, height);
        }
    }

    private final class OptionRow extends LinearLayout {
        final TextView caption;
        OptionRow(String title, String description, Button value) {
            super(activity);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(8), dp(7), dp(8), dp(7));
            setMinimumHeight(dp(68));
            LinearLayout text = new LinearLayout(activity);
            text.setOrientation(VERTICAL);
            text.setPadding(0, 0, dp(14), 0);
            TextView heading = label(title, 19, WHITE);
            heading.setTypeface(condensed());
            heading.setMaxLines(2);
            heading.setLabelFor(value.getId());
            value.setTooltipText(description);
            text.addView(heading);
            caption = label(description, 10, MUTED);
            caption.setPadding(0, dp(4), 0, 0);
            caption.setLineSpacing(dp(1), 1);
            text.addView(caption);
            addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            addView(value, new LinearLayout.LayoutParams(dp(132), -2));
            value.setOnFocusChangeListener((view, focused) -> {
                if (focused) {
                    GradientDrawable highlight = new GradientDrawable();
                    highlight.setColor(Color.argb(38, 90, 121, 173));
                    highlight.setCornerRadius(dp(4));
                    highlight.setStroke(dp(1), BLUE);
                    setBackground(highlight);
                } else setBackground(null);
            });
        }
    }

    private final class PanelDrawable extends Drawable {
        private final boolean compact;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path outline = new Path();
        private int opacity = 255;
        PanelDrawable(boolean compact) { this.compact = compact; }
        @Override public void draw(Canvas canvas) {
            RectF bounds = new RectF(getBounds());
            float corner = dp(compact ? 7 : 13);
            chamfer(outline, bounds.left + dp(1), bounds.top + dp(1), bounds.right - dp(1), bounds.bottom - dp(1), corner);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(bounds.left, bounds.top, bounds.right, bounds.bottom,
                    Color.argb(238, 25, 19, 24), Color.argb(242, 9, 10, 15), Shader.TileMode.CLAMP));
            paint.setAlpha(opacity);
            canvas.drawPath(outline, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(Color.rgb(126, 21, 34));
            paint.setAlpha(opacity);
            canvas.drawPath(outline, paint);
            paint.setColor(Color.argb(180, 218, 33, 43));
            canvas.drawLine(bounds.left + corner, bounds.top + dp(1), bounds.right - corner, bounds.top + dp(1), paint);
        }
        @Override public void setAlpha(int alpha) { opacity = alpha; invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private final class ActionDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path outline = new Path();
        private boolean enabled = true, focused, pressed, selected;
        private int opacity = 255;
        @Override public boolean isStateful() { return true; }
        @Override protected boolean onStateChange(int[] states) {
            enabled = false; focused = false; pressed = false; selected = false;
            for (int state : states) {
                if (state == android.R.attr.state_enabled) enabled = true;
                if (state == android.R.attr.state_focused) focused = true;
                if (state == android.R.attr.state_pressed) pressed = true;
                if (state == android.R.attr.state_selected) selected = true;
            }
            invalidateSelf();
            return true;
        }
        @Override public void draw(Canvas canvas) {
            RectF bounds = new RectF(getBounds());
            boolean lit = enabled && (focused || pressed || selected);
            int top = !enabled ? Color.rgb(27, 22, 26) : lit ? Color.rgb(242, 34, 41) : Color.rgb(80, 11, 20);
            int bottom = !enabled ? Color.rgb(15, 13, 18) : lit ? Color.rgb(134, 5, 17) : Color.rgb(22, 11, 16);
            chamfer(outline, bounds.left + dp(1), bounds.top + dp(1), bounds.right - dp(1), bounds.bottom - dp(1), dp(7));
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom, top, bottom, Shader.TileMode.CLAMP));
            paint.setAlpha(opacity);
            canvas.drawPath(outline, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(focused ? 2 : 1));
            paint.setColor(!enabled ? Color.rgb(60, 32, 39) : focused ? BLUE : lit ? Color.rgb(244, 53, 61) : Color.rgb(143, 21, 36));
            paint.setAlpha(opacity);
            canvas.drawPath(outline, paint);
            if (selected && bounds.width() > dp(210)) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(WHITE);
                float middle = bounds.centerY();
                Path left = new Path();
                left.moveTo(bounds.left + dp(12), middle);
                left.lineTo(bounds.left + dp(20), middle - dp(7));
                left.lineTo(bounds.left + dp(20), middle + dp(7));
                left.close();
                canvas.drawPath(left, paint);
                Path right = new Path();
                right.moveTo(bounds.right - dp(12), middle);
                right.lineTo(bounds.right - dp(20), middle - dp(7));
                right.lineTo(bounds.right - dp(20), middle + dp(7));
                right.close();
                canvas.drawPath(right, paint);
            }
        }
        @Override public void setAlpha(int alpha) { opacity = alpha; invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static void chamfer(Path path, float left, float top, float right, float bottom, float cut) {
        path.reset();
        path.moveTo(left + cut, top);
        path.lineTo(right - cut, top);
        path.lineTo(right, top + cut);
        path.lineTo(right, bottom - cut);
        path.lineTo(right - cut, bottom);
        path.lineTo(left + cut, bottom);
        path.lineTo(left, bottom - cut);
        path.lineTo(left, top + cut);
        path.close();
    }

    /** Static artwork: no timers or rendering thread run while the launcher is idle. */
    private final class Backdrop extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        @Override public void draw(Canvas canvas) {
            RectF bounds = new RectF(getBounds());
            canvas.drawColor(Color.rgb(6, 8, 13));
            float width = bounds.width(), height = bounds.height();
            float radius = Math.max(width, height) * 0.78f;
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new RadialGradient(width * 0.37f, height * 0.48f, radius,
                    Color.argb(125, 105, 13, 28), Color.TRANSPARENT, Shader.TileMode.CLAMP));
            canvas.drawRect(bounds, paint);
            paint.setShader(new RadialGradient(width * 0.9f, height * 0.04f, radius * 0.8f,
                    Color.argb(55, 54, 77, 114), Color.TRANSPARENT, Shader.TileMode.CLAMP));
            canvas.drawRect(bounds, paint);
            paint.setShader(null);
            canvas.save();
            float emblem = Math.min(width * 0.66f, height * 1.16f);
            canvas.translate(width * 0.61f - emblem / 2, height * 0.51f - emblem / 2);
            drawSigil(canvas, emblem, Color.argb(28, 190, 43, 52));
            canvas.restore();
            paint.setStrokeWidth(dp(1));
            paint.setColor(Color.argb(25, 169, 61, 68));
            for (int line = 0; line < 8; line++) {
                float start = width * 0.43f + line * dp(36);
                canvas.drawLine(start, 0, start - height * 0.28f, height, paint);
            }
            paint.setShader(new LinearGradient(0, 0, 0, height,
                    Color.argb(65, 0, 0, 0), Color.argb(205, 0, 0, 0), Shader.TileMode.CLAMP));
            canvas.drawRect(bounds, paint);
            paint.setShader(null);
        }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    private final class SigilView extends View {
        SigilView(Context context) { super(context); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save();
            float size = Math.min(getWidth(), getHeight());
            canvas.translate((getWidth() - size) / 2, (getHeight() - size) / 2);
            drawSigil(canvas, size, Color.rgb(215, 33, 43));
            canvas.restore();
        }
    }

    /** Original coiled-dragon line art, drawn in a normalized square. */
    private void drawSigil(Canvas canvas, float size, int color) {
        canvas.save();
        canvas.scale(size / 100f, size / 100f);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f);
        canvas.drawCircle(50, 50, 46, paint);
        paint.setStrokeWidth(0.65f);
        canvas.drawCircle(50, 50, 42, paint);
        Path dragon = new Path();
        dragon.moveTo(65, 14);
        dragon.lineTo(52, 22);
        dragon.lineTo(61, 22);
        dragon.lineTo(48, 31);
        dragon.cubicTo(29, 33, 20, 49, 24, 64);
        dragon.cubicTo(29, 85, 64, 90, 77, 69);
        dragon.cubicTo(63, 82, 42, 76, 39, 62);
        dragon.cubicTo(36, 49, 47, 40, 60, 44);
        dragon.lineTo(57, 52);
        dragon.lineTo(70, 47);
        dragon.lineTo(66, 41);
        dragon.lineTo(80, 39);
        dragon.lineTo(72, 33);
        dragon.lineTo(83, 31);
        dragon.lineTo(64, 26);
        dragon.lineTo(70, 17);
        dragon.lineTo(58, 21);
        dragon.close();
        paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(dragon, paint);
        paint.setColor(Color.argb(Color.alpha(color), 9, 9, 14));
        canvas.drawCircle(65, 34, 1.4f, paint);
        canvas.restore();
    }
}
