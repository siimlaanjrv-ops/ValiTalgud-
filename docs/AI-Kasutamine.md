# AI-Kasutamine

Siin dokumendis on kokkuvõte ValiIT AI kursuse (BCS Koolitus, mai 2026) põhiõppetundidest ning konkreetsed soovitused, kuidas neid meie chatboti **Pam** paremaks muutmiseks rakendada.

---

## 1. Mida LLM tegelikult on (ja ei ole)

**LLM on tõenäosuste kalkulaator**, mitte mõtlev olend ega andmebaas.

- Genereerib teksti token haaval, valides iga kord kõige tõenäolisema järgmise sõna.
- **Ei ole andmebaas** — ei otsi, ei mäleta, ei arvuta.
- **Ei ole arutlusmootor** — ei mõista loogikat, põhjust ega tagajärge.
- **Hallutsinatsioonid on sisse ehitatud**: mudeli ülesanne on ennustada *usutavaim* järgmine token, mitte *kõige tõesem*.

**Meie chatbotile:** Pam ei tea reaalajas ürituste kuupäevi, hindu ega kasutajate andmeid — see on õige käitumine, mitte viga.

---

## 2. Prompti struktuur — 5 põhimõtet

Hea prompt ei ole küsimus, vaid **struktureeritud funktsioonikutse**. Iga argument on loomulikus keeles.

| Põhimõte | Kirjeldus |
|---|---|
| **Anna roll** | Kirjelda täpselt, kes mudel on ja mida "triaaž" sinu kontekstis tähendab |
| **Määra formaat** | Ütle täpselt, millise struktuuriga vastust ootad |
| **Defineeri piirangud** | Lubatud kategooriad, keel, keelatud teemad |
| **Anna näiteid (few-shot)** | 2–3 näidet näitavad mudelile täpselt soovitud tooni ja struktuuri |
| **Hinda tulemust** | Testi erinevate sisendite, äärjuhtumite ja ründetekstidega |

### Prompti rollid

```
system  →  "God Mode" juhised: roll, reeglid, formaat, näited
user    →  kasutaja tegelik sisend (muutuv)
```

Süsteemisõnum on fikseeritud vestluse alguses ja kasutajal on seda raskem üle kirjutada kui tavalist prompti.

### Markdown süsteemiprompis

Markdown on **tokenitõhusam** kui proosa või HTML:

- `## Pealkiri` kasutab vähem tokeneid kui pikk selgitav lõik
- `---` eraldab osad selgelt (roll, reeglid, näited)
- Loendid (`-`) toimivad semantiliste suunaviitadena
- Koodiblokid (` ``` `) ütlevad mudelile: "lülitu süntaksirežiimi"

**Konkreetne soovitus:** Meie `chatbot-context.md` on juba hea, kuid osad võib `---` eraldusjoonega kompaktida ilma sisu kaotamata, mis vähendab tokenite kulu iga päringu pealt.

---

## 3. Ankurdamine (Grounding)

**Ankurdamine** tähendab mudeli vastuste sidumist kontrollitavate faktidega.

Ilma ankurdamiseta toetub LLM ainult oma treeningandmetele, mis viib hallutsinatsioonide või aegunud teabeni.

### Ankurdamise viisid (lihtsast keeruliseni)

1. **Lubatud loendid promptis** — anna konkreetsed väärtused, millega mudel peab töötama
2. **Long context** — lisa kogu vajalik info otse süsteemiprompti (sobib väikestele andmetele)
3. **Function calling** — anna mudelile juurdepääs konkreetsetele API-dele (reaalajaandmed)
4. **RAG** — otsi vektorandmebaasist asjakohased tükid, lisa need prompti

**Meie chatbotile:** Pam on hästi ankurdatud (teab platvormi funktsioone, piirdub lubatud teemadega). Kui tulevikus lisame reaalajas ürituste andmeid, sobib function calling.

### Oluline: ära ankurda liiga palju

> "Ära groundi asju, mis ei ole olulised. Rohkem tokeneid = suurem kulu ja rohkem müra."

Konfliktne grounding (nt süsteem ütleb "ole lakooniline", aga näide on pikk) läheb vastuollu ja mudel eksib ära.

---

## 4. Näited promptis (Few-Shot Prompting)

### Kolm taset

| Tase | Kirjeldus | Kulu | Kasutus |
|---|---|---|---|
| **Zero-shot** | Ainult juhis, ilma näideteta | Madal | Lihtsad ülesanded |
| **One-shot** | Üks näide | Keskmine | Kui toon/formaat on kriitiline |
| **Few-shot** | 2–5 näidet | Kõrge | Keerulised mustrid, nišiloogika |

**Rusikareegel:** kasuta minimaalset vajalikku näidete arvu. Kui kaks näidet töötavad, ära kasuta viit.

**Meie chatbotile:** `chatbot-context.md` sisaldab juba näiteid — see on õige lähenemine. Veendu, et näited katavad:
- "Happy path" (tavaline küsimus)
- Äärjuhtum (ebamäärane küsimus)
- Ründesisend (prompt injection katse)

---

## 5. Hallutsinatsioonid — kuidas neid ohjata

### Miks mudelid hallutsineerivad?

- Statistiline ennustamine: täidab lüngad "usutavaga", kui täpseid andmeid pole
- Soov meeldida: eelistab anda vale vastuse, kui tunnistada, et ei tea
- Treeningandmete lüngad ja vastuolud

### Levinud mustrid

- Väljamõeldud kategooriad (`PET_INQUIRY`, `EMOTIONAL`)
- Väljamõeldud faktid (`"Tagasimaks kinnitati 12. märtsil"`)
- Enesekindel "ma ei tea", mis on maskeeritud andmetena (`"Kliendil on kaebus"`)

### Halb lahendus

> `"KASUTA AINULT selle nimekirja kategooriaid"` — töötab ~90% ajast. 10% on katkised päringud.

### Õige lahendus: tüübisüsteem Java poolel

```java
public enum ChatTopic { PLATFORM, EVENTS, SHOP, OFFICE, OFF_TOPIC }

// Kui mudel tagastab tundmatu väärtuse → Jackson ei suuda deserialiseerida
// catch plokis kasuta vaikekateegooria fallback
```

**Mudel on vale koht suletud nimekirja jõustamiseks. Java on õige koht.**

---

## 6. Parameetrid — temperatuur, Top-K, Top-P

LLM-i väljundit juhivad kolm põhiparameetrit:

| Parameeter | Mis see on | Madalal | Kõrgel |
|---|---|---|---|
| **Temperature** | "Loovus" | Deterministlik, ennustatav | Loominguline, varieeruv |
| **Top-K** | Valik K kõige tõenäolisema sõna seast | Faktiline, täpne | Laiem valik |
| **Top-P** | Valik kuni P tõenäosuse summani | Kindel, enesekindel | Dünaamilisem |

### Soovitused kasutustüübi järgi

| Kasutus | Temperature | Top-P | Top-K |
|---|---|---|---|
| **Chatbot (Pam)** | `0.5` | `0.95` | `30` |
| Täpne/tehniline | `0.1` | `0.9` | `20` |
| Loov | `1.0–1.2` | `0.99` | `40` |

**Meie chatbotile:** Temperature `0.5` on õige valik — Pam peab olema sõbralik ja varieeruv, aga mitte hallutsineeriv.

> NB: üle 1.5 minnes võivad vastused päris jaburaks minna.

---

## 7. AI Turvalisus

### Peamised rünnakud

**LLM01 — Prompt Injection**
> Ründaja tekst tühistab süsteemijuhise. Mudelil pole võimalust teada, kelle juhis peaks peale jääma — tavaliselt eelistatakse värskemat ja spetsiifilisemat juhist. See on ründaja oma.

**LLM04 — Model Denial of Service**
> Ründaja saadab 50 000 tähemärgise päringu, vigaste päringute tsükli või rekursiivse prompti. Kulud suurenevad, päringupiirang saab täis, legitiimsed kasutajad lukustatakse välja.

### Kaitsekihid (defense in depth)

```
Kasutaja sisend
    ↓
[Kiht 1] — sisendi puhastamine: piira pikkust, eemalda ohtlikud märgid
    ↓
[AI päring] — mudel võib ründekatse läbida
    ↓
[Kiht 2] — enum-valideerimine: tundmatu kategooria → fallback väärtus
    ↓
[Kiht 3] — sisublokkeering: regex blokeerib ohtlikud fraasid (nt "refund approved")
    ↓
[Kiht 4] — väljundi pikkuse piiramine: kaitseb allavoolu süsteeme
    ↓
Kasutajale kuvatav vastus
```

### Rate Limiter

Kolm põhjust lisada:
1. **Kulu** — iga päring maksab, ründaja tsükkel on arve
2. **Tasuta paketi kaitse** — Groq/Gemini tasuta tier lukustab su välja pärast N päringut/min (see juhtus meie chatbotiga!)
3. **Latentsus legitiimsetele kasutajatele** — ründeliiklus paneb teised ootama

```java
// Lihtne IP-põhine rate limiter (õpetuslikul eesmärgil)
@Component
public class RateLimiter {
    private final ConcurrentHashMap<String, Long> lastCall = new ConcurrentHashMap<>();
    private static final long MIN_INTERVAL_MS = 1000; // 1 päring/sek IP kohta

    public boolean allow(String ip) {
        long now = System.currentTimeMillis();
        Long prev = lastCall.put(ip, now);
        return prev == null || (now - prev) >= MIN_INTERVAL_MS;
    }
}
```

> Tootmises: kasuta **Bucket4j** (lubab burst-i, jagab olekut mitu instantsi vahel, toetab per-user ja per-endpoint piiranguid).

### Regex blocklist väljundile

```java
static final Pattern BLOCKLIST = Pattern.compile(
    "(?i)\\b(refund\\s+approved|administrator\\s+(mode|access)|override|debug\\s+mode)\\b"
);
```

Regex püüab 80% rünnakutest odavalt kinni. Hindav LLM (LLM-as-judge) püüab ülejäänu kallilt. Mõlemaid koos kasutamine on tootmise standard.

---

## 8. Kokkuvõte — mida meie chatbot vajab

### Olemasolevad tugevused ✓
- Süsteemiprompt on struktureeritud (roll, lubatud/keelatud teemad, näited)
- Manipulatsioonikaitse on promptis olemas
- Vastuste keelamine teemavälisele sisendile töötab

### Prioritiseeritud parendusvõimalused

| # | Muudatus | Põhjus | Keerukus |
|---|---|---|---|
| 1 | **Rate limiter** IP-põhine backendis | Groq 429 vead — praegu saab üks kasutaja kõik tokenid ära kulutada | Madal |
| 2 | **Sisendi pikkuse piiramine** backendis | Model DoS kaitse, kulud | Madal |
| 3 | **Vestluse ajaloo piiramine** (viimased N sõnumit) | Praegu saadetakse kogu ajalugu → tokenikulud kasvavad lõpmatuseni | Madal |
| 4 | **Temperature seadistamine** (0.5) | Praegune väärtus tuleks üle kontrollida — chatbotile sobib 0.5 | Madal |
| 5 | **Few-shot näited** süsteemiprompis | Parandab tooni järjepidevust | Keskmine |
| 6 | **Regex blocklist** väljundile | Kaitseb ohtlike vastuste eest | Keskmine |

---

## 9. Kasulikud ressursid

- **PromptFoo** — promtide testimiseks (happy path, edge cases, adversarial inputs): `npm install -g promptfoo`
- **Tokenizer** — tokenite arvu kontrollimiseks: https://platform.openai.com/tokenizer
- **Bucket4j** — tootmiseks sobiv rate limiter Javas
- **LLMOps tööriistade loend**: https://github.com/tensorchord/Awesome-LLMOps