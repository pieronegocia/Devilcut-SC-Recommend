# PinSaver HD

App Android para descargar videos de Pinterest en la mejor calidad disponible (HD).

## Qué hace

- Acepta enlaces `https://pin.it/…`, `https://pinterest.com/pin/…` (cualquier dominio regional: `es.`, `.mx`, `.co.uk`…) o el texto completo que copia la app de Pinterest.
- Aparece en el menú **Compartir** de Pinterest: compartes el Pin con *PinSaver HD* y la app lo analiza al instante.
- Busca **todas** las calidades del video y marca la mejor:
  - MP4 directos (`V_720P`, `V_EXP7`, variantes HEVC…), con su tamaño exacto.
  - Streams HLS: lee la playlist para saber su resolución real (a menudo 1080p). Si es mejor que cualquier MP4, se descarga y se convierte a MP4 en el teléfono **sin recomprimir** (Media3 Transformer), así que no pierde calidad.
  - Si Pinterest solo da HLS, también prueba la copia MP4 de 720p que Pinterest guarda junto al stream.
- **Descarga automática** opcional: al compartir un Pin, baja la mejor calidad directamente.
- Los videos se guardan en **Películas/PinSaverHD** y aparecen en la galería. Las descargas MP4 usan el `DownloadManager` de Android, así que siguen aunque cierres la app.
- Interfaz en español, Material 3, con tema claro y oscuro.

> Descarga solo contenido propio o con permiso de su autor y respeta las condiciones de uso de Pinterest.

## Cómo conseguir el APK

### Opción 1: GitHub Actions (sin instalar nada)

Cada push que toca `PinSaverHD/` compila la app con el workflow `.github/workflows/pinsaver-android.yml`.

1. En GitHub abre la pestaña **Actions** → **PinSaver HD (Android APK)** → la última ejecución.
2. Descarga el artefacto **PinSaverHD-apk** (es un `.zip` con `app-release.apk`).
3. Pásalo al teléfono, ábrelo y permite *instalar apps de origen desconocido*.

También puedes lanzarlo a mano con **Run workflow**.

> El APK va firmado con una clave de depuración que se genera en cada ejecución del workflow. Para actualizar a un APK de otra ejecución, desinstala antes la versión anterior.

### Opción 2: Android Studio

1. Abre la carpeta `PinSaverHD/` en Android Studio (Ladybug o más reciente).
2. Conecta el teléfono con depuración USB y pulsa **Run**.

O desde la terminal (requiere JDK 17 y el Android SDK):

```bash
cd PinSaverHD
./gradlew :core:test :app:assembleRelease
# APK en app/build/outputs/apk/release/app-release.apk
```

## Requisitos

- Android 8.0 (API 26) o superior.
- En Android 8 y 9 la app pide permiso de almacenamiento la primera vez que descargas.

## Estructura

```
PinSaverHD/
├── core/   Kotlin puro (sin Android), con tests
│   ├── PinterestLinks       detecta y normaliza enlaces / ids de Pin
│   ├── PinterestExtractor   pin.it → id → API de Pinterest → HTML de respaldo → calidades
│   ├── VideoJsonScanner     encuentra videos en cualquier JSON de Pinterest
│   ├── HtmlPinParser        lee el JSON incrustado en la página del Pin
│   ├── HlsPlaylist          lee playlists HLS (resoluciones y bitrates)
│   └── FileNames            nombres de archivo seguros
└── app/    App Android (Jetpack Compose)
    ├── MainActivity         recibe enlaces compartidos
    ├── MainViewModel        estado de la pantalla y de las descargas
    ├── ui/MainScreen        interfaz
    └── download/            DownloadManager (MP4), Media3 Transformer (HLS → MP4), MediaStore
```

### Cómo encuentra el video

1. Si el enlace es `pin.it`, sigue las redirecciones hasta `pinterest.com/pin/<id>`.
2. Pide el Pin al endpoint web de Pinterest (`/resource/PinResource/get/`).
3. Si falla, descarga la página del Pin y lee el JSON que lleva incrustado.
4. En ambos casos busca cualquier objeto con una `url` `.mp4`/`.m3u8` en lugar de depender de nombres de campo concretos, y agrupa las calidades de cada video por el hash de su nombre de archivo. Así sigue funcionando aunque Pinterest cambie la estructura de sus datos.

Pinterest no tiene una API pública para esto, así que si cambia su web puede hacer falta ajustar `PinterestExtractor`. Los tests de `core` (`./gradlew :core:test`) simulan las respuestas de Pinterest con un servidor falso.
