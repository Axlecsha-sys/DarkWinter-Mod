// Maps service failures to fixed public descriptions without exposing request data.
package com.dark.winter.client.online;

import com.dark.winter.online.node.DirectoryClient;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;

/** Fixed public descriptions only: exception text, endpoints and credentials never enter the UI. */
public final class OnlineServiceDiagnostics {
    private static final String GENERIC =
            "Связь со службой не подтверждена. Запросы не считаются доставленными.";

    private OnlineServiceDiagnostics() {}

    public static String describe(Throwable failure) {
        List<Throwable> chain = new ArrayList<>(16);
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && chain.size() < 16 && visited.add(current);
                current = current.getCause()) {
            chain.add(current);
        }
        // Fixed diagnostic advice takes precedence; an unsigned rejection hint establishes no trust.
        for (Throwable current : chain) {
            if (current instanceof DirectoryClient.Failure classified) {
                return switch (classified.kind()) {
                    case INVALID_ADDRESS -> "Неверный адрес службы. Проверьте адрес в настройках подключения.";
                    case HTTPS_REQUIRED -> "Для службы на другом компьютере нужен адрес HTTPS и доверенный сертификат.";
                    case NODE_CHANGED -> "Ключ службы изменился. Проверьте служебный узел; прежнее доверие сохранено.";
                    case CLOCK_SKEW -> "Проверьте дату, время и их синхронизацию на обоих ПК.";
                    case REJECTED -> "Служба отклонила запрос. Действие не подтверждено.";
                    case INVALID_RESPONSE -> "Ответ службы не прошёл проверку. Действие не подтверждено.";
                };
            }
        }
        for (Throwable current : chain) {
            if (current instanceof UnknownHostException || current instanceof UnresolvedAddressException)
                return "Не удалось найти адрес службы. Проверьте имя службы и подключение к сети.";
            if (current instanceof SSLException)
                return "Не удалось проверить защищённое соединение. Проверьте доверие к сертификату и совпадение имени службы; проверку TLS отключать не нужно.";
            if (current instanceof ConnectException)
                return "Не удалось подключиться к службе. Проверьте, запущена ли она и доступна ли с этого компьютера.";
            if (current instanceof HttpTimeoutException || current instanceof TimeoutException)
                return "Служба не ответила вовремя. Проверьте её доступность и повторите попытку.";
            if (current instanceof CancellationException || current instanceof InterruptedException)
                return "Подключение к службе отменено. Действие не подтверждено.";
            if (current instanceof IllegalArgumentException)
                return "Неверный адрес службы. Проверьте адрес в настройках подключения.";
        }
        return GENERIC;
    }
}
