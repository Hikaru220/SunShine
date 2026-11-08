package com.sunshine.plugin.court;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Рисует судебный документ (решение по делу) картинкой PNG, формат A4 при 150 dpi. Чистый Java2D без
 * внешних программ и зависимостей от сервера - на панельном хостинге нет ни Word, ни LibreOffice.
 * Кириллицу гарантирует свой шрифт DejaVu Serif из ресурсов jar (на хостинге системных шрифтов может
 * не быть). Можно положить свой бланк фоном ({@link Options#background()}) - тогда рисуется только
 * содержимое, без рамки, шапки и печати.
 */
public final class CourtDocumentRenderer {

    public static final int WIDTH = 1240;
    public static final int HEIGHT = 1754;

    /** maxLines - сколько строк текста поля влезет, остальное обрезается многоточием. */
    public record Field(String label, String text, int maxLines) {
    }

    public record Document(String courtName, String title, String number, String date, List<Field> fields,
                           String judge, String footer) {
    }

    /** background - необязательный свой бланк (PNG/JPG); contentTop и margin нужны только с ним. */
    public record Options(File background, int contentTop, int margin) {
    }

    private static final Color INK = new Color(0x2a, 0x21, 0x18);
    private static final Color ACCENT = new Color(0x7a, 0x5a, 0x2a);
    private static final Color PAPER = new Color(0xfb, 0xf7, 0xee);
    private static final Color MUTED = new Color(0x8a, 0x80, 0x70);
    private static final int LABEL_COLUMN = 270;

    private static Font serif;
    private static Font serifBold;
    /** Каллиграфический шрифт для "живой" подписи судьи (Great Vibes, формальный копперплейт, кириллица + латиница). */
    private static Font script;

    static {
        System.setProperty("java.awt.headless", "true");
    }

    private CourtDocumentRenderer() {
    }

    private static synchronized void loadFonts() throws Exception {
        if (serif != null) return;
        try (InputStream in = CourtDocumentRenderer.class.getResourceAsStream("/court/fonts/DejaVuSerif.ttf")) {
            serif = Font.createFont(Font.TRUETYPE_FONT, in);
        }
        try (InputStream in = CourtDocumentRenderer.class.getResourceAsStream("/court/fonts/DejaVuSerif-Bold.ttf")) {
            serifBold = Font.createFont(Font.TRUETYPE_FONT, in);
        }
        try (InputStream in = CourtDocumentRenderer.class.getResourceAsStream("/court/fonts/GreatVibes-Regular.ttf")) {
            script = Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (Exception e) {
            script = serif.deriveFont(Font.ITALIC);
        }
    }

    public static byte[] render(Document doc, Options options) throws Exception {
        loadFonts();
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);

            boolean custom = options.background() != null && options.background().isFile();
            int margin = custom ? options.margin() : 110;
            int y;
            if (custom) {
                g.drawImage(ImageIO.read(options.background()), 0, 0, WIDTH, HEIGHT, null);
                y = options.contentTop();
            } else {
                g.setColor(PAPER);
                g.fillRect(0, 0, WIDTH, HEIGHT);
                drawFrame(g);
                g.setColor(ACCENT);
                drawCentered(g, doc.courtName(), serifBold.deriveFont(46f), ACCENT, 130);
                g.setStroke(new BasicStroke(2f));
                g.drawLine(margin, 152, WIDTH - margin, 152);
                y = 250;
            }

            y = drawTitleBlock(g, doc, y, margin, custom);
            y = drawFields(g, doc.fields(), y, margin);

            drawSignature(g, doc.judge(), margin, custom);
            if (!custom) drawStamp(g, doc.number(), margin);

            g.setFont(serif.deriveFont(20f));
            g.setColor(MUTED);
            drawCentered(g, doc.footer(), serif.deriveFont(20f), MUTED, HEIGHT - 70);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static void drawFrame(Graphics2D g) {
        g.setColor(ACCENT);
        g.setStroke(new BasicStroke(4f));
        g.drawRect(40, 40, WIDTH - 80, HEIGHT - 80);
        g.setStroke(new BasicStroke(1.5f));
        g.drawRect(56, 56, WIDTH - 112, HEIGHT - 112);
    }

    private static int drawTitleBlock(Graphics2D g, Document doc, int y, int margin, boolean custom) {
        drawCentered(g, doc.title(), serifBold.deriveFont(62f), INK, y);
        drawCentered(g, "по делу № " + doc.number(), serif.deriveFont(34f), INK, y + 56);
        g.setFont(serif.deriveFont(26f));
        g.setColor(INK);
        g.drawString(clean(doc.date(), serif.deriveFont(26f)), margin, y + 126);
        FontMetrics fm = g.getFontMetrics();
        String place = "Игровой сервер Sunshine";
        g.drawString(place, WIDTH - margin - fm.stringWidth(place), y + 126);
        g.setColor(ACCENT);
        g.setStroke(new BasicStroke(1.2f));
        g.drawLine(margin, y + 148, WIDTH - margin, y + 148);
        return y + 205;
    }

    private static int drawFields(Graphics2D g, List<Field> fields, int y, int margin) {
        Font labelFont = serifBold.deriveFont(26f);
        Font textFont = serif.deriveFont(26f);
        int textX = margin + LABEL_COLUMN;
        int textWidth = WIDTH - margin - textX;
        int bottomLimit = HEIGHT - 340;

        for (Field field : fields) {
            g.setFont(textFont);
            FontMetrics fm = g.getFontMetrics();
            List<String> lines = wrap(clean(field.text(), textFont), fm, textWidth);
            boolean truncated = lines.size() > field.maxLines();
            if (truncated) {
                lines = new ArrayList<>(lines.subList(0, field.maxLines()));
                String last = lines.get(lines.size() - 1);
                lines.set(lines.size() - 1, (last.length() > 3 ? last.substring(0, last.length() - 3) : last) + "...");
            }
            int lineHeight = fm.getHeight() + 6;
            if (y + lineHeight * Math.max(1, lines.size()) > bottomLimit) break;

            g.setFont(labelFont);
            g.setColor(ACCENT);
            g.drawString(clean(field.label(), labelFont), margin, y);
            g.setFont(textFont);
            g.setColor(INK);
            for (String line : lines) {
                g.drawString(line, textX, y);
                y += lineHeight;
            }
            y += 14;
        }
        return y;
    }

    private static void drawSignature(Graphics2D g, String judge, int margin, boolean custom) {
        int y = HEIGHT - 250;
        g.setFont(serifBold.deriveFont(26f));
        g.setColor(ACCENT);
        g.drawString("Судья:", margin, y);

        int lineStart = margin + 120;
        int lineEnd = margin + 440;
        g.setColor(INK);
        g.setStroke(new BasicStroke(1.5f));
        g.drawLine(lineStart, y + 4, lineEnd, y + 4);

        // Сама "подпись": ник судьи крупным рукописным шрифтом синими чернилами, чуть наклонённо, прямо на линии.
        String signature = clean(judge, script);
        int signatureWidthLimit = lineEnd - lineStart + 20;
        float signatureSize = 92f;
        Font signatureFont = script.deriveFont(signatureSize);
        FontMetrics signatureMetrics = g.getFontMetrics(signatureFont);
        while (signatureMetrics.stringWidth(signature) > signatureWidthLimit && signatureSize > 30f) {
            signatureSize -= 2f;
            signatureFont = script.deriveFont(signatureSize);
            signatureMetrics = g.getFontMetrics(signatureFont);
        }
        Graphics2D ink = (Graphics2D) g.create();
        try {
            ink.rotate(Math.toRadians(-4), lineStart, y);
            ink.setFont(signatureFont);
            ink.setColor(new Color(0x1d, 0x2f, 0x6b));
            ink.drawString(signature, lineStart + 8, y - 2);
        } finally {
            ink.dispose();
        }

        Font caption = serif.deriveFont(18f);
        g.setFont(caption);
        g.setColor(MUTED);
        int captionWidth = g.getFontMetrics().stringWidth("подпись");
        g.drawString("подпись", lineStart + (lineEnd - lineStart - captionWidth) / 2, y + 28);

        // Ник справа от линии, не под ней - иначе длинный ник наезжает на подпись. Не влезает - уменьшаем шрифт.
        String name = "/ " + clean(judge, serif) + " /";
        int rightLimit = custom ? WIDTH - margin : WIDTH - margin - 340;
        int available = rightLimit - (lineEnd + 24);
        float size = 26f;
        Font nameFont = serif.deriveFont(size);
        FontMetrics fm = g.getFontMetrics(nameFont);
        while (fm.stringWidth(name) > available && size > 14f) {
            size -= 1f;
            nameFont = serif.deriveFont(size);
            fm = g.getFontMetrics(nameFont);
        }
        g.setFont(nameFont);
        g.setColor(INK);
        g.drawString(name, lineEnd + 24, y);
    }

    private static void drawStamp(Graphics2D g, String number, int margin) {
        int cx = WIDTH - margin - 170;
        int cy = HEIGHT - 290;
        int radius = 130;
        Graphics2D s = (Graphics2D) g.create();
        try {
            s.translate(cx, cy);
            s.rotate(Math.toRadians(-14));
            s.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.72f));
            Color stampColor = new Color(0x2f, 0x4f, 0xa8);
            s.setColor(stampColor);
            s.setStroke(new BasicStroke(6f));
            s.drawOval(-radius, -radius, radius * 2, radius * 2);
            s.setStroke(new BasicStroke(2.5f));
            s.drawOval(-radius + 16, -radius + 16, (radius - 16) * 2, (radius - 16) * 2);
            drawCenteredAt(s, "СУД", serifBold.deriveFont(40f), stampColor, -14);
            drawCenteredAt(s, "SUNSHINE", serifBold.deriveFont(32f), stampColor, 28);
            drawCenteredAt(s, "№ " + number, serif.deriveFont(24f), stampColor, 66);
        } finally {
            s.dispose();
        }
    }

    /** Центрирует строку по ширине страницы; y - базовая линия. */
    private static void drawCentered(Graphics2D g, String text, Font font, Color color, int y) {
        g.setFont(font);
        g.setColor(color);
        String safe = clean(text, font);
        g.drawString(safe, (WIDTH - g.getFontMetrics().stringWidth(safe)) / 2, y);
    }

    /** Центрирует строку относительно нуля текущей системы координат (для печати). */
    private static void drawCenteredAt(Graphics2D g, String text, Font font, Color color, int y) {
        g.setFont(font);
        g.setColor(color);
        String safe = clean(text, font);
        g.drawString(safe, -g.getFontMetrics().stringWidth(safe) / 2, y);
    }

    /** Убирает символы, которых нет в шрифте (эмодзи и т.п.), иначе вместо них рисуются квадраты. */
    private static String clean(String text, Font font) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            if (cp == '\n' || cp == '\t' || font.canDisplay(cp)) sb.appendCodePoint(cp == '\t' ? ' ' : cp);
        });
        return sb.toString().replaceAll("[ ]{2,}", " ").trim();
    }

    private static List<String> wrap(String text, FontMetrics fm, int maxWidth) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.trim().split("\\s+")) {
                if (word.isEmpty()) continue;
                // слишком длинное слово (ссылка) режем по символам
                while (fm.stringWidth(word) > maxWidth) {
                    int cut = word.length();
                    while (cut > 1 && fm.stringWidth(word.substring(0, cut)) > maxWidth) cut--;
                    if (line.length() > 0) {
                        lines.add(line.toString());
                        line.setLength(0);
                    }
                    lines.add(word.substring(0, cut));
                    word = word.substring(cut);
                }
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (fm.stringWidth(candidate) <= maxWidth) {
                    line.setLength(0);
                    line.append(candidate);
                } else {
                    lines.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                }
            }
            lines.add(line.toString());
        }
        if (lines.isEmpty()) lines.add("");
        return lines;
    }
}
