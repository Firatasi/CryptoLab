package com.crypto.reportingservice.infrastructure.export;
/**
 * Sorumluluk: Dış sistem veya teknik sınırı uygular.
 * Okuma ipucu: Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.
 */
public final class CsvCell {
 private CsvCell(){}
 public static String encode(String value){
  String text=value==null?"":value;
  String trimmed=text.stripLeading();
  // Hücre tırnaklamak formül çalışmasını tek başına engellemez.
  if(!trimmed.isEmpty()&&"=+-@".indexOf(trimmed.charAt(0))>=0||text.startsWith("\t")||text.startsWith("\r"))text="'"+text;
  return "\""+text.replace("\"","\"\"")+"\"";
 }
}

