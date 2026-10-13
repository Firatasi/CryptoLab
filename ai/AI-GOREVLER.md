# CryptoLab — AI için sıralı elle kodlama görevleri

Plan 1.0 · Şartname 3.0 · 10 ana görev. **Başlangıçta bütün görevler TODO.**

[AI şartnamesi](03-AI.md), [ana sözleşme](../README.md) ve [ortak çalışma kılavuzu](../CALISMA-KILAVUZU.md) ile uygulanır. Numeric model, PDF-türevli açıklama modeli ve Spring policy/finans otoritesi ayrıdır. Bu rehber hazır “trader modeli” veya otomatik eğitim script’i teslimi değildir; senin yazacağın çalışma sırasıdır.

## AI sohbetine başlangıç talimatı

```text
Bu sohbet CryptoLab AI öğrenme sohbetimdir. README.md, 03-AI.md ve
AI-GOREVLER.md esas alınacak. Python kodunu, dataset işlemlerini, eğitim
script/job'larını ve testleri ben elle yazacağım. Ben açıkça istemedikçe dosya
oluşturma/değiştirme, komut çalıştırma veya modeli otomatik eğitme.
Her mesajda tek küçük alt görev: kavram, yazacağım bölüm, küçük veri örneği,
beklenen çıktı ve kontrol sorusu. Önce benim denememi gör.
İpucu→pseudocode→küçük örnek; tam çözüm yalnız isteğimle.
Görmediğin veriyi/çıktıyı var sayma. Mock, baseline, candidate ve active model
ayrı; test veya training yapılmadan yapıldı deme. PDF yalnız eğitimde;
inference'ta PDF/RAG ekleme. Python'da ikinci finans/likidasyon motoru yazma.
Java/React dosyalarını değiştirme; contract değişikliğini backend sohbetine not et.
Ekran kartı/sürücü/sistem ayarıyla oynama; ortam değişikliği gerekirse etkisini
anlat ve benim yapacağım açık adımı ver. Yeni başlangıç A01.1'dir.
```

Donanım, temel model ve gerçek eğitim kaynakları bilinmeden isim/VRAM/süre/kâr sonucu uydurulmaz. Eski sohbetten bir donanım adı hatırlansa bile bu proje için kullanılan ortam A01’de doğrulanır. Eğitim başarısı düşük olsa da doğru ölçülmüş negatif sonuç kabul edilebilir; kârlılık şartı olarak sunulmaz.

## Görev haritası

| Görev | Çıktı | Ön koşul | Diğer paketle bağ |
|---|---|---|---|
| A01 | Python sınırı ve contract | Yok; sözleşme için B01 | B01 |
| A02 | Dataset/feature pipeline | A01 | B06 export; önce fixture |
| A03 | Label/split/leakage | A02 | Bağımsız fixture |
| A04 | Numeric baseline eğitim artifact | A03 | model_db; Java eval A09 |
| A05 | Hazır model inference | A04 | B09+F07; geliştirme bundle’ı |
| A06 | Eğitim PDF source pipeline | A05 | B10+F09 |
| A07 | Reviewed explanation dataset | A06 | B10+F09 |
| A08 | Gerçek açıklama fine-tuning | A07 | Kaynak/donanım/model seçimi |
| A09 | Maliyetli eval/registry/publish | A08 | B11 Java replay |
| A10 | Ayrık dağıtım ve uçtan uca | A09 | B12+F10 |

A09’daki numeric değerlendirme alt adımları A05 sonrası hazırlık olarak çalışılabilir; tam yayın kapısı yine A09’da kapanır. A08 ortam yüzünden BLOCKED ise template fallback normal sistemi çalıştırabilir ama PDF fine-tuning görevi DONE sayılmaz. B11 A09’u beklemeden sentetik intent ile hazırlanır; circular dependency yoktur.

## A01 — Python ortamı, süreç sınırı ve contract modelleri

**Öğrenme:** Sanal ortam, modül, type validation, saf fonksiyon, test ve serving/training farkı.

**Elle yazılacaklar:** `ai/pyproject.toml` veya seçilen bağımlılık manifesti/lock; `app/api`, `contracts`, `features`, `training`, `inference`, `registry`; Pydantic DTO’lar ve contract testleri. Version seçimi gerçek ortam doğrulandıktan sonra.

- [ ] **A01.1** Python, OS, RAM, CPU, disk ve varsa GPU/VRAM bilgisini sen kontrol et. Sürücü değiştirme; yalnız bilgi topla.
- [ ] **A01.2** Serving ve training dependency gruplarını ayır; model-api’nin PDF/OCR gerektirmeyeceğini klasör/import sınırında göster.
- [ ] **A01.3** Basit list/dict, type hint, dataclass veya model validation tekrarını tek sentetik veri satırıyla yap.
- [ ] **A01.4** B01 schema’dan PredictionRequest/PredictionResult ve error modellerini yaz; horizon/UUID/asOf/schema validation.
- [ ] **A01.5** Internal service auth/scope port ve endpoint iskeleti; tarayıcıya doğrudan servis açma. Fake sonuç açık MOCK etiketiyle test alanında.
- [ ] **A01.6** Pytest contract fixture roundtrip, invalid input ve serving import testi; kullandığın komut ve çıktı kaydı.

**Kabul:** Eski/farklı DTO alanı yok; request’te PDF/collection/corpus yok. Finite numeric output, Decimal finans sınırı ve UTC anlamları açıklanır. Ortam hazır olmadan paket kuruldu/test edildi denmez.
**Anlama sorusu:** “Model API’si ile training worker niye aynı process olmak zorunda değil?”
**Devir:** B09’a iç API fixture; contract version. **İlk iş:** Yalnız A01.1 envanter, otomatik ortam kurulumu yok.

## A02 — Piyasa dataset’i ve ortak özellik üretimi

**Öğrenme:** DataFrame, zaman sırası, eksik veri, normalize özellik ve train/serve parity.

**Dosyalar:** MarketDatasetManifest, authorized artifact reader/hash validator, `features/pipeline`, FeatureSpec/FeatureSnapshot, coverage validation ve test fixture’ları.

- [ ] **A02.1** Önce üç sentetik varlık ve az sayıda kapalı mumla manifest oluştur; sahte veri olduğu açık olsun.
- [ ] **A02.2** B06 export schema’yı oku, checksum/coverage/priceKind/availableAt doğrula; doğrudan Market DB’ye bağlanma.
- [ ] **A02.3** Returns/relative volume gibi iki basit feature’ı elle yaz; pencere başlangıcı ve missing değer anlamını açıkla.
- [ ] **A02.4** ATR/trend/volatility/market context ailelerini sırayla ekle; hangi priceKind/interval/observation gerektirdiğini FeatureSpec’e kaydet.
- [ ] **A02.5** Higher timeframe için closeTime≤asOf ve availableAt≤asOf join; kapanmamış barı yanlış dahil etme.
- [ ] **A02.6** Aynı package fonksiyonunu offline ve inference’da kullan; feature order/schema hash/parity fixture.
- [ ] **A02.7** Gerçek küçük B06 export hazırsa schema/parity dene; yoksa fixture geliştirmesi sürer, gerçek veri entegrasyonu açık kalır.

**Kabul:** Bir future bar değiştiğinde önceki karar feature’ı değişmez. Aynı symbol farklı venue çakışmaz. Eksik depth yerine bugünkü book geçmişe taşınmaz. Her request bütün history indirmez; manifest/coverage/hash sınırı vardır. Feature sayısını gereksiz çoğaltmak başarı ölçütü değildir.
**Anlama sorusu:** “availableAt ile mumun closeTime alanı neden her zaman aynı olmayabilir?”
**Devir:** B09’a featureVersion/input summary anlamı; A03 dondurulabilir feature snapshot. **İlk iş:** Bir kapalı mum tablosu ve tek yüzde getiri sütunu.

## A03 — Hedefler, zaman ayrımı ve veri sızıntısı

**Öğrenme:** Label, horizon, validation/test, purge, unseen asset family ve train-only preprocessing.

**Dosyalar:** LabelSpec/label builder, SplitManifest, panel time splitter, asset family mapping, leakage validators, preprocess pipeline tests.

- [ ] **A03.1** y_h=log(P(t+h)/P(t)) etiketini önce tek horizon için yaz; t+h yoksa örneğin niye dışlandığını göster.
- [ ] **A03.2** Beş horizon’u ayrı kolon veya estimator dataset’e bağla; candle interval ile aynı sayma.
- [ ] **A03.3** Bütün varlıklara ortak UTC train/validation/test kesimleri; random row split’i kabul yöntemine koyma.
- [ ] **A03.4** Label aralığı sonraki fold’a taşan train satırlarını purge; max horizon ve değişken timestamp örneği.
- [ ] **A03.5** Unseen asset family setini ayır; aynı token başka venue/sarılmış türev eşleşmesini veri politikasına yaz.
- [ ] **A03.6** Scaler/imputer/feature selection fit yalnız train; validation/test transform. Teste çok uç değer ekleyip train parametresinin değişmediğini göster.
- [ ] **A03.7** Split manifest freeze ve testleri; eksik/delist veriler nedeniyle survivorship sınırını kayıt altına al.

**Kabul:** Gelecek label overlap yakalanıyor; holdout aile train-fit’e sızmıyor; çoklu timeframe geleceği görmüyor; test dönemi hyperparameter seçimi için kullanılmıyor. Veri miktarı yeterli değilse başarı skoru uydurmak yerine veri açığı raporu var.
**Anlama sorusu:** “Model training’de bir haftalık hedef öğreniyorsa sınırdaki son train satırlarının niye silinmesi gerekebilir?”
**Devir:** A04 dataset/split/label hash; bu kapı geçmeden fit yok. **İlk iş:** Kağıt üstünde üç karar zamanı ve t+h çizelgesi.

## A04 — Numeric baseline ve ilk eğitim işi

**Öğrenme:** Fit/predict, baseline, seed, parametre, artifact ve async job metadata.

**Dosyalar:** train_numeric, baseline estimator adapter, run config, model_db migration/TrainingRun/lease, artifact writer/reader, evaluation summary/model card taslağı.

- [ ] **A04.1** NO_TRADE ve basit rule baseline’ın rolünü açıkla; ilk küçük linear/logistic veya şartnamedeki CPU estimator için aday planı yaz.
- [ ] **A04.2** Train pipeline ve seed/config; preprocessing artifact’ı estimator ile birlikte sakla.
- [ ] **A04.3** Validation metric’i task’a göre seç; label hedefi ham getiri, net işlem kârı değil.
- [ ] **A04.4** TrainingRun immutable manifest + durable QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED; stage ve heartbeat/lease ayrımı.
- [ ] **A04.5** Model/preprocess/schema hash + dataset/split/code/dependency kaydı; güvenilen serialization formatı.
- [ ] **A04.6** Artifact yeniden yüklenince aynı fixture prediction; bozuk hash veya feature order reddi.
- [ ] **A04.7** Küçük veriyle baştan sona gerçek fit işlemini sen başlat; log/metric/limitasyon paylaş. Cancel/yarım artifact active olamaz.

**Kabul:** Gerçek fit çıktısı dosyada ve yeniden yüklenebilir. Mock predictor eğitimli model diye sunulmaz. Bir pozitif metric net kârlılık kanıtı sayılmaz; maliyetli Java eval A09’da. Aday registry CANDIDATE, otomatik ACTIVE yok.
**Anlama sorusu:** “Eğitilmiş estimator dosyasını kaydedip scaler’ı kaydetmezsen ne bozulur?”
**Devir:** A05 geliştirme inference’ı için immutable candidate; B09 model output fixture. **İlk iş:** Sentetik küçük dataset’te tek baseline fit/predict.

## A05 — Hazır model inference ve açıklama fallback’i

**Öğrenme:** API request validation, feature parity, version pin, cache, timeout ve model belirsizliği.

**Dosyalar:** model-api predictions/explanations/active-bundle uç noktaları, bundle loader, feature client/cache, template explanation, service auth, integration tests.

- [ ] **A05.1** Geliştirme/test model bundle’ını explicit yükle; candidate’ı üretim alias’ına sessiz ACTIVE yapma. A09 kalite/yayın kapısı saklı.
- [ ] **A05.2** Prediction request→hash/schema/asOf/coverage→aynı feature pipeline→predict→typed result.
- [ ] **A05.3** READY/INSUFFICIENT_DATA/OUT_OF_DOMAIN/STALE_DATA ile teknik exception ayrımı; non-ready skor null.
- [ ] **A05.4** Cache key bundle+featureSnapshot+horizon; stale prediction’a yeni timestamp basma.
- [ ] **A05.5** Template açıklama verified summary/policy outcome’dan; LONG/SHORT/WAIT veya quantity’yi değiştirtemez.
- [ ] **A05.6** Bundle başta pin, concurrent request aynı sürümle bitsin; API/worker kaynak sınırları ve timeout.
- [ ] **A05.7** B09 client ile gerçek contract roundtrip; F07’ye mock/candidate/provider etiketi ve açık version bilgisi aktar.

**Kabul:** PDF’ler olmadan tahmin endpoint’i çalışıyor. Yeterli feature olan görülmemiş coin ID’si otomatik reject değil; domain/data gate geçerli. Model yoksa 503/FAILED; önceki skor başarı gibi dönmüyor. Başlangıç template fine-tuned açıklama modeli gibi gösterilmiyor.
**Anlama sorusu:** “Model hatası ile modelin veri yetersiz bulması niye aynı status değil?”
**Devir:** K3 geliştirme numeric analizi; A09 sonrası active alias final testi. **İlk iş:** Candidate yükleme ve tek fixture prediction endpoint’i.

## A06 — Yalnız eğitim için PDF kaynak hattı

**Öğrenme:** Dosya işlemi, parser sınırı, OCR kalite, durable ingestion job ve kaynak kökeni.

**Dosyalar:** training_sources/source_versions/extracted_pages migrations, importer/parser port, extraction job/worker, source review API, artifact permission ve limits.

- [ ] **A06.1** İzinli küçük bir PDF seç; gerçek kaynak yoksa sentetik fixture kullan ve etiketle. Başlık/tarih/dil/rights/content hash alanlarını yaz.
- [ ] **A06.2** Upload/import job status-stage ve boyut/timeout/karantina; tekrar hash owner scope davranışı.
- [ ] **A06.3** Text-native tek sayfada başlık/paragraf/sayfa eşlemesini çıkar; çok sütun okuma sırasını gözle kontrol et.
- [ ] **A06.4** Scanned sayfa/OCR, tablo/birim/footnote ve grafik caption’ı için ayrı quality/review yolu. Grafikten kesin fiyat icat etme.
- [ ] **A06.5** URL import public HTTPS/DNS/IP/redirect/SSRF ve dosya/path limitlerini test et; PDF linklerini otomatik takip etme.
- [ ] **A06.6** Yetkili source detail/page/delete ve private data izolasyonu; B10 facade/F09 UI ile job sonucu.
- [ ] **A06.7** Training bağımlılıklarının model-api’ye import/mount ile taşınmadığını kontrol et; PDF store’u kapatıp A05’i çağır.

**Kabul:** READY_FOR_EXAMPLES model eğitildi demek değil. Bozuk/şifreli/kalitesiz sayfa açık hata/review alıyor. PDF içeriği talimat değil veri. Private-network redirect import’u reddediliyor. Bir parser/library’nin lisans ve sürümünü seçmeden kuruldu varsayılmaz.
**Anlama sorusu:** “PDF’yi metne çevirdik; modelin ağırlıkları neden hâlâ aynı?”
**Devir:** K4 kaynak job; A07 reviewed content ve source spans. **İlk iş:** Tek sayfalık izinli PDF’den metin+sayı sayfa eşlemesi.

## A07 — Kontrollü eğitim örnekleri ve dataset freeze

**Öğrenme:** Görev odaklı SFT verisi, target/input ayrımı, insan review, kopya aileleri ve örnek kalitesi.

**Dosyalar:** Example schema/task types, example draft generator port, review endpoint, document-family dedupe, dataset freeze/JSONL exporter ve quality tests.

- [ ] **A07.1** CONCEPT_EXPLANATION/RISK_REASONING/ABSTENTION/FAILURE_ANALYSIS/OUTPUT_SCHEMA görevlerini birer elle hazırlanmış örnekle anlamlandır.
- [ ] **A07.2** Input yalnız runtime’da mevcut verified features+policy; sourceSpans eğitim metadata’sı. Runtime’da bulunmayacak retrievedEvidence zorunluluğu koyma.
- [ ] **A07.3** Draft üretici varsa rule/teacher adapter olarak kur; LLM taslağı otomatik doğru hedef değildir.
- [ ] **A07.4** DRAFT/IN_REVIEW/ACCEPTED/REJECTED ve expectedVersion; yanlış OCR/iddia düzeltme, WAIT ve kayıp örnekleri.
- [ ] **A07.5** Aynı kitabın OCR/çeviri/baskı türevlerini document family’ye grupla; train/held-out belge ayrımı ve near-duplicate kontrolü.
- [ ] **A07.6** Accepted örnekleri immutable dataset’e freeze et; JSONL schema, source/reviewer/hash ve sınıf dağılımı raporu.
- [ ] **A07.7** Strateji hipotezini explanation örneğinden ayır: test edilecek kural/config taslağı olarak sakla, deploy etme.

**Kabul:** İncelenmemiş taslak train’e girmiyor. Geleceği gösteren grafik input olarak sızmıyor. Açıklama target’ı verified policy ile çelişirse quality gate fail. Öğrenme örnek seti küçük olabilir; bu büyüklük model kalitesi yeterli demek değildir.
**Anlama sorusu:** “Kaynak sayfasını metadata’da tutup inference input’una koymamamız neden önemli?”
**Devir:** A08 exact accepted dataset/split; B10/F09 review sözleşmesi. **İlk iş:** Yalnız bir WAIT örneğini kendin yazıp şemayla kontrol etmek.

## A08 — Gerçek açıklama modeli fine-tuning

**Öğrenme:** Tokenizer, target mask, loss, batch/accumulation, adapter ve overfit. Bu ana görev birkaç çalışma oturumuna bölünebilir; tek mesajda tamamı kodlanmaz.

**Dosyalar:** SFT config manifest, tokenizer/collator, train_explanation adapter, checkpoint/export, small held-out evaluator, model card. Kesin açık model adı donanım/lisans/veri kontrolünden sonra seçilir.

- [ ] **A08.1** Gerçek RAM/VRAM ve disk bütçesine göre uygun küçük base model adayını değerlendir; license/revision/tokenizer hash kaydet. Sürücü/GPU ayarını kendiliğinden değiştirme.
- [ ] **A08.2** A07 verisini modelin konuşma/input formatına çevir; serving formatıyla eşleştir.
- [ ] **A08.3** Bir batch’te token length/truncation/target loss mask’i elle incele; hedefi tamamen kesilen örnek reddi.
- [ ] **A08.4** Küçük dry-run; trainable parameter/adapter kontrolü, finite loss, memory ölçümü. Bu kalite eğitimi tamamlandı demek değildir.
- [ ] **A08.5** Asıl training konfigürasyonunu seed/LR/batch/steps/precision/early stop ile kaydet; işi sen başlat ve logları yorumla.
- [ ] **A08.6** Adapter/weights+base revision+tokenizer export; fresh process’te yeniden yükle; checkpoint/cancel davranışı.
- [ ] **A08.7** Base ve fine-tuned modeli aynı held-out görevlerde karşılaştır: schema, yanlış sayı, policy contradiction, WAIT gerekçesi ve dil kalitesi.
- [ ] **A08.8** Sonucu CANDIDATE/REJECTED olarak kaydet; loss düşüşünü kâr başarısı sayma. B10 run progress ve F09’a gerçek stage/metric aktar.

**Kabul:** Gerçek train çalışmış, hash’li değişen ağırlık/adapter export edilmiş, baseline karşılaştırması ve limitasyon kaydı var. Yalnız PDF parse, teacher çağrısı veya Gemini API yanıtı fine-tuning değildir. Donanım/model erişimi engelliyse BLOCKED; template ile işi tamamlandı işaretleme. Eğitim başarısızsa sebebi araştır; sahte başarı çıkarma.
**Anlama sorusu:** “Adapter dosyası neden base model revision ve tokenizer olmadan tam çalışır model sayılmaz?”
**Devir:** A09 explanation candidate ve metric; A05 adapter entegrasyonu versioned bundle ile. **İlk iş:** Sadece temel model seçimi için ölçülmüş ihtiyaç tablosu.

## A09 — Maliyetli değerlendirme, registry ve yayınlama

**Öğrenme:** Araştırma tasarımı, out-of-sample kanıt, Java replay sözleşmesi, lifecycle ve rollback.

**Dosyalar:** PolicyIntent exporter, ReplayEvaluation client, evaluation report/gates, model_versions/bundles/aliases/promotion_audit, outbox ModelPromoted, publish/rollback API.

- [ ] **A09.1** Numeric dataset/split/feature ve explanation document split hash’lerini tekrar doğrula; test verisine göre tekrar ayar seçme.
- [ ] **A09.2** Policy intent artifact’ı B11 contract’ına göre üret; Java finance-core replay’e gönder; Python’da PnL/liq kopyalama.
- [ ] **A09.3** Aynı dataset/seed/cost profile baseline/candidate; net/gross/fee/funding/MDD/WAIT coverage ve unseen/future cohort ayrı rapor.
- [ ] **A09.4** Fine-tuning açıklama katkısını ayrı ölç; PDF hipotezi policy katkısı varsa ayrı deney. RAG karşılaştırması ekleme.
- [ ] **A09.5** Hard leakage/schema/hash gates ve validation’da dondurulmuş kalite/kaynak eşikleri; fail model publish olamasın.
- [ ] **A09.6** CANDIDATE→VALIDATED→SHADOW→ACTIVE; MODEL_PUBLISH+expectedAliasVersion, atomic alias ve outbox.
- [ ] **A09.7** Shadow aynı snapshot üzerinde paper emir yaratmadan karşılaştırma; rollback exact bundle; mevcut pozisyon planına dokunma.
- [ ] **A09.8** Source silme/retrain/retire lineage politikasını uygula; eğitilmiş ağırlıktan bilgi anında silindi iddiası yok.

**Kabul:** B11 gerçek eval bağlantısı ve finance artifact kaydı; metric sayılarına model/source versiyonundan ulaşılabilir. Promotion yetkisiz veya gate fail iken reddediliyor. Eşzamanlı request tek bundle ile tamamlanıyor. Negatif performans dürüst raporlanabilir; ACTIVE’a zorla geçirilmez. Geliştirme alias’ı kalite kapısını atlama yolu olmaz.
**Anlama sorusu:** “Fine-tuned açıklama daha iyi olsa da sayısal trade getirisi artmadıysa tezde sonucu nasıl yazarsın?”
**Devir:** K5, B09 active bundle ve F09 model kartı; A10 final deploy. **İlk iş:** Sentetik intent’i Java replay’e gönderip result schema okumak.

## A10 — Ayrı serving/training dağıtımı ve AI teslimi

**Hedef:** Gerçek artifact’larla çalışmayı ve eğitim bağımlılığının normal kullanıcı akışında olmadığını kanıtlamak.

**Dosyalar:** Serving/training image definitions, config/lock/env example, authorized artifact loader, system/contract testleri, model cards/evaluation kayıtları ve progress teslim notu.

- [ ] **A10.1** A01–A09 kaynak/contract/mock/gerçek borç listesini çıkar; özellikle A08 gerçek fine-tuning kanıtı.
- [ ] **A10.2** Inference image yalnız serving deps; model artifacts read-only, PDF/source volume yok; internal auth ve network sınırı.
- [ ] **A10.3** Training worker ve PDF deposunu kapat; aktif modelle prediction/explanation ve B12/F10 sanal işlem akışı sürsün.
- [ ] **A10.4** Corrupt artifact/schema mismatch/model unavailable/OOD/stale testleri; uygun WAIT veya teknik hata, sahte skor yok.
- [ ] **A10.5** Eğitim sırasında inference latency/memory ve Simulation market lag ölç; kaynak bütçesi aşılırsa worker pause mekanizması.
- [ ] **A10.6** Checkpoint/cancel/restart, alias rollback, deleted source lineage ve unauthorized publish/import testleri.
- [ ] **A10.7** Temiz load, dataset/model manifest doğrulama, model card/limitations, metrics ve dependency locks; secrets/redacted log disiplini.
- [ ] **A10.8** B12/F10 ortak demo: PDF’siz USER, analiz→onay→sanal işlem; TRAINER’da ayrı gerçek küçük eğitim işi ve durumu.

**Kabul:** Serving request path’inde dosya extraction/retrieval yok. Yayımlanmamış candidate yanlışlıkla active değil. Gerçek eğitim uygulanmadıysa açık eksik iş, full model tamamlandı iddiası yok. Ölçülmemiş latency/VRAM/süre veya kazanç yüzdesi yazılmaz. Bir test ancak çıktısı görüldüyse PASS.
**Anlama sorusu:** “Yeni PDF ekledikten sonra eski active modelin yanıtı niye kendiliğinden değişmemeli?”
**Devir:** K6 AI kabul kanıtları. **İlk iş:** Yalnız açık borç ve artifact envanteri.

## İlerleme kaydı ve sonraki sohbet

`progress/AI.md` dosyasına sen yaz: görev/alt adım, input veri gerçek/sentetik, dataset/split/model/contract hash, çalıştırdığın komut, gözlenen metric/test, eksik bilgi, sonraki adım. Büyük veri veya ağırlıkları sohbete yüklemek şart değildir; manifest, ilgili kod ve gerekli log parçası yeterli olabilir. Asistan görmediği dataset’i kontrol etmiş veya eğitimi çalıştırmış sayılmaz.

Yeni sohbete bu rehberle beraber ana ve AI şartnamesini, son kontrol noktasını ve ilgili güncel kodu taşı. İlk kez başlıyorsan sadece **A01.1** ile ilerle; bütün AI paketini otomatik oluşturmayı istemiyorsun.
