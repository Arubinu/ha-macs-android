# ❓ FAQ

[English](faq.md) · [Français](faq.fr.md) · [Deutsch](faq.de.md) · **Español** · [Italiano](faq.it.md) · [Nederlands](faq.nl.md) · [Português](faq.pt.md) · [Polski](faq.pl.md)

## 📱 Dispositivos

**¿Qué tabletas funcionan?**
Cualquier tableta con Android **8.0** o posterior. Se usa a diario en una Samsung Galaxy Tab A8 y se ha probado en una
Raspberry Pi 4 con Android. Basta una tableta modesta: la aplicación es nativa, sin navegador detrás.

**¿Tabletas Amazon Fire?**
Aún no probadas. Fire OS se basa en Android, sin los servicios de Google: la aplicación debería funcionar, y el recorte de
los avatares usa entonces el modelo incluido en la aplicación (bordes algo menos limpios). Se agradecen comentarios.

**¿Raspberry Pi?**
Sí, con Android instalado: consulte la [sección Raspberry Pi](../README.es.md#-raspberry-pi) del README. Use una
fuente de alimentación real de 5 V / 3 A: un puerto USB de ordenador o de base es demasiado débil, la pantalla pierde la
señal y aparece la indicación *Alimentación insuficiente*.

**¿Qué APK descargo?**
`…-arm64-v8a.apk` para casi todas las tabletas recientes y la Raspberry Pi. `…-armeabi-v7a.apk` para tabletas antiguas
de 32 bits. El APK universal funciona en todas partes, pero ocupa el triple.

**¿Por qué la aplicación es tan grande?**
Sobre todo por el recorte de los avatares en el dispositivo (modelos de Google ML Kit). El APK de la arquitectura de su
tableta ocupa unos 44 MB.

## 🏡 Home Assistant

**¿Hay que instalar algo en Home Assistant?**
No: ni tarjetas personalizadas, ni ayudantes, ni plantillas, ni automatizaciones. La aplicación lee las entidades de sus
integraciones y calcula el resto por sí misma (presencia, combustible más barato, alertas meteorológicas…). Dos cosas son
opcionales: mostrar la tableta como dispositivo en Home Assistant (*mobile_app*, para sus sensores y notificaciones) y el
satélite de voz.

**¿Hay que crear un token?**
No. El asistente inicia sesión con su cuenta de Home Assistant, desde el teléfono (código QR) o en la tableta, y crea el
token por sí mismo. Pegar un token creado por usted también funciona.

**¿Funciona fuera de casa?**
Sí, a través de un proxy inverso (Pangolin, Cloudflare Access…): añada sus cabeceras en *Ajustes › Home Assistant*. La
aplicación avisa si se usa una dirección `http://` fuera de la red local.

**¿Mi token está seguro?**
Se queda en la aplicación, en la tableta. En una copia de seguridad exportada se cifra con una clave ligada a la tableta:
en otra tableta se vuelve a pedir el token y hay que elegir de nuevo los códigos de acceso.

## 📹 Cámaras

**¿Qué cámaras son compatibles?**
Cualquier entidad `camera.*` de Home Assistant (captura y transmisión a través de Home Assistant), o una dirección
`rtsp://` / HLS directa (go2rtc…). Las cámaras con batería solo se activan cuando hace falta, según su alimentación (red,
solar, batería).

**La pantalla completa se queda en negro o indica un formato no compatible.**
Algunas tabletas no pueden decodificar transmisiones 4K H.265. La aplicación prueba entonces un decodificador por
software, luego la transmisión más ligera de la tarjeta, y recuerda los formatos rechazados (*Ajustes › Pantalla* para
restablecerlos). Elegir la transmisión «fluida» de la cámara para la pantalla completa suele resolverlo.

**¿De dónde sale el historial de detecciones?**
De la carpeta donde se guardan sus grabaciones: medios de Home Assistant, un recurso compartido SMB o NFS, o una carpeta
de la tableta. Cada captura se asocia con su vídeo.

## 🔒 Modo quiosco y actualizaciones

**¿Cómo abro los ajustes?**
Mantenga pulsada la esquina superior izquierda de la pantalla. Si hay un código maestro, se pide primero.

**¿Cómo bloqueo la tableta por completo?**
Convierta la aplicación en *propietaria del dispositivo* una vez (un comando `adb`, o un código QR tras un
restablecimiento): consulte [Modo quiosco](../README.es.md#-modo-quiosco). Antes de desinstalar: *Ajustes › Seguridad ›
Quitar los derechos de propietario*.

**¿Cómo funcionan las actualizaciones?**
La aplicación consulta las Releases de GitHub cada 12 horas e instala la nueva versión. La primera vez, Android pide que
permita a HA-MACS *instalar aplicaciones desconocidas*: después, la actualización continúa sola. Como propietaria del
dispositivo, no se pide nada.

## 🐛 Problemas

**La aplicación se cerró o algo no funciona.**
Abra *Ajustes › Acerca de › Registro de fallos*: cópielo (o descárguelo desde un teléfono con *Ajustes › Panel › Copia
de seguridad con el teléfono*) y péguelo en un [informe](https://github.com/Arubinu/ha-macs-android/issues/new/choose).
También muestra los errores de vídeo y de transmisión, con las direcciones ocultas. No comparta nunca su token, sus
códigos ni un archivo de copia de seguridad.

**Faltan las animaciones o van a saltos.**
Las animaciones de Android están desactivadas en la tableta (ajuste de accesibilidad u opciones para desarrolladores). La
aplicación lo detecta y muestra cómo corregirlo en *Ajustes › Pantalla*.

## ℹ️ Acerca de

**¿Es una aplicación oficial de Home Assistant?**
No, es un proyecto independiente. El personaje M.A.C.S. es obra de
[Glyn Davidson](https://github.com/glyndavidson/MACS) (CC BY-NC-SA 4.0, uso no comercial).

**¿Puedo ayudar?**
Con mucho gusto: informes con el registro, ideas, traducciones (8 idiomas hoy) y pruebas en otras tabletas.
