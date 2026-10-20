package com.crypto.reportingservice.infrastructure.repository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
/**
 * Sorumluluk: Portun SQL veya okuma modeli kalıcılığını uygular.
 * Okuma ipucu: Parametreli SQL, transaction katılımı, owner/unique/lock kapsamını incele.
 */
@Service
public class LedgerProjection {
 private final JdbcTemplate db;private final ObjectMapper json;
 public LedgerProjection(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
 @Transactional public void accept(String raw)throws Exception{
  var event=json.readTree(raw);UUID id=UUID.fromString(event.required("eventId").asText());
  UUID session=UUID.fromString(event.required("sessionId").asText());long seq=event.required("engineSequence").asLong();
  db.update("INSERT INTO projection_checkpoints VALUES (?,0,?) ON CONFLICT DO NOTHING",session,Timestamp.from(Instant.EPOCH));
  long last=db.queryForObject("SELECT last_sequence FROM projection_checkpoints WHERE session_id=? FOR UPDATE",Long.class,session);
  Integer seen=db.queryForObject("SELECT count(*) FROM inbox WHERE consumer='reporting-simulation-v2' AND event_id=?",Integer.class,id);
  if(seen!=null&&seen>0)return;
  if(seq!=last+1)throw new IllegalStateException("PROJECTION_SEQUENCE_GAP");
  if("LedgerTransactionPosted".equals(event.required("eventType").asText())){
   var p=event.required("payload");UUID journal=UUID.fromString(p.required("journalTransactionId").asText());
   BigDecimal sum=BigDecimal.ZERO;
   for(var posting:p.required("postings"))sum=sum.add(new BigDecimal(posting.required("amount").asText()));
   if(sum.signum()!=0)throw new IllegalStateException("UNBALANCED_EVENT");
   int added=db.update("INSERT INTO ledger_projection VALUES (?,?,?,?,?,?::jsonb) ON CONFLICT(transaction_id) DO NOTHING",
    journal,session,id,seq,Timestamp.from(Instant.parse(p.required("effectiveAt").asText())),p.toString());
   if(added==1)for(var posting:p.required("postings")){
    UUID account=UUID.fromString(posting.required("accountId").asText());
    int metadata=db.queryForObject("SELECT count(*) FROM account_metadata WHERE account_id=? AND session_id=?",Integer.class,account,session);
    if(metadata!=1)throw new IllegalStateException("ACCOUNT_METADATA_MISSING");
    db.update("INSERT INTO account_projection VALUES (?,?) ON CONFLICT(account_id) DO UPDATE SET balance=account_projection.balance+EXCLUDED.balance",account,new BigDecimal(posting.required("amount").asText()));
   }
  }
  // Position events için para ekleme yok; timeline handler burada ayrı çağrılır.
  if("SessionValuationUpdated".equals(event.required("eventType").asText())){
   var p=event.required("payload");
   db.update("INSERT INTO equity_samples VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING",session,seq,Timestamp.from(Instant.parse(p.required("asOf").asText())),new BigDecimal(p.required("equity").asText()),new BigDecimal(p.required("unrealizedPnl").asText()),p.required("dataQuality").asText());
  }
  db.update("INSERT INTO inbox VALUES ('reporting-simulation-v2',?)",id);
  db.update("UPDATE projection_checkpoints SET last_sequence=?,as_of=? WHERE session_id=?",seq,Timestamp.from(Instant.parse(event.required("recordedAt").asText())),session);
 }
}

