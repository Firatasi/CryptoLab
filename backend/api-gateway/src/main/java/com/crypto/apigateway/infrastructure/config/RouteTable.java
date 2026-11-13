package com.crypto.apigateway.infrastructure.config;
import java.util.Map;
/**
 * Sorumluluk: Teknik nesneleri ve Spring bağlantılarını kurar.
 * Okuma ipucu: Bean hangi portu karşılıyor ve hangi profile’da etkin sorularını cevapla.
 */
public final class RouteTable {
 private static final Map<String,String> SERVICES=Map.of("auth","identity-service","market","market-data-service","sim","simulation-service","strategy","strategy-service","reports","reporting-service","account","account-service");
 private RouteTable(){}
 public static String serviceId(String rawPath){
  if(rawPath==null||rawPath.isEmpty())throw new IllegalArgumentException("NO_ROUTE");
  if(rawPath.equals("/.well-known/jwks.json"))return "identity-service";
  if(hasUnsafePathToken(rawPath)||!rawPath.startsWith("/api/v1/"))throw new IllegalArgumentException("NO_ROUTE");
  String group=rawPath.substring(8).split("/",2)[0];String service=SERVICES.get(group);
  if(service==null)throw new IllegalArgumentException("NO_ROUTE");
  return service;
 }
 private static boolean hasUnsafePathToken(String p){
  if(p.contains("..")||p.contains("%")||p.contains("\\")||p.contains(";")||p.contains("//")||p.contains("@"))return true;
  for(int i=0;i<p.length();i++){char c=p.charAt(i);if(c<0x20||c==0x7f)return true;}
  return false;
 }
}

