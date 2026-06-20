# Задача: заменить FFmpeg H.264 энкодер/декодер в BitCam на библиотеку javah264 (openh264)

## Контекст
BitCam — мультилоадерный Minecraft-мод (Fabric/NeoForge/Paper), рабочая папка `/Users/dalynkaa/Documents/Develop/MineModding/BitCam`. Клиент стримит вебку по UDP в H.264. Сейчас энкод/декод H.264 идут через FFmpeg (bytedeco javacv). Нужно заменить **только H.264 энкод и декод** на библиотеку `dev.nexbit:javah264` (openh264-обёртка, форк на github.com/NexBitstd/JavaH264).

**ЖЁСТКИЕ ОГРАНИЧЕНИЯ — не нарушать:**
- НЕ трогать захват камеры (`JavaCvCameraBackend.java`) — он остаётся на FFmpeg.
- НЕ менять UDP-протокол, фрагментацию, FEC, реассемблинг. Payload остаётся одним Annex-B access unit (склеенные NAL со start-кодами `00 00 01`), ровно как сейчас.
- НЕ менять интерфейсы `FrameEncoder` и `FrameDecoder`, классы `RemoteFrameStore`, `CameraCaptureController`, `RemoteFrame`, `EncodedLocalFrame`, `DecodedFrame`, `VideoFrameSupport`.
- Сохранить конструктор `H264FrameDecoder(Consumer<Throwable> failureConsumer, Consumer<String> lifecycleLog)` и метод `boolean failed()` — иначе `RemoteFrameStore` сломается.
- Сохранить у `H264FrameEncoder` конструктор без аргументов `new H264FrameEncoder()` и методы — он создаётся так в `CameraCaptureController`.
- Стиль кода: `final` классы, package-private, комментарии как в соседних файлах. Java 21.

## API библиотеки javah264 (пакет `dev.nexbit.javah264`)

**Энкодер:**
```java
H264Encoder enc = H264Encoder.builder()
    .profile(H264Encoder.Profile.Baseline)
    .rateControlMode(H264Encoder.RateControlMode.Bitrate)
    .spsPpsStrategy(H264Encoder.SpsPpsStrategy.IncreasingId)
    .usageType(H264Encoder.UsageType.CameraVideoRealTime)
    .multipleThreadIdc((short) 1)
    .maxFrameRate(fps)              // float
    .targetBitrate(bitrateBps)      // int, биты/сек
    .intraFramePeriod(fps * 2)      // периодический IDR ~раз в 2с
    .build();                       // throws IOException, UnknownPlatformException
byte[] annexB = enc.encodeRGBA(width, height, rgbaBytes);  // ВЕСЬ кадр (все NAL склеены, Annex-B); throws EncoderException
enc.close();
```
- Вход: `rgbaBytes` длиной `width*height*4`, порядок байт R,G,B,A.
- `width` и `height` ДОЛЖНЫ быть чётными и ≥16 (иначе IllegalArgumentException).
- **Force-keyframe метода НЕТ.** Чтобы выдать keyframe по запросу — пересоздать энкодер (новый `build()` стартует с SPS/PPS+IDR). Это как в текущем FFmpeg-коде.

**Декодер:**
```java
H264Decoder dec = H264Decoder.builder()
    .flushBehavior(H264Decoder.FlushBehavior.NoFlush)
    .build();                                   // throws IOException, UnknownPlatformException
for (byte[] nal : H264Decoder.nalUnits(annexB)) {   // static, делит Annex-B на NAL; throws IOException, UnknownPlatformException
    DecodeResult r = dec.decodeRGBA(nal);            // null пока кадр не готов / нет IDR
    if (r != null) {
        int w = r.getWidth(), h = r.getHeight();
        byte[] rgba = r.getImage();                  // w*h*4, R,G,B,A
        long ts = r.getTimestamp();
    }
}
dec.close();
```
- Декодер сам ждёт первый IDR (до него `decodeRGBA` возвращает null) — отдельной keyframe-логики не нужно, на не-IDR NAL он не падает.

## Текущие классы BitCam (которые меняем/используем)
Все в `client-common/src/main/java/dev/nexbit/bitcam/clientcommon/`:
- `H264FrameEncoder.java` — переписать на `H264Encoder`. Реализует интерфейс `FrameEncoder`:
  ```java
  BitCamVideoCodec codec();              // вернуть BitCamVideoCodec.H264
  void requestKeyframe();                // выставить флаг → пересоздать энкодер на следующем encode
  void setBitrateScale(float scale);     // 0.1..1.0, домножает битрейт
  EncodedLocalFrame encode(BufferedImage source, int targetWidth, int targetHeight,
                           int fps, float quality, int frameId, long captureTimeMillis); // может вернуть null
  void close();
  ```
- `H264FrameDecoder.java` — переписать на `H264Decoder`. Реализует `FrameDecoder`:
  ```java
  void decode(RemoteFrame frame, Consumer<DecodedFrame> output);
  void close();
  // + СОХРАНИТЬ: конструктор (Consumer<Throwable>, Consumer<String>) и boolean failed();
  ```
- `EncodedLocalFrame` — record, конструктор:
  `new EncodedLocalFrame(int frameId, int width, int height, long captureTimeMillis, boolean keyFrame, BitCamVideoCodec codec, byte[] payload)`
- `RemoteFrame` — методы: `.payload()` (Annex-B AU), `.frameId()`, `.captureTimeMillis()`, `.width()`, `.height()`, `.bubbleStyle()`, `.keyFrame()`, `.codec()`.
- `VideoFrameSupport` (статические методы, тот же пакет):
  - `BufferedImage scale(BufferedImage src, int w, int h)` — масштабирует (или возвращает src если совпадает).
  - `DecodedFrame toDecodedFrame(BufferedImage source, int frameId, long captureTimeMillis, int sourceWidth, int sourceHeight, BitCamBubbleStyle style)` — делает crop/mask/aspect + ABGR. ВЫЗЫВАТЬ её для построения DecodedFrame из декодированного кадра.
- `BitCamVideoCodec` — enum, значение `H264`.
- `CameraCaptureController.capture()` зовёт `encoder.encode(...)` на потоке `bitcam-network-encode`; `requestKeyframe()`/`setBitrateScale()` — с других потоков (поэтому флаги делать volatile / synchronized).
- `RemoteFrameStore.decoderFor()` создаёт `new H264FrameDecoder(failCb, logCb)` и проверяет `failed()`; `decode()` зовётся на одном потоке `bitcam-decode`, но `close()` может прийти с render-потока → защитить нативный декодер локом (как в текущем `H264FrameDecoder`: `ReadWriteLock`/`synchronized`, decode под одним замком, close под ним же).

## Реализация — пошагово

### Шаг 1. Зависимость
В `gradle.properties` добавь `javah264_version=1.0.2` (или актуальную опубликованную версию).

В `client-common/build.gradle.kts` добавь `compileOnly("dev.nexbit:javah264:$javah264_version")` (рядом с `compileOnly("org.bytedeco:javacv:...")`), прочитав версию через `rootProject.property("javah264_version")`.

Репозиторий — GitHub Packages (требует PAT с `read:packages`). Для локальной разработки проще опубликовать форк в mavenLocal:
```bash
cd /Users/dalynkaa/Documents/Develop/MineModding/JavaH264
cd rust && cargo build --release --lib && cd ..
mkdir -p src/main/resources/natives/mac-aarch64
cp rust/target/release/libjavah264.dylib src/main/resources/natives/mac-aarch64/
# системный java здесь = JDK 26, gradle 8.13 его не тянет; тулчейн = 8, но JDK 8 не установлен:
sed -i '' 's/JavaLanguageVersion.of(8)/JavaLanguageVersion.of(17)/' build.gradle
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home ./gradlew publishToMavenLocal
sed -i '' 's/JavaLanguageVersion.of(17)/JavaLanguageVersion.of(8)/' build.gradle
```
Затем в BitCam добавь `mavenLocal()` в repositories модуля client-common и (для рантайма лоадеров) забандль jar.

Бандлинг в рантайм: jar javah264 САМ содержит нативы (извлекаются `LibraryLoader` в рантайме) — отдельных classifier-нативов как у javacv НЕ нужно. В `versions/shared/fabric.gradle.kts` и `versions/shared/neoforge.gradle.kts` добавь jar в рантайм так же, как там подключается `javaCvBundledLibraries` (runtimeOnly + jarJar, для Fabric — `include`).

### Шаг 2. Переписать `H264FrameEncoder`
Держи внутри `volatile H264Encoder encoder` + текущие `width/height/fps/appliedBitrate`. Логика:
```java
public EncodedLocalFrame encode(BufferedImage source, int targetWidth, int targetHeight,
                                int fps, float quality, int frameId, long captureTimeMillis) {
    BufferedImage scaled = VideoFrameSupport.scale(source, targetWidth, targetHeight);
    byte[] rgba = imageToRgba(scaled, targetWidth, targetHeight);
    ensureEncoder(targetWidth, targetHeight, fps, quality); // пересоздать если: encoder==null | размер изменился | keyframeRequested (коалесцировать ~500мс) | битрейт изменился (порог ~10% + интервал ~700мс)
    byte[] payload;
    try { payload = this.encoder.encodeRGBA(targetWidth, targetHeight, rgba); }
    catch (Exception e) { throw new IllegalStateException("Failed to encode H.264 frame: " + e.getMessage()
            + " — openh264 native may be missing or failed to load.", e); }
    if (payload == null || payload.length == 0) return null;
    return new EncodedLocalFrame(frameId, targetWidth, targetHeight, captureTimeMillis,
            containsIdr(payload), BitCamVideoCodec.H264, payload);
}
```
Переноси из старого файла: `bitrateFor(width,height,fps,quality,scale)` (логика битрейта) и `containsIdr(byte[])` (ищет NAL type 5):
```java
private static boolean containsIdr(byte[] annexB) {
    for (int i = 0; (i + 3) < annexB.length; i++) {
        if (annexB[i] == 0 && annexB[i + 1] == 0 && annexB[i + 2] == 1 && (annexB[i + 3] & 0x1F) == 5) return true;
    }
    return false;
}
```
`requestKeyframe()` — выставляет `volatile boolean keyframeRequested = true`. `ensureEncoder` при этом флаге (с коалесцированием по времени) закрывает старый и строит новый `H264Encoder` → он стартует с IDR. `setBitrateScale(float)` — volatile, применяется при следующем ensureEncoder. `close()` — `encoder.close()`.
Хелпер:
```java
private static byte[] imageToRgba(BufferedImage image, int width, int height) {
    int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
    byte[] rgba = new byte[width * height * 4];
    for (int i = 0, p = 0; i < argb.length; i++, p += 4) {
        int c = argb[i];
        rgba[p]   = (byte) ((c >> 16) & 0xFF); // R
        rgba[p+1] = (byte) ((c >> 8)  & 0xFF); // G
        rgba[p+2] = (byte) (c & 0xFF);         // B
        rgba[p+3] = (byte) ((c >> 24) & 0xFF); // A
    }
    return rgba;
}
```

### Шаг 3. Переписать `H264FrameDecoder`
Сохрани конструктор `(Consumer<Throwable> failureConsumer, Consumer<String> lifecycleLog)`, поле `volatile boolean failed`, метод `failed()`, лок (как сейчас). Логика:
```java
public void decode(RemoteFrame frame, Consumer<DecodedFrame> output) {
    synchronized (lock) {                     // или ReadWriteLock как в текущем файле
        if (closed || failed) return;
        try {
            if (decoder == null) {
                decoder = H264Decoder.builder().flushBehavior(H264Decoder.FlushBehavior.NoFlush).build();
                lifecycleLog.accept("openh264 H.264 decoder initialised");
            }
            for (byte[] nal : H264Decoder.nalUnits(frame.payload())) {
                DecodeResult r = decoder.decodeRGBA(nal);
                if (r == null) continue;
                BufferedImage image = rgbaToImage(r.getImage(), r.getWidth(), r.getHeight());
                output.accept(VideoFrameSupport.toDecodedFrame(image, frame.frameId(),
                        frame.captureTimeMillis(), frame.width(), frame.height(), frame.bubbleStyle()));
                if (!firstFrameLogged) { firstFrameLogged = true;
                    lifecycleLog.accept("decoded first frame " + r.getWidth() + "x" + r.getHeight()); }
            }
        } catch (Throwable e) {
            failed = true; failureConsumer.accept(e);
        }
    }
}
```
Хелпер:
```java
private static BufferedImage rgbaToImage(byte[] rgba, int width, int height) {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    int[] argb = new int[width * height];
    for (int i = 0, p = 0; i < argb.length; i++, p += 4) {
        int r = rgba[p] & 0xFF, g = rgba[p+1] & 0xFF, b = rgba[p+2] & 0xFF, a = rgba[p+3] & 0xFF;
        argb[i] = (a << 24) | (r << 16) | (g << 8) | b;
    }
    image.setRGB(0, 0, width, height, argb, 0, width);
    return image;
}
```
`close()` — под локом: `closed = true; if (decoder != null) decoder.close();`.
(Опциональная оптимизация позже: `decodeRGBAInto(nal, reusedDirectByteBuffer)` чтобы не аллоцировать byte[] на кадр — НЕ делать в первой версии, сначала рабочий вариант.)

### Шаг 4. Сборка и проверка
```bash
cd /Users/dalynkaa/Documents/Develop/MineModding/BitCam
./gradlew :client-common:compileJava
./gradlew :mc1_21_8:fabric:compileClientJava :mc1_21_8:neoforge:compileJava
```
Затем запусти Fabric-клиент (`./gradlew :mc1_21_8:fabric:runClient`), подключись с двумя клиентами, включи стрим (клавиша V) и проверь в логе:
- появляется `openh264 H.264 decoder initialised` и `decoded first frame WxH`;
- НЕ повторяется `BitCam started receiving H264 video from ...` каждые 10 секунд;
- баблы с камерой видны над головами игроков.

## Чек-лист «не сломать»
- [ ] `RemoteFrameStore` не изменён (конструктор декодера и `failed()` те же).
- [ ] `CameraCaptureController` не изменён (`new H264FrameEncoder()` и сигнатуры методов те же).
- [ ] Протокол/фрагментация/FEC не тронуты — payload по-прежнему один Annex-B AU.
- [ ] FFmpeg-захват камеры не тронут.
- [ ] `./gradlew :client-common:compileJava` зелёный.
- [ ] Размеры кадра чётные и ≥16 (приходят из welcome сервера; если нет — округлить вниз до чётного).
