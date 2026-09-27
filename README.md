# 🤖 Jarvis Android — "Hey Jarvis" (ilovani ochmasdan)

Fonda doim eshitib turadi. **"Hey Jarvis"** deysiz → 🔔 → buyruq → Madina ovozida javob + amal bajariladi.
Ekran o'chiq bo'lsa ham ishlaydi. "Hey Jarvis"ni aniqlash telefonning o'zida (internetsiz).

## 1. APK olish
1. Bu papkani alohida GitHub repozitoriyga yuklang (`main`).
2. **Actions → Build Jarvis APK** tugashini kuting (~5-7 daqiqa).
3. **Artifacts → Jarvis-APK** ni yuklab, telefonga o'rnating ("Noma'lum manbalar"ga ruxsat bering).

## 2. Bir martalik sozlash (ilovada)
1. **Server manzili** — `jarvis-web` Vercel linki (masalan `https://jarvis-farhod.vercel.app`)
2. **Parol** — Vercel'dagi `JARVIS_TOKEN`
3. Uchta tugmani bosing: **Ruxsatlar**, **Ustidan ko'rsatish**, **Batareya cheklovi**
4. **Jarvis'ni yoqish** → ilovani yoping. Tamom!

Xiaomi / Samsung / Realme: *Sozlamalar → Ilovalar → Jarvis → Batareya → "Cheklovsiz"* va *Avtozapusk* ni yoqing, aks holda tizim Jarvis'ni o'chirib qo'yadi.

## Nimalar qila oladi
| Ayting | Bajaradi |
|---|---|
| "Chiroqni yoq" | Fonar |
| "Telegramni och" | Ilova ochadi |
| "Onamga qo'ng'iroq qil" | Kontaktdan topib qo'ng'iroq qiladi |
| "Ertalab yettida uyg'ot" | Budilnik |
| "Besh daqiqaga taymer qo'y" | Taymer |
| "YouTube'da Shoxrux qo'shiqlari" | YouTube qidiruv |
| "Musiqani to'xtat / keyingisi" | Media boshqaruv |
| "Ovozni balandlat" | Ovoz |
| "Rahmat, bo'ldi" | Suhbatni tugatadi |

Javobdan keyin **4 soniya ichida** yana gapirsangiz — "Hey Jarvis"siz davom etadi.

## Bilishingiz kerak (Android qoidalari)
- Bildirishnomalarda doim **"Jarvis — Tinglayapman"** turadi (Android talabi, yashirib bo'lmaydi).
- Telefon qayta yonganda Android 14+ mikrofonni o'zi yoqishga ruxsat bermaydi — **"Jarvis: Yoqish uchun bosing"** bildirishnomasini bir marta bosasiz.
- Doim eshitish batareyani biroz ko'proq sarflaydi.
- "Hey Jarvis" modeli: openWakeWord (CC BY-NC-SA 4.0 — shaxsiy foydalanish uchun).

## Tuzilma
`WakeWord.kt` — "Hey Jarvis" (ONNX, asl algoritm bilan aynan bir xil) · `JarvisService.kt` — fon xizmati, yozish, ovoz · `ServerClient.kt` — `/api/voice` · `PhoneActions.kt` — amallar · `MainActivity.kt` — sozlash
# jarvis-andiroid
