"""A07.7: Araştırma ve strateji hipotezlerinin temsili, epistemik sınıflandırma ve doğrulanması.
Kural: Hipotezler araştırma/deney config taslağıdır; doğrudan model çıktısı veya canlı emir politikası olarak deploy edilemez.
PDF kaynaklarındaki bilgiler üç katmanda ayrılır:
1. VERIFIED_DEFINITION: Doğrulanmış matematiksel/yapısal tanım.
2. RESEARCH_FINDING: Belirli veri seti ve dönemle sınırlı akademik araştırma bulgusu.
3. TESTABLE_HYPOTHESIS: Maliyet sonrası (komisyon, funding, slippage) dokunulmamış veride test edilmesi gereken hipotez.
"""
from dataclasses import dataclass
from typing import Any
from uuid import UUID, uuid4

EPISTEMIC_TIERS = {
    "VERIFIED_DEFINITION",
    "RESEARCH_FINDING",
    "TESTABLE_HYPOTHESIS",
}


@dataclass(frozen=True)
class KnowledgeItem:
    """PDF kaynaklarından çıkarılan bilginin epistemik katman kaydı."""

    item_id: str
    tier: str
    source_id: str
    locator: str
    statement: str
    scope_limitation: str
    is_live_executable: bool = False

    def __post_init__(self) -> None:
        if self.tier not in EPISTEMIC_TIERS:
            raise ValueError(f"INVALID_EPISTEMIC_TIER: {self.tier}")
        if self.is_live_executable:
            raise ValueError("KNOWLEDGE_ITEM_CANNOT_BE_LIVE_EXECUTABLE")

    def to_dict(self) -> dict[str, Any]:
        return {
            "itemId": self.item_id,
            "tier": self.tier,
            "sourceId": self.source_id,
            "locator": self.locator,
            "statement": self.statement,
            "scopeLimitation": self.scope_limitation,
            "isLiveExecutable": self.is_live_executable,
        }


class StrategyHypothesis:
    """PDF veya araştırma kaynaklarından çıkarılan test edilebilir strateji hipotezi taslağı."""

    def __init__(
        self,
        hypothesis_id: UUID,
        title: str,
        feature_requirements: list[str],
        entry_conditions: dict[str, Any],
        exit_conditions: dict[str, Any],
        parameter_ranges: dict[str, tuple[float, float]],
        source_lineage: list[dict[str, Any]],
        notes: str = "",
    ):
        self.hypothesis_id = hypothesis_id
        self.title = title
        self.feature_requirements = feature_requirements
        self.entry_conditions = entry_conditions
        self.exit_conditions = exit_conditions
        self.parameter_ranges = parameter_ranges
        self.source_lineage = source_lineage
        self.notes = notes

    def to_experiment_config(self) -> dict[str, Any]:
        """B11 Replay veya simülasyon deneyleri için konfigürasyon taslağı üretir."""
        return {
            "hypothesisId": str(self.hypothesis_id),
            "title": self.title,
            "status": "EXPERIMENT_CANDIDATE",
            "isLiveExecutable": False,  # Asla doğrudan canlıya alınamaz
            "featureRequirements": self.feature_requirements,
            "entryConditions": self.entry_conditions,
            "exitConditions": self.exit_conditions,
            "parameterRanges": {k: list(v) for k, v in self.parameter_ranges.items()},
            "sourceLineage": self.source_lineage,
            "notes": self.notes,
        }


def build_domain_knowledge_catalog() -> list[KnowledgeItem]:
    """10 PDF kaynağındaki temel bilgileri Doğrulanmış Tanım, Araştırma Bulgusu ve Hipotez olarak ayırır."""
    return [
        # --- DOĞRULANMIŞ TANIMLAR (VERIFIED_DEFINITION) ---
        KnowledgeItem(
            item_id="K-DEF-01",
            tier="VERIFIED_DEFINITION",
            source_id="S04",
            locator="Cont et al. (2011), s.3-6, Denklem (1)-(3)",
            statement="Order Flow Imbalance (OFI), en iyi alış/satış (Level 1) kuyruklarındaki limit emir ekleme, iptal ve piyasa emirlerinin net toplamıdır: OFI_k = sum(e_n).",
            scope_limitation="Hesaplama için Level 1 bid/ask fiyat ve miktar güncellemeleri zorunludur; yalnız OHLCV mum verisinden gerçek OFI hesaplanamaz.",
        ),
        KnowledgeItem(
            item_id="K-DEF-02",
            tier="VERIFIED_DEFINITION",
            source_id="S05",
            locator="Ackerer et al. (2024), s.7-12, Denklem (4)-(6), Proposition 1 & Corollary 1",
            statement="Perpetual futures sözleşmelerinde vade yoktur; fiyatın spot fiyata çapalanması periyodik funding ödemesi [kappa_t*(f_t - x_t) + iota_t*x_t] ile sağlanır. iota_t = (r_at - r_bt)/(1 + r_bt) seçildiğinde arbitrajsız teorik vadeli fiyat spota eşitlenir (f_t = x_t).",
            scope_limitation="Teorik arbitrajsız fiyatlama özdeşliğidir; tek başına gelecekteki spot fiyat yönünü tahmin etmez.",
        ),
        KnowledgeItem(
            item_id="K-DEF-03",
            tier="VERIFIED_DEFINITION",
            source_id="S25",
            locator="Kapoor & Narayanan (2022), s.5-6, Bölüm 2.4 [L1.1-L3.3]",
            statement="Veri sızıntısı (data leakage) taksonomisi 8 alt tipten oluşur: L1.1 test seti olmaması, L1.2 tüm veri üzerinde ön-işleme/imputation, L1.3 tüm veri üzerinde özellik seçimi, L1.4 tekrarlı örnekler, L2 hedef vekili (proxy) özellikler, L3.1 zamansal sızıntı, L3.2 bağımlı örnekler, L3.3 seçim yanlılığı.",
            scope_limitation="Metodolojik doğrulama standardıdır; tüm sızıntı kontrolleri geçilse dahi strateji kârlılığı garanti edilmez.",
        ),
        KnowledgeItem(
            item_id="K-DEF-04",
            tier="VERIFIED_DEFINITION",
            source_id="S02",
            locator="Bailey et al. (2015), s.9-13, Definition 2.1-2.2 & Algorithm 2.3 (CSCV)",
            statement="Backtest Aşırı Uyum Olasılığı (PBO), örneklem içi (IS) en iyi seçilen stratejinin örneklem dışı (OOS) medyan performansın altında kalma olasılığıdır ve CSCV kombinatorik alt-matris algoritması ile logit dağılımından (lambda_c = ln(omega_c / (1 - omega_c))) hesaplanır.",
            scope_limitation="CSCV/PBO bir strateji seçim sürecinin güvenilirliğini ölçer; hedef fonksiyon olarak optimize edilirse kendisi aşırı uyuma yol açar.",
        ),
        KnowledgeItem(
            item_id="K-DEF-05",
            tier="VERIFIED_DEFINITION",
            source_id="S09",
            locator="Modül 1 (Makro Likidite) & FRED WALCL/WTREGEN/RRPONTSYD",
            statement="Net Likidite vekili WALCL - TGA - RRP formülüyle tanımlanır; WALCL ve TGA milyon USD, RRP milyar USD olduğundan birim dönüşümü ve yayınlanma zamanı (availableAt) hizalaması zorunludur.",
            scope_limitation="Gözlem tarihi ile kamuya açıklanma tarihi farklıdır; revize edilmiş bugünkü seriyi geçmişe yazmak lookahead sızıntısıdır.",
        ),
        # --- ARAŞTIRMA BULGULARI (RESEARCH_FINDING) ---
        KnowledgeItem(
            item_id="K-RES-01",
            tier="RESEARCH_FINDING",
            source_id="S04",
            locator="Cont et al. (2011), s.8-17, Tablo 2 & Tablo 4",
            statement="Nisan 2010 NYSE TAQ verisinde 50 ABD hissesi için 10 saniyelik dilimlerde OFI orta-fiyat değişimlerini doğrusal olarak ortalama %65 R^2 ile açıklarken, yalnızca işlem hacmi dengesizliği (Trade Imbalance) %32 R^2 düzeyinde kalmıştır.",
            scope_limitation="Bulgu 2010 yılı ABD hisse senedi NBBO verisine aittir; 7/24 parçalı kripto borsalarında aynı R^2 katsayısı doğrudan varsayılamaz.",
        ),
        KnowledgeItem(
            item_id="K-RES-02",
            tier="RESEARCH_FINDING",
            source_id="S25",
            locator="Kapoor & Narayanan (2022), s.3, Şekil 1 & Bölüm 4",
            statement="İncelenen 17 alandaki 329 makalede veri sızıntısı tespit edilmiş; sızıntı (ortak imputation, k-fold zamansal karıştırma, vekil değişken) düzeltildiğinde karmaşık ML modellerinin basit Lojistik Regresyon baseline'ına göre üstünlüğü ortadan kalkmıştır.",
            scope_limitation="Literatür meta-analiz bulgusudur; her yeni model kendi sızıntı bilgi formu (Model Info Sheet) ve basit baseline karşılaştırmasıyla sınanmalıdır.",
        ),
        KnowledgeItem(
            item_id="K-RES-03",
            tier="RESEARCH_FINDING",
            source_id="S01",
            locator="Fang et al. (2022), s.40-45, Şekil 9-10 & Bölüm 10-11",
            statement="146 kripto ticaret makalesinin taramasında teknik/istatistiksel ve ML çalışmalarının çoğunun veri kalitesi, işlem maliyeti ve zaman ufku (kısa ufukta yüksek komisyon/slippage etkisi) kısıtlarına duyarlı olduğu; 2017 sonrası çalışmaların yarısından azının temiz veri kullandığı raporlanmıştır.",
            scope_limitation="Tarama çalışmasıdır; literatürde bir yöntemin sık kullanılması maliyet sonrası pozitif net beklenti kanıtı değildir.",
        ),
        KnowledgeItem(
            item_id="K-RES-04",
            tier="RESEARCH_FINDING",
            source_id="S05",
            locator="Ackerer et al. (2024), s.21-24, Bölüm 7.2, Proposition 5",
            statement="Quanto perpetual sözleşmelerde teminat para birimi (ör. BTC) ile dayanak parite (ör. ETH/USD) farklı olduğundan teorik fiyatta kovaryans (sigma_x * sigma_z) kaynaklı konvekslik düzeltmesi (convexity adjustment) ortaya çıkar.",
            scope_limitation="Sürekli zamanlı difüzyon varsayımı altındaki fiyatlama sonucudur.",
        ),
        # --- TEST EDİLMESİ GEREKEN HİPOTEZLER (TESTABLE_HYPOTHESIS) ---
        KnowledgeItem(
            item_id="K-HYP-01",
            tier="TESTABLE_HYPOTHESIS",
            source_id="S04",
            locator="Cont et al. (2011), s.6, Denklem (2)-(3) -> Kripto uyarlama hipotezi",
            statement="Kripto paritelerinde kısa ufuklu normalize OFI ve emir defteri derinliği (beta_i = c / AD_i^lambda) oranının, taker komisyonu ve slippage düşüldükten sonra sıfır-getiri baseline'ına göre anlamlı tahmin üstünlüğü sağladığı hipotezi.",
            scope_limitation="Canlıya alınamaz; önce B06/B11 replay hattında komisyon ve slippage dahil OOS testinden ve PBO < 0.05 kapısından geçmelidir.",
        ),
        KnowledgeItem(
            item_id="K-HYP-02",
            tier="TESTABLE_HYPOTHESIS",
            source_id="S05",
            locator="Ackerer et al. (2024), s.7-12 & Modül 3 (Funding Aşırılıkları)",
            statement="Perpetual futures primi (f_t - x_t) ve 8 saatlik funding oranının belirli bir eşiği aşmasının, funding ödemesi ve likidasyon riski düşüldükten sonra ortalama dönüş (mean-reversion) yönünde pozitif net beklenti ürettiği hipotezi.",
            scope_limitation="Yüksek pozitif funding güçlü trendlerde uzun süre devam edebilir; yalnız funding oranına bakarak otomatik ters pozisyon açılamaz.",
        ),
        KnowledgeItem(
            item_id="K-HYP-03",
            tier="TESTABLE_HYPOTHESIS",
            source_id="S01",
            locator="Fang et al. (2022), s.17-19, Bölüm 5.3 & 6.1 (ATR/EMA kırılım ve teknik kurallar)",
            statement="Çoklu zaman dilimi EMA/ATR volatilite bütçeli trend takip kurallarının kripto piyasasında komisyon, funding ve slippage sonrası 1/N veya Buy-and-Hold referans stratejisini aynı maksimum drawdown kısıtı altında geçtiği hipotezi.",
            scope_limitation="Çoklu parametre denemelerinde CSCV/PBO ile aşırı uyum kontrolü yapılmadan geçerli kabul edilmez.",
        ),
    ]


