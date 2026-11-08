package com.sunshine.plugin.console;

import com.sunshine.plugin.SunShinePlugin;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Log4j2-аппендер, транслирующий вывод консоли сервера в Discord-канал - аналог
 * "console channel" из DiscordSRV. Цепляется напрямую на root-логгер Paper, поэтому
 * ловит абсолютно всё: ванильный лог, логи плагинов, стектрейсы, результат команд,
 * выполненных через {@link DiscordConsoleListener}.
 *
 * <p>Строки копятся в буфере и улетают в Discord раз в ~{@value #FLUSH_PERIOD_SECONDS}
 * секунды одним или несколькими сообщениями (Discord режет сообщения на 2000 символов),
 * чтобы не долбить rate-limit построчно на каждый чих консоли.</p>
 */
public class ConsoleLogRelay extends AbstractAppender {

    private static final String APPENDER_NAME = "SunShineConsoleRelay";
    private static final int DISCORD_MESSAGE_LIMIT = 2000;
    // Запас под обёртку ```\n ... \n``` (7 служебных символов + перевод строки с каждой стороны).
    private static final int CHUNK_LIMIT = DISCORD_MESSAGE_LIMIT - 16;
    private static final long FLUSH_PERIOD_SECONDS = 3;

    /**
     * Логгеры, которые никогда не должны попадать в Discord. Прежде всего - сама JDA
     * (net.dv8tion) и её HTTP-клиент (okhttp3): без этого фильтра лог "JDA отправила
     * сообщение в канал X" сам порождал бы новое сообщение в этом же канале - эхо-петля,
     * которая быстро зафлудит канал и упрётся в rate-limit. com.sunshine.libs - шейднутые
     * зависимости (в т.ч. та же JDA под другим корнем пакета после relocation).
     */
    private static final String[] IGNORED_LOGGER_PREFIXES = {
            "net.dv8tion",
            "okhttp3",
            "com.sunshine.libs"
    };

    /**
     * ANSI escape-коды (в т.ч. 24-битный truecolor, которым FlectonePulse красит чат в консоли).
     * В самой консоли/файле лога терминал их интерпретирует как цвет, но при пересылке в Discord
     * (обычный текст) они остаются как нечитаемый мусор вида "[38;2;77;166;255m" - вырезаем перед
     * отправкой, аналогично тому, как раньше почистили вывод whois/geolocate.
     */
    private static final Pattern ANSI_PATTERN = Pattern.compile("\\[[;\\d]*[a-zA-Z]");

    private final SunShinePlugin plugin;
    private final ReentrantLock bufferLock = new ReentrantLock();
    private final Deque<String> buffer = new ArrayDeque<>();

    private volatile String channelId;
    private ScheduledExecutorService scheduler;

    public ConsoleLogRelay(SunShinePlugin plugin) {
        super(APPENDER_NAME, null, PatternLayout.newBuilder()
                .withPattern("[%d{HH:mm:ss} %level] %msg%n")
                .build(), false, Property.EMPTY_ARRAY);
        this.plugin = plugin;
    }

    /**
     * Включает мост: регистрирует аппендер на root-логгере Log4j2 и запускает периодический
     * флаш буфера в Discord. Если {@code console.enabled=false} или канал не задан - ничего
     * не делает (защита дублируется здесь на случай, если вызывающий код забудет проверить).
     */
    public void start(String channelId) {
        if (!plugin.config().consoleEnabled()) return;
        if (channelId == null || channelId.isBlank()) {
            plugin.getLogger().warning("console.enabled=true, но console.channel-id пуст - консольный мост не запущен.");
            return;
        }
        this.channelId = channelId;

        start(); // AbstractAppender#start() - переводит аппендер в состояние STARTED, обязательно перед append()

        org.apache.logging.log4j.core.Logger rootLogger =
                (org.apache.logging.log4j.core.Logger) LogManager.getRootLogger();
        rootLogger.addAppender(this);

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SunShine-ConsoleRelay-Flush");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::flush, FLUSH_PERIOD_SECONDS, FLUSH_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Отписывает аппендер от root-логгера и останавливает планировщик. Обязательно вызывать
     * из {@code SunShinePlugin#onDisable} ДО {@code discordBot.stop()} - иначе поток JDA, в
     * который улетает последний флаш буфера, может быть уже остановлен, что в этом проекте
     * уже приводило к крашам при /reload и выключении (см. комментарий в
     * {@code DiscordBot#stop()} про "zip file closed").
     */
    @Override
    public void stop() {
        org.apache.logging.log4j.core.Logger rootLogger =
                (org.apache.logging.log4j.core.Logger) LogManager.getRootLogger();
        rootLogger.removeAppender(this);

        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        flush(); // последний шанс выгрузить то, что накопилось в буфере
        super.stop();
    }

    @Override
    public void append(LogEvent event) {
        String loggerName = event.getLoggerName();
        if (loggerName != null) {
            for (String prefix : IGNORED_LOGGER_PREFIXES) {
                if (loggerName.startsWith(prefix)) return;
            }
            // Сообщения самого этого аппендера (например, ошибки Log4j при его же работе) -
            // не транслируем, чтобы не зациклиться.
            if (loggerName.equals(APPENDER_NAME)) return;
        }

        String formatted;
        try {
            formatted = new String(getLayout().toByteArray(event), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return; // не роняем логирование сервера из-за ошибки форматирования
        }
        formatted = ANSI_PATTERN.matcher(formatted).replaceAll("");
        if (formatted.isBlank()) return;

        bufferLock.lock();
        try {
            buffer.addLast(formatted.stripTrailing());
        } finally {
            bufferLock.unlock();
        }
    }

    /** Забирает всё из буфера и отправляет в Discord одним или несколькими сообщениями. */
    private void flush() {
        if (channelId == null || !plugin.discordBot().isReady()) return;

        List<String> lines;
        bufferLock.lock();
        try {
            if (buffer.isEmpty()) return;
            lines = new ArrayList<>(buffer);
            buffer.clear();
        } finally {
            bufferLock.unlock();
        }

        for (String chunk : chunk(lines)) {
            plugin.discordBot().sendToChannel(channelId, "```\n" + chunk + "\n```");
        }
    }

    /** Режет накопленные строки на куски не длиннее {@link #CHUNK_LIMIT} символов (лимит Discord). */
    private List<String> chunk(List<String> lines) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String line : lines) {
            // Одна строка сама по себе длиннее лимита (гигантский стектрейс) - режем её саму на части.
            if (line.length() > CHUNK_LIMIT) {
                if (current.length() > 0) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                for (int i = 0; i < line.length(); i += CHUNK_LIMIT) {
                    chunks.add(line.substring(i, Math.min(line.length(), i + CHUNK_LIMIT)));
                }
                continue;
            }

            if (current.length() + line.length() + 1 > CHUNK_LIMIT) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) current.append('\n');
            current.append(line);
        }
        if (current.length() > 0) chunks.add(current.toString());
        return chunks;
    }
}
