# La Peña Live

App de Android para transmitir los partidos de La Peña FC a YouTube, sin marcas de agua.

## Etapas

1. **Transmitir a YouTube** desde la cámara del celular. ✅
2. **Escudo y marcador** sobre el video, manejado desde otro celular. ✅ (esta versión)
3. Comentarios del chat de YouTube en pantalla.

## Cómo instalarla

1. Entrá a **Releases → "La Peña Live – última versión"** desde el celular.
2. Descargá `LaPenaLive.apk` y abrilo. Android va a pedir permiso para instalar apps de fuentes desconocidas: aceptalo.

## Marcador desde otro celular

1. En el celular que filma: **TRANSMITIR**. Abajo a la izquierda aparece el **código del partido** (6 letras). Mantenelo apretado para generar uno nuevo.
2. En otro celular con la app: **CONTROLAR MARCADOR** y escribí ese código.
3. Cada cambio (goles, reloj, 1T/2T) aparece en el video en 1–2 segundos. Funciona con datos móviles, no hace falta el mismo wifi.

La sincronización usa [ntfy.sh](https://ntfy.sh) (gratis, sin cuenta).

## Modo ahorro

Con la transmisión en vivo, el botón **AHORRO** apaga la vista previa y oscurece la pantalla. La transmisión sigue. Tocá la pantalla para volver.

## Cómo transmitir

1. En YouTube Studio: **Crear → Emitir en vivo → Transmisión** (con codificador / software).
2. Copiá la **clave de transmisión** y pegala en la app (queda guardada).
3. Tocá **EN VIVO ▶**. En YouTube Studio vas a ver la imagen en unos segundos.

Calidad: 1280×720, 30 fps, hasta 2,5 Mb/s (baja sola si la conexión empeora).

Hecha con [RootEncoder](https://github.com/pedroSG94/RootEncoder) (Apache 2.0).
