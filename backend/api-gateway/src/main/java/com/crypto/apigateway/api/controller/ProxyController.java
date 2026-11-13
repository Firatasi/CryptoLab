package com.crypto.apigateway.api.controller;
import com.crypto.apigateway.infrastructure.client.GatewayTargetResolver;
import com.crypto.apigateway.infrastructure.config.GatewayRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
/**
 * Sorumluluk: HTTP isteğini uygulama akışına bağlar.
 * Okuma ipucu: DTO doğrulamasını, principal kaynağını ve HTTP cevabını takip et; SQL burada bulunmaz.
 */
@RestController
public class ProxyController {
 private final GatewayTargetResolver targets;
 private final GatewayRateLimiter rateLimiter;
 private final boolean trustForwardedHeaders;
 private final String frontendUrl;

 public ProxyController(GatewayTargetResolver targets) {
  this(targets, new GatewayRateLimiter(true, 60, 600), false, "");
 }

 @Autowired
 public ProxyController(
  GatewayTargetResolver targets,
  GatewayRateLimiter rateLimiter,
  @Value("${app.gateway.trust-forwarded-headers:false}") boolean trustForwardedHeaders,
  @Value("${app.gateway.frontend-url:}") String frontendUrl
 ) {
  this.targets = targets;
  this.rateLimiter = rateLimiter;
  this.trustForwardedHeaders = trustForwardedHeaders;
  this.frontendUrl = frontendUrl;
 }

 private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
 private static final Set<String> ALLOWED_METHODS=Set.of("GET","POST","PUT","PATCH","DELETE","OPTIONS","HEAD");
 private static final Set<String> REQUEST_HEADERS=Set.of("authorization","content-type","accept","idempotency-key","last-event-id","origin","x-csrf","cookie");
 private static final Set<String> RESPONSE_HEADERS=Set.of("content-type","cache-control","set-cookie","location","retry-after","x-accel-buffering","content-disposition","vary","www-authenticate");
 private static final Pattern SAFE_IP_PATTERN=Pattern.compile("^[0-9a-fA-F:.]{3,45}$");

 @GetMapping("/")
 public Map<String, Object> gatewayIndex() {
  return Map.of(
   "service", "cryptolab-api-gateway",
   "status", "UP",
   "frontendUrl", frontendUrl.isBlank() ? "/app" : frontendUrl,
   "healthUrl", "/actuator/health",
   "routes", List.of(
    "/api/v1/auth/**",
    "/api/v1/market/**",
    "/api/v1/sim/**",
    "/api/v1/strategy/**",
    "/api/v1/reports/**",
    "/api/v1/account/**",
    "/.well-known/jwks.json"
   )
  );
 }
 @RequestMapping({"/api/v1/**","/.well-known/jwks.json"}) public void proxy(HttpServletRequest in,HttpServletResponse out)throws Exception{
  String method=in.getMethod()==null?"":in.getMethod().toUpperCase();
  if(!ALLOWED_METHODS.contains(method)){
   writeError(out,405,"METHOD_NOT_ALLOWED","Bu HTTP yöntemi desteklenmiyor.",false);
   return;
  }
  String clientIp=resolveClientIp(in);
  String uri=in.getRequestURI();
  if(!rateLimiter.allow(clientIp,uri)){
   out.setHeader("Retry-After","60");
   writeError(out,429,"RATE_LIMITED","Çok fazla istek gönderildi. Lütfen kısa süre sonra tekrar deneyin.",true);
   return;
  }
  long limit=("POST".equals(method)&&"/api/v1/strategy/training/sources".equals(uri))?20L*1024*1024:1024*1024;
  if(in.getContentLengthLong()>limit){
   writeError(out,413,"PAYLOAD_TOO_LARGE","İstek boyutu izin verilen sınırı aşıyor.",false);
   return;
  }
  // Bu proxy bounded body buffer kullanır; sınırsız readAllBytes yok.
  byte[] body=in.getInputStream().readNBytes((int)limit+1);
  if(body.length>limit){
   writeError(out,413,"PAYLOAD_TOO_LARGE","İstek boyutu izin verilen sınırı aşıyor.",false);
   return;
  }
  var builder=HttpRequest.newBuilder(targets.resolve(uri,in.getQueryString()));
  boolean stream=uri.endsWith("/stream");
  if(!stream)builder.timeout(Duration.ofSeconds(15));
  for(String h:REQUEST_HEADERS){String value=in.getHeader(h);if(value!=null&&!hasCrLf(value))builder.header(h,value);}
  builder.header("X-Forwarded-For",clientIp);
  builder.header("X-Real-IP",clientIp);
  builder.header("X-Forwarded-Proto",resolveProto(in));
  builder.method(method,body.length==0?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body));
  // Tek send; POST'u otomatik uygulama retry'siyle yeniden gönderme.
  var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofInputStream());
  out.setStatus(response.statusCode());
  response.headers().map().forEach((name,values)->{
   String lower=name.toLowerCase();
   if(!RESPONSE_HEADERS.contains(lower))return;
   for(String v:values){
    if("location".equals(lower)&&!isSafeRelativeLocation(v))continue;
    out.addHeader(name,v);
   }
  });
  try(var content=response.body()){
   byte[] buffer=new byte[8192];int read;
   while((read=content.read(buffer))!=-1){out.getOutputStream().write(buffer,0,read);if(stream)out.flushBuffer();}
  }
 }

 private String resolveClientIp(HttpServletRequest in){
  String remote=normalizeIp(in.getRemoteAddr());
  if(trustForwardedHeaders){
   String xff=in.getHeader("X-Forwarded-For");
   if(xff!=null&&!xff.isBlank()){
    String first=normalizeIp(xff.split(",")[0]);
    if(first!=null)return first;
   }
   String xRealIp=normalizeIp(in.getHeader("X-Real-IP"));
   if(xRealIp!=null)return xRealIp;
  }
  return remote!=null?remote:"unknown";
 }

 private String resolveProto(HttpServletRequest in){
  if(in.isSecure())return "https";
  if(trustForwardedHeaders&&"https".equalsIgnoreCase(in.getHeader("X-Forwarded-Proto")))return "https";
  return "http";
 }

 private static String normalizeIp(String candidate){
  if(candidate==null)return null;
  String trimmed=candidate.trim();
  if(SAFE_IP_PATTERN.matcher(trimmed).matches())return trimmed;
  return null;
 }

 private static boolean hasCrLf(String value){
  return value.indexOf('\r')>=0||value.indexOf('\n')>=0||value.indexOf('\0')>=0;
 }

 private static boolean isSafeRelativeLocation(String value){
  return value!=null&&value.startsWith("/")&&!value.startsWith("//")&&!hasCrLf(value);
 }

 private static void writeError(HttpServletResponse out,int status,String code,String message,boolean retryable)throws IOException{
  out.setStatus(status);
  out.setContentType("application/json;charset=UTF-8");
  String payload="{\"code\":\""+code+"\",\"message\":\""+message+"\",\"traceId\":\""+UUID.randomUUID()+"\",\"retryable\":"+retryable+",\"details\":{}}";
  out.getOutputStream().write(payload.getBytes(StandardCharsets.UTF_8));
 }
}

