package com.crypto.marketdataservice.application.port;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

public interface MarketSource {
    // Provider JSON nesnesi domain'e sızmaz; normalize edilmiş kendi kayıtlarımız döner.
    record Instrument(String exchange,String symbol,String contractIdentity,String marketType,
                      String base,String quote,String settlement,String status,String tick,String step,String minNotional){}

    record Candle(String symbol,String interval,String priceKind,Instant openTime,Instant closeTime,
                  Instant availableAt,String open,String high,String low,String close,String volume,boolean finalized){}

    record Mark(String symbol,String price,Instant sourceTime,Instant receivedAt,String epoch,String sequence){}

    record Quote(String symbol,String bid,String bidQty,String ask,String askQty,Instant sourceTime,Instant receivedAt,String epoch,String sequence){}

    record Funding(String symbol,Instant settlementTime,String rate,String settlementMark,String sourceId){}

    List<Instrument> listInstruments();
    List<Candle> loadCandles(String symbol,String interval,Instant from,Instant to);
    List<Candle> loadMarkHistory(String symbol,String interval,Instant from,Instant to);
    List<Funding> loadFundingHistory(String symbol,Instant from,Instant to);
    AutoCloseable connectMarks(List<String> symbols,Consumer<Mark> sink);
    AutoCloseable connectQuotes(List<String> symbols,Consumer<Quote> sink);
}