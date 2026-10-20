package com.crypto.apigateway.api.controller;
import com.crypto.apigateway.infrastructure.client.GatewayTargetResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import java.net.http.*;
import java.time.Duration;
import java.util.Set;
/**
 * Sorumluluk: HTTP isteğini uygulama akışına bağlar.
 * Okuma ipucu: DTO doğrulamasını, principal kaynağını ve HTTP cevabını takip et; SQL burada bulunmaz.
 */
@RestController
public class ProxyController {
 private final GatewayTargetResolver targets;
 public ProxyController(GatewayTargetResolver targets){this.targets=targets;}
 private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
 private static final Set<String> REQUEST_HEADERS=Set.of("authorization","content-type","accept","idempotency-key","last-event-id","origin","x-csrf","cookie");
 private static final Set<String> RESPONSE_HEADERS=Set.of("content-type","cache-control","set-cookie","location","retry-after","x-accel-buffering");
 @RequestMapping({"/api/v1/**","/.well-known/jwks.json"}) public void proxy(HttpServletRequest in,HttpServletResponse out)throws Exception{
  long limit=in.getRequestURI().startsWith("/api/v1/strategy/training/sources")?20L*1024*1024:1024*1024;
  if(in.getContentLengthLong()>limit){out.sendError(413);return;}
  // Bu öğretim proxy'si bounded body buffer kullanır; sınırsız readAllBytes yok.
  byte[] body=in.getInputStream().readNBytes((int)limit+1);
  if(body.length>limit){out.sendError(413);return;}
  var builder=HttpRequest.newBuilder(targets.resolve(in.getRequestURI(),in.getQueryString()));
  boolean stream=in.getRequestURI().endsWith("/stream");
  if(!stream)builder.timeout(Duration.ofSeconds(15));
  for(String h:REQUEST_HEADERS){String value=in.getHeader(h);if(value!=null)builder.header(h,value);}
  builder.method(in.getMethod(),body.length==0?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body));
  // Tek send; POST'u otomatik uygulama retry'siyle yeniden gönderme.
  var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofInputStream());
  out.setStatus(response.statusCode());
  response.headers().map().forEach((name,values)->{if(RESPONSE_HEADERS.contains(name.toLowerCase()))values.forEach(v->out.addHeader(name,v));});
  try(var content=response.body()){
   byte[] buffer=new byte[8192];int read;
   while((read=content.read(buffer))!=-1){out.getOutputStream().write(buffer,0,read);if(stream)out.flushBuffer();}
  }
 }
}

