package com.crypto.marketdataservice.infrastructure.client;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
/**
 * Sorumluluk: Dış sistem veya teknik sınırı uygular.
 * Okuma ipucu: Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.
 */
public final class PublicMarketHttp {
    public record RetryLater(int status,String retryAfter){}
    public static final class ProviderException extends RuntimeException {
        public final int status;public final String retryAfter;
        ProviderException(int status,String retryAfter){super("PROVIDER_HTTP_"+status);this.status=status;this.retryAfter=retryAfter;}
    }
    private final URI base;private final ObjectMapper mapper;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    public PublicMarketHttp(URI base,ObjectMapper mapper){
        if(!"https".equals(base.getScheme()))throw new IllegalArgumentException("HTTPS_REQUIRED");
        this.base=base;this.mapper=mapper;
    }
    public JsonNode get(String relative)throws Exception{
        URI target=base.resolve(relative);
        if(!base.getHost().equals(target.getHost()))throw new IllegalArgumentException("PROVIDER_HOST_CHANGED");
        var request=HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(5)).GET().build();
        var result=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
        try(var in=result.body()){
            if(result.statusCode()!=200)throw new ProviderException(result.statusCode(),result.headers().firstValue("Retry-After").orElse(""));
            byte[] bytes=in.readNBytes(4*1024*1024+1);
            if(bytes.length>4*1024*1024)throw new IllegalStateException("PAYLOAD_TOO_LARGE");
            return mapper.readTree(bytes);
        }
    }
}