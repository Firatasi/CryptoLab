import json
import os
import re
import subprocess
import time
import requests
from google import genai
from google.genai import types

GEMINI_API_KEY = os.environ.get("GEMINI_API_KEY", "").strip()
GITHUB_TOKEN = os.environ.get("GITHUB_TOKEN", "").strip()
REPO = os.environ.get("GITHUB_REPOSITORY", "")
EVENT_NAME = os.environ.get("EVENT_NAME", "push")
PR_NUMBER = os.environ.get("PR_NUMBER", "").strip()

headers = {
    "Authorization": f"Bearer {GITHUB_TOKEN}",
    "Accept": "application/vnd.github+json",
    "X-GitHub-Api-Version": "2022-11-28",
}

if EVENT_NAME == "pull_request" and PR_NUMBER:
    BASE_SHA = os.environ.get("PR_BASE_SHA", "")
    HEAD_SHA = os.environ.get("PR_HEAD_SHA") or os.environ.get("PUSH_AFTER_SHA", "HEAD")
else:
    BASE_SHA = os.environ.get("PUSH_BEFORE_SHA", "")
    HEAD_SHA = os.environ.get("PUSH_AFTER_SHA", "HEAD")
    if not BASE_SHA or BASE_SHA == "0000000000000000000000000000000000000000":
        BASE_SHA = f"{HEAD_SHA}~1"

# Gerçek HEAD SHA'yı git rev-parse ile kesinleştir
try:
    HEAD_SHA = subprocess.check_output(["git", "rev-parse", HEAD_SHA], text=True).strip()
except Exception:
    pass

print(f"İnceleme başlatıldı: EVENT={EVENT_NAME}, PR={PR_NUMBER}, HEAD_SHA={HEAD_SHA}, BASE_SHA={BASE_SHA}")

# Push event'indeysek ama commit bir PR'a aitse PR numarasını keşfet
if not PR_NUMBER and HEAD_SHA:
    try:
        pr_resp = requests.get(
            f"https://api.github.com/repos/{REPO}/commits/{HEAD_SHA}/pulls",
            headers=headers,
            timeout=10,
        )
        if pr_resp.status_code == 200:
            prs = pr_resp.json()
            if prs and len(prs) > 0:
                PR_NUMBER = str(prs[0].get("number", ""))
                print(f"Commit {HEAD_SHA} için açık PR #{PR_NUMBER} tespit edildi.")
    except Exception as e:
        print(f"İlişkili PR sorgulanırken hata: {e}")

# 1. Diff çıktısını al ve satır numaralarını etiketle (gürültülü/kilit dosyalarını hariç tut)
git_exclude_args = [
    "--",
    ".",
    ":!*.lock",
    ":!package-lock.json",
    ":!pnpm-lock.yaml",
    ":!yarn.lock",
    ":!**/target/**",
    ":!*.dump",
    ":!*.hprof",
    ":!*.log",
    ":!dist/**",
    ":!build/**",
    ":!node_modules/**",
]

try:
    raw_diff = subprocess.check_output(
        ["git", "diff", "--unified=3", BASE_SHA, HEAD_SHA] + git_exclude_args,
        text=True,
        errors="replace",
    )
except Exception:
    try:
        raw_diff = subprocess.check_output(
            ["git", "show", "--format=", "--unified=3", HEAD_SHA] + git_exclude_args,
            text=True,
            errors="replace",
        )
    except Exception as diff_err:
        print(f"Git diff alınamadı: {diff_err}")
        raw_diff = ""

SYSTEM_PROMPT = """Sen Kıdemli bir Uygulama Güvenliği Mühendisi (Principal AppSec Engineer) ve Staff Software Engineer'sın. Görevin, sana verilen `git diff` değişikliklerini güvenlik açıkları, çalışma zamanı hataları (runtime bugs), performans darboğazları ve temiz kod (clean code) standartları açısından incelemektir.

### KESİN KURALLAR:
1. ASLA "PR Özeti", "Yapılan Değişiklikler Listesi" veya "Commit Özeti" yazma. Geliştirici zaten ne yazdığını biliyor; senin görevin kodun zayıf noktalarını bulmak ve nasıl iyileştirileceğini göstermektir.
2. Yanıtını tamamen **Türkçe**, net, teknik ve profesyonel bir dille yaz.
3. Yalnızca değişen kod satırlarına (`+` ile eklenen veya değiştirilen kısımlara) ve bu değişikliklerin mevcut mimariyle etkileşimine odaklan.
4. Hayali sorun uydurma. Kod gerçekten temizse zorlama eleştiri yazma.
5. Her bulgu için mutlaka **dosya adını, satır numarasını, sorunun teknik nedenini** ve **nasıl düzeltileceğini gösteren örnek kodu** paylaş.

### İNCELEME KONTROL LİSTESİ (INTERNAL CHECKLIST):
- **Güvenlik (AppSec)**: SQL/NoSQL/Command Injection, XSS, SSRF, Path Traversal, Hardcoded API Key/Secret/Password, eksik kimlik doğrulama/yetkilendirme (AuthN/AuthZ), güvensiz deserialization, hassas verinin loglanması, kriptografik zayıflıklar.
- **Güvenilirlik & Bug**: Null/None referans hataları, yakalanmayan istisnalar (unhandled exceptions), race condition / asenkron kilitlenmeler, kaynak sızıntıları (açık kalan DB bağlantısı/dosya), sınır değer (edge-case) hataları.
- **Performans**: Döngü içinde veritabanı/ağ çağrısı (N+1 problemi), gereksiz bellek kopyalama, bloklayıcı I/O işlemleri, eksik indeks veya sayfalama (pagination) riskleri.
- **Kod Kalitesi & Mimari**: Tip güvenliği (type annotations), DRY ihlalleri, karmaşık iç içe koşullar (cyclomatic complexity), yanıltıcı değişken/fonksiyon isimlendirmeleri.

### ÇIKTI ŞABLONU (BU FORMATIN DIŞINA ÇIKMA):

# 🛡️ Otomatik Kod ve Güvenlik İncelemesi

**Genel Durum:** [Aşağıdaki 3 seçenekten yalnızca birini seç]
- `🔴 KRİTİK RİSK TESPİT EDİLDİ — Merge edilmeden önce kritik bulgular giderilmeli.`
- `🟡 UYARILAR MEVCUT — Kritik güvenlik açığı yok ancak önemli iyileştirmeler önerilir.`
- `🟢 GÜVENLİ VE TEMİZ — Kritik bir hata veya güvenlik açığı tespit edilmedi.`

---

## 🔴 Kritik Bulgular (Güvenlik & Çökme Riskleri)
*(Eğer yoksa: "Bu kategoride herhangi bir sorun tespit edilmedi." yaz ve geç)*

### 1. [Bulgu Başlığı] — `dosya/yolu.uzanti` (Satır: X-Y)
- **Risk & Etki:** [Sorunun neden tehlikeli olduğunu 1-2 cümleyle açıkla]
- **Çözüm Önerisi:** [Nasıl düzeltilmesi gerektiğini açıkla]
```diff
- mevcut_hatali_kod_satiri()
+ onerilen_guvenli_kod_satiri()
```

## 🟡 Uyarılar (Performans, Hata Yönetimi & Mantıksal Riskler)
*(Eğer yoksa: "Bu kategoride herhangi bir sorun tespit edilmedi." yaz ve geç)*

### 1. [Bulgu Başlığı] — `dosya/yolu.uzanti` (Satır: X-Y)
- **Sorun:** [Performans veya hata yönetimi riskini açıkla]
- **Bunu Yapabilirsin:**
```diff
- mevcut_kod()
+ iyilestirilmis_kod()
```

## 🟢 İyileştirme Önerileri (Clean Code & Best Practices)
*(Eğer yoksa: "Bu kategoride herhangi bir sorun tespit edilmedi." yaz ve geç)*

- `dosya/yolu.uzanti` (Satır: X): [Kısa açıklama ve önerilen refactoring]
"""

marker = f"<!-- gemini-security-review:{HEAD_SHA} -->"
review_markdown = None
status_desc = "İnceleme tamamlandı"

if not raw_diff.strip():
    print("İncelenecek kod değişikliği bulunamadı (yalnızca kilit veya hariç tutulan dosyalar değişmiş olabilir).")
    review_markdown = (
        "# 🛡️ Otomatik Kod ve Güvenlik İncelemesi\n\n"
        "**Genel Durum:** `🟢 GÜVENLİ VE TEMİZ — Kritik bir hata veya güvenlik açığı tespit edilmedi.`\n\n"
        "Bu commit'te incelenecek uygulama kaynak kodu değişikliği bulunamadı (yalnızca kilit, önbellek veya dokümantasyon dosyaları güncellenmiş olabilir).\n\n"
        f"> 🤖 Tetiklenen commit: `{HEAD_SHA[:7]}`"
    )
else:
    annotated_diff_lines = []
    current_file = None
    new_line_num = None

    for line in raw_diff.splitlines():
        if line.startswith("+++ b/"):
            current_file = line[6:].strip()
            annotated_diff_lines.append(line)
        elif line.startswith("@@ "):
            m = re.search(r"\+(\d+)", line)
            if m:
                new_line_num = int(m.group(1))
            annotated_diff_lines.append(line)
        elif current_file and new_line_num is not None:
            if line.startswith("+") and not line.startswith("+++"):
                annotated_diff_lines.append(f"[SATIR:{new_line_num}] {line}")
                new_line_num += 1
            elif line.startswith("-") and not line.startswith("---"):
                annotated_diff_lines.append(line)
            elif line.startswith(" "):
                annotated_diff_lines.append(f"[SATIR:{new_line_num}] {line}")
                new_line_num += 1
            else:
                annotated_diff_lines.append(line)
        else:
            annotated_diff_lines.append(line)

    annotated_diff = "\n".join(annotated_diff_lines)[:120000]

    # 2. Resmi Gemini model zinciri ve exponential backoff
    MODELS = ["gemini-2.5-flash", "gemini-2.5-pro"]
    BACKOFF_DELAYS = [5, 15, 30]

    response = None
    used_model = None

    if GEMINI_API_KEY:
        try:
            client = genai.Client(api_key=GEMINI_API_KEY)
            for model_name in MODELS:
                print(f"Gemini modeli deneniyor: {model_name}...")
                for attempt, delay in enumerate(BACKOFF_DELAYS, 1):
                    try:
                        resp = client.models.generate_content(
                            model=model_name,
                            contents=f"Aşağıdaki git diff değişikliklerini incele ve doğrudan belirtilen şablona uygun Markdown raporu üret:\n\n{annotated_diff}",
                            config=types.GenerateContentConfig(
                                temperature=0.2,
                                system_instruction=SYSTEM_PROMPT,
                            ),
                        )
                        if resp and resp.text and resp.text.strip():
                            response = resp
                            used_model = model_name
                            print(f"Başarılı model: {used_model}")
                            break
                    except Exception as err:
                        err_str = str(err)
                        print(f"{model_name} (deneme {attempt}/{len(BACKOFF_DELAYS)}) hata: {err_str}")
                        if "503" in err_str or "overloaded" in err_str.lower() or "429" in err_str or "resource_exhausted" in err_str.lower():
                            print(f"{delay} saniye bekleniyor...")
                            time.sleep(delay)
                        else:
                            break
                if response is not None:
                    break
        except Exception as client_err:
            print(f"GenAI Client başlatma hatası: {client_err}")
    else:
        print("UYARI: GEMINI_API_KEY secret değeri repo ayarlarında tanımlı değil!")

    if response is not None and response.text and response.text.strip():
        review_markdown = response.text.strip()
        review_markdown += f"\n\n---\n> 🤖 Bu inceleme `{HEAD_SHA[:7]}` commiti için Gemini API (`{used_model}`) tarafından otomatik oluşturulmuştur."
        status_desc = f"Gemini ({used_model}): İnceleme tamamlandı"
    else:
        review_markdown = (
            "# 🛡️ Otomatik Kod ve Güvenlik İncelemesi\n\n"
            "⚠️ **Gemini API kota/sunucu hatası nedeniyle otomatik güvenlik incelemesi tamamlanamadı. Lütfen workflow'u yeniden tetikleyin.**\n\n"
            f"> 🤖 Tetiklenen commit: `{HEAD_SHA[:7]}`"
        )
        status_desc = "Gemini API kota/sunucu hatası (İnceleme tamamlanamadı)"

full_body = f"{marker}\n\n{review_markdown}"

# 3. GITHUB_STEP_SUMMARY'e bas
step_summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
if step_summary_path:
    try:
        with open(step_summary_path, "a", encoding="utf-8") as sf:
            sf.write(review_markdown + "\n")
    except Exception as sum_err:
        print(f"Summary yazma hatası: {sum_err}")

# 4. Commit Status API'sine durum gönder
try:
    requests.post(
        f"https://api.github.com/repos/{REPO}/statuses/{HEAD_SHA}",
        headers=headers,
        json={
            "state": "success",
            "context": "Gemini Security Review",
            "description": status_desc[:135],
        },
        timeout=10,
    )
except Exception as st_err:
    print(f"Commit status hatası: {st_err}")

# 5. YORUMLARI BAS (DOĞRUDAN COMMIT'İN ALTINA VE PR VARSA PR'A)

# A) Doğrudan Commit'in Hemen Altına Yorum Bas (GitHub Commit sayfasında en altta görünür)
commit_comment_url = f"https://api.github.com/repos/{REPO}/commits/{HEAD_SHA}/comments"
try:
    c_res = requests.post(commit_comment_url, headers=headers, json={"body": full_body}, timeout=15)
    print(f"Commit yorumu yanıt kodu: {c_res.status_code}")
    if c_res.status_code in (200, 201):
        print(f"Commit {HEAD_SHA} altına başarıyla güvenlik inceleme yorumu eklendi!")
    else:
        print(f"Commit yorumu eklenemedi ({c_res.status_code}): {c_res.text[:200]}")
except Exception as c_err:
    print(f"Commit yorumu gönderim hatası: {c_err}")

# B) Eğer PR varsa, PR Sohbet Alanına (Issue Comments) ve PR Review sekmesine de bas
if PR_NUMBER:
    # 1. PR Sohbet Alanı (Timeline Comment)
    pr_comment_url = f"https://api.github.com/repos/{REPO}/issues/{PR_NUMBER}/comments"
    try:
        p_res = requests.post(pr_comment_url, headers=headers, json={"body": full_body}, timeout=15)
        print(f"PR sohbet yorumu yanıt kodu: {p_res.status_code}")
        if p_res.status_code in (200, 201):
            print(f"PR #{PR_NUMBER} sohbet akışına başarıyla inceleme yorumu eklendi!")
        else:
            print(f"PR yorumu eklenemedi ({p_res.status_code}): {p_res.text[:200]}")
    except Exception as p_err:
        print(f"PR yorumu gönderim hatası: {p_err}")

    # 2. PR Review API (commit_id ile bağlantılı resmi review)
    try:
        review_url = f"https://api.github.com/repos/{REPO}/pulls/{PR_NUMBER}/reviews"
        r_res = requests.post(
            review_url,
            headers=headers,
            json={
                "commit_id": HEAD_SHA,
                "body": full_body,
                "event": "COMMENT",
            },
            timeout=15,
        )
        print(f"PR Review API yanıt kodu: {r_res.status_code}")
    except Exception as r_err:
        print(f"PR Review API hatası: {r_err}")
