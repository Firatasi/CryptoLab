# CryptoLab — Commit ve Push Kuralları

Bu proje Conventional Commits mesaj formatını ve GitHub Flow temelli branch / Pull Request akışını kullanır. İngilizce mesajlar, branch adlandırması ve push sıklığı aşağıda belirlenen proje tercihleridir.

## 1. Commit mesajı formatı

```text
type(scope): kısa açıklama
```

- Mesajı İngilizce, kısa ve açıklayıcı yaz.
- Açıklamada `add`, `fix`, `update`, `remove` gibi emir kipindeki fiilleri kullan.
- Scope için ilgili alanı belirt: `backend`, `frontend`, `ai`. Projenin tamamını ilgilendiren değişikliklerde scope atlanabilir.
- `update`, `son hali`, `fix bug` gibi belirsiz mesajlar kullanma.
- Gerekirse başlıktan sonra boş satır bırakarak değişikliğin nedenini açıklayan bir gövde ekle.

```text
feat(backend): add user registration endpoint
```

Anlamı: Backend tarafına kullanıcı kayıt endpointi eklendi.

## 2. Commit türleri

| Tür | Kullanım | Örnek |
| --- | --- | --- |
| `feat` | Yeni özellik | `feat(backend): add user registration` |
| `fix` | Hata düzeltme | `fix(backend): handle duplicate email` |
| `refactor` | Davranışı değiştirmeden kodu düzenleme | `refactor(backend): simplify user service` |
| `test` | Test ekleme veya düzenleme | `test(backend): add user service tests` |
| `docs` | Dokümantasyon ve açıklama yorumları | `docs(backend): explain Maven dependencies` |
| `build` | Bağımlılıklar ve derleme ayarları | `build(backend): add springdoc dependency` |
| `ci` | Otomatik kontrol ve dağıtım iş akışları | `ci: add backend build workflow` |
| `style` | Davranışı etkilemeyen biçim değişikliği | `style(backend): format controller classes` |
| `chore` | Diğer bakım işleri | `chore: update gitignore` |
| `perf` | Performans iyileştirmesi | `perf(backend): optimize market query` |

`feat` ve `fix`, Conventional Commits standardının temel türleridir. Diğer türler yaygın kullanılan ek türlerdir. `style`, burada görsel arayüz özelliği değil, kod biçimlendirmesi anlamındadır.

## 3. Commit kapsamı

- Bir commit tek bir mantıksal değişikliği temsil etsin.
- Bir özelliğin çalışması için gereken Controller, Service, Repository, DTO ve ilgili testler aynı commit içinde olabilir.
- Her sınıf için ayrı commit atmak gerekmez.
- İlgisiz değişiklikleri aynı commit içinde birleştirme.
- Görevin tamamını tek commit yapmak zorunda değilsin; tamamlanmış, anlamlı parçalara ayırabilirsin.

## 4. Branch düzeni

`main`, çalışır ve birleştirmeye hazır kodu barındırır. Her görev için kısa ömürlü ayrı bir branch aç.

```text
feat/backend-user-registration
fix/backend-email-validation
build/backend-swagger
docs/backend-setup-guide
```

Branch isimlerinde küçük harf ve kelimeler arasında tire kullan. Birbirinden bağımsız görevleri ayrı branch'lerde geliştir.

## 5. Commit ve push zamanı

- Commit, değişikliği yerel Git geçmişine kaydeder.
- Push, yerel commit'leri uzak depoya gönderir.
- Anlamlı bir değişiklik tamamlandığında commit at.
- Anlamlı ilerlemelerden sonra ve çalışma oturumunun sonunda görev branch'ini push et.
- Push için bütün görevin bitmesini bekleme.
- Tamamlanmamış görevler için Draft Pull Request açılabilir; main'e birleştirmeden önce iş tamamlanmalı ve kontroller geçmelidir.

## 6. Kontrol listesi

- [ ] `git status` ve `git diff` ile değişiklikleri inceledim.
- [ ] Yalnızca ilgili dosyaları stage ettim.
- [ ] `git diff --staged` ile commit içeriğini kontrol ettim.
- [ ] Commit mesajı değişikliği açıkça anlatıyor.
- [ ] Şifre, API anahtarı veya gerçek bağlantı bilgisi eklemedim.
- [ ] `.env` ve `target/` gibi yerel veya üretilmiş dosyaları commit kapsamına almadım.
- [ ] Birleştirmeden önce derleme ve ilgili testleri çalıştırdım.

Maven Wrapper varsa backend proje dizininde kontrol komutu:

```powershell
# Windows PowerShell
.\mvnw.cmd verify
```

```bash
# Linux / macOS
./mvnw verify
```

Wrapper yoksa kurulu Maven ile `mvn verify` kullan. Entegrasyon testleri veritabanı gibi ek servisler gerektiriyorsa bu servisleri projenin test yapılandırmasına göre başlat.

## 7. Örnek: Swagger bağımlılığını commit etme

Aşağıdaki örnek, uzak deponun `origin` olarak tanımlı olduğunu ve `pom.xml` dosyasının repo kökünde bulunduğunu varsayar. Dosya alt klasördeyse gerçek yolunu kullan.

```bash
# Mevcut değişikliklerle yeni görev branch'i oluştur.
git switch -c build/backend-swagger

# Değişiklikleri incele.
git status
git diff

# İlgili dosyayı stage et.
git add pom.xml

# Commit kapsamını kontrol et.
git diff --staged

# Yerel geçmişe kaydet.
git commit -m "build(backend): add Swagger UI dependency"

# Uzak depoya gönder ve branch takip bağlantısını kur.
git push -u origin build/backend-swagger
```

Aynı branch'te sonraki commit'lerden sonra:

```bash
git push
```

Swagger daha önce commit edildiyse ve yalnızca dependency açıklama yorumları eklendiyse, ilgili dosyayı stage ettikten sonra şu mesajı kullan:

```bash
git commit -m "docs(backend): explain Maven dependencies"
```

## 8. Pull Request ve birleştirme

1. Görev branch'ini push et.
2. Hedefi `main` olan bir Pull Request aç.
3. Ne değiştiğini, neden gerektiğini ve nasıl doğruladığını kısaca yaz.
4. Değişiklikleri gözden geçir; derleme ve test kontrollerini tamamla.
5. Hazır olduğunda birleştir ve tamamlanan görev branch'ini sil.

Ortak branch'lere force push yapma. Birleştirilmiş hatalı değişiklikleri düzeltmek için yeni bir düzeltme commit'i veya uygun olduğunda `git revert` kullan.

## 9. Geriye dönük uyumluluğu bozan değişiklikler

Mevcut istemcilerin kullandığı API sözleşmesini uyumsuz biçimde değiştiriyorsan `!` kullan ve etkisini açıkla:

```text
feat(backend)!: change authentication response format

BREAKING CHANGE: clients must read accessToken instead of token.
```

Her büyük kod değişikliği breaking change değildir; mevcut tüketicilerin uyum sağlamasını gerektiren değişiklikler bu kapsama girer.

## Kaynaklar

- [Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/)
- [GitHub Flow](https://docs.github.com/en/get-started/using-github/github-flow)
