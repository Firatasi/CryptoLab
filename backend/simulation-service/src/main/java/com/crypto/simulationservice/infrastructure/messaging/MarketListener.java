package com.crypto.simulationservice.infrastructure.messaging;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
/**
 * Sorumluluk: Dış sistem veya teknik sınırı uygular.
 * Okuma ipucu: Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.
 */
@Component
public class MarketListener {
    private final MarketIngress ingress;
    public MarketListener(MarketIngress ingress){this.ingress=ingress;}
    @KafkaListener(topics={"market.mark.v2","market.quote.v2","market.funding.settled.v2"},groupId="simulation-market-v2")
    public void consume(String payload,Acknowledgment ack)throws Exception{
        ingress.accept(payload); // Proxy döndüğünde DB commit başarılıdır.
        ack.acknowledge(); // Offset DB'den önce giderse crash'te veri kaybedersin.
    }
}