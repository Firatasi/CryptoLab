package com.crypto.marketdataservice.infrastructure.client.fixture;
import com.crypto.marketdataservice.application.port.MarketSource;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;

public class FixtureAdapter implements MarketSource {
    private final Clock clock;
    public FixtureAdapter(Clock clock){this.clock=clock;}
    public List<Instrument> listInstruments(){return List.of(new Instrument("FIXTURE","BTCUSDT","educational-1","PERPETUAL","BTC","USDT","USDT","TRADING","0.1","0.001","5"));}
    public List<Candle> loadCandles(String s,String i,Instant f,Instant t){return List.of();}
    public List<Candle> loadMarkHistory(String s,String i,Instant f,Instant t){return List.of();}
    public List<Funding> loadFundingHistory(String s,Instant f,Instant t){return List.of();}
    public AutoCloseable connectMarks(List<String> symbols,Consumer<Mark> sink){
        // Boş history gerçek veri varmış gibi doldurulmaz. Tek kontrollü tick öğretim içindir.
        var now=clock.instant();symbols.forEach(s->sink.accept(new Mark(s,"50000",now,now,"fixture-1","1")));return ()->{};
    }
    public AutoCloseable connectQuotes(List<String> symbols,Consumer<Quote> sink){
        var now=clock.instant();symbols.forEach(s->sink.accept(new Quote(s,"50000","2","50000","2",now,now,"fixture-1","1")));return ()->{};
    }
}