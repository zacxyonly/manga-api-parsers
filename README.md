<div align="center">

<h1>📚 manga-api-parsers</h1>

<p>Universal Manga REST API — powered by <a href="https://github.com/YakaTeam/kotatsu-parsers">kotatsu-parsers</a></p>

<p>
  <img src="https://img.shields.io/badge/Kotlin-2.2.10-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" />
  <img src="https://img.shields.io/badge/Ktor-3.1.3-087CFA?style=for-the-badge&logo=ktor&logoColor=white" />
  <img src="https://img.shields.io/badge/JDK-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white" />
  <img src="https://img.shields.io/badge/Docker-ready-2496ED?style=for-the-badge&logo=docker&logoColor=white" />
  <img src="https://img.shields.io/badge/Sources-1200%2B-f97316?style=for-the-badge" />
  <img src="https://img.shields.io/badge/License-MIT-22c55e?style=for-the-badge" />
</p>

<p>Akses <strong>1200+ sumber manga</strong> dari seluruh dunia — manga, manhwa, manhua — dalam satu REST API yang konsisten.</p>

</div>

---

## ✨ Fitur Utama

|  | Fitur | Deskripsi |
|---|---|---|
| 🌐 | **1200+ Sources** | Manga, manhwa, manhua dari puluhan negara & bahasa |
| 🔑 | **API Key Auth** | 3 tier: `READ` · `FULL` · `ADMIN` + rate limiting per tier |
| ⚡ | **Response Cache** | TTL cache in-memory, kurangi beban ke upstream |
| 🔍 | **Multi-search** | Cari di beberapa source sekaligus secara paralel |
| 🛡️ | **Image Proxy** | SSRF-protected proxy dengan referer injection otomatis |
| 🎛️ | **Admin API** | Kelola key, monitor cache, lihat status server |
| 🐳 | **Docker Ready** | Multi-stage Dockerfile + docker-compose siap pakai |
| 📄 | **Landing Page** | Dokumentasi interaktif langsung di `GET /` |

---

## 🚀 Quick Start

### 🐳 Docker Compose (Direkomendasikan)

```bash
# Clone & masuk ke folder
git clone https://github.com/zacxyonly/manga-api-parsers.git
cd manga-api-parsers

# Buat file konfigurasi
cp .env.example .env

# Build & jalankan
docker compose up -d

# Lihat log untuk mengambil ADMIN key pertama
docker compose logs -f api-manga
```

Cari baris ini di log saat pertama kali jalan:

```
═══════════════════════════════════════════════════════
  NO API KEYS FOUND — generated initial ADMIN key:
  mapi_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
  Save this key! It will NOT be shown again.
═══════════════════════════════════════════════════════
```

> [!WARNING]
> **Simpan key ini segera!** Tidak akan ditampilkan lagi. Gunakan untuk membuat key lain lewat `POST /admin/keys`.

### 🛠️ Gradle (Development)

```bash
# Butuh JDK 21+
chmod +x gradlew
./gradlew run
```

Server berjalan di → `http://localhost:8080`

---

## 🔑 Autentikasi

Semua endpoint `/api/*` butuh API key. Bisa dikirim tiga cara:

```bash
# Header (direkomendasikan)
curl -H "X-Api-Key: mapi_xxx" http://localhost:8080/api/sources

# Bearer token
curl -H "Authorization: Bearer mapi_xxx" http://localhost:8080/api/sources

# Query parameter
curl "http://localhost:8080/api/sources?api_key=mapi_xxx"
```

### Tier & Rate Limit

| Tier | Rate Limit | Akses |
|:---:|:---:|---|
| `READ` | 60 req/menit | Browse, search, detail, chapters, pages |
| `FULL` | 120 req/menit | READ + image proxy |
| `ADMIN` | ∞ Unlimited | FULL + manajemen key, flush cache, server status |

### Manajemen Key

```bash
# Buat key baru
curl -X POST http://localhost:8080/admin/keys \
  -H "X-Api-Key: mapi_ADMIN_KEY" \
  -H "Content-Type: application/json" \
  -d '{"name": "app-saya", "tier": "FULL"}'

# List semua key
curl -H "X-Api-Key: mapi_ADMIN_KEY" http://localhost:8080/admin/keys

# Revoke key
curl -X DELETE -H "X-Api-Key: mapi_ADMIN_KEY" \
  http://localhost:8080/admin/keys/mapi_KEY_YANG_DIREVOKE

# Cek info key sendiri
curl -H "X-Api-Key: mapi_xxx" http://localhost:8080/admin/me
```

---

## 📡 Endpoints

### 🔓 Public — Tanpa Auth

| Method | Endpoint | Deskripsi |
|---|---|---|
| `GET` | `/` | Landing page & dokumentasi interaktif |
| `GET` | `/health` | Health check |

### 📖 Browse — `READ+`

| Method | Endpoint | Deskripsi |
|---|---|---|
| `GET` | `/api/sources` | Semua source · `?lang=id&broken=false&type=MANGA` |
| `GET` | `/api/home/{source}` | Popular + latest sekaligus |
| `GET` | `/api/popular/{source}` | Manga paling populer · `?page=1` |
| `GET` | `/api/latest/{source}` | Baru diupdate · `?page=1` |
| `GET` | `/api/newest/{source}` | Baru ditambahkan · `?page=1` |
| `GET` | `/api/trending/{source}` | Trending hari → minggu → bulan · `?page=1` |
| `GET` | `/api/top-rated/{source}` | Rating tertinggi · `?page=1` |
| `GET` | `/api/alphabetical/{source}` | Urutan A–Z · `?page=1` |

### 🔍 Filter & Search — `READ+`

| Method | Endpoint | Deskripsi |
|---|---|---|
| `GET` | `/api/tags/{source}` | Semua genre + opsi filter |
| `GET` | `/api/filter/{source}` | Filter kombinasi · `?tag=action&state=ONGOING&sort=UPDATED` |
| `GET` | `/api/search` | Cari manga · `?source=MGKOMIK&q=naruto&page=1` |
| `GET` | `/api/multi-search` | Multi-source paralel · `?sources=MGKOMIK,MANGADEX&q=naruto` |

### 📘 Detail & Reading — `READ+`

| Method | Endpoint | Deskripsi |
|---|---|---|
| `GET` | `/api/manga/{source}/{id}` | Detail manga + semua chapter · `?url=...` |
| `GET` | `/api/chapters/{source}/{mangaId}` | Daftar chapter saja · `?url=...` |
| `GET` | `/api/related/{source}/{id}` | Manga terkait / rekomendasi · `?url=...` |
| `GET` | `/api/pages/{source}/{chapterId}` | Halaman dalam chapter · `?url=...` |
| `GET` | `/api/page-url/{source}` | Resolve URL CDN final · `?pageId=...&url=...` |

### 🖼️ Proxy — `FULL+`

| Method | Endpoint | Deskripsi |
|---|---|---|
| `GET` | `/api/proxy` | Image proxy SSRF-protected · `?url={encodedUrl}&referer=...` |

### ⚙️ Admin

| Method | Endpoint | Tier | Deskripsi |
|---|---|:---:|---|
| `GET` | `/admin/me` | READ+ | Info key yang sedang dipakai |
| `GET` | `/admin/status` | FULL+ | Status server, uptime, cache stats |
| `GET` | `/admin/keys` | ADMIN | List semua key |
| `POST` | `/admin/keys` | ADMIN | Buat key baru |
| `DELETE` | `/admin/keys/{key}` | ADMIN | Revoke key |
| `POST` | `/admin/cache/flush` | ADMIN | Flush semua cache |
| `POST` | `/admin/cache/flush/{source}` | ADMIN | Flush cache satu source |
| `GET` | `/admin/cache/stats` | ADMIN | Statistik cache |

---

## 🐳 Docker Compose

```bash
docker compose up -d                # Jalankan di background
docker compose logs -f api-manga    # Log real-time
docker compose down                 # Stop
docker compose up -d --build        # Rebuild setelah ubah kode
docker compose restart api-manga    # Restart setelah ubah .env
docker compose down -v              # ⚠️ Hapus container + volume (keys hilang!)
```

> [!TIP]
> API key disimpan di Docker **named volume** `api_keys_data` — data tetap ada meski container di-restart atau di-rebuild. Hanya hilang jika `docker compose down -v`.

---

## ⚙️ Konfigurasi

```bash
cp .env.example .env  # lalu sesuaikan
```

| Variable | Default | Keterangan |
|---|:---:|---|
| `PORT` | `8080` | Port server dalam container |
| `HOST_PORT` | `8080` | Port yang di-expose ke host |
| `API_KEYS_FILE` | `data/api_keys.json` | Path penyimpanan API key |
| `ALLOWED_ORIGINS` | `*` | CORS — isi domain kamu di production |
| `CACHE_TTL_LIST_SEC` | `300` | TTL cache list/browse (detik) |
| `CACHE_TTL_DETAIL_SEC` | `600` | TTL cache detail manga |
| `CACHE_TTL_TAGS_SEC` | `3600` | TTL cache tags/genre |
| `CACHE_TTL_PAGES_SEC` | `1800` | TTL cache URL halaman |

---

## 🛠️ Tech Stack

| Library | Versi | Peran |
|---|:---:|---|
| [Kotlin](https://kotlinlang.org) | `2.2.10` | Bahasa utama |
| [Ktor](https://ktor.io) | `3.1.3` | HTTP server framework |
| [kotatsu-parsers](https://github.com/YakaTeam/kotatsu-parsers) | `master-SNAPSHOT` | Parser 1200+ sumber manga |
| [OkHttp](https://square.github.io/okhttp/) | `5.1.0` | HTTP client |
| [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | `1.8.1` | JSON serialization |
| [Logback](https://logback.qos.ch) | `1.5.18` | Logging |
| [eclipse-temurin](https://adoptium.net) | `21-jre-jammy` | JVM runtime (Docker) |

---

## 📁 Struktur Project

```
manga-api-parsers/
├── src/main/kotlin/com/mangaapi/
│   ├── Application.kt                  # Entry point & konfigurasi Ktor
│   ├── auth/ApiKeyManager.kt           # File-backed key management
│   ├── cache/ResponseCache.kt          # In-memory TTL cache
│   ├── context/MangaLoaderContextImpl  # JVM implementation of kotatsu context
│   ├── models/Dtos.kt                  # Data classes & mappers
│   └── routes/
│       ├── ApiRoutes.kt                # Semua endpoint /api/* dan /
│       └── AdminRoutes.kt              # Endpoint /admin/*
├── Dockerfile                          # Multi-stage build: JDK 21 → JRE runtime
├── docker-compose.yml
├── .env.example
└── build.gradle.kts
```

---

<div align="center">

Made with ❤️ by <a href="https://github.com/zacxyonly">zacxyonly</a> &nbsp;·&nbsp; Powered by <a href="https://github.com/YakaTeam/kotatsu-parsers">kotatsu-parsers</a>

</div>
