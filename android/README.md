# JARVIS — App Android

Assistente de voz pessoal que **roda 100% no seu celular**. Sem servidor, sem API
do Anthropic ou Fish Audio: o reconhecimento de voz, a geração de respostas (LLM)
e a fala (TTS) acontecem todos no próprio aparelho. As únicas chamadas à internet
são as buscas em tempo real (GitHub, arXiv e Google News) — e mesmo essas só
ocorrem quando você pede ou pelo refresh em segundo plano.

> Tudo é em **português do Brasil**, com a personalidade do JARVIS do filme do
> Homem de Ferro: mordomo britânico, irônico na medida, sempre tratando você
> por **"senhor"**.

## O que ele faz

- **Sempre escutando** — palavra de ativação `"JARVIS"` em mãos livres, igual
  "Ok Google". Não precisa apertar botão.
- **Funciona com a tela apagada** — `Foreground Service` + wake-lock parcial.
- **Inicia no boot** — Receiver para `BOOT_COMPLETED`.
- **Controla o celular** — abre apps, faz ligações, manda SMS, ajusta volume e
  brilho, cria alarmes, abre câmera, faz screenshot, lê o que está na tela e
  toca em botões pelo nome (via *Accessibility Service*).
- **Aprende em tempo real** — busca em GitHub, arXiv e Google News quando você
  pergunta sobre algum tema; um worker periódico mantém um cache de novidades
  atualizado a cada 30 minutos.
- **Personalidade JARVIS-MCU em pt-BR** — sarcástico em momentos certos,
  proativo, calmo sob pressão, sempre te chamando de senhor.

## Arquitetura

```
                ┌────────────────────────────────────────────┐
                │         JarvisForegroundService            │
                │  (rodando o tempo todo, com a tela off)    │
                └─────┬───────────────┬──────────────┬───────┘
                      │               │              │
                ┌─────▼─────┐   ┌─────▼──────┐   ┌──▼──────────┐
                │ WakeWord  │   │   STT      │   │ LocalConver- │
                │ Detector  │──▶│ pt-BR      │──▶│ sationEngine │
                │ "JARVIS"  │   │ (Android)  │   └──┬───────────┘
                └───────────┘   └────────────┘      │
                                                    │
                            ┌───────────────────────┴─────────────┐
                            │                                     │
                      ┌─────▼─────┐  ┌──────────────┐   ┌─────────▼────────┐
                      │  LocalLlm │  │ RealtimeLearn│   │ PhoneActionRouter│
                      │ (Gemma /  │  │ (GitHub /    │   │  + Accessibility │
                      │ MediaPipe)│  │  arXiv / News)│  │     Service      │
                      └─────┬─────┘  └──────────────┘   └──────────────────┘
                            │
                       ┌────▼─────┐
                       │ LocalTts │
                       │ (Android │
                       │  pt-BR)  │
                       └──────────┘
```

## Como instalar (caminho fácil — APK pronto)

Toda vez que esta branch recebe um commit, o **GitHub Actions** monta o APK
automaticamente. O senhor só precisa baixar.

1. Abra https://github.com/luanmatheus059/jarvis-ethan/releases no celular.
2. Procure o release mais recente (`JARVIS Android build #N`).
3. Baixe `app-debug.apk`.
4. Toque no APK no notificador. Se o Android pedir, autorize "Instalar de
   fontes desconhecidas" para o navegador / gerenciador de arquivos.
5. Abra o app `JARVIS`.

> Se preferir compilar você mesmo: `cd android && ./gradlew :app:assembleDebug`
> (precisa de JDK 17 + Android SDK + plataforma 34).

## Setup dentro do app (tudo no celular, sem PC)

Ao abrir o app pela primeira vez, a tela inicial mostra cards com checklist:

1. **Cérebro local (LLM)** → toque em "Baixar agora". Padrão: Falcon-RW-1B INT8
   (~570 MB, gratuito, sem login). Pode trocar por outro `.task` colando uma URL.
2. **Voz personalizada** → toque em "Enviar áudio" e escolha o MP3/WAV com a
   voz desejada. O app calibra pitch e velocidade do TTS local pra imitar
   o tom (clonagem neural completa offline ainda não cabe num celular comum
   sem dor — esta é a melhor opção 100% gratuita).
3. **Controle do telefone (Acessibilidade)** → toque para abrir a tela do
   sistema e ative `JARVIS — Controle do Telefone`.
4. **Definir como Assistente** (opcional) — permite chamar pelo gesto do botão
   power.
5. **Ignorar otimização de bateria** — sem isso, o Android pode matar o serviço
   depois de algumas horas.

Em seguida toque no botão grande **Ativar JARVIS**. Pronto: ele vai ficar
ouvindo `"JARVIS"` em segundo plano, mesmo com a tela apagada.

Para parar a qualquer momento, abra o app e toque em **Parar JARVIS**.

### Permissões a conceder

Na primeira execução o app pede:

- **Microfone** (obrigatório — é assim que ele te ouve).
- **Notificações** (para a notificação persistente do serviço).

E há três passos manuais:

1. **Configurações → Acessibilidade → JARVIS — Controle do Telefone** → ativar.
   Sem isso, ele não consegue tocar em botões nem ler a tela.
2. **Configurações → Aplicativos → Aplicativos padrão → Assistente digital →
   JARVIS**. Permite acioná-lo pelo gesto do botão power e pelo atalho
   "Hey JARVIS" do sistema.
3. **Configurações → Bateria → Otimização → JARVIS → Não otimizar**. Sem isso,
   o Android pode matar o serviço em segundo plano depois de algumas horas.

### Idioma e voz TTS

O app usa o **TTS do Android** em pt-BR. Para uma voz mais grave (estilo JARVIS):

- *Configurações → Acessibilidade → Saída de texto pra fala*.
- Instalar uma voz natural em pt-BR (Google, Vocalizer ou RHVoice funcionam).
- No `LocalTextToSpeech.kt` o pitch padrão é `0.85` — ajuste a gosto.

## Tags de ação que o JARVIS gera

Quando o LLM decide executar algo no telefone, ele termina a resposta com uma
tag. O `PhoneActionRouter` parseia tudo automaticamente:

| Tag                                     | Efeito                                                |
| --------------------------------------- | ----------------------------------------------------- |
| `[PHONE:OPEN_APP] com.whatsapp`         | Abre o app pelo *package name*                        |
| `[PHONE:OPEN_URL] https://…`            | Abre URL no navegador padrão                          |
| `[PHONE:CALL] +55119…`                  | Discagem direta                                       |
| `[PHONE:SMS] número \|\|\| mensagem`    | Manda SMS                                             |
| `[PHONE:VOLUME] 80`                     | Volume de mídia em 80%                                |
| `[PHONE:BRIGHTNESS] 50`                 | Brilho em 50% (precisa de WRITE_SETTINGS)             |
| `[PHONE:ALARM] 07:30 \|\|\| Reunião`    | Cria alarme no relógio                                |
| `[PHONE:HOME]` / `[PHONE:BACK]` / etc.  | Gestos do sistema                                     |
| `[PHONE:LOCK]`                          | Trava a tela (Android 9+)                             |
| `[PHONE:SCREENSHOT]`                    | Captura de tela (Android 9+)                          |
| `[PHONE:CAMERA]`                        | Abre câmera                                           |
| `[PHONE:TAP_TEXT] Salvar`               | Toca no botão "Salvar" da tela atual                  |
| `[LEARN] termo de pesquisa`             | Dispara busca tempo real (GitHub + arXiv + Notícias)  |

## Privacidade

- O microfone só envia áudio para o **SpeechRecognizer do Android** (que pode
  fazer reconhecimento on-device se o pacote de idiomas estiver baixado).
- Nenhum áudio nem transcrição é mandado para a internet pelo app.
- O LLM roda **inteiramente no aparelho** via MediaPipe.
- As únicas chamadas externas são para `api.github.com`, `export.arxiv.org` e
  `news.google.com` — todas leitura de dados públicos.

## Diretórios

```
android/
  app/
    src/main/java/com/jarvis/assistant/
      JarvisApplication.kt        # Classe Application + canal de notificação
      JarvisSettings.kt           # DataStore (idioma, wake word, background)
      service/                    # Foreground service + boot receiver + voice interaction
      audio/                      # SpeechToText (STT) + LocalTextToSpeech (TTS)
      wakeword/                   # Detector da palavra "JARVIS"
      personality/                # JarvisPersona (system prompt) + LocalLlm + Engine
      learning/                   # RealtimeLearner + NewsRefreshWorker
      accessibility/              # Accessibility Service + PhoneActionRouter
      ui/                         # MainActivity (orb) + SettingsActivity
    res/                          # Strings, temas, ícone, configs XML
    AndroidManifest.xml
  build.gradle.kts
  settings.gradle.kts
```
