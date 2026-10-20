package com.crypto.marketdataservice.infrastructure.messaging;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
/**
 * Sorumluluk: Dış sistem veya teknik sınırı uygular.
 * Okuma ipucu: Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.
 */
@Component
public class OutboxPublisher {
    private record Item(UUID id,String topic,String key,String json){}
    private final JdbcTemplate db;private final KafkaTemplate<String,String> kafka;
    public OutboxPublisher(JdbcTemplate db,KafkaTemplate<String,String> kafka){this.db=db;this.kafka=kafka;}
    @Scheduled(fixedDelay=500) public void publish(){
        // Tek atomic UPDATE claim yapar; broker beklerken finans session kilidi tutulmaz.
        var batch=db.query("""
   UPDATE outbox SET lease_until=now()+interval '30 seconds',attempts=attempts+1
   WHERE event_id IN (SELECT event_id FROM outbox WHERE published_at IS NULL
    AND (lease_until IS NULL OR lease_until<now()) ORDER BY created_at LIMIT 20 FOR UPDATE SKIP LOCKED)
   RETURNING event_id,topic,event_key,payload::text
   """,(rs,n)->new Item(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4)));
        for(var item:batch){
            try{
                kafka.send(item.topic(),item.key(),item.json()).get(5,TimeUnit.SECONDS);
                // Publish başarılı, DB update öncesi crash olursa aynı eventId yeniden gelir.
                db.update("UPDATE outbox SET published_at=now(),lease_until=NULL WHERE event_id=?",item.id());
            }catch(InterruptedException e){Thread.currentThread().interrupt();return;}
            catch(Exception e){
                // Retry sonraki lease'te; exception payload/secrets olarak loglanmaz.
                // Sayaç/son hata metadatasını operasyon metriklerine bağla.
            }
        }
    }
}