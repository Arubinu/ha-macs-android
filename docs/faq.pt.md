# ❓ FAQ

[English](faq.md) · [Français](faq.fr.md) · [Deutsch](faq.de.md) · [Español](faq.es.md) · [Italiano](faq.it.md) · [Nederlands](faq.nl.md) · **Português** · [Polski](faq.pl.md)

## 📱 Dispositivos

**Que tablets funcionam?**
Qualquer tablet com Android **8.0** ou superior. Usada todos os dias num Samsung Galaxy Tab A8 e testada num Raspberry
Pi 4 com Android. Basta um tablet modesto: a aplicação é nativa, sem navegador por trás.

**Tablets Amazon Fire?**
Ainda não testados. O Fire OS baseia-se no Android, sem os serviços Google: a aplicação deve funcionar, e o recorte dos
avatares usa então o modelo incluído na aplicação (contornos um pouco menos limpos). Os comentários são bem-vindos.

**Raspberry Pi?**
Sim, com Android instalado: veja a [secção Raspberry Pi](../README.pt.md#-raspberry-pi) do README. Use uma verdadeira
fonte de alimentação de 5 V / 3 A: uma porta USB de computador ou de dock é demasiado fraca, o ecrã perde o sinal e
aparece a indicação *Alimentação insuficiente*.

**Que APK devo transferir?**
`…-arm64-v8a.apk` para quase todos os tablets recentes e o Raspberry Pi. `…-armeabi-v7a.apk` para tablets antigos de
32 bits. O APK universal funciona em todo o lado, mas é três vezes maior.

**Porque é que a aplicação é tão grande?**
Sobretudo por causa do recorte dos avatares no dispositivo (modelos Google ML Kit). O APK da arquitetura do seu tablet
tem cerca de 50 MB. A **versão leve** (`…-lite-universal.apk`, 15 MB, para todos os tablets) não tem Google ML
Kit: os avatares são então fotos redondas, sem recorte; tudo o resto é igual.

## 🏡 Home Assistant

**É preciso instalar algo no Home Assistant?**
Não: nem cartões personalizados, nem auxiliares, nem modelos, nem automatizações. A aplicação lê as entidades das suas
integrações e calcula o resto sozinha (presença, combustível mais barato, avisos meteorológicos…). Duas coisas são
opcionais: mostrar o tablet como dispositivo no Home Assistant (*mobile_app*, para os seus sensores e notificações) e o
satélite de voz.

**É preciso criar um token?**
Não. O assistente inicia sessão com a sua conta do Home Assistant, a partir do telemóvel (código QR) ou no tablet, e cria
o token sozinho. Colar um token criado por si também funciona.

**Funciona fora de casa?**
Sim, através de um proxy inverso (Pangolin, Cloudflare Access…): adicione os cabeçalhos em *Definições › Home
Assistant*. A aplicação avisa se for usado um endereço `http://` fora da rede local.

**O meu token está seguro?**
Fica na aplicação, no tablet. Numa cópia de segurança exportada, é cifrado com uma chave ligada ao tablet: noutro tablet,
o token é pedido de novo e os códigos de acesso têm de ser escolhidos outra vez.

## 📹 Câmaras

**Que câmaras são suportadas?**
Qualquer entidade `camera.*` do Home Assistant (captura e stream através do Home Assistant), ou um endereço `rtsp://` /
HLS direto (go2rtc…). As câmaras a bateria só são acordadas quando é preciso, conforme a alimentação (corrente, solar,
bateria).

**O ecrã inteiro fica preto ou indica um formato não suportado.**
Alguns tablets não conseguem descodificar streams 4K H.265. A aplicação tenta então um descodificador por software,
depois o stream mais leve do cartão, e memoriza os formatos recusados (*Definições › Ecrã* para os repor). Escolher o
stream «fluido» da câmara para o ecrã inteiro costuma resolver.

**De onde vem o histórico de deteções?**
Da pasta onde as gravações são guardadas: multimédia do Home Assistant, uma partilha SMB ou NFS, ou uma pasta do tablet.
Cada captura é associada ao seu vídeo.

## 🔒 Modo quiosque e atualizações

**Como abro as definições?**
Mantenha premido o canto superior esquerdo do ecrã. Se houver um código mestre, é pedido primeiro.

**Como bloqueio o tablet por completo?**
Torne a aplicação *proprietária do dispositivo* uma vez (um comando `adb`, ou um código QR após uma reposição): veja
[Modo quiosque](../README.pt.md#-modo-quiosque). Antes de desinstalar: *Definições › Segurança › Retirar os direitos de
proprietário*.

**Como funcionam as atualizações?**
A aplicação consulta as Releases do GitHub a cada 12 horas e instala a nova versão. Da primeira vez, o Android pede para
permitir que o HA-MACS *instale aplicações desconhecidas*: depois a atualização continua sozinha. Como proprietária do
dispositivo, nada é pedido.

## 🐛 Problemas

**A aplicação falhou ou algo não funciona.**
Abra *Definições › Acerca de › Registo de falhas*: copie-o (ou transfira-o a partir de um telemóvel com *Definições ›
Painel › Cópia de segurança com o telemóvel*) e cole-o num
[relatório](https://github.com/Arubinu/ha-macs-android/issues/new/choose). Também lista os erros de vídeo e de stream,
com os endereços ocultos. Nunca partilhe o seu token, os seus códigos ou um ficheiro de cópia de segurança.

**As animações faltam ou estão aos solavancos.**
As animações do Android estão desativadas no tablet (definição de acessibilidade ou opções de programador). A aplicação
deteta-o e mostra como corrigir em *Definições › Ecrã*.

## ℹ️ Acerca de

**É uma aplicação oficial do Home Assistant?**
Não, é um projeto independente. A personagem M.A.C.S. é obra de
[Glyn Davidson](https://github.com/glyndavidson/MACS) (CC BY-NC-SA 4.0, uso não comercial).

**Posso ajudar?**
Com todo o gosto: relatórios com o registo, ideias, traduções (8 idiomas hoje) e testes noutros tablets.
