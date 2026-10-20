package com.crypto.simulationservice.infrastructure.messaging;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
/**
 * Sorumluluk: Dış sistem veya teknik sınırı uygular.
 * Okuma ipucu: Timeout, serialization, hata/fallback ve kaynak kapatma davranışını incele.
 */
@Service
public class MarketIngress {

    private final JdbcTemplate db;
    private final ObjectMapper json;
    public MarketIngress(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
    @Transactional public void accept(String payload)throws Exception{
        var event=json.readTree(payload);UUID id=UUID.fromString(event.required("eventId").asText());
        UUID instrument=UUID.fromString(event.required("payload").required("instrumentId").asText());
        // Şema validation bu parse'dan önce uygulanmalı; missing/type hatası atlanmaz.
        int added=db.update("INSERT INTO market_inputs(id,payload,received_at) VALUES (?,?::jsonb,now()) ON CONFLICT DO NOTHING",id,payload);
        if(added==0)return;
        // Input + bütün ilgili session iş kayıtları aynı TX; ortasında crash kayıp fan-out yaratmaz.
        db.update("""
   INSERT INTO pending_engine_inputs(session_id,input_id)
   SELECT DISTINCT session_id,? FROM (
    SELECT session_id FROM positions WHERE instrument_id=? AND state IN ('OPEN','LIQUIDATING')
    UNION SELECT session_id FROM orders WHERE instrument_id=? AND status IN ('NEW','EXIT_PENDING_DATA')
   ) affected ON CONFLICT DO NOTHING
   """,id,instrument,instrument);
    }
}