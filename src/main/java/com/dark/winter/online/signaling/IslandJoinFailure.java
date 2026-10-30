// Only fixed codes cross the signed rejection boundary; never display arbitrary peer/error text.
package com.dark.winter.online.signaling;

import java.io.IOException;

/** Only fixed codes cross the signed rejection boundary; never display arbitrary peer/error text. */
public final class IslandJoinFailure extends IOException {
    public enum Kind {
        BUILD_MISMATCH("На ПК разные сборки DarkWinter. Установите один и тот же JAR на обоих ПК и перезапустите игру."),
        MINECRAFT_MISMATCH("На ПК разные версии Minecraft. Для входа нужна одинаковая версия."),
        PROTOCOL_MISMATCH("Версии сетевого протокола различаются. Обновите DarkWinter на обоих ПК."),
        INVALID_INVITATION("Код острова повреждён или просрочен. Скопируйте новое приглашение у ведущего."),
        WRONG_RECIPIENT("Это приглашение выдано другому участнику. Попросите новое приглашение."),
        SELF_INVITATION("Это приглашение на ваш собственный остров. Его должен вводить другой участник."),
        HOST_UNAVAILABLE("Остров у ведущего не открыт. Ведущему нужно открыть остров для игры."),
        HOST_REJECTED("Ведущий отклонил вход или настройки острова запрещают подключение.");
        private final String message;
        Kind(String message){this.message=message;}
        public String message(){return message;}
        public String wireCode(){return "DW_JOIN_"+name();}
    }
    private final Kind kind;
    public IslandJoinFailure(Kind kind){super(kind.wireCode());this.kind=kind;}
    public Kind kind(){return kind;}
    public String publicMessage(){return kind.message();}
    public static IslandJoinFailure fromWire(String code){
        for(Kind kind:Kind.values())if(kind.wireCode().equals(code))return new IslandJoinFailure(kind);
        return null;
    }
    public static IslandJoinFailure find(Throwable failure){
        for(int depth=0;failure!=null&&depth<12;depth++,failure=failure.getCause())
            if(failure instanceof IslandJoinFailure typed)return typed;
        return null;
    }
    public static boolean isPublicMessage(String text){
        for(Kind kind:Kind.values())if(kind.message().equals(text))return true;
        return false;
    }
}
