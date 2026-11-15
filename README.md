# Nexu — Assistente Virtual Inteligente em Java 21

Assistente desktop por voz, inspirado no JARVIS. Escuta o tempo todo, reconhece a palavra de
ativação **"Nexu"** (inclusive quando o reconhecedor transcreve "anexo", "nexo", "nexus"...),
entende comandos em linguagem natural, responde falando e executa tarefas.

Funcionalidades atuais: previsão do tempo (local ou de qualquer cidade, hoje/amanhã/depois de
amanhã), cronômetro inteligente, data e horário, encerramento por voz.

---

## 1. Arquitetura

```
                 ┌──────────────────────┐
 Microfone ────▶ │ VoskSpeechRecognition│  (thread nexu-stt)  transcrição offline pt-BR
                 └──────────┬───────────┘
                            │ texto final
                            ▼
                 ┌──────────────────────┐
                 │    NexuAssistant     │  (thread nexu-core) orquestrador, 1 frase por vez
                 │  ┌────────────────┐  │
                 │  │ eco? (AudioMgr)│  │  descarta a própria voz
                 │  ├────────────────┤  │
                 │  │WakeWordDetector│  │  "Nexu" / "anexo" com contexto
                 │  ├────────────────┤  │
                 │  │IntentRecognizer│  │  o que o usuário QUIS dizer
                 │  ├────────────────┤  │
                 │  │CommandProcessor│──┼──▶ handlers ──▶ WeatherService (nexu-http)
                 │  └────────────────┘  │                 TimerService   (nexu-timer)
                 └──────────┬───────────┘                 DateTimeService
                            │ resposta
                            ▼
                 ┌──────────────────────┐
                 │     AudioManager     │  (thread nexu-audio-out) fila única de fala e bipes;
                 │   + TextToSpeech     │  fecha o "microfone lógico" enquanto fala
                 └──────────────────────┘
                            │ eventos (AssistantListener)
                            ▼
                 ┌──────────────────────┐
                 │  NexuWindow (JavaFX) │  (thread FX) só exibe; nunca bloqueia
                 └──────────────────────┘
```

Princípios:

- **Separação total entre transcrição e execução.** O reconhecedor só produz texto; quem decide
  é o `NexuAssistant` com o `WakeWordDetector` e o `IntentRecognizer`.
- **Nada bloqueia a interface.** Áudio, rede, cronômetro e lógica rodam em threads próprias
  (todas *daemon*). A UI recebe eventos via `Platform.runLater`.
- **Cronômetro independente.** Usa `System.nanoTime()` (relógio monotônico) e prazo absoluto,
  continua contando enquanto o Nexu escuta, fala, consulta a internet ou está pausado.
- **Extensível.** Nova funcionalidade = uma `Intent` + uma `IntentRule` + um `CommandHandler`.

### Estrutura do projeto

```
Nexu/
├── pom.xml
├── README.md
└── src/
    ├── main/
    │   ├── java/br/com/nexu/
    │   │   ├── Main.java                       ponto de entrada (lança o JavaFX)
    │   │   ├── config/AppConfig.java           configuração em camadas (arquivo/env/-D)
    │   │   ├── core/
    │   │   │   ├── NexuAssistant.java          orquestrador e ciclo de vida
    │   │   │   ├── AssistantState.java         inicializando/escutando/processando/falando...
    │   │   │   ├── AssistantListener.java      eventos para a UI
    │   │   │   └── ConversationEntry.java      item do histórico
    │   │   ├── audio/
    │   │   │   ├── SpeechRecognition.java      contrato do STT
    │   │   │   ├── VoskSpeechRecognition.java  STT contínuo offline (Vosk)
    │   │   │   ├── TextToSpeech.java           contrato do TTS
    │   │   │   ├── AudioManager.java           fila de fala/bipe, anti-eco
    │   │   │   └── tts/
    │   │   │       ├── TtsFactory.java         escolhe o motor por SO/configuração
    │   │   │       ├── WindowsSapiTts.java     Windows: SAPI via PowerShell persistente
    │   │   │       ├── MacSayTts.java          macOS: say (Luciana)
    │   │   │       ├── EspeakTts.java          Linux: espeak-ng
    │   │   │       ├── CommandTts.java         qualquer motor externo (ex.: Piper)
    │   │   │       ├── ProcessTts.java         base para motores por processo
    │   │   │       └── NoVoiceTts.java         fallback quando não há motor
    │   │   ├── wakeword/
    │   │   │   ├── WakeWordDetector.java       fonética + fuzzy + contexto ("anexo")
    │   │   │   └── WakeWordResult.java
    │   │   ├── commands/
    │   │   │   ├── Intent.java                 intenções (ordem = prioridade)
    │   │   │   ├── IntentRule.java             contrato de regra (ponto de extensão)
    │   │   │   ├── IntentRecognizer.java       regras de intenção e extração de cidade
    │   │   │   ├── IntentMatch.java            intenção + parâmetros
    │   │   │   ├── CommandProcessor.java       roteamento e anti-duplicação
    │   │   │   ├── NumberParser.java           números por extenso → algarismos
    │   │   │   ├── DurationParser.java         "uma hora e meia" → 5400 s
    │   │   │   └── handlers/
    │   │   │       ├── CommandHandler.java
    │   │   │       ├── CommandResult.java
    │   │   │       ├── WeatherCommandHandler.java
    │   │   │       ├── TimerCommandHandler.java
    │   │   │       └── DateTimeCommandHandler.java
    │   │   ├── features/
    │   │   │   ├── WeatherService.java         Open-Meteo + geocoding + localização por IP
    │   │   │   ├── WeatherReport.java          dados e texto falado
    │   │   │   ├── WeatherCodes.java           códigos WMO em português
    │   │   │   ├── WeatherException.java
    │   │   │   ├── TimerService.java           cronômetro monotônico
    │   │   │   └── DateTimeService.java        data/hora do sistema
    │   │   ├── utils/
    │   │   │   ├── TextNormalizer.java         minúsculas, sem acentos, tokens
    │   │   │   ├── FuzzyMatcher.java           Levenshtein, Jaro-Winkler
    │   │   │   ├── PhoneticEncoder.java        chave fonética pt-BR
    │   │   │   └── SpeechFormatter.java        "duas horas", "4 minutos e 32 segundos"
    │   │   └── ui/
    │   │       ├── NexuApplication.java        Application do JavaFX
    │   │       ├── NexuWindow.java             janela principal
    │   │       └── ReactorView.java            animação "reator"
    │   └── resources/
    │       ├── application.properties          configurações padrão
    │       ├── logback.xml                     logs em console e ~/.nexu/logs
    │       ├── sounds/beep.wav                 bipe do cronômetro
    │       └── ui/nexu.css                     tema escuro azul/ciano
    └── test/java/br/com/nexu/...               31 testes (JUnit 5)
```

## 2. Tecnologias escolhidas

| Necessidade | Escolha | Por quê |
|---|---|---|
| Speech-to-Text | **Vosk 0.3.45** (Kaldi) | Gratuito, **offline**, contínuo, modelo pt-BR pronto, roda em Windows/macOS/Linux pelo próprio jar (JNA). Não depende de internet nem de chave. |
| Text-to-Speech | **Voz nativa do sistema** | Não existe biblioteca Java pura com boa voz pt-BR. Windows: SAPI (voz "Maria"); macOS: `say` (Luciana); Linux: espeak-ng. Gratuito e sem rede. Motores melhores (Piper) podem ser plugados por `tts.command`. |
| Captura/reprodução | `javax.sound.sampled` | Nativo do JDK, sem dependências. |
| Clima | **Open-Meteo** | Gratuito, **sem chave de API**, dados de serviços meteorológicos nacionais, inclui sensação térmica, umidade e probabilidade de chuva. Geocoding da própria Open-Meteo. |
| Localização | ip-api.com → ipapi.co | Gratuitos, sem chave; com fallback. Ou configure cidade/coordenadas fixas. |
| HTTP/JSON | `java.net.http.HttpClient` + Jackson 2.17 | Assíncrono nativo; Jackson estável. |
| Interface | **JavaFX 21** | Moderno, leve, CSS, Canvas para animação. |
| Logs | SLF4J 2 + Logback 1.5 | Padrão de mercado. |

### Compatibilidade verificada com Java 21

- JavaFX 21.0.5 é a linha LTS feita para o JDK 21.
- Vosk 0.3.45 usa JNA; o `pom.xml` fixa JNA 5.14.0 (compatível com Java 21, mesma API).
- Jackson 2.17.x, SLF4J 2.0.x e Logback 1.5.x suportam Java 21.
- O projeto roda no **classpath** (sem `module-info`), por isso `Main` não estende `Application`
  (requisito do JavaFX fora do sistema de módulos). O JavaFX pode imprimir o aviso
  *"Unsupported JavaFX configuration: classes were loaded from 'unnamed module'"* — é inofensivo.
- O código compila com `-Xlint:all` sem avisos.

## 3. Requisitos

1. **JDK 21** (Temurin, Oracle, Liberica...). Verifique com `java -version`.
2. **Maven 3.9+** (ou use o Maven embutido da sua IDE).
3. **Microfone**.
4. **Modelo Vosk em português** (passo abaixo).
5. **Voz pt-BR no sistema**:
   - **Windows 10/11:** Configurações → Hora e idioma → Fala → *Adicionar vozes* → Português (Brasil).
     Se a voz não aparecer para programas, instale também o pacote de idioma Português (Brasil)
     com o recurso "Fala". O Nexu escolhe a primeira voz pt-BR automaticamente (ex.: *Microsoft Maria Desktop*).
   - **macOS:** Ajustes do Sistema → Acessibilidade → Conteúdo Falado → Voz do sistema →
     Gerenciar vozes → Português (Brasil) → *Luciana*.
   - **Linux:** `sudo apt install espeak-ng` (Debian/Ubuntu) ou equivalente.

## 4. Instalação

### 4.1 Baixar o modelo de voz

Baixe em <https://alphacephei.com/vosk/models>:

- **`vosk-model-small-pt-0.3`** (~31 MB) — leve, recomendado para começar;
- `vosk-model-pt-fb-v0.1.1-20220516_2113` (~1,6 GB) — mais preciso, usa mais memória.

Descompacte dentro de uma pasta `models/` na raiz do projeto:

```
Nexu/
└── models/
    └── vosk-model-small-pt-0.3/
        ├── am/
        ├── conf/
        ├── graph/
        └── ...
```

Para usar outro local ou modelo, ajuste `vosk.model.path` (seção 5).

### 4.2 Compilar e testar

```bash
cd Nexu
mvn clean package
```

Isso compila, roda os testes e gera `target/nexu-1.0.0-all.jar` (jar único com todas as dependências).

### 4.3 Executar

Opção A — pelo Maven (desenvolvimento):

```bash
mvn javafx:run
```

Opção B — jar executável:

```bash
java -jar target/nexu-1.0.0-all.jar
```

Execute a partir da pasta `Nexu/` (onde está `models/`), ou configure um caminho absoluto em `vosk.model.path`.

> O jar gerado contém o JavaFX **do sistema operacional em que foi compilado**. Para rodar em
> outro SO, compile nele (ou use `mvn javafx:run`).

Opção C — IDE (IntelliJ/Eclipse/NetBeans): importe como projeto Maven e execute `br.com.nexu.Main`.

## 5. Configuração

Os valores padrão estão em `src/main/resources/application.properties`. Para personalizar **sem
recompilar**, crie um destes arquivos (o último vence):

1. `~/.nexu/application.properties`
2. `nexu.properties` na pasta onde o Nexu é iniciado
3. arquivo passado com `-Dnexu.config=/caminho/arquivo.properties`

Também valem variáveis de ambiente `NEXU_<CHAVE>` (pontos viram `_`) e `-Dnexu.<chave>=valor`.

Exemplo de `nexu.properties`:

```properties
weather.default.city=Recife
vosk.model.path=C:/Nexu/models/vosk-model-small-pt-0.3
tts.voice=Maria
assistant.conversation.timeout.seconds=15
```

Principais chaves:

| Chave | Padrão | Descrição |
|---|---|---|
| `vosk.model.path` | `models/vosk-model-small-pt-0.3` | Pasta do modelo Vosk |
| `audio.input.device` | (vazio) | Parte do nome do microfone; vazio = padrão do sistema |
| `wakeword.threshold` | `0.82` | Similaridade mínima para ativar |
| `wakeword.contextual.threshold` | `0.90` | Similaridade mínima para palavras reais ("anexo", "nexo") |
| `wakeword.aliases` | `nexu,nexus,nessu,...` | Transcrições aceitas diretamente |
| `wakeword.blocklist` | `nesse,nessa,...` | Palavras que nunca ativam |
| `assistant.conversation.timeout.seconds` | `12` | Tempo em que comandos são aceitos sem repetir "Nexu" |
| `audio.echo.guard.millis` | `700` | Microfone ignorado após o Nexu falar |
| `tts.engine` | `auto` | `auto`, `windows`, `macos`, `espeak`, `command`, `none` |
| `tts.voice` / `tts.rate` | | Voz e velocidade no Windows |
| `tts.command` | | Motor externo, ex.: `meu-piper.sh {text}` |
| `weather.default.city` | (vazio) | Cidade padrão; vazio = detectar por IP |
| `weather.default.latitude/longitude` | (vazio) | Coordenadas fixas (prioridade máxima) |
| `weather.api.key` | (vazio) | Só para plano comercial da Open-Meteo — **use `NEXU_WEATHER_API_KEY`** |
| `timer.max.hours` | `24` | Duração máxima do cronômetro |

Nenhuma chave de API é necessária para o uso normal, e nenhuma está no código-fonte.

## 6. Como usar

Ao abrir, o Nexu diz *"Olá senhor, estou à sua disposição."* e passa a escutar.

| Você diz | Nexu |
|---|---|
| "Nexu" (ou "anexo", isolado) | "Olá senhor, estou aqui. O que deseja?" |
| "Nexu, qual a previsão do tempo?" | temperatura, sensação, umidade, máx./mín., chuva e condição |
| "Como está o tempo em Recife?" | o mesmo, para Recife |
| "Previsão do tempo para amanhã em Curitiba" | previsão do dia seguinte |
| "Cronometre 10 minutos" / "Conte 1 minuto e 30 segundos" / "Cronômetro de uma hora e meia" | "Perfeitamente, senhor. Cronômetro iniciado para..." |
| "Quanto falta?" | "Senhor, faltam 4 minutos e 32 segundos." |
| "Cancelar cronômetro" | "Conforme solicitado, senhor. Cronômetro cancelado." |
| "Que horas são?" | "Senhor, agora são 23 horas e 15 minutos." |
| "Que dia é hoje?" | "Hoje é sábado, 14 de novembro de 2026." |
| "Tchau" / "Encerrar" / "Até mais, Nexu" | "Até logo, senhor. Foi um prazer ajudá-lo." e fecha |

Depois de chamar "Nexu", há uma **janela de conversa** (12 s por padrão, renovada a cada
resposta) em que você pode dar comandos seguidos sem repetir o nome. "Cronometre" sem duração
faz o Nexu perguntar "Por quanto tempo?", e basta responder "dez minutos".

Quando o cronômetro termina, o Nexu toca três bipes e diz *"Senhor, o tempo acabou."* — mesmo
com a janela minimizada ou com a escuta pausada — e traz a janela para a frente.

O botão **Pausar/Iniciar** libera o microfone para outros programas; o cronômetro continua.

## 7. Como a palavra "Nexu" é reconhecida

1. **Normalização:** minúsculas, sem acentos e pontuação (`"Néxu!"` → `nexu`).
2. **Variações conhecidas:** `nexus`, `nessu`, `neksu`... valem direto.
3. **Chave fonética pt-BR:** x/ks/cs/ss/z → *s*; "o" final → *u*; plural removido.
   `nexu`, `nexo`, `nexus`, `nessu`, `neksu` → todas viram `nesu`.
4. **Similaridade:** 70% fonética (Levenshtein) + 30% grafia (Jaro-Winkler), com limiar configurável.
5. **Palavras quebradas:** "ne xu" é reunido em "nexu".
6. **Contexto para "anexo" (e "nexo"):** só ativa se
   - for dito **isoladamente** ("anexo", "ei anexo"); ou
   - estiver no **início ou fim** de uma frase cujo restante é um **comando válido**
     ("anexo, que horas são?", "até mais, anexo").

   Não ativa em "abra **o** anexo", "segue **em** anexo", "anexo **do** e-mail", "anexo errado"
   ou com "anexo" no meio da frase.
7. **Bloqueios:** "nesse", "nessa", "sexo", "mexo"... nunca ativam.

**Anti-autoativação:** enquanto o Nexu fala (e 700 ms depois), o áudio do microfone é descartado
e o reconhecedor é reiniciado; além disso, frases parecidas com uma fala recente do Nexu são
tratadas como eco.

## 8. Interpretação de comandos

Prioridades do `IntentRecognizer` (a primeira regra que reconhecer vence):

1. Encerramento ("tchau", "até mais", "encerrar"; "desligar" só sem outro objeto — "desligue a luz" não fecha o Nexu).
2. **Previsão do tempo** — qualquer frase com "tempo" (tolerando erros como "tempu"), "previsão",
   "clima", "chuva", "temperatura"... **Exceções explícitas** para não quebrar o cronômetro:
   "quanto **tempo falta**", "conte um tempo de 5 minutos", "cancele o tempo do cronômetro".
3. Tempo restante ("quanto falta?" — com cronômetro ativo, até "falta muito?").
4. Cancelamento ("cancelar/parar/encerrar o cronômetro"; com cronômetro ativo, só "cancela").
5. Criação (verbo ou substantivo de cronômetro + duração, ou apenas a duração).
6. Horário. 7. Data.

Também: números por extenso ("vinte e cinco", "duas horas e quinze"), correspondência
aproximada de palavras longas ("cronometre", "cronometra", "cronômetro" → mesma palavra-chave),
e **anti-duplicação**: a mesma intenção com o mesmo texto aproximado em menos de 3 s é ignorada.

## 9. Adicionando novas funcionalidades

Exemplo: "Nexu, abra o navegador".

1. Em `Intent`, adicione `OPEN_APP("Abrir programa")`.
2. Registre uma regra (prioridade abaixo das existentes):
   ```java
   intents.registerRule((u, ctx) -> FuzzyMatcher.containsAny(u.tokens(), Set.of("abra", "abrir", "abre"))
           ? Optional.of(new IntentMatch(Intent.OPEN_APP, 0.9, Map.of("app", "navegador"), u.text(), u.normalized()))
           : Optional.empty());
   ```
3. Crie o handler e registre no `NexuAssistant`:
   ```java
   processor.register(match -> CompletableFuture.supplyAsync(() -> {
       // abrir o programa...
       return CommandResult.say("Abrindo o navegador, senhor.");
   }), Intent.OPEN_APP);
   ```

Para integrar IA (LLM), um handler de *fallback* pode ser registrado para `Intent.UNKNOWN`
e enviar a frase a uma API, mantendo a chave em variável de ambiente.

`NexuAssistant.submitText(String)` aceita texto como se fosse fala — útil para testes ou para
uma futura entrada por teclado.

## 10. Testes

```bash
mvn test
```

31 testes cobrem: normalização, fonética, fuzzy, números por extenso, durações, todas as
intenções (incluindo cidades e dias), palavra de ativação e o caso "anexo", cronômetro (relógio
monotônico, término real, substituição), data/hora, montagem da fala do clima a partir de JSON
real da Open-Meteo e um fluxo de conversa completo do assistente (sem microfone e sem voz).

## 11. Solução de problemas

| Sintoma | Causa provável / solução |
|---|---|
| "Modelo de reconhecimento de voz não encontrado" | Baixe e descompacte o modelo em `models/` ou ajuste `vosk.model.path`. |
| "Microfone indisponível" | Verifique permissões de microfone do SO (Windows: Privacidade → Microfone; macOS: Privacidade → Microfone para o Terminal/IDE/Java). |
| Nexu fala com sotaque inglês | Nenhuma voz pt-BR instalada; veja a seção 3. No Windows, o log mostra a voz selecionada. |
| "Sem voz (instale o espeak-ng)" | Linux sem motor de voz: `sudo apt install espeak-ng`. |
| Ativa sozinho com o som do alto-falante | Aumente `audio.echo.guard.millis` (ex.: 1200) ou use fone de ouvido. |
| Não reconhece "Nexu" | Veja no log/rodapé o que foi transcrito e acrescente a variação em `wakeword.aliases`, ou reduza `wakeword.threshold`. |
| Clima: "não consegui identificar sua localização" | Rede bloqueia os serviços de IP: configure `weather.default.city`. |

Logs: console e `~/.nexu/logs/nexu.log`.

## 12. Licenças de terceiros

Vosk (Apache 2.0), modelos Vosk (Apache 2.0), JavaFX (GPL v2 + Classpath Exception),
Jackson (Apache 2.0), SLF4J (MIT), Logback (EPL/LGPL), Open-Meteo (dados CC BY 4.0 — uso não
comercial gratuito), ip-api.com (gratuito para uso não comercial).
