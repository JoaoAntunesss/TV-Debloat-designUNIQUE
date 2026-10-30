# TV-Debloat-designUNIQUE

Ecrã inicial estilo Apple TV e limpeza ("debloat") para a TV **TCL Smart TV Pro (G08, Google TV, Android 12)**,
feito a partir do [tv-home](https://github.com/mihirgupta0900/tv-home) de Mihir Gupta (MIT).
Faz conjunto com o comando para o telemóvel **[TV-Remote-designUNIQUE](https://github.com/JoaoAntunesss/TV-Remote-designUNIQUE)**.

## O que faz
- **Debloat reversível:** desativa 20 apps de anúncios, telemetria e lixo da TCL e o ecrã Google TV com linhas patrocinadas
  ([lista](examples/tcl-google-tv-g08/debloat.txt)). Nada é desinstalado.
- **Ecrã inicial novo (em português):** relógio, data, dock com as apps principais
  (AntunesPlayer, SmartTube, Spotify, HBO Max, SkyShowtime, Netflix) e botões **Aplicações** e **Definições**.
  Fica como ecrã inicial por omissão e sobrevive a reiniciar.
- **Fundo (painel → Fundo):**
  - **Canal AntunesPlayer:** o último canal visto no AntunesPlayer a dar sem som atrás do menu.
    Pára quando se abre uma app ou após 30 min sem tocar no comando.
  - **Lareira (com som):** ao ligar a TV abre uma lareira em ecrã inteiro no SmartTube (YouTube sem anúncios).
  - **Fotos da natureza** (12 fotos da Wikimedia Commons, ver [créditos](launcher/WALLPAPER-CREDITS.md)).
- **Painel de controlo:** música a tocar, Wi‑Fi, saída de som (colunas da TV / Bluetooth), relógio em ecrã inteiro,
  tema de fundo, lareira, pesquisa, acessibilidade, imagem, fontes, definições, RAM e espaço livre.
- **Cartão "A tocar":** com a capa, ⏮ ⏯ ⏭ e "Ouvir", e o brilho na cor da capa.
- **Ecrã dividido:** tecla Menu do comando (Fontes) → *Ecrã dividido · HDMI n*: HDMI à esquerda, canal à direita;
  ◀ ▶ escolhem o som.
- **Serviço para o comando do telemóvel** (rede local, porta 8765): `/apps` (apps com imagens), `/now` (o que está a dar),
  `/inputs` (HDMI), `/do` (lareira, relógio, fontes, ecrã dividido), `/bg` (tema de fundo) e `/shot.jpg`
  (imagem do ecrã; vídeo protegido aparece preto).

## Instalar (Windows)
Requisitos: Android Studio (JDK + SDK build-tools 36 + platform 34) e, na TV, Opções de programador → depuração USB/rede.
```
powershell -ExecutionPolicy Bypass -File windows\install.ps1 -Tv 192.168.1.128
```
Desfazer: `windows\restore.ps1`. Só compilar: `launcher\build-windows.ps1` (gera `launcher\build\tvhome.apk`).

A primeira compilação cria `launcher/debug.keystore` (não está no repositório). Guarda-o: com outra chave a TV
não deixa instalar por cima e é preciso desinstalar primeiro.

## Notas desta TV
- `adb install` falha com verificação do Play Protect; o script envia o APK e instala com `pm install`.
- O APK tem de ter `targetSdk` ≥ 31, senão o Play Protect bloqueia ("App insegura").
- Um ecrã inicial transparente por cima de outra app não é desenhado nesta TCL; por isso o canal de fundo é reproduzido
  pelo próprio ecrã inicial.
- `aapt2 -A` no Windows grava os assets com `\`; o script de compilação junta-os à mão com `/`.
- A imagem do ecrã para o telemóvel é autorizada uma vez com `appops set com.mihir.tvhome PROJECT_MEDIA allow`
  (o `install.ps1` já o faz), para a TV não perguntar.

Código: MIT (ver [LICENSE](LICENSE)). README original: [docs/README-upstream.md](docs/README-upstream.md).
