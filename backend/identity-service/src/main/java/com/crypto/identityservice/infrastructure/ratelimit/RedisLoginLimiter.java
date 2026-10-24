package com.crypto.identityservice.infrastructure.ratelimit;
import com.crypto.identityservice.application.security.Crypto;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import java.util.List;
/**
 * Sorumluluk: Dış sistem veya teknik sınırı uygular.
 * Okuma ipucu: Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.
 */
@Component
@org.springframework.context.annotation.Primary
public class RedisLoginLimiter implements com.crypto.identityservice.application.port.LoginLimiter {
 private final StringRedisTemplate redis;private final LocalLoginLimiter fallback;private final DefaultRedisScript<Long> script;
 public RedisLoginLimiter(StringRedisTemplate redis,LocalLoginLimiter fallback){
  this.redis=redis;this.fallback=fallback;script=new DefaultRedisScript<>();script.setLocation(new ClassPathResource("login-limit.lua"));script.setResultType(Long.class);
 }
 public void check(String key){
  Long count;
  try{count=redis.execute(script,List.of("login:"+Crypto.hash(key)),"60");}
  catch(DataAccessException e){fallback.check(key);return;}
  if(count==null){fallback.check(key);return;}
  if(count>10)throw new IllegalStateException("RATE_LIMITED");
 }
}

