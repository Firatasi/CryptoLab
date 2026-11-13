import { useState } from 'react';
import { Link } from 'react-router-dom';
import { BinanceMarketTerminal } from '@/features/market/BinanceMarketTerminal';
import { authApi } from '@/features/auth/api';
import { ErrorBanner } from '@/shared/ui/Feedback';

const HANDWRITING_LETTERS = ['C', 'r', 'y', 'p', 't', 'o', 'L', 'a', 'b'] as const;

export function LandingPage() {
  const [animCycle, setAnimCycle] = useState(0);
  const [hoveredIdx, setHoveredIdx] = useState<number | null>(null);
  const [cursorRatio, setCursorRatio] = useState(0.5);
  const [contactName, setContactName] = useState('');
  const [contactEmail, setContactEmail] = useState('');
  const [contactSubject, setContactSubject] = useState('');
  const [contactMessage, setContactMessage] = useState('');
  const [contactSubmitting, setContactSubmitting] = useState(false);
  const [contactError, setContactError] = useState<unknown>(null);
  const [contactSubmitted, setContactSubmitted] = useState(false);

  const handleContactSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!contactName.trim() || !contactEmail.trim() || !contactSubject.trim() || !contactMessage.trim()) {
      return;
    }
    setContactSubmitting(true);
    setContactError(null);
    setContactSubmitted(false);
    try {
      await authApi.submitContactMessage({
        senderName: contactName.trim(),
        senderEmail: contactEmail.trim(),
        subject: contactSubject.trim(),
        message: contactMessage.trim(),
        recipientEmail: 'support@cryptolab.dev',
      });
      setContactName('');
      setContactEmail('');
      setContactSubject('');
      setContactMessage('');
      setContactSubmitted(true);
    } catch (err) {
      setContactError(err);
    } finally {
      setContactSubmitting(false);
    }
  };

  return (
    <div className="bg-white text-[#0a0a0a]">
      {/* Sinematik Atmosferik Hero — Evasion Referans Estetiği */}
      <section className="relative flex min-h-[calc(100vh-5.75rem)] min-h-[calc(100dvh-5.75rem)] w-full flex-col overflow-hidden border-b border-[#e5e5e5] bg-[#1a1510] text-white lg:min-h-[calc(100vh-3.5rem)] lg:min-h-[calc(100dvh-3.5rem)]">
        {/* Atmosferik Gün Doğumu & Dağ Sırtları Katmanı */}
        <svg
          aria-hidden="true"
          viewBox="0 0 1600 900"
          preserveAspectRatio="xMidYMid slice"
          className="absolute inset-0 h-full w-full object-cover"
        >
          <defs>
            <linearGradient id="cl-sky" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="#c7a57a" />
              <stop offset="28%" stopColor="#e3c396" />
              <stop offset="52%" stopColor="#d2ae7e" />
              <stop offset="100%" stopColor="#3b2d1d" />
            </linearGradient>
            <linearGradient id="cl-haze" x1="0" y1="0" x2="1" y2="0">
              <stop offset="0%" stopColor="#f5dec0" stopOpacity="0.18" />
              <stop offset="50%" stopColor="#fff3df" stopOpacity="0.38" />
              <stop offset="100%" stopColor="#c8a273" stopOpacity="0.12" />
            </linearGradient>
            <linearGradient id="cl-ridge-1" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="#94785a" />
              <stop offset="100%" stopColor="#5e4933" />
            </linearGradient>
            <linearGradient id="cl-ridge-2" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="#6c5439" />
              <stop offset="100%" stopColor="#352819" />
            </linearGradient>
            <linearGradient id="cl-ridge-3" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="#3e2f1d" />
              <stop offset="100%" stopColor="#14100a" />
            </linearGradient>
          </defs>

          <rect width="1600" height="900" fill="url(#cl-sky)" />
          <ellipse cx="860" cy="240" rx="760" ry="140" fill="url(#cl-haze)" />

          {/* Uzak Ufuk Dağları */}
          <path
            d="M0,380 L120,350 L240,365 L370,320 L510,355 L650,315 L790,340 L940,305 L1100,335 L1250,310 L1420,345 L1600,330 L1600,900 L0,900 Z"
            fill="#b59672"
            opacity="0.65"
          />
          {/* Orta Katman Sırtlar */}
          <path
            d="M0,450 L180,410 L360,370 L530,420 L710,365 L900,400 L1080,350 L1290,395 L1460,375 L1600,410 L1600,900 L0,900 Z"
            fill="url(#cl-ridge-1)"
          />
          <path
            d="M0,540 L140,490 L330,445 L520,515 L760,460 L980,430 L1180,485 L1390,455 L1600,505 L1600,900 L0,900 Z"
            fill="url(#cl-ridge-2)"
          />
          {/* Ön Plan Koyu Orman Sırtı */}
          <path
            d="M0,620 L210,555 L420,610 L640,665 L880,585 L1140,540 L1380,615 L1600,590 L1600,900 L0,900 Z"
            fill="url(#cl-ridge-3)"
          />

          {/* Süzülen İki Kuş Silüeti */}
          <path
            d="M530,118 C542,112 553,114 562,121 C572,111 585,104 600,101 C589,110 578,118 566,127 C552,121 540,120 530,118 Z"
            fill="#231a11"
          />
          <path
            d="M730,162 C744,159 756,160 765,164 C775,160 787,158 802,159 C788,163 776,166 765,169 C754,165 742,163 730,162 Z"
            fill="#2b2015"
          />
        </svg>

        {/* Orta Metin & Eylem Katmanı (Kutusu Kaldırılmış, Ortalanmış Sade Görünüm) */}
        <div className="relative z-10 mx-auto flex w-full max-w-7xl flex-1 flex-col justify-between px-6 pt-12 pb-4 sm:pb-6">
          <div className="mx-auto flex flex-1 max-w-4xl flex-col items-center justify-center space-y-4 text-center">
            <h1 className="text-2xl font-semibold tracking-tight text-white drop-shadow-[0_2px_12px_rgba(0,0,0,0.45)] sm:whitespace-nowrap sm:text-4xl">
              Sanal İşlem Motoru Ve AI Analizi
            </h1>

            <p className="max-w-3xl text-xs leading-relaxed text-white/95 drop-shadow-[0_1px_8px_rgba(0,0,0,0.5)] sm:text-sm">
              CryptoLab; sanal USDT bakiyesi üzerinde çift taraflı muhasebe defteri (double-entry
              ledger), sunucu imzalı emir önizleme planları ve veri kalitesine duyarlı AI karar
              desteği sunan mühendislik odaklı bir araştırma terminalidir.
              <strong className="mt-1.5 block font-semibold text-white underline underline-offset-4">
                Sanal işlem · Gerçek para kullanılmaz.
              </strong>
            </p>
          </div>

          {/* Sadeleştirilmiş İnteraktif El Yazısı (Handwriting) CryptoLab Başlığı */}
          <div
            key={animCycle}
            role="button"
            tabIndex={0}
            aria-label="CryptoLab el yazısı başlığı"
            onClick={() => setAnimCycle((c) => c + 1)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                setAnimCycle((c) => c + 1);
              }
            }}
            onMouseMove={(e) => {
              const rect = e.currentTarget.getBoundingClientRect();
              if (rect.width > 0) {
                setCursorRatio(Math.min(1, Math.max(0, (e.clientX - rect.left) / rect.width)));
              }
            }}
            onMouseLeave={() => {
              setHoveredIdx(null);
              setCursorRatio(0.5);
            }}
            className="relative mt-6 cursor-pointer select-none pt-4 pb-2"
          >
            <div className="relative mx-auto flex flex-col items-center justify-center">
              <div className="flex items-baseline justify-center font-handwriting text-[14vw] font-bold leading-[0.88] tracking-[-0.015em] text-white drop-shadow-[0_4px_16px_rgba(0,0,0,0.45)] sm:text-[12.5vw]">
                {HANDWRITING_LETTERS.map((char, index) => {
                  const distance = hoveredIdx !== null ? Math.abs(hoveredIdx - index) : null;
                  const isDirectHover = distance === 0;
                  const isNeighbor = distance === 1;
                  const liftY = isDirectHover ? -10 : isNeighbor ? -4 : 0;
                  const scaleVal = isDirectHover ? 1.07 : isNeighbor ? 1.02 : 1;
                  const kerningAdjust =
                    index === 0
                      ? '-mr-[0.075em]'
                      : index === 1
                        ? '-ml-[0.02em]'
                        : index === 6
                          ? '-ml-[0.015em]'
                          : '';

                  return (
                    <span
                      key={`${char}-${index}`}
                      onMouseEnter={() => setHoveredIdx(index)}
                      style={{
                        animationDelay: `${index * 95}ms`,
                        transform: `translateY(${liftY}px) scale(${scaleVal})`,
                      }}
                      className={`animate-letter-write inline-block transition-all duration-200 ease-out ${kerningAdjust} ${
                        isDirectHover ? 'text-[#fff3df]' : 'text-white'
                      }`}
                    >
                      {char}
                    </span>
                  );
                })}
              </div>

              {/* Sade Kaligrafik İmza Çizgisi */}
              <svg
                aria-hidden="true"
                viewBox="0 0 800 28"
                className="-mt-1 h-5 w-[64%] max-w-2xl overflow-visible sm:h-7"
                fill="none"
              >
                <path
                  d={`M 40 16 Q ${Math.round(400 + (cursorRatio - 0.5) * 120)} ${
                    hoveredIdx !== null ? 24 : 18
                  } 760 12`}
                  stroke="rgba(255,255,255,0.75)"
                  strokeWidth="2.2"
                  strokeLinecap="round"
                  className="animate-signature-flourish transition-all duration-200"
                />
              </svg>
            </div>
          </div>
        </div>
      </section>

      {/* Canlı Binance TradingView Grafik ve Piyasa Genel Bakış Terminali */}
      <div id="terminal" className="scroll-mt-16">
        <BinanceMarketTerminal />
      </div>

      {/* Hakkımızda Bölümü */}
      <section
        id="hakkimizda"
        className="scroll-mt-16 border-t border-[#e5e5e5] bg-white px-6 py-20 text-[#0a0a0a]"
      >
        <div className="mx-auto max-w-7xl">
          <div className="grid grid-cols-1 gap-12 lg:grid-cols-12">
            <div className="space-y-4 lg:col-span-5">
              <span className="inline-block border border-[#0a0a0a] px-2.5 py-1 font-mono text-[10px] font-semibold uppercase tracking-widest text-[#0a0a0a]">
                01 / HAKKIMIZDA
              </span>
              <h2 className="text-2xl font-semibold tracking-tight text-[#0a0a0a] sm:text-3xl">
                Gerçek Sermaye Riske Atmadan Kurumsal Borsa Hassasiyetinde Vadeli İşlem Araştırması
              </h2>
              <p className="text-xs leading-relaxed text-[#525252] sm:text-sm">
                CryptoLab, algoritmik strateji geliştiricileri, finans mühendisleri ve piyasa
                araştırmacıları için tasarlanmış yeni nesil bir sanal vadeli işlem (Perpetual
                Futures) laboratuvarıdır. Gerçek para kullanmadan, canlı Binance USD-M piyasa
                verileri üzerinde kurumsal muhasebe ve risk disipliniyle çalışmanızı sağlar.
              </p>
            </div>

            <div className="grid grid-cols-1 gap-px border border-[#e5e5e5] bg-[#e5e5e5] sm:grid-cols-2 lg:col-span-7">
              <div className="space-y-2.5 bg-white p-6">
                <div className="font-mono text-[11px] font-semibold uppercase tracking-widest text-[#737373]">
                  ÇİFT TARAFLI MUHASEBE (DOUBLE-ENTRY)
                </div>
                <h3 className="text-sm font-semibold text-[#0a0a0a]">
                  Sıfır Yuvarlama Hatası, Tam Denetlenebilirlik
                </h3>
                <p className="text-xs leading-relaxed text-[#525252]">
                  Sanal USDT bakiyeleri, marjin blokajları, komisyon kesintileri, fonlama ödemeleri
                  ve gerçekleşen kar/zarar (PnL) kalemleri değişmez muhasebe defteri kayıtlarıyla
                  tutulur.
                </p>
              </div>

              <div className="space-y-2.5 bg-white p-6">
                <div className="font-mono text-[11px] font-semibold uppercase tracking-widest text-[#737373]">
                  CANLI PİYASA AKIŞI &amp; EMİR PLANI
                </div>
                <h3 className="text-sm font-semibold text-[#0a0a0a]">
                  Sunucu İmzalı Emir Önizleme Sözleşmesi
                </h3>
                <p className="text-xs leading-relaxed text-[#525252]">
                  Emir gönderilmeden önce tahmini tasfiye fiyatı, başabaş noktası ve marjin etkisi
                  sunucu tarafından hesaplanıp imzalanır; onayınız olmadan hiçbir işlem yürütülmez.
                </p>
              </div>

              <div className="space-y-2.5 bg-white p-6">
                <div className="font-mono text-[11px] font-semibold uppercase tracking-widest text-[#737373]">
                  AI KARAR DESTEK SİSTEMİ
                </div>
                <h3 className="text-sm font-semibold text-[#0a0a0a]">
                  Veri Kalitesine Duyarlı Analiz ve Tarama
                </h3>
                <p className="text-xs leading-relaxed text-[#525252]">
                  Yapay zeka katmanı piyasa akışındaki gecikmeleri ve veri tazeliğini denetler;
                  belirsizlik yüksek olduğunda yanıltıcı sinyal üretmek yerine çekimser (WAIT)
                  kalır.
                </p>
              </div>

              <div className="space-y-2.5 bg-white p-6">
                <div className="font-mono text-[11px] font-semibold uppercase tracking-widest text-[#737373]">
                  TARİHSEL TEKRAR (REPLAY)
                </div>
                <h3 className="text-sm font-semibold text-[#0a0a0a]">
                  Tekrarlanabilir Geriye Dönük Strateji Testi
                </h3>
                <p className="text-xs leading-relaxed text-[#525252]">
                  Geçmiş piyasa mumları üzerinde aynı başlangıç parametreleriyle birebir aynı
                  finansal sonuçları üreten şeffaf simülasyon motoru ile stratejilerinizi doğrulayın.
                </p>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* Vizyon & Misyon Bölümü */}
      <section
        id="vizyon-misyon"
        className="scroll-mt-16 border-t border-[#e5e5e5] bg-[#f5f5f5] px-6 py-20 text-[#0a0a0a]"
      >
        <div className="mx-auto max-w-7xl space-y-12">
          <div className="max-w-2xl space-y-3">
            <span className="inline-block border border-[#0a0a0a] bg-white px-2.5 py-1 font-mono text-[10px] font-semibold uppercase tracking-widest text-[#0a0a0a]">
              02 / VİZYON &amp; MİSYON
            </span>
            <h2 className="text-2xl font-semibold tracking-tight text-[#0a0a0a] sm:text-3xl">
              Finansal Mühendislik ve Şeffaf Yapay Zeka İlkelerimiz
            </h2>
          </div>

          <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
            {/* Vizyon Kartı */}
            <div className="flex flex-col justify-between border border-[#e5e5e5] bg-white p-8">
              <div className="space-y-4">
                <div className="flex items-center justify-between border-b border-[#e5e5e5] pb-4">
                  <span className="font-mono text-xs font-semibold uppercase tracking-widest text-[#0a0a0a]">
                    VİZYONUMUZ
                  </span>
                  <span className="font-mono text-xs text-[#737373]">VISION // 2026+</span>
                </div>
                <h3 className="text-lg font-semibold text-[#0a0a0a]">
                  Vadeli İşlem Araştırmalarında Küresel Referans Laboratuvarı Olmak
                </h3>
                <p className="text-xs leading-relaxed text-[#525252] sm:text-sm">
                  Bireysel araştırmacıların, öğrencilerin ve algoritmik işlem ekiplerinin; gerçek
                  sermaye kaybı riski taşımadan, kurumsal yatırım bankaları ve profesyonel fonlarla
                  aynı matematiksel hassasiyette strateji geliştirebildiği, yapay zeka modellerini
                  eğitebildiği ve test edebildiği açık, güvenilir bir finansal teknoloji ekosistemi
                  inşa etmek.
                </p>
              </div>
              <div className="mt-8 grid grid-cols-3 gap-4 border-t border-[#e5e5e5] pt-5">
                <div>
                  <div className="font-mono text-sm font-semibold text-[#0a0a0a]">%100</div>
                  <div className="text-[11px] text-[#737373]">Sanal Sermaye Güvencesi</div>
                </div>
                <div>
                  <div className="font-mono text-sm font-semibold text-[#0a0a0a]">7/24</div>
                  <div className="text-[11px] text-[#737373]">Canlı Binance Veri Akışı</div>
                </div>
                <div>
                  <div className="font-mono text-sm font-semibold text-[#0a0a0a]">Sıfır</div>
                  <div className="text-[11px] text-[#737373]">Finansal Kayıp Riski</div>
                </div>
              </div>
            </div>

            {/* Misyon Kartı */}
            <div className="flex flex-col justify-between border border-[#262626] bg-[#0a0a0a] p-8 text-white">
              <div className="space-y-4">
                <div className="flex items-center justify-between border-b border-[#262626] pb-4">
                  <span className="font-mono text-xs font-semibold uppercase tracking-widest text-white">
                    MİSYONUMUZ
                  </span>
                  <span className="font-mono text-xs text-[#a3a3a3]">MISSION // CORE</span>
                </div>
                <h3 className="text-lg font-semibold text-white">
                  Şeffaf Muhasebe, Açıklanabilir AI ve Doğrulanabilir Simülasyon Sunmak
                </h3>
                <p className="text-xs leading-relaxed text-[#a3a3a3] sm:text-sm">
                  Kripto türev piyasalarındaki karmaşık marjin, kaldıraç, tasfiye (liquidation) ve
                  fonlama mekanizmalarını kara kutu olmaktan çıkararak; her kuruşun izlenebildiği
                  çift taraflı muhasebe defteri, gerekçeli yapay zeka analizleri ve tekrarlanabilir
                  tarihsel test araçlarıyla kullanıcıların finansal okuryazarlığını ve analitik
                  yetkinliğini en üst seviyeye taşımak.
                </p>
              </div>
              <div className="mt-8 grid grid-cols-3 gap-4 border-t border-[#262626] pt-5">
                <div>
                  <div className="font-mono text-sm font-semibold text-white">Mark ≠ Last</div>
                  <div className="text-[11px] text-[#a3a3a3]">Gerçekçi Tasfiye Motoru</div>
                </div>
                <div>
                  <div className="font-mono text-sm font-semibold text-white">Açıklanabilir</div>
                  <div className="text-[11px] text-[#a3a3a3]">Kanıt Odaklı AI Raporu</div>
                </div>
                <div>
                  <div className="font-mono text-sm font-semibold text-white">İzole Alan</div>
                  <div className="text-[11px] text-[#a3a3a3]">Eğitici &amp; Araştırma Modu</div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* İletişim Bölümü */}
      <section
        id="iletisim"
        className="scroll-mt-16 border-t border-[#e5e5e5] bg-white px-6 py-20 text-[#0a0a0a]"
      >
        <div className="mx-auto max-w-7xl">
          <div className="grid grid-cols-1 gap-12 lg:grid-cols-12">
            <div className="space-y-6 lg:col-span-5">
              <div className="space-y-3">
                <span className="inline-block border border-[#0a0a0a] px-2.5 py-1 font-mono text-[10px] font-semibold uppercase tracking-widest text-[#0a0a0a]">
                  03 / İLETİŞİM
                </span>
                <h2 className="text-2xl font-semibold tracking-tight text-[#0a0a0a] sm:text-3xl">
                  Araştırma Ekibimizle İletişime Geçin
                </h2>
                <p className="text-xs leading-relaxed text-[#525252] sm:text-sm">
                  Akademik işbirlikleri, kurumsal simülasyon talepleri, eğitici (Trainer) yetkisi
                  başvuruları veya teknik sorularınız için doğrudan ekibimize ulaşabilirsiniz.
                </p>
              </div>

              <div className="space-y-4 border-t border-[#e5e5e5] pt-6 text-xs">
                <div className="flex flex-col gap-1 border border-[#e5e5e5] p-4">
                  <span className="font-mono text-[10px] font-semibold uppercase tracking-widest text-[#737373]">
                    ARAŞTIRMA &amp; AKADEMİK İŞBİRLİĞİ
                  </span>
                  <span className="font-mono text-sm font-semibold text-[#0a0a0a]">
                    research@cryptolab.dev
                  </span>
                  <span className="text-[#525252]">
                    Üniversite laboratuvarları, tez çalışmaları ve veri seti talepleri.
                  </span>
                </div>

                <div className="flex flex-col gap-1 border border-[#e5e5e5] p-4">
                  <span className="font-mono text-[10px] font-semibold uppercase tracking-widest text-[#737373]">
                    TEKNİK DESTEK &amp; SİSTEM OPERASYONU
                  </span>
                  <span className="font-mono text-sm font-semibold text-[#0a0a0a]">
                    support@cryptolab.dev
                  </span>
                  <span className="text-[#525252]">
                    API ağ geçidi, sanal oturum yönetimi ve tarihsel tekrar motoru desteği.
                  </span>
                </div>
              </div>
            </div>

            <div className="border border-[#e5e5e5] bg-[#f5f5f5] p-8 lg:col-span-7">
              <h3 className="text-base font-semibold text-[#0a0a0a]">Hızlı İletişim Formu</h3>
              <p className="mt-1 text-xs text-[#525252]">
                Mesajınızı bırakın; mühendislik ve araştırma ekibimiz en kısa sürede dönüş yapsın.
              </p>

              <form onSubmit={handleContactSubmit} className="mt-6 space-y-4">
                {contactError ? <ErrorBanner error={contactError} /> : null}
                {contactSubmitted ? (
                  <div className="border border-[#0a0a0a] bg-white px-4 py-3 text-xs text-[#0a0a0a]">
                    Mesajınız <span className="font-mono font-semibold">support@cryptolab.dev</span>{' '}
                    destek kutusuna ve yönetici paneline başarıyla kaydedildi.
                  </div>
                ) : null}

                <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                  <div className="space-y-1.5">
                    <label
                      htmlFor="contact-name"
                      className="block font-mono text-[11px] font-semibold uppercase tracking-wider text-[#0a0a0a]"
                    >
                      Ad Soyad
                    </label>
                    <input
                      id="contact-name"
                      type="text"
                      required
                      value={contactName}
                      onChange={(e) => setContactName(e.target.value)}
                      placeholder="Adınız ve Soyadınız"
                      className="w-full border border-[#e5e5e5] bg-white px-3.5 py-2.5 text-xs text-[#0a0a0a] placeholder-[#a3a3a3] focus:border-[#0a0a0a] focus:outline-none"
                    />
                  </div>
                  <div className="space-y-1.5">
                    <label
                      htmlFor="contact-email"
                      className="block font-mono text-[11px] font-semibold uppercase tracking-wider text-[#0a0a0a]"
                    >
                      E-Posta Adresi
                    </label>
                    <input
                      id="contact-email"
                      type="email"
                      required
                      value={contactEmail}
                      onChange={(e) => setContactEmail(e.target.value)}
                      placeholder="ornek@kurum.com"
                      className="w-full border border-[#e5e5e5] bg-white px-3.5 py-2.5 text-xs text-[#0a0a0a] placeholder-[#a3a3a3] focus:border-[#0a0a0a] focus:outline-none"
                    />
                  </div>
                </div>

                <div className="space-y-1.5">
                  <label
                    htmlFor="contact-subject"
                    className="block font-mono text-[11px] font-semibold uppercase tracking-wider text-[#0a0a0a]"
                  >
                    Konu
                  </label>
                  <input
                    id="contact-subject"
                    type="text"
                    required
                    value={contactSubject}
                    onChange={(e) => setContactSubject(e.target.value)}
                    placeholder="Örn: Eğitici (Trainer) Hesabı / Strateji Araştırma İşbirliği"
                    className="w-full border border-[#e5e5e5] bg-white px-3.5 py-2.5 text-xs text-[#0a0a0a] placeholder-[#a3a3a3] focus:border-[#0a0a0a] focus:outline-none"
                  />
                </div>

                <div className="space-y-1.5">
                  <label
                    htmlFor="contact-message"
                    className="block font-mono text-[11px] font-semibold uppercase tracking-wider text-[#0a0a0a]"
                  >
                    Mesajınız
                  </label>
                  <textarea
                    id="contact-message"
                    rows={4}
                    required
                    value={contactMessage}
                    onChange={(e) => setContactMessage(e.target.value)}
                    placeholder="Talebinizi veya sorunuzu detaylıca yazabilirsiniz..."
                    className="w-full border border-[#e5e5e5] bg-white px-3.5 py-2.5 text-xs text-[#0a0a0a] placeholder-[#a3a3a3] focus:border-[#0a0a0a] focus:outline-none"
                  />
                </div>

                <div className="flex items-center justify-between pt-2">
                  <span className="text-[11px] text-[#737373]">
                    Üye olmadan da mesaj gönderebilirsiniz.
                  </span>
                  <button
                    type="submit"
                    disabled={contactSubmitting}
                    className="bg-[#0a0a0a] px-6 py-2.5 text-xs font-semibold text-white transition-colors hover:bg-[#262626] disabled:opacity-50"
                  >
                    {contactSubmitting ? 'Gönderiliyor...' : 'Mesajı Gönder'}
                  </button>
                </div>
              </form>
            </div>
          </div>
        </div>
      </section>

      {/* Alt Bilgi (Footer) */}
      <footer className="border-t border-[#262626] bg-[#0a0a0a] text-white">
        <div className="mx-auto max-w-7xl px-6 py-7">
          <div className="grid grid-cols-1 gap-6 md:grid-cols-12 md:items-start">
            {/* Marka ve Özet */}
            <div className="space-y-2 md:col-span-5">
              <Link
                to="/"
                className="inline-block font-handwriting text-2xl font-bold tracking-normal text-white transition-opacity hover:opacity-85"
              >
                CryptoLab
              </Link>
              <p className="max-w-md text-xs leading-relaxed text-[#a3a3a3]">
                Sanal USDT bakiyesi üzerinde çift taraflı muhasebe defteri, sunucu imzalı emir
                önizleme planları ve veri kalitesine duyarlı AI karar desteği sunan araştırma
                terminali.
              </p>
            </div>

            {/* Hızlı Bağlantılar */}
            <div className="space-y-2 md:col-span-4">
              <h2 className="font-mono text-[10px] font-semibold uppercase tracking-[0.18em] text-[#737373]">
                Kurumsal
              </h2>
              <ul className="grid grid-cols-2 gap-x-6 gap-y-1.5 text-xs text-[#d4d4d4]">
                <li>
                  <a href="#terminal" className="transition-colors hover:text-white">
                    Canlı Grafik
                  </a>
                </li>
                <li>
                  <a href="#hakkimizda" className="transition-colors hover:text-white">
                    Hakkımızda
                  </a>
                </li>
                <li>
                  <a href="#vizyon-misyon" className="transition-colors hover:text-white">
                    Vizyon &amp; Misyon
                  </a>
                </li>
                <li>
                  <a href="#iletisim" className="transition-colors hover:text-white">
                    İletişim
                  </a>
                </li>
              </ul>
            </div>

            {/* Altyapı */}
            <div className="space-y-2 md:col-span-3">
              <h2 className="font-mono text-[10px] font-semibold uppercase tracking-[0.18em] text-[#737373]">
                Altyapı
              </h2>
              <ul className="space-y-1.5 text-xs text-[#a3a3a3]">
                <li className="flex items-center gap-2">
                  <span className="h-1.5 w-1.5 rounded-full bg-emerald-500/80" aria-hidden="true" />
                  <span>Binance USD-M Veri Akışı</span>
                </li>
                <li className="flex items-center gap-2">
                  <span className="h-1.5 w-1.5 rounded-full bg-[#737373]" aria-hidden="true" />
                  <span>Çift Taraflı Muhasebe</span>
                </li>
                <li className="flex items-center gap-2">
                  <span className="h-1.5 w-1.5 rounded-full bg-[#737373]" aria-hidden="true" />
                  <span>AI Analiz &amp; Tarama</span>
                </li>
              </ul>
            </div>
          </div>

          <div className="mt-6 flex flex-col items-center justify-between gap-2 border-t border-[#1f1f1f] pt-4 text-[11px] text-[#737373] sm:flex-row">
            <span>© {new Date().getFullYear()} CryptoLab. Tüm hakları saklıdır.</span>
            <span>Sanal İşlem Araştırma Terminali · Gerçek para kullanılmaz.</span>
          </div>
        </div>
      </footer>
    </div>
  );
}
