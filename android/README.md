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

## Como compilar e instalar

### Pré-requisitos

- Android Studio Iguana ou superior (Gradle 8.7).
- JDK 17.
- Aparelho Android **8.0 (API 26)** ou superior. Para o LLM rodar bem, recomendo
  **6 GB+ de RAM**.

### Passos

```bash
cd android
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Configurar o LLM local (Gemma)

1. Baixe o modelo em formato `.task` da [galeria do MediaPipe](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference)
   (recomendado: **Gemma 2 2B IT, INT4**, ≈1.4 GB).
2. Copie pro aparelho:
   ```bash
   adb push gemma-2-2b-it-int4.task /sdcard/Android/data/com.jarvis.assistant/files/llm/gemma.task
   ```
3. Abra o app — o JARVIS detecta sozinho e aquece o modelo.

> Se o senhor não copiar o modelo, o JARVIS ainda escuta, transcreve e responde
> por TTS, mas vai avisar polidamente que o modelo offline ainda não está
> instalado.

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
