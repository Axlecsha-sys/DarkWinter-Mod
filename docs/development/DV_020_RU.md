# DV-020: срок жизни памяти клиента

Адресный этап разделяет срок жизни общей native библиотеки и рабочих буферов, освобождает кэши экспериментов после смены режима/отключения и закрывает ресурсы визуального мира. Общая память процесса, драйверная VRAM и все системы Minecraft этим этапом не оптимизируются.

## Подтверждённые удержания

- В mesh-эксперименте Batch сохранял три int-массива с payload 256 KiB и, после использования C++, native scratch ещё 256 KiB. Два слоя и два вложенных сохранённых Frame дают верхнюю границу 2 MiB payload на worker, если все эти буферы были созданы. Это не фактический объём каждого worker и не размер всего Java-объекта.
- Отдельный Java writer кэшировал 112 KiB int-массив на worker.
- Старый voxel bridge выделял 5832+4096=9928 байт scratch для каждого использовавшего его worker из той же SharedArena, что загруженная библиотека. Завершение worker не освобождало эти индивидуальные выделения.
- VisualRenderer после disconnect закрывал только film history. В сохраняемом LevelRenderer оставались world reference, shadow target/projection/sampler; private DynamicGpuData и список casters освобождались лишь при финальном close клиента.

Это факты графа владения по исходникам. Значения не выдаются за измеренный RSS или снижение VRAM.

## Изменения

NativeScratchBuffers владеет отдельной ограниченной SharedArena и выдаёт синхронную lease. Очистка помечает область retired, запрещает новые вызовы и немедленно закрывает только свободную область. Если вызов выполняется, область закрывается при его release после возврата из native и последнего использования результата. Монитор не удерживается на время copy/downcall. Cleaner служит запасным освобождением недостижимого владельца; registry сохраняет состояние, но не Thread и не владельца.

Общая библиотека остаётся загруженной на время приложения. Её нельзя выгружать под работающим native-вызовом. Удалены scratch allocations из library arena. Ошибки выделения/регистрации закрывают частично созданные области и сохраняют исключение.

Mesh Frame сохраняет epoch жизненного цикла. Свободные массивы/ссылки сбрасываются при очистке, работающий Frame завершает запись со своими данными и освобождает их на выходе. Java writer очищает кэшируемую ссылку, не меняя массив, уже взятый текущим вызовом. Учитываются mode change, disconnect, resource reload и остановка клиента.

JOIN открывает admission, disconnect/stop закрывают его. Поэтому позднее задание, начавшееся уже после общей очистки, не создаёт новый удерживаемый native scratch. Оно завершает допустимую запись через Java и сбрасывает payload. Алгоритмы геометрии, C++ ABI и native GPU upload этим этапом не меняются.

VisualRenderer на disconnect полностью закрывается на render thread с проверкой принадлежности старому миру; queued callback не закрывает новый мир. close сбрасывает world/frame references. При native resetLevelRenderData освобождаются private shadow DynamicGpuData и casters. Повторное close безопасно. Обычная resource reload сама по себе не приравнивается к этому native reset; неизменившиеся ресурсы могут повторно использоваться.

## Раздельные измерения

- Native scratch: реальные capacity payload, live/retired regions, active calls, accumulated allocated/freed bytes.
- Java caches: число и payload массивов, active/cached Frame и Batch.
- GPU: реальные принадлежащие ресурсы и isClosed; логический размер явно названных текстур/буферов.
- JVM: Heap, non-heap и BufferPoolMXBean записываются раздельно как диагностические значения.

Object headers, allocator padding, OS pages/RSS, loaded DLL и драйверные GPU allocations в scratch payload не входят. BufferPoolMXBean не охватывает все FFM arena allocations. System.gc и произвольные требования возврата heap не используются.

## Проверка

Private SDK suite использует отдельную копию ранее созданного flat-стенда, трижды открывает её, дожидается настоящих CPP mesh/voxel compiles и выполняет disconnect. Эксперименты проверяются последовательно. Дополнительно проверяются resize, resource reload с продолжением рендера и отдельно native resetLevelRenderData.

Фиксируются реально выполненный shadow pass, непустые casters, GPU handles и ссылки на мир. Удерживаемая lease проходит disconnect: данные остаются живыми до release, повторный acquire отклоняется, после release область закрыта. Поздняя сборка после disconnect пишет настоящий BufferBuilder из 32 quad/128 vertices/3584 bytes через Java, без новой native allocation и без удержанного payload. Отдельно проверяются retirement обоих типов scratch.

OpenGL и Vulkan завершили по 19 проверок и три load/render/disconnect цикла. В каждом disconnect проверены закрытие 13 захваченных GPU handles, освобождение ссылок на старый мир, нулевые liveBytes/activeCalls/retiredRegions обоих scratch видов и нулевой cached Java payload. Захваченный логический shadow GPU payload в проверенных сценах — около 4,72 MB; это не вся VRAM процесса. Native mesh и voxel kernels реально выполнялись, отдельный held buffer пережил retirement до release. Все три поздних BufferBuilder-записи сохранили 128 vertices/3584 bytes без роста native allocatedBytes.

Первый runtime OpenGL выявил оставшийся открытый private DynamicGpuData: native LevelExtractor ставит reset-флаг при setLevel(null), но сам reset выполняет лишь при следующем world extraction. Исправлен немедленный guarded release собственных ресурсов через SceneAccess на disconnect; перед этим неуспешный отчёт сохранён. Свежие OpenGL/Vulkan прогоны прошли после исправления, assertions не ослаблялись. Первый Gradle запуск остановился до клиента из-за config-cache несовместимости приватного Copy task; task отмечен несовместимым, это не ошибка игрового кода.

Самостоятельные blockMeshVerify (640 пакетов) и voxelFacesVerify (264 сцены) также прошли. SDK lifecycle suite является функциональной проверкой, не измерением FPS.

Локальный evidence:
- build/run/dv020Client-opengl-20261101-074058-155/dv020-evidence/report.json;
- build/run/dv020Client-vulkan-20261101-074320-751/dv020-evidence/report.json;
- build/run/dv020Client-opengl-20261101-073630-480/dv020-evidence/report.json — сохранённый ранний FAIL;
- build/dv020-evidence-20261101 — source/publication manifests и итоговые проверки.

## Границы

Не заявляются отсутствие всех утечек Minecraft, снижение RSS на фиксированное число мегабайт или ускорение кадров. Сокращение удержания проверяется на конкретных владельцах при quiescence; активные native вызовы временно сохраняют память до безопасного возврата. Другие GPU backends/устройства, длительный thread churn и fault injection всех вариантов OOM требуют отдельного покрытия.
