# Valitalgud PWA — Dokumentatsioon

## Mis on PWA?

**Progressive Web App (PWA)** on veebileht, mis käitub nagu mobiilirakendus. Kasutaja saab selle "installida" oma telefoni avakuvale ilma App Store'i või Google Play'ta. Pärast installimist:

- avaneb ilma brauseri aadressiribata (nagu päris äpp)
- töötab osaliselt ka ilma internetiühenduseta (vahemälu abil)
- saab seadet kaasaegse ikooni ja nimega

PWA ei vaja App Store'i, ei maksa midagi ja töötab nii Android'il kui iOS'il.

---

## Miks eraldi kaust `pwa/`?

`frontend/` on olemasolev veebirakendus. `pwa/` on sellest **täiesti eraldiseisev koopiaga projekt**, millele on lisatud mobiili- ja PWA-spetsiifiline konfiguratsioon. Põhjused:

- **Selgus:** kõik PWA failid on ühes kohas, segamini midagi ei ole
- **Iseseisvus:** mõlemat projekti saab arendada sõltumatult
- **Ohutus:** muutused `pwa/`-s ei mõjuta `frontend/`-i ja vastupidi

Mõlemad projektid ühenduvad sama Spring Boot backendiga (port 8080).

---

## Kaustade struktuur

```
pwa/
├── public/                        # Staatilised failid (kopeeritakse buildi juurkausta)
│   ├── icon.svg                   # PWA ikoonide LÄHTEALLIKAS — muuda seda, kui soovid ikooni vahetada
│   ├── pwa-64x64.png              # Genereeritud ikoon (brauseri vahekaart)
│   ├── pwa-192x192.png            # Genereeritud ikoon (Android avakuva)
│   ├── pwa-512x512.png            # Genereeritud ikoon (splash screen)
│   ├── maskable-icon-512x512.png  # Genereeritud ikoon (Android adaptive icon — ovaalne kujund)
│   ├── apple-touch-icon-180x180.png # Genereeritud ikoon (iOS avakuva)
│   ├── favicon.ico                # Genereeritud brauser ikoon
│   ├── favicon.svg                # Originaalne Valitalgude logo SVG (kopeeritud frontendist)
│   └── images/                    # Pildid (tooted, hero jms — kopeeritud frontendist)
│
├── src/                           # Lähtekood (identne frontendiga, välja arvatud ChatbotWidget)
│   ├── api-services/              # Axios API päringud (üks fail ressursi kohta)
│   ├── assets/                    # CSS ja pildid
│   ├── auth/                      # Autentimise loogika
│   ├── components/                # Vue komponendid (sh uuendatud ChatbotWidget streaming-iga)
│   ├── navigation/                # Navigatsioon
│   ├── router/                    # Vue Router marsruudid
│   └── views/                     # Leheküljed
│
├── dist/                          # Valmis build (tekib npm run build järel)
│   ├── sw.js                      # Service worker — vahemälu ja offline loogika
│   └── workbox-*.js               # Workbox'i abiteek service workeri jaoks
│
├── index.html                     # HTML sisendpunkt — PWA meta-sildid
├── vite.config.js                 # Vite + VitePWA seadistus
├── pwa-assets.config.js           # Ikoonide generaatori seadistus
├── package.json                   # Sõltuvused ja skriptid
└── PWA.md                         # See fail
```

---

## Mis faile loodi ja miks

### `public/icon.svg` — ikoonide lähteallikas

Lihtne SVG: kollane taust (#FFD600), must kiri "VT" ja must ääris. See vastab Valitalgude neobrutalismi disainile. Kõik PNG ikoonid **genereeritakse sellest failist automaatselt**.

Kui soovid ikooni muuta: muuda `icon.svg`-d ja käivita uuesti `npm run generate-icons`.

### `pwa-assets.config.js` — ikoonide generaatori seadistus

```js
import { defineConfig, minimal2023Preset } from '@vite-pwa/assets-generator/config'

export default defineConfig({
  preset: minimal2023Preset,  // genereerib kõik vajalikud suurused automaatselt
  images: ['public/icon.svg'], // lähteallikas
})
```

`minimal2023Preset` loob täpselt need formaadid, mida nüüdisaegsed brauserid ja mobiiliseadmed vajavad:
- 64×64 (vahekaardi ikoon)
- 192×192 (Android avakuva)
- 512×512 (splash screen)
- 512×512 maskable (Android adaptive icon — "ovaalne" kujund)
- 180×180 apple-touch-icon (iOS avakuva)
- favicon.ico (vana formaat brauserite jaoks)

### `index.html` — PWA meta-sildid

Võrreldes `frontend/index.html`-iga on lisatud:

```html
<!-- Mida lisati ja miks -->

<meta name="theme-color" content="#FFD600" />
<!-- Brauseri ja Android'i ülariba värv — kasutaja näeb kuldset värvi -->

<meta name="apple-mobile-web-app-capable" content="yes" />
<!-- iOS: luba "täisekraan" režiim — aadressiriba kaob -->

<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent" />
<!-- iOS: staatusriba stiil — muudab läbipaistvaks et äpp näeb "puhast" välja -->

<meta name="apple-mobile-web-app-title" content="Valitalgud" />
<!-- iOS avakuval kuvatav nimi ikooni all -->

<link rel="apple-touch-icon" href="/apple-touch-icon-180x180.png" />
<!-- iOS avakuva ikoon — iOS ei loe Web App Manifesti ikoonide kirjet, vajab eraldi linki -->
```

NB: Android loeb ikoonid manifest'ist, iOS vajab eraldi `apple-touch-icon` linki — mõlemad on lisatud.

### `vite.config.js` — VitePWA seadistus

Kõige olulisem fail. Kolm osa:

**1. Manifest — äpi "isikutunnistus"**
```js
manifest: {
  name: 'Valitalgud',       // täisnimi installimise dialoogis
  short_name: 'Valitalgud', // nimi avakuval (lühidalt)
  theme_color: '#FFD600',   // Android'i ülariba värv
  display: 'standalone',    // äpp avaneb ilma brauseri aadressiribata
  orientation: 'portrait',  // püstine orientatsioon (sündmuste leht sobib paremini)
  start_url: '/',           // milliselt lehelt äpp avaneb
  lang: 'et',               // eesti keel
}
```

**2. Workbox — service worker ja vahemälu**

Service worker on "vahemees" brauseri ja serveri vahel. Ta otsustab, millised päringud lähevad võrku ja millised serveeritakse vahemälust.

```js
workbox: {
  globPatterns: ['**/*.{js,css,html,ico,png,svg,jpg,woff2}'],
  // Kõik need failid laaditakse KOHE installimise ajal vahemällu (precache).
  // Tulemus: äpp töötab kohe esimesel avamisel, isegi kui internet on aeglane.

  globIgnores: ['**/hero-*.png', '**/Adobe*.png', '**/*.webp'],
  // Suured pildid (hero ~3MB, gif ~1MB) jäetakse precache'ist välja.
  // Põhjus: vahemällu laadimine võtaks liiga kaua ja suurendaks installimisaega.
  // Need pildid laetakse võrgust (NetworkFirst) ja jäävad hiljem runtime cache'i.

  runtimeCaching: [
    {
      urlPattern: /\/api\//,
      handler: 'NetworkFirst',
      // API päringud: proovi ALATI võrku, kasuta vahemälu ainult siis kui võrku pole.
      // Põhjus: kasutaja peab nägema värsket infot (sündmused, registreerimised jms).
      // networkTimeoutSeconds: 10 — kui server ei vasta 10 sekundiga, kasuta vahemälu.
    },
    {
      urlPattern: /^https:\/\/fonts\.googleapis\.com\/.*/i,
      handler: 'CacheFirst',
      // Google Fonts: laadi ALATI vahemälust (kui olemas).
      // Põhjus: fondid muutuvad harva, võrgupäring on tarbetu.
    },
  ],
}
```

**3. `registerType: 'autoUpdate'`**

Kui server avalikustab uue versiooni, uuendab service worker ennast automaatselt taustal. Kasutajal pole vaja lehte manuaalselt uuendada — järgmisel avamisel on uus versioon automaatselt aktiivsed.

### `package.json` — sõltuvused

Võrreldes `frontend/package.json`-iga:
- **Lisatud:** `vite-plugin-pwa`, `@vite-pwa/assets-generator`
- **Eemaldatud:** kõik lintimistööriistad (oxlint, eslint, prettier) — PWA projektis pole neid vaja
- **Port muudetud:** 8082 (et ei läheks konflikt frontendi 8081-ga)
- **Nimi muudetud:** `valitalgud-pwa`

---

## Kuidas käivitada

### Arendus

```bash
# 1. Mine pwa/ kausta
cd pwa

# 2. Paigalda sõltuvused (ainult esimest korda)
npm install

# 3. Käivita arendusserver
npm run dev
# → avab http://localhost:8082
# NB: PWA funktsioonid (service worker, install) töötavad ainult buildis, mitte dev-serveris
```

Arenduseks on vaja, et backend töötaks pordil 8080 (`./gradlew bootRun`).

### Tootmise build

```bash
npm run build
# → loob dist/ kausta koos sw.js service workeriga
```

### Ikoonide uuesti genereerimine

```bash
# Muuda public/icon.svg-d, siis:
npm run generate-icons
# → uuendab kõik PNG failid public/ kaustas
```

---

## Kuidas installida mobiilile

### Android (Chrome)

1. Ava brauser, mine rakenduse URL-ile
2. Vajuta brauseri menüüsse (kolm punkti üleval paremal)
3. Vali **"Lisa avakuvale"** / **"Install app"**
4. Kinnita — ikoon ilmub avakuvale

### iOS (Safari)

1. Ava **Safari** (Chrome iOS-il ei toeta installimist!)
2. Mine rakenduse URL-ile
3. Vajuta **jagamise nupule** (kast noolega, ekraani allosas)
4. Vali **"Lisa avakuvale"** / **"Add to Home Screen"**
5. Kinnita — ikoon ilmub avakuvale

### Arvuti (Chrome/Edge)

1. Mine rakenduse URL-ile
2. Aadressiriba paremas servas ilmub installimise ikoon (monitor noolega)
3. Vajuta sellele ja kinnita

---

## Offline käitumine

| Ressurss | Offline käitumine |
|---|---|
| JS, CSS, HTML (äpp ise) | ✅ Töötab — laetud vahemälust (precache) |
| Fontid, ikoonid, väiksed pildid | ✅ Töötab — laetud vahemälust |
| API päringud (sündmused, kasutaja jms) | ⚠️ Viimane tulemus — vahemälust, võib olla vananenud |
| Hero pilt, gif, suured pildid | ❌ Ei laadi — liiga suured vahemällu salvestamiseks |

Praktikas tähendab see: kasutaja näeb äppi ja viimati laetud andmeid, kuid ei saa sündmuste nimekirja uuendada ega sisse logida ilma internetita.

---

## Tehnilised otsused

**Miks `NetworkFirst` API päringutele?**
Alternatiiv oleks `StaleWhileRevalidate` (näita kohe vahemälu, uuenda taustal) või `CacheFirst` (alati vahemälu). `NetworkFirst` tagab, et kasutaja näeb alati värsket infot — see on kogukonnaleheküljel oluline (sündmuste kellaaeg, registreerimiste arv jms).

**Miks `standalone` display mode?**
Alternatiiv on `browser` (tavalise brauseri aknaga) või `fullscreen` (ilma staatusribata). `standalone` on optimaalne: äpp näeb välja nagu natiivne äpp (ilma aadressiribata), aga staatusriba jääb alles (kasutaja näeb kellaaega).

**Miks `portrait` orientatsioon?**
Sündmuste loend, kalender ja ostukorv on kõik vertikaalse paigutusega — horisontaalne kasutus ei paranda UX-i. `portrait` lukustus hoiab liidese ootuspärasena.

**Miks ikoonid genereerida (mitte käsitsi luua)?**
Käsitsi PNG-de loomine on vigaderohke (vale suurus, vale formaat, vale padding). `@vite-pwa/assets-generator` kasutab `sharp` teeki ja loob täpselt õiged suurused korrektse formaadiga. Kui ikoon muutub, käivita üks käsk ja kõik on uuendatud.

**Miks hero.png on precache'ist välja jäetud?**
Workbox'i vaikelimiit on 2MB. Hero pilt on 3MB. Precache'ist välja jätmine tähendab, et installimise aeg on lühem ja esimene avamine kiirem. Hero pilt laetakse tavaliste võrgupäringutena (ja jääb brauseri HTTP cache'i).
