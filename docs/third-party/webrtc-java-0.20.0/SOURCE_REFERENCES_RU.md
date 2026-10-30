# webrtc-java 0.20.0: уведомления и ссылки на исходники

Это инвентаризация включённой зависимости и отправные ссылки для подготовки материалов к распространению. Она не изменяет лицензии, не является письменным предложением выдать отсутствующие исходники и не подтверждает полное выполнение условий распространения.

## Что включено

DarkWinter использует dev.onvoid.webrtc:webrtc-java:0.20.0 и Windows x64 native classifier. Отдельный webrtc-java-media не включён. Исходный upstream NOTICE сообщает о статически связанном WebRTC и его сторонних компонентах, включая Chromium FFmpeg под LGPL-2.1-or-later; отсутствие media JAR не устраняет этот компонент. [Upstream NOTICE](https://github.com/devopvoid/webrtc-java/blob/v0.20.0/NOTICE).

В META-INF/licenses/darkwinter-webrtc/ сохранены без изменений:

- LICENSE и NOTICE поставщика;
- windows-x86_64/META-INF/licenses/webrtc/LICENSE.md, включая раздел ffmpeg с текстом LGPL;
- windows-x86_64/META-INF/licenses/webrtc/PATENTS;
- windows-x86_64/META-INF/licenses/nvenc/LICENSE.

Эти пять файлов при проверке игрового JAR 29.10.2026 совпали по SHA-256 с сохранёнными копиями. Три native-уведомления также остались внутри вложенного Windows JAR. Общая отметка CC0 проекта DarkWinter не заменяет лицензии этих зависимостей.

Проверенный DLL payload: webrtc-java-windows-x86_64.dll, 22532096 байт, SHA-256 5da4d170b52973468ed4b629b1eaf8d809431f8ec77858aaa3970cd867fbe93a. Он совпадает с DLL оригинального Maven classifier. Fabric добавляет metadata во вложенные JAR, поэтому хеш вложенного контейнера не следует приравнивать к хешу оригинального Maven JAR.

## Первичные ссылки

- [Релиз v0.20.0](https://github.com/devopvoid/webrtc-java/releases/tag/v0.20.0).
- [Точные исходники Java/JNI wrapper: commit cb394fb74fa7d26c9d7316368e59a08e6f167fad](https://github.com/devopvoid/webrtc-java/tree/cb394fb74fa7d26c9d7316368e59a08e6f167fad).
- [Архив этого wrapper commit](https://github.com/devopvoid/webrtc-java/archive/cb394fb74fa7d26c9d7316368e59a08e6f167fad.zip).
- [JNI POM этого commit](https://github.com/devopvoid/webrtc-java/blob/cb394fb74fa7d26c9d7316368e59a08e6f167fad/webrtc-jni/pom.xml).
- [Настройки и процедура сборки WebRTC](https://github.com/devopvoid/webrtc-java/blob/cb394fb74fa7d26c9d7316368e59a08e6f167fad/webrtc-jni/src/main/cpp/dependencies/webrtc/CMakeLists.txt).
- [Upstream набор лицензий](https://github.com/devopvoid/webrtc-java/tree/cb394fb74fa7d26c9d7316368e59a08e6f167fad/webrtc-jni/src/main/cpp/dependencies/webrtc/licenses).

POM задаёт branch-heads/8059; CMake использует соответствующий refs/branch-heads/8059 и его DEPS, статическую сборку и GN-настройки. [Репозиторий WebRTC](https://webrtc.googlesource.com/src/+/refs/branch-heads/8059/), [Chromium FFmpeg](https://chromium.googlesource.com/chromium/third_party/ffmpeg/) — отправные репозитории, а не подтверждённые точные исходники проверенной DLL. Текущее состояние ветки не заменяет commit и DEPS именно сборки 16.10.2026.

## Что пока не подтверждено

В проверенном игровом JAR нет полного архива соответствующих исходников статически включённых WebRTC/FFmpeg, записи точных native dependency commits или комплекта объектов для перелинковки. Manifest native JAR указывает версию и дату, но не точный WebRTC commit.

Для решения о распространении ещё нужны подтверждённые соответствующие native-исходники, использованные dependency revisions/patches и реальные инструкции/материалы выбранного способа пересборки или перелинковки. Одна ссылка на wrapper, название ветки, текст LGPL или успешный игровой тест этого не доказывают. Правовые выводы о достаточности комплекта здесь не делаются.

Обращение к поставщику и публикация исходников этим документом не выполнялись. Оригинальные LICENSE/NOTICE/PATENTS не сокращены и не переписаны.
