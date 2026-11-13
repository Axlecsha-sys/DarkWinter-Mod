# Текущее состояние разработки DarkWinter

## 13.11 — подготовка сборки DarkWinter pre-0.0.3

Устранены препятствия обычной сборки публичных исходников WKE: приватный `tests/wke-client` подключается только при наличии; общий `wkeBootstrapCpp` подготавливает закреплённый Zig 0.14.1 с проверкой архива, marker и SHA исполняемого файла. Старый `voxelFacesBootstrapCpp` остаётся экспериментальным alias с прежней проверкой ветки. Производственные native-задачи зависят от общего installer, renderer также явно зависит от проверяемых Vulkan-Headers. GitHub Actions переведён на Windows runner и `build wkeVerifyPackage`; сам удалённый workflow ещё не запускался.

Версия исходников и JAR — `0.0.3-pre`; утверждённые название релиза `DarkWinter pre-0.0.3`, тег `pre-0.0.3` и тексты коммита/релиза сохранены. Обновлены README и инструкции сборки/публикации. Изменения этого этапа касаются подготовки и проверки сборки.

Проверки:
- Рабочий `build wkeVerifyPackage`, job `2826a10cdfb648568fdc1a024114a6bc`: exit 0, 54 с. `build/libs/dark-winter-0.0.3-pre.jar`, 139337201 байт, SHA-256 `f724e9458c0676c104ff5043e9823ac5bc916f6300270b25caf8cd99df253968`.
- `verifyPublicationBuild`, job `83a6cbf2f4a3445aafbbc14ad595929e`: exit 0, 2 мин 3 с. Создана новая копия входов в `build/publication-check/source` без tools/tests, прежних build outputs и личного CodexResumerSetup (уже исключённого .gitignore). Zig и Vulkan Headers подготовлены самой копией. Её JAR: 139336842 байта, SHA-256 `0e6d631868e781fa660cce7ee4161361188e86c5f7a71fa68915569445a1ac23`.
- В обоих пакетах проверены 3 WKE DLL, их SHA-256, production Mixin entries, 36 канонических render resources и отсутствие приватных тестовых классов; 157 block textures проходят проверку mip level 4. Версия processed fabric.mod.json в обоих случаях `0.0.3-pre`.

Сборочный fixture не является полным Git-экспортом и не заменяет `tools/check-repository.py`. Следующий этап — аудит кандидатов, синхронизация отдельной публикационной копии с сохранением истории main, согласованный коммит и выпуск существующего draft. Git-индекс не менялся; commit/push/publish на этом этапе не выполнялись. Старую `build/github-ready-20261031-compact`, пользовательские миры и локальные настройки не заменяли.

## 12.11 — продолжение: числовая геометрия снега и повторные шаги

Перенос горячего представления геометрии выполнен; игровая приёмка производительности НЕ пройдена. Подробные числа, версии, пути исходных записей и ограничения: `docs/snow-storage-comparison-20261112.json`. Это текущая контрольная точка; ниже сохранена история предыдущих работ.

Остановка прошлого чата восстановлена: после 11.11 20:52 UTC уже имелись primitive identities в ConformingSnowMesh и массив ReliefCache, но новая сборка не проверялась. Продолжение было разрешено ранее данным «делай». Работа выполнена в действительном Java-пути WKE/Fabric, форма фиксированных следов не менялась.

Реализовано: финальные cap-треугольники выдаются прямо в double[] атрибутов и int[] индексов; AO хранится по индексу в float[]. SnowTriangleBuffer выдаёт исключительные временные буферы, опубликованные GPU-байты владеют отдельной неизменяемой памятью. Граница переиспользует рассчитанное затенение. Повторный одинаковый шаг в normal-apron не пересобирает соседа, если фактический immutable halo не изменился; новый контакт/удаление продолжают пересборку. После первого игрового замера исправлена ёмкость пула: до 512 буферов при прежнем ограничении 16 MiB массивов, малая начальная ёмкость для плоских участков. В тесте 512 одновременных владельцев, 4 прохода: 512 созданий только в первом проходе, 1536 повторных использований, 557056 удержанных байт, без алиасов и старых ссылок.

Итоговый пакет: `build/libs/dark-winter-1.0.1.jar`, 120315460 байт, SHA-256 `f5441158aff639b2a1ac5bdb6319b4f7eca2d8cb903072b4e89fa1e581ac5ce5`. Pin: `build/WKE/snow-v2/candidate/dark-winter-f5441158aff639b2a1ac5bdb6319b4f7eca2d8cb903072b4e89fa1e581ac5ce5.jar`. SnowTriangleBuffer SHA 4d01b65849383bd9b56cc1d8f44c2ef819152b80db34426167ad38560ed43192, SnowSurfaceClient 6acf307c6084640c7e9459a2b70ecb4076577952129f75253fe38007d875cb16. Временная задача архивирования удалена из Gradle.

Проверки: чистая геометрия 11 315 168 assertions PASS; финальная сборка и exact-JAR SDK job `91191fa98d7d4c729b181ac16a761f50` exit 0. 902592 байта / 18804 вершины / 6268 треугольников / 4 владельца побайтово совпадают с объектным производителем. Cold/warm, serial/parallel, добавление/удаление, halo, погода, shared walls, неизменный normal-apron и 257 resident chunks проверены. Максимальная ошибка действительного построителя 6.18851 мм при лимите 6.25 мм. Переупаковка не означает изменение визуальной формы.

Точный offline replay одного записанного участка, одинаковый fixture SHA 3417c03c…: baseline cacef23b… против final f5441158…; пять измеренных пар после пяти прогревов. Медиана cold 58.9155 → 58.7917 мс, warm 10.3620 → 8.9879 мс; первый след cold 9.4959 → 8.1351 мс, warm 2.6707 → 2.1844 мс. Это стоимость производителя, не FPS. Baseline replay успешно завершил подзадачу в job 91744c…; вторая вложенная GradleBuild того же пути была отвергнута Gradle, поэтому final replay отдельно выполнен job `5982268b109344c99b79d93dbec6be2b` exit 0.

Игровые копии одного pin, все 1435 исходных файлов совпадают, сценарий/SDK одинаковы, точные траектории и контакты отличаются. Baseline job `fd999ea82b8e419ead3f4603bc417127`, run `C:/workspace/DarkWinter-snow-storage/run/placement-surfaces-20261112-110327-713`. Final job `cfadde48107b46989bfdfe8b1a935b34`, run `C:/workspace/DarkWinter-snow-storage/run/placement-surfaces-20261112-111618-931`. Оба exit 1 по строгой проверке dense-ledger: maxLogical 275 / 250 вместо >512; collision oracle parity=true, maxError=0. Порог не ослаблялся. Final 60.0507 м, 522 локальных контакта, 0 неопубликованных. Hot p95 72.9483 → 77.9975 мс, max 164.0547 → 190.5741 мс; цель 25/100 НЕ достигнута. Первый промежуточный кандидат 716f49b… имел p95 75.3359 / max93.3056 — его лучшие числа не выдавать за итоговую сборку.

P95 интервала END_EXTRACTION: свежий путь 71.7241 → 65.4108 мс, обратный 88.6183 → 94.1366 мс. Интервалы включают pacing SDK, не являются обычным FPS. Пик sampled JVM heap по всем фазам 2684011352 → 2989318544 байта; общий расход памяти не снизился. GC 55 → 49 пауз, сумма 493.4891 → 543.7586 мс, максимум38.2884 → 73.3412 мс. Один такой игровой A/B не подтверждает ускорения всего клиента.

Метель: baseline прошёл измеряемый маршрут39.9753 м, но не уложился в бюджет (p95 118.9417, max138.9365 мс). Оба новых игровых прогона остановились ДО измеряемого участка на штатной проверке native patrol: первый «physically traversable», final «unchanged saved-path corridor». Нет сопоставимого финального результата метели, не объявлять её исправленной. Миры штатно сохранились и клиент завершился; windows=[] после проверок. Дневной диагностический кадр final просмотрен; визуальная переработка снега в этой задаче не выполнялась.

F: почти заполнен (на последней проверке ~58.8 MB свободно). Проект и конечный JAR остаются на F. Тестовые копии на C. Удалена только недокопированная папка этого запуска placement-surfaces-20261112-110108-132. Промежуточный JAR716f49b… сначала скопирован с проверкой SHA в `C:/workspace/DarkWinter-snow-storage/artifacts`, затем удалён только его дубликат в candidate; baseline/current и исходный мир сохранены.

Следующая работа по лагам — сокращение холодных вычислений поля/освещения и реальной области пересборки. В final JFR наверху SnowFieldEnvelope.Cache.sample, SnowSurfaceShading.sameSupport, ReliefCache.get, Prepared.depressionMillimetersForMax; переупаковка сама по себе не главный остаточный расход. Для повторной приёмки нужно воспроизводимое прохождение ветрового коридора без ослабления допусков и отдельное покрытие >512. Перенос хранения не выдавать за полное устранение лагов.


## 11.11 — приёмка геометрии перед игровым прогоном

Зафиксированный JAR 87d6fa7a… успешно собран (`e288b7a…`), но **не принят для игрового прогона**. Точный headless SDK (`cea97631…`) подтвердил кеш, математику MAX и порядок ACK; тест формы нашёл 57,325 мм после сшивки в отдельном построителе и 15,681 мм в реальном `canonicalTile`. Первоначальный сбой проверки рёбер оказался ошибкой классификации: осевая граница теперь проверяется точно, ребро с двумя соседями внутри больше не принимается за внешний контур.

Пробная повторная адаптация всех новых треугольников (`c3e3368`, `ab7fcfe8…`) исчерпала бюджет в плотном поле. Более строгий фильтр формы (`4bb0a65`, `b24dc4de…`) удержал работу, но оставил 13,528 мм в чистом тесте и 14,218 мм в настоящем построителе. Следующий кандидат согласованно делит обе стороны длинного общего ребра; качество 6,25 мм и исходная нагрузка теста сохраняются. Сначала чистая и изолированная проверки, затем новая сборка и один игровой прогон при свободном клиенте.

Парное разбиение прошло чистую геометрию (`94e99add…`, максимальная ошибка в целевом примере сшивки 2,888 мм). Точный SDK на 92422e3e… подтвердил все чистые стыки/отверстия и 5,896 мм по 714 574 независимым пробам, но нашёл 8,404 мм внутри чернового треугольника настоящего построителя (`2469e5b…`). Добавляются внутренние пробы только при заметно меняющемся уплотнении.
Погодные проверки и принудительное исчерпание бюджета прошли (`7e3db5bf…`): погодные результаты совпадают точно, запасная сетка из 92 треугольников сохраняет полный контур. Итоговый helper 5ddc6b0b…; SDK проверит ноль срабатываний на обычной колее и в метель. Тест `wkeSnowV2ForestOfflineCandidateChecks` явно зависит от новой упаковки; `…OfflinePinnedChecks` проверяет уже зафиксированный JAR.

**Текущий кандидат, точный JAR принят без игры:** `2261c418c0264b9cb1d7177f03107ee5` exit 0, pin `17f5755df2de4e8e0265b35bb4b440c1fd4cfa87db27e6e584f88331f4c88f5e`. Финальные источники SnowSurfaceClient6406f916…, ConformingSnowMeshe4f0bd89…. Настоящий производитель высоты максимум 6,0411 мм (предел 6,25), два идентичных повтора поля, два треугольника на чистой плоскости, 20 проб закрытия стыка при смещении ревизий на 8 мм внутри и между чанками. Чистая проверка 714 574 независимых проб максимум 5,896 мм. Предохранитель не сработал. Холодная синтетика с шестью глубокими перекрывающимися метками (6454/13639 черновых, 21004 уточнённых треугольников) сама по себе **не замер FPS**.

**Первый игровой прогон `863e7fdd670c4a21b72fad0f1d4b289a` завершился кодом 1 до движения.** Копия `build/run/placement-surfaces-20261111-123535-327` штатно сохранилась. Нет падения движка или доказательства прохождения ветра 30 м/с. После ускоренных двух часов погоды все 9 владельцев маршрута видимы и получены свежие кадры в обоих чанках пути (453/454,111), ACK 9/9, но видимые углы старые: расхождение 258,606→258,226 мм за 40×20 тиков. Фоновый построитель завершил ещё 22 чанка за ~40 с, преимущественно расширение покрытия. `ForestSnowClientTest.awaitPublishedRoute` не разрешил начало движения. Контактный JFR не стартовал, старые записи не относятся к этому тесту. Исправления: приоритет ближней крупной смены поля, отличие неизвестного соседнего столбца от нулевого снега, план только тех границ halo, которые влияют на участок. Чистый тест последнего фильтра d4d3de88… прошёл (`1378d965…`), допуск углов ≤1 мм сохраняется.

**Второй прогон (`35a8b12f701440b5ab85ef347c732573`, pin 68ff7787…)**: точный пакет прошёл 6,0411 мм, исправленная смена поля дошла с первой попытки, расхождение 0,10022 мм, 12 ближних приоритетов. Реальная ходьба: 26 контактов (3 подошвы + 23 канала), 91 видимый владелец; максимум публикации и первой отправки на отрисовку 0 кадров. Оригинальный тест остановлен на устаревшем `subdivisionDepths` (SDK reflection, не сбой игры). Копия `build/run/placement-surfaces-20261111-124407-635` штатно записалась и переоткрылась: 63 канонических/189 core, 26 локальных/78 core, 0 потерь, максимум дефицита 0,1099 мм; collision parity 42 сэмпла, ошибка 0. Полная проверка плотного журнала (>512) и метель не достигнуты. SDK legacy-field/реальные диапазоны поправлены (`cbfdda64…`), пороги времени/геометрии и плотности полного маршрута неизменны.

**Реальная нагрузка ещё высока** в том же прерванном прогоне: 29 синхронных patch за 3,990 с суммарно, худший 316,887 мс, 127 ячеек/190959 треугольников/4,699 млн запросов поля, ноль бюджетных fallback. Собственный JFR `contact-latency/profile.jfr`, отчёт `profile-summary.json`: 112 из 115 render samples в buildLocalContactMesh, 85 в WorkerSampling.relief. Рабочие оптимизации без смены формы: terminal-error callback/не пересчитывать принятую геометрию (`c4c46c2f…`, чистый тест `831b0354…` PASS), reuse точных полей между ячейками и упаковкой, прямой индекс CapFace, быстрый повтор доступа к тому же полю (`SnowFieldEnvelope` af9c829e… ожидает общую проверку). После заморозки клиента/SDK нужно exact package test, затем третий контролируемый прогон. Не показывать FPS как исправленный до замера.

**Третий одиночный прогон `301ece0cca8a48ffa2bf3c35f93195c4`, pin 96f26358…, завершён кодом 1.** Копия `build/run/placement-surfaces-20261111-125504-614`. 58,706 м; 955 локальных контактов (113 подошв, 842 канала), все zero-frame; 2824 видимые отправки zero-frame; точная causal/плотная collision parity >512 пройдена, до 1387 отметок в чанке, 955 видимых образцов сохранились в idle. 51 черновая ячейка не закрылась за240 idle, 71 прогноз ещё активен. P95 hot 1467,98 мс (713), max1838,20; whole-client SDK медианы свежий3 / плотный повтор1 / простой68 FPS, не standalone. 471 patch/2172 ячейки/6,454 млн треугольников/110,88 млн relief queries/238,64 с. Штатная запись; открытие уже плотного мира не готово за1800 client ticks, так что severe30 не достигнут. Нет утверждения о вылете игры.

JFR этого же прогона `contact-latency/profile.jfr`/`profile-summary.json`: 17 522 из22 105 snow render samples в exact envelope sample, 4 158 StrictMath.hypot из прямоугольного rim, 2 330 nextDown от C-aware Evaluation, пауза GC max29,26 мс против секунд CPU. Новый spatial feature lookup client6ed965c1…, независимая математика точного quarter-bin envelope3a6d8383…/tests a7251a7f…, Prepared bounded rounded norm f6ae2040…; новый `wkeSnowV2ForestStormPinnedClient` отдельно сохраняет стартовую реальную колею и гоняет severe30 в той же тестовой копии, без притворного disk reopen. Сохранены пороги0 кадров/6.25мм/25/100мс/погодные пределы; strict полный сценарий остаётся. Чистый/точный пакет `57a4ff0f76604bfc9716f6c5b1155412` запущен, только после PASS и windows0 запускать отдельный storm. Секундную задержку пока не считать исправленной; требуется ограничить область перестроения каждого шага, для этого нужны устойчивые подучастки/диапазоны и общие контуры.

**Последняя зафиксированная сборка `4b86f6cd2130ae9faa362d6c5df41a04bec5ed7c3f2e52af1669224af4077a7d`.** `57a4ff0f76604bfc9716f6c5b1155412` exit 0. Пакет, чистые проверки и точный SDK прошли. Максимальное отклонение действительного построителя 6,0411 мм. На независимом наборе из 5000 записей пространственный индекс сократил количество кандидатов с 22504 до 5008 при совпадении поля. Погодное обновление 14 сценариев, 52833 проверки, нулевое расхождение с полным обходом.

**Отдельный тест метели `a1bd6179d2b54681b1f2e5f21c2ec185` завершился по таймауту Gradle в 12 минут до открытия мира.** Копия `build/run/placement-surfaces-20261111-130849-121`. Журнал остановился после загрузки атласов рендера. Записей входа в мир, ветра 30 м/с и отчёта о маршруте нет. Результат фризов и вылета в метель на последней сборке остаётся непроверенным. Полный предыдущий маршрут продемонстрировал секундные задержки и медленное переоткрытие, несмотря на правильное появление и сохранение следов в контролируемых сценариях. Сокращение числа кандидатов не является замером кадров. Исходную копию рабочего мира не использовали для записи.

## 11.11 — новая переработка снега: повторная колея, щели, метель — предыдущие контрольные точки

ТЕКУЩИЙ ФИНАЛЬНЫЙ CHECKPOINT: mainSnowSurfaceClient3299cd3bc78a2e0a6a9ca9187ce45f78a20a0dd8c4e9402094376d592b9b37a7; compile-only/pin job `e288b7a00fce423eaad519d022d44f6a` running. FIRSTPOLL. SDK finalProbe d3a45dc1/Forest96d7db90/CanonicalTile0225c332/FringeAck76049391, allfrozen. NO GAME LAUNCHED YET. Previous3767247 compile-only PASS (pin42b480...,120161434) beforefinalreview fixes; doNOTtestobsolete42b.

Finaloffline PASS: `fff45cd52fd84afda0ff07b62de94ec2` geometry8272574 inclunpreparedSAT35840/Envelope2422 (newkernelwork1/8/64 histories62/62/62,5000Prepared reused+only1new, exacttol5.68e-14mm), `11843f32774c4feb85d168017a630c0b` sameGeometryold +Surface131180/Dynamics300/Weather52833exact0covererror, `c6b8443489cf490e8fafdbe741407657` host26assert PASS compileJava. Sourcehelpers finalEnvelope92bbad2b, kernel1f446576. Runtime09878c31 and Surface8b98e94a unchanged.

Mainagentfinald8473 changes: cache-backedactualfreshcanonicalroots/pointdelta; stable world1/64AO; bounded 4x4 8MiB copy-only re-pack cadence/single-source skip; fixed3x3x7 LightingField.at context insteadO(N²); nonallocSAT; boundedurgent64 work, asynchronousweather; client16/tick validated sameplacement physicalCover fromcanonicalstrength; fullburial proofonlyaftermatched≤6.25 andlatercompleteframe; capacity512 defersNEW speculative work (countsdropFAIL), alwaysstillrawauthoritytrace, NO eraseold. contact_timing independentreview noblocker. Mainagentcompleted.

Independentgeometryreview caughtandROOTFIXED beforegame (main3299): old upper ACK could hidewholecapwhilewaitinglowerACK; replacement now ownnativeDisplayReady immediately covers suppressedcap; full deps still requiredretirement/bootstrap andremoval waitsall. Mixed old carried foot tile/new fine withinSAMEchunk canopengap; compatible shared edges nowalways close toactualcommonbottom, equalcapsoccludeinternalwalls, bothsame/crosschunk±8mm actualSDKfixture. Normal sampling apron1/64 updates neighbor whoseheightfielddoesn'tchange butnormalgradientdoes. No qualitythreshold changes. Reviewerread-onlyre-review pending. Slightpotential transientoldnativefringe untilLowerACK preferabletohole; don'tclaimatomicnativepairupload.

Aftere288 PASS andpeerreview green, freshRuntimeclient_status no windows required. Run ONE `wkeSnowV2ForestContactLatencyProfilePinnedClient` usinglatestimmutablepin, no sourcechanges mid-run. Actual SDK compares rollingMinecraft.getFps() fresh/return/idle/storm (diagnostic not120FPS promise), strict25/100 final contactbudget, sameframe actualdraw/persistence, separate strongblizzard30m/s repeated≥20m/12mwithin.6m oldcorridor/≥60% afteroriginalsave-reopen proof, realweatherbudget counts32/512/1024, capacityfallback0. Stressnativeconnector excludedfromcoverage. PreserveoriginalFAIL ifany, ordinarysave/close only; noforce-stop withloadedworld. User asked codefirst; proof/reviewdonebeforegame, do not skip actual outcome/imagecheck ifpossible.

Пользователь ЯВНО разрешил «продолжай/исправляй», требует senior skill (прочитан), качественную картинку и оптимизацию по коду ДО игрового прогона. Новые изображения: длинные щели, дырки, острые грани, разные цвета/свет, вертикальные обрывы/белые полосы. Уточнение: static tracks FPS не портят, деградация именно при ходьбе и многократном проходе по той же колее. Ещё critical: strongblizzard+wind30m/s+tracks freeze/crash. Не просить «делай» повторно. НИКАКИХ ИГРОВЫХ ЗАПУСКОВ до offlineproof+review+sourcefreeze. Runtimeclient_status последнийunavailable/windows[] (клиент закрыт); прежний пользовательский клиент не трогали.

Честная архитектура уже сообщена автору: dynamic snow сейчас НАШ Java WKE, CPUmeshingJava; Fabric host всёещё integrated MinecraftServer для state/save. StandaloneDarkWinter.exe v14 реально безJVM/server со своим C++loop120Hz/generation/stream/save, НО dynamicfootprints/pressure/weather snow туда НЕ перенесены (static snow material/collision.dwc). Не выдавать nativeSectionAcks за C++snow solver и не заявлять серверудалёнвFabric. Текущая задача snow correctness/perf, не формальный rename server.

Подтверждённые rootcauses:
- oldpackedcaps repeatedlyrecursive-refine, no coarsen => cost растёт от историческойтесселяции; >16384verts fallback каждыйконтакт.
- independenttri/cell/skirt subdivision => Tjunction nonlinearheight cracks.
- flat pertri normals + perownercell block/sky constants +oldAoSpan/freshAO mixture => facets/colors.
- signed old D>0?D:-R mathematicallydiscontinuous: соседнийbank даёт10.3199mm jump на2nm уshoulder; никакойrefinement этого не чинит.
- static native whiteSOIL_FRINGE_TAG onlowerowner не скрываетсяdynamicmask + skirtbottom>=ownerY, хотя physicalsnowbottom может бытьownerY-.125 => whitebands/holes; capY16 fringe section15 mustalsoACK.
- every20tickweatherALLresidentcolumns+ALLmarks bulk work; root заменяет fairbudget. Pendingpredictions не weather-age => potential512backlog.
- Свежийrun/logs/latest.log заканчивается11:04:34, сразу послеwind30 11:04:10 иstrongblizzard11:04:24, безновогоcrashreport/hs_err. Старые05:06NoSuchMethodError=смешанныеперекомпилированныеклассы; старые03:24/04:15AccessDeniedweather.bin неявляютсядоказательствомсвежегосбоя. Не обвинятьGC/памятьбезфактов; прошлый2клиентаproblem отдельный.

Владение/текущийкод (все Runtime freshSHAedit, NO resets/commits):
- local_mesh owns ALLSnowSurfaceClient +NEW SnowMeshRange: freshcanonical1mowner/face roots instead oldpackedhistory, sharedconformingmesher, immutableCellData.range(source,first,count), source-awareDrawGroup main/shadow, mergepointer-only/nofullchunkcopy, GPUsourceidentityliveness/fence, boundedquiet COPY-only compaction for drawfragmentation. Field D/R/C alreadywired (checkpoint0e63/a66...), oldCommonDominance invocation disabled. NO client edits fromroot.
- review_contact_pipeline ownsNEWpure ConformingSnowMesh (checkpointbcba294f) +ConformingSnowMeshChecks (packagecom.winterkore.engine.surface), freshroots, world1/64boundary+authored endpoints+sharedadaptive1.5mm edge->1/512, closure/sharededge registry; tests independentlytopology/error/repeat/work/cancel. Его child fringe_ownership ownsTestSnowTerrainModels (fc0a6160), hidesonlyselecteddynamicfringe, preservesbody; lowerACK coordinated.
- contact_timing ownsSnowContactRelief continuousFieldAccumulator2969c922 +RuntimecollisionC-aware2pass and independentstateless reference; D=maxDi,R=maxRi,C=max(Di/depth_i), signedD=D-R*(1-C). AnysolecoreC1 preservesfullcut; singlefoot unchanged, singlevehiclecomposition INTENTIONALLYcontinuousnewlaw (oldrectvehicleitselfhasD/rimoverlap). IndividualD/Rkernels+savedledgerunchanged. D-onlyMAX canhideC; thresholdmin(currentD,currentC*kernelDepth) andbreakonlyC1. OwnsexistingSnowSurfaceGeometryChecks latest749fe3de withcontinuous+Conforming+Shading calls. Сейчасread-onlyrootweatherreview.
- snow_boundary_shading ownsNEWpureSnowSurfaceShading<T> a4c6947c +tests1ff4395d, helperworldcanonicalheight onlycompatible rawskin ANDbottom, neveraverage roofs/realsteps, sharedcentralgradientnormals/bilinearworldlight. CoordinatesClient adapter andtargetedlowerfringeACKwithlocal_mesh; no generalClientwritesuntilagreedwindow.
- snow_regressions owns SDK tests/placement-surfaces/**, packedCell(owner) actualsparse ACK-selectedsource nohotflatten, independentD/R/C oracle andnewcanonicalmesherblackbox, repeatwalk/stormvalidationAFTERoriginalidle+diskdurability (stormlegitimatelyfills). OwnNEWpureSnowWeatherBudgetChecks662adc8d; rootwiredinSnowSurfaceChecks23ebd74f. SDKsourceWIP DONOTRUNuntilfreeze.
- ROOT ownsSnowSurface.ageContactsBudget +Runtime fairweather maintenance only (otherscollisionwon'ttouchweather). PureSurface8b98e94a: boundedreversebatch ContactAging(visited,visiblyChanged,removed,completed), onebulk-rangecompaction vsquadraticpermarkremove, exactpercontactweathercursors, noageerase. Runtime09878c31: queue perresidentSurface, coalescedlatestSnapshot,32batches/tick*16cols/32contacts=>512cols/1024marks max, soft2msdeadline +atleastoneprogressbatch, snapshotreplaylossless, refreshimmutablecontactview oncecompletedcycle noteveryagedmark, queuecleanuponevict. PublicweatherMaintenanceMetrics(server) exposes batches/columns/contacts/completedSweeps/elapsedNanos/lastTickNanos/lastTickBatches/Columns/Contacts/pendingSurfaces/dirtyChunkViews. Nohistoryloss/timerdeletion. Needsreview+actualpure/hostcompile.

НЕТ ACTIVEGAME. Pure `a7577c780e3d499fa3585f8406e1cc94` exit0: Weather52833assert/19709visits/14cases/exactcover0err; Surface131180 +Dynamics300; continuous10140max1.14e-13mm; Conforming19625(flat2tris/curved2750,max3.9961mm); Shading6254; allgeometry8236734. 297d/1d6f earlierfixturefailures corrected: actualforget(int), weatherdepth/exposurecrossproduct notsamei%4, workguard exceptiontype, fixturebaseinfluence (NOquality/budgetweakening). Confirmed helper01e75 latest source01:26:35 compiled01:29:55. RootIDE build corefa17345dClient+Runtime09878+Surface8b98 ACTUALPASS problems[]; this is intermediate, notfinalpin or visualacceptance.

NEW root queue checkpointf45e2990 (laterclient local_mesh changes preserved): incomingnew/increased authority max64urgentWORK marks; aged/removal/overflow ONLYdirty/coalescedlatest async; underfoot baseline actualcommittedAuthoritative +urgentpositive +local overlay, notentire agedreadyFrames; commitmergesonlyactuallypatchedcanon. User marksnotcapped/erased, allreadyFrames retained. Releasedmainwindowbacktolocal_mesh. NEED audit compatible oldworkerpublication+latestrequeue underconstantweather and cumulative substep changes vsdisplayedfield. Keep explicitunknownprediction nevereraseTTL/cap; knowncanonicalMark.strength=effective/raw yields exactabsolute cover and physicalreconcile. Lastknownremaining≤6.25+latercompleteframe allowslegitfullburial onlyafterprovedmatch; no explicit wire tombstone yet. If realstormstillunknownbacklog, exact per-stamp nonce/authorityreceipt needed, notguessedidentity. Strong30m/s+snowphysicalfill~.1mm/s, matchesexpectedbeforeburial.

Currentagentcheckpoints:
- Conforming helper final01e75b9ce459f93ff0e5902e1cc5755f71eb9fac4708be569a9b6eae80762319 +test5b455d15; exactcollinearreduction flat2tri, 64bithashbijection preventsedgekeytreebuckets, work/cancelcounts; fringe fc0a6160. Reviewer+fringechild completed.
- Shadinghelperfinalb7f63aad/testd31e86f1; actualrawskin+bottomgroup andcompatibleatquerycrossing, sharednormals/light; mainACK checkpoint8ccb madeHandoff dependonbothcap+lowerfringesection, bothbefore/after andbootstrap dependencies; frozenactualbrightness. Boundaryagentcompleted.
- local_mesh maincheckpoint1a5e3a86 includes canonicalTile bothfine+urgent, authentic genericbilinear skin, allactualfeatureprobes, persegment skirts, sharedworld1/64AO. ImmutableCellData packed ranges + GPUsourceidentity retirement acrossALLlive frames/handoffs/shadow/jobs+3frames. Pointer-only merge, ByteBuffer.mismatch exactsamebytes reuse. NEW SnowMeshCompactor530ba7 copy-only bounded8MiB/oneidlebackgroundjob, 4×4ownerarea ≥8sources, commitrangeidentity protectsnewfeet. Metrics copied/uploaded/reused/sources/drawgroups/repack. Still WIP weatherprediction cover/asynccoalescing + envelope integration/cleanup; NO finalbuildyet.
- contact_timing NEW WKE pure SnowFieldEnvelope checkpoint40c554b9, sharedthread-safe perTileKey<=3Inputversions, exactXZ points+D/C/R+winners, delta kernels onaddition, onlyinvalidwinnercomponentrebuildonremoval, mathoutsidebriefcachelock, oldworker latewarm nofuturefieldreturn, 64MiB conservativeestimatedretention budget inclInput/kernel/point storage. Root flagged needreuseoldPrepared bylogicalKey BEFORE eagerprepare to avoid2048LRUscans andactualdenseN budgetloss, independent highN/repeat/concurrency oracle pending. Runtimeweatherreviewno blocker; 2mssoft nothard. OwnsnewEnvelope+tests, NO moreRuntime edits.
- snow_regressions SDK WIP: sparseactual packedCell(owner), continuousindependent oracle, NEW CanonicalSnowTileChecks/SnowFringeAckChecks plusactualproductiontile reflection; extra severe30stress AFTER originalidle/save-reopenproof, 3short actualsame-pathrepeats; defersEARLYperfonly in stress, final25/100UNCHANGED, budgetcountstrict32/512/1024, no512backlog, afterclear+240idleactive/retired/coarse/fine0. Preserve originalerror/suppressed. Needsfinalfreeze/compilebeforegame.

Next: finishallagents/code reviews, run final pure+Gradlepin once finalsourcefreeze; SDKcompile/independentfixtures first, actualonegame onlywhen confident and Runtimeclient_status confirmsnouserclient. Prefer freshownedcopyrepresentativeoldfurrow+severe30, source untouched. Do notlaunch whileworkinprogress. LatestpreviousJar9698 does NOThave these newfixes; nofinalclaim/screenshotyet.

Skill/toolpolicy: senior/workflowread. Runtime/dw_editSHA reliable, directexecbroken; Node rootEXCLUSIVE(readFSfine, cp shellfailed), agentsNO Node. Agentthreadlimit oldfirst_person couldnotfollowup; new boundary+regressions spawned, now7actives inclfringechild. full-historyagents retainedsameauthorizations. No remoteotherappmessages. ProgressRussian every60sec. Needpersistfinalstatus+reportandgenuineperformance notfuturepromise. Usercoregoal remainsactive; previouspartialfinalNOTclosure.


## Немедленный след под стопой — В РАБОТЕ, 11.11.2026

### Актуальное состояние — 9698c315 собран; сохранность PASS, performance НЕ принят

АКТИВНЫХ ТЕСТОВ НЕТ. NORMAL74ac root intentionally stopped ДО входа в мир: SDK startup stalled10:44:59, ownedlogs NOStartingIntegratedServer/NOplayerlogin/NOadmissionfile. Runtimeclient_status показал одновременно пользовательский foregroundPID30676 с загруженным миром и мойSDK16848 в startup. NodeosRAM15.59GiBtotal/0.25GiBfree. dw_job_stop ТОЛЬКОbridge-owned74ac завершилSDK; повторныйclient_status подтвердилONLYPID30676world stillloaded, freeRAM2.83GiB. Пользовательскую игру не закрывали/не сохраняли/не изменяли черезUI. НЕзапускать новыйпараллельныйSDK, пока пользовательский клиент активен. Этотnormalattempt НЕ даёт ниPASSниFAILмеханики/perf; итогblocked(environment/startup).

Результат пользователя: same-frame pressure+durable footprint/furrow функциональноPASS в2fullнатуральныхлесныхпрогонах62.746м и60.917м, ALLserver+ALLshownlocal exactcores afteridle/reopen0lost/0absent/packedfail0. NOtimer/FIFOerase; толькофизическоезаметание/таяние. Latest9698 отличается от full-tested5a1b толькоMAXinsideEPScorrectness guard; all8158169purechecksPASS. Jar`F:/DarkWinter/build/libs/dark-winter-1.0.1.jar` и immutable9698candidate актуальны. Чтобы текущаяигра загрузилаизменённыеклассы, нужен еёперезапуск пользователем. НЕ объявлятьобщуюperformanceприёмку: profilelatest59.73p95/346.25maxFAIL остаётсяфактом; нормальныйбезJFRblocked. СтатистическиевесаJFRнеравныдоказанномучислубайткаждогокласса.

### История финального normalattempt

Последний productionpin`9698c315e76582b3f1a13598ca75382f467ae23a858414d2043ac3e7c48c8167`,120079996байт, job407c2d9f exit0. Сейчас NORMAL SDK`74ac19a8f59f42dfaf6e874c1b1939ee`, исходные early/final25ms p95/100max безJFR и ослаблений; первымделомpollего. Kernel`b2552d864445d1abb99763ab45e90e48b20ac5e55eb126abc9b0db040c124c9d` fixes MAXplane insideEPS: onlycross>1e-13 canprune, normal8d0 untouched. Clientf690/Memo205/Scratchb3unchanged; SDKProbed11e8fdd +MemoChecks46433/MAXChecks0fc241/ScratchChecks256928 frozen. Puregeometry`4b5d075eee8e4f4fa158e33f0087e311` PASS8158169:34560MAX+960tinyEPSexact(all4sizes, noinsideplanechecks)+1.2Mshoulder. Thin absoluteEPS bug in oldordinary prepared forunphysicaltiny geometry outofscope; newMAXdoesnotworsen.

PROFILE8cc20b32d2ab456ba4f62a4a3c1587f8 (5a1b pin) completednormal save/close exit1 ONLYperf:60.9166m,1092locals0frame/3158draw0frame, idleALLpred/coarse/fine0, BOTHoriginalserver+shown-local allidle/diskLost0/absent0/packedFailures0. New3fixtures PASS:Scratch47assertions/Memo132952assertions(98561keys)/MAX201600+4800 max1.42e-13. Hotp9559.7316/max346.2519ms, JFRactualSTW263.85ms. MAX17.47Mrejects/75.23Mtests; scratch676reuses/604.8MBavoided/4MiBretained; nofallback. Run`build/run/placement-surfaces-20261111-103541-254/`. JFRsummarytasklatest4dfa1fff exit0 nowhasallocationSites. Float14.77GBWEIGHTsite isproductionlocalSkyVisibilitycache.put, BUT actualAOqueries183111/hits157401 cannotallocate14GBFloat; statisticalWEIGHToutlier hypothesis, do NOTclaimrealclassbytes orchasewithoutcounts. SDKread-onlyauditnoDebugVertexbuilding/nofull4096diagpertick; noSDKchangesauthorized. Nativegeneric lowpolyterrainand foliage outofscope. FINALNormal74ac important beforeclosure; stillnooverallacceptance.

### Предыдущий этап — 5a1b913c, точные оптимизации после подтверждения сохранности

Новый pin`5a1b913c7d9b72f5f3386601994ff80d41228af5f3a670c0a378f8d8903ec801`,120079984байт, build`c1c21e0349ba4528affe0c773303f275` exit0. Client`f6907110cdc803f2b21c7ce45a57bda865517b245549ada3f6fe31f8f1418a5a`, kernel`89b0b7465ee0fc7f22ee031a10a4132004a334546ce3f9c2beefcdb06843b5b7`, NEWQuadDoubleMemo`205eeb54…`, NEWContactPatchBuffer`b3f3cdfc…`. Совместный IDEbuildPASS. MAXspecialized positive-only exactmajorants q≤1−u² andq≤9/8*(1−u), outwardthresholds+planeslack; normal8d0methods literallyunchanged, rawzero/RIMprioritypreserved. Threadlocal32768slot4doublecanonical-bit memo replacesonlyheights Map<P,P>; numericPonly/generationclear/contextprovenance unchanged. Temporarypatch64MiBboundedThreadLocallease coversBUILD+MERGE; resultownsindependentCPUbuffer, asyncfineallocateunchanged, nesting/oversizefallback andfinallyrelease. IndependentreviewPASS. SDKagent finishes1callfor3focusedfixtures, thenfullPROFILE. Do NOT claimreadyuntilactualnewrun.

JFR880actualGCPhasePause180.9384ms; weightedSnowallocationsHashMapNode1.205GB/P2.365GB/temporaryHeapByteBuffer2.410GB motivate boundedmemo/scratch. P-only hash hypothesis independentlyREJECTED(gridmax6); nohashchange. JFRtasknowseparatesactualSTW andweightedallocationstacks, GradleSHA`b709845b…`, jobc03c11exit0. PriorfunctionalcheckpointLabreport `.darkwinter-lab/reports/20261111-103336-853ac3/report.md` savedwithimmutableevidence. Sourceworld1435manifestfiles independentlyrereadbytes+SHAafter880,0mismatches, receiptinrun/source-save-verified-after-contact.json.

### Проверенный предыдущий этап — b1927b60, SSP bootstrap и сохранность

Кандидат `b1927b600ac6ebd93e06168a5287d9a017c6e55dc903e7d79baf7254075310f2`, 120072805 байт, pin job `c0e0719fa5ea4846b6591c5cf772f388` exit0. SDK PROFILE `880ebbc0cda1422e810a4ad09e75965e` завершился штатно exit1 ТОЛЬКО по performance. Функционально PASS:62.746м,1228locals ALL0frame,3642first-eligible actualdraw ALL0frame,serverUnpublished0. После240idle ALLactive/retired/retained/coarse/fine0. ВСЕ3264server и1228shown-local witnesses послеidle Иdisk-reopen:serverLost0/displayedLost0/absent0/packedFailures0; maxshown deficit0.428мм. Reopenbootstrap300ticks,1cohort0invalidations,CPU/nativeACK9/9, presencegate0missing after100ticks. Runtime42samples perphase exactmaxerror0 withlogical1518>512. Run `build/run/placement-surfaces-20261111-102119-121/`. Оставшийся FAIL hotp9546.1943/max215.2241мс; пороги25/100неослаблены. JFRsummary82fa994 exit0, render677buildContactPatch/524subdivide/389relief samples, leaf113Prepareddepression+95edgeDistance+71HashMap.get+43TreeNode.put. Сейчасlocal_mesh/contact_timing делают proved MAX-threshold culling, SDKagent независимую parityfixture; reviewer read-onlyP hashdistribution. Следы функциональносохраняются, производительностьещёнеготова.

Текущие frozen production: Runtime `079a4c88…`, Client `edc91855…`, Sender `01d16e8d…`, kernel `8d0ed0a0…`; SDK Probe `9ae9839f…`, Forest `d3297a47…`, collision parity `d798355a…`. Добавлены точный spatial/prepared cache native collision с прежней арифметикой и независимым fullscan reference; сохранение coherent initial9 до CPU commit; shared sourceRevision initial9; SSP retry с monotone token и ACK только реального CPU/native owner commit; история32 полностью отправленных cohort для delayed ACK. Обычные updates удерживаются только SSP owner до ACK; remote прежний путь. Native guards не обходились.

Последний завершённый PROFILE `715afe0c48674ceea0e05530b4afc141` (20a42 pin) прошёл59.96м и idle retention, но FAIL hotp95≈49.56/max237.76мс и реальный reopen bootstrap deadlock: initial9 терялась за busy worker. Run `build/run/placement-surfaces-20261111-094046-038/`, штатный save/close. Это НЕ приёмка. Предыдущий 1a7 full58.347м:1150locals ALL0frame/no missing, после240idle active/retired/retained/coarse/finePending0; обе группы pressure idle serverLost0/displayLost0, disk serverLost0, но visible cold missing. Порог p95≤25/max100мс остаётся.

Pure verification `a9a4b41c356d4fc89eea4d4b60c47f30`: Surface78347/Dynamics300/Geometry8038751 PASS, включая1207500 shoulder±ULP и смешанный MAX/rim знак, max3.01866e-20мм. Финальный bootstrap/cache нужен реальный SDK; при FAIL сохранить исходную ошибку и обычный save/reopen. Все тестовые миры owned copies, без телепортации/изменения terrain. Shell недоступен, Runtime надёжен; Node root-exclusive. Пользователь явно разрешил продолжение и требует сохранности следов, нового согласования не нужно.

### Предыдущий этап — 1a7ae0 pin

CURRENT candidate`1a7ae0d374038ea53a23bbae49a2226ecca814b9235c23de095b4411e682a789`,120048131байт, build5f9d85883f264239a9a71e2fe6103b4d exit0. СейчасSDKPROFILEjob`eb5fd9f9992443298bc297edd2577b97` (начатпослеhelperfreeze66488); ОНПЕРВЫМДЕЛОМДОЛЖЕНПРОВЕРИТЬматематикуnewconstantcore, затемполныймаршрут/idle/save-reopen/realshownlocalproof. Не объявлять готовность доactualpass. SDKProbe`66488aedce0c2da58ca963cfee9dc858ec6b7d1d6cbbb5052c0d78ca7e8c1df7`,ForestTest`451a3dad...`. Новая600case/~14.5ktrianglesconstantcoreparity проверяетtrue shortcut на10barycentric old/newstatelessMAX/5profiles/10yaw/negative+near30M/3scales, tol1e-9мм; не меняетперф/погоду/давление.

CURRENTprod: SnowSurfaceClient`ff81b360e0da82e237a7341e57f8e7f5fe093ef6b8823508f2da41b2a5ed5336`: MAX6/base4/features9+rawshownMarkdiagnostics+exactdominantflatcore retain shortcut. Условие: обаcellGLOBALmaxD exactequal>0 включаяvehicles,oldpacked3depth==D, old+newFOOTsole eachcontainwholetriangleviaactualconvexhalfplanes; retainbytes/normals/UV/AO/depth. Generic/rootDraftunchanged. ДиагностикаretainedCoreTriangles/coreProofChecks, полезностьтолькопометрикам. Sender`69fc695f...` sameasbelow. Runtime`4b0b0915f09d0beeebc5f57a6e59ef45a3cd370b357e868fb86f44199bc8aab7`,Surface`f0a709eef06ac079b78285c296f04a53f8cda63182b8c08eb6797f602a5c9099`: ДОПОЛНИТЕЛЬНОкreceiver теперьpressinheritберётlive3x3residentledgers ownerfilteredс1mindex API Predicate, неcontactViews(staleвbatch), capped max(raw,GLOBALpressure). Surfaceappendиндексincremental, неO(N²). Puretest`d50871848cf3d3dc0229c586e9aefb7d1d6ccb1aa7f0b43c91cea52ba56eeead`:±16seams+expandednewtoe+samebatch+roof+65×49referencegrid+weather/reopen. Job`f7a651f5473248d987854c92d6c7aa04` PASS78347surface+300dynamics+3208744geometry.

Предыдущий40ab3549...120042448 JAR PROFILEjob5e5f3deb6d6441509113481b0b69c3c0 дал47.767м/3chunks/5seams/1035locals ALL0frame/nodrops, receiver1043received=1043stamped/rejected0/budget0. Показанныепотерипослеreopen7cores events94/882/887/935 ВСЕточноeastofx7264/7280chunkseams (например228.925→197.965мм). RootcauseПОКОДУ: clientGLOBALhalo inheritsdepth, serverpressContactownchunk-only; новыйglobalhelperисправляетреальныйдефект, критерийНЕослаблять. ПредыдущийhelpernavтакжеимелотдельнуюошибкуnoCollision(futureBody)безSnowCollisionQueryscope, провалилсяпосле47.7м; исправленnativefuturebodyquery(scope), min20out+10return/реальныепрепятствиянеизменны. Rawrun`build/run/placement-surfaces-20261111-090111-267/`. ФизическийsourceclampгипотезуSDKнеподменял, исходныеметкиdepthсохранены.

Perfэтого40ab:hotp9573.3727/max92.0758мс,scan8.686s/patch10.846s, pack.704s,3405ktris scanned/2050krefined,9.02Mreliefqueries/202.8Mcandidates. Activepred27,canonicalRetire1012,unconfirmedPastTTL7(именноseams),no512backlog. JFRrender1021samples:buildPatch700/subdivide591/compact466/reliefError423, AOgone. CPUshortcut ff81 целитflatplateauпротивповторногосканирования. GCwholecollections600msнеSTW; largestactualpause47.74ms. ReadonlyfutureideasНЕвнедрятьбезметрик: exactfieldmetadatarebindexcludingYafteraffectingpossibleONLYexactkerneldepth equality; <.5mmrebindlosesbakedbaseline. ClientXZfloatcanonicalizationmustpreserveRAWphysicalowner atinternalblockedge. RuntimecollisionstillcontactViewsfullscan/statelessandint+floatworldcentreprecisionbug; contactagentоценилpreparedsnapshotindexноКОДНЕписал. Don'tmixunprovenoptimizations.

Root JFRsummarytasknowfixedconfigurationcaptures (no executionproject/layoutmodel), gradleSHA`dc9c710572a0d48c34bf1f3d121b3bc4b961ea89b6dff4fa6a678cbbf333f9c7`, latestbuild5f9 ranpin+summaryexit0/no2cachewarnings. Task`wkeSnowV2SummarizeLatestContactProfile` writes latestownedprofile-summary.json afterSDKends, purestreamingnoNode. Runtimealwaysstable; shellfailed8007000F,Nodeintermittent, rootexclusiveonly. NeedactuallatestSDKresult+newJFRsummaryifstillperfFAIL. Следыдолжныоставаться:rootuserlastexplicitcontinue+images, неaskapprovalagain. FinalLabreportещёнесохранён, ниоднакандидатнепринят.

### Предыдущий этап — actual FootPlant bridge/MAX6

Продакшн1056d8e64347b17f9b6cd4639de42129321369585877bcce5e69c9928e3990bd,120031130байт, pin2c730exit0 был проверен нормальнымSDK1ec8a6be7a3845708becdb4a50fc4462:15.699м/100ticks,413locals all0frame/no missing, ноCPUoutlier165.7825мс (обычныйp9526.811;hotтолько5samplesp9519.188). Ordinarysave/reopen послеэтогоFAIL реальнопроверил786servermarks/2358cores:0server/displaylost,8packedsourceвыборокfailures0,qualityerrors<.003мм. ЭтоПОКАНЕдоказываетсохранностьКЛИЕНТСКОЙпоказаннойглубины. Rawrun`build/run/placement-surfaces-20261111-084338-574/`. Paging1301/3pages/44assertionsPASS. Pureactualsurfacejob74b540PASS2620+Dynamics300, geometry3d59PASS3.208M.

СледующийPROFILE95d4f9999ab9459f8d6573dbfdf4192a натомже1056FAILраньшеиз-загеометрии: event59channelowner7262,75,1781 depth44.776мм,packederror7.238>6.25,cell1851verts. Rawrun`build/run/placement-surfaces-20261111-084601-719/`. TargetНЕослаблять. local_mesh восстановилLOCAL_MAX_DEPTH6 (base4/features9/target6неизменны), добавилactualshownMarkfieldsвevents; текущийSnowSurfaceClient`756d8847ce773b5e87013927034f9ee8b3defcb84e7f3f091e0b6fcfd89b4fa1` IDEPASS/frozen.

Важныйреальныйrootcauseпоказанногопровала:219unconfirmed из421events,56core/163contouredge,oldestsource134.45мм vscanonical69.61мм, неочередь (canonicalcheckmax3.23мс). LocalMark ONLYmax(raw,existing), не добавляетweight/compaction. НужносохранитьоригинальнуюrenderedFootPlant(soleY), т.к.server20Hzposeнеравенrenderpartialpose. ПринятоSINGLEPLAYERbridge: clientacceptedrawplants->immutableRuntime.RenderedFootTrace(x,soleY,z,yaw,width,length,channel), batch8/server.execute; receiverownUUID/entityId/dimension/recentrealbody/equipment/loadedhostvalidator, forcedloadingfalse/serverprofile+realdepthcap; serverWalkerоставляетединственныйstanceweight. НЕпередаватьMark.y(онsnowtop), неослаблятьwitness/TTL, неотбрасывать219. First_personOWNS SnowLocalContacts sender`69fc695f119e2ff43df34cbf7c5b27018dd63e0d18ad725c0b183cb14ebc95f8`: processmonotonicseq, preenqueueonlysessionguard, callbackcapturedserver/UUID/entityId/dim, queuedlasttraceнепадаетнаbindnull, batchesloopall. Contact_timing OWNS Runtime receiver сейчасвработе, затемpureactualrenderdepthregression. Neednextpure+pin+SDKпослеfreeze.

SDKlatency_pipelineCURRENTForestProbe`8da9064f924af11aae8abd97e732fad2dbcd78dead68260c644eb88661264974`,ForestTest`451a3dad...`: watermarkpost-state+compactsummary+serveroriginalANDallshown-localMarkcores separatelyidle/reopenfloor=max(original-6.25,.5original), actualpackedfirst/mid/lastTRUElocal sourcepoints (nonearestserver substitute), bothsourcereportsattemptedonfailure. RawshownMarkincludesinheritedmaxdepth. Helpersfrozen,norealcompileпослеэтихновыхправокещё. KeepfailuresFAILwhileordinarysave/reopendiagnosticsrun. Coldreadinessafterreopenvanillawait+100ticks only; reportsabsentmeshseparately.

RootдобавилreusableJFRstreamsummarytaskв`WKE/surface/surface.gradle`,SHA`b44099ce606898aab529d849c9cb550c2b52c4f84ec693452781982bf6cd23b7`; task`wkeSnowV2SummarizeLatestContactProfile`. Первыйзапуск40f26FAILconfigurationcache; taskmarkednotCompatible, повтор765041d389374a12831f3f5ee2e2725cсейчас. ОнаНЕкомпилирует/запускаетигру, пишетprofile-summary.jsonlatestownedrun. Node/shellпериодическинедоступны(shellnowCreateProcessSecurityEnvironment8007000F), Runtimeстабилен. НуженполныйпрофильпослеMAX6+bridgeпередфинальнойприёмкой. Пользовательждётреальнойсохранности+подногой, продолжениеявноразрешено.

### Предыдущий этап — 101e ledger/paging pin

Новый immutable pin`101e3a784d88ded41229cd039f69caf77d3099001bf99fbaeeebfdea58ef0294`,120030903байт, buildjob`2055a30acb9845e0a856f197925cf597` exit0. Client`c916f99f92730b88337015710a42d74c44c510e11f9d686b769738f42bd5c729` TTLdiagnostic/no spatialerase/cachedactualcanonicalprofilepressurewitness6.25мм/16checkspertickroundrobin/seamXZguard; capacity512cannotdropuncoveredpressure, explicitbacklogerror+8oldestfailedwitnesssummaryifallunconfirmed. Runtime`8478bdc6bfc9e23ab6d9f5c2c754b60e5fb997560c0ea446a09dde38fb1b49fd` uncappedlogicalfields/frozenpagecursor2packetspertick1.5мс/SentFramecommitlastpage. Surface`c938564c2f09dc08a5707c49ebcf053f1521413de08f7a947d367f16942d259d` noFIFO/exactplacementmerge/noageheal/1mconservativepressurebins. Store`934b51328aaa225799d5b62b3d91a649a07cf38d8714b541cfae400666b1d88b` v6countInt/CRCatomic/fullledger/legacyv5effective-depthbakedintocover. Payload`b0d020079182d519a84778ac039c1fdd8749589de1abedb4f219c3699d22a7c6`,Inbox`2a01f3e0195c273e6f73e8d66290acae223f159f49d08c9f910d8f34bcf92b0c`: atomiccompletepaging512/page0columns+halo. IDEcompile/reviewsPASS. NewSDKpagingfixture`SnowContactPagingChecks.java`92992f8f...1301records+outoforder+duplicates+codecinvalid513 ready, needrealSDKrun.

Puregeometryjob`3d59b86049254ba894f0beb4321b7e94` PASS3.208M, НО он НЕ запускает SnowSurfaceChecks. Отдельный `wkeVerifySurface` job019b25 сначалаFAIL наустаревшейпроверке age-expire. Tests обновлены по новому поведению: SurfaceChecks`27d63fd...`641savedcontacts/allcentres/200001calm/reopen/v5agedmigration; DynamicsChecks`94d944711fdfc97b77647ad69ec7dd8d04f676ed2094a8b089d31a7f7f82059f` calmretention+actualsnow/wind/thawfill. Сейчасactualsurfaceverifyjob`74b5406961024093ba84993b01ba7175`. Pipelineagent обнаружился interrupted, возобновлён: предыдущий helper102bca14watermark+compactsummary COMPLETE, заканчиваетpaginginvoke+idle/save-reopenpressurewitnesses. НуженSDKroute сейчаспослеегоfreeze. Не объявлять готовность: новыйclientможетуперетьсяв512uncanonicalposes, actualrun долженпроверить. Contingencyreadonlyresearchfirst_person: server20Hz≠renderpose; если canonicalfieldнепринимаетactualshownpressure, singleplayerimmutableFootPlanttraceможнопередатьserver.execute сUUID/session/hostvalidator, Mark.yнеподходит(ужеsnowtop); кодаэтогомостанет.

### Причины и принятое решение о сохранении

Пользователь 11.11 добавил обязательное замечание: колея/следы сейчас исчезают, должны оставаться. Продолжение явно разрешено, не просить «делай» снова. Найдены реальные erase paths: SnowSurface.MAX_CONTACTS128+removeFirst, Runtimevisible128/transport512 truncation; nearby merge переносит старый kernel к новой ноге; clientTTL200/cap512/spatialmerge удаляют ещё не подтверждённое давление. Есть и age-only fade144000tick даже приcoldcalm, убираетсяв пользу реального weathercover. Поэтому dc804 НЕпринимается как готовый.

Принято минимальное lossless решение: canonical Contact ledger без удаления по общему количеству, обычное chunk unload/save; Storev6 countInt/CRCatomic с backwardv2-v5 migration без восстановлениястаройage-wornглубины; точные duplicateplacements можнообъединять, движущиеся реальные placements сохраняются. Сетевой512 становится размеромпакета: pages(logicalChunk), page0 columns+halo, прочиеmarks; Inbox собирает revision целиком и до завершения сохраняет старый complete field. Owner/ACK остаётся. Client retirement только после cached canonical actualdisplayed pressure witness, TTL диагностический, не стирающий. Capacity cannotdiscard uncovered realpressure. Root выбрал реализацию, agents выполняют.

Владение: contact_timing — SnowSurface/Store/Runtime+pure persistence tests; reviewer — ChunkPayload/Inbox; local_mesh — SnowSurfaceClient handover; latency_pipeline — ForestProbe post-state timeline watermark+compactsummary+retention witnesses idle/save-reopen; first_person_sole — новый независимый paging/codec SDKhelper. Все Runtime-only, Node доступ периодически исчезает; rootэксклюзивный Node. После freeze обязательно новыйpin+pure+SDK, >128/512 retention, погода, save/reopen, actual0frame+perf. Нельзя заменить решение увеличением cap или клиентским вечным прогнозом.

### Кандидат dc804 до замечания о сохранении

Кандидат `dc80421940bb362c5aac6a2039f586156182aeca24574809bceaf4421a7b0f1b`,120015489байт, pinjob`c5b3644fbea54a62af75f83b7046e960` exit0. SnowSurfaceClient`152c4cc49e8878fc8ba147603f9299d41ad15814e9d8281e2c7f586facb1633f`: draft AO наследуется только от точного исходного треугольника (истинные новые геометрия/нормали сразу, новый horizon AO после fine); fallback к authored roots теперь отдельно на клетку. Раннее cooperative-cancel только заведомо непубликуемого retired snapshot, quiet fine keys повторно удерживаются в dirty, cold fineQueueStates объясняет задержки. Actual-cell samePressureField предотвращает переподразбиение/сброс fine при том же поле; старые CellData остаются основанием ремонта удалённого прогноза. IDEcompilePASS, игровой convergence/perf ещё НЕ проверены. SnowContactRelief`0e351bd3a51eae1885361cb2b6857288f70cd652602be99b7107607f5fc6f8a2`: точный центральный rectangle shortcut с однократной проверкой всех четырёх углов по всем рёбрам. Purejob`c401dd0e79da48c08f0b97f021edde0b` PASS3,208,744assertions,core97200+extreme945maxerror0,exterior838400max3.41e-13мм. Runtime`6e0f00fc...` agingurgency проверен вторым агентом: фоновые depth/strength/frameSignature сохраняются, новое давление home+halo срочное. SDK дорабатывает корректное доказательство superseded producer pressure по post-producer timeline watermark, без изменения20step/geometry/perf порогов; exact ephemeralID может законно исчезнуть до снимка. Нужен fresh complete SDK; не объявлять приёмку/готовность.

### Предыдущий профиль d32

Последний pin `d32c0298883ed2d59778c3b4d455e7fdea02149b3ebdf28078d9d8e005e799c7`,120011126байт. Profile job`2fe907898b884020847e78a3202c7cee` завершился FAIL:54.886м,1277местных route-контактов, все опубликованы в том же кадре/тике, пропусков0; после240idle active/retired/retainedPredictedMarks0, но15клеток ещё coarse/finePending. Hotp9564.6154/max84.5747мс приJFR. Server8unpublished (никогда не наблюдались в установленном readyFrame), наблюдавшиеся0unpublished, serverpubp952/max246clientsteps. Источник receipt:`build/run/placement-surfaces-20261111-075531-767/contact-latency/all-contact-latency.json`. ЭтоНЕприёмка. Runtime aging urgency исправлен вSHA`6e0f00fc44dd84ae814c3252c6e788c9a6c26042db33818cb19605e3b6ed3ea6`: плановое старение не занимаетurgentcounter нового давления, backgroundsignature20тиков иhome+halo сохранены. IDEcompilePASS. local_mesh исправляет convergence, reviewer — повторные плотные локальные rebuild. Node/host environment сейчас unavailable, Runtime read/edit/Gradle доступны. Порогов не ослаблять.

### История предыдущих этапов

Текущий исходник SnowSurfaceClient: `9bed5eec73c0d0b09ce03f467b0f665201d8814892bee65fcc7cc17a2e08a2df`. Feature refinement использует настоящий контур подошвы, середины рёбер, внутренние кольца, оси и пересечения с границами клеток; обычный draft MAX5, только треугольники с доказанной выборочной ошибкой у feature могут уточняться до9. Сохраняются физика, fine9 и критерий packed geometry6.25мм. Статический обзор и IDE-сборка пройдены. Добавлена очередь окончательного уточнения: три quiet-fine задачи, затем одна обычная, с FIFO внутри групп. Сейчас production pin job `56379bed83b046388e342ba65369a795`; после pin нужен полный SDK. Agent latency_pipeline ещё добавляет наблюдение реально установленных readyFrame и дополнительное строгое доказательство canonical subset под локальными прогнозами; пороги не ослаблять.

Проверенный минимальный MAX6 вариант: JAR `eca7433725c073d92b3032dbc5a6160d7dceddf594b296ec806232d3679b57ae`,120000660байт, pin `2f265977e49b4f39a24e99b5bb200a03` exit0. Profile job `bc8addfd7ea24cc2adefa13335a9a945` прошёл55.09м, все1166местных событий опубликованы в том же кадре, пропусков0. После240idle activePredictions=0, retiredPredictions=0, retainedPredictedMarks=0 — ghost repair реально сработал. Однако12клеток не успели fine, hotp95=70.541/max120.635мс приJFR,13server states не показаны, maxserverpub33тика. Это FAIL и НЕ финальная приёмка. Raw: `build/run/placement-surfaces-20261111-074055-077/contact-latency/all-contact-latency.json`. Packed sole/channel errors<.007мм, duplicate caps0. Новая feature-сетка и quiet-priority отвечают именно на этот результат.

Простой MAX5 без features был ОТВЕРГНУТ по реальной геометрии: JAR `b63d3e97d4c464a14005b89319f446d4c5819985655a2d99d257d4daa8184d6b`, профиль `7ae7aef7c9a649119e827f7297ecbb1a`, raw `build/run/placement-surfaces-20261111-073205-785/`: короткая борозда pressure13.135мм получила packed error9.0448мм>6.25. Нельзя принимать только по хорошему времени13мс. Feature refinement должен исправить эту потерю точности, а не поднимать допуск.

Ghost source checkpoint `7dcf186b2fed4b6f6e4b9c183f2b065e5bb0d4cb4ae4ec4604a4d102514265c3` включает: retirement ставит pressure repair в bounded очередь; удаление выбирает старые CellData по changed keys даже при contactMatches=false; metadata-only Mesh делит тот же data object. CPU pool учитывает publishedData identity у ВСЕХ живых aliases (frame/handoff/shadow/job), поэтому не копирует весь чанк и не переиспользует живые bytes. Проверено вторым агентом. Root добавил физическую толщину/общий cap, guard против старого deeper prediction, boolean handled и один patch за кадр. Server adaptive sweep `4c982...` остаётся. Все файлы не коммитились; сохранять чужую грязную ветку.

Инфраструктура: запуск `78a310...` завис ДО создания мира в Fabric gametest Phaser/Semaphore (подтверждено jcmd2140, user.dir owned copy071646). После force-stop только этого bridge job оставался daemon16028 и nested cache lock; повтор `f389...` отказал с already locked. Проверено отсутствие Minecraft JVM; ровно принадлежащий застрявшему SDK daemon16028 штатно завершён временным Java attach helper с проверкой PID/mainclass, затем запуск7ae прошёл. Helper только в Temp, проектный код для recovery не добавлялся. Обычный shell всё ещё не запускается: invalid Windows sandbox URI file:///workspace. Node использовать только root; agents Runtime. Не повторять force-stop с открытым миром.

**Последний checkpoint, приоритет над историей ниже:** готовится новый productionpin; прошлый297bd527bcc542d2bcf4dc47a080c2e1 (JAR2f80c42f0e1b3403aaecb7f9ff602c8b376ebb2bf00a38b3f7587eca62502733,119906183байт) прошёл51.975м иidle, ноFAIL:1390locals allpublished0frames/missing0,3620owner-firsteligible-draw0frames; после240idle active/coarse/finePending0, но29retired/retainedghosts. Hotp9549.7818/max101.5128мс приJFR, server4late195–336ticks+1unpublished. Rawbuild/run/placement-surfaces-20261111-064714-802/. ЭтоНЕготово/НЕприёмка. НовыйLightingSampleParity7200samples/3760ownerSelections maxerror0 прошёл; FP/TP3.589Mvertices опятьPASS.

Новые ЕЩЁ НЕ ПРОВЕРЕННЫЕ исходники: SnowSurfaceClient0062be9fbd6fc60dde21edc4ff148ce5e3a319e54a32385ede11b9dfe385f5c8 (послеghostba231997; localmaxdepth5вместо6, actualtargeterror/packedtestsнеизменны, fullfine9; localpressurephysical теперьcolumn.layers*125+delta иfinalcapmin750/physical*.9 послеinherit; non-authoritativeoverdeepworkerнеперезаписываетновыйshalloweractive). SnowLocalContacts35a686ce7b182705a6d1ee6bdbabf930c3440f44067c99202b67e6c9944c5c34 возвращаетbooleanhandled; ENDflushfallbackтолькобезlocalstage, retirementпосленовойпубликациидренируетсяследующимкадром, ане2rebuild/frame. Ghostagent ещёпроверяетremovalselection: старыйCellData.contacts требуеточисткидажеприcurrentcontactMatches=false; metadata-onlyglobalghostsпочиненыбезудаленияреальныхsavedследов. ColdretiredContactDiagnostics разделяетglobal-only/actualdisplayedcells/savedauthority/snapshots.

PlayerSnowFootprints4c982306712c4fbb526b08ef3fd20e7d2048cd0c832bddeb590d6faff656288c: adaptiveSERVER1..8subtick sharedpose+bodyXYZ/yaw curvedsweep, timeline/equipmentonce, loadingstancebinaryonce, continuousloadingfalse. Clientостаётсяexactrenderframe. IDEcompilePASS, игровойрезультатpending. SnowSurface.storageнепереписывался; проверитьсохранностьреальнойпродавленнойколеи(существующийnearbymergeможетдвигатьконтакт).

SnowContactRelief0e7f2043ce6b51cf40b08e3ef27dbd0064c9f6bb0c69c6891606ccdaa93bf4ac: exactconvexoutsideedgepruning, purejobd8cef51a9d3b44cd9f9de6881250aeff PASS3012454+volume81741+wading6020;838400newexteriorpointsmaxerror3.4106e-13мм/tol1e-9. Height-only870912max0м. ПромежуточныйHEIGHT_CACHE c1d3/2b8e4JARОТВЕРГНУТ:skin27Mqueries71%hitsвосновномповторныесобственныевызовы,compacted23%hits,TL/hashрегрессияp9590.875/max245.663(JFR). Cacheполностьюудалён; actualLightingSample передаётужеизмеренныйskinHeightиз5dylookupбезповторногорасчёта;samplingпередаётсяявновAO/geometryloops. Draft-onlyAOorigin1/32м(maxshift22.1мм), actualvertices/collision/fineAOнеизменны.

SDKForestContactLatencyProbe31818ea9d3d4b6803c5669727b109538a0751a50cb87786b595a00d8fb81d2a7: continuoussweptBBrouteplanningловиттонкиестержниспрюса(раньшеendpointstrideпропускалреальныйствол); boundedwallturn/replanбезтелепорта/terrainedit. Общиеминимумы20мout/10мreturn/seamнеослаблены. PROFILE/JFRONLY собираетполныймаршрутбезEARLYperformanceabort; FINALp95<=25/max<=100/min100hotосталисьстрогими. NORMALранниебарьерыпрежние. Следующийшаг: freshpinпослеfreezeghostselection, полныйSDK, реальныеPNG, отчёт. Не закрыватькарточки/необъявлятьFPSилиготовностьпокомпиляции.

Автор потребовал след и колею прямо под ногой без больших задержек и явно разрешил продолжать «делай». Повторное согласование не требуется. Старый результат одного следа за 3 тика/144 мс не является приёмкой.

Подтверждённые причины: серверный Walker ограничивал sweep тремя тиками; местных контактов не было; полные снимки, worker и FIFO задерживали видимость. Дополнительно urgent priority-first мог постоянно вытеснять соседний чанк при бюджете1.5мс, а effectiveContacts брал устаревшие mesh.contacts, скрывал новые серверные контакты и возвращал снятые прогнозы.

Текущая реализация:
- Местное давление берётся из фактической интерполированной позы извлечённого аватара до заморозки снежного кадра. Общий gait/sampler, непрерывное loading=false без повторного уплотнения веса, настоящий stance loading=true сохранён. Первый реальный контакт не ждёт server roundtrip.
- Срочные серверные чанки обрабатываются по старейшему checkedTick, priority используется вторым критерием; лимиты2кадра/тик и1.5мс сохранены. Клиент начинает давление из latest readyFrame, а не presentation history. Пришедшие contact deltas применяются один раз перед END_EXTRACTION; после публикации старого worker актуальная дельта применяется до видимого кадра. Native ownership/ACK не обходятся.
- Incremental draft сохраняет готовые треугольники вне изменённого давления. Общий local depth4..6, target sampled error6мм; когда затронута историческая fine-топология>6 либо>16384vertices/cell, draft восстанавливается от authored roots. Фоновый builder остаётся depth9/error1.5мм. Глобальная ошибка<=6мм НЕ заявлена: на стыках ранее измерялось20–21мм даже наdepth9; фактические packed sole-core witnesses были<.008мм. Retained AO временно сохраняется до fine. После3тихихтиков fine обязательно ставится в очередь, завершение за3тика не обещается.
- Пространственное объединение местных прогнозов использует серверные owner/profile/vehicle иradius.30*min(width,length), сохраняет опубликованное давление. Текущий batch/unpublished/eligible-undrawn не теряются. Hardcap512; offscreen published допускают coalescing. Tombstones защищают от старого worker. contactOwners и affecting используют одинаковый conservative radiusAABB+SAT: прежние SAT-only пустые углы создавали ложных requiredowners.
- Exact Prepared/height-only caches, XZ bins/XYZ lighting field и CPU mesh-buffer pool128MiB сохраняют математику/владение. Кэш не хранит World. Абсолютная глубина подразделения — byte/triangle, не сбрасывается при переносе.
- FP-body теперь использует настоящие TP leg subtrees, нижнюю одежду/обувь и зарегистрированные hulls; прежние торс/руки и инвентарная модель сохранены. View-only torso offset компенсируется у ног. Подтверждён javap реальный внешний AvatarRenderer crouch offset -2/16*scale до Living.submit, компенсация общая. Новый actual-vertex fixture готовится/проверяется; полного визуального совпадения пока не объявлять.

Проверки и честный статус:
- Pure geometry job6692a235332f4583b874d5a2779b03b8 PASS1335654, volume81741, wading6020. Height-only870912samples максимум0м; Prepared максимум1.7053e-13мм при justified tolerance1e-9мм (stateless reference сохранён).
- Промежуточные кандидаты c56627..d782926 и45907..c5f7 НЕ ПРИНЯТЫ. Был исправлен OOM от сброса subdivision depth; лимит heap не маскировал ошибку.
- c5f7cf49.. pooled fullroute54.1876м: hotp9575.965мс, max98.755мс; после240idle fine/coarse/active0, но96retiredghostmarks остались. Это реальный FAIL. Receipt build/run/placement-surfaces-20261111-053819-192/contact-latency/all-contact-latency.json.
- ac319236e0d897239a03888cc5940160e501dc4313154f826fe146f3856d0ddc,119903499байт, pinjobc1af9d19d0cd4cc9860641b3a36783dc exit0. Gamejobc31e8421032a4c9c8259ca3c0d346c5b FAIL по CPU: все616routecontacts published same tick/frame, пропусков0; hotp9571.086мс/max106.805мс. Incremental ещё переносил слишком плотную историческуюfine-сетку; это НЕ финальная приёмка. Receipt и2свежих ночных stationary PNG: build/run/placement-surfaces-20261111-060929-302/. Packed sole/channel cores<.008мм, duplicatecaps0. Старый lower-body fixture211332assertions/1652cases прошёл; новый vertexfixture этим прогоном НЕ проверялся.
- Следующий sourceSnowSurfaceClient47f064aaac1896f3a3185a140576756ce89012a58b6d40ade3f3b22efe8b9b66 вводит hybriddepth6 и timestamp ДО received-pressure flush, чтобы его стоимость не скрывалась из замера. Сейчас pinjobc825ef93516040bfbc2d4ddff50bed25; после него нужен полный игровой сценарий. LowerBodyActionChecks7a54ce31889b5d0ac9609b3d987ab3572bb7ef9e45140f3c3219ce250e1d45ab включаетactualFP/TPvertices17outfits<1мм, IDE compilePASS, игровой результат ещё не получен.

Актуализация следующего этапа:79443c160072038f3b6a2054584c3f6caaed8cf50c8e7e1d73d2319f266d86b6 (119903826байт), pin c825ef.. exit0, test f237080a923846488b0a21cacd76db67 FAILhotp9562.58/max100.423мс на29.57м; все715localspub0frames, missing0. Новый FP/TP fixture ПРОШЁЛ:3589056vertices/4896cases/17outfits, maxerror0.000096858мм приlimit1мм. Bytecode Model.setupAnim/resetPose и Humanoid/PlayerModel подтвердил native legY не переписывается, crouchZ+=4relative соответствуетfixture.

После объединения received+local в одинpatch/frame и draft-onlyAOorigin grid1/64:sourceSnowSurfaceClientf93d6dde128d0eb6a3c86288e6bfc0a16b3f3da3e609e9aa3582a6f4a085b4fd, productionJAR64def97258d410c83d809fd9dfc68f92b158b586beaf1c02538db0ce8fe10ba9 (119904583байт), pinb042880953624473b5a0147efb19be4e exit0. Test8a75f9277e324b1eb10cb8d4e7b0f06b сноваFAIL:39.58м,985locals allsameframe/missing0, hotp9561.9996/max135.3574мс. Rawbuild/run/placement-surfaces-20261111-062549-511/. ThreefreshstationaryPNGs (night+FPlookdown+labelledsame-daynoon) сохраненыпослезамеров. BodygeometryPASS повторён. ИграНЕпринята.

Профиль79443 ...062101-061/contact-latency/profile.jfr: Render793samples, localSkyVisibility316(40%), horizonSamples272(34%), reliefError151(19%), mergeCells33(4%). Новое profile64def job94af54de21d647ad852302226a97bbc8 сейчасработает. Reviewer реализует ТОЧНЫЙ boundedprimitiveheightcache поidentityCellSnapshot+rawworldXZ+skin/compactedtype, generationнаWorkerSampling (неbeginCell), дляlightingCell/horizon. SourceменяетсятолькопослеfreshSHAread, productionpinнужноповторитьпослечекпоинта. ПороговрадиPASSнеослаблять; percellGPUarchitectureоставленапланом, неimplemented.

SDK wkeSnowV2ForestContactLatencyPinnedClient: все local generated→applied same tick/frame; all required-owner draw<=1firsteligibleframe; серверные exact либо доказанное retained3corepressure за20ticks; outward/return>=20м/10м+шов; реальная packed geometry;240idle coarse/fine/predictioncleanup. Performance final p95<=25мс/max<=100мс, минимум100hot events; earlyp95barrier только после1000hot events. Порогов ради PASS не ослаблять. STOP flag <ownedRun>/contact-latency/STOP штатно закрывает мир. Только копии исходного сохранения, manifests проверяют исходныеSHA. FPS не измерялся. Не закрывать снежные карточки; ещё нужна визуальная/глубокая приёмка походки и рыхлого снега.

Практика инструментов: node_repl нельзя вызывать параллельно из агентов — совместные чтения зависали на563–957сек. Root вызывает его один, агенты используют Runtime read/edit/IDE. Все проектные правки через SHA-checked Runtime, не сбрасывать чужую грязную ветку develop/с++.

## Продолжение WinterKore Engine: снег остаётся в работе — 11.11.2026

Этот раздел уточняет статус снега и имеет приоритет над формулировкой «все четыре карточки выполнены» в записи 10.11 ниже. Автор отверг прежнюю форму следов и снега на растительности; последняя команда «делай» уже разрешала продолжить эту задачу. Повторного согласования не требуется. Карточки «Движение по снегу как в RDR2» (2ae19684-9a66-4031-b44b-7fe870ad0087) и «Анимация движения по снегу» (e31fcd13-44b2-4d40-84a4-69f10207433a) переведены в doing. Технический PASS не означает художественную приёмку.

### Восстановленный контекст

- Канонический workshop: tools/darkwinter-workshop/data/workspace.json — 89 задач, 64 документа, 23 раздела; доска 70 узлов и 58 связей. Изучены все названия/статусы, 34 основных описания и связанные документы. Прочитаны исходное объединённое задание 08.11 и дополнение из 13 задач 09.11; все 13 уже присутствуют, повторно импортировать нельзя.
- Визуально просмотрены все 19 изображений, прикреплённых к соответствующим задачам: 5 референсов снега/ходьбы RDR2, 3 биома, 2 примера стыков стен, 9 референсов лесной еды. Сверены старые игровые кадры. Полная лента старого чата через приложение не открылась: чтение актуальных файлов и восстановленной истории не следует описывать как чтение всего чата.
- WKE сейчас встроен в Fabric-мод; отдельная C++-игра отложена. Активная задача — снег, следы, походка и снег на ветках. Рыбалка, охота/мясо, готовка еды и прочие отложенные пункты не добавлялись в объём этой итерации.

### Изменения этой итерации

SnowSurfaceClient ранее требовал одинаковый weatherTick всего набора 3×3 даже для обычного обновления. Серверные соседние чанки обновляются независимо: готовый след мог ждать ещё восемь кадров. Начальный согласованный набор 3×3 сохранён; последующие обновления используют собственный полный halo, а доступные согласованные соседние кадры включаются дополнительно. ACK и передача владения сеткой сохранены.

SnowCollisionSupport.shape и genericShape теперь используют SnowCollisionQuery.footprint(entity). Область назначения уже создавалась vanilla collision query, но игнорировалась при выборе снежной опоры. Вне scope остаётся обычный bounding box. Проверки стен/потолка и сам vanilla collision solver не обходятся.

Существовавшие к началу итерации правки общей походки первого/третьего лица, глубины для стоящего на земле креатива, коротких следов волочения, мягкого осыпания краёв и комков снега на настоящих ветках сохранены. Не приписывать их все этой итерации. Вертикальный ствол остаётся препятствием, а не площадкой для снежных полок.

ForestSnowClientTest усилен: первая новая авторитетная подошва проверяется в фактически отображаемой ACK-selected сетке каждый тик при удержании W, предел ожидания 60 тиков. После физического маршрута записывается ходьба в survival/creative с первым/третьим лицом. Для первого лица проверяются настоящее извлечение и отправка видимого тела с тем же snapshot. Старое требование длинной vehicle-колеи заменено проверкой коротких слабых отпечатков волочения подошвы. Геометрические проверки учитывают мягкую осыпающуюся кромку и низкие разорванные валики; подключены существующие volume-contact, stream-look-ahead и snow-wading checks.

### Проверено на production JAR

- Файл build/libs/dark-winter-1.0.1.jar, 119503889 байт. SHA-256: 28b9dcb43d62fa231ae7a2c8688d5245353726d6d2dee73a7b135f48e31ad126. Неизменяемый кандидат: build/WKE/snow-v2/candidate/dark-winter-28b9dcb43d62fa231ae7a2c8688d5245353726d6d2dee73a7b135f48e31ad126.jar.
- Геометрия поверхности: 464726; объёмный контакт: 81741; снежная анимация: 6020; stream look-ahead выполнен; host geometry/codec: 26. Dynamics 286 и surface 42 прошли до двух последних адресных production-правок, повторный полный запуск этих двух наборов после них не заявляется.
- Финальный Gradle job 52cd63d91f25406cb50dad95dd8458f6, wkeSnowV2ForestSnowPinnedClient: exit code 0, BUILD SUCCESSFUL, completed=true, 34745 утверждений.
- Реальный неизменённый лес в копии сохранения, 200 тиков W, 20.3143 м. 187 grounded samples; максимальное погружение 1.4433 мм, максимальный абсолютный остаток опоры 4.2248 мм, крупных противонаправленных рассогласований 0.
- Первый новый след отображён за 3 клиентских тика / 144.2468 мс во время W. Это измерение одного нового контакта, не процентиль общей задержки. Найдены 83 подошвы и 74 коротких слабых контакта волочения.
- Четыре режима походки прошли; глубина в момент кадров 195.84–279.37 мм. Оба кадра первого лица: extracted=1, submitted=1, bodyStates=1, sameSnapshot=true; flying=false.
- После штатного открытия с диска: глубина 310.353→310.098 мм, 9 контактов выбранного чанка. История послеметельного ветра 12 м/с за 72000 weather ticks: 264.341→200 мм.
- Все 1435 файлов исходного мира совпали по SHA-256. Исходный мир не открывался для записи. До сценария зафиксированы 20 TPS и randomTickSpeed=3, настройки исходного мира не сбрасывались. Для дневных кадров после физического маршрута только в копии выставлен полдень той же календарной даты.
- Клиент завершился штатно: Vulkan created=307, destroyed=307, live=0. FPS не измерялся.

### Доказательства и открытая работа

Полные forest-snow-acceptance.json и forest-walk-raw.json: build/run/placement-surfaces-20261111-025339-995/. В screenshots/ восемь кадров; визуально проверены 0002–0007: след за игроком, четыре режима ходьбы и дневной куст/кроны. Снег на кусте выглядит отдельными небольшими комками на ветвях; прежних квадратных полок на показанном кусте нет. Контуры следа всё ещё заметно гранёные: сходство с референсами не объявлено окончательным.

Далее требуется довести художественную форму рыхлого снега и проверить реальную походку/спринт на нескольких глубинах, включая 0.5–1.5 м. Новый лесной маршрут не заменяет проверку всех лестниц, полублоков, потолков и препятствий. Не закрывать снежные карточки на основании одного PASS и не уходить в следующую механику до завершения снежной задачи.

Отчёт с копиями и SHA-256 доказательств: .darkwinter-lab/reports/20261111-030836-568be1/report.md.


## Поверхности размещения и глубокий снег — 10.11.2026

Работа выполнена по команде автора «делай», включая уточнение о предметах DW на мебели. Четыре карточки workshop: e58f3f7d-ed97-4f15-8917-add7465d5682, 2ae19684-9a66-4031-b44b-7fe870ad0087, 832ccd30-6126-4614-a82a-4054153f33bd, e8617c8a-ef92-4e2c-9546-857d4561083e. Все четыре карточки отмечены выполненными после итоговой приёмки; описания дополнены результатами и границами проверки.

### Поведение

- Подходящий крепёж и мебель DW/Minecraft ставятся к фактической геометрии стен и пола. Общая привязка используется отрисовкой, коллизией, лучом, сохранением, выборкой снежной опоры и дропом. Нативные BlockState, сборки мебели, сундуки и их содержимое сохраняются.
- Обычные предметы DW выкладываются на мебель через присесть + ПКМ: с текущей раскладкой Ctrl + ПКМ, Shift занят бегом. Хранится один настоящий ItemStack с компонентами; основание GROUND-модели находится на столешнице. Служебная рамка выделения не создаёт препятствие для ходьбы, ложную снежную опору или отказ из-за микронеровностей самой столешницы.
- WKE задаёт материал, предел накопления, оседание, сухую ветровую убыль и историю погоды. Обычный покров ограничен диапазоном 20–50 см, локальные наносы — до 1–1,5 м; это пределы накопления, не стартовая толщина. Исходные авторские скосы сохраняются.
- Снег поддерживает камень, дерево, бетон, металл, крыши и мебель. Разные высоты одной колонки хранятся отдельно; отверстия в опоре не заполняются целым квадратом.
- Глубина определяет сопротивление движению, затраты сил и походку. Реальная обувь задаёт контур следа; повторные проходы локально уплотняют снег. Глубокие посадки стоп соединяются бороздами, контакты стареют и заполняются новой метелью.
- Снег, выросший или догруженный вокруг неподвижных ног, больше не оставляет персонажа внутри поверхности. Подъём проходит через обычный vanilla collision result с проверкой всего твёрдого тела и потолка. Если W направлен в дерево, разрешён отдельно проверенный вертикальный выход без продвижения в ствол. Телепорт, отключение коллизии и откат мягкого снега не используются.
- Предыдущая постановка стопы сохраняется через обычные тики без нового пакета перемещения; настоящий простой, действие, разрыв маршрута и воздух обрабатываются отдельно. Именно сброс на каждом нулевом XZ-тике ранее не давал соединить борозду.
- На реальном кадре чрезмерные бортики уменьшены: 18% выкопанного объёма образует видимую кромку, остальное уплотняется. В проверяемой ячейке максимальная кромка около 39 мм, углубление около 207 мм.

### Оптимизация

Исправлен обнаруженный в настоящем мире ConcurrentModificationException: обход access-order residency использует ограниченный снимок ключей. Worker заранее вычисляет константы контактов и повторно использует выборки только в пределах текущей геометрии/ячейки. Деление сетки учитывает повёрнутую ограничивающую область следа и кромки через четыре SAT-оси вместо большого круга вокруг длинной узкой борозды. Высоты и kernel сохранены этой оптимизацией; изменение доли кромки выполнено отдельно и проверено новым целевым объёмом. Transport/halo сохраняют консервативный радиус. Затраты остаются ограниченными; FPS этим не объявляется измеренным.

### Итоговая сборка и проверки

JAR: build/libs/dark-winter-1.0.1.jar, 92954591 байт, SHA-256 8d1ebaecf1d5745992ea039267877ac8dd00178314c128c6b17d1f037b2424cc. Оба игровых SDK загрузили именно один этот зафиксированный production JAR.

- Job 17935abb74eb4f6d9088b893392d78bd: build, package, WKE dynamics — 286, surface — 42, geometry — 446670, host/codec — 26 и полный лесной сценарий — exit 0. Максимальный проверенный сетевой пакет 933916 байт. Геометрические проверки включают эквивалентность подготовленного sampler исходному и сохранение всех ненулевых точек при SAT-culling.
- Forest: 56708 утверждений: новая копия настоящего Test-леса, survival с winter_boots, обычная W-ходьба 200 ticks/20,1268 м с пересечением чанков и упором в дерево. Grounded 193/200, максимальное проникновение 1,982 мм, абсолютное отклонение от опоры 5,253 мм, повторяющихся крупных противоположных отклонений 0. Конечная X-координата 7280,95 осталась у препятствия, Y 75,2188 совпала со снежной опорой.
- В проверенной области 13 реальных подошвенных контактов и 5 борозд. Точный выбранный серверный след найден в отображаемом ACK-selected mesh, без требования остановить фоновые обновления всего мира. Проверены UV, настоящее углубление и поднятая кромка; в выбранной ячейке 18374 треугольника.
- Обычное закрытие/открытие: глубина 421,644→421,554 мм, сохранено 17 контактов. После 1 активного часа сухого ветра 12 м/с проверенный открытый участок уменьшился 264,513→200 мм.
- Job a8efc29f567b41ed812e2bf7298cbb9c: полный placement SDK на том же JAR — exit 0. Проверены нативные lever/torch, DW lamp/mailbox, сундук и его содержимое, стол, обычный canned_corn и отдельный BlockItem canned_meat на столе, ItemFrame, физический луч/коллизия, client mirror, диск и удаление опор. Assertions 113+98+15. Проверка дропа сначала требует единственный предмет рядом с физическим spawn, затем отслеживает ту же UUID/count/components после 120 ticks обычного падения/дрейфа.
- Оба прогона сверили SHA-256 всех 1435 файлов неизменяемого исходного сохранения. Блоки лесного рельефа не переписывались, снежная платформа не создавалась. Сценарий проигрывает историю 2 часов осадков перемоткой game clock; это не заявление о двухчасовой симуляции всей экологии. Измеренная ходьба выполняется обычным W без перемещения координат тестом.
- Клиенты штатно завершились, native Vulkan pipelines live=0. Кадры — настоящий 2560×1440, HIGH. Итоговый ночной вид следов просмотрен непосредственно; обещание полного визуального совпадения с RDR2 не даётся.

### Тики

Во всех девяти run/saves/* файл data/minecraft/game_rules.dat содержит random_tick_speed=3. Live API до изменений SDK: ticksPerSecond=20, frozen=false, stepping=false. Маркер terrain-followup.json отсутствует во всех девяти; пользовательских TPS/time overrides в TestingSettings нет. Календарные metadata содержат dayMultiplier=4.0. Настройки исходных миров не сбрасывались. Ошибка опоры была воспроизведена при 20 TPS, поэтому для её исправления не потребовалось менять тики; исторические несохраняемые команды /tick этим не устанавливаются.

### Доказательства и границы

- build/run/placement-surfaces-20261110-223735-547/forest-snow-acceptance.json и forest-walk-raw.json.
- build/run/placement-surfaces-20261110-224013-923/placement-acceptance.json.
- build/run/placement-surfaces-20261110-223735-547/screenshots/0002_forest-actual-trail-lookback.png — SHA 4539af508e31338638f23243a31f36756c262acf5cd7886afcbd91b00502fc43.
- .darkwinter-lab/jobs/17935abb74eb4f6d9088b893392d78bd.log и a8efc29f567b41ed812e2bf7298cbb9c.log.

Текущие пределы host: 8 опор/колонку, 16 patches/owner, поиск 40 блоков вниз; сложная поверхность одного owner делит физическую толщину между patches. Игровая проверка относится к существующему Test-лесу; остальные материалы дополнительно проверены host/geometry контрактами. Новые сторонние моды не добавлены. Цель 144 FPS при 2560×1440 HIGH не измерена и не подтверждена. Отдельный полный прогон обычной подготовки 2601 чанка вне SDK не проводился. Ранее существовавшие изменения других задач сохранены, сбросов и коммитов в этой работе не выполнялось.

## Раздельные каталоги креатива — 09.11.2026

По команде автора «делай» реализованы отдельные страницы Minecraft и DarkWinter с общим инвентарём игрока. По последнему уточнению обувь и аксессуары объединены с одеждой.

### Реализовано

- Десять собственных категорий DarkWinter зарегистрированы как обычные CreativeModeTab. Используются нативные страницы Fabric: Minecraft — страница 0, все десять категорий DarkWinter — страница 1.
- Категории и поиск каждой страницы показывают только соответствующий каталог. Удалены три callback, ранее добавлявшие DarkWinter в ванильные категории: ModItems, ModBlocks, TestTerrainBlocks. Сами предметы, блоки и их регистрация сохранены.
- Переключение выполняется брендовой кнопкой DarkWinter размером 26×28, выровненной с нативными вкладками; на странице DarkWinter в той же позиции находится возврат в Minecraft. Вкладка сохранённых панелей HOTBAR скрыта на обеих страницах. Стрелки Fabric удаляются через removeWidget.
- Если операторские предметы включены, их вкладка сохраняется на странице Minecraft в свободной верхней позиции.
- Сохранены все 284 текущих уникальных ID, включая параллельно добавленные 40 предметов ассортимента еды и напитков. Заснеженные версии мебели и котелок со снегом/водой остаются в соответствующих категориях: всего 341 отображаемый стек.
- Common API в ModCreativeTabs: tabs(), keys(), isDarkWinterTab(CreativeModeTab), firstTab(), enum Category с id()/key()/translationKey()/iconId(). Старый ключ darkwinter:darkwinter сохранён для категории одежды.
- Новые клиентские классы: CreativeCatalogButton.java в client/inventory и CreativeCatalogScreenMixin.java в mixin/client. Обновлены клиентская конфигурация Mixin и обе локализации ru_ru/en_us.
- Ресурс логотипа darkwinter:textures/gui/creative/darkwinter_logo.png создан imagegen из логотипа автора; прозрачный PNG 1254×1254, SHA-256 331c201fc253d8d18da184297ff572f83734d35bc00d305bd4352322dd6f393a.
- Распределение предметов выполняется одним обходом реестра при инициализации; вкладки используют неизменяемые списки предметов, а при пересборке каталога создают свежие стеки вариантов.

### Состав и проверка категорий

| Категория | Уникальных ID | Стеков с вариантами |
|---|---:|---:|
| Одежда, включая обувь и аксессуары | 77 | 77 |
| Строительство | 16 | 18 |
| Дороги и ограды | 43 | 75 |
| Мебель | 6 | 6 |
| Медицина | 10 | 10 |
| Быт и мастерская | 25 | 25 |
| Еда и поход, включая оба рюкзака | 60 | 63 |
| Улица | 18 | 20 |
| Природа | 24 | 42 |
| Грузы | 5 | 5 |
| **Всего** | **284** | **341** |

### Подтверждённые проверки

- IntelliJ compilation: PASS.
- Финальный последовательный Gradle build: job 2d5f332d315d49f2bd548d3e173d95bc, exit code 0, BUILD SUCCESSFUL, 28 с. Задача test — NO-SOURCE; это не запуск автоматических тестов.
- Реальный клиент в существующем мире DarkWinter - Items: проверены все десять категорий DW с указанными выше счётчиками и все десять проверяемых категорий Minecraft. Во всех проверенных категориях foreignCatalogEntries=0.
- Пустой поиск: Minecraft — 2000 результатов, DarkWinter — 341. Запрос «парка»: Minecraft — 0, DarkWinter — 5. Запрос «алмаз»: Minecraft — 17, DarkWinter — 0.
- Общий инвентарь и курсор: одна зимняя парка сохранена на курсоре при переходе DW→Minecraft, помещена в hotbar и видна как та же единственная вещь на обеих страницах общего Inventory. После проверки парка удалена через штатную корзину, инвентарь возвращён к исходному пустому состоянию.
- Проверка Page Up / Page Down выявила устаревшие результаты поиска при приоритете Mixin 1100. Приоритет исправлен на 900; повторная проверка из DW-поиска «парка» с 5 результатами: Page Up → Minecraft / строительство, 475 результатов; Page Down → DarkWinter / одежда, 77 результатов; foreignCatalogEntries=0.
- Финальные кадры сняты в реальной игре на русском языке, 2560×1440. Английские переводы проверены в JSON; отдельный визуальный прогон EN не выполнялся.

### Финальные кадры

- run/screenshots/lab-captures/20261109-132615-creative-darkwinter-final-637f0383.png — SHA-256 8906eb5548f7c448b48257edb3f6afe51fbe54f108e1d397676aec3e114a34b2.
- run/screenshots/lab-captures/20261109-132621-creative-minecraft-final-2bb443c4.png — SHA-256 07d67a555653b6b2dcd3471e4ad4231ec75596d6d561036eaa816dd82ceb056e.

Предыдущие записи ниже сохранены как история этапов. В частности, прежние замечания об отсутствии окончательной проверки категорий и технических ключах их названий относятся к более раннему запуску.

## Расширение ассортимента еды и напитков — 09.11.2026

По прямому поручению автора «Сделай её, просто добавь предметы» выполнен упрощённый этап задачи wiki `372b4f2b-1abe-4a0f-93db-bf6ad1e4dcce`: 22 продукта и 18 напитков как отдельные обычные предметы, с общей сгенерированной иконкой-заглушкой.

### Реализовано

- Новый `src/main/java/com/dark/winter/registry/ModProvisionItems.java`: 40 уникальных ID, регистрация до формирования категорий креатива. В `DarkWinter.java` добавлен один вызов инициализации, в `ModCreativeTabs.java` — явная принадлежность каталога категории `FOOD_CAMP`.
- 40 item definitions в `src/client/resources/assets/darkwinter/items/` используют одну общую модель `models/item/untextured_item.json` и PNG `textures/item/untextured_item.png`.
- Одна оригинальная сгенерированная иконка вопроса на прозрачном фоне. PNG 1254×1254 RGBA, SHA-256 `87463db27657c79a6e59e4c260549074c10f36c6b1c58fef670bc9456e75064d`. Промпт и происхождение: `docs/art/provision-placeholder-20261109.md`.
- В обеих клиентских локализациях добавлены 40 названий, 40 индивидуальных коротких описаний и название категории «Еда и поход» / “Food & Camping”. Прежние ключи сохранены.
- В wiki зафиксирован выполненный объём добавления предметов; текст и HTML описаний синхронизированы, сохранены ID и связи. Все 40 конечных карточек имеют статус done. После записи новое внешнее редактирование вернуло общей задаче статус doing, а 12 группам todo; эти более свежие статусы сохранены.
- Каталог использует одну общую текстуру/модель и проверку принадлежности по экземпляру предмета; новых обработчиков тиков/кадров нет. FPS не измерялся.

### Проверки

- IntelliJ `build_project`: PASS, problems=[].
- Первый Gradle `build` пересёкся с IDEA build и завершился ошибками отсутствующих client class files. Повторный последовательный `build` без изменений этих исходников: job `4305e980c154491f9cc7fff0d517908c`, exit code 0, BUILD SUCCESSFUL, 28 с. `test` — NO-SOURCE, не запуск тестов.
- Все 40 item JSON и общая модель прочитаны после записи, корректны; обе локализации содержат все новые названия и описания.
- Minecraft 26.3 / Fabric / Java 25 / Vulkan запущен: job `61e1e39f04ae498ba1e67932652c9623`. Лог подтвердил 40 новых регистраций и 60 уникальных предметов в food_camp.
- В существующем мире `DarkWinter - Items` через Tab → Каталог → страница DarkWinter → «Еда и поход» проверены верх и низ списка: все 40 новых русских названий присутствуют, пропусков нет. Категория содержит 63 отображаемых стека с учётом прежних вариантов предметов; `foreignCatalogEntries=0`.
- Общая иконка визуально проверена на двух свежих кадрах 2560×1440. В логе запуска нет ошибки загрузки новой модели/текстуры.
- Мир штатно сохранён, клиент завершился с exit code 0. Предметы из каталога в инвентарь не переносились, блоки в тестовом мире не менялись.

Кадры:

- `run/screenshots/lab-captures/20261109-131007-food-camp-new-assortment-top-9ae61595.png` — SHA-256 `ef6d27f2b6e166f32334988ed1e2a27b42360d531ea98b1849050117d9332c9f`.
- `run/screenshots/lab-captures/20261109-131041-food-camp-new-assortment-drinks-c7bbd010.png` — SHA-256 `9b38eda64052fd73bd01610d563696b4bc3624ed213a7dc7600ebcd8024b9ca7`.

### Границы результата

Пищевая ценность, новая масса, употребление, эффекты, рецепты, частичные порции и индивидуальное оформление не назначались. Это законченный этап добавления ассортимента по уточнению автора. Названия/описания EN проверены по ресурсам; отдельный визуальный просмотр всех карточек Tab и английского UI не выполнялся. Проверка других категорий нового креатива относится к отдельной задаче; некоторые их подписи во время этого запуска ещё показывали технические ключи. Метаданные Git недоступны через текущий мост, поэтому ветка и git status не заявляются проверенными; адресные правки выполнены с SHA и резервными копиями.


## Характеристики и описания предметов в Tab — 09.11.2026

По команде автора «делай» добавлены характеристики одежды по актуальной wiki и индивидуальные короткие художественные описания всех текущих зарегистрированных предметов DarkWinter. В описаниях одежды свойства переданы словами, без повторения числовых значений.

### Реализовано

- 77 индивидуальных профилей одежды точно перенесены из `clothingItems` в `tools/darkwinter-workshop/data/workspace.json`. SHA-256 использованной wiki: `7a584a56e7490bce23cb85eb361f2659a55e3a63077c824c1aac6c2abb8c44de`.
- 244 описания на русском и английском охватывают все текущие зарегистрированные предметы, включая 79 предметов одежды и рюкзаков. Тексты одежды отражают качественные тепло, защиту, тяжесть и ограничения соответствующих вещей; цифры находятся в характеристиках.
- На страницах «Одежда» и «Рюкзак» карточка одежды показывает шесть подписанных полей: тепло в °C, защиту от ветра, защиту от влаги, ограничение движений, массу и состояние. Масса и состояние читаются из выбранного стека. Профили одежды не назначаются двум рюкзакам: для них сохраняются фактические состояние, масса и влагозащита.
- Масса `darkwinter:winter_parka` приведена к wiki: 1900 → 2200 г.
- Убрана неверная сумма сырого `INSULATION` из сводки одежды; в холодовой карточке состояния соответствующая строка заменена средним состоянием экипировки. Сырые баллы утепления не показываются как градусы.
- Добавлен `src/client/java/com/dark/winter/client/inventory/InventoryItemDetails.java`; обновлены `DarkWinterInventoryScreen.java`, `src/main/java/com/dark/winter/registry/ModItems.java` и клиентские локализации `ru_ru.json` / `en_us.json`.
- Описания используют кэш переводимых компонентов, сохраняя переключение языка; отрисовка свойств ограничена шестью ячейками. Новых обходов мира или операций в игровом тике не добавлено.

### Проверки

- IntelliJ `build_project`: **PASS**.
- Независимое сравнение всех 77 профилей с источником wiki: **PASS**, без расхождений по ID, порядку полей и значениям; оба рюкзака отсутствуют в таблице профилей одежды.
- Gradle `build`: job `ad86146e2cc34b45b2f5686424f1fa74`, exit code **0**, `BUILD SUCCESSFUL`, 19 с. Задача `test` сообщила `NO-SOURCE`; это не результат выполненных автоматических тестов.
- В существующем творческом мире `DarkWinter - Items` визуально проверены: английская карточка зимней парки в BACKPACK, русская карточка парки в CLOTHES, русская фланелевая рубашка с теплом `+1,5 °C` в BACKPACK, термос и походный рюкзак. На проверенных карточках подписи и описания помещаются. У термоса нет лишних характеристик; у рюкзака показаны состояние 100%, масса 1,6 кг и влагозащита 55%.

Сохранённые кадры:

- `run/screenshots/lab-captures/20261109-112436-inventory-wiki-parka-en-backpack-29239045.png`
- `run/screenshots/lab-captures/20261109-112650-inventory-wiki-parka-ru-clothing-0d64666a.png`
- `run/screenshots/lab-captures/20261109-112734-inventory-wiki-flannel-ru-backpack-de5229d6.png`
- `run/screenshots/lab-captures/20261109-112759-inventory-flask-description-ru-2ac45bdd.png`
- `run/screenshots/lab-captures/20261109-112804-inventory-backpack-properties-ru-18ee0f58.png`

Первые три кадра имеют размер 2560×1440; последние два получены с `scale=2` и имеют размер 1280×720.

### Границы результата

Это данные карточек и интерфейс. Симуляция намокания, износа и суммарного действия комплекта этим изменением не вводилась. Визуально проверены перечисленные примеры, а не все 244 карточки. Динамический износ не проверялся. Предыдущие записи ниже сохранены как история; их сведения о прежних JAR и приоритетах относятся к указанным в них этапам.

## Дополнение: звуки ясного леса — 08.11.2026

Текущий JAR: `build/libs/dark-winter-1.0.1.jar`, 87 816 997 байт, SHA256 `5784053701266fa1b95553cce537302fcfd16e94ac9471f1c10eb6d3f3e4fce4`. Добавлены три птичьи переклички и четыре треска веток, пространственные источники на реальных деревьях, только ясная погода; птицы днём. build/wkeVerifyPackage и IDEA PASS. Все семь упакованных OGG совпали по SHA с проверенными mono48kHz Vorbis-файлами. Отдельного субъективного прослушивания в игре не было. Подробности и исходники: `docs/audio/forest-clear-20261108/README.md`.

## Предыдущая снежная сборка: читаемые сугробы — 08.11.2026

Автор уточнил по записи: препятствиями оказались скрытые под снегом камни; движение сейчас считает нормальным. Последняя просьба — закончить видимость снега и сугробов. Это текущий приоритет; не выдавать прежнюю проверку искусственной площадки за воспроизведение его видео.

**Готовый JAR:** `build/libs/dark-winter-1.0.1.jar`, 87 692 413 байт.
**SHA-256:** `ca2ee4bbf26697b4e543f920953a703d93e2f912ff858a4ae1b4e100b68aebce`.
Неизменяемая копия: `build/WKE/snow-v2/candidate/dark-winter-ca2ee4bbf26697b4e543f920953a703d93e2f912ff858a4ae1b4e100b68aebce.jar`.

### Видимость снега

Добавлено локальное затенение по высоте настоящих соседних поверхностей: банки, впадины и отпечатки закрывают часть рассеянного света неба. Расчёт охватывает соседние блоки и существующий граничный halo чанка, использует ту же авторскую, мелкую и уплотнённую геометрию. Освещение солнцем и фактические тени сохранены. Ровная непрерывная плоскость сохраняет прежнюю яркость.

Расчёт выполняется при фоновой сборке сетки, с кэшем общих вершин и пропуском целых плоских участков. Дополнительных проходов GPU и обращений к миру каждый кадр нет. Это изменение света; форма модовых скосов сохранена. Клиентский исходник SnowSurfaceClient: `347cf0cf7adb3a4ad528b74e7f326a49427cc85d90a936d92a0b8845e74d22a7`.

### Проверка готовой сборки

- build + wkeVerifyPackage: job `ec2f7f06863b4a85937da886ecfb02de` PASS.
- IntelliJ build_project: PASS, problems=[].
- Реальный Minecraft/Vulkan, HIGH, 2560×1440, VANILLA section packing; вход в копию SVG, ходьба, сохранение и штатный выход. Native pipelines 305 созданы / 305 уничтожены, live 0.
- Проверка перенесена на **существующий рельеф SVG**, рядом с местом записи: настоящий полублок `7251,76,1882`, начальная область автора `7251,76,1885`. Блоки, снег и биом не подменялись. По 40 W-тиков: подъём 6,335 м и спуск 6,080 м; коррекций сервера 0, обратных скачков 0, проникновение практически 0. Это ограниченный маршрут, не доказательство отсутствия любых препятствий под снегом.
- Авторская запись `2026-11-08 19-58-23.mkv` просмотрена по всей длине 27,35 с; участок 9–18 с показал упор у уступа. Позднее автор сам установил причину со скрытыми камнями.
- Исходное сохранение: 1 435 файлов / 570 238 946 байт, до и после проверки не изменилось.
- Квитанция: `build/run/wkeSnowV2-candidate-20261108-202326-869/wke-snow-v2-summary.json`; точный scope естественного маршрута записан в movementAcceptance.scope. Общая внешняя подпись movementOnly в этой исторической квитанции ещё содержит старое описание fixture.
- GPU кадр: `build/run/wkeSnowV2-candidate-20261108-202326-869/gpu-captures/movement-after-0.png`. Кадр просмотрен; отдельного количественного A/B видимости сугробов не было. 144 FPS не заявлены.

Другие незакрытые задачи: дальнее мерцание кустов и полная визуальная приёмка всех погодных/снежных сценариев. Эксперимент depth-normal ±1/±2 ухудшил результат и отменён побайтно; JAR 14a249eb… не является релизом. Ограничения старых тестов и предыдущие результаты ниже сохранены как история.

## История предыдущих этапов

Дата: 08.11.2026. Приоритет: WKE и Fabric-мод Minecraft 26.3 / Java 25 / Windows / Vulkan.
Самостоятельная игра отложена; генерация сохраняется из Minecraft/DarkWinter.

## Текущий этап: исправления Snow v2 после проверки автором

Статус: рабочие исходники переделываются после новых замечаний автора. Неправильные следы, вертикальное дёрганье, застревание на плитах, частичная догрузка снега и отсутствие собственных теней считаются открытыми. Последний полный PASS384242 относится к cc7fb97c…, не закрывает эти замечания. Memory-only2bce29e6… собран/проверен CPU, отдельный GPUпрогон выполняется. Дальнейшие изменения ещё не собраны и не заявляются готовыми. Подробный checkpoint — docs/development/WKE_SNOW_V2_20261108_RU.md.

### Предыдущий проверенный этап Snow v2

Переделана снежная поверхность с сохранением нынешних скосов/корки. Толщина в миллиметрах, общие граничные высоты, углубления следов и общая опубликованная поверхность для отрисовки/контактов/опоры. Сохраняемая хронология осадков/оттепели догоняет поздние участки без повторного начисления; укрытия учитывают форму/проницаемость и направление ветра. Сетка собирается одним ограниченным фоновым worker.

**Статус: заключительная игровая приёмка ещё выполняется.** Последняя компиляция/build/package/SDK PASS: job442c10bd926d4397acb3e8fc370b9c62; core38 и geometry218660 проверок PASS. IntelliJ build_project PASS. JAR build/libs/dark-winter-1.0.1.jar —87583206байт, SHA256 cc7fb97c65738ea77bd162dc433335a391117f6a1d6be6c70a3db575f009af5b.

Предыдущая полная итерация jobdc08bc104ecc4274827a93ac70f16856 прошла352416 функциональных проверок:6000тиков настоящей метели +20,6257мм на открытой поверхности/0под укрытием, поздний чанк, настоящие шаги на скосе и полном снежном блоке, save/close/reopen. Этот PASS предшествует фоновой сборке и последнему общему descriptor callback; не выдаётся за приёмку последней версии.

Свежий отдельный GPU probe jobdf594059c7574317ba7db0a1f807c535 PASS: haze6случаев RGBA/D32bitExact, TAAresetPASS, experimentalshadow26случаевbitExact. Shippingshadow не переключён; выгода для actualANISOTROPIC не доказана.

**144 FPS и полное устранение мерцания не достигнуты.** Настоящий исходный DarkWinter Test:100,66FPS стоя /86,62 поворот /79,93ходьба. Прежние~142FPS относятся к сцене24деревьев. Промежуточная новая синхронная сетка снизила ходьбу до44→62FPS; worker добавлен для устранения этих главных CPU-пиков, свежий независимый замер ожидается. FabricGameTest послеreadback может синхронизировать кадры с20Hzserverbarrier; его такие окна отклонены, готовится обычный nativeclientbenchmark безrunnerbarrier/readback.

Отчёт и точные границы: docs/development/WKE_SNOW_V2_20261108_RU.md. Границы/команды: WKE/README_RU.md. Изменения мира выполняются только в отдельных копиях; исходное сохранение не заменялось. Коммитов/push/публикаций нет.

## Архив WKE: материалы, AA, эффекты, снежная поверхность и акустика

Цель автора обновлена: настоящее **2560×1440, стабильные 144 FPS на максимальном реализованном качестве**, бюджет 6,944 мс.
Контрольный ПК Ryzen 5 8400F / Radeon RX 7600 XT. Цель пока не выполнена: в контрольном HIGH снегопаде **141,7 FPS average / 127,3 FPS 1% low**, p99 полного кадра 7,598 мс.
Ясная сцена: 262,6 average / 216,7 FPS 1% low. Это ограниченный production forest fixture RD15/SD17, не весь регион.

- Материалы: действительный MAIN sampler и fog composition, полные GPU mip chains 10/7 для хвои/ветвей, общий независимый C++ mip/coverage kernel. Ветровой renderer использует настоящий atlas/sampler.
- AA: native stable-current reconstruction, реальные current/previous camera/depth, FP16 feedback до quantization, clipping/rejection и две реальные R8 particle masks. На одинаковых дальних world points мерцание −25,3%, p95 −37,4%. Полных object motion vectors нет.
- Эффекты: canonical WKE ресурсы/шесть graphs и реальный adapter executor. В игре работают quality/AA/MIP/film controls. Прежние физические copies не участвуют в активной упаковке.
- Снег: накопление в загруженной области, сопротивление ходьбе, небольшие footprints/заметание, bounded store и реальное восстановление после save/close/reopen. Offline региона и готового транспорта нет.
- Акустика: существующий OpenAL context, реальные obstacle filters и room EFX; направление/дистанция сохраняются. Полная wave acoustics и принудительный HRTF не заявляются.
- Облака: только exact-zero вычисления удалены; GPU reference A/B на одинаковых uniform slices дал побитно одинаковые RGBA и D32 на 921600 пикселях. Число шагов и разрешение сохранены.

## Проверки и сборка

- Обычный реальный GPU SDK: wkeSceneVerifyClient, job **89db51ee3f19470fbe7a49b5286f2573**, PASS **710 checks**. Проверены материалы, SAME-WORLD-POINT AA, actual Halton phases, NPC, частицы, snow movement/рост/footprints/save/reopen, настоящие OpenAL bindings.
- GPU source и timeline: build/run/wkeScene-20261108-131829-286; итоговая квитанция build/WKE/latest-scene-client.json. Native pipelines created=destroyed=964, live=0.
- Финальные build + wkeVerifyPackage + renderSettingsVerifyClient: job **4ade5110a4d84cfd85eb6d145d7147e1**, PASS, **179 UI checks**, actual film parity, 1070 native pipelines уничтожены, live=0.
- IntelliJ build_project: PASS, problems=[].
- Независимые native/core/material/surface/FFM проверки предыдущей основы сохранены в отчёте; они не выдаются за FPS-приёмку.

**Свежий мод:** build/libs/dark-winter-1.0.1.jar, **85703503 байта**.
**SHA-256:** 6deb9711451bad33e0cbc53b053f1d6b620ff22be06fd39466dc88e65557b81d.
Проверка actual JAR: build/WKE/package-verification.json — три native DLL, 35 canonical render resources по SHA, реальные зарегистрированные mixins и отсутствие private SDK classes.

Полный отчёт: docs/development/WKE_SCENE_20261108_RU.md. Границы engine/host и команды: WKE/README_RU.md. Цели: WKE/ROADMAP_RU.md.
Обычные сохранения/настройки игрока не заменялись. Коммитов, push и внешних публикаций в этом этапе не выполнялось.
Следующий performance участок — дорогие облака и редкие host/render hitches; стабильные 144 FPS не объявлены достигнутыми.

## Архив WKE: renderer и предыдущая MIP/film-приёмка

Ниже сохранена история предыдущих сборок; их JAR hashes и старые 120 FPS относятся к своим этапам.

### WKE WORLD renderer — реализован и проверен

WKE теперь создаёт графические VkPipeline и записывает реальные C++ Vulkan draw-команды мировых проходов: местность, сущности, небо, частицы, 3D-предметы/HUD, outline и постэффекты. Заимствуются устройство, targets и ресурсы Minecraft. Симуляция, модели/видимость/подготовка геометрии и света, FrameGraph, barriers/copy/clear и submission остаются у host. Двумерный GUI вне native world scope. Это native graphics backend, не перенос всей игры в C++.

Код: WKE/runtime/embedded; отдельный адаптер modules/wke/adapter. Общий исполнитель команд используется также прежним WKE Renderer::drawGeometry. В обычный JAR добавлен wke_render.dll с shader frontend и восемью mixins. Это отдельная подсистема от /dwmesh; сохранённый mesh VANILLA не выключает renderer.

Финальный SDK: wkeRendererVerifyClient, job ff34d2664ba443789139d83fbfe282d0, exit 0, 57 сек, 100 проверок. Независимые Java WORLD/TERRAIN observers подтверждают нулевой host draw внутри WKE-scopes; nativeDrawCalls=31678, worldDispatches=31678, terrainDispatches=11842, отказов/ошибок/fallback=0. Парные color/depth прочитаны с GPU; depth/mask совпали. Проверены реальный NPC и его удаление, 2200 снежных частиц, 1280×720→960×640→1920×1080, блоки, камера, reload, disconnect/re-entry. При закрытии все 571 native pipeline уничтожены, live=0.

Также PASS: 40 native renderer contract checks; независимый PowerShell/Zig driver с тремя DLL и core assertions; coreCompileGame; wkeVerifyPackage + build (job d41621d7943d46cabeba3b62282e70f4), IDEA build. Renderer DLL в SDK и JAR имеет один SHA e3827c4bf040172f15481d777aac14166f7417967f832f3ccfed2cb29a592c4f. Приватные SDK-классы в продукт не входят.

Сборка исторического renderer-этапа: build/libs/dark-winter-1.0.1.jar, 85530504 байт.
SHA-256: fc9842254f2c43b54c5784530b1e404a90bef0cbb4e3a135e9244b1450cce895.
Проверка автором в мире: /wkerender status — effective=WKE и растущие nativeDrawCalls. По умолчанию AUTO; нужен --enable-native-access=ALL-UNNAMED (в runClient уже есть).

Полный отчёт: docs/development/WKE_RENDERER_20261108_RU.md. Квитанция: build/WKE/latest-renderer-client.json. Исходные кадры/float depth: build/run/wkeRenderer-20261108-094102-803. Сборка и границы: WKE/README_RU.md; цели: WKE/ROADMAP_RU.md.

### MIP и имитация плёнки — реализованы и проверены

Два authored PNG 1254×1254 (кора и торец дерева) сохраняются; сборка готовит совместимые копии 1280×1280. Проверка 68 packaged block textures проходит. Реальный GPU atlas при MIP4 теперь имеет пять уровней (base+4), при MIP0 — один. В графике работают «Уровни MIP» 0–4 с настоящим options/reload и «Постобработка → Имитация плёнки» Вкл/Выкл с сохранением. WKE содержит общий MC-free policy, конкретные atlas/UI операции выполняет host-адаптер.

OFF сохраняет цветокоррекцию, выключает analog noise/scan/chroma/lens/vignette/9% blur/history, прекращает capture/rotation (GPU targets остаются валидными). Контракт сцены 416 байт сохранён. Исправлено устаревание live-профиля при внешней правке внутри poll-интервала и сохранении film-флага.

Финальный build + wkeVerifyPackage + renderSettingsVerifyClient, job 78dc36f6d91a45ad804c5929db8cca20: PASS, 109 проверок, Full HD. Проверены реальные UI clicks, mip0→4 с GPU readback mip4, профиль/сохранение/reload, OFF grade-only с max RGB error 1/255 и alpha mismatch 0, статичная OFF-картинка без drift, ON возобновляет history. Все 1052 native pipeline уничтожены при закрытии. IDEA build PASS. Live пользовательский visuals.properties не менялся.

Отчёт: docs/development/MIP_FILM_20261108_RU.md; квитанция build/render-settings/latest.json; первичные кадры build/run/renderSettings-20261108-103330-978. Полное исчезновение ряби/сетки исходного лесного кадра и 120 FPS этими проверками не подтверждены.

### Предыдущие законченные этапы

- Выделены реальные core/kernels/runtime/shaders WKE, введены независимая сборка, C ABI, SHA-packaging и FFM-адаптер. Старый SDK подтвердил native packing и byte parity; исторический отчёт docs/development/WKE_INTEGRATION_20261108_RU.md.
- В моде исправлены границы UI и левое положение паузы; два размера/масштаба, resize и штатный resume проверены в Minecraft. Отчёт docs/development/UI_EDGES_20261108_RU.md.

### Что оставалось на этом историческом этапе

Стабильные 120 FPS не измерялись и не подтверждаются этими тестами. CMake/Ninja путь не запускался; Vulkan validation callback в SDK не наблюдался. Подключение backend не переносит автоматически AA/материалы/генерацию старой C++-игры и не объявляет исправленными её рябь/хвою/снежные швы.

Ограничение MIP4→1 исправлено, MIP и film-настройки проверены в клиенте. Следующий визуальный приоритет — сравнение исходного лесного кадра в движении: остаточная сетка снега, геометрические швы, алиасинг травы/хвои и тени. Вклад MIP в каждый из этих дефектов отдельно не измерен. Реалистичные материалы и новые модели — после согласованных исправлений.

## Архив: этап v13 — верхушки и точные снежные соединения

Актуальная сборка: build/core/game-client-v13/DarkWinter.exe.
SHA-256 EXE: 83141768eaa8fec9180080287be707e99a8c3bb54422f0cc49b2c3223a5ec465.
Квитанция: build/core/game-client-v13/build-receipt.json (schema 13).
Запуск без автоматических прогонов: F:\DarkWinter\Start-DarkWinter-Core.cmd.
Лаунчер предпочитает v13; настройки копируются из v12 только при отсутствии новых.
Сохранения остаются в build/core/world-client/saves.

### Исправления по трём кадрам пользователя

- «Штыри» над кронами: прежний taper 5-y*4/tall никогда не достигал radius1.
  Генератор v4 использует пять ступеней и недостающий верхний ярус ветвей.
  Старые версии v1/v2/v3 сохранены без перестановки объектов.
- Исправление верхушек действует и в существующих мирах: Generator.naturalLeader
  определяет исходную вершину по seed/версии, точным координатам и материалу.
  StreamingWorld подключает callback к Scene и очищает при уничтожении.
  Кэш ограничен 64 якорями и не используется потоками генерации.
  Рендер естественной вершины получает исходную авторскую макушку и нижний
  переход из тех же хвойных карточек (нижняя граница -0.726234495639801).
  Пни на других координатах и изменённые материалы не получают эту крону.
- Корневой снег выбирается по точному классу: радиус сверху, закрытый низ,
  фактическая высота соседнего снега, четыре стороны контакта и естественная крона.
  Диагональный снег не создаёт полный воротник. Случайный захваченный collar
  больше не подменяет нужную высоту. Отверстие повторяет сечение ствола на этой высоте.
  Открытые края снежного участка закрываются; общие границы сохраняются.
- Descriptor[6]=nativeLogContext:
  0x80000000|above|closedBottom<<3|snowLayers<<4|snowSides<<8|crown<<12.
  Дополнительные формы готовятся при упаковке, не во время кадра.
- Подтверждено по PNG/raw RGBA: боковые спрайты 13/14 и снег15 полностью
  непрозрачны. Прямоугольные тёмные зубцы — рисунок albedo, а не cutout-дыры.
  36 боковых граней снежной травы/сухих плит используют собственный непрерывный
  снежный материал с белым tint. Глубокий грунт, дно и столкновения сохранены.
- Базовая форма снежного слоя канонизирована в плоский закрытый объём точной высоты.
  Это устраняет несовпадение идеальной высоты соединения с первым случайным
  скошенным шаблоном из захвата. Исходные captured context-варианты сохранены.
- Для всех трёх показанных дефектов можно открыть текущий мир.
  Новый мир v4 нужен для проверки нового алгоритма сужения/верхнего яруса,
  но починка существующей естественной верхушки и снега не требует нового мира.

### Сборка и порядок проверки

Gradle job: 92e95796b821459c98998eb49e7a663d.
coreBuildGame: exit code 0. C++ перекомпилирован; shader outputs актуальны и
использованы без лишней перекомпиляции. Штатная проверка ресурсов и упаковка успешны.
Пакет: 1593 формы, 289043 треугольников, 31 текстура,
970 native вариантов ствола, 900 точных снежных соединений,
высоты [1,5] восьмых блока.
Игру, игровые автотесты, скриншоты нового билда и замеры FPS НЕ ЗАПУСКАЛИ:
сначала ручная оценка пользователя, затем его отзыв и наши игровые проверки.
Картинка/сходство не доказаны компиляцией. Исторические результаты ниже
не относятся к новой сборке v13.

## Предыдущий этап v12: опора растений, деревянные срезы и фильтрация леса

Актуальная сборка: build/core/game-client-v12/DarkWinter.exe.
SHA-256 EXE: 8beeca91b7779d10e4f613781abb838561b7c21ed264797a9a128df4885b28a0.
Квитанция: build/core/game-client-v12/build-receipt.json (schema 12).
Запуск без автоматических прогонов: F:\DarkWinter\Start-DarkWinter-Core.cmd.
Лаунчер предпочитает v12, прежние версии остаются fallback.
Сохранения по-прежнему build/core/world-client/saves. Настройки v11 копируются
только при отсутствии settings.cfg в v12.

### Реализовано

- Генератор v3 сохраняет рельеф и ищет настоящую полную опору для дерева,
  травы, камня и куста. Убрана принудительная замена отдельного блока под
  деревом на полноразмерную землю. Куст ставится целиком после проверки
  свободного места; все стволы размещаются перед ветвями.
- Якоря деревьев ищут ближайшую опору внутри своей области 8×8; наземные
  растения могут смещаться до трёх блоков с детерминированным выбором.
  Высоты кешируются. Результат не зависит от порядка генерации колонок.
- Миры v1/v2 сохраняют прежнюю генерацию и координаты объектов/правок.
  Исправленное размещение нужно смотреть в НОВОМ мире v3.
  Старые миры не мигрируются автоматически.
- Ствол имеет независимые контексты: соединение со стволом, открытый деревянный
  низ и основание на грунте со снегом. Воздух под срезом никогда не разрешает
  воротник. Настоящему основанию нужны полная опора и соседний снежный покров.
- Во время упаковки добавлены 60 чистых восьмигранных вариантов для
  5 радиусов/материалов, всех верхних соединений и закрытых/открытых низов.
  Верх при отсутствии продолжения закрыт деревянным торцом.
  Сохранены старые shape ID и авторская хвойная макушка тонкого лидера.
  Descriptor[4] хранит endgrainTexture+1, [5] — явный тип корневого/чистого варианта.
- Сгенерированы и реально включены в assets.dwa собственные материалы коры
  (экспорт 256×256) и торца с годовыми кольцами (128×128). Мастера PNG
  сохранены отдельно от прежней коры мода. Источники, SHA-256 и назначение:
  native/darkwinter-client/export/material-overrides.json и forest-materials-v12.md.
- Массив текстур Vulkan: 31 слоя 512×512, полные mip-цепочки в линейном
  свете с premultiplied alpha, сохранением alpha coverage и RGB кромкой.
  Трёхлинейное уменьшение, nearest magnification, анизотропия до 8× при поддержке.
  Производные координат берутся до discard и преобразуются вместе с UV атласа.
  Main и shadow используют одинаковую обработку cutout. Mip для мелкой хвои
  ограничен снизу маской 4×4, чтобы не превращать ветвь в сплошной квадрат.
- Дополнительная GPU-память текстур около 41,3 MiB; staging временный при загрузке.
  Исходный SSBO сохранён для независимого геометрического oracle.
- Убраны плёночное зерно, сканирующие строки, расщепление линзы и смешивание
  со старыми непривязанными к камере кадрами. Цветокоррекция, туман, облака,
  погодные часы и временный дневной просмотр продолжают работать.

### Подтверждено и не проверено

Gradle job: 57dc1795e3674d71a39462c8a701840c.
coreBuildGame завершён с exit code 0; C++ и все 22 SPIR-V собраны.
Упаковка подтверждает 2 authoredMaterials, 60 новых вариантов,
72 переназначенных торцевых quads, 683 формы и 31 текстуру.
Штатные проверки ресурсов/упаковки выполнены как часть сборки.
Игру, игровые автотесты и замеры FPS v12 НЕ ЗАПУСКАЛИ: сначала пользователь
вручную оценивает новую сборку, затем сообщает результат, после этого
начинаются наши игровые проверки. Сходство с референсом не доказано сборкой.

verify=true теперь явно включает исходное point-sampled чтение текстур:
это проверка геометрии и исходной alpha, а не качества mip-фильтрации.
В трёх native JSON-потребителях добавлены sourceTextureOracleFrames,
filteredTextureFrames и textureOracleMode=point_source_geometry_only;
world_main суммирует счётчики через смену Renderer.
Визуальный осмотр фильтрованного режима нужно выполнять без --verify.
Исторические прогоны ниже относятся к другим сборкам и не подтверждают v12.

## Предыдущий этап v11: дневной просмотр, память и обновление секций

Актуальная сборка: build/core/game-client-v11/DarkWinter.exe.
SHA-256 EXE: 1e0ffaaac2007a80ac8c625fb53c95bda22ac97e5bf57347364f6f744cc22f71.
Квитанция: build/core/game-client-v11/build-receipt.json (schema 11).
Запуск без автоматических прогонов: F:\DarkWinter\Start-DarkWinter-Core.cmd.
Лаунчер предпочитает v11. Сохранения остаются в build/core/world-client/saves;
прежние настройки клиента копируются только при отсутствии settings.cfg в v11.

### Выполнено

- Исходный визуальный граф мода экспортирован заново: небо/облака,
  surface, volume, composite, sky merge/fused и film. Собраны все 22 SPIR-V.
  Это существующий порт собственных шейдеров, а не новый заменяющий набор.
- Временно включён daylightPreview: рендер и часы HUD показывают полдень
  08.10.1997. Сохранённый календарь симуляции не подменяется; погода,
  ветер и анимации продолжаются. --world-clock или daylightPreview 0
  в settings.cfg возвращает обычные часы; --daylight-preview включает просмотр.
- Данные рендера секций хранятся как SoA: uint16 локальные ячейки и uint32 ID форм.
  Это DOD для рендера чанков; все системы игры в общий ECS ещё не переведены.
- Scene и RasterScene обновляют изменённые секции с нужными соседями.
  Данные экземпляров загружаются по версиям только в изменённых диапазонах
  после fence своего кадра. Полная загрузка нужна при увеличении GPU буфера.
  Сохранён прежний порядок материалов/секций/ячеек при совпадении глубины.
- Диапазоны арены переиспользуются; свободный хвост сокращается. Если фрагментация
  превысила число резидентных ячеек (4096 на секцию), выполняется компактная
  пересборка с новыми версиями. Логический размер арены ограничен этим бюджетом;
  выделенные CPU/GPU ёмкости могут сохранять прежний запас.
- Соединения стволов пересчитываются в затронутых секциях.
- Буферы и изображения используют VMA 3.3.0 с Vulkan 1.1. Версия/размеры/SHA-256
  зависимости закреплены; MIT-уведомление включено в пакет.
  Растущие буферы увеличиваются геометрически и переиспользуются.
- Pipeline cache загружается до создания пайплайнов и сохраняется на диск
  атомарной заменой. Проверяются длина, контрольная сумма, vendor/device,
  pipelineCacheUUID; имя учитывает driverVersion. Непригодный кэш игнорируется.
- Внешние зависимости render pass ограничены фактическими стадиями attachment;
  чтения шейдерами и transfer покрыты явными переходами изображений.
  Пулы дескрипторов и два кадра в полёте сохранены.

### Подтверждение сборки и порядок проверки

Gradle job: 7b04e0758e184b7b92a496e874369f61.
Задача: coreBuildGame, exit code 0; C++ и 22 SPIR-V собраны.
В состав сборки входит штатная проверка ресурсов visualsDoctor.
Игровые прогоны, coreVerifyGame/coreAuditGame, UI-сценарии и замеры FPS
для v11 НЕ ЗАПУСКАЛИСЬ по прямому указанию пользователя.
Сначала пользователь вручную оценивает картинку, FPS и микрофризы;
после его сообщения начинаются наши игровые проверки и измерения.
Исторические результаты ниже относятся к v10/v9 и не подтверждают v11.

Неизмеренные ограничения: снимок world для фоновой occupancy-работы пока полный;
агрегация и сортировка списка draw batches пока общие. Выигрыш FPS и отсутствие
микрофризов не заявляются до наблюдений. GPL и отдельная transfer queue
не включались без проверки устройств/нагрузки: основные пайплайны создаются
перед игровыми кадрами, а изменяемые экземпляры уже используют frame-owned
отображаемую память. Полная визуальная идентичность с модом ещё не подтверждена.

## Предыдущий этап v10: нативный рендер леса и проверка геометрии

Продолжено согласованное поручение переноса шейдеров и ускорения леса без
отдельного FPS-бенчмарка. Самостоятельный C++/Win32/Vulkan-клиент использует
растеризацию исходных моделей и исходный граф визуальных проходов DarkWinter.
Полное попиксельное совпадение с Minecraft-модом этим этапом не заявляется.

Актуальная сборка: build/core/game-client-v10/DarkWinter.exe.
SHA-256 EXE: cd9628bd5f91ca1d2b9b0da0a6dc2397b4d051eb1fc72f61f6ea693a133516a8.
Квитанция: build/core/game-client-v10/build-receipt.json.
Запуск: F:\DarkWinter\Start-DarkWinter-Core.cmd (v10, затем прежние fallback).
Отдельный каталог выбран потому, что открытый пользовательский v9 блокировал
перезапись своего EXE. Этот процесс и его сохранения не останавливались.

### Исправления

- Точно координатные плоскости получают глубину непосредственно из уравнения
  плоскости с camera-relative вычитанием. Исходные Möller-пересечение,
  UV, alpha и расстояние для диагностики сохранены; диагональная геометрия
  не привязывается к сетке и не подменяется приближённой нормалью.
- При несовпадении material ID проверка не пропускает образец: независимый CPU
  ray проверяет именно GPU material и существующий фиксированный band 5e-5
  относительно исходного CPU hit. Прежние проверки расстояния действуют
  относительно обеих поверхностей. Для cutout это разрешено только при прямых
  UV без atlas mapping и tint alpha 255. Канонический CPU выбор не изменён.
- Допустимые совпадающие поверхности считаются отдельно как oracleEquivalent.
  Это проверка геометрической эквивалентности, а не доказательство точного
  GPU порядка material ID при совпадениях. Счётчик есть во всех трёх native
  JSON-потребителях Renderer.
- Удалён неверный barycentric shortcut перед существующим world-space
  roundoff envelope: тонкий треугольник может попасть в него и при v=0.089.
  Сам размер envelope не увеличен. Такие границы явно учитываются как
  oracleAmbiguous; на больших координатах их доля может быть выше.
- CPU список команд drawCommands переиспользуется между кадрами.
  После достаточного reserve устраняются повторные allocation/free;
  оба обхода, порядок команд и копирование в frame-owned GPU буфер сохранены.
  Геометрия, плотность леса, дальность, разрешение и игровые правила не снижены.

### Проверка

Gradle job: 6ab427d4f5e84fa6b1877d125f321dbf.
Задача: coreVerifyGame. Завершена с exit code 0.
Прогон: build/core/game-runs/20261108-031112-260.
Основное свидетельство: ui/stage6.json.

- 1718 player checks, 68 player-transition checks, 45 generation/streaming/journal
  checks (9 добавленных регрессий), 10022 UI checks, WORLD_UI_STORE_PASS и
  WORLD_OPTIONS_CHECKS_PASS.
- Нативный сценарий: 720 кадров, jvm=false, fullscreen/window restore,
  пауза, первое лицо, отмена загрузки и сохранение/повторный вход — pass.
- 1997 проверенных CPU/GPU образцов, из них 1 отдельно подтверждённый tie.
  32 численно граничных образца помечены ambiguous и не считаются строгой
  проверкой геометрии. Нельзя описывать их как совпавшие пиксели.
- 546 сгенерированных колонок; резидентный бюджет 1183 секции.
- C++ и GLSL собраны. Ранее в этом же этапе перекомпилированы все 22 SPIR-V;
  финальная проверка использовала актуальные выходы задачи coreCompileWorldShader.
- Просмотрен свежий ui/frame-120.png (лес, первое лицо, HUD).
  Это не сверка с модом при одинаковых условиях. Отдельный FPS-прогон не запускался.
- Звук в скрытом прогоне не проверен: audioReady=false.

Следующий участок: визуальная сверка нативной сцены и оставшихся GUI-расхождений
с живым модом при одинаковых времени, погоде, размере окна и масштабе UI.

## Предыдущий завершённый этап: интерфейс и настройки мира

### Настройки мира и сборка предыдущего этапа

Продолжается перенос интерфейса и поведения Fabric-мода в самостоятельный
C++/Win32/Vulkan-клиент. Завершён текущий участок: применение настроек мира,
независимое применение правил, способности режимов игрока, календарные часы,
сохранение/повторный вход и обработка обязательной ошибки загрузки.
Полный перенос игры и визуальное соответствие всех экранов ещё не завершены.

Запуск: F:\DarkWinter\Start-DarkWinter-Core.cmd.
Сборка этого предыдущего этапа: build/core/game-client-v9/DarkWinter.exe.
SHA-256 EXE: f0e62035c4e22260cc4555c2db86552392855d961752e058b8d8365b6d6f5a81.
Квитанция сборки: build/core/game-client-v9/build-receipt.json.

## Настройки мира и игровые правила

- Применить проверяет весь запрос до изменения мира, применяет режим по
  умолчанию, личный режим, доступ к командам, сложность и фиксацию сложности,
  затем возвращает в паузу. Настройки записываются на диск при сохранении мира.
- Отмена отбрасывает внешний черновик. Личный режим независим от режима
  мира по умолчанию. Фиксация сложности требует подтверждения; применённую
  фиксацию снять нельзя. Ограничения хардкора проверяются и в обработчике.
- Готово внутри игровых правил применяет только изменённые правила сразу
  к текущему миру. Последующая Отмена внешней формы не откатывает их.
  Выход с изменёнными правилами поддерживает обе ветви подтверждения:
  отбросить изменения либо продолжить редактирование.
- Значения проверяются по типу и диапазону из каталога текущего Minecraft.
  Недопустимое целое значение не применяется. Изменения формы не попадают
  в глобальные настройки клиента.
- Неподдержанные сетевые параметры отклоняют весь запрос без частичного
  применения других настроек. Сетевая игра этим этапом не реализована.
- Подписи режимов используют короткие selectWorld.gameMode.*,
  как GameType.getShortDisplayName() в исходном WorldOptionsScreen.

## Режимы, часы и сохранение

Личный режим влияет на настоящий контроллер игрока: творческий поддерживает
двойное нажатие прыжка для полёта, подъём и спуск; наблюдатель постоянно летает
и проходит сквозь блоки. Приключение и наблюдатель запрещают изменение блоков.
Тело и руки наблюдателя скрыты. Дальность взаимодействия творческого режима
равна 5 блокам, остальных — 4,5. F8 остаётся отдельной отладочной камерой.

Переход из наблюдателя в творческий возле земли выключает полёт, когда тело
свободно и поверхность находится ближе одного блока. Над землёй полёт
сохраняется; повторное применение того же режима не меняет его. Выносливость
DarkWinter не получила неподтверждённого исключения для творческого режима.
Ходьба сохраняет прежние формулы, новые ветви полёта проверены отдельно.

Календарное время отделено от игрового. advance_time останавливает календарь,
сезонный ход и Солнце; время собственной погоды DarkWinter продолжает идти.
spectators_generate_chunks запрещает постановку новых задач генерации при
движении наблюдателя; уже начатая работа может завершиться.

Канонические параметры world.*, календарные часы и полёт сохраняются
в .options рядом с существующим снимком мира. Форматы старых .state
не изменены, для старых сохранений предусмотрены исходные значения.
Проверен повторный вход через список миров новым процессом.

## Ошибка загрузки

Проверена реальная ошибка чтения собственного тестового сохранения:
неверный заголовок .state приводит к FAULT с сообщением Save header invalid.
Процесс остаётся жив, мир не активен, резидентных секций нет. Назад возвращает
в главное меню. После побайтового восстановления исходного .state через
список вновь открывается тот же мир и сохраняется штатно.

Этот отказ происходит до готовности мира. В моде кнопка Продолжить относится
к необязательной дополнительной подготовке после входа в уже готовый мир.
Такого отдельного этапа в native пока нет; безопасное Продолжить для
проверенного обязательного отказа не существует.

## Проверки этой сборки

Gradle job: 969053d2ebda4d95b6a8a5df9da0297e.
Задачи: coreVerifyGame, coreReviewGameWorldOptions, coreReviewGameLoadFailure.
Все завершились с exit code 0.
Результаты: build/core/game-runs/20261107-151309-485.

- CORE_PLAYER_CHECKS_PASS: 1718 проверок.
- CORE_PLAYER_TRANSITIONS_PASS: 68 проверок, включая 49 проверок
  режимов/полёта. JSON содержит 67, потому что последняя проверка записи
  самого JSON учитывается уже после его формирования.
- 36 проверок генерации/стриминга/журнала; 10022 проверки интерфейса;
  WORLD_UI_STORE_PASS и WORLD_OPTIONS_CHECKS_PASS.
- Реальный нативный запуск: 720 кадров, без JVM, полный экран и возврат
  в окно, пауза, первое лицо, отмена загрузки и повторная загрузка.
- World Options: Apply/Cancel, независимость режимов, обе ветви
  подтверждения фиксации сложности, независимые Rules Done, недопустимое
  целое правило, обе ветви выхода из правил, календарная остановка и
  возобновление, переход Spectator→Creative возле земли, сохранение
  и повторное открытие новым процессом. 7 PNG, errors=[].
- Load failure: повреждение только собственной тестовой копии .state,
  реальный отказ, возврат в меню, точное восстановление по SHA-256,
  повторный вход в тот же worldId и сохранение. 5 PNG, 8 проверок, errors=[].
- Процессы source/reopen и source/recovery завершились штатно, exit code 0.
  Пользовательские сохранения не использовались для внесения повреждения.

Ключевые свидетельства:
- build/core/game-runs/20261107-151309-485/world-options-review/audit.json
- build/core/game-runs/20261107-151309-485/world-options-review/source/audit.json
- build/core/game-runs/20261107-151309-485/world-options-review/reopen/audit.json
- build/core/game-runs/20261107-151309-485/load-failure-review/audit.json
- .darkwinter-lab/jobs/969053d2ebda4d95b6a8a5df9da0297e.log
- build/core/minecraft-reference/manifest.json

После перезапуска проверены сохранённые значения: режим мира 1 (Creative),
личный режим 3 (Spectator), команды включены, сложность 3 и зафиксирована,
полёт включён, advance_time=0, spectators_generate_chunks=1.
Финальная телеметрия меню также подтверждает короткие подписи:
Стандартный режим игры: Выживание / Личный режим игры: Выживание.

## Источники и визуальная сверка

Поведение сопоставлялось с точными исходниками Minecraft 26.3 из Loom
текущего проекта: WorldOptionsScreen, GameType, Abilities, Player,
LocalPlayer, LivingEntity, ServerPlayerGameMode. Происхождение и SHA
семи экспортированных файлов записаны в
build/core/minecraft-reference/manifest.json.
Дополнительно сверены исходники календаря, собственной погоды, HUD,
выносливости и походки DarkWinter.

Эталоны живого мода:
- run/screenshots/lab-captures/20261107-021810-native-ui-world-options-reference-7be04d54.png
- run/screenshots/lab-captures/20261107-021851-native-ui-world-rules-reference-04c15f6e.png

Визуально просмотрены native PNG предыдущего прогона
build/core/game-runs/20261107-145833-696/world-options-review
и оба эталона. Все 13 основных controls World Options, а также поиск,
14 видимых переключателей Rules и Done/Cancel совпадают по GUI-координатам
и размерам. Это не доказательство полного визуального совпадения.
Финальная правка после этой сверки меняет только ключи коротких названий
режимов; обработчики и геометрия не менялись.

## Оставшаяся работа и границы результата

- Размытие фона, разделители, подложка списка, жирные подчёркнутые
  заголовки World Options и жёлтые категории Rules ещё отличаются от мода.
  Полоса прокрутки Rules расположена у края окна вместо края списка.
  Подпись Менять режим при входе обрезается иначе.
  Полный перенос контекстных подсказок ещё не завершён.
- Каталог игровых правил проверяется и сохраняется, но действующие
  нативные потребители этого этапа — advance_time и
  spectators_generate_chunks. Правила ещё не перенесённых мобов, предметов,
  урона и других подсистем не получили искусственных эффектов.
- Полные команды/разрешения, творческий инвентарь, сеть, остальные
  генераторы, симуляция потребностей и необязательная подготовка мира
  не реализованы в этом проходе. Сложность сохраняется и блокируется,
  но это не означает реализации отсутствующих зависимых подсистем.
- Runtime-аудитор пока не нажимает LAN и Escape на страницах Rules,
  difficulty-confirm и rules-discard-confirm. Сетевой отказ проверен
  в тестах обработчика; маршруты Escape проверены чтением кода.
- Повторное создание мира после изменения его параметров требует
  отдельной сверки: форма создания пока использует create.*.
- Слышимость звука и прирост производительности не оценивались.

Следующий участок того же переноса: устранить перечисленные конкретные
визуальные расхождения и продолжить сверку остальных вкладок и обработчиков.

## Сохранённая история

Предыдущий этап загрузки/паузы:
Gradle job 22ca4610356741ff8519f942c9e06df6,
build/core/game-runs/20261107-140059-307/loading-review.
В нём проверены кнопочная отмена, отсутствие отмены по Escape,
неизменность времени/позиции/направления на паузе, создание, сохранение
и повторный вход в тот же worldId. Короткие ранние фазы, не пойманные
на снимке, отмечены not_observed, а не pass.

История ранних этапов: docs/development/CORE_STAGE5_RU.md,
docs/development/CORE_STAGE6_RU.md и
docs/development/CORE_NATIVE_COMPARISON_RU.md.
