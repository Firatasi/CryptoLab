# CryptoLab Backend — Öğretici dersler, revizyon 4

Başlangıç: [00-BASLANGIC.md](00-BASLANGIC.md).

1. [Paket ve kod standardı](01-PAKET-VE-KOD-STANDARDI.md): hangi sınıfı nereye ve neden koyuyoruz?
2. [Sınıf haritası](02-SINIF-VE-PAKET-HARITASI.md): package ve dosya taşıma rehberi.
3. B01 → B01A → B02–B08 → B08A → B09–B12: sırayla elle yazılacak kodlar, açıklamalar ve kontroller.
4. [Doğrulama](DOGRULAMA.md): gerçekten yapılan kontroller ve açık kalan işler.

`kaynaklar/` altındaki dört dosya değişmeden korunmuştur. Paket düzeni mevcut api/application/domain/infrastructure sınırlarını detaylandırır. Bu teslim çalışma reposu değil öğretici MD paketidir; Boot 4 uyarlaması ve tam entegrasyon sınırlamaları başlangıç kılavuzundadır.

Yeni mimari eki: [03-ACCOUNT-VE-GUVENLIK-EKI.md](03-ACCOUNT-VE-GUVENLIK-EKI.md). JWT Identity; kalıcı geçmiş Reporting; bakiye Simulation; Hesabım API Account. Spring ayarları application.properties.

Eureka eki: [B01A-Eureka-ve-Servis-Kesfi.md](B01A-Eureka-ve-Servis-Kesfi.md). Localhost kayıt/keşif, Cloud BOM, Gateway/Account bağlantısı ve başlatma–port değişimi kontrolleri. 10 modül / 8 süreç.

Tek dosyalık genel görünüm: [BACKEND-MIMARI-VE-PAKET-YAPISI.md](BACKEND-MIMARI-VE-PAKET-YAPISI.md).
