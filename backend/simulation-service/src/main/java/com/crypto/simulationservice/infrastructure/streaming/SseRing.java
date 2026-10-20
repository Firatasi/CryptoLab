package com.crypto.simulationservice.infrastructure.streaming;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;
import java.io.IOException;
/**
 * Sorumluluk: Dış sistem veya teknik sınırı uygular.
 * Okuma ipucu: Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.
 */
public class SseRing {
 private record Event(String id,String name,Object data){}
 private final String epoch=UUID.randomUUID().toString();private long sequence;
 private final Deque<Event> ring=new ArrayDeque<>();private final Set<SseEmitter> clients=new HashSet<>();
 public synchronized SseEmitter subscribe(String lastId){
  var emitter=new SseEmitter(60_000L);
  emitter.onCompletion(()->remove(emitter));emitter.onTimeout(()->remove(emitter));emitter.onError(e->remove(emitter));
  if(lastId!=null&&!lastId.isBlank()){
   boolean found=false;
   for(var event:ring){if(found)send(emitter,event);if(event.id().equals(lastId))found=true;}
   if(!found)send(emitter,new Event(epoch+":"+sequence,"resync.required",Map.of("reason","CURSOR_EXPIRED")));
  }
  clients.add(emitter);return emitter;
 }
 public synchronized void publish(String name,Object data){
  var event=new Event(epoch+":"+(++sequence),name,data);ring.addLast(event);if(ring.size()>256)ring.removeFirst();
  for(var client:List.copyOf(clients))send(client,event);
 }
 public synchronized void heartbeat(){
  for(var client:List.copyOf(clients))try{client.send(SseEmitter.event().comment("heartbeat"));}catch(IOException e){clients.remove(client);client.complete();}
 }
 private void send(SseEmitter emitter,Event event){
  try{emitter.send(SseEmitter.event().id(event.id()).name(event.name()).data(event.data()));}
  catch(IOException e){clients.remove(emitter);emitter.complete();}
 }
 private synchronized void remove(SseEmitter e){clients.remove(e);}
}

