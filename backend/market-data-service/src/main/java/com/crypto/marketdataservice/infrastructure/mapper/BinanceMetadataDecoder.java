package com.crypto.marketdataservice.infrastructure.mapper;
import com.crypto.marketdataservice.application.port.MarketSource.Instrument;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
/**
 * Sorumluluk: Dış sistem veya teknik sınırı uygular.
 * Okuma ipucu: Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.
 */
public final class BinanceMetadataDecoder {
    private BinanceMetadataDecoder(){}
    private static JsonNode filter(JsonNode symbol,String type){
        for(var f:symbol.required("filters"))if(type.equals(f.required("filterType").asText()))return f;
        throw new IllegalArgumentException("MISSING_FILTER:"+type);
    }
    public static List<Instrument> decode(JsonNode response){
        List<Instrument> out=new ArrayList<>();
        for(var s:response.required("symbols")){
            String symbol=s.required("symbol").asText();
            String identity=s.required("contractType").asText()+":"+s.required("onboardDate").asText();
            // Katalogdan unsupported kontratı silmeyiz; paperTradable capability daha sonra false olabilir.
            out.add(new Instrument("BINANCE",symbol,identity,s.required("contractType").asText(),
                    s.required("baseAsset").asText(),s.required("quoteAsset").asText(),s.required("marginAsset").asText(),
                    s.required("status").asText(),filter(s,"PRICE_FILTER").required("tickSize").asText(),
                    filter(s,"LOT_SIZE").required("stepSize").asText(),filter(s,"MIN_NOTIONAL").required("notional").asText()));
        }
        return List.copyOf(out);
    }
}