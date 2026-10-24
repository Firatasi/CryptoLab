package com.crypto.marketdataservice.infrastructure.cache;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.DataAccessException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
/**
 * Sorumluluk: Dış sistem veya teknik sınırı uygular.
 * Okuma ipucu: Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.
 */
public class SnapshotCache {
    private final StringRedisTemplate redis;
    public SnapshotCache(StringRedisTemplate redis){this.redis=redis;}
    public void put(UUID instrument,String kind,String originalJson){
        Duration ttl=kind.equals("mark")?Duration.ofSeconds(5):Duration.ofSeconds(2);
        try{redis.opsForValue().set("market:"+kind+":"+instrument,originalJson,ttl);}
        catch(DataAccessException e){/* Cache hata metriği; finans state'i bu sınıfta yok. */}
    }
    public Optional<String> get(UUID instrument,String kind){
        try{return Optional.ofNullable(redis.opsForValue().get("market:"+kind+":"+instrument));}
        catch(DataAccessException e){return Optional.empty();}
    }
    // Cache hit olsa bile sourceTime ve receivedAt gate'i tekrar uygulanır.
}